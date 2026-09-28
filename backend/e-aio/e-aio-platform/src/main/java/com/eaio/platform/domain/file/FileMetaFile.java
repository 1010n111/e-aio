package com.eaio.platform.domain.file;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

/**
 * 文件元数据行（P1 册 3.3.2 的 {@code FileMetaFile}；表见 4.3.5）。二进制不在库里，这里只有元数据与存储路径。
 *
 * <p>两条不可变口径：
 * <ul>
 *   <li>{@code storageType} 写入后**不可改**：下载/删除按行选择存储适配器（裁决 P1-C5）——若按"当前配置"
 *       选适配器，切换存储类型的那一刻历史文件全部读不到；</li>
 *   <li>{@code storagePath} 由服务端生成（{@code fileId} + 白名单扩展名），客户端永远碰不到它
 *       （3.3.5 第 5 条）。</li>
 * </ul>
 */
@TableName("eaio_platform.file")
public class FileMetaFile {

    @TableId(type = IdType.INPUT)
    private Long id;

    private String originalName;
    private String extension;
    private String contentType;
    private Long sizeBytes;
    private String sha256;
    private String storageType;
    private String storagePath;
    private Long uploaderId;
    private Long uploaderOrgId;
    private String source;
    private Instant createdAt;
    private Long createdBy;
    private Instant updatedAt;
    private Long updatedBy;

    /** 乐观锁（{@code Del} 带 version）：写路径带 version 条件更新，影响行数为 0 即 10003。 */
    @Version
    private Integer version;

    /**
     * 逻辑删除：删除 = 标记 + 事件，物理清理由任务 {@code platform.file.orphan.clean} 负责（3.3.7）。
     *
     * <p>孤儿清理要**看得见**已软删的行（按 {@code deleted_at + purge_days} 物理删），那两条查询走
     * {@code FileMapper} 的显式 SQL 绕过本注解自动追加的条件。
     */
    @TableLogic
    private Boolean deleted;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOriginalName() {
        return originalName;
    }

    public void setOriginalName(String originalName) {
        this.originalName = originalName;
    }

    public String getExtension() {
        return extension;
    }

    public void setExtension(String extension) {
        this.extension = extension;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public String getStorageType() {
        return storageType;
    }

    public void setStorageType(String storageType) {
        this.storageType = storageType;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;
    }

    public Long getUploaderId() {
        return uploaderId;
    }

    public void setUploaderId(Long uploaderId) {
        this.uploaderId = uploaderId;
    }

    public Long getUploaderOrgId() {
        return uploaderOrgId;
    }

    public void setUploaderOrgId(Long uploaderOrgId) {
        this.uploaderOrgId = uploaderOrgId;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Boolean getDeleted() {
        return deleted;
    }

    public void setDeleted(Boolean deleted) {
        this.deleted = deleted;
    }
}
