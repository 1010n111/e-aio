package com.eaio.platform.api.dto;

public record AlertRuleQuery(
    String ruleCode,
    String metricKey,
    Boolean enabled,
    Integer pageNum,
    Integer pageSize,
    String orderBy,
    String orderDir) {}
