package com.eaio.platform.application;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import com.eaio.common.api.PageResult;
import com.eaio.common.excel.ExcelError;
import com.eaio.common.excel.ExcelKit;
import com.eaio.common.excel.ExcelReadOptions;
import com.eaio.common.excel.ImportResult;
import com.eaio.common.exception.BusinessException;
import com.eaio.common.id.IdGenerator;
import com.eaio.platform.api.ExcelExportHandler;
import com.eaio.platform.api.ExcelImportHandler;
import com.eaio.platform.api.ExportContext;
import com.eaio.platform.api.ImportRowContext;
import com.eaio.platform.api.PlatformErrorCode;
import com.eaio.platform.api.dto.ExcelExportCmd;
import com.eaio.platform.api.dto.ExcelImportCmd;
import com.eaio.platform.api.dto.ExcelTaskAccepted;
import com.eaio.platform.api.dto.ExcelTaskDTO;
import com.eaio.platform.api.dto.ExcelTaskErrorDTO;
import com.eaio.platform.api.dto.ExcelTaskErrorQuery;
import com.eaio.platform.api.dto.ExcelTaskQuery;
import com.eaio.platform.api.dto.TaskProgress;
import com.eaio.platform.api.dto.FileDownloadCmd;
import com.eaio.platform.api.dto.FileDTO;
import com.eaio.platform.api.dto.FileUploadCmd;
import com.eaio.platform.api.port.OrgContextPort;
import com.eaio.platform.application.param.ParamResolver;
import com.eaio.platform.domain.param.ParamContext;
import com.eaio.platform.domain.excel.ExcelTask;
import com.eaio.platform.domain.excel.ExcelTaskError;
import com.eaio.platform.infrastructure.persistence.ExcelTaskStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

/** Async Excel orchestration. Business handlers remain optional and are registered by other modules. */
@Service
public class ExcelTaskAppService {

    private static final int DEFAULT_MAX_IMPORT = 200_000;
    private static final int DEFAULT_MAX_EXPORT = 1_000_000;
    private static final int DEFAULT_ERROR_MAX = 1_000;
    private static final int DEFAULT_ATOMIC_MAX = 20_000;
    private static final String MAX_IMPORT_KEY = "platform.excel.max-import-rows";
    private static final String MAX_EXPORT_KEY = "platform.excel.max-export-rows";
    private static final String ERROR_MAX_KEY = "platform.excel.error-max";
    private static final String ATOMIC_MAX_KEY = "platform.excel.atomic-max-rows";
    private static final int BATCH = 1_000;

    private final ExcelTaskStore store;
    private final FileApiImpl files;
    private final IdGenerator ids;
    private final ObjectProvider<OrgContextPort> context;
    private final List<ExcelImportHandler<?>> importHandlers;
    private final List<ExcelExportHandler> exportHandlers;
    private final ExecutorService executor;
    private final ParamResolver params;
    private final ObjectProvider<PlatformTransactionManager> transactionManagers;
    private final ConcurrentHashMap<String, LiveProgress> live = new ConcurrentHashMap<>();

    public ExcelTaskAppService(ExcelTaskStore store, FileApiImpl files, IdGenerator ids,
            ObjectProvider<OrgContextPort> context, List<ExcelImportHandler<?>> importHandlers,
            List<ExcelExportHandler> exportHandlers, @Qualifier("excelTaskExecutor") ExecutorService executor) {
        this(store, files, ids, context, importHandlers, exportHandlers, executor, null, null);
    }

    public ExcelTaskAppService(ExcelTaskStore store, FileApiImpl files, IdGenerator ids,
            ObjectProvider<OrgContextPort> context, List<ExcelImportHandler<?>> importHandlers,
            List<ExcelExportHandler> exportHandlers, @Qualifier("excelTaskExecutor") ExecutorService executor,
            ParamResolver params) {
        this(store, files, ids, context, importHandlers, exportHandlers, executor, params, null);
    }

    @Autowired
    public ExcelTaskAppService(ExcelTaskStore store, FileApiImpl files, IdGenerator ids,
            ObjectProvider<OrgContextPort> context, List<ExcelImportHandler<?>> importHandlers,
            List<ExcelExportHandler> exportHandlers, @Qualifier("excelTaskExecutor") ExecutorService executor,
            ParamResolver params, ObjectProvider<PlatformTransactionManager> transactionManagers) {
        this.store = store;
        this.files = files;
        this.ids = ids;
        this.context = context;
        this.importHandlers = List.copyOf(importHandlers);
        this.exportHandlers = List.copyOf(exportHandlers);
        this.executor = executor;
        this.params = params;
        this.transactionManagers = transactionManagers;
    }

