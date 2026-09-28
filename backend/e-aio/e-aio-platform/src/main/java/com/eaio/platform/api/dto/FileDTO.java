package com.eaio.platform.api.dto;

import java.time.Instant;

/**
 * 文件元数据（跨模块 DTO，P1 册 5.3 契约 V1）。
 *
 * <p><b>刻意不含 {@code storagePath}</b>：物理存储路径（本地盘的绝对路径 / S3 对象键）是存储适配器的实现细节，
 * 对外暴露只会变成"绕过权限直接读盘"的入口，也是路径穿越的现成拼图（P1 册 3.3.5、5.3）。库里有该列，
 * DTO 与列表接口里永远没有它。
 *
 * @param id            行 ID（雪花；{@code GetMeta}/{@code Del}/{@code Bind} 用）
 * @param originalName  原始文件名（≤255；Content-Disposition 由它生成）
 * @param extension     扩展名（**小写、不含点**；白名单校验的就是它）
 * @param contentType   客户端声明的 MIME（只作记录，不作判据——客户端可伪造，3.3.5）
 * @param sizeBytes     服务端统计的实际字节数（不信任客户端声明的 size）
 * @param sha256        服务端重算的摘要（十六进制小写，64 位）
 * @param storageType   存储类型 {@code LOCAL}/{@code S3}（写入后不可变，P1-C5）
 * @param uploaderId    上传者 ID（可见性判定的第一依据）
 * @param uploaderOrgId 上传者组织 ID（{@code 0} = 上传时无组织上下文，此时只有本人可下载）
 * @param source        来源 {@code UPLOAD}/{@code CHUNK}/{@code EXPORT}/{@code IMPORT_ERROR}/{@code TEMPLATE}
 * @param createdAt     上传时间
 * @param version       乐观锁版本（{@code Del} 必填）
 * @param url           带外下载链接；只有 {@code GetUrl} 的返回填充，其余接口恒为 {@code null}
 */
public record FileDTO(
        long id,
        String originalName,
        String extension,
        String contentType,
        long sizeBytes,
        String sha256,
        String storageType,
        Long uploaderId,
        long uploaderOrgId,
        String source,
        Instant createdAt,
        int version,
        String url) {
}
