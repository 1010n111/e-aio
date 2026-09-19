package com.eaio.platform.infrastructure.cache;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;

import com.eaio.platform.application.dict.DictResolver;
import com.eaio.platform.events.DictChangedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 字典失效监听器的单测（P1 册 3.2.6 最后一条：改项后"缓存键被删除"）。
 *
 * <p>断言的是 3.2.3 的"三件事"：清本机 L1+L2（{@code resolver.invalidate}）与广播
 * （{@code publisher.publish}）。真正"键从 Redis 里消失"由集成测试证明（{@code DictCacheIT}）。
 */
class DictInvalidationListenerTest {

    private final DictResolver resolver = mock(DictResolver.class);
    private final DictInvalidationPublisher publisher = mock(DictInvalidationPublisher.class);
    private final DictInvalidationListener listener = new DictInvalidationListener(resolver, publisher);

    @Test
    @DisplayName("字典变更事件 → 清本机缓存 + 广播其他实例")
    void listenerInvalidatesAndBroadcasts() {
        listener.onDictChanged(new DictChangedEvent("evt-1", Instant.now(), "it_status", "RUNNING", "UP", 7L));

        verify(resolver).invalidate("it_status");
        verify(publisher).publish("it_status");
    }

    @Test
    @DisplayName("失效用的键是 typeCode（不是 itemValue）：一个类型的项共用一份缓存")
    void usesTypeCodeAsCacheKey() {
        listener.onDictChanged(new DictChangedEvent("evt-2", Instant.now(), "platform_param_level", null, "DEL", 0L));

        verify(resolver).invalidate("platform_param_level");
        verify(publisher).publish("platform_param_level");
    }
}