    public ExcelTaskAccepted submitImport(ExcelImportCmd cmd) {
        ExcelImportHandler<?> handler = importHandler(cmd.bizType());
        FileDTO source = files.getMeta(cmd.fileId());
        Long user = currentUser();
        ExcelTask duplicate = store.duplicate("IMPORT", cmd.bizType().trim(), user, source.sha256());
        if (duplicate != null && !cmd.force()) {
            store.markDeduplicated(duplicate.getId());
            return new ExcelTaskAccepted(taskId(duplicate), true);
        }
        if (duplicate != null) {
            throw new BusinessException(PlatformErrorCode.EXCEL_TASK_CONFLICT, "相同导入仍在运行：taskId=" + taskId(duplicate));
        }
        ExcelTask task = newTask("IMPORT", cmd.bizType(), cmd.templateCode(), cmd.fileId(), source.sha256(), null);
        store.insert(task);
        LiveProgress progress = new LiveProgress(task.getId());
        live.put(taskId(task), progress);
        try {
            executor.execute(() -> runImport(task, handler, cmd.sheetIndex()));
        } catch (RuntimeException e) {
            live.remove(taskId(task));
            store.deletePending(task.getId());
            if (e instanceof java.util.concurrent.RejectedExecutionException) {
                throw new BusinessException(PlatformErrorCode.EXCEL_QUEUE_FULL.getCode(), "Excel 任务队列已满，请稍后重试", e);
            }
            throw e;
        }
        return new ExcelTaskAccepted(taskId(task), false);
    }

    public ExcelTaskAccepted submitExport(ExcelExportCmd cmd) {
        ExcelExportHandler handler = exportHandler(cmd.bizType());
        ExcelTask task = newTask("EXPORT", cmd.bizType(), cmd.templateCode(), null, null, cmd.requestJson());
        store.insert(task);
        live.put(taskId(task), new LiveProgress(task.getId()));
        try {
            executor.execute(() -> runExport(task, handler, cmd));
        } catch (RuntimeException e) {
            live.remove(taskId(task));
            store.deletePending(task.getId());
            if (e instanceof java.util.concurrent.RejectedExecutionException) {
                throw new BusinessException(PlatformErrorCode.EXCEL_QUEUE_FULL.getCode(), "Excel 任务队列已满，请稍后重试", e);
            }
            throw e;
        }
        return new ExcelTaskAccepted(taskId(task), false);
    }

    public ExcelTaskDTO task(String taskId) {
        ExcelTask task = require(taskId);
        List<ExcelTaskErrorDTO> errors = new ArrayList<>();
        var page = store.pageErrors(new ExcelTaskErrorQuery(taskId, 1, 100), task.getId());
        for (ExcelTaskError error : page.getRecords()) {
            errors.add(toDto(taskId, error));
        }
        return toDto(task, errors, store.countErrors(task.getId()) > errorMax());
    }

    public PageResult<ExcelTaskDTO> page(ExcelTaskQuery query) {
        var page = store.page(query);
        List<ExcelTaskDTO> rows = page.getRecords().stream()
                .map(task -> toDto(task, List.of(), store.countErrors(task.getId()) > errorMax()))
                .toList();
        return PageResult.from(page.getCurrent(), page.getSize(), page.getTotal(), rows);
    }

    public TaskProgress progress(String taskId) {
        ExcelTask task = require(taskId);
        LiveProgress current = live.get(taskId);
        if (current != null) {
            return current.snapshot(task);
        }
        return new TaskProgress(taskId, task.getStatus(), value(task.getProgressPercent()), value(task.getTotalRows()),
                value(task.getSuccessRows()), value(task.getFailRows()), Boolean.TRUE.equals(task.getDeduplicated()),
                task.getResultFileId(), task.getErrorFileId(), task.getFinishTime(), task.getErrorMessage());
    }

    public PageResult<ExcelTaskErrorDTO> errors(ExcelTaskErrorQuery query) {
        ExcelTask task = require(query.taskId());
        var page = store.pageErrors(query, task.getId());
        List<ExcelTaskErrorDTO> rows = page.getRecords().stream().map(error -> toDto(query.taskId(), error)).toList();
        return PageResult.from(page.getCurrent(), page.getSize(), page.getTotal(), rows);
    }

