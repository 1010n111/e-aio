package com.eaio.platform.application.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import com.eaio.platform.api.CacheKey;
import com.eaio.platform.api.CacheRegion;
import com.eaio.platform.infrastructure.cache.CacheInvalidationPublisher;
import com.eaio.platform.infrastructure.cache.CaffeineRegionCache;
import com.eaio.platform.infrastructure.cache.RedisRegionCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

/**
 * {@link CacheManager} 的单测：三防护里**可纯函数化的判定**与两条本地可观测的行为。
 *
 * <p>真实 Redis 上的三防护（穿透/击穿/雪崩）由 {@code CacheConnectIT} 这类集成测试证明，
 * 这里钉的是"判定本身"：抖动上下界、TTL 覆盖与 L1 封顶、超长值/null 值拒绝、空值占位开关、
 * 前缀失效的两个键空间（值 + 空值占位）、重建计数。
 */
class CacheManagerTest {

    private final MockEnvironment environment = new MockEnvironment();

    private final RedisRegionCache l2 = mock(RedisRegionCache.class);

    private final CacheInvalidationPublisher publisher = mock(CacheInvalidationPublisher.class);

    private final CaffeineRegionCache l1 = new CaffeineRegionCache(environment);

    private CacheManager cache;

    @BeforeEach
    void setUp() {
        given(l2.env()).willReturn("it");
        cache = new CacheManager(l1, l2, publisher, environment);
    }

    @Test
    @DisplayName("TTL 抖动：±10% 上下界，且 200 次不全都一样（3.6.2 防雪崩）")
    void jitterStaysWithinTenPercent() {
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            long millis = CacheManager.jittered(Duration.ofSeconds(1000)).toMillis();

            assertThat(millis).isBetween(900_000L, 1_100_000L);
            seen.add(millis);
        }
        assertThat(seen).as("抖动必须真的抖：整批同时过期才是雪崩").hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("显式 TTL：L2 用显式值（±10%），L1 取 min(显式, 区域 L1)——L1 先于 L2 过期")
    void explicitTtlCapsL1() {
        cache.put(new CacheKey(CacheRegion.FILE_META, "it.file.1"), "v", 1);

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(l2).put(eq(CacheRegion.FILE_META), eq("it.file.1"), eq("v"), ttl.capture());
        assertThat(ttl.getValue().toMillis()).isBetween(900L, 1_100L);
        // Caffeine 的 VarExpiration.getExpiresAfter 给的是**剩余**时长（随断言前的耗时递减），故断言"落在
        // 配置值附近"：没做封顶时会拿到区域 L1 的 30s（差 30 倍），±10% 的窗口既容纳调度抖动又必红
        assertThat(l1.expiresAfter(CacheRegion.FILE_META, "it.file.1"))
                .isCloseTo(Duration.ofSeconds(1), Duration.ofMillis(100));
    }

