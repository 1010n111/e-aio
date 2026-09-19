package com.eaio.platform.events;

import java.time.Instant;

/**
 * 参数变更事件（P1 册 3.9 / FR-PLT-01）。
 *
 * <p><b>前两个组件固定为 {@code eventId}/{@code occurredAt}</b>（P1 册 3.9.1，由 6.3 的
 * {@code eventRecordsHaveEventIdFirst} 机械检查）：{@code eventId} 是消费侧幂等键，位置固定让人一眼看到
 * "这个事件可去重"；{@code occurredAt} 是业务发生时间（不是投递时间——重试后投递时间会变，用它算时序会错）。
 *
 * <p>载荷只放 ID 与标量（3.9 的消费契约）：不放新旧值——SECRET 参数的新旧值一旦进事件载荷，
 * 就会顺着日志、消息、监听器泄漏。
 *
 * @param eventId    事件 ID（消费侧幂等键）
 * @param occurredAt 业务发生时间
 * @param changeType 变更类型：{@code ADD}/{@code UP}/{@code DEL}
 * @param paramKey   参数键
 * @param paramLevel 变更行级别（SYSTEM/ORG/USER）
 * @param ownerId    变更行归属 ID
 */
public record ParamChangedEvent(String eventId, Instant occurredAt, String changeType, String paramKey,
        String paramLevel, long ownerId) implements PlatformEvent {
}