    public void cancel(String taskId) {
        ExcelTask task = require(taskId);
        if (store.cancel(task.getId()) == 1) {
            LiveProgress progress = live.get(taskId);
            if (progress != null) {
                progress.cancelled.set(true);
                progress.status = "CANCELLED";
            }
        }
    }

    public Resource downloadResult(String taskId, boolean error) {
        ExcelTask task = require(taskId);
        Long fileId = error ? task.getErrorFileId() : task.getResultFileId();
        if (fileId == null) {
            throw new BusinessException(PlatformErrorCode.EXCEL_TASK_NOT_FOUND, "任务尚无可下载文件：" + taskId);
        }
        return files.download(FileDownloadCmd.attachment(fileId, currentUser()));
    }

    private void runImport(ExcelTask task, ExcelImportHandler<?> handler, int sheetIndex) {
        String taskId = taskId(task);
        LiveProgress progress = live.get(taskId);
        try {
            if (store.markRunning(task.getId()) == 0) {
                return;
            }
            progress.status = "RUNNING";
            int atomicMaxRows = atomicMaxRows();
            long expectedRows = countRows(task, handler, progress, sheetIndex);
            progress.expectedRows = expectedRows;
            try (InputStream input = files.download(FileDownloadCmd.attachment(task.getSourceFileId(), task.getSubmitterId()))
                    .getInputStream()) {
            ImportResult<?> result = read(handler, input, task, progress, sheetIndex, expectedRows, atomicMaxRows);
            persistErrors(task, result.errors());
            Long errorFile = result.errors().isEmpty() ? null : uploadErrors(taskId, result.errors());
            String status = result.totalRows() > atomicMaxRows ? "PARTIAL"
                    : result.failRows() == 0 ? "SUCCESS" : result.successRows() == 0 ? "FAILED" : "PARTIAL";
            finish(task, progress, status, checkedRows(result.totalRows()), checkedRows(result.successRows()),
                    checkedRows(result.failRows()), null, errorFile, null);
            }
        } catch (ExcelKit.RowLimitException e) {
            finish(task, progress, "FAILED", 0, 0, 0, null, null, e.getMessage());
        } catch (CancelledTask e) {
            if (!progress.cancelled.get()) {
                finish(task, progress, "FAILED", progress.total, progress.success, progress.failed,
                        null, null, "任务执行线程已中断");
            }
        } catch (Exception e) {
            finish(task, progress, "FAILED", progress.total, progress.success, progress.failed,
                    null, null, ExcelKit.message(e));
        } finally {
            live.remove(taskId);
        }
    }

    private long countRows(ExcelTask task, ExcelImportHandler<?> handler, LiveProgress progress, int sheetIndex) {
        try (InputStream input = files.download(FileDownloadCmd.attachment(task.getSourceFileId(), task.getSubmitterId()))
                .getInputStream()) {
            return ExcelKit.read(input, handler.rowType(), new ExcelReadOptions(maxImportRows(), sheetIndex), (row, rowNum) -> {
                if (progress.cancelled.get() || Thread.currentThread().isInterrupted()) {
                    throw new CancelledTask();
                }
            }, 0).totalRows();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("读取 Excel 源文件失败", e);
        }
    }

    private ImportResult<?> read(ExcelImportHandler<?> handler, InputStream input, ExcelTask task, LiveProgress progress,
            int sheetIndex, long expectedRows, int atomicMaxRows) {
        return readTyped(handler, input, task, progress, sheetIndex, expectedRows, atomicMaxRows);
    }

