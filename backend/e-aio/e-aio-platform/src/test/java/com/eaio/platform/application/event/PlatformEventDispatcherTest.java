package com.eaio.platform.application.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;

import com.eaio.common.id.IdGenerator;
import com.eaio.platform.domain.event.EventDelivery;
import com.eaio.platform.domain.event.EventDeliveryStatus;
import com.eaio.platform.events.EventDeadLetteredEvent;
import com.eaio.platform.events.ParamChangedEvent;
import com.eaio.platform.events.PlatformEvent;
import com.eaio.platform.infrastructure.persistence.EventDeliveryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * {@link PlatformEventDispatcher} 的单测（P1 册 3.9.4 第 2、3 条 + 3.9.3 的失败模式）：
 * 全部监听器成功 → {@code DONE}；任一抛异常 → {@code RETRYING} + {@code attempt=2} + 退避到期时刻；
 * 达到上限 → {@code DEAD} + 死信事实；退避封顶 30 分钟；失败绝不外抛。
 */
class PlatformEventDispatcherTest {

    private final EventDeliveryStore deliveries = mock(EventDeliveryStore.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final EventParams params = mock(EventParams.class);
    private final IdGenerator idGenerator = mock(IdGenerator.class);

    private PlatformEventDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        given(params.retryMax()).willReturn(5);
        given(params.backoffSeconds()).willReturn(30);
        given(idGenerator.nextStr()).willReturn("dead-1");
        dispatcher = new PlatformEventDispatcher(deliveries, events, params, idGenerator);
    }

    @Test
    @DisplayName("AFTER_COMMIT 信封 → 用信封里的事件投递（信封只是凭据，不进发件箱、监听方也看不到它）")
    void registrationEnvelopeTriggersDelivery() {
        given(deliveries.rowByEventId("evt-reg")).willReturn(row("evt-reg", 1, 5));
        given(deliveries.markDone("evt-reg")).willReturn(true);
        ParamChangedEvent event = event("evt-reg");

        dispatcher.onRegistered(new DeliveryRegistration(event));

        verify(events).publishEvent(event);
        verify(deliveries).markDone("evt-reg");
    }

