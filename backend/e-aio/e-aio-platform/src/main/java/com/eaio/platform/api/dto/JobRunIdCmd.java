package com.eaio.platform.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 只带运行 ID 的入参（P1 册 5.2 的 {@code {runId}} 形态）：{@code jobRun/Get} 与 {@code jobRun/Retry} 共用。
 *
 * <p>两个端点的入参逐字相同（{@code {runId}}），拆成两个 record 只会多一处抄写。
 *
 * @param runId 运行 ID（{@code job_run.id}）
 */
public record JobRunIdCmd(@NotNull Long runId) {
}
