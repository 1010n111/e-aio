package com.eaio.platform.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record AlertRuleSaveCmd(
    @NotBlank @Size(max = 64) String ruleCode,
    @NotBlank @Size(max = 128) String ruleName,
    @NotBlank @Size(max = 128) String metricKey,
    @NotBlank String operator,
    @NotNull @DecimalMin("-100000000000") @DecimalMax("100000000000") BigDecimal threshold,
    @Min(0) @Max(86400) Integer durationSeconds,
    @NotBlank String severity,
    @Min(0) @Max(604800) Integer silenceSeconds,
    Boolean notifySite,
    Boolean enabled,
    @Size(max = 255) String remark,
    Integer version) {}
