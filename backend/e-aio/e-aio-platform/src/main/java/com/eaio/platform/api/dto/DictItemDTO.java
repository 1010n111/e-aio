package com.eaio.platform.api.dto;

/**
 * 字典项（跨模块 DTO，P1 册 5.3 契约 V1）。
 *
 * @param id        行 ID（雪花）
 * @param typeCode  所属类型编码
 * @param itemValue 项值（≤128，(typeCode, itemValue) 是行身份，{@code Up} 时不可改）
 * @param itemLabel 项标签（≤128）
 * @param sortNo    排序号（升序；{@code getItems} 按它返回）
 * @param status    ENABLED/DISABLED（停用项不出现在 {@code getItems}，但 {@code getLabel} 仍可解析）
 * @param isDefault 是否该类型的默认项
 * @param extJson   前端渲染扩展（颜色/图标等）的 **JSON 字符串**；前端自行解析（5.3）
 * @param version   乐观锁版本
 */
public record DictItemDTO(
        long id,
        String typeCode,
        String itemValue,
        String itemLabel,
        int sortNo,
        String status,
        boolean isDefault,
        String extJson,
        int version) {
}
