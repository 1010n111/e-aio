package com.eaio.common.excel;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

import org.apache.fesod.sheet.FesodSheet;
import org.apache.fesod.sheet.context.AnalysisContext;
import org.apache.fesod.sheet.event.AnalysisEventListener;
import org.apache.fesod.sheet.write.metadata.WriteSheet;
import org.apache.fesod.sheet.ExcelWriter;

/** The only Excel dependency exposed to e-aio modules. */
public final class ExcelKit {

    private ExcelKit() {
    }

    /** Read one sheet in Fesod event mode; rows are never accumulated by this method. */
    public static <T> ImportResult<T> read(InputStream input, Class<T> rowType, ExcelReadOptions options,
            BiConsumer<T, Long> consumer) {
        return read(input, rowType, options, consumer, 1_000);
    }

    /** Read one sheet with a caller-owned cap on retained row errors. */
    public static <T> ImportResult<T> read(InputStream input, Class<T> rowType, ExcelReadOptions options,
            BiConsumer<T, Long> consumer, int errorMax) {
        if (input == null || rowType == null || consumer == null) {
            throw new IllegalArgumentException("Excel 输入、行类型和消费者不能为空");
        }
        if (errorMax < 0) {
            throw new IllegalArgumentException("Excel 错误明细上限不能为负数");
        }
        ExcelReadOptions actual = options == null ? ExcelReadOptions.defaults() : options;
        AtomicLong total = new AtomicLong();
        AtomicLong failed = new AtomicLong();
        List<ExcelError> errors = new ArrayList<>();
        var reader = FesodSheet.read(input, rowType, new AnalysisEventListener<T>() {
                @Override
                public void invoke(T row, AnalysisContext context) {
                    long rowNum = context.readRowHolder().getRowIndex() + 1L;
                    long rowCount = total.incrementAndGet();
                    if (actual.maxRows() > 0 && rowCount > actual.maxRows()) {
                        throw new RowLimitException(actual.maxRows());
                    }
                try {
                    consumer.accept(row, rowNum);
                } catch (ReadControlException ex) {
                    throw ex;
                } catch (RuntimeException ex) {
                    failed.incrementAndGet();
                    if (errors.size() < errorMax) {
                        errors.add(new ExcelError(rowNum, null, null, message(ex)));
                    }
                }
            }

            @Override
            public void doAfterAllAnalysed(AnalysisContext context) {
            }
        }).build();
        reader.read(org.apache.fesod.sheet.FesodSheet.readSheet(actual.sheetNo()).build());
        long rows = total.get();
        return new ImportResult<>(rows, rows - failed.get(), failed.get(), errors, failed.get() > errors.size());
    }

    /** Write a batch at a time; the caller controls the batch size and source stream. */
    public static void write(OutputStream output, Class<?> rowType, Collection<?> rows, String sheetName) {
        try (WriterSession writer = writer(output, rowType, sheetName)) {
            writer.write(rows);
        }
    }

    /** A single workbook writer that accepts bounded batches without concatenating XLSX files. */
    public static WriterSession writer(OutputStream output, Class<?> rowType, String sheetName) {
        return new WriterSession(FesodSheet.write(output, rowType).build(),
                FesodSheet.writerSheet(sheetName == null ? "Sheet1" : sheetName).build());
    }

    public static final class WriterSession implements AutoCloseable {
        private final ExcelWriter writer;
        private final WriteSheet sheet;

        private WriterSession(ExcelWriter writer, WriteSheet sheet) {
            this.writer = writer;
            this.sheet = sheet;
        }

        public void write(Collection<?> rows) {
            writer.write(rows == null ? List.of() : rows, sheet);
        }

        @Override
        public void close() {
            writer.finish();
        }
    }

    public static String message(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && (current.getMessage() == null || current.getMessage().isBlank())) {
            current = current.getCause();
        }
        return current.getMessage() == null || current.getMessage().isBlank()
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }

    /** Signals a caller-requested stop that must escape the row-error boundary. */
    public static class ReadControlException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        protected ReadControlException() {
        }
    }

    /** Raised by the event listener as soon as the configured row limit is crossed. */
    public static final class RowLimitException extends RuntimeException {
        private final int maxRows;

        public RowLimitException(int maxRows) {
            super("Excel 行数超过上限：" + maxRows);
            this.maxRows = maxRows;
        }

        public int maxRows() {
            return maxRows;
        }
    }
}
