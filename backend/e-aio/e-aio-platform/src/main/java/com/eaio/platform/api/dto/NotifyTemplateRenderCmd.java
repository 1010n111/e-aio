package com.eaio.platform.api.dto;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;

public record NotifyTemplateRenderCmd(@NotBlank String templateCode, Map<String, String> params) {
}
