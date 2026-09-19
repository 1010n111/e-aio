package com.eaio.platform.infrastructure.cache;

import java.util.Optional;

import com.eaio.platform.api.CacheRegion;
import com.eaio.platform.application.cache.CacheManager;
import com.eaio.platform.domain.param.ParamItem;
import org.springframework.stereotype.Component;

/**
 * 参数二级缓存（L2，Redis；P1 册 3.1.3、3.6、4.6）。
 *
 * <p>键 {@code eaio:{env}:platform:param:{orgId}:{key}}，值是该 org 上下文下**生效行**的 JSON。
 * 只有"无用户上下文"的请求读它：用户级 override 按 3.1.5 只进 L1（否则键空间 = 用户数 × 键数）。
 *
 * <p><b>T6 起它只是 {@link CacheManager} 的薄适配器</b>（收敛而不是重写）：键前缀、TTL 抖动、
 * 空值占位（本区不启用）、互斥重建、失败降级都只有 CacheManager 一份实现，本类保留的是**参数区的键
 * 形状**（{@code orgId:key}）与"跨组织失效"这一条区域语义。L1 仍在 {@code ParamResolver} 里
 * （T4 已交付），所以这里只走 L2 原语，不再自己拼 Redis 命令。
 *
 * <p>失效是**前缀删除**（3.1.5 原文："失效用前缀删除 + 广播，而不是单键删除"）：改一个 SYSTEM 级参数会
 * 影响**所有组织**的键，而组织清单在 platform 侧不可枚举，故用 {@code *:{key}} 这种"后缀精确"的通配。
 */
@Component
public class ParamL2Cache {

    /** 跨组织的通配段（{@code eaio:{env}:platform:param:*:{key}}）。 */
    private static final String ALL_ORGS = "*:";

    private final CacheManager cache;

    public ParamL2Cache(CacheManager cache) {
        this.cache = cache;
    }

    /** 读；未命中、无 Redis、Redis 不可用都返回空（调用方回源）。 */
    public Optional<ParamItem> get(long orgId, String key) {
        return cache.l2Get(CacheRegion.PARAM, regionKey(orgId, key), ParamItem.class);
    }

    /** 写（TTL 30min ± 10%，防同一时刻大面积同时过期）。 */
    public void put(long orgId, String key, ParamItem row) {
        cache.l2Put(CacheRegion.PARAM, regionKey(orgId, key), row, 0);
    }

    /** 前缀删除：该键在**所有组织**下的 L2 变体（改 SYSTEM 级参数时只有这一种办法）。 */
    public void evict(String key) {
        cache.l2EvictByPattern(CacheRegion.PARAM, ALL_ORGS + requireKey(key));
    }

    /** 全量失效（{@code refresh()} 用：参数被绕过接口直接改库后的兜底）。 */
    public void evictAll() {
        cache.l2EvictByPattern(CacheRegion.PARAM, "*");
    }

    /** 取互斥重建锁（3.6.2 防击穿）：拿到的人回源，其他人等这位填缓存。 */
    public boolean tryLock(long orgId, String key) {
        return cache.tryLock(CacheRegion.PARAM, regionKey(orgId, key));
    }

    /** 等锁的持有者填好缓存（≤{@code timeoutMillis}）；没等到返回 {@code null}，调用方直接回源。 */
    public ParamItem awaitValue(long orgId, String key, long timeoutMillis) {
        return cache.awaitValue(CacheRegion.PARAM, regionKey(orgId, key), ParamItem.class, timeoutMillis).orElse(null);
    }

    /** 释放互斥重建锁。 */
    public void unlock(long orgId, String key) {
        cache.unlock(CacheRegion.PARAM, regionKey(orgId, key));
    }

    /** 区域内的键形状（{@code orgId:key}）：缓存操作传它，env 与区域前缀由 CacheManager 加。 */
    private static String regionKey(long orgId, String key) {
        return orgId + ":" + requireKey(key);
    }

    /** 空键会让相邻段粘连（{@code 1001:}）——这类静默错键在构造点直接拒绝（P1-2 册 3.8 的口径）。 */
    private static String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("参数键不得为空（L2 键 = 区域前缀 + orgId:key）");
        }
        return key;
    }
}
