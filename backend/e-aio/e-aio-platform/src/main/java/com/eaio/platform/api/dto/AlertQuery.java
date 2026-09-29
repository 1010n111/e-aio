package com.eaio.platform.api.dto;

public record AlertQuery(
    String ruleCode,
    String severity,
    String status,
    Integer pageNum,
    Integer pageSize,
    String orderBy,
    String orderDir) {}
