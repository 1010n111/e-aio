package com.eaio.platform.api;

import java.util.Optional;

import com.eaio.common.exception.BusinessException;
import com.eaio.common.redis.RedisKeys;

/**
 * 缓存区登记表（P1 册 3.6.1 的表逐字落地；P1 册 2.3 裁决 P1-C2：本包只放 interface/record/enum）。
 *
 * <p><b>为什么必须是枚举</b>：3.6.1 的"未登记的区域编译期不可表达"只有枚举能做到——{@code CacheApi}
 * 的每个入参都收 {@link CacheRegion}/{@link CacheKey}，字符串形式的区域名根本传不进去。运行期才需要
 * 解析区域文本的只有一处：配置/参数里写的区域名（{@link #requireRegistered(String)}，失败抛 20051）。
 *
 * <p><b>四个字段都是登记值</b>：键前缀（{@code keyPrefix}）、L1/L2 TTL、是否写空值占位。
 * 键前缀是含 {@code {env}} 占位符的模板，替换即具体前缀（{@link #prefix(String)}）——值键、锁键、
 * 空值占位键都由这里派生，调用方不得自己拼串（P1-2 册 3.8 的"唯一构造点"）。
 *
 * <p><b>L1 必须短于 L2</b>（3.6.2 防雪崩的第二条）：L1 是本机副本，它比 L2 晚过期就意味着"L2 已按
 * TTL 丢掉的值还在本机命中"——多实例下表现为"同一时刻不同实例读到的值不同"。{@code CacheRegionTest}
 * 逐区断言这条不变量。
 *
 * <p><b>空值占位只对 3.6.1 标"是"的区域开放</b>（DICT/NOTIFY_TEMPLATE/FILE_META）：参数与告警规则的
 * 键是有限登记集合，键不存在多半是代码拼错，缓存这个"不存在"只会把错误藏起来（3.1.5 的既有口径）。
 */
public enum CacheRegion {

    /** 参数配置中心：{@code eaio:{env}:platform:param:}，L1 60s / L2 30min±10%，不写空值占位。 */
    PARAM("param", "eaio:{env}:platform:param:", 60, 1800, false),

    /** 数据字典（键是 typeCode）：{@code eaio:{env}:platform:dict:items:}，L1 60s / L2 30min±10%，空值占位 60s。 */
    DICT("dict", "eaio:{env}:platform:dict:items:", 60, 1800, true),

    /** 通知模板（键是模板编码）：{@code eaio:{env}:platform:notifyTemplate:}，L1 60s / L2 30min±10%，空值占位 60s。 */
    NOTIFY_TEMPLATE("notify_template", "eaio:{env}:platform:notifyTemplate:", 60, 1800, true),

    /** 文件元数据（键是 fileId）：{@code eaio:{env}:platform:file:meta:}，L1 30s / L2 5min，空值占位 60s。 */
    FILE_META("file_meta", "eaio:{env}:platform:file:meta:", 30, 300, true),

    /** 告警规则：{@code eaio:{env}:platform:alertRule:}，L1 60s / L2 10min，不写空值占位。 */
    ALERT_RULE("alert_rule", "eaio:{env}:platform:alertRule:", 60, 600, false);

    /** 键前缀模板里的环境段占位符（与 {@link RedisKeys} 的 {@code {env}} 段同名）。 */
    private static final String ENV_PLACEHOLDER = "{env}";

    /** 相对前缀之前的那一段（{@code eaio:{env}:}）：用于从模板里切出"键里不该出现的前缀"。 */
    private static final String ROOT_HEAD = RedisKeys.ROOT + ":" + ENV_PLACEHOLDER + ":";

    /** 失效广播载荷与锁/空值键里的区域段（3.1.4 已登记的 {@code param}/{@code dict} 逐字不变）。 */
    private final String code;

    private final String keyPrefix;

    private final int l1TtlSeconds;

    private final int l2TtlSeconds;

    private final boolean nullCaching;

    CacheRegion(String code, String keyPrefix, int l1TtlSeconds, int l2TtlSeconds, boolean nullCaching) {
        this.code = code;
        this.keyPrefix = keyPrefix;
        this.l1TtlSeconds = l1TtlSeconds;
        this.l2TtlSeconds = l2TtlSeconds;
        this.nullCaching = nullCaching;
    }

    /** 区域段（广播载荷 {@code region} 与 {@code platform:cache:null:{region}:{key}} 用）。 */
    public String code() {
        return code;
    }

    /** 键前缀模板（3.6.1 的表列，含 {@code {env}} 占位符）。 */
    public String keyPrefix() {
        return keyPrefix;
    }

