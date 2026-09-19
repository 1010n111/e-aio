package com.eaio.platform.api.dto;

/**
 * 任务分页查询入参（P1 册 5.3：{@code JobQuery}）。
 *
 * <p>分页公共字段口径同 {@link DictTypeQuery}（{@code pageNum} ≥ 1、{@code pageSize} 1–200、
 * {@code orderBy} 走白名单）。
 *
 * @param jobCode  任务编码（模糊）
 * @param enabled  启用状态（精确）
 * @param pageNum  页码（从 1 起）
 * @param pageSize 页大小（1–200）
 * @param orderBy  排序字段（白名单外的值被忽略）
 * @param orderDir 排序方向 ASC/DESC
 */
public record JobQuery(
        String jobCode,
        Boolean enabled,
        Integer pageNum,
        Integer pageSize,
        String orderBy,
        String orderDir) {
}
