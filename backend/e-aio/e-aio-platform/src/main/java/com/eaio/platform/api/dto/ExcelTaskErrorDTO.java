package com.eaio.platform.api.dto;

import java.time.Instant;

public record ExcelTaskErrorDTO(long id, String taskId, long rowNum, String columnName, String cellValue,
        String errorMessage, Instant createdAt) {
}
