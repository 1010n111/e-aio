package com.eaio.platform.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 字典类型写入命令（跨模块 DTO，P1 册 5.3 契约 V1）。
 *
 * <p><b>契约补全</b>：5.3 只写"同 DTO 的可写字段 + version"，未给字段名。这里取 DTO 里可写的四个字段
 * （{@code typeCode}/{@code typeName}/{@code status}/{@code remark}）加 {@code version}，**不带 id**：
 * {@code type_code} 是行身份且"Up 时不可修改"（3.2.4），定位行用它即可，多一个 id 就多一种
 * "id 与 typeCode 指向不同行"的自相矛盾输入。
 *
 * @param typeCode 类型编码（必填，≤64）
 * @param typeName 类型名称（必填，≤128）
 * @param status   ENABLED/DISABLED，空则按 ENABLED（{@code Up} 时为空 = 保持原状态）
 * @param remark   备注（≤255）
 * @param version  乐观锁版本（Add 可空，Up 必填）
 */
public record DictTypeSaveCmd(
        @NotBlank @Size(max = 64) String typeCode,
        @NotBlank @Size(max = 128) String typeName,
        @Size(max = 32) String status,
        @Size(max = 255) String remark,
        Integer version) {
}
