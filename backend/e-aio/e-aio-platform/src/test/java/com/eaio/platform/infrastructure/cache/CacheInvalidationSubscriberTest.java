package com.eaio.platform.infrastructure.cache;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.eaio.platform.api.CacheKey;
import com.eaio.platform.api.CacheRegion;
import com.eaio.platform.application.cache.CacheManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.Message;
import org.springframework.mock.env.MockEnvironment;

/**
 * 通用缓存区失效广播接收方的单测（P1 册 3.6.3、3.6.5 的最后一条测试点）。
 *
 * <p>重点是**过滤与分流**：env 不同必须忽略（否则 dev 的改值会清 prod 实例的缓存）、区域未登记也忽略、
 * 前缀消息与单键消息必须分开处理（前缀消息按单键处理会漏清一大片本机 L1）。真正"L1 被清"由集成测试
 * 证明（{@code CacheCenterIT}：广播后用短 TTL 的 L2 值区分"L1 命中"与"L1 已清"）。
 */
class CacheInvalidationSubscriberTest {

    private final CacheManager cache = mock(CacheManager.class);

    private final CacheInvalidationSubscriber subscriber = new CacheInvalidationSubscriber(cache, profileEnv("it"));

    @Test
    @DisplayName("单键消息：清本机 L1 的该键")
    void keyMessageClearsLocalKey() {
        subscriber.onMessage(message("{\"env\":\"it\",\"region\":\"file_meta\",\"key\":\"9\"}"), null);

        verify(cache).clearLocal(new CacheKey(CacheRegion.FILE_META, "9"));
    }

    @Test
    @DisplayName("前缀消息（keyPrefix 非空）：按前缀清本机 L1，不按单键处理")
    void prefixMessageClearsLocalPrefix() {
        subscriber.onMessage(message(
                "{\"env\":\"it\",\"region\":\"dict\",\"key\":\"it.dict.\",\"keyPrefix\":\"it.dict.\"}"), null);

        verify(cache).clearLocalByPrefix(CacheRegion.DICT, "it.dict.");
        verify(cache, never()).clearLocal(any(CacheKey.class));
    }

    @Test
    @DisplayName("key 与 keyPrefix 都为空：按区域全量清本机 L1")
    void emptyKeyClearsWholeRegion() {
        subscriber.onMessage(message("{\"env\":\"it\",\"region\":\"alert_rule\",\"key\":null}"), null);

        verify(cache).clearLocalByPrefix(CacheRegion.ALERT_RULE, "");
    }

    @Test
    @DisplayName("其他环境的广播必须忽略（否则 dev 的改值会清 prod 实例的缓存）")
    void otherEnvIsIgnored() {
        subscriber.onMessage(message("{\"env\":\"prod\",\"region\":\"dict\",\"key\":\"k\"}"), null);

        verifyNoInteractions(cache);
    }

    @Test
    @DisplayName("未登记区域的广播必须忽略（7.1：能被解析的只有登记表里的区域）")
    void unknownRegionIsIgnored() {
        subscriber.onMessage(message("{\"env\":\"it\",\"region\":\"unknown-region\",\"key\":\"k\"}"), null);

        verifyNoInteractions(cache);
    }

    @Test
    @DisplayName("载荷解析失败：忽略本条，不抛（一条脏消息不能把订阅线程打死）")
    void malformedPayloadIsIgnored() {
        subscriber.onMessage(message("not-json"), null);

        verifyNoInteractions(cache);
    }

    @Test
    @DisplayName("非法键（自带区域前缀）只记日志；CacheManager 抛异常也绝不逃出回调")
    void badKeyAndCacheFailureDoNotEscape() {
        assertThatCode(() -> subscriber.onMessage(
                message("{\"env\":\"it\",\"region\":\"dict\",\"key\":\"eaio:it:platform:dict:items:x\"}"), null))
                .doesNotThrowAnyException();

        willThrow(new IllegalStateException("boom")).given(cache).clearLocal(new CacheKey(CacheRegion.DICT, "k"));

        assertThatCode(() -> subscriber.onMessage(
                message("{\"env\":\"it\",\"region\":\"dict\",\"key\":\"k\"}"), null)).doesNotThrowAnyException();
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
