package com.eaio.platform.api.dto;

/** Row-level error exposed by the cross-module Excel contract. */
public record ExcelRowError(int rowNum, String columnName, String cellValue, String message) {
}
