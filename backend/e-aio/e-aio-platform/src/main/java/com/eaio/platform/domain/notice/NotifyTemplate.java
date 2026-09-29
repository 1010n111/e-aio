package com.eaio.platform.domain.notice;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.eaio.platform.infrastructure.persistence.JsonbStringTypeHandler;

@TableName(value = "eaio_platform.notify_template", autoResultMap = true)
public class NotifyTemplate {
    @TableId(type = IdType.INPUT)
    private Long id;
    private String templateCode;
    private String templateName;
    private String channel;
    private String titleTemplate;
    private String contentTemplate;
    @TableField(value = "variables_json", typeHandler = JsonbStringTypeHandler.class)
    private String variablesJson;
    private String status;
    private boolean builtin;
    private String remark;
    private Instant createdAt;
    private Long createdBy;
    private Instant updatedAt;
    private Long updatedBy;
    @Version
    private Integer version;
    @TableLogic
    private Boolean deleted;

    public Long getId() {

        return id;

    }
    public void setId(Long id) {
        this.id = id;
    }
    public String getTemplateCode() {
        return templateCode;
    }
    public void setTemplateCode(String templateCode) {
        this.templateCode = templateCode;
    }
    public String getTemplateName() {
        return templateName;
    }
    public void setTemplateName(String templateName) {
        this.templateName = templateName;
    }
    public String getChannel() {
        return channel;
    }
    public void setChannel(String channel) {
        this.channel = channel;
    }
    public String getTitleTemplate() {
        return titleTemplate;
    }
    public void setTitleTemplate(String titleTemplate) {
        this.titleTemplate = titleTemplate;
    }
    public String getContentTemplate() {
        return contentTemplate;
    }
    public void setContentTemplate(String contentTemplate) {
        this.contentTemplate = contentTemplate;
    }
    public String getVariablesJson() {
        return variablesJson;
    }
    public void setVariablesJson(String variablesJson) {
        this.variablesJson = variablesJson;
    }
    public String getStatus() {
        return status;
    }
    public void setStatus(String status) {
        this.status = status;
    }
    public boolean isBuiltin() {
        return builtin;
    }
    public void setBuiltin(boolean builtin) {
        this.builtin = builtin;
    }
    public String getRemark() {
        return remark;
    }
    public void setRemark(String remark) {
        this.remark = remark;
    }
    public Instant getCreatedAt() {
        return createdAt;
    }
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
    public Long getCreatedBy() {
        return createdBy;
    }
    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }
    public Instant getUpdatedAt() {
        return updatedAt;
    }
    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
    public Long getUpdatedBy() {
        return updatedBy;
    }
    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }
    public Integer getVersion() {
        return version;
    }
    public void setVersion(Integer version) {
        this.version = version;
    }
    public Boolean getDeleted() {
        return deleted;
    }
    public void setDeleted(Boolean deleted) {
        this.deleted = deleted;
    }
}
