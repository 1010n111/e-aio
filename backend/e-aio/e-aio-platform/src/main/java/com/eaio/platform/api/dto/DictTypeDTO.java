package com.eaio.platform.api.dto;

/**
 * 字典类型（跨模块 DTO，P1 册 5.3 契约 V1）。
 *
 * @param id       行 ID（雪花；{@code Del}/{@code Get} 用）
 * @param typeCode 类型编码（≤64，行身份，{@code Up} 时不可改）
 * @param typeName 类型名称（≤128）
 * @param status   ENABLED/DISABLED
 * @param builtin  是否平台内置（内置类型不可删）
 * @param itemCount 该类型下**未删除**的字典项数量（列表页展示；由查询聚合得到，5.3）
 * @param remark   备注
 * @param version  乐观锁版本
 */
public record DictTypeDTO(
        long id,
        String typeCode,
        String typeName,
        String status,
        boolean builtin,
        int itemCount,
        String remark,
        int version) {
}
