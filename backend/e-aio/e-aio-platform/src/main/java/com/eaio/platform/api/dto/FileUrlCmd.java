package com.eaio.platform.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 带外下载链接入参（P1 册 5.2 的 {@code /platform/file/GetUrl}）。
 *
 * @param fileId        文件 ID（必填）
 * @param expireSeconds 有效期（秒）；非正数按默认 300 处理（{@code FileAppService} 里统一夹取上限，
 *                      避免"某个调用点传了 10 年"这种把时效 token 变成永久链接的写法）
 */
public record FileUrlCmd(
        @NotNull Long fileId,
        @Positive Integer expireSeconds) {
}
