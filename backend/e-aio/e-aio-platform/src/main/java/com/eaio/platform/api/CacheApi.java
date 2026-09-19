package com.eaio.platform.api;

import java.util.Optional;

/**
 * 两级缓存（P1 册 5.4 逐字）：region 是枚举（未登记区域编译期不可表达）；evict 失败只记日志不抛（3.6.3）。
 *
 * <p>读路径：L1（本机）→ L2（Redis）；Redis 不可用时 {@link #get} 返回空、{@link #put}/{@link #evict}
 * 记错误并累加错误指标（{@code platform.cache.op.error}），<b>都不抛</b>——缓存降级只影响新鲜度，
 * 不影响"能不能读"（3.1.5/3.6.3）。
 *
 * <p>单值超过 64KB 的写入被拒绝（3.6.4：拒绝入缓存 + WARN，业务仍返回正确结果）；区域与键前缀不匹配
 * 的调用在 {@link CacheKey} 构造处即被拒绝（{@code IllegalArgumentException}）。
 */
public interface CacheApi {

    /** 读：L1 命中即返回；否则读 L2（未命中、Redis 不可用都是空）。 */
    <T> Optional<T> get(CacheKey key, Class<T> type);

    /** 写：ttlSeconds ≤ 0 表示用 region 默认 TTL；L1 的 TTL 不会长于 L2（3.6.2）。 */
    void put(CacheKey key, Object value, int ttlSeconds);

    /** 失效单键：删 L2（值 + 空值占位）+ 清本机 L1 + 广播其他实例。 */
    void evict(CacheKey key);

    /** 按前缀批量失效（SCAN 游标，不用 KEYS）：{@code keyPrefix} 为空串表示该区域全量。 */
    void evictByPrefix(CacheRegion region, String keyPrefix);
}
