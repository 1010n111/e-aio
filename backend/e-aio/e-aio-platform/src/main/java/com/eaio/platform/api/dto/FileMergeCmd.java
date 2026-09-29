package com.eaio.platform.api.dto;

/** 分片合并请求；会话 DONE 时返回原文件，保证网络重试幂等。 */
public record FileMergeCmd(String uploadId, String bizType, Long bizId) {
}
