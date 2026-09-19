package com.eaio.platform.application.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.time.Instant;

import com.eaio.common.exception.SystemException;
import com.eaio.common.id.IdGenerator;
import com.eaio.platform.domain.event.EventDelivery;
import com.eaio.platform.domain.event.EventDeliveryStatus;
import com.eaio.platform.events.ParamChangedEvent;
import com.eaio.platform.events.PlatformEvent;
import com.eaio.platform.infrastructure.persistence.EventDeliveryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link PlatformEventPublisher} 的单测（P1 册 3.9.4 前两条）。
 *
 * <p><b>"事务内登记"怎么在单测里证明</b>：真正的回滚证明需要数据库（{@code EventRetryIT} 用例 ① 用真实
 * PG 做：业务回滚 → {@code event_delivery} 无行 + 监听方未被调用）。这里证明的是它的**前置条件**：
 * 登记方法的传播行为是 {@code REQUIRED}（加入调用方事务）而**不是** {@code REQUIRES_NEW}
 * （那会开独立事务、业务回滚后登记照样提交——3.9.2 明确否决），且登记与"请求投递"发生在同一个方法调用里。
 */
class PlatformEventPublisherTest {

    private final EventDeliveryStore deliveries = mock(EventDeliveryStore.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final EventParams params = mock(EventParams.class);
    private final IdGenerator idGenerator = mock(IdGenerator.class);

    private PlatformEventPublisher publisher;

    @BeforeEach
    void setUp() {
        given(params.retryMax()).willReturn(5);
        given(idGenerator.nextId()).willReturn(9001L);
        publisher = new PlatformEventPublisher(deliveries, events, params, idGenerator);
    }

    @Test
    @DisplayName("登记即插入 event_delivery：RETRYING/attempt=1/next_retry=NULL/payload=事件 JSON/max_attempt 快照")
    void publishInsertsRetryingRow() {
        ParamChangedEvent event = new ParamChangedEvent("evt-t8-1", Instant.parse("2026-09-19T10:00:00Z"), "UP",
                "platform.file.max-size", "SYSTEM", 0L);

        publisher.publish(event);

        ArgumentCaptor<EventDelivery> captor = ArgumentCaptor.forClass(EventDelivery.class);
        verify(deliveries).insert(captor.capture());
        EventDelivery row = captor.getValue();
        assertThat(row.getId()).isEqualTo(9001L);
        assertThat(row.getEventId()).isEqualTo("evt-t8-1");
        assertThat(row.getEventType()).isEqualTo(ParamChangedEvent.class.getName());
        assertThat(row.getStatus()).isEqualTo(EventDeliveryStatus.RETRYING.name());
        assertThat(row.getAttemptCount()).as("首次登记就是第 1 次尝试").isEqualTo(1);
        assertThat(row.getNextRetryTime()).as("还没失败过就没有重投时刻").isNull();
        assertThat(row.getMaxAttempt()).as("max_attempt 是登记时的快照（之后调参数不影响在途记录）").isEqualTo(5);
        assertThat(row.getVersion()).isEqualTo(0);
        assertThat(row.getCreatedAt()).isNotNull();
        assertThat(row.getPayloadJson())
                .as("载荷快照要能反序列化回事件对象（重投靠它）")
                .contains("\"eventId\":\"evt-t8-1\"")
                .contains("\"changeType\":\"UP\"")
                .contains("platform.file.max-size");
        verify(events).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("事务内发的是登记信封，不是事件本身：普通 @EventListener 不会在业务事务里被同步触发")
    void publishesRegistrationEnvelopeNotTheEventItself() {
        ParamChangedEvent event = new ParamChangedEvent("evt-t8-envelope", Instant.now(), "ADD", "it.key", "SYSTEM",
                0L);

        publisher.publish(event);

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(published.capture());
        assertThat(published.getValue()).as("事务内只发信封（否则回滚后监听方已经跑过、提交后还会再投一次）")
                .isInstanceOf(DeliveryRegistration.class);
        assertThat(((DeliveryRegistration) published.getValue()).event()).isSameAs(event);
    }

    @Test
    @DisplayName("登记与业务同事务：@Transactional 传播是 REQUIRED（不是 REQUIRES_NEW），业务回滚登记一起回滚")
    void publishJoinsCallerTransaction() throws Exception {
        Method publish = PlatformEventPublisher.class.getMethod("publish", PlatformEvent.class);
        Transactional annotation = publish.getAnnotation(Transactional.class);

        assertThat(annotation).as("必须声明事务边界：没有事务时自己开一个（如任务执行壳发 JobFailedEvent）")
                .isNotNull();
        assertThat(annotation.propagation())
                .as("必须是 REQUIRED：加入业务事务（回滚一起回滚）；REQUIRES_NEW 会让业务回滚后登记仍然提交，"
                        + "等于发出一个业务上不存在的事件（3.9.2 第 1 条明确否决）")
                .isEqualTo(Propagation.REQUIRED);
    }

    @Test
    @DisplayName("未登记的事件类型在登记时就失败：首次投递能跑、重投解不开的静默死信必须挡在开发期")
    void publishRejectsUnregisteredEventType() {
        PlatformEvent unregistered = new UnregisteredProbeEvent("evt-t8-2", Instant.now());

        assertThatThrownBy(() -> publisher.publish(unregistered))
                .isInstanceOf(SystemException.class)
                .hasMessageContaining("EventTypes");
        verify(deliveries, never()).insert(any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    /** 未登记进 {@code EventTypes} 的事件（控制组：证明上面的守卫真的会因为"没登记"而失败）。 */
    private record UnregisteredProbeEvent(String eventId, Instant occurredAt) implements PlatformEvent {
    }
}
