package com.eaio.platform.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.eaio.common.json.JsonUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;

/**
 * 字典失效广播发布方的单测（P1 册 6.1：键/载荷构造是纯逻辑，不需要数据库）。
 *
 * <p>断言的是**契约面**：与参数共用同一条通道 {@code eaio:{env}:platform:ch:invalidation}（4.6 只有
 * 一条失效通道），靠 {@code region = "dict"} 区分。通道名或 region 段一变，其他实例就再也收不到
 * 字典失效——那是个跨实例才暴露、本机永远复现不了的问题。
 */
class DictInvalidationPublisherTest {

    private final StringRedisTemplate template = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);

    @Test
    @DisplayName("通道 = eaio:{env}:platform:ch:invalidation；载荷 region = dict")
    void publishesChannelAndPayload() {
        given(provider.getIfAvailable()).willReturn(template);
        DictInvalidationPublisher publisher = new DictInvalidationPublisher(provider, profileEnv("it"));

        publisher.publish("it_status");

        ArgumentCaptor<String> channel = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(template).convertAndSend(channel.capture(), body.capture());
        assertThat(channel.getValue()).isEqualTo("eaio:it:platform:ch:invalidation");
        assertThat(JsonUtils.fromJson(body.getValue(), DictInvalidationPublisher.Payload.class))
                .isEqualTo(new DictInvalidationPublisher.Payload("it", "dict", "it_status"));
    }

    @Test
    @DisplayName("没配 Redis：不发消息也不抛（广播缺失只影响其他实例的新鲜度窗口）")
    void withoutRedisIsNoop() {
        given(provider.getIfAvailable()).willReturn(null);
        DictInvalidationPublisher publisher = new DictInvalidationPublisher(provider, profileEnv("it"));

        assertThatCode(() -> publisher.publish("it_status")).doesNotThrowAnyException();
        verify(template, never()).convertAndSend(anyString(), anyString());
    }

    @Test
    @DisplayName("Redis 命令失败：吞掉并记 WARN，不把改值事务的提交后回调炸掉")
    void publishFailureIsSwallowed() {
        given(provider.getIfAvailable()).willReturn(template);
        given(template.convertAndSend(anyString(), anyString()))
                .willThrow(new RedisConnectionFailureException("redis down"));
        DictInvalidationPublisher publisher = new DictInvalidationPublisher(provider, profileEnv("it"));

        assertThatCode(() -> publisher.publish("it_status")).doesNotThrowAnyException();
    }

    private static MockEnvironment profileEnv(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return environment;
    }
}
