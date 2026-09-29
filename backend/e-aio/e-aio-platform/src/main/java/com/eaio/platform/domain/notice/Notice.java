package com.eaio.platform.domain.notice;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

@TableName("eaio_platform.notice")
public class Notice {

    @TableId(type = IdType.INPUT)
    private Long id;
    private String title;
    private String content;
    private String scopeType;
    private String publishStatus;
    private Instant publishTime;
    private Instant expireTime;
    private boolean topFlag;
    private Long publisherId;
    private long publisherOrgId;
    private Instant createdAt;
    private Long createdBy;
    private Instant updatedAt;
    private Long updatedBy;
    @Version
    private Integer version;
    @TableLogic
    private Boolean deleted;

    public Long getId() {

        return id;

    }
    public void setId(Long id) {
        this.id = id;
    }
    public String getTitle() {
        return title;
    }
    public void setTitle(String title) {
        this.title = title;
    }
    public String getContent() {
        return content;
    }
    public void setContent(String content) {
        this.content = content;
    }
    public String getScopeType() {
        return scopeType;
    }
    public void setScopeType(String scopeType) {
        this.scopeType = scopeType;
    }
    public String getPublishStatus() {
        return publishStatus;
    }
    public void setPublishStatus(String publishStatus) {
        this.publishStatus = publishStatus;
    }
    public Instant getPublishTime() {
        return publishTime;
    }
    public void setPublishTime(Instant publishTime) {
        this.publishTime = publishTime;
    }
    public Instant getExpireTime() {
        return expireTime;
    }
    public void setExpireTime(Instant expireTime) {
        this.expireTime = expireTime;
    }
    public boolean isTopFlag() {
        return topFlag;
    }
    public void setTopFlag(boolean topFlag) {
        this.topFlag = topFlag;
    }
    public Long getPublisherId() {
        return publisherId;
    }
    public void setPublisherId(Long publisherId) {
        this.publisherId = publisherId;
    }
    public long getPublisherOrgId() {
        return publisherOrgId;
    }
    public void setPublisherOrgId(long publisherOrgId) {
        this.publisherOrgId = publisherOrgId;
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
