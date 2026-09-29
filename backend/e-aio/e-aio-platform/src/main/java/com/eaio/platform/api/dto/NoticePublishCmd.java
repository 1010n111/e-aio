package com.eaio.platform.api.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 公告新增/更新/立即发布入参；正文按纯文本处理。 */
public record NoticePublishCmd(
        Long id,
        @NotBlank @Size(max = 200) String title,
        @NotNull String content,
        @NotBlank String scopeType,
        String targetType,
        List<Long> targetIds,
        Instant publishTime,
        Instant expireTime,
        boolean topFlag) {
}
