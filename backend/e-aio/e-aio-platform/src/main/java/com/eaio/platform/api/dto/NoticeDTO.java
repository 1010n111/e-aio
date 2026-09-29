package com.eaio.platform.api.dto;

import java.time.Instant;

/** 公告出参；read 只代表当前用户的已读状态。 */
public record NoticeDTO(
        long id,
        String title,
        String content,
        String scopeType,
        String publishStatus,
        Instant publishTime,
        Instant expireTime,
        boolean topFlag,
        boolean read,
        Long publisherId,
        int version) {
}
