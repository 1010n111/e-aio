package com.eaio.platform.domain.monitor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.math.BigDecimal;
import java.time.Instant;

@TableName("eaio_platform.alert_rule")
public class AlertRule {
  @TableId(type = IdType.INPUT)
  private Long id;

  private String ruleCode;
  private String ruleName;
  private String metricKey;
  private String operator;
  private BigDecimal threshold;
  private Integer durationSeconds;
  private String severity;
  private Integer silenceSeconds;
  private Boolean notifySite;
  private Boolean enabled;
  private Boolean builtin;
  private String remark;
  private Instant createdAt;
  private Long createdBy;
  private Instant updatedAt;
  private Long updatedBy;
  @Version private Integer version;
  @TableLogic private Boolean deleted;

  public Long getId() {
    return id;
  }

  public void setId(Long v) {
    id = v;
  }

  public String getRuleCode() {
    return ruleCode;
  }

  public void setRuleCode(String v) {
    ruleCode = v;
  }

  public String getRuleName() {
    return ruleName;
  }

  public void setRuleName(String v) {
    ruleName = v;
  }

  public String getMetricKey() {
    return metricKey;
  }

  public void setMetricKey(String v) {
    metricKey = v;
  }

  public String getOperator() {
    return operator;
  }

  public void setOperator(String v) {
    operator = v;
  }

  public BigDecimal getThreshold() {
    return threshold;
  }

  public void setThreshold(BigDecimal v) {
    threshold = v;
  }

  public Integer getDurationSeconds() {
    return durationSeconds;
  }

  public void setDurationSeconds(Integer v) {
    durationSeconds = v;
  }

  public String getSeverity() {
    return severity;
  }

  public void setSeverity(String v) {
    severity = v;
  }

  public Integer getSilenceSeconds() {
    return silenceSeconds;
  }

  public void setSilenceSeconds(Integer v) {
    silenceSeconds = v;
  }

  public Boolean getNotifySite() {
    return notifySite;
  }

  public void setNotifySite(Boolean v) {
    notifySite = v;
  }

  public Boolean getEnabled() {
    return enabled;
  }

  public void setEnabled(Boolean v) {
    enabled = v;
  }

  public Boolean getBuiltin() {
    return builtin;
  }

  public void setBuiltin(Boolean v) {
    builtin = v;
  }

  public String getRemark() {
    return remark;
  }

  public void setRemark(String v) {
    remark = v;
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

  public Boolean getDeleted() {
    return deleted;
  }

  public void setDeleted(Boolean v) {
    deleted = v;
  }
}
