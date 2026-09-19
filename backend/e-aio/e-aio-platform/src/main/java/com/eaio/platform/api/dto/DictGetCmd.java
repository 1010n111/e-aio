package com.eaio.platform.api.dto;

/**
 * 按 ID 查单条（P1 册 5.2 的 {@code /platform/dictType/Get} 入参 {@code {id}}）。
 *
 * <p><b>契约补全</b>：设计册 5.3 的 DTO 表未给这个入参命名（5.2 只写了字段），与 T3 的
 * {@code ParamGetCmd} 同款处理：类型化成 record，字段与 5.2 逐字一致。
 *
 * @param id 行 ID
 */
public record DictGetCmd(long id) {
}
