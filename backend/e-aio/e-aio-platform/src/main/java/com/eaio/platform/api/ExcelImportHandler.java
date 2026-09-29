package com.eaio.platform.api;

/** Business supplied row handler. The platform owns reading, progress and error reporting. */
public interface ExcelImportHandler<T> {

    String bizType();

    Class<T> rowType();

    void handleRow(T row, ImportRowContext context);

    default void beforeBatch(int batchNo) {
    }

    default void afterBatch(int batchNo, int successRows) {
    }
}
