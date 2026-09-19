package com.eaio.platform.api.dto;

import java.time.Instant;
import java.util.Map;

/**
 * 任务执行上下文（P1 册 3.4.2 的 {@code JobContext}，record 落地）。处理点通过它拿到本次执行的全部事实。
 *
 * @param jobCode  任务编码（与 {@code job.job_code} 一致）
 * @param params   本次执行的参数（手册触发时由调用方给，cron 触发取 {@code job.params_json}）
 * @param runId    本次运行 ID（{@code job_run.id}，日志与留痕用）
 * @param attempt  第几次尝试（从 1 起；重投时递增，3.4.4）
 * @param traceId  链路 ID（写入 {@code job_run.trace_id}，便于从日志反查）
 * @param deadline 截止时刻（{@code start_time + timeout_seconds}），到点线程被中断
 */
public record JobContext(
        String jobCode,
        Map<String, String> params,
        long runId,
        int attempt,
        String traceId,
        Instant deadline) {

    /** 参数（永不为 {@code null}，处理点不必判空）。 */
    public JobContext {
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /** 是否已过截止时刻：长循环处理点用它主动退出（见 {@link com.eaio.platform.api.JobHandler} 约定 3）。 */
    public boolean expired() {
        return deadline != null && !Instant.now().isBefore(deadline);
    }
}
