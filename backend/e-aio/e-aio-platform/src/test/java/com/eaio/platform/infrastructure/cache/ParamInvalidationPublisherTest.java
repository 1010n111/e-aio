package com.eaio.platform.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
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
 * 失效广播发布方的单测（P1 册 6.1：纯逻辑与键/载荷构造不需要数据库）。
 *
 * <p>这里断言的是**契约面**：通道名（4.6 键表）与载荷结构（3.1.4）。它们一旦变了，其他实例就再也收不到
 * 失效消息——那是个跨实例才暴露、本机永远复现不了的问题，所以值得用测试钉住。
 */
class ParamInvalidationPublisherTest {

    private final StringRedisTemplate template = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);

    @Test
    @DisplayName("通道 = eaio:{env}:platform:ch:invalidation；载荷 = {env, region, key}")
    void publishesChannelAndPayload() {
        given(provider.getIfAvailable()).willReturn(template);
        ParamInvalidationPublisher publisher = new ParamInvalidationPublisher(provider, profileEnv("it"));

        publisher.publish("platform.file.max-size");

        ArgumentCaptor<String> channel = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(template).convertAndSend(channel.capture(), body.capture());
        assertThat(channel.getValue()).isEqualTo("eaio:it:platform:ch:invalidation");
        assertThat(JsonUtils.fromJson(body.getValue(), ParamInvalidationPublisher.Payload.class))
                .isEqualTo(new ParamInvalidationPublisher.Payload("it", "param", "platform.file.max-size"));
    }

    @Test
    @DisplayName("key 为空 = 全量失效（refresh() 无参的载荷）")
    void emptyKeyMeansFullInvalidation() {
        given(provider.getIfAvailable()).willReturn(template);
        ParamInvalidationPublisher publisher = new ParamInvalidationPublisher(provider, profileEnv("local"));

        publisher.publish(null);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(template).convertAndSend(org.mockito.ArgumentMatchers.anyString(), body.capture());
        assertThat(JsonUtils.fromJson(body.getValue(), ParamInvalidationPublisher.Payload.class).key()).isNull();
    }

    @Test
    @DisplayName("没配 Redis：不发消息也不抛（广播缺失只影响其他实例的新鲜度窗口）")
    void withoutRedisIsNoop() {
        given(provider.getIfAvailable()).willReturn(null);
        ParamInvalidationPublisher publisher = new ParamInvalidationPublisher(provider, profileEnv("it"));

        assertThatCode(() -> publisher.publish("k")).doesNotThrowAnyException();
        verify(template, org.mockito.Mockito.never()).convertAndSend(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("Redis 命令失败：吞掉并记 WARN，不把改值事务的提交后回调炸掉")
    void publishFailureIsSwallowed() {
        given(provider.getIfAvailable()).willReturn(template);
        given(template.convertAndSend(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .willThrow(new RedisConnectionFailureException("redis down"));
        ParamInvalidationPublisher publisher = new ParamInvalidationPublisher(provider, profileEnv("it"));

        assertThatCode(() -> publisher.publish("k")).doesNotThrowAnyException();
    }

    private static MockEnvironment profileEnv(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return environment;
    }
}
