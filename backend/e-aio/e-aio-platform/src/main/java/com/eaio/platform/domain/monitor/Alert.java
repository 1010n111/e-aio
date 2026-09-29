package com.eaio.platform.domain.monitor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.math.BigDecimal;
import java.time.Instant;

@TableName("eaio_platform.alert")
public class Alert {
  @TableId(type = IdType.INPUT)
  private Long id;

  private Long ruleId;
  private String ruleCode;
  private String severity;
  private String status;
  private String title;
  private String detail;
  private BigDecimal metricValue;
  private BigDecimal thresholdValue;
  private Instant firstTriggerTime;
  private Instant lastTriggerTime;
  private Integer triggerCount;
  private Long ackBy;
  private Instant ackTime;
  private Instant resolveTime;
  private Long noticeId;
  private Instant createdAt;
  private Long createdBy;
  private Instant updatedAt;
  private Long updatedBy;
  @Version private Integer version;

  public Long getId() {
    return id;
  }

  public void setId(Long v) {
    id = v;
  }

  public Long getRuleId() {
    return ruleId;
  }

  public void setRuleId(Long v) {
    ruleId = v;
  }

  public String getRuleCode() {
    return ruleCode;
  }

  public void setRuleCode(String v) {
    ruleCode = v;
  }

  public String getSeverity() {
    return severity;
  }

  public void setSeverity(String v) {
    severity = v;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String v) {
    status = v;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String v) {
    title = v;
  }

  public String getDetail() {
    return detail;
  }

  public void setDetail(String v) {
    detail = v;
  }

  public BigDecimal getMetricValue() {
    return metricValue;
  }

  public void setMetricValue(BigDecimal v) {
    metricValue = v;
  }

  public BigDecimal getThresholdValue() {
    return thresholdValue;
  }

  public void setThresholdValue(BigDecimal v) {
    thresholdValue = v;
  }

  public Instant getFirstTriggerTime() {
    return firstTriggerTime;
  }

  public void setFirstTriggerTime(Instant v) {
    firstTriggerTime = v;
  }

  public Instant getLastTriggerTime() {
    return lastTriggerTime;
  }

  public void setLastTriggerTime(Instant v) {
    lastTriggerTime = v;
  }

  public Integer getTriggerCount() {
    return triggerCount;
  }

  public void setTriggerCount(Integer v) {
    triggerCount = v;
  }

  public Long getAckBy() {
    return ackBy;
  }

  public void setAckBy(Long v) {
    ackBy = v;
  }

  public Instant getAckTime() {
    return ackTime;
  }

  public void setAckTime(Instant v) {
    ackTime = v;
  }

  public Instant getResolveTime() {
    return resolveTime;
  }

  public void setResolveTime(Instant v) {
    resolveTime = v;
  }

  public Long getNoticeId() {
    return noticeId;
  }

  public void setNoticeId(Long v) {
    noticeId = v;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant v) {
    createdAt = v;
  }

  public Long getCreatedBy() {
    return createdBy;
  }

  public void setCreatedBy(Long v) {
    createdBy = v;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant v) {
    updatedAt = v;
  }

  public Long getUpdatedBy() {
    return updatedBy;
  }

  public void setUpdatedBy(Long v) {
    updatedBy = v;
  }

  public Integer getVersion() {
    return version;
  }

  public void setVersion(Integer v) {
    version = v;
  }
}
