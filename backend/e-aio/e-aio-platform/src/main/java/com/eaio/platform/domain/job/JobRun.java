package com.eaio.platform.domain.job;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 任务运行日志行（P1 册 4.3.10；表 {@code eaio_platform.job_run}）。
 *
 * <p><b>为什么没有 {@code @Version}/{@code @TableLogic}（与统一列口径的显式例外）</b>：这张表是
 * 只追加的运行事实——没有 {@code updated_at}/{@code version}/{@code deleted} 列（见 V5 脚本头的例外登记），
 * 状态流转是"按 id 覆盖终态列"，用不到乐观锁；清理走物理删除（保留期 3.4.4）。
 *
 * <p><b>状态流转只能向前</b>：一次执行的终态写定之后不再被改写（{@code RETRYING} 行被重投扫描器
 * 以 {@code UPDATE ... WHERE status = 'RETRYING'} 认领，认领本身就是"只允许一个实例拿到"的原子操作）。
 */
@TableName("eaio_platform.job_run")
public class JobRun {

    @TableId(type = IdType.INPUT)
    private Long id;

    private String jobCode;
    private String triggerType;
    private String status;

    /**
     * 第几次尝试（1 起）。
     *
     * <p><b>为什么是包装类型 {@code Integer} 而不是 {@code int}</b>（实测缺陷，2026-09-19）：状态流转是
     * "按 id 局部更新"（{@code updateById} 只写非 null 字段），基本类型 {@code int} 在部分更新用的临时实体上
     * 恒为 0 且**不算 null**，于是"写终态"会把库里的 {@code attempt} 一起改写成 0——次数静默丢失，
     * 重投上限判定（{@code attempt ≤ retry_max}）随之失效。包装类型让"没打算改 attempt"真的不改。
     */
    private Integer attempt;

    private Instant startTime;
    private Instant endTime;
    private Integer durationMs;
    private Instant nextRetryTime;

    /** 执行实例标识（{@code host:port} 形态）；同一条重投行的 node_id 会被改写为接管实例——它记录的是
     * "谁最后跑的"，不是"谁最初创建的"。 */
    private String nodeId;

    private String traceId;
    private String errorMessage;
    private Instant createdAt;
    private Long createdBy;

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

    public String getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(String triggerType) {
        this.triggerType = triggerType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getAttempt() {
        return attempt;
    }

    public void setAttempt(Integer attempt) {
        this.attempt = attempt;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public void setEndTime(Instant endTime) {
        this.endTime = endTime;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Integer durationMs) {
        this.durationMs = durationMs;
    }

    public Instant getNextRetryTime() {
        return nextRetryTime;
    }

    public void setNextRetryTime(Instant nextRetryTime) {
        this.nextRetryTime = nextRetryTime;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
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
}
