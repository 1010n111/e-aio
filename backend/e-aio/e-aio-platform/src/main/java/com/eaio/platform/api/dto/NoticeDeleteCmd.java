package com.eaio.platform.api.dto;

import jakarta.validation.constraints.Positive;

public record NoticeDeleteCmd(@Positive long id, int version) {
}
