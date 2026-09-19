package com.eaio.platform.application.param;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.eaio.platform.application.cache.CacheManager;
import com.eaio.platform.domain.param.ParamContext;
import com.eaio.platform.domain.param.ParamItem;
import com.eaio.platform.domain.param.ParamLevel;
import com.eaio.platform.domain.param.ParamOverlayResolver;
import com.eaio.platform.domain.param.ParamValueCipher;
import com.eaio.platform.infrastructure.cache.ParamL2Cache;
import com.eaio.platform.infrastructure.persistence.ParamStore;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 参数读路径：分级覆盖 + 两级缓存（P1 册 3.1.2、3.1.3）。
 *
 * <p>取值链：L1（Caffeine，本机，键含 orgId+userId）→ L2（Redis，仅"无用户上下文"的请求读）
 * → DB（一次查询取该键的 0..3 行候选）→ Spring 配置（约定属性名 {@code eaio.param.<key>}）
 * → 调用方给的默认值（{@code get*}）或"键未定义"（{@link #resolve} 空）。
 *
 * <p><b>两处与图示的显式差异（都是为了正确性，写在代码里而不是留给读者猜）</b>：
 * <ol>
 *   <li>带用户上下文的请求**不读 L2**：3.1.5 规定用户级 override 只进 L1（不进 L2，避免键空间爆炸），
 *       而"该用户是否有 override"只有查库才知道；若无脑读 L2 会在用户有 override 时返回组织级旧值。
 *       代价是这类请求在 L1 未命中时多一次 DB 往返（参数表 200–2000 行、走
 *       {@code idx_param_level_owner}，DD 3.1.3 给的回源预算是 &lt;20ms）。</li>
 *   <li><b>未定义键不写空值占位</b>（3.1.5 原文）：参数键是有限集合（7.2 全表登记），不存在的键
 *       多半是代码拼错——把拼错的键缓存 60s 会把错误藏起来（3.6.1 的 PARAM 区"空值占位 = 否"）。</li>
 * </ol>
 *
 * <p><b>T6 起 "无用户上下文"的冷读多了一层互斥重建</b>（3.6.2 防击穿）：L2 未命中时先抢 SETNX 锁
 * （{@code l2.tryLock}），只有抢到的人回源，其余人在 1s 内轮询等这份缓存、超时直接回源。抢锁只发生在
 * {@code userId == 0} 这条路径上——带用户上下文的请求不读 L2（见上），也就没有"击穿 L2"这回事。
 *
 * <p><b>跨实例失效广播</b>（Redis Pub/Sub {@code eaio:{env}:platform:ch:invalidation}）不在这里，但
 * "三件事"是配套的：本类的 {@link #invalidate(String)}/{@link #invalidateAll()} 负责清本机 L1 与 L2，
 * 改值方（{@code ParamInvalidationListener}，{@code @TransactionalEventListener(AFTER_COMMIT)}）
 * 另外删 L2 前缀并把失效消息广播出去，其他实例的订阅方（{@code ParamInvalidationSubscriber}）
 * 收到后再调本类的 invalidate——三件事都做齐，其他实例才不会读到旧值（只做广播会漏发布方自己，
 * 只做本机会让其他实例最长读 60s 旧值，L1 TTL 只是兜底）。
 */
@Component
public class ParamResolver {

    private static final Logger log = LoggerFactory.getLogger(ParamResolver.class);

    /** L1 最大条目数：{@code platform.cache.local.max-size} 的默认值（7.2 标"否=重启生效"）。 */
    private static final long L1_MAX_SIZE = 10_000L;

    /** Spring 配置层的属性名前缀：{@code eaio.param.<key>}（设计册只写了"DB → Spring 配置 → 代码默认"链路，未定名，这里是落地约定）。 */
    private static final String YAML_PREFIX = "eaio.param.";

    private final ParamStore store;
    private final ParamL2Cache l2;
    private final ParamCryptoKeys cryptoKeys;
    private final Environment environment;
    private final Cache<String, ResolvedParam> l1;
    private final boolean l1Enabled;

    public ParamResolver(ParamStore store, ParamL2Cache l2, ParamCryptoKeys cryptoKeys, Environment environment) {
        this.store = store;
        this.l2 = l2;
        this.cryptoKeys = cryptoKeys;
        this.environment = environment;
        this.l1Enabled = environment.getProperty("eaio.cache.local.enabled", Boolean.class, true);
        Duration l1Ttl = Duration.ofSeconds(environment.getProperty("eaio.param.cache-ttl-seconds", Long.class, 60L));
        this.l1 = Caffeine.newBuilder().maximumSize(L1_MAX_SIZE).expireAfterWrite(l1Ttl).build();
    }

    /** 解析键的生效值；键未定义返回空（{@code get(key)} 据此抛 20001）。 */
    public Optional<ResolvedParam> resolve(String key, ParamContext context) {
        String cacheKey = cacheKey(context, key);
        if (l1Enabled) {
            ResolvedParam hit = l1.getIfPresent(cacheKey);
            if (hit != null) {
                return Optional.of(hit);
            }
        }
        boolean owner = false;
        if (context.userId() == 0L) {
            Optional<ParamItem> cached = l2.get(context.orgId(), key);
            if (cached.isPresent()) {
                return Optional.of(cache(cacheKey, toResolved(cached.get())));
            }
            owner = l2.tryLock(context.orgId(), key);
            if (!owner) {
                ParamItem rebuilt = l2.awaitValue(context.orgId(), key, CacheManager.LOCK_WAIT_MILLIS);
                if (rebuilt != null) {
                    return Optional.of(cache(cacheKey, toResolved(rebuilt)));
                }
            }
        }
        try {
            return load(key, context, cacheKey);
        } finally {
            if (owner) {
                l2.unlock(context.orgId(), key);
            }
        }
    }

    /** 回源与回填（3.1.2 取值链的后半段）：DB → 生效值 → L2/L1；未定义键走 Spring 配置兜底。 */
    private Optional<ResolvedParam> load(String key, ParamContext context, String cacheKey) {
        List<ParamItem> candidates = store.rowsByKey(key);
        Optional<ParamItem> effective = ParamOverlayResolver.effective(candidates, context);
        if (effective.isPresent()) {
            ParamItem row = effective.get();
            ResolvedParam resolved = toResolved(row);
            if (!ParamLevel.USER.name().equals(row.getParamLevel())) {
                // 用户级值不进 L2（3.1.5）；组织级/系统级值对所有"无用户上下文"的请求都成立
                l2.put(context.orgId(), key, row);
            }
            return Optional.of(cache(cacheKey, resolved));
        }
        String yaml = environment.getProperty(YAML_PREFIX + key);
        if (yaml != null) {
            return Optional.of(cache(cacheKey, new ResolvedParam(key, yaml, "YAML", null, true, null)));
        }
        return Optional.empty();
    }

    /** 解析键的生效值；未定义时用调用方默认值（{@code getInt/getBool/getString} 的语义）。 */
    public ResolvedParam resolveOrDefault(String key, String defaultValue, ParamContext context) {
        return resolve(key, context).orElseGet(() -> ResolvedParam.ofDefault(key, defaultValue));
    }

    /** 清缓存并重载：本机 L1 的该键所有维度变体 + L2 的全部组织变体。 */
    public void invalidate(String key) {
        String suffix = ":" + key;
        l1.asMap().keySet().removeIf(cacheKey -> cacheKey.endsWith(suffix));
        l2.evict(key);
        log.debug("参数缓存已失效：key={}", key);
    }

    /** 本 region 全量失效（{@code refresh()} 无参：DBA 绕过接口直接改库后的兜底）。 */
    public void invalidateAll() {
        l1.invalidateAll();
        l2.evictAll();
        log.info("参数缓存已全量失效（L1 + L2）");
    }

    /** 键的候选行（管理页用；**不过缓存**——管理页要看到"刚被谁改过"，缓存会让它看到旧数据）。 */
    public List<ParamItem> candidates(String key) {
        return store.rowsByKey(key);
    }

    /** 分组全量行（管理页/批量读取）。 */
    public List<ParamItem> rowsByGroup(String paramGroup) {
        return store.rowsByGroup(paramGroup);
    }

    /** 全量行（缓存预热/导出）。 */
    public List<ParamItem> allRows() {
        return store.allRows();
    }

    private ResolvedParam cache(String cacheKey, ResolvedParam resolved) {
        if (l1Enabled) {
            l1.put(cacheKey, resolved);
        }
        return resolved;
    }

    /** L1 键 = orgId:userId:key（上下文完整，故用户级 override 也安全地只在本机缓存）。 */
    private static String cacheKey(ParamContext context, String key) {
        return context.orgId() + ":" + context.userId() + ":" + key;
    }

    /** 行 → 解析结果：SECRET 类在这里解密（内部调用方要真值；给管理端的脱敏在 DTO 映射处）。 */
    private ResolvedParam toResolved(ParamItem row) {
        String value = row.isEncrypted()
                ? ParamValueCipher.decrypt(row.getParamValue(), cryptoKeys.key())
                : row.getParamValue();
        return new ResolvedParam(row.getParamKey(), value, "DB", row.getParamLevel(), row.isHotReload(), row);
    }
}
