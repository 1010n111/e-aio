package com.eaio.platform.api;

import java.util.stream.Stream;

/** Business supplied lazy row source used by the streaming export runner. */
public interface ExcelExportHandler {

    String bizType();

    Class<?> rowType();

    Stream<?> rows(ExportContext context);

    default String fileName() {
        return bizType() + ".xlsx";
    }
}
