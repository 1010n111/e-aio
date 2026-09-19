package com.eaio.platform.api.dto;

/**
 * 触发/重试的出参（P1 册 5.2 的 {@code {runId}}）：{@code job/Run} 与 {@code jobRun/Retry} 共用。
 *
 * <p>返回运行 ID 而不是整个 {@code JobRunDTO}：执行是异步的（ADR-0001：长耗时一律返回 ID 后轮询），
 * 刚受理的那一刻除了 {@code RUNNING} 之外没有任何信息可给。
 *
 * @param runId 运行 ID（{@code job_run.id}；用它查 {@code jobRun/Get}）
 */
public record JobRunIdDTO(long runId) {
}
