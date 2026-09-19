package com.eaio.platform.api.dto;

import java.time.Instant;

/**
 * 参数（跨模块 DTO，P1 册 5.3 契约 V1）。
 *
 * <p><b>契约补全</b>：设计册 5.3 的字段清单未列 {@code id}，但同册 5.2 的 {@code /platform/param/Del}
 * 入参是 {@code {id, version}}——不给 id 前端无法定位行，故本 DTO 显式带上。
 *
 * @param id         行 ID（雪花；{@code Del}/{@code Up} 用）
 * @param paramKey   参数键（≤128）
 * @param paramLevel 级别名 SYSTEM/ORG/USER
 * @param ownerId    归属 ID：SYSTEM 级恒 0，ORG 级为组织 ID，USER 级为用户 ID
 * @param paramValue 值；{@code encrypted = true} 时**恒为 {@code ******}（不因调用方是管理端而回显明文）
 * @param valueType  STRING/INT/BOOL/DECIMAL/JSON/SECRET
 * @param paramGroup 分组名
 * @param encrypted  是否加密存储（SECRET 类）
 * @param builtin    是否平台内置（内置不可删，删即 20005）
 * @param hotReload  false 表示改值需重启才生效
 * @param version    乐观锁版本
 * @param updatedAt  最近更新时间（未更新过为空）
 */
public record ParamDTO(
        long id,
        String paramKey,
        String paramLevel,
        long ownerId,
        String paramValue,
        String valueType,
        String paramGroup,
        boolean encrypted,
        boolean builtin,
        boolean hotReload,
        int version,
        Instant updatedAt) {
}
