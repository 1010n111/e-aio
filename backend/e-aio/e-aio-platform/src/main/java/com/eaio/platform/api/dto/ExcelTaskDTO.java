package com.eaio.platform.api.dto;

import java.time.Instant;
import java.util.List;

/** Full task status returned to the management page. */
public record ExcelTaskDTO(
        String taskId,
        String taskType,
        String bizType,
        String templateCode,
        String status,
        int progressPercent,
        long totalRows,
        long successRows,
        long failRows,
        boolean deduplicated,
        Long sourceFileId,
        Long resultFileId,
        Long errorFileId,
        Instant startTime,
        Instant finishTime,
        String errorMessage,
        boolean errorTruncated,
        List<ExcelTaskErrorDTO> errors) {

    public ExcelTaskDTO {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
