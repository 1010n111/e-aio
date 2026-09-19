package com.eaio.platform.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 只带任务编码的入参（P1 册 5.2 的 {@code {jobCode}} 形态）：{@code job/Get}、{@code job/Pause}、
 * {@code job/Resume} 共用。三个端点的入参逐字相同，拆三个 record 只会多两处抄写。
 *
 * @param jobCode 任务编码（必填）
 */
public record JobCodeCmd(@NotBlank @Size(max = 64) String jobCode) {
}
