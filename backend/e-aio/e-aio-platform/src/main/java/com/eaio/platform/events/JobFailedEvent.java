package com.eaio.platform.events;

import java.time.Instant;

/**
 * 任务执行失败（终态）事实（P1 册 3.4.2/3.4.3：{@code JobExecutor} 在写定终态后发布）。
 *
 * <p><b>发布时机</b>：只在**终态失败/超时**（重投次数用尽）时发布一次；进入
 * {@code RETRYING} 的中间失败不发——否则一次连续失败会发 N 条事件，下游告警会在重投期间反复响铃
 * （3.4.4 的抑制窗口是给"连续失败"用的，不是给"每一次尝试"用的）。
 *
 * <p>字段口径（P1 册 3.9.1）：前两个字段固定为 {@code eventId}（消费侧幂等键）与 {@code occurredAt}，
 * 载荷只放 ID 与标量。
 *
 * <p><b>消费方</b>：告警能力（{@code rule_code = job.failure} 的 {@code alert} 行 + 站内公告）在 V9/T13
 * 落地时订阅本事件；T7 只负责把事实发出来（3.4.4 的告警落库依赖 {@code alert} 表与 {@code NoticeApi}）。
 *
 * @param eventId      事件 ID（消费侧幂等键）
 * @param occurredAt   发生时刻（写定终态的时刻）
 * @param jobCode      任务编码
 * @param runId        运行 ID（{@code job_run.id}，查日志/回溯用）
 * @param attempt      失败时的尝试次数（= 用尽重投次数后的最后一次）
 * @param errorMessage 错误摘要（已截 2000 字符）
 * @param traceId      链路 ID
 */
public record JobFailedEvent(
        String eventId,
        Instant occurredAt,
        String jobCode,
        long runId,
        int attempt,
        String errorMessage,
        String traceId) {
}