    @Test
    @DisplayName("监听器全部成功 → status=DONE（markDone 的条件更新生效）")
    void allListenersSucceedMarksDone() {
        given(deliveries.rowByEventId("evt-1")).willReturn(row("evt-1", 1, 5));
        given(deliveries.markDone("evt-1")).willReturn(true);

        boolean delivered = dispatcher.deliver(event("evt-1"));

        assertThat(delivered).isTrue();
        verify(events).publishEvent(any(PlatformEvent.class));
        verify(deliveries).markDone("evt-1");
        verify(deliveries, never()).markRetry(anyString(), anyInt(), any(), anyString());
        verify(deliveries, never()).markDead(anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("监听器抛异常 → RETRYING/attempt=2/next_retry_time ≈ now+30s（3.9.4 第 3 条），且异常不外抛")
    void listenerFailureRetriesWithBackoff() {
        given(deliveries.rowByEventId("evt-2")).willReturn(row("evt-2", 1, 5));
        doThrow(new IllegalStateException("监听方炸了")).when(events).publishEvent(any(PlatformEvent.class));
        Instant before = Instant.now();

        boolean delivered = dispatcherDeliverWithoutThrowing("evt-2");

        assertThat(delivered).as("失败必须返回 false，而不是把异常丢给业务事务的调用方").isFalse();
        ArgumentCaptor<Instant> nextRetry = ArgumentCaptor.forClass(Instant.class);
        verify(deliveries).markRetry(eq("evt-2"), eq(2), nextRetry.capture(), anyString());
        assertThat(Duration.between(before, nextRetry.getValue()))
                .as("退避 = 30 × 2^(1-1) = 30s")
                .isBetween(Duration.ofSeconds(28), Duration.ofSeconds(32));
        verify(deliveries, never()).markDone(anyString());
        verify(deliveries, never()).markDead(anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("退避封顶 30 分钟：backoff=1800s 时下一次仍是 1800s（不翻倍成 1 小时）")
    void backoffIsCappedAtThirtyMinutes() {
        given(params.backoffSeconds()).willReturn(1800);
        given(deliveries.rowByEventId("evt-cap")).willReturn(row("evt-cap", 1, 5));
        doThrow(new IllegalStateException("还是失败")).when(events).publishEvent(any(PlatformEvent.class));
        Instant before = Instant.now();

        dispatcherDeliverWithoutThrowing("evt-cap");

        ArgumentCaptor<Instant> nextRetry = ArgumentCaptor.forClass(Instant.class);
        verify(deliveries).markRetry(eq("evt-cap"), eq(2), nextRetry.capture(), anyString());
        assertThat(Duration.between(before, nextRetry.getValue()))
                .as("封顶 30 分钟（3.9.2 第 3 条）")
                .isBetween(Duration.ofSeconds(1798), Duration.ofSeconds(1802));
    }

    @Test
    @DisplayName("第 5 次尝试仍失败（max_attempt=5）→ DEAD + 发 EventDeadLetteredEvent，不再排重投")
    void exhaustedAttemptsBecomeDeadLetter() {
        given(deliveries.rowByEventId("evt-dead")).willReturn(row("evt-dead", 5, 5));
        given(deliveries.markDead(eq("evt-dead"), eq(5), anyString())).willReturn(true);
        doThrow(new IllegalStateException("一直失败")).doNothing().when(events).publishEvent(any(PlatformEvent.class));

        boolean delivered = dispatcherDeliverWithoutThrowing("evt-dead");

        assertThat(delivered).isFalse();
        verify(deliveries).markDead(eq("evt-dead"), eq(5), contains("第 5 次投递失败"));
        verify(deliveries, never()).markRetry(anyString(), anyInt(), any(), anyString());

        ArgumentCaptor<PlatformEvent> published = ArgumentCaptor.forClass(PlatformEvent.class);
        verify(events, times(2)).publishEvent(published.capture());
        assertThat(published.getAllValues().get(1)).isInstanceOf(EventDeadLetteredEvent.class);
        EventDeadLetteredEvent dead = (EventDeadLetteredEvent) published.getAllValues().get(1);
        assertThat(dead.eventId()).as("死信事实的事件 ID 是新的（不是原事件的 ID）").isEqualTo("dead-1");
        assertThat(dead.originalEventId()).isEqualTo("evt-dead");
        assertThat(dead.eventType()).isEqualTo(ParamChangedEvent.class.getName());
        assertThat(dead.attemptCount()).isEqualTo(5);
        assertThat(dead.lastError()).contains("第 5 次投递失败");
    }

    @Test
    @DisplayName("死信事实发布失败也只记 ERROR：不回抛、不影响已写定的 DEAD 终态")
    void deadLetterPublishFailureIsSwallowed() {
        given(deliveries.rowByEventId("evt-dead2")).willReturn(row("evt-dead2", 5, 5));
        given(deliveries.markDead(eq("evt-dead2"), eq(5), anyString())).willReturn(true);
        doThrow(new IllegalStateException("监听方炸了")).when(events).publishEvent(any(PlatformEvent.class));

        assertThatCode(() -> dispatcher.deliver(event("evt-dead2"))).doesNotThrowAnyException();

        verify(deliveries).markDead(eq("evt-dead2"), eq(5), anyString());
    }

    @Test
    @DisplayName("内部事实事件（EventDeadLetteredEvent）没有登记行：只广播、不写状态")
    void internalFactEventIsBroadcastOnly() {
        given(deliveries.rowByEventId("dead-9")).willReturn(null);
        EventDeadLetteredEvent fact = new EventDeadLetteredEvent("dead-9", Instant.now(), "evt-origin",
                ParamChangedEvent.class.getName(), 5, "第 5 次投递失败：boom", "t-9");

        boolean delivered = dispatcher.deliver(fact);

        assertThat(delivered).isTrue();
        verify(events).publishEvent(fact);
        verifyNoStateWrites();
    }

    @Test
    @DisplayName("内部事实事件投递失败：记 ERROR 后返回 false（无登记行可排重投，也没有重试语义）")
    void internalFactEventFailureIsNotRetried() {
        given(deliveries.rowByEventId("dead-10")).willReturn(null);
        doThrow(new IllegalStateException("告警监听方炸了")).when(events).publishEvent(any(PlatformEvent.class));
        EventDeadLetteredEvent fact = new EventDeadLetteredEvent("dead-10", Instant.now(), "evt-origin2",
                ParamChangedEvent.class.getName(), 5, "boom", null);

        assertThatCode(() -> assertThat(dispatcher.deliver(fact)).isFalse()).doesNotThrowAnyException();

        verifyNoStateWrites();
    }

    // ---------------------------------------------------------------- 辅助

    private static EventDelivery row(String eventId, int attempt, int maxAttempt) {
        EventDelivery row = new EventDelivery();
        row.setEventId(eventId);
        row.setEventType(ParamChangedEvent.class.getName());
        row.setStatus(EventDeliveryStatus.RETRYING.name());
        row.setAttemptCount(attempt);
        row.setMaxAttempt(maxAttempt);
        row.setPayloadJson("{}");
        return row;
    }

    private static ParamChangedEvent event(String eventId) {
        return new ParamChangedEvent(eventId, Instant.now(), "UP", "it.key", "SYSTEM", 0L);
    }

    private boolean dispatcherDeliverWithoutThrowing(String eventId) {
        try {
            return dispatcher.deliver(event(eventId));
        } catch (RuntimeException e) {
            throw new AssertionError("投递失败必须被 dispatcher 消化（异常会从业务方法里冒出去）", e);
        }
    }

    /** 没有登记行的投递不接受任何状态写入。 */
    private void verifyNoStateWrites() {
        verify(deliveries, never()).markDone(anyString());
        verify(deliveries, never()).markRetry(anyString(), anyInt(), any(), anyString());
        verify(deliveries, never()).markDead(anyString(), anyInt(), anyString());
    }
}
