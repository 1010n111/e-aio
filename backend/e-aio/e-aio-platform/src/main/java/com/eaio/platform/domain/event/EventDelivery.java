package com.eaio.platform.domain.event;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.eaio.platform.infrastructure.persistence.JsonbStringTypeHandler;

/**
 * 事件投递登记行（P1 册 4.3.14；表 {@code eaio_platform.event_delivery}）。
 *
 * <p>两处映射细节（与 {@code Job} 同款）：
 * <ol>
 *   <li>{@code payload_json} 是 **JSONB**，必须带 {@code JsonbStringTypeHandler} + {@code autoResultMap}
 *       （普通 String 绑定会让 PG 报"column is of type jsonb but expression is of type character varying"）；</li>
 *   <li>{@code version} 参与乐观锁（{@code MybatisPlusConfig} 已装配 {@code OptimisticLockerInnerInterceptor}），
 *       但本表的四次状态流转（成功/失败/死信/人工重放）都用 Mapper 的显式 UPDATE SQL 做
 *       <b>条件更新 + 原子推进</b>（{@code WHERE status = ...}），不走 {@code updateById}——
 *       "只有当前状态符合才允许改"是并发正确性的落点（两个实例同时扫到同一条只可能有一个改成 DONE）。</li>
 * </ol>
 *
 * <p><b>字段用包装类型</b>（同 {@code JobRun} 的实测缺陷登记）：局部更新用的临时实体上，基本类型
 * 恒为 0 且不算 null，会把库里的计数列一起改写；本表虽然不用 {@code updateById}，仍保持包装类型
 * 以免后来者踩同一个坑。
 */
@TableName(value = "eaio_platform.event_delivery", autoResultMap = true)
public class EventDelivery {

    @TableId(type = IdType.INPUT)
    private Long id;

    /** 消费侧幂等键（{@code uk_event_delivery_event_id}；重投与人工重放都靠它去重）。 */
    private String eventId;

    /** 事件类型（事件 record 的**全限定类名**，重投时按它反序列化 {@code payload_json}）。 */
    private String eventType;

    /** 事件载荷快照（JSON 对象文本）；只放 ID 与标量（4.3.14 的列注释）。 */
    @TableField(value = "payload_json", typeHandler = JsonbStringTypeHandler.class)
    private String payloadJson;

    /** {@code RETRYING}/{@code DONE}/{@code DEAD}（{@code ck_event_delivery_status}）。 */
    private String status;

    /** 第几次投递尝试（1 起）：首次登记即 1，失败后 +1；它同时是退避公式里的指数。 */
    private Integer attemptCount;

    /** 最大投递尝试次数（登记时从 {@code platform.event.retry-max} 取快照，默认 5）。 */
    private Integer maxAttempt;

    /** 下次重投时刻：首次登记为 {@code null}（还没失败过），失败后按退避写入。 */
    private Instant nextRetryTime;

    /** 最后一次错误摘要（人工重放时**追加**，保留历史）。 */
    private String lastError;

    private String traceId;

    /** 投递成功的时刻（{@code DONE} 时非空；重放时清空）。 */
    private Instant finishTime;

    private Instant createdAt;
    private Long createdBy;
    private Instant updatedAt;
    private Long updatedBy;

    @Version
    private Integer version;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(Integer attemptCount) {
        this.attemptCount = attemptCount;
    }

    public Integer getMaxAttempt() {
        return maxAttempt;
    }

    public void setMaxAttempt(Integer maxAttempt) {
        this.maxAttempt = maxAttempt;
    }

    public Instant getNextRetryTime() {
        return nextRetryTime;
    }

    public void setNextRetryTime(Instant nextRetryTime) {
        this.nextRetryTime = nextRetryTime;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public Instant getFinishTime() {
        return finishTime;
    }

    public void setFinishTime(Instant finishTime) {
        this.finishTime = finishTime;
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
}
