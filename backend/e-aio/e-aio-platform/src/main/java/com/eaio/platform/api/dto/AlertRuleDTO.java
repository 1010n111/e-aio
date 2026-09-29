package com.eaio.platform.api.dto;

import java.math.BigDecimal;

public record AlertRuleDTO(
    long id,
    String ruleCode,
    String ruleName,
    String metricKey,
    String operator,
    BigDecimal threshold,
    int durationSeconds,
    String severity,
    int silenceSeconds,
    boolean notifySite,
    boolean enabled,
    boolean builtin,
    String remark,
    int version) {}
