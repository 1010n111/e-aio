package com.eaio.platform.api.dto;

import java.time.Instant;

/**
 * 任务运行日志出参（P1 册 5.3 契约 V1，字段逐条对齐）。
 *
 * <p><b>契约补全一处</b>：5.3 未列 {@code nextRetryTime}，但"失败按退避重投"的运维语义（票面验收 4）
 * 需要看到"下一次什么时候重投"——缺它就只能靠 {@code status=RETRYING} 猜。补上并登记在
 * 《实现注记（T7）》。{@code status=RETRYING} 时它非空，是重投的到期时刻。
 *
 * @param id            运行 ID（{@code job_run.id}，雪花）
 * @param jobCode       任务编码
 * @param triggerType   触发来源 {@code CRON}/{@code MANUAL}/{@code RETRY}
 * @param status        {@code RUNNING}/{@code SUCCESS}/{@code FAILED}/{@code TIMEOUT}/{@code RETRYING}/{@code SKIPPED}
 * @param attempt       第几次尝试（从 1 起）
 * @param startTime     开始时刻
 * @param endTime       结束时刻（{@code RUNNING} 时为空）
 * @param durationMs    耗时毫秒（{@code RUNNING} 时为空）
 * @param nextRetryTime 下次重投时刻（仅 {@code RETRYING} 非空）
 * @param errorMessage  错误摘要（截 2000 字符，不落完整堆栈）
 * @param traceId       链路 ID
 */
public record JobRunDTO(
        long id,
        String jobCode,
        String triggerType,
        String status,
        int attempt,
        Instant startTime,
        Instant endTime,
        Integer durationMs,
        Instant nextRetryTime,
        String errorMessage,
        String traceId) {
}
