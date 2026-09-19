package com.eaio.platform.application.event;

import java.time.Instant;

import com.eaio.common.exception.SystemException;
import com.eaio.common.id.IdGenerator;
import com.eaio.common.json.JsonUtils;
import com.eaio.platform.domain.event.EventDelivery;
import com.eaio.platform.domain.event.EventDeliveryStatus;
import com.eaio.platform.events.EventTypes;
import com.eaio.platform.events.PlatformEvent;
import com.eaio.platform.infrastructure.persistence.EventDeliveryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 平台事件发布口（P1 册 3.9.2 第 1 条：**登记与业务同事务**的轻量发件箱）。
 *
 * <p>一次 {@link #publish(PlatformEvent)} 做两件事，顺序不可换：
 * <ol>
 *   <li>在当前事务里 INSERT 一行 {@code event_delivery}（{@code RETRYING}/{@code attempt_count=1}/
 *       {@code next_retry_time=NULL}/{@code payload_json=} 事件 JSON）；</li>
 *   <li>发布一个 {@link DeliveryRegistration} **信封**（不是事件本身！）——真正的投递由
 *       {@link PlatformEventDispatcher} 在 {@code AFTER_COMMIT} 回调里把事件发布给全部监听方。</li>
 * </ol>
 *
 * <p><b>为什么事务内发的是信封而不是事件本身</b>：普通 {@code @EventListener}（5.5 的消费契约）是
 * **同步**的，事务内直接发事件会在业务事务里就把监听方跑一遍——业务回滚时监听方已经执行过（违反
 * 3.9.2 第 1 条），提交后投递器还会再投一次（一次业务写投两遍）。信封只有投递器认，监听方拿不到它。
 * 这条被 {@code EventRetryIT#rollbackLeavesNoDeliveryRowAndNoListenerCall} 与
 * {@code PlatformEventPublisherTest#publishesRegistrationEnvelopeNotTheEventItself} 钉住。
 *
 * <p><b>为什么是 {@code @Transactional}（REQUIRED）而不是 {@code REQUIRES_NEW}</b>：登记必须与业务写
 * 同生共死——{@code REQUIRES_NEW} 会开一个独立事务，业务回滚时登记已经提交，于是"发出了一个业务上
 * 不存在的事件"（3.9.2 第 1 条明确否决这种实现）。REQUIRED 的语义正是"有事务就加入，没有就自己开一个"：
 * 业务路径（参数/字典写）加入业务事务；没有事务的路径（如任务执行壳发 {@code JobFailedEvent}）自己开
 * 一个短事务，登记提交后 {@code AFTER_COMMIT} 照样触发——两条路径共用同一份登记逻辑。
 *
 * <p><b>{@code max_attempt} 在登记时取快照</b>（4.3.14 的列，默认 5）：之后调低参数不影响已经登记的
 * 记录——"这条事件当时承诺最多试几次"必须是稳定的，否则一次参数调整会把在途记录直接推成死信。
 *
 * <p>登记行没有 {@code created_by}：发布口不持有操作者上下文（重投扫描线程上更没有）。操作者留痕由
 * 各业务域自己的记录列承担（如 {@code param_change_log.operator_id}），不从事件表再抄一遍。
 */
@Component
public class PlatformEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(PlatformEventPublisher.class);

    private final EventDeliveryStore deliveries;
    private final ApplicationEventPublisher events;
    private final EventParams params;
    private final IdGenerator idGenerator;

    public PlatformEventPublisher(EventDeliveryStore deliveries, ApplicationEventPublisher events,
            EventParams params, IdGenerator idGenerator) {
        this.deliveries = deliveries;
        this.events = events;
        this.params = params;
        this.idGenerator = idGenerator;
    }

    /** 登记事件（事务内）并请求提交后投递；业务回滚 → 登记一起回滚、监听方完全不被调用。 */
    @Transactional
    public void publish(PlatformEvent event) {
        String eventType = event.getClass().getName();
        if (EventTypes.resolve(eventType).isEmpty()) {
            // 在最早的时点失败：未登记的类型首次投递能跑，但重投时解不开 → 静默变死信。
            // 这是"代码忘了登记事件类型"的编程错误，必须在开发/测试期就炸出来，不能等到生产排障。
            throw new SystemException("事件类型未在 com.eaio.platform.events.EventTypes 登记：" + eventType
                    + "（登记后才能进发件箱；未登记的类型重投时无法反序列化）");
        }
        EventDelivery row = new EventDelivery();
        row.setId(idGenerator.nextId());
        row.setEventId(event.eventId());
        row.setEventType(eventType);
        row.setPayloadJson(JsonUtils.toJson(event));
        row.setStatus(EventDeliveryStatus.RETRYING.name());
        row.setAttemptCount(1);
        row.setMaxAttempt(params.retryMax());
        row.setTraceId(MDC.get("traceId"));
        row.setCreatedAt(Instant.now());
        row.setVersion(0);
        deliveries.insert(row);
        events.publishEvent(new DeliveryRegistration(event));
        log.info("事件已登记待投递：eventType={} eventId={} maxAttempt={}", row.getEventType(), row.getEventId(),
                row.getMaxAttempt());
    }
}
