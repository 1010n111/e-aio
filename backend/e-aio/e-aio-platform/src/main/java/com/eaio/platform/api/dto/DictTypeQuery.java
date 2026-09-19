package com.eaio.platform.api.dto;

/**
 * 字典类型分页查询入参（跨模块 DTO，P1 册 5.3 契约 V1）。
 *
 * <p>分页公共字段：{@code pageNum}（从 1 起，默认 1）、{@code pageSize}（1–200，默认 20）、
 * {@code orderBy}（**白名单**，非法值忽略并 WARN）、{@code orderDir}（ASC/DESC）。
 *
 * @param typeCode 类型编码（模糊）
 * @param typeName 类型名称（模糊）
 * @param status   状态（精确）
 * @param pageNum  页码（从 1 起）
 * @param pageSize 页大小（1–200）
 * @param orderBy  排序字段（白名单外的值被忽略）
 * @param orderDir 排序方向 ASC/DESC
 */
public record DictTypeQuery(
        String typeCode,
        String typeName,
        String status,
        Integer pageNum,
        Integer pageSize,
        String orderBy,
        String orderDir) {
}
