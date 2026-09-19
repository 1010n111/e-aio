package com.eaio.platform.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.Optional;

import com.eaio.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 缓存区登记表的单测（P1 册 3.6.1、4.6、7.1、5.4）。
 *
 * <p>钉住的是**登记面**：五个区域的四个登记值逐字、L1 必短于 L2（3.6.2 防雪崩）、键/锁/空值键的
 * 三段式键空间（4.6）、区域文本解析失败给 20051、以及 {@link CacheApi} 的签名只收枚举（3.6.1 的
 * "未登记的区域编译期不可表达"）。
 */
class CacheRegionTest {

    @Test
    @DisplayName("3.6.1 的表逐字：五个区域、键前缀模板、L1/L2 TTL、空值占位")
    void regionTableMatchesDesign() {
        assertThat(CacheRegion.values()).extracting(CacheRegion::code)
                .containsExactly("param", "dict", "notify_template", "file_meta", "alert_rule");

        assertRegion(CacheRegion.PARAM, "eaio:{env}:platform:param:", 60, 1800, false);
        assertRegion(CacheRegion.DICT, "eaio:{env}:platform:dict:items:", 60, 1800, true);
        assertRegion(CacheRegion.NOTIFY_TEMPLATE, "eaio:{env}:platform:notifyTemplate:", 60, 1800, true);
        assertRegion(CacheRegion.FILE_META, "eaio:{env}:platform:file:meta:", 30, 300, true);
        assertRegion(CacheRegion.ALERT_RULE, "eaio:{env}:platform:alertRule:", 60, 600, false);
    }

    @Test
    @DisplayName("具体 env 下的前缀与 4.6 键表逐字一致（IT 里按这些字面量查 Redis）")
    void prefixesMatchKeyTable() {
        assertThat(CacheRegion.PARAM.prefix("it")).isEqualTo("eaio:it:platform:param:");
        assertThat(CacheRegion.DICT.prefix("it")).isEqualTo("eaio:it:platform:dict:items:");
        assertThat(CacheRegion.DICT.redisKey("it", "platform_param_level"))
                .isEqualTo("eaio:it:platform:dict:items:platform_param_level");
        assertThat(CacheRegion.PARAM.redisKey("it", "1001:platform.file.max-size"))
                .isEqualTo("eaio:it:platform:param:1001:platform.file.max-size");
        assertThat(CacheRegion.FILE_META.nullKey("it", "9")).isEqualTo("eaio:it:platform:cache:null:file_meta:9");
        assertThat(CacheRegion.DICT.nullPrefix("it")).isEqualTo("eaio:it:platform:cache:null:dict:");
        assertThat(CacheRegion.PARAM.lockKey("it", "1001:k")).isEqualTo("eaio:it:platform:lock:cache:param:1001:k");
        assertThatThrownBy(() -> CacheRegion.PARAM.prefix(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("L1 必须短于 L2（3.6.2 防雪崩的第二条：L1 晚过期 = 多实例读到不同值）")
    void l1AlwaysExpiresBeforeL2() {
        for (CacheRegion region : CacheRegion.values()) {
            assertThat(region.l1TtlSeconds()).as("region=%s", region.code())
                    .isLessThan(region.l2TtlSeconds());
        }
    }

    @Test
    @DisplayName("键里不得自带区域前缀（3.6.4：否则会写出 ...:param:param:x 这类静默错键）")
    void plainKeyRule() {
        CacheRegion.requirePlainKey("platform.file.max-size");
        CacheRegion.requirePlainKey("1001:platform.file.max-size");
        CacheRegion.requirePlainKey("");

        assertThatThrownBy(() -> CacheRegion.requirePlainKey("eaio:it:platform:param:1001:k"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("完整的 Redis 键");
        assertThatThrownBy(() -> CacheRegion.requirePlainKey("platform:dict:items:it_status"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("区域前缀");
    }

    @Test
    @DisplayName("相对前缀（键里不该出现的段）与模板一一对应")
    void relativePrefixes() {
        assertThat(CacheRegion.PARAM.relativePrefix()).isEqualTo("platform:param:");
        assertThat(CacheRegion.DICT.relativePrefix()).isEqualTo("platform:dict:items:");
        assertThat(CacheRegion.NOTIFY_TEMPLATE.relativePrefix()).isEqualTo("platform:notifyTemplate:");
        assertThat(CacheRegion.FILE_META.relativePrefix()).isEqualTo("platform:file:meta:");
        assertThat(CacheRegion.ALERT_RULE.relativePrefix()).isEqualTo("platform:alertRule:");
    }

    @Test
    @DisplayName("区域文本解析：区域段与枚举名都认；解析不了给 20051（7.1 的 CACHE_REGION_UNKNOWN）")
    void regionTextResolution() {
        assertThat(CacheRegion.of("param")).contains(CacheRegion.PARAM);
        assertThat(CacheRegion.of(" file_meta ")).contains(CacheRegion.FILE_META);
        assertThat(CacheRegion.of("DICT")).contains(CacheRegion.DICT);
        assertThat(CacheRegion.of("Alert_Rule")).contains(CacheRegion.ALERT_RULE);
        assertThat(CacheRegion.of(null)).isEmpty();
        assertThat(CacheRegion.of("unknown-region")).isEmpty();

        assertThat(CacheRegion.requireRegistered("file_meta")).isEqualTo(CacheRegion.FILE_META);
        assertThatThrownBy(() -> CacheRegion.requireRegistered("unknown-region"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(20051));
        assertThat(CacheRegion.registeredCodes()).isEqualTo("param/dict/notify_template/file_meta/alert_rule");
    }

    @Test
    @DisplayName("CacheApi 的签名只收 CacheKey/CacheRegion（未登记区域编译期不可表达）")
    void cacheApiOnlyAcceptsRegisteredRegions() throws Exception {
        assertThat(CacheApi.class.getMethod("get", CacheKey.class, Class.class)).isNotNull();
        assertThat(CacheApi.class.getMethod("put", CacheKey.class, Object.class, int.class)).isNotNull();
        assertThat(CacheApi.class.getMethod("evict", CacheKey.class)).isNotNull();
        assertThat(CacheApi.class.getMethod("evictByPrefix", CacheRegion.class, String.class)).isNotNull();

        for (Method method : CacheApi.class.getDeclaredMethods()) {
            assertThat(method.getParameterTypes()[0])
                    .as("方法 %s 的第一个入参必须是 CacheKey/CacheRegion（区域文本进不来）", method.getName())
                    .isIn(CacheKey.class, CacheRegion.class);
        }
        assertThat(CacheApi.class.getMethod("get", CacheKey.class, Class.class).getReturnType())
                .isEqualTo(Optional.class);
    }

    private static void assertRegion(CacheRegion region, String keyPrefix, int l1Ttl, int l2Ttl, boolean nullCaching) {
        assertThat(region.keyPrefix()).isEqualTo(keyPrefix);
        assertThat(region.l1TtlSeconds()).isEqualTo(l1Ttl);
        assertThat(region.l2TtlSeconds()).isEqualTo(l2Ttl);
        assertThat(region.nullCaching()).isEqualTo(nullCaching);
    }
}
