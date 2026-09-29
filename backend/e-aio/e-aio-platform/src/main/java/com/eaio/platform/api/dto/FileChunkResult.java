package com.eaio.platform.api.dto;

/** 分片上传回执。received 是当前会话已落盘的分片数。 */
public record FileChunkResult(String uploadId, int received, int chunkTotal) {
}