    private <T> ImportResult<T> readTyped(ExcelImportHandler<T> handler, InputStream input, ExcelTask task,
            LiveProgress progress, int sheetIndex, long expectedRows, int atomicMaxRows) {
        int[] batchRows = { 0 };
        int[] batchNo = { 1 };
        int batchLimit = expectedRows <= atomicMaxRows ? atomicMaxRows : BATCH;
        BatchTransaction transaction = new BatchTransaction(transactionManagers);
        try {
            ImportResult<T> result = ExcelKit.read(input, handler.rowType(),
                    new ExcelReadOptions(maxImportRows(), sheetIndex), (row, rowNum) -> {
                if (progress.cancelled.get() || Thread.currentThread().isInterrupted()) {
                    throw new CancelledTask();
                }
                progress.total++;
                try {
                    if (batchRows[0] == 0) {
                        transaction.begin();
                        handler.beforeBatch(batchNo[0]);
                    }
                    Object savepoint = transaction.savepoint();
                    try {
                        handler.handleRow(row, new ImportRowContext(taskId(task), rowNum, false));
                        progress.success++;
                    } catch (TransactionFailure e) {
                        throw e;
                    } catch (RuntimeException e) {
                        transaction.rollbackRow(savepoint);
                        throw e;
                    } finally {
                        transaction.releaseSavepoint(savepoint);
                    }
                } finally {
                    batchRows[0]++;
                    progress.failed = progress.total - progress.success;
                    if (batchRows[0] == batchLimit) {
                        int finishedBatch = batchNo[0]++;
                        batchRows[0] = 0;
                        RuntimeException callbackFailure = null;
                        try {
                            handler.afterBatch(finishedBatch, progress.success);
                        } catch (RuntimeException e) {
                            callbackFailure = e;
                        }
                        transaction.commit();
                        if (callbackFailure != null) {
                            throw callbackFailure;
                        }
                    }
                    flush(task, progress);
                }
            }, errorMax());
            if (batchRows[0] > 0) {
                handler.afterBatch(batchNo[0], progress.success);
                transaction.commit();
            }
            return result;
        } catch (RuntimeException e) {
            transaction.rollback();
            throw e;
        }
    }

    private void runExport(ExcelTask task, ExcelExportHandler handler, ExcelExportCmd cmd) {
        LiveProgress progress = live.get(taskId(task));
        Path outputPath = null;
        try {
            if (store.markRunning(task.getId()) == 0) {
                return;
            }
            progress.status = "RUNNING";
            try (Stream<?> rows = handler.rows(new ExportContext(taskId(task), task.getBizType(), task.getTemplateCode(),
                    cmd.requestJson(), Map.of()))) {
            outputPath = Files.createTempFile("eaio-excel-", ".xlsx");
            long total = 0;
            int maxRows = maxExportRows();
            try (OutputStream output = Files.newOutputStream(outputPath);
                    var writer = ExcelKit.writer(output, handler.rowType(), "Sheet1")) {
            List<Object> batch = new ArrayList<>(BATCH);
            var iterator = rows.iterator();
            while (iterator.hasNext()) {
                if (progress.cancelled.get() || Thread.currentThread().isInterrupted()) {
                    throw new CancelledTask();
                }
                batch.add(iterator.next());
                total++;
                if (total > maxRows) {
                    throw new RowLimitException(maxRows);
                }
                if (batch.size() == BATCH) {
                    writer.write(batch);
                    batch.clear();
                    progress.total = (int) Math.min(Integer.MAX_VALUE, total);
                    progress.success = progress.total;
                    flush(task, progress);
                }
            }
            if (!batch.isEmpty() || total == 0) {
                writer.write(batch);
            }
            }
            FileDTO result;
            try (InputStream input = Files.newInputStream(outputPath)) {
                result = files.upload(new FileUploadCmd(handler.fileName(),
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", Files.size(outputPath),
                        input, "excel.export", task.getId()), FileAppService.SOURCE_EXPORT);
            }
            finish(task, progress, "SUCCESS", checkedRows(total), checkedRows(total), 0, result.id(), null, null);
            }
        } catch (RowLimitException e) {
            finish(task, progress, "FAILED", progress.total, progress.success, 0, null, null,
                    PlatformErrorCode.EXCEL_ROW_LIMIT_EXCEEDED.getMessage());
        } catch (CancelledTask e) {
            if (!progress.cancelled.get()) {
                finish(task, progress, "FAILED", progress.total, progress.success, 0, null, null,
                        "任务执行线程已中断");
            }
        } catch (Exception e) {
            finish(task, progress, "FAILED", progress.total, progress.success, 0, null, null, ExcelKit.message(e));
        } finally {
            if (outputPath != null) {
                try {
                    Files.deleteIfExists(outputPath);
                } catch (Exception ignored) {
                    // Temporary output is best effort cleanup after the task has recorded its result.
                }
            }
            live.remove(taskId(task));
        }
    }

    private Long uploadErrors(String taskId, List<ExcelError> errors) {
        StringBuilder csv = new StringBuilder("row,column,value,message\n");
        for (ExcelError error : errors) {
            csv.append(error.rowNum()).append(',').append(csv(error.column())).append(',').append(csv(error.value()))
                    .append(',').append(csv(error.message())).append('\n');
        }
        byte[] bytes = csv.toString().getBytes(StandardCharsets.UTF_8);
        FileDTO file = files.upload(new FileUploadCmd(taskId + "-errors.csv", "text/csv", (long) bytes.length,
                new java.io.ByteArrayInputStream(bytes), "excel.import.error", Long.parseLong(taskId)),
                FileAppService.SOURCE_IMPORT_ERROR);
        return file.id();
    }

