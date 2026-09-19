package com.eaio.platform.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;

import com.eaio.common.redis.RedisKit;
import com.eaio.common.redis.RedisUnavailableException;
import com.eaio.platform.api.CacheRegion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;

/**
 * L2 原语的单测（P1 册 4.6 键表 + 3.6.3 的失败语义）。
 *
 * <p>钉三件事：键（值/空值占位/锁三个键空间）、失败时"只记错误不抛 + 计数器累加"、
 * 以及互斥重建锁在 Redis 不可用时 fail-open（否则每次冷读都要白等 1s 才能回源）。
 */
class RedisRegionCacheTest {

    private final RedisKit kit = mock(RedisKit.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<RedisKit> provider = mock(ObjectProvider.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<StringRedisTemplate> templates = mock(ObjectProvider.class);

    private final RedisRegionCache cache = new RedisRegionCache(provider, templates, profileEnv());

    @Test
    @DisplayName("读：用值键（4.6）；Redis 不可用 = 未命中 + 错误计数 +1")
    void getUsesValueKeyAndDegradesToMiss() {
        given(provider.getIfAvailable()).willReturn(kit);
        given(kit.get("eaio:it:platform:dict:items:it_status", String.class)).willReturn("v");
        assertThat(cache.get(CacheRegion.DICT, "it_status", String.class)).contains("v");

        given(kit.get(anyString(), any())).willThrow(new RedisUnavailableException("redis down"));
        assertThat(cache.get(CacheRegion.DICT, "it_status", String.class)).isEmpty();
        assertThat(cache.opErrorCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("写：用值键与调用方给的 TTL；失败只累加错误计数，不抛")
    void putUsesValueKeyAndSwallowsFailure() {
        given(provider.getIfAvailable()).willReturn(kit);
        cache.put(CacheRegion.FILE_META, "9", "v", Duration.ofSeconds(270));

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(kit).set(eq("eaio:it:platform:file:meta:9"), eq("v"), ttl.capture());
        assertThat(ttl.getValue()).isEqualTo(Duration.ofSeconds(270));

        willThrow(new RedisUnavailableException("redis down")).given(kit).set(anyString(), any(), any());
        assertThatCode(() -> cache.put(CacheRegion.FILE_META, "9", "v", Duration.ofSeconds(1)))
                .doesNotThrowAnyException();
        assertThat(cache.opErrorCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("单键失效删两个键：值键 + 空值占位键（4.6）")
    void deleteRemovesValueAndPlaceholder() {
        given(provider.getIfAvailable()).willReturn(kit);

        cache.delete(CacheRegion.DICT, "it_status");

        verify(kit).delete("eaio:it:platform:dict:items:it_status");
        verify(kit).delete("eaio:it:platform:cache:null:dict:it_status");
    }

    @Test
    @DisplayName("空值占位：isAbsent 看空值键、markAbsent 写空值键（短 TTL 由调用方给）")
    void placeholderUsesNullKey() {
        given(provider.getIfAvailable()).willReturn(kit);
        given(kit.get("eaio:it:platform:cache:null:dict:it_status", String.class)).willReturn("NULL");

        assertThat(cache.isAbsent(CacheRegion.DICT, "it_status")).isTrue();
        cache.markAbsent(CacheRegion.DICT, "it_status", Duration.ofSeconds(60));
        verify(kit).set("eaio:it:platform:cache:null:dict:it_status", "NULL", Duration.ofSeconds(60));

        given(kit.get(anyString(), any())).willThrow(new RedisUnavailableException("redis down"));
        assertThat(cache.isAbsent(CacheRegion.DICT, "it_status")).as("不可用 = 没有占位").isFalse();
        assertThat(cache.opErrorCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("互斥重建锁：SETNX 10s 用锁键；没拿到返回 false；Redis 不可用 fail-open 返回 true")
    void tryLockSemantics() {
        given(provider.getIfAvailable()).willReturn(kit);
        given(kit.setIfAbsent("eaio:it:platform:lock:cache:dict:it_status", "1", Duration.ofSeconds(10)))
                .willReturn(true);

        assertThat(cache.tryLock(CacheRegion.DICT, "it_status", Duration.ofSeconds(10))).isTrue();

        given(kit.setIfAbsent(anyString(), any(), any())).willThrow(new RedisUnavailableException("redis down"));
        assertThat(cache.tryLock(CacheRegion.DICT, "it_status", Duration.ofSeconds(10)))
                .as("锁不可用时放行回源：等待只会白加 1s 延迟").isTrue();
        assertThat(cache.opErrorCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("解锁删锁键；失败不抛")
    void unlockDeletesLockKey() {
        given(provider.getIfAvailable()).willReturn(kit);

        cache.unlock(CacheRegion.DICT, "it_status");

        verify(kit).delete("eaio:it:platform:lock:cache:dict:it_status");

        willThrow(new RedisUnavailableException("redis down")).given(kit).delete(anyString());
        assertThatCode(() -> cache.unlock(CacheRegion.DICT, "it_status")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("没配 Redis：读空、写/删/锁不抛、tryLock 放行、前缀扫不抛（降级只影响新鲜度）")
    void withoutRedisEverythingDegrades() {
        given(provider.getIfAvailable()).willReturn(null);
        given(templates.getIfAvailable()).willReturn(null);

        assertThat(cache.get(CacheRegion.DICT, "it_status", String.class)).isEmpty();
        assertThat(cache.isAbsent(CacheRegion.DICT, "it_status")).isFalse();
        assertThat(cache.tryLock(CacheRegion.DICT, "it_status", Duration.ofSeconds(10))).isTrue();
        assertThatCode(() -> {
            cache.put(CacheRegion.DICT, "it_status", "v", Duration.ofSeconds(1));
            cache.markAbsent(CacheRegion.DICT, "it_status", Duration.ofSeconds(1));
            cache.delete(CacheRegion.DICT, "it_status");
            cache.unlock(CacheRegion.DICT, "it_status");
            cache.deleteByPattern("eaio:it:platform:dict:items:*");
        }).doesNotThrowAnyException();
        verify(kit, never()).set(anyString(), any(), any());
        assertThat(cache.opErrorCount()).isZero();
    }

    @Test
    @DisplayName("前缀扫描失败（模板抛错）：累加错误计数、不抛（L2 靠 TTL 兜底）")
    void scanFailureIsCounted() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        given(templates.getIfAvailable()).willReturn(template);
        willThrow(new RuntimeException("redis down")).given(template).scan(any(ScanOptions.class));

        cache.deleteByPattern("eaio:it:platform:dict:items:*");

        assertThat(cache.opErrorCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("env 段的解析规则与 CacheEnv 一致（缓存键与广播通道必须算同一个 env）")
    void exposesEnv() {
        assertThat(cache.env()).isEqualTo("it");
        assertThat(new RedisRegionCache(provider, templates,
                new MockEnvironment().withProperty("eaio.env", "prod")).env()).isEqualTo("prod");
    }

    private static MockEnvironment profileEnv() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("it");
        return environment;
    }

    @Test
    @DisplayName("键不存在（RedisKit 返回 null）= 未命中，不抛")
    void getReturnsEmptyForMissingKey() {
        given(provider.getIfAvailable()).willReturn(kit);
        given(kit.get(anyString(), any())).willReturn(null);

        assertThat(cache.get(CacheRegion.ALERT_RULE, "rule-1", String.class)).isEmpty();
    }
}
