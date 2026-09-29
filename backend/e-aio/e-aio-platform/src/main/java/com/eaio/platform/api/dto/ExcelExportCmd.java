package com.eaio.platform.api.dto;

import jakarta.validation.constraints.NotBlank;

/** Export submission; requestJson is an auditable, non-secret condition summary. */
public record ExcelExportCmd(
        @NotBlank String bizType,
        String templateCode,
        String requestJson) {
}
