package com.eaio.platform.api.dto;

public record ExcelTaskErrorQuery(String taskId, Integer pageNum, Integer pageSize) {

    public ExcelTaskErrorQuery {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
    }
}
