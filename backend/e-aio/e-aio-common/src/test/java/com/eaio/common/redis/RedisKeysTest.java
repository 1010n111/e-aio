package com.eaio.common.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Redis 键构造点（P0 册 4.4 键规范）。
 *
 * <p>与 P0 幂等链路的兼容性是**逐字**要求：键变了，已存在的幂等占位就全部失效（同一请求会被执行两次）。
 */
class RedisKeysTest {

    @Test
    @DisplayName("通用键：eaio:{env}:{segment...}（段内允许冒号，如 platform:param）")
    void buildsGeneralKey() {
        assertThat(RedisKeys.of("prod", "cache", "platform:param", "1001"))
                .isEqualTo("eaio:prod:cache:platform:param:1001");
        assertThat(RedisKeys.of("dev")).isEqualTo("eaio:dev");
    }

    @Test
    @DisplayName("幂等键与 P0 口径逐字一致：eaio:{env}:idem:{sha256(uri+key)}")
    void idempotencyKeyMatchesP0Formula() throws Exception {
        String expected = "eaio:prod:idem:" + sha256("/api/platform/param/Add" + "key-1");

        assertThat(RedisKeys.idempotency("prod", "/api/platform/param/Add", "key-1")).isEqualTo(expected);
    }

    @Test
    @DisplayName("幂等键：同输入稳定，换环境/uri/键任一都不同（不同请求不得撞键）")
    void idempotencyKeyIsStableAndDistinct() {
        String baseline = RedisKeys.idempotency("prod", "/api/x", "k1");

        assertThat(RedisKeys.idempotency("prod", "/api/x", "k1")).isEqualTo(baseline);
        assertThat(RedisKeys.idempotency("prod", "/api/x", "k2")).isNotEqualTo(baseline);
        assertThat(RedisKeys.idempotency("prod", "/api/y", "k1")).isNotEqualTo(baseline);
        assertThat(RedisKeys.idempotency("dev", "/api/x", "k1")).isNotEqualTo(baseline);
        assertThat(baseline).hasSize("eaio:prod:idem:".length() + 64);
    }

    @Test
    @DisplayName("通用键可表达模块键表的布局（模块段在前，如 platform 的 cache/param 键）")
    void buildsModuleKeyLayout() {
        assertThat(RedisKeys.of("prod", "platform", "cache", "param", "7"))
                .isEqualTo("eaio:prod:platform:cache:param:7");
    }

    @Test
    @DisplayName("空段直接拒绝：cache::x 与 cache:x 会静默混淆成不同键")
    void rejectsBlankSegments() {
        assertThatThrownBy(() -> RedisKeys.of("prod", "cache", " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RedisKeys.of(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RedisKeys.idempotency("prod", "", "k")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RedisKeys.idempotency("prod", "/api/x", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("env 取值规则（唯一实现）：eaio.env 优先（并去空白）> 首个激活 profile > default")
    void envResolutionRule() {
        assertThat(RedisKeys.envOf(" prod ", new String[] {"it"})).as("显式配置优先，且去首尾空白").isEqualTo("prod");
        assertThat(RedisKeys.envOf(null, new String[] {"it"})).isEqualTo("it");
        assertThat(RedisKeys.envOf("  ", new String[] {"it"})).as("空白配置等于没配").isEqualTo("it");
        assertThat(RedisKeys.envOf(null, new String[0])).isEqualTo("default");
        assertThat(RedisKeys.envOf(null, null)).isEqualTo("default");
        assertThat(RedisKeys.envOf(null, new String[] {null, "second"})).as("首个 profile 为空也不能拼出空 env 段").isEqualTo("default");
    }

    @Test
    @DisplayName("不做隐式改写：段内空白原样保留（键是调用方与运维都能看到的契约）")
    void doesNotRewriteSegments() {
        assertThat(RedisKeys.of("prod", " x ")).isEqualTo("eaio:prod: x ");
    }

    /** 独立实现一遍 P0 公式，避免"用被测实现算期望值"的自证循环。 */
    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
