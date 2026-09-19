package com.eaio.platform.events;

import java.time.Instant;

/**
 * 死信事实（P1 册 3.9.2 第 4 条 / 3.9.4）：某条投递记录达到 {@code max_attempt} 仍失败，
 * 被置为 {@code DEAD} 时发布一次，供告警能力订阅。
 *
 * <p><b>为什么是"发布事件"而不是直接写 {@code alert} 表</b>：{@code alert}/{@code alert_rule} 属
 * V9 与告警能力的票（T13），T8 既没有表也没有 {@code NoticeApi}；把事实发出来、由承接方订阅，
 * 与 T7 对 {@code JobFailedEvent} 的处理是同一先例（两处差异都登记在《实现注记（T8）》）。
 *
 * <p><b>它是 {@link InternalFactEvent}，不进发件箱</b>：没有 {@code event_delivery} 登记行，
 * 投递失败也只记 ERROR（不重试）——否则"死信的死信"会形成链条，而这条事实的价值就是尽快送达。
 *
 * <p>字段口径（3.9.1）：前两个组件固定为 {@code eventId}/{@code occurredAt}；本事件自己的
 * {@code eventId} 是**新生成的**（它是"死信发生"这个事实的幂等键），原事件的 ID 走
 * {@code originalEventId}——两者不可混：混了之后消费侧"按 eventId 去重"会把"第一条死信"当成
 * "原事件的重复投递"而丢掉。
 *
 * @param eventId         本事件 ID（消费侧幂等键）
 * @param occurredAt      死信时刻
 * @param originalEventId 原事件的 ID（{@code event_delivery.event_id}，人工重放就是按它定位）
 * @param eventType       原事件类型（{@code event_delivery.event_type}）
 * @param attemptCount    失败时的尝试次数（= {@code max_attempt}）
 * @param lastError       最后一次错误摘要
 * @param traceId         链路 ID（原始投递登记时落库的那个）
 */
public record EventDeadLetteredEvent(
        String eventId,
        Instant occurredAt,
        String originalEventId,
        String eventType,
        int attemptCount,
        String lastError,
        String traceId) implements InternalFactEvent {
}
