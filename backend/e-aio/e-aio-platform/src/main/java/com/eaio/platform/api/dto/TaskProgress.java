package com.eaio.platform.api.dto;

import java.time.Instant;

/** Stable small cross-module polling contract. */
public record TaskProgress(String taskId, String status, int progressPercent, long totalRows, long successRows,
        long failRows, boolean deduplicated, Long resultFileId, Long errorFileId, Instant finishTime,
        String errorMessage) {
}
