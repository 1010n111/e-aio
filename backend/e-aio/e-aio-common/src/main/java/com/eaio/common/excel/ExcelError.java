package com.eaio.common.excel;

/** A row-level Excel validation error. */
public record ExcelError(long rowNum, String column, String value, String message) {
}
