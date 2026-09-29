package com.eaio.platform.api.dto;

import jakarta.validation.constraints.Positive;

public record AlertIdCmd(@Positive long id) {}
