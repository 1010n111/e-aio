package com.eaio.platform.domain.job;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.eaio.platform.infrastructure.persistence.JsonbStringTypeHandler;

/**
 * 定时任务注册表行（P1 册 4.3.9；表 {@code eaio_platform.job}）。
 *
 * <p>两处映射细节（与 {@code DictItem} 同款，理由见 V3/V5 脚本头）：
 * <ol>
 *   <li>{@code params_json} 是 **JSONB**，必须带 {@code JsonbStringTypeHandler} + {@code autoResultMap}
 *       （普通 String 绑定会让 PG 报"column is of type jsonb but expression is of type character varying"）；</li>
 *   <li>{@code uk_job_code} 是 {@code WHERE deleted = false} 的部分唯一索引 → {@code @TableLogic}
 *       逻辑删除（{@code deleted} 列是 BOOLEAN，全局配置已把 logic-delete-value 设成 "true"）。</li>
 * </ol>
 *
 * <p>实体上**没有**任何"可执行点"字段：{@code handler_code} 只是注册表里的一个键，解析它的唯一途径是
 * {@code JobHandlerRegistry}（代码注册的 Bean）。按字符串反射调方法是明确否决的（3.4.1，RCE 面）。
 */
@TableName(value = "eaio_platform.job", autoResultMap = true)
public class Job {

    @TableId(type = IdType.INPUT)
    private Long id;

    private String jobCode;
    private String jobName;
    private String handlerCode;
    private String cron;

    /** 任务参数（JSON 对象文本）；无参数为 {@code null}。 */
    @TableField(value = "params_json", typeHandler = JsonbStringTypeHandler.class)
    private String paramsJson;

    private boolean enabled;
    private int timeoutSeconds;
    private int retryMax;
    private int backoffSeconds;
    private boolean allowConcurrent;

    /** 下次触发时刻：**仅供管理页展示**，不参与调度决策（4.3.9 的列注释）。 */
    private Instant nextRunTime;
    private Instant lastRunTime;
    private String lastStatus;
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

    public String getJobCode() {
        return jobCode;
    }

    public void setJobCode(String jobCode) {
        this.jobCode = jobCode;
    }

    public String getJobName() {
        return jobName;
    }

    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    public String getHandlerCode() {
        return handlerCode;
    }

    public void setHandlerCode(String handlerCode) {
        this.handlerCode = handlerCode;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public String getParamsJson() {
        return paramsJson;
    }

    public void setParamsJson(String paramsJson) {
        this.paramsJson = paramsJson;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getRetryMax() {
        return retryMax;
    }

    public void setRetryMax(int retryMax) {
        this.retryMax = retryMax;
    }

    public int getBackoffSeconds() {
        return backoffSeconds;
    }

    public void setBackoffSeconds(int backoffSeconds) {
        this.backoffSeconds = backoffSeconds;
    }

    public boolean isAllowConcurrent() {
        return allowConcurrent;
    }

    public void setAllowConcurrent(boolean allowConcurrent) {
        this.allowConcurrent = allowConcurrent;
    }

    public Instant getNextRunTime() {
        return nextRunTime;
    }

    public void setNextRunTime(Instant nextRunTime) {
        this.nextRunTime = nextRunTime;
    }

    public Instant getLastRunTime() {
        return lastRunTime;
    }

    public void setLastRunTime(Instant lastRunTime) {
        this.lastRunTime = lastRunTime;
    }

    public String getLastStatus() {
        return lastStatus;
    }

    public void setLastStatus(String lastStatus) {
        this.lastStatus = lastStatus;
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
