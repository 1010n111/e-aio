package com.eaio.common.excel;

import java.util.List;

/** Streaming import counters and bounded error details. */
public record ImportResult<T>(long totalRows, long successRows, long failRows, List<ExcelError> errors,
        boolean errorTruncated) {

    public ImportResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