    private void persistErrors(ExcelTask task, List<ExcelError> errors) {
        for (ExcelError error : errors) {
            ExcelTaskError row = new ExcelTaskError();
            row.setId(ids.nextId());
            row.setTaskId(task.getId());
            row.setRowNum(checkedRows(error.rowNum()));
            row.setColumnName(error.column());
            row.setCellValue(truncate(error.value(), 512));
            row.setErrorMessage(truncate(error.message(), 512));
            row.setCreatedAt(Instant.now());
            row.setCreatedBy(task.getSubmitterId());
            store.insertError(row);
        }
    }

    private void flush(ExcelTask task, LiveProgress progress) {
        if (progress.total % BATCH == 0) {
            int percent = progress.expectedRows <= 0 ? 0
                    : (int) Math.min(99, progress.total * 100L / progress.expectedRows);
            progress.progressPercent = percent;
            store.updateProgress(task.getId(), "RUNNING", percent, progress.total, progress.success, progress.failed);
        }
    }

    private void finish(ExcelTask task, LiveProgress progress, String status, int total, int success, int failed,
            Long result, Long error, String message) {
        if (store.finish(task.getId(), status, total, success, failed, result, error, message) == 1) {
            progress.status = status;
            progress.progressPercent = 100;
        } else if (progress.cancelled.get()) {
            progress.status = "CANCELLED";
        }
    }

    private int maxImportRows() {
        return intParam(MAX_IMPORT_KEY, DEFAULT_MAX_IMPORT);
    }

    private int maxExportRows() {
        return intParam(MAX_EXPORT_KEY, DEFAULT_MAX_EXPORT);
    }

    private int errorMax() {
        return intParam(ERROR_MAX_KEY, DEFAULT_ERROR_MAX);
    }

    private int atomicMaxRows() {
        return intParam(ATOMIC_MAX_KEY, DEFAULT_ATOMIC_MAX);
    }

