package com.eaio.platform.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 单键查询入参（P1 册 5.2 的 {@code /platform/param/Get} 入参 {@code {paramKey, paramLevel?, ownerId?}}）。
 *
 * <p><b>契约补全</b>：设计册 5.3 的 DTO 表未给这个入参命名（5.2 只写了字段）。这里类型化成 record，
 * 字段与 5.2 逐字一致，避免用 {@code Map<String, Object>} 收参——那样校验、文档与前端契约都靠约定。
 *
 * @param paramKey   参数键
 * @param paramLevel 级别（可空：空则按当前请求上下文解析）
 * @param ownerId    归属 ID（可空：与级别配套；SYSTEM 级必须为 0）
 */
public record ParamGetCmd(
        @NotBlank String paramKey,
        String paramLevel,
        Long ownerId) {
}
