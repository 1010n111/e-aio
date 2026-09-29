package com.eaio.platform.api.dto;

import java.io.InputStream;

/** 一个分片上传请求；首片需要同时给出会话元数据，后续片只需 uploadId/index。 */
public record FileChunkCmd(
        String uploadId,
        int chunkIndex,
        Integer chunkTotal,
        Integer chunkSize,
        String chunkSha256,
        String fileName,
        Long expectedSize,
        String fileSha256,
        InputStream content) {
}