    private int intParam(String key, int fallback) {
        if (params == null) {
            return fallback;
        }
        String value = params.resolveOrDefault(key, Integer.toString(fallback), ParamContext.systemOnly()).value();
        try {
            int parsed = Integer.parseInt(value == null ? "" : value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private ExcelImportHandler<?> importHandler(String bizType) {
        return importHandlers.stream().filter(item -> item.bizType().equals(bizType.trim())).findFirst()
                .orElseThrow(() -> new BusinessException(PlatformErrorCode.EXCEL_HANDLER_NOT_REGISTERED,
                        "Excel 导入处理器未注册：" + bizType));
    }

    private ExcelExportHandler exportHandler(String bizType) {
        return exportHandlers.stream().filter(item -> item.bizType().equals(bizType.trim())).findFirst()
                .orElseThrow(() -> new BusinessException(PlatformErrorCode.EXCEL_HANDLER_NOT_REGISTERED,
                        "Excel 导出处理器未注册：" + bizType));
    }

    private ExcelTask newTask(String type, String bizType, String template, Long fileId, String sha256, String request) {
        Long user = currentUser();
        ExcelTask task = new ExcelTask();
        task.setId(ids.nextId());
        task.setTaskType(type);
        task.setBizType(bizType.trim());
        task.setTemplateCode(template);
        task.setStatus("PENDING");
        task.setProgressPercent(0);
        task.setTotalRows(0);
        task.setSuccessRows(0);
        task.setFailRows(0);
        task.setDeduplicated(false);
        task.setSourceFileId(fileId);
        task.setSourceSha256(sha256);
        task.setRequestJson(request);
        task.setSubmitterId(user);
        task.setSubmitterOrgId(currentOrg());
        task.setCreatedAt(Instant.now());
        task.setCreatedBy(user);
        task.setVersion(0);
        return task;
    }

    private ExcelTask require(String taskId) {
        long id;
        try {
            id = Long.parseLong(taskId);
        } catch (NumberFormatException e) {
            throw taskNotFound(taskId);
        }
        ExcelTask task = store.row(id);
        if (task == null) {
            throw taskNotFound(taskId);
        }
        return task;
    }

    private BusinessException taskNotFound(String taskId) {
        return new BusinessException(PlatformErrorCode.EXCEL_TASK_NOT_FOUND, "异步任务不存在或已过期：" + taskId);
    }

    private static String taskId(ExcelTask task) {
        return Long.toString(task.getId());
    }

    private Long currentUser() {
        return context.getIfAvailable() == null ? null
                : context.getIfAvailable().current().map(OrgContextPort.OrgContext::userId).orElse(null);
    }

    private long currentOrg() {
        return context.getIfAvailable() == null ? 0L
                : context.getIfAvailable().current().map(OrgContextPort.OrgContext::orgId).orElse(0L);
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static int checkedRows(long value) {
        return Math.toIntExact(value);
    }

    private static String csv(String value) {
        return value == null ? "" : "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String truncate(String value, int max) {
        return value == null ? null : value.length() <= max ? value : value.substring(0, max);
    }
    private static ExcelTaskErrorDTO toDto(String taskId, ExcelTaskError error) {
        return new ExcelTaskErrorDTO(error.getId(), taskId, value(error.getRowNum()), error.getColumnName(),
                error.getCellValue(), error.getErrorMessage(), error.getCreatedAt());
    }
    private static ExcelTaskDTO toDto(ExcelTask task, List<ExcelTaskErrorDTO> errors, boolean truncated) {
        return new ExcelTaskDTO(taskId(task), task.getTaskType(), task.getBizType(), task.getTemplateCode(), task.getStatus(),
                value(task.getProgressPercent()), value(task.getTotalRows()), value(task.getSuccessRows()), value(task.getFailRows()),
                Boolean.TRUE.equals(task.getDeduplicated()), task.getSourceFileId(), task.getResultFileId(), task.getErrorFileId(),
                task.getStartTime(), task.getFinishTime(), task.getErrorMessage(), truncated, errors);
    }

    private static final class BatchTransaction {
        private final PlatformTransactionManager manager;
        private TransactionStatus status;

        private BatchTransaction(ObjectProvider<PlatformTransactionManager> managers) {
            manager = managers == null ? null : managers.getIfAvailable();
        }

        private void begin() {
            if (manager == null) {
                return;
            }
            try {
                status = manager.getTransaction(new DefaultTransactionDefinition());
            } catch (RuntimeException e) {
                throw new TransactionFailure(e);
            }
        }

        private Object savepoint() {
            if (status == null) {
                return null;
            }
            try {
                return status.createSavepoint();
            } catch (RuntimeException e) {
                throw new TransactionFailure(e);
            }
        }

        private void rollbackRow(Object savepoint) {
            if (status == null || savepoint == null) {
                return;
            }
            try {
                status.rollbackToSavepoint(savepoint);
            } catch (RuntimeException e) {
                throw new TransactionFailure(e);
            }
        }

        private void releaseSavepoint(Object savepoint) {
            if (status == null || savepoint == null) {
                return;
            }
            try {
                status.releaseSavepoint(savepoint);
            } catch (RuntimeException e) {
                throw new TransactionFailure(e);
            }
        }

        private void commit() {
            if (status == null) {
                return;
            }
            TransactionStatus current = status;
            status = null;
            try {
                manager.commit(current);
            } catch (RuntimeException e) {
                throw new TransactionFailure(e);
            }
        }

        private void rollback() {
            if (status == null) {
                return;
            }
            TransactionStatus current = status;
            status = null;
            try {
                manager.rollback(current);
            } catch (RuntimeException ignored) {
                // Preserve the original import failure; the transaction is already unusable.
            }
        }
    }

    private static final class TransactionFailure extends ExcelKit.ReadControlException {
        private static final long serialVersionUID = 1L;

        private TransactionFailure(Throwable cause) {
            initCause(cause);
        }
    }

    private static final class LiveProgress {
        private final long id;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile String status = "PENDING";
        private volatile int progressPercent;
        private volatile long expectedRows;
        private volatile int total;
        private volatile int success;
        private volatile int failed;

        private LiveProgress(long id) {
            this.id = id;
        }

        private TaskProgress snapshot(ExcelTask task) {
            return new TaskProgress(taskId(task), status,
                    progressPercent, total, success, failed,
                    Boolean.TRUE.equals(task.getDeduplicated()), task.getResultFileId(), task.getErrorFileId(),
                    task.getFinishTime(), task.getErrorMessage());
        }
    }

    private static final class CancelledTask extends ExcelKit.ReadControlException {
        private static final long serialVersionUID = 1L;
    }

    private static final class RowLimitException extends RuntimeException {
        private final int max;

        private RowLimitException(int max) {
            this.max = max;
        }
    }
}