    @Test
    @DisplayName("默认 TTL（ttlSeconds ≤ 0）：用区域登记值，L1 = 30s < L2 = 300s ± 10%")
    void defaultTtlUsesRegionTable() {
        cache.put(new CacheKey(CacheRegion.FILE_META, "it.file.2"), "v", 0);

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(l2).put(eq(CacheRegion.FILE_META), eq("it.file.2"), eq("v"), ttl.capture());
        assertThat(ttl.getValue().toSeconds()).isBetween(270L, 330L);
        assertThat(l1.expiresAfter(CacheRegion.FILE_META, "it.file.2"))
                .as("剩余时长语义：用区域 L1（30s）而不是 L2（300s）")
                .isCloseTo(Duration.ofSeconds(30), Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("按区域的 L2 TTL 覆盖（eaio.cache.<region>.l2-ttl-seconds）优先于区域登记值")
    void regionTtlOverrideWins() {
        MockEnvironment overridden = new MockEnvironment().withProperty("eaio.cache.file-meta.l2-ttl-seconds", "45");
        CacheManager manager = new CacheManager(new CaffeineRegionCache(overridden), l2, publisher, overridden);

        assertThat(manager.l2Ttl(CacheRegion.FILE_META, 0)).isEqualTo(Duration.ofSeconds(45));
        assertThat(manager.l2Ttl(CacheRegion.FILE_META, 7)).as("显式 TTL 仍然最优先").isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    @DisplayName("超长值（>64KB）与 null 值：拒绝入缓存，L1 与 L2 都不写（3.6.4）")
    void oversizeAndNullValuesAreRejected() {
        String big = "x".repeat(CacheManager.MAX_VALUE_BYTES + 1);

        cache.put(new CacheKey(CacheRegion.FILE_META, "it.file.big"), big, 0);
        cache.put(new CacheKey(CacheRegion.FILE_META, "it.file.null"), null, 0);

        assertThat(l1.get(CacheRegion.FILE_META, "it.file.big")).isNull();
        assertThat(l1.get(CacheRegion.FILE_META, "it.file.null")).isNull();
        verify(l2, never()).put(any(), any(), any(), any());
    }

    @Test
    @DisplayName("读：L1 命中不碰 L2；L1 未命中读 L2")
    void getReadsL1ThenL2() {
        cache.put(new CacheKey(CacheRegion.FILE_META, "it.file.3"), "local", 0);

        assertThat(cache.get(new CacheKey(CacheRegion.FILE_META, "it.file.3"), String.class)).contains("local");
        verify(l2, never()).get(any(), any(), any());

        given(l2.get(CacheRegion.FILE_META, "it.file.4", Object.class)).willReturn(Optional.of("remote"));
        assertThat(cache.get(new CacheKey(CacheRegion.FILE_META, "it.file.4"), Object.class)).contains("remote");
    }

    @Test
    @DisplayName("失效单键：清本机 L1 + 删 L2（值 + 空值占位）+ 广播")
    void evictClearsBothLayersAndBroadcasts() {
        CacheKey key = new CacheKey(CacheRegion.FILE_META, "it.file.5");
        cache.put(key, "v", 0);

        cache.evict(key);

        assertThat(l1.get(CacheRegion.FILE_META, "it.file.5")).isNull();
        verify(l2).delete(CacheRegion.FILE_META, "it.file.5");
        verify(publisher).publishKey(CacheRegion.FILE_META, "it.file.5");
    }

    @Test
    @DisplayName("前缀失效：SCAN 值键**与**空值占位键两个键空间，并清本机 L1 + 广播（3.6.3）")
    void evictByPrefixScansBothKeySpaces() {
        CacheKey key = new CacheKey(CacheRegion.DICT, "it.dict.a");
        cache.put(key, "v", 0);

        cache.evictByPrefix(CacheRegion.DICT, "it.dict.");

        assertThat(l1.get(CacheRegion.DICT, "it.dict.a")).isNull();
        verify(l2).deleteByPattern("eaio:it:platform:dict:items:it.dict.*");
        verify(l2).deleteByPattern("eaio:it:platform:cache:null:dict:it.dict.*");
        verify(publisher).publishPrefix(CacheRegion.DICT, "it.dict.");
    }

    @Test
    @DisplayName("前缀失效的参数校验：null 与完整 Redis 键都拒绝（3.6.4），空串=全区域允许")
    void evictByPrefixValidatesArguments() {
        assertThatThrownBy(() -> cache.evictByPrefix(CacheRegion.DICT, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不得为空");
        assertThatThrownBy(() -> cache.evictByPrefix(CacheRegion.DICT, "eaio:it:platform:dict:items:x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("完整的 Redis 键");

        assertThatCode(() -> cache.evictByPrefix(CacheRegion.DICT, "")).doesNotThrowAnyException();
        verify(l2).deleteByPattern("eaio:it:platform:dict:items:*");
    }

    @Test
    @DisplayName("空值占位只对 3.6.1 标「是」的区域生效，TTL 默认 60s 且可配（platform.cache.null-ttl-seconds）")
    void nullPlaceholderOnlyForRegisteredRegions() {
        cache.markAbsent(CacheRegion.PARAM, "1001:platform.file.max-size");
        verify(l2, never()).markAbsent(any(), any(), any());

        cache.markAbsent(CacheRegion.DICT, "it.dict.absent");
        verify(l2).markAbsent(CacheRegion.DICT, "it.dict.absent", Duration.ofSeconds(60));

        MockEnvironment five = new MockEnvironment().withProperty("eaio.cache.null-ttl-seconds", "5");
        CacheManager manager = new CacheManager(new CaffeineRegionCache(five), l2, publisher, five);
        manager.markAbsent(CacheRegion.DICT, "it.dict.absent2");
        verify(l2).markAbsent(CacheRegion.DICT, "it.dict.absent2", Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("重建计数只在真的拿到锁时累加（集成测试据它断言 10 并发只回源 1 次）")
    void rebuildCountTracksGrantedLocks() {
        given(l2.tryLock(any(), any(), any())).willReturn(true);

        assertThat(cache.tryLock(CacheRegion.DICT, "it.dict.cold")).isTrue();
        assertThat(cache.rebuildCount(CacheRegion.DICT)).isEqualTo(1L);

        given(l2.tryLock(any(), any(), any())).willReturn(false);
        assertThat(cache.tryLock(CacheRegion.DICT, "it.dict.cold")).isFalse();
        assertThat(cache.rebuildCount(CacheRegion.DICT)).isEqualTo(1L);
    }

    @Test
    @DisplayName("等锁：有值就返回（且不再看占位）；占位命中立即返回空；都没有才等到超时")
    void awaitValueStopsOnValuePlaceholderOrTimeout() {
        given(l2.get(CacheRegion.DICT, "it.dict.wait", String.class)).willReturn(Optional.of("filled"));
        assertThat(cache.awaitValue(CacheRegion.DICT, "it.dict.wait", String.class, CacheManager.LOCK_WAIT_MILLIS))
                .contains("filled");
        verify(l2, never()).isAbsent(CacheRegion.DICT, "it.dict.wait");

        given(l2.get(CacheRegion.DICT, "it.dict.absent", String.class)).willReturn(Optional.empty());
        given(l2.isAbsent(CacheRegion.DICT, "it.dict.absent")).willReturn(true);
        long started = System.currentTimeMillis();
        assertThat(cache.awaitValue(CacheRegion.DICT, "it.dict.absent", String.class, CacheManager.LOCK_WAIT_MILLIS))
                .isEmpty();
        assertThat(System.currentTimeMillis() - started).as("占位命中不等满 1s").isLessThan(500L);

        given(l2.isAbsent(CacheRegion.DICT, "it.dict.cold")).willReturn(false);
        given(l2.get(CacheRegion.DICT, "it.dict.cold", String.class)).willReturn(Optional.empty());
        started = System.currentTimeMillis();
        assertThat(cache.awaitValue(CacheRegion.DICT, "it.dict.cold", String.class, 100L)).isEmpty();
        assertThat(System.currentTimeMillis() - started).isGreaterThanOrEqualTo(100L);
    }
}
