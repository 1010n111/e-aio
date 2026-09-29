package com.eaio.platform.domain.notice;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("eaio_platform.notice_read")
public class NoticeRead {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Long noticeId;
    private Long userId;
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
    public Long getUserId() {
        return userId;
    }
    public void setUserId(Long userId) {
        this.userId = userId;
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
