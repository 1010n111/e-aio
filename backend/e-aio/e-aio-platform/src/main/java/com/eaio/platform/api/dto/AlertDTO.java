package com.eaio.platform.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record AlertDTO(
    long id,
    String ruleCode,
    String severity,
    String status,
    String title,
    String detail,
    BigDecimal metricValue,
    BigDecimal thresholdValue,
    Instant firstTriggerTime,
    Instant lastTriggerTime,
    int triggerCount,
    Long ackBy,
    Instant ackTime,
    Instant resolveTime,
    Long noticeId,
    int version) {}
