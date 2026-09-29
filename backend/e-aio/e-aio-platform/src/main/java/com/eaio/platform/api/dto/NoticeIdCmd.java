package com.eaio.platform.api.dto;

import jakarta.validation.constraints.Positive;

public record NoticeIdCmd(@Positive long id) {
}
