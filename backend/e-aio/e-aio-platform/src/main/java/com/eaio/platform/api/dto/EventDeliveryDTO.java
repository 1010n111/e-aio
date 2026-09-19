package com.eaio.platform.api.dto;

import java.time.Instant;

/**
 * 事件投递出参（P1 册 5.3 契约 V1，字段逐条对齐）。
 *
 * <p><b>载荷只给预览</b>：{@code payloadPreview} 是 {@code payload_json} 的前 1000 字符，不是完整载荷
 * ——管理页是运维可见面，完整载荷会把"事件里放过的所有标量"变成可检索的数据外泄面（5.3 的列说明）。
 * 需要完整载荷排障时走数据库/日志，不从这里开洞。
 *
 * @param eventId        事件 ID（消费侧幂等键）
 * @param eventType      事件类型（事件 record 的全限定类名）
 * @param status         {@code RETRYING}/{@code DONE}/{@code DEAD}
 * @param attemptCount   已经尝试的投递次数（1 起）
 * @param maxAttempt     最大投递尝试次数（登记时的快照）
 * @param nextRetryTime  下次重投时刻（{@code RETRYING} 且失败过时非空）
 * @param lastError      最后一次错误摘要（人工重放会追加一行，历史保留）
 * @param traceId        链路 ID
 * @param createdAt      登记时刻（与业务写同一事务）
 * @param finishTime     投递成功时刻（{@code DONE} 时非空）
 * @param payloadPreview 载荷预览（≤1000 字符）
 */
public record EventDeliveryDTO(
        String eventId,
        String eventType,
        String status,
        int attemptCount,
        int maxAttempt,
        Instant nextRetryTime,
        String lastError,
        String traceId,
        Instant createdAt,
        Instant finishTime,
        String payloadPreview) {
}
