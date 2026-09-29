package com.eaio.platform.api.dto;

/** Excel task management page filters. */
public record ExcelTaskQuery(
        String taskType,
        String bizType,
        String status,
        Long submitterId,
        Integer pageNum,
        Integer pageSize,
        String orderBy,
        String orderDir) {
}
