package com.eaio.platform.api.dto;

/**
 * 字典项分页查询入参（跨模块 DTO，P1 册 5.3 契约 V1）。
 *
 * <p>分页公共字段口径同 {@link DictTypeQuery}。
 *
 * @param typeCode  所属类型编码（**精确**：项列表总是"某个类型下"的列表，模糊匹配没有使用场景）
 * @param itemValue 项值（模糊）
 * @param status    状态（精确）
 * @param pageNum   页码（从 1 起）
 * @param pageSize  页大小（1–200）
 * @param orderBy   排序字段（白名单外的值被忽略）
 * @param orderDir  排序方向 ASC/DESC
 */
public record DictItemQuery(
        String typeCode,
        String itemValue,
        String status,
        Integer pageNum,
        Integer pageSize,
        String orderBy,
        String orderDir) {
}
