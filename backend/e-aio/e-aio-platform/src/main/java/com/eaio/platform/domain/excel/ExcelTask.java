package com.eaio.platform.domain.excel;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.eaio.platform.infrastructure.persistence.JsonbStringTypeHandler;

@TableName(value = "eaio_platform.excel_task", autoResultMap = true)
public class ExcelTask {

    @TableId(type = IdType.INPUT)
    private Long id;
    private String taskType;
    private String bizType;
    private String templateCode;
    private String status;
    private Integer progressPercent;
    private Integer totalRows;
    private Integer successRows;
    private Integer failRows;
    private Boolean deduplicated;
    private Long sourceFileId;
    private Long resultFileId;
    private Long errorFileId;
    @TableField(value = "request_json", typeHandler = JsonbStringTypeHandler.class)
    private String requestJson;
    private Long submitterId;
    private Long submitterOrgId;
    private String sourceSha256;
    private Instant startTime;
    private Instant finishTime;
    private String errorMessage;
    private Instant createdAt;
    private Long createdBy;
    private Instant updatedAt;
    private Long updatedBy;
    private Integer version;

    public Long getId() {
        return id;
    }

    public void setId(Long value) {
        id = value;
    }

    public String getTaskType() {
        return taskType;
    }

    public void setTaskType(String value) {
        taskType = value;
    }

    public String getBizType() {
        return bizType;
    }

    public void setBizType(String value) {
        bizType = value;
    }

    public String getTemplateCode() {
        return templateCode;
    }

    public void setTemplateCode(String value) {
        templateCode = value;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String value) {
        status = value;
    }

    public Integer getProgressPercent() {
        return progressPercent;
    }

    public void setProgressPercent(Integer value) {
        progressPercent = value;
    }

    public Integer getTotalRows() {
        return totalRows;
    }

    public void setTotalRows(Integer value) {
        totalRows = value;
    }

    public Integer getSuccessRows() {
        return successRows;
    }

    public void setSuccessRows(Integer value) {
        successRows = value;
    }

    public Integer getFailRows() {
        return failRows;
    }

    public void setFailRows(Integer value) {
        failRows = value;
    }

    public Boolean getDeduplicated() {
        return deduplicated;
    }

    public void setDeduplicated(Boolean value) {
        deduplicated = value;
    }

    public Long getSourceFileId() {
        return sourceFileId;
    }

    public void setSourceFileId(Long value) {
        sourceFileId = value;
    }

    public Long getResultFileId() {
        return resultFileId;
    }

    public void setResultFileId(Long value) {
        resultFileId = value;
    }

    public Long getErrorFileId() {
        return errorFileId;
    }

    public void setErrorFileId(Long value) {
        errorFileId = value;
    }

    public String getRequestJson() {
        return requestJson;
    }

    public void setRequestJson(String value) {
        requestJson = value;
    }

    public Long getSubmitterId() {
        return submitterId;
    }

    public void setSubmitterId(Long value) {
        submitterId = value;
    }

    public Long getSubmitterOrgId() {
        return submitterOrgId;
    }

    public void setSubmitterOrgId(Long value) {
        submitterOrgId = value;
    }

    public String getSourceSha256() {
        return sourceSha256;
    }

    public void setSourceSha256(String value) {
        sourceSha256 = value;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public void setStartTime(Instant value) {
        startTime = value;
    }

    public Instant getFinishTime() {
        return finishTime;
    }

    public void setFinishTime(Instant value) {
        finishTime = value;
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

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant value) {
        updatedAt = value;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(Long value) {
        updatedBy = value;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer value) {
        version = value;
    }
}
