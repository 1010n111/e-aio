package com.eaio.platform.api.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 绑定业务对象入参（P1 册 5.2 的 {@code /platform/file/Bind}，幂等键：是）。
 *
 * <p>幂等由唯一键 {@code uk_file_binding_triple(file_id, biz_type, biz_id)} + {@code ON CONFLICT DO NOTHING}
 * 承担：重复绑定**不报错**（5.4 的 {@code bind} 约定），也不产生第二行。
 *
 * @param bizType  业务类型后缀（如 {@code crm.customer}；platform 不解释其含义，只做索引）
 * @param bizId    业务对象 ID
 * @param fileIds  文件 ID 列表（必填、非空；重复项在服务端去重）
 */
public record FileBindCmd(
        @NotBlank String bizType,
        @NotNull @Positive Long bizId,
        @NotEmpty List<Long> fileIds) {
}
