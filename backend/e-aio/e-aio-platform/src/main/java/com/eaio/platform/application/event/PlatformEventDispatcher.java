package com.eaio.platform.application.event;

import java.time.Instant;

import com.eaio.common.id.IdGenerator;
import com.eaio.platform.domain.event.EventDelivery;
import com.eaio.platform.domain.job.JobRetryPolicy;
import com.eaio.platform.events.EventDeadLetteredEvent;
import com.eaio.platform.events.InternalFactEvent;
import com.eaio.platform.events.PlatformEvent;
import com.eaio.platform.infrastructure.persistence.EventDeliveryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 提交后投递器（P1 册 3.9.2 第 2 条）：业务事务**提交后**同步调用全部 {@code @EventListener}
 * （5.5 的消费契约），把结果写回 {@code event_delivery}。
 *
 * <p>四条口径，每条都有明确理由：
 * <ol>
 *   <li><b>只在 {@code AFTER_COMMIT} 投递</b>：事务没提交就投递，监听方会看到"还不存在"的数据；
 *       回滚时更不该投递——那正是"业务回滚 → 监听方完全不被调用"（验收 ①）的实现方式；</li>
 *   <li><b>异常一律在本类内消化，绝不外抛</b>：{@code AFTER_COMMIT} 回调跑在发布方线程里，
 *       异常会从业务方法里冒出去——于是"库已经提交、接口却报错"，比失败本身更难排查。失败被记进
 *       {@code last_error} + 重投队列，这才是"状态必须落库"（票面口径 2）；</li>
 *   <li><b>成功/失败都是条件更新</b>（{@code WHERE status = 'RETRYING'}）：重复投递、人工重放与扫描器
 *       同时动手时，状态机只会向前走一步；</li>
 *   <li><b>死信只发事实、不落 {@code alert} 表</b>：{@code alert}/{@code alert_rule} 属 V9 与告警能力的
 *       票（T13），本票按 T7 对 {@code JobFailedEvent} 的先例发 {@link EventDeadLetteredEvent} 并记 ERROR
 *       （承接方登记在《实现注记（T8）》）。死信事实本身实现了
 *       {@link com.eaio.platform.events.InternalFactEvent}：**不进发件箱**（没有登记行，投递失败不重试、
 *       只记 ERROR），否则"死信的死信"会形成链条。</li>
 * </ol>
 *
 * <p>失败后的退避复用 {@link JobRetryPolicy#nextRetryAt}：与 3.4.4 是同一条公式
 * （{@code backoff × 2^(attempt-1)}、封顶 30 分钟、同样的溢出防护），差别只在上限语义
 * （{@code max_attempt} = 最大投递次数，而非"可重试次数"）：下一次尝试序号超过上限即死信。
 */
@Component
public class PlatformEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PlatformEventDispatcher.class);

    private final EventDeliveryStore deliveries;
    private final ApplicationEventPublisher events;
    private final EventParams params;
    private final IdGenerator idGenerator;

    public PlatformEventDispatcher(EventDeliveryStore deliveries, ApplicationEventPublisher events,
            EventParams params, IdGenerator idGenerator) {
        this.deliveries = deliveries;
        this.events = events;
        this.params = params;
        this.idGenerator = idGenerator;
    }

    /**
     * 业务事务提交后投递一次。参数是发件箱登记信封（{@link DeliveryRegistration}）而不是事件本身：
     * 事务内不能投递（普通 {@code @EventListener} 是同步的），信封只有本类认，
     * 事件对象在这里——**提交之后**——才发布给全部监听方。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRegistered(DeliveryRegistration registration) {
        deliver(registration.event());
    }

    /**
     * 同步投递给全部监听方并落状态（重投扫描器也走本方法——重投与首次投递必须是同一条代码路径，
     * 否则"重投成功"与"首次投递成功"的落库口径会各自漂移）。
     *
     * @return {@code true} = 全部监听方成功（行已置 {@code DONE}），{@code false} = 失败（已排重投或转死信）
     */
    public boolean deliver(PlatformEvent event) {
        EventDelivery row = deliveries.rowByEventId(event.eventId());
        int attempt = row == null || row.getAttemptCount() == null ? 1 : row.getAttemptCount();
        int maxAttempt = row == null || row.getMaxAttempt() == null ? params.retryMax() : row.getMaxAttempt();
        try {
            events.publishEvent(event);
        } catch (RuntimeException e) {
            String message = "第 " + attempt + " 次投递失败：" + describe(e);
            if (row == null) {
                if (event instanceof InternalFactEvent) {
                    log.error("内部事实事件投递失败（不进发件箱、没有重试语义）：eventType={} eventId={} 错误={}",
                            event.getClass().getName(), event.eventId(), message);
                } else {
                    log.error("投递失败且没有登记行（无法排重投）：eventType={} eventId={} 错误={}",
                            event.getClass().getName(), event.eventId(), message);
                }
                return false;
            }
            recordFailure(event, attempt, maxAttempt, message);
            return false;
        }
        if (row == null) {
            if (event instanceof InternalFactEvent) {
                log.debug("内部事实事件已同步广播（不进发件箱，无登记行）：eventType={} eventId={}",
                        event.getClass().getName(), event.eventId());
            } else {
                log.warn("投递成功但没有登记行（无发件箱登记的发布路径）：eventType={} eventId={}",
                        event.getClass().getName(), event.eventId());
            }
            return true;
        }
        if (deliveries.markDone(event.eventId())) {
            log.info("事件投递完成：eventType={} eventId={} attempt={}", event.getClass().getName(), event.eventId(),
                    attempt);
        } else {
            log.warn("事件投递成功但登记行状态已不是 RETRYING（重复投递或已被人工处置）：eventId={}",
                    event.eventId());
        }
        return true;
    }

    // ---------------------------------------------------------------- 内部

    /** 失败落库：还能重投就推进 attempt + 退避到期时刻，超上限就转死信并发布死信事实。 */
    private void recordFailure(PlatformEvent event, int attempt, int maxAttempt, String message) {
        int next = attempt + 1;
        if (next > maxAttempt) {
            if (deliveries.markDead(event.eventId(), attempt, message)) {
                log.error("事件投递达到上限，转死信（只能人工重放）：eventType={} eventId={} attempt={} maxAttempt={}",
                        event.getClass().getName(), event.eventId(), attempt, maxAttempt);
                publishDeadLettered(event, attempt, message);
            } else {
                log.warn("事件转死信未生效（状态已被别的流程改写）：eventId={}", event.eventId());
            }
            return;
        }
        Instant nextRetryTime = JobRetryPolicy.nextRetryAt(Instant.now(), params.backoffSeconds(), attempt);
        if (deliveries.markRetry(event.eventId(), next, nextRetryTime, message)) {
            log.warn("事件投递失败，已排入重投：eventType={} eventId={} 下次={} 错误={}",
                    event.getClass().getName(), event.eventId(), nextRetryTime, message);
        } else {
            log.warn("事件投递失败但排重投未生效（状态已被别的流程改写）：eventId={}", event.eventId());
        }
    }

    /** 死信事实（告警能力在 T13 订阅）；发布失败只记 ERROR——它不能反过来影响业务请求。 */
    private void publishDeadLettered(PlatformEvent event, int attempt, String message) {
        try {
            events.publishEvent(new EventDeadLetteredEvent(idGenerator.nextStr(), Instant.now(), event.eventId(),
                    event.getClass().getName(), attempt, message, MDC.get("traceId")));
        } catch (RuntimeException e) {
            log.error("死信事件发布失败（投递记录的终态已写成 DEAD，不影响业务）：eventId={} 原因={}",
                    event.eventId(), e.getMessage());
        }
    }

    /** 异常 → 可读摘要（类型 + 消息；与任务执行壳同款，不落完整堆栈）。 */
    private static String describe(Throwable cause) {
        String type = cause.getClass().getSimpleName();
        String message = cause.getMessage();
        return message == null ? type : type + ": " + message;
    }
}