    /** L1 TTL（秒）。 */
    public int l1TtlSeconds() {
        return l1TtlSeconds;
    }

    /** L2 TTL（秒）；实际写入时在其上叠加 ±10% 抖动（3.6.2），也可被 {@code platform.cache.*.l2-ttl-seconds} 覆盖。 */
    public int l2TtlSeconds() {
        return l2TtlSeconds;
    }

    /** 是否允许写空值占位（3.6.1 的最后一列：防穿透用）。 */
    public boolean nullCaching() {
        return nullCaching;
    }

    /** 具体 env 下的键前缀：模板里只有 {@code {env}} 一个变量。 */
    public String prefix(String env) {
        if (env == null || env.isBlank()) {
            throw new IllegalArgumentException("缓存键的 env 段不得为空（P1-2 册 3.8）");
        }
        return keyPrefix.replace(ENV_PLACEHOLDER, env);
    }

    /** 键里不该自带的那一段（如 {@code platform:param:}）：{@link CacheKey} 用它拒绝"二次前缀"。 */
    public String relativePrefix() {
        return keyPrefix.substring(ROOT_HEAD.length());
    }

    /** 值键（4.6：{@code eaio:{env}:platform:param:{orgId}:{key}} 等，按区域）。 */
    public String redisKey(String env, String key) {
        return prefix(env) + key;
    }

    /** 空值占位键（4.6：{@code eaio:{env}:platform:cache:null:{region}:{key}}）。 */
    public String nullKey(String env, String key) {
        return RedisKeys.of(env, "platform", "cache", "null", code, key);
    }

    /** 空值占位键前缀（批量失效要连占位一起清，否则"类型重建后 60s 内仍读不到"）。 */
    public String nullPrefix(String env) {
        return RedisKeys.of(env, "platform", "cache", "null", code) + ":";
    }

    /** 互斥重建锁键（4.6：{@code eaio:{env}:platform:lock:cache:{region}:{key}}）。 */
    public String lockKey(String env, String key) {
        return RedisKeys.of(env, "platform", "lock", "cache", code, key);
    }

    /**
     * 区域文本 → 区域（配置/参数里的文本是外部输入，故返回空而不是抛异常）。
     *
     * <p>同时接受 3.6.1 的区域段（{@code param}/{@code file_meta}）与枚举名（{@code PARAM}/{@code FILE_META}，
     * 忽略大小写）：配置里两种写法都出现过，解析歧义为 0，多一种写法就少一类"配置写了但没生效"。
     */
    public static Optional<CacheRegion> of(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String normalized = text.trim();
        for (CacheRegion region : values()) {
            if (normalized.equals(region.code) || normalized.equalsIgnoreCase(region.name())) {
                return Optional.of(region);
            }
        }
        return Optional.empty();
    }

    /** 同上，但解析失败抛 20051（7.1：{@code CACHE_REGION_UNKNOWN} 的适用场景就是"参数/配置中的区域文本"）。 */
    public static CacheRegion requireRegistered(String text) {
        return of(text).orElseThrow(() -> new BusinessException(PlatformErrorCode.CACHE_REGION_UNKNOWN,
                "未登记的缓存区：" + text + "（可用：" + registeredCodes() + "）"));
    }

    /** 已登记的区域段清单（错误消息与排障用）。 */
    public static String registeredCodes() {
        StringBuilder codes = new StringBuilder();
        for (CacheRegion region : values()) {
            if (codes.length() > 0) {
                codes.append('/');
            }
            codes.append(region.code);
        }
        return codes.toString();
    }

    /**
     * 拒绝"键里带着区域前缀"（3.6.4：{@code CacheKey.region} 与键前缀不匹配 → 拒绝）。
     *
     * <p>判据是"键以任一区域的相对前缀开头"（{@code platform:param:} 等），外加整键
     * {@code eaio:} 开头：两种写法都会让最终键变成 {@code ...:param:param:x} 这类静默错键——
     * 不报错，只是永远读不到、永远击穿。
     */
    public static void requirePlainKey(String key) {
        if (key.startsWith(RedisKeys.ROOT + ":")) {
            throw new IllegalArgumentException("缓存键不得是完整的 Redis 键（不含 env 与区域前缀）：key=" + key);
        }
        for (CacheRegion region : values()) {
            if (key.startsWith(region.relativePrefix())) {
                throw new IllegalArgumentException("缓存键不得自带区域前缀 " + region.relativePrefix()
                        + "（前缀由 CacheRegion 提供）：region=" + region.code + "，key=" + key);
            }
        }
    }
}
