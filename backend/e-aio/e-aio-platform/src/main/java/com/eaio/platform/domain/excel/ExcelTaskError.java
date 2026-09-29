package com.eaio.platform.domain.excel;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("eaio_platform.excel_task_error")
public class ExcelTaskError {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long taskId;
    private Integer rowNum;
    private String columnName;
    private String cellValue;
    private String errorMessage;
    private Instant createdAt;
    private Long createdBy;

    public Long getId() {
        return id;
    }

    public void setId(Long value) {
        id = value;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long value) {
        taskId = value;
    }

    public Integer getRowNum() {
        return rowNum;
    }

    public void setRowNum(Integer value) {
        rowNum = value;
    }

    public String getColumnName() {
        return columnName;
    }

    public void setColumnName(String value) {
        columnName = value;
    }

    public String getCellValue() {
        return cellValue;
    }

    public void setCellValue(String value) {
        cellValue = value;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String value) {
        errorMessage = value;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant value) {
        createdAt = value;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long value) {
        createdBy = value;
    }
}
