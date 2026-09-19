package com.eaio.platform.api.dto;

import jakarta.validation.constraints.Size;

/**
 * 按分组查询参数（跨模块 DTO，P1 册 5.2 {@code /platform/param/GetAll} 的 {@code {paramGroup?}} 入参）。
 *
 * <p><b>契约补全</b>：5.2 只给了字段名与"可选"，没给类型名；与 {@link ParamGetCmd}/{@link ParamDelCmd}
 * 同批登记在《实现注记（T3/T4）》。为空 = 不过滤分组（返回全量，即 {@code getAll()} 的语义）。
 *
 * @param paramGroup 分组名（≤64；为空表示全量）
 */
public record ParamGroupCmd(@Size(max = 64) String paramGroup) {
}
