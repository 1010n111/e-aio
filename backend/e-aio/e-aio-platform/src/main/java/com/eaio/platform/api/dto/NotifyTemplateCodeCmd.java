package com.eaio.platform.api.dto;

import jakarta.validation.constraints.NotBlank;

public record NotifyTemplateCodeCmd(@NotBlank String templateCode, Integer version) {
}
