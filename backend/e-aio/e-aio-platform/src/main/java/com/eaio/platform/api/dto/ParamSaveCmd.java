package com.eaio.platform.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 参数写入命令（跨模块 DTO，P1 册 5.3 契约 V1）。
 *
 * <p>两条硬口径：{@code paramLevel = SYSTEM} 时 {@code ownerId} 必须为 0（否则 20006）；
 * {@code Up} 时 {@code version} 必填（乐观锁，缺失即 10003）。
 *
 * @param paramKey   参数键
 * @param paramLevel SYSTEM/ORG/USER
 * @param ownerId    归属 ID（SYSTEM 级必须为 0）
 * @param paramValue 值（SECRET 类由写入方给明文，落库前加密）
 * @param valueType  STRING/INT/BOOL/DECIMAL/JSON/SECRET
 * @param paramGroup 分组名（空则按 default 处理）
 * @param remark     备注
 * @param version    乐观锁版本（Add 可空，Up 必填）
 */
public record ParamSaveCmd(
        @NotBlank @Size(max = 128) String paramKey,
        @NotBlank String paramLevel,
        long ownerId,
        String paramValue,
        @NotBlank String valueType,
        @Size(max = 64) String paramGroup,
        @Size(max = 255) String remark,
        Integer version) {
}
