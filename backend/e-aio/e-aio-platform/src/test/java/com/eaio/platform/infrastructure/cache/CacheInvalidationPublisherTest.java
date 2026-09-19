package com.eaio.platform.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.eaio.common.json.JsonUtils;
import com.eaio.platform.api.CacheRegion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;

/**
 * 通用缓存区失效广播发布方的单测（P1 册 3.6.3）。
 *
 * <p>钉住的是**契约面**：与参数/字典共用同一条通道（4.6 只有一条失效通道）、{@code region} 段用
 * 3.6.1 的区域段、前缀消息与单键消息的可区分（多一个 {@code keyPrefix} 字段）。通道或区域段一变，
 * 其他实例就再也收不到这类失效——那是个跨实例才暴露、本机永远复现不了的问题。
 */
class CacheInvalidationPublisherTest {

    private final StringRedisTemplate template = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);

    @Test
    @DisplayName("通道与参数/字典侧逐字相同；单键载荷 = {env, region, key, keyPrefix=null}")
    void publishesKeyMessageOnSharedChannel() {
        CacheInvalidationPublisher publisher = publisher();
        given(provider.getIfAvailable()).willReturn(template);

        publisher.publishKey(CacheRegion.DICT, "it_status");

        ArgumentCaptor<String> channel = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(template).convertAndSend(channel.capture(), body.capture());
        assertThat(channel.getValue()).isEqualTo("eaio:it:platform:ch:invalidation");
        assertThat(channel.getValue()).isEqualTo(new ParamInvalidationPublisher(provider, profileEnv()).channel());
        assertThat(channel.getValue()).isEqualTo(new DictInvalidationPublisher(provider, profileEnv()).channel());
        assertThat(JsonUtils.fromJson(body.getValue(), CacheInvalidationPublisher.Payload.class))
                .isEqualTo(new CacheInvalidationPublisher.Payload("it", "dict", "it_status", null));
    }

    @Test
    @DisplayName("前缀载荷：key 与 keyPrefix 同值（T3/T4 的订阅方只会按「单键」处理，不会误判成全量失效）")
    void publishesPrefixMessage() {
        CacheInvalidationPublisher publisher = publisher();
        given(provider.getIfAvailable()).willReturn(template);

        publisher.publishPrefix(CacheRegion.FILE_META, "it.file.");

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(template).convertAndSend(anyString(), body.capture());
        CacheInvalidationPublisher.Payload payload =
                JsonUtils.fromJson(body.getValue(), CacheInvalidationPublisher.Payload.class);
        assertThat(payload.region()).isEqualTo("file_meta");
        assertThat(payload.key()).isEqualTo("it.file.").isEqualTo(payload.keyPrefix());
    }

    @Test
    @DisplayName("没配 Redis：不发消息也不抛（广播缺失只影响其他实例的新鲜度窗口）")
    void withoutRedisIsNoop() {
        CacheInvalidationPublisher publisher = publisher();
        given(provider.getIfAvailable()).willReturn(null);

        assertThatCode(() -> {
            publisher.publishKey(CacheRegion.DICT, "it_status");
            publisher.publishPrefix(CacheRegion.DICT, "");
        }).doesNotThrowAnyException();
        verify(template, never()).convertAndSend(anyString(), anyString());
    }

    @Test
    @DisplayName("Redis 命令失败：吞掉并记 WARN，不把改值事务的提交后回调炸掉")
    void publishFailureIsSwallowed() {
        CacheInvalidationPublisher publisher = publisher();
        given(provider.getIfAvailable()).willReturn(template);
        given(template.convertAndSend(anyString(), anyString()))
                .willThrow(new RedisConnectionFailureException("redis down"));

        assertThatCode(() -> publisher.publishKey(CacheRegion.ALERT_RULE, "rule-1"))
                .doesNotThrowAnyException();
    }

    private CacheInvalidationPublisher publisher() {
        return new CacheInvalidationPublisher(provider, profileEnv());
    }

    private static MockEnvironment profileEnv() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("it");
        return environment;
    }
}
