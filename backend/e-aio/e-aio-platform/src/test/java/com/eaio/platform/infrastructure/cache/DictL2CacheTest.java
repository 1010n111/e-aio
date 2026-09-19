package com.eaio.platform.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.eaio.common.redis.RedisKit;
import com.eaio.common.redis.RedisUnavailableException;
import com.eaio.platform.domain.dict.DictItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

/**
 * 字典 L2 缓存的单测（P1 册 4.6 键表 + P1-2 册 3.15 配置登记表）。
 *
 * <p>钉住三件契约：键模式、TTL 抖动区间、Redis 不可用时"只降级不抛"。
 */
class DictL2CacheTest {

    private final RedisKit kit = mock(RedisKit.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<RedisKit> provider = mock(ObjectProvider.class);

    @Test
    @DisplayName("键 = eaio:{env}:platform:dict:items:{typeCode}（4.6 逐字）")
    void keyMatchesDesignTable() {
        given(provider.getIfAvailable()).willReturn(null);

        DictL2Cache cache = new DictL2Cache(provider, profileEnv("it"));

        assertThat(cache.keyOf("platform_param_level"))
                .isEqualTo("eaio:it:platform:dict:items:platform_param_level");
    }

    @Test
    @DisplayName("写入 TTL = 1800s ± 10%（抖动的意义是防止整批同时过期）")
    void writeUsesJitteredTtl() {
        given(provider.getIfAvailable()).willReturn(kit);
        DictL2Cache cache = new DictL2Cache(provider, profileEnv("it"));

        cache.put("it_status", List.of(item("RUNNING")));

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(kit).set(anyString(), any(), ttl.capture());
        assertThat(ttl.getValue().toSeconds()).isBetween(1620L, 1980L);
    }

    @Test
    @DisplayName("读：Redis 不可用 = 未命中（回源 DB），不抛异常")
    void readDegradesToMiss() {
        given(provider.getIfAvailable()).willReturn(kit);
        given(kit.get(anyString(), any())).willThrow(new RedisUnavailableException("redis down"));
        DictL2Cache cache = new DictL2Cache(provider, profileEnv("it"));

        assertThat(cache.get("it_status")).isEmpty();
    }

    @Test
    @DisplayName("没配 Redis：读=未命中、写/删=静默跳过（不抛、不假装成功）")
    void withoutRedisIsNoop() {
        given(provider.getIfAvailable()).willReturn(null);
        DictL2Cache cache = new DictL2Cache(provider, profileEnv("it"));

        assertThat(cache.get("it_status")).isEmpty();
        assertThatCode(() -> cache.put("it_status", List.of(item("RUNNING")))).doesNotThrowAnyException();
        assertThatCode(() -> cache.evict("it_status")).doesNotThrowAnyException();
        verify(kit, never()).set(anyString(), any(), any());
    }

    @Test
    @DisplayName("失效失败只记 WARN（L1 已清、L2 靠 TTL 兜底）")
    void evictFailureIsSwallowed() {
        given(provider.getIfAvailable()).willReturn(kit);
        willThrow(new RedisUnavailableException("redis down")).given(kit).delete(anyString());
        DictL2Cache cache = new DictL2Cache(provider, profileEnv("it"));

        assertThatCode(() -> cache.evict("it_status")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("载荷往返：缓存里存的是该类型下全部项（含停用），供 getLabel 解析历史值")
    void payloadCarriesAllItems() {
        given(provider.getIfAvailable()).willReturn(kit);
        given(kit.get(anyString(), any())).willReturn(new DictL2Cache.Payload(List.of(item("OLD"))));
        DictL2Cache cache = new DictL2Cache(provider, profileEnv("it"));

        Optional<List<DictItem>> cached = cache.get("it_status");

        assertThat(cached).isPresent();
        assertThat(cached.get()).extracting(DictItem::getItemValue).containsExactly("OLD");
    }

    private static MockEnvironment profileEnv(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return environment;
    }

    private static DictItem item(String value) {
        DictItem item = new DictItem();
        item.setTypeCode("it_status");
        item.setItemValue(value);
        item.setItemLabel("标签");
        item.setStatus("DISABLED");
        return item;
    }
}
