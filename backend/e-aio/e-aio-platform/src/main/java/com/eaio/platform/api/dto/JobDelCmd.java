package com.eaio.platform.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 删除任务入参（P1 册 5.2 的 {@code {jobCode, version}} → {@code Void}）：{@code /platform/job/Del}。
 *
 * <p>入参用 {@code jobCode} 而不是 {@code id}，与 {@code job/Get} 一致（{@code job_code} 是行身份）；
 * {@code version} 必填，否则删除变成"无视并发改动的盲删"（同 T5 对 {@code dictType/Del} 的口径）。
 *
 * @param jobCode 任务编码（必填）
 * @param version 乐观锁版本（必填；过期即 10003）
 */
public record JobDelCmd(
        @NotBlank @Size(max = 64) String jobCode,
        @NotNull Integer version) {
}
