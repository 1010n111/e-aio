package com.eaio.platform.api.dto;

import java.time.Instant;

/**
 * 文件分页查询入参（跨模块 DTO，P1 册 5.3 契约 V1）。
 *
 * <p>分页公共字段：{@code pageNum}（从 1 起，默认 1）、{@code pageSize}（1–200，默认 20）、
 * {@code orderBy}（**白名单**列名，非法值忽略并 WARN——绝不拼任意列名进 SQL）、{@code orderDir}（ASC/DESC）。
 *
 * <p>{@code bizType} + {@code bizId} 是**经由 {@code file_binding} 的关联过滤**：两者都给才生效，
 * 只给一个按"没给"处理（只给 {@code bizType} 会退化成"该业务类型下任何对象的文件"，不是调用方想要的）。
 *
 * @param originalName  原始文件名（模糊）
 * @param bizType       业务类型后缀（精确；与 {@code bizId} 成对）
 * @param bizId         业务对象 ID（精确；与 {@code bizType} 成对）
 * @param uploaderId    上传者 ID（精确）
 * @param uploaderOrgId 上传者组织 ID（精确）
 * @param source        来源（精确）
 * @param createdFrom   上传时间下界（含）
 * @param createdTo     上传时间上界（含）
 * @param pageNum       页码（从 1 起）
 * @param pageSize      页大小（1–200）
 * @param orderBy       排序字段（白名单外的值被忽略）
 * @param orderDir      排序方向 ASC/DESC
 */
public record FileQuery(
        String originalName,
        String bizType,
        Long bizId,
        Long uploaderId,
        Long uploaderOrgId,
        String source,
        Instant createdFrom,
        Instant createdTo,
        Integer pageNum,
        Integer pageSize,
        String orderBy,
        String orderDir) {
}
