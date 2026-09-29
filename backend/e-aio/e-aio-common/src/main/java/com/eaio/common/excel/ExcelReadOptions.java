package com.eaio.common.excel;

/** Streaming read options. */
public record ExcelReadOptions(int maxRows, int sheetNo) {

    public static ExcelReadOptions defaults() {
        return new ExcelReadOptions(0, 0);
    }

    public ExcelReadOptions {
        if (maxRows < 0) {
            throw new IllegalArgumentException("maxRows 不能为负数");
        }
        if (sheetNo < 0) {
            throw new IllegalArgumentException("sheetNo 不能为负数");
        }
    }
}
