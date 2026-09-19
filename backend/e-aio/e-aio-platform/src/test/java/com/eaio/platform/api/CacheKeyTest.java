package com.eaio.platform.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 缓存键的单测（P1 册 5.4 的 DTO 表 + 3.6.4 的边界）。
 *
 * <p>构造即校验：坏键在这里就炸，而不是写进 Redis 后静默读不到（那是"缓存永远不命中"这类
 * 只能靠 Redis 里翻键才发现的缺陷）。
 */
class CacheKeyTest {

    @Test
    @DisplayName("合法键：值键 = 区域前缀 + key（前缀由区域提供，key 里没有前缀）")
    void validKeyBuildsRedisKey() {
        CacheKey key = new CacheKey(CacheRegion.DICT, "it.dict.status");

        assertThat(key.region()).isEqualTo(CacheRegion.DICT);
        assertThat(key.key()).isEqualTo("it.dict.status");
        assertThat(key.redisKey("it")).isEqualTo("eaio:it:platform:dict:items:it.dict.status");
    }

    @Test
    @DisplayName("键长上限 256：正好 256 通过，257 拒绝")
    void keyLengthBoundary() {
        assertThat(new CacheKey(CacheRegion.FILE_META, "k".repeat(256)).key()).hasSize(256);

        assertThatThrownBy(() -> new CacheKey(CacheRegion.FILE_META, "k".repeat(257)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("256");
    }

    @Test
    @DisplayName("区域为空 / 键为空 / 键是完整 Redis 键 / 键自带区域前缀：一律拒绝（3.6.4）")
    void invalidKeysAreRejected() {
        assertThatThrownBy(() -> new CacheKey(null, "k"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("缓存区不得为空");
        assertThatThrownBy(() -> new CacheKey(CacheRegion.PARAM, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不得为空");
        assertThatThrownBy(() -> new CacheKey(CacheRegion.PARAM, "  "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不得为空");
        assertThatThrownBy(() -> new CacheKey(CacheRegion.PARAM, "eaio:it:platform:param:1001:k"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("完整的 Redis 键");
        assertThatThrownBy(() -> new CacheKey(CacheRegion.ALERT_RULE, "platform:param:1001:k"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("区域前缀");
    }
}
