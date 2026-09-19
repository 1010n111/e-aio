package com.eaio.platform.infrastructure.cache;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.eaio.platform.application.param.ParamResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.Message;
import org.springframework.mock.env.MockEnvironment;

/**
 * 失效广播接收方的单测（P1 册 6.1）。
 *
 * <p>重点是**过滤规则**：env 不同必须忽略（否则 dev 环境的一次改值会清掉 prod 实例的缓存），region 不是
 * param 也要忽略（后续 region 复用同一通道时不能误清参数缓存）。
 */
class ParamInvalidationSubscriberTest {

    private final ParamResolver resolver = mock(ParamResolver.class);

    private final ParamInvalidationSubscriber subscriber =
            new ParamInvalidationSubscriber(resolver, profileEnv("it"));

    @Test
    @DisplayName("同环境同 region：清该键（L1 全部上下文变体 + L2 前缀）")
    void matchingMessageInvalidatesKey() {
        subscriber.onMessage(message("{\"env\":\"it\",\"region\":\"param\",\"key\":\"platform.file.max-size\"}"), null);

        verify(resolver).invalidate("platform.file.max-size");
        verify(resolver, never()).invalidateAll();
    }

    @Test
    @DisplayName("key 为空：全量失效")
    void missingKeyInvalidatesAll() {
        subscriber.onMessage(message("{\"env\":\"it\",\"region\":\"param\",\"key\":null}"), null);

        verify(resolver).invalidateAll();
        verify(resolver, never()).invalidate("platform.file.max-size");
    }

    @Test
    @DisplayName("其他环境的广播必须忽略（否则 dev 的改值会清 prod 实例的缓存）")
    void otherEnvIsIgnored() {
        subscriber.onMessage(message("{\"env\":\"prod\",\"region\":\"param\",\"key\":\"k\"}"), null);

        verifyNoInteractions(resolver);
    }

    @Test
    @DisplayName("非 param 区的广播必须忽略（同一通道后续会被其他 region 复用）")
    void otherRegionIsIgnored() {
        subscriber.onMessage(message("{\"env\":\"it\",\"region\":\"dict\",\"key\":\"k\"}"), null);

        verifyNoInteractions(resolver);
    }

    @Test
    @DisplayName("载荷解析失败：忽略本条，不抛（一条脏消息不能把订阅线程打死）")
    void malformedPayloadIsIgnored() {
        subscriber.onMessage(message("not-json"), null);

        verifyNoInteractions(resolver);
    }

    @Test
    @DisplayName("resolver 抛异常时回调必须兜住（订阅线程一挂，所有实例的失效都失灵）")
    void resolverFailureDoesNotEscape() {
        willThrow(new IllegalStateException("boom")).given(resolver).invalidate("k");

        assertThatCode(() -> subscriber.onMessage(
                message("{\"env\":\"it\",\"region\":\"param\",\"key\":\"k\"}"), null)).doesNotThrowAnyException();
    }

    private static Message message(String body) {
        return new DefaultMessage("eaio:it:platform:ch:invalidation".getBytes(UTF_8), body.getBytes(UTF_8));
    }

    private static MockEnvironment profileEnv(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return environment;
    }
}
