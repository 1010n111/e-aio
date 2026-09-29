package com.eaio.platform.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/** Import submission. */
public record ExcelImportCmd(
        @NotNull @Positive Long fileId,
        @NotBlank String bizType,
        String templateCode,
        @PositiveOrZero int sheetIndex,
        boolean force) {

    /** Backward-compatible constructor; imports default to the first sheet. */
    public ExcelImportCmd(Long fileId, String bizType, String templateCode, boolean force) {
        this(fileId, bizType, templateCode, 0, force);
    }
}
