package com.eaio.platform.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 字典项写入命令（跨模块 DTO，P1 册 5.3 契约 V1）。
 *
 * <p><b>契约补全</b>：同 {@link DictTypeSaveCmd}，5.3 未给字段名。取 DTO 里可写的字段加 {@code version}；
 * 定位行用 {@code (typeCode, itemValue)}（部分唯一索引 {@code uk_dict_item_type_value} 的键）。
 *
 * <p><b>不含 remark</b>：{@code dict_item.remark} 列存在，但 5.3 的 {@code DictItemDTO} 没有这个字段
 * ——契约里不可见的字段不做成"只能写不能读"的隐性入口（登记在《实现注记（T5）》）。
 *
 * @param typeCode  所属类型编码（必填）
 * @param itemValue 项值（必填，≤128）
 * @param itemLabel 项标签（必填，≤128）
 * @param sortNo    排序号（空则按 0）
 * @param status    ENABLED/DISABLED，空则按 ENABLED（{@code Up} 时为空 = 保持原状态；停用 ≠ 删除：{@code getLabel} 仍可解析）
 * @param isDefault 是否默认项（空则按 false）
 * @param extJson   前端渲染扩展的 JSON 字符串（≤2000，原样入库；无结构校验，3.2.4）
 * @param version   乐观锁版本（Add 可空，Up 必填）
 */
public record DictItemSaveCmd(
        @NotBlank @Size(max = 64) String typeCode,
        @NotBlank @Size(max = 128) String itemValue,
        @NotBlank @Size(max = 128) String itemLabel,
        Integer sortNo,
        @Size(max = 32) String status,
        Boolean isDefault,
        @Size(max = 2000) String extJson,
        Integer version) {
}
