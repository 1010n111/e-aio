package com.eaio.platform.api;

/**
 * 缓存键（P1 册 5.4 的 DTO 表逐字落地）：{@code region} 必填、{@code key} 必填且 ≤256，
 * <b>{@code key} 不含区域前缀</b>（前缀由 {@link CacheRegion} 提供）。
 *
 * <p>构造即校验（坏键在跨模块调用的入口就炸，而不是写进 Redis 后静默读不到）：区域为空、键为空、
 * 键超长、键自带区域前缀（3.6.4）一律 {@link IllegalArgumentException}。对外部输入（配置/参数里的
 * 区域文本）用 {@link CacheRegion#requireRegistered(String)} → 20051，两者是不同的错误面：
 * 前者是代码错误（调用方传错），后者是配置错误（文本无法解析）。
 */
public record CacheKey(CacheRegion region, String key) {

    /** 键长度上限（5.4：≤256）。 */
    public static final int MAX_KEY_LENGTH = 256;

    public CacheKey {
        if (region == null) {
            throw new IllegalArgumentException("缓存区不得为空（CacheRegion 枚举：未登记的区域编译期不可表达）");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("缓存键不得为空：region=" + region.code());
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("缓存键长度不得超过 " + MAX_KEY_LENGTH
                    + "：region=" + region.code() + "，长度=" + key.length());
        }
        CacheRegion.requirePlainKey(key);
    }

    /** 值键（含 env 与区域前缀）；前缀只由 {@link CacheRegion} 提供，调用方不得自己拼。 */
    public String redisKey(String env) {
        return region.redisKey(env, key);
    }
}
