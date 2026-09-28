package com.eaio.platform.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 单文件动作入参（{@code GetMeta} / {@code Del}）：一个 {@code fileId} 就够了的端点共用（P1 册 5.2）。
 *
 * @param fileId 文件 ID（必填）
 */
public record FileIdCmd(@NotNull Long fileId) {
}
