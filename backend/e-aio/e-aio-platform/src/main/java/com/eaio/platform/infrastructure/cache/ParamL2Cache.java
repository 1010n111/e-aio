package com.eaio.platform.infrastructure.cache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import com.eaio.common.redis.RedisKeys;
import com.eaio.common.redis.RedisKit;
import com.eaio.common.redis.RedisUnavailableException;
import com.eaio.platform.domain.param.ParamItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 参数二级缓存（L2，Redis；P1 册 3.1.3、4.6）。
 *
 * <p>键 {@code eaio:{env}:platform:param:{orgId}:{key}}，值是该 org 上下文下**生效行**的 JSON。
 * 只有"无用户上下文"的请求读它：用户级 override 按 3.1.5 只进 L1（否则键空间 = 用户数 × 键数）。
 *
 * <p><b>失效是前缀删除</b>（3.1.5 原文："失效用前缀删除 + 广播，而不是单键删除"）：改一个
 * SYSTEM 级参数会影响**所有组织**的键，而组织清单在 platform 侧不可枚举。{@link RedisKit}
 * 的冻结方法集（P0 册 4.4）没有 scan/前缀删除，因此这里对"按前缀清理"这一件事直接用
 * {@link StringRedisTemplate}——**正常读写仍走 RedisKit 门面**，不绕过它。
 *
 * <p>Redis 不可用（连不上）时：读=当作未命中、写/删=记 WARN 后放弃（L1 的 60s TTL 是兜底，
 * 与 P1 册 3.1.5 的"Redis 不可用时退化为 L1 TTL 兜底"一致）。**不做静默降级为放行**：
 * 缓存降级只影响新鲜度，不影响"能不能读"。
 */
@Component
public class ParamL2Cache {

    private static final Logger log = LoggerFactory.getLogger(ParamL2Cache.class);

    private final ObjectProvider<RedisKit> redisKit;
    private final ObjectProvider<StringRedisTemplate> redisTemplates;
    private final String env;
    private final Duration ttl;

    public ParamL2Cache(ObjectProvider<RedisKit> redisKit, ObjectProvider<StringRedisTemplate> redisTemplates,
            Environment environment) {
        this.redisKit = redisKit;
        this.redisTemplates = redisTemplates;
        this.env = environmentOf(environment);
        this.ttl = Duration.ofSeconds(environment.getProperty("eaio.cache.redis.ttl-seconds", Long.class, 1800L));
    }

    /** 读；未命中、无 Redis、Redis 不可用都返回空（调用方回源）。 */
    public Optional<ParamItem> get(long orgId, String key) {
        RedisKit kit = redisKit.getIfAvailable();
        if (kit == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(kit.get(keyOf(orgId, key), ParamItem.class));
        } catch (RedisUnavailableException e) {
            log.warn("L2 缓存不可用，参数 {} 回源数据库；traceId 由入站链路记录。原因：{}", key, e.getMessage());
            return Optional.empty();
        }
    }

    /** 写（TTL 30min ± 10%，防同一时刻大面积同时过期）。 */
    public void put(long orgId, String key, ParamItem row) {
        RedisKit kit = redisKit.getIfAvailable();
        if (kit == null) {
            return;
        }
        try {
            kit.set(keyOf(orgId, key), row, jitter(ttl));
        } catch (RedisUnavailableException e) {
            log.warn("L2 缓存写入跳过（Redis 不可用）：{}", key);
        }
    }

    /** 前缀删除：该键在**所有组织**下的 L2 变体（改 SYSTEM 级参数时只有这一种办法）。 */
    public void evict(String key) {
        deleteByPattern(prefix() + ":*:" + key);
    }

    /** 全量失效（{@code refresh()} 用：参数被绕过接口直接改库后的兜底）。 */
    public void evictAll() {
        deleteByPattern(prefix() + ":*");
    }

    private void deleteByPattern(String pattern) {
        StringRedisTemplate template = redisTemplates.getIfAvailable();
        if (template == null) {
            return;
        }
        try (Cursor<String> cursor = template.scan(ScanOptions.scanOptions().match(pattern).count(500).build())) {
            List<String> batch = new ArrayList<>();
            while (cursor.hasNext()) {
                batch.add(cursor.next());
                if (batch.size() >= 500) {
                    template.delete(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                template.delete(batch);
            }
        } catch (RuntimeException e) {
            log.warn("L2 前缀失效失败（pattern={}）：{}；L1 已清、L2 靠 TTL 兜底", pattern, e.getMessage());
        }
    }

    private String keyOf(long orgId, String key) {
        return RedisKeys.of(env, "platform", "param", String.valueOf(orgId), key);
    }

    private String prefix() {
        return RedisKeys.of(env, "platform", "param");
    }

    private static Duration jitter(Duration base) {
        double factor = 0.9 + ThreadLocalRandom.current().nextDouble() * 0.2;
        return Duration.ofMillis(Math.max(1L, (long) (base.toMillis() * factor)));
    }

    /**
     * Redis 键的 {@code {env}} 段：优先 {@code EAIO_ENV}（宽松绑定为 {@code eaio.env}，P1 册 7.2），
     * 未设置时退回"首个激活 profile"（P0 的既有口径），再退回 {@code default}。
     */
    private static String environmentOf(Environment environment) {
        String configured = environment.getProperty("eaio.env");
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        String[] profiles = environment.getActiveProfiles();
        return profiles.length == 0 ? "default" : profiles[0];
    }
}
