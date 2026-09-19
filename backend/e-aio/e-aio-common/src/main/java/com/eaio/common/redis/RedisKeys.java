package com.eaio.common.redis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Redis 键的唯一构造点（P0 册 4.4、P1-2 册 2.7）。
 *
 * <p>键规范 {@code eaio:{env}:{biz}:{id}}，例如 {@code eaio:prod:cache:crm:customer:1001}（P0 册 4.4）。
 * 业务代码**禁止**散落拼串：拼错的键不会报错，只会静默读到空值、静默击穿缓存，这类缺陷只能靠
 * "唯一构造点"避免。
 *
 * <p><b>各模块的具体键</b>以该模块自己的键表为准（总册 2.2 的口径），用 {@link #of} 构造——
 * 例如 platform 的 {@code eaio:{env}:platform:cache:{region}:{key}}、{@code eaio:{env}:platform:param:{orgId}:{key}}
 * （P1-3 册 7.1）。本类不预置模块专属方法：键表先于方法落地，先建方法只会锁死一个与键表不一致的格式
 * （本批曾短暂实现 {@code cache(env, namespace, key)} 的 {@code eaio:{env}:cache:{ns}:{key}} 顺序，
 * 与 platform 键表的"模块段在前"相反，已删除）。
 *
 * <p>环境段 {@code {env}} 由调用方显式传入（不读全局状态、不读 Spring 配置）：同一个进程可能要以
 * 不同环境前缀访问同一 Redis（灰度/迁移），静态可变的环境变量会让这种用例无法表达，也让单测必须
 * 依赖类初始化顺序。装配层（{@code eaio.env} 配置/环境变量 {@code EAIO_ENV}）负责把值传进来。
 */
public final class RedisKeys {

    /** 根前缀：与 P0 幂等键前缀一致（不得改动，否则已存在的幂等占位会全部失效）。 */
    public static final String ROOT = "eaio";

    private static final char SEPARATOR = ':';

    private RedisKeys() {
    }

    /**
     * 通用构造：{@code eaio:{env}:{segment...}}。
     *
     * <p>段内允许出现 {@code ':'}（如 {@code platform:param}、组织 ID 前缀），因此不做字符白名单。
     */
    public static String of(String env, String... segments) {
        StringBuilder key = new StringBuilder(ROOT).append(SEPARATOR).append(require(env, "env"));
        if (segments != null) {
            for (String segment : segments) {
                key.append(SEPARATOR).append(require(segment, "segment"));
            }
        }
        return key.toString();
    }

    /**
     * 幂等占位键：{@code eaio:{env}:idem:{sha256(uri + 幂等键)}}（P0 册 3.2.5）。
     *
     * <p>用摘要而非原文：幂等键由调用方生成，可能是任意可打印字符，直接进键名会与分隔符冲突。
     */
    public static String idempotency(String env, String uri, String idempotencyKey) {
        String material = require(uri, "uri") + require(idempotencyKey, "idempotencyKey");
        return of(env, "idem", sha256(material));
    }

    /**
     * 环境段 {@code {env}} 的**取值规则**（唯一实现）：显式配置（{@code eaio.env} / {@code EAIO_ENV}）
     * 优先，其次第一个激活 profile，最后 {@code default}。
     *
     * <p>规则集中在这里而不是各装配类各写一遍：{@code eaio:{env}:platform:...} 与 {@code eaio:{env}:idem:...}
     * 是两套键空间，两处规则一旦分叉，同一进程会用两个 env 前缀写同一个 Redis——不报错，只在排查时才发现。
     * 参数仍由调用方显式传入（本类不读全局状态、不读 Spring 配置，故保持零 Spring 依赖）。
     */
    public static String envOf(String configuredEnv, String[] activeProfiles) {
        if (configuredEnv != null && !configuredEnv.isBlank()) {
            return configuredEnv.trim();
        }
        if (activeProfiles != null && activeProfiles.length > 0
                && activeProfiles[0] != null && !activeProfiles[0].isBlank()) {
            return activeProfiles[0];
        }
        return "default";
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
    }

    /**
     * 空段会让相邻段粘连成另一个合法键（{@code cache::x} 与 {@code cache:x} 混淆），
     * 这类静默错键比异常更难查，所以在构造点直接拒绝。
     */
    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Redis 键的 " + name + " 不得为空（键由 RedisKeys 唯一构造）");
        }
        return value;
    }
}
