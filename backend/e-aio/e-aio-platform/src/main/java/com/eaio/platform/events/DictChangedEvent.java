package com.eaio.platform.events;

import java.time.Instant;

/**
 * 字典变更事件（P1 册 3.9.1 / FR-PLT-02）。
 *
 * <p><b>{@code eventId} 必须是第一个组件</b>（P1 册 6.3 的 {@code eventRecordsHaveEventIdFirst}）：
 * 消费侧靠它做幂等键；载荷只放 ID 与标量（3.9 的消费契约）。
 *
 * <p>发布时机：字典写事务**提交后**；消费者：platform（缓存失效）、audit。
 *
 * @param eventId    事件 ID（消费侧幂等键）
 * @param occurredAt 发生时间（3.9 统一约定：事件 record 前两个字段固定为 eventId/occurredAt）
 * @param typeCode   字典类型编码（缓存失效的键就由它构成）
 * @param itemValue  字典项值（类型级变更时为空）
 * @param action     变更类型：{@code ADD}/{@code UP}/{@code DEL}
 * @param operatorId 操作人 ID（audit 未就绪时靠它后补操作审计，3.2.4）
 */
public record DictChangedEvent(
        String eventId,
        Instant occurredAt,
        String typeCode,
        String itemValue,
        String action,
        long operatorId) implements PlatformEvent {
}
