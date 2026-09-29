package com.eaio.platform.domain.file;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

/** 分片上传会话元数据。二进制只落本地分片目录，不进数据库。 */
@TableName("eaio_platform.file_upload_session")
public class FileUploadSession {
    @TableId(type = IdType.INPUT)
    private Long id;
    private String uploadId;
    private String fileName;
    private String extension;
    private Long expectedSize;
    private Integer chunkSize;
    private Integer chunkTotal;
    private String sha256;
    private String storageType;
    private String status;
    private Instant expireTime;
    private Long fileId;
    private Instant createdAt;
    private Long createdBy;
    private Instant updatedAt;
    private Long updatedBy;
    @Version
    private Integer version;

    public Long getId() {
        return id;
    }

    public void setId(Long value) {
        id = value;
    }

    public String getUploadId() {
        return uploadId;
    }

    public void setUploadId(String value) {
        uploadId = value;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String value) {
        fileName = value;
    }

    public String getExtension() {
        return extension;
    }

    public void setExtension(String value) {
        extension = value;
    }

    public Long getExpectedSize() {
        return expectedSize;
    }

    public void setExpectedSize(Long value) {
        expectedSize = value;
    }

    public Integer getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(Integer value) {
        chunkSize = value;
    }

    public Integer getChunkTotal() {
        return chunkTotal;
    }

    public void setChunkTotal(Integer value) {
        chunkTotal = value;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String value) {
        sha256 = value;
    }

    public String getStorageType() {
        return storageType;
    }

    public void setStorageType(String value) {
        storageType = value;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String value) {
        status = value;
    }

    public Instant getExpireTime() {
        return expireTime;
    }

    public void setExpireTime(Instant value) {
        expireTime = value;
    }

    public Long getFileId() {
        return fileId;
    }

    public void setFileId(Long value) {
        fileId = value;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant value) {
        createdAt = value;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long value) {
        createdBy = value;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant value) {
        updatedAt = value;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(Long value) {
        updatedBy = value;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer value) {
        version = value;
    }
}
