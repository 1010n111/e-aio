package com.eaio.platform.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;

import com.eaio.common.redis.RedisKit;
import com.eaio.common.redis.RedisUnavailableException;
import com.eaio.platform.application.cache.CacheManager;
import com.eaio.platform.domain.param.ParamItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;

/**
 * 参数 L2 适配器的单测（P1 册 4.6 键表）：T6 收敛后它只剩"参数区的键形状"与区域路由，
 * 本类钉住的正是这两件事——**适配器传相对键、前缀由 CacheRegion 加**，最终落到的 Redis 键必须与
 * 4.6 的 {@code eaio:{env}:platform:param:{orgId}:{key}} 逐字一致（真 Redis 上的同一断言见
 * {@code ParamCenterIT} 的 {@code l2Key(...)} + {@code hasKey(...)}）。
 */
class ParamL2CacheTest {

    private final RedisKit kit = mock(RedisKit.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<RedisKit> provider = mock(ObjectProvider.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<StringRedisTemplate> templates = mock(ObjectProvider.class);

    private final ParamL2Cache cache = new ParamL2Cache(cacheManager());

    @Test
    @DisplayName("写：键 = eaio:{env}:platform:param:{orgId}:{key}，TTL 1800s ± 10%（4.6 逐字）")
    void putUsesExactRedisKey() {
        given(provider.getIfAvailable()).willReturn(kit);

        cache.put(9901L, "platform.file.max-size", new ParamItem());

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(kit).set(key.capture(), any(), ttl.capture());
        assertThat(key.getValue()).isEqualTo("eaio:it:platform:param:9901:platform.file.max-size");
        assertThat(ttl.getValue().toSeconds()).isBetween(1620L, 1980L);
    }

    @Test
    @DisplayName("读：同一个键；未命中与 Redis 不可用都是空（调用方回源）")
    void getUsesExactRedisKeyAndDegradesToMiss() {
        given(provider.getIfAvailable()).willReturn(kit);
        given(kit.get("eaio:it:platform:param:7:k", ParamItem.class)).willReturn(new ParamItem());

        assertThat(cache.get(7L, "k")).isPresent();

        given(kit.get(anyString(), any())).willThrow(new RedisUnavailableException("redis down"));
        assertThat(cache.get(7L, "k")).isEmpty();
    }

    @Test
    @DisplayName("互斥重建锁：锁键带区域段 param（4.6：eaio:{env}:platform:lock:cache:{region}:{key}）")
    void lockUsesRegionScopedKey() {
        given(provider.getIfAvailable()).willReturn(kit);
        given(kit.setIfAbsent(anyString(), any(), any())).willReturn(true);

        assertThat(cache.tryLock(7L, "k")).isTrue();

        verify(kit).setIfAbsent("eaio:it:platform:lock:cache:param:7:k", "1", Duration.ofSeconds(10));
    }

    /** 真实装配链（L2 + 广播 + CacheManager），只 mock RedisKit 那一层：键与 TTL 才会真的走到 kit 上。 */
    private CacheManager cacheManager() {
        MockEnvironment environment = profileEnv();
        return new CacheManager(new CaffeineRegionCache(environment),
                new RedisRegionCache(provider, templates, environment),
                new CacheInvalidationPublisher(templates, environment), environment);
    }

    private static MockEnvironment profileEnv() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("it");
        return environment;
    }
}
