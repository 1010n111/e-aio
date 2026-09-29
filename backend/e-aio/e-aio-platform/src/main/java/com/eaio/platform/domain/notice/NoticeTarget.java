package com.eaio.platform.domain.notice;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("eaio_platform.notice_target")
public class NoticeTarget {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Long noticeId;
    private String targetType;
    private Long targetId;
    private Instant createdAt;
    private Long createdBy;

    public Long getId() {

        return id;

    }
    public void setId(Long id) {
        this.id = id;
    }
    public Long getNoticeId() {
        return noticeId;
    }
    public void setNoticeId(Long noticeId) {
        this.noticeId = noticeId;
    }
    public String getTargetType() {
        return targetType;
    }
    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }
    public Long getTargetId() {
        return targetId;
    }
    public void setTargetId(Long targetId) {
        this.targetId = targetId;
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
}
