package com.eaio.platform.api;

import com.eaio.common.api.PageResult;
import com.eaio.platform.api.dto.ExcelExportCmd;
import com.eaio.platform.api.dto.ExcelImportCmd;
import com.eaio.platform.api.dto.ExcelRowError;
import com.eaio.platform.api.dto.ExcelTaskDTO;
import com.eaio.platform.api.dto.ExcelTaskErrorDTO;
import com.eaio.platform.api.dto.ExcelTaskErrorQuery;
import com.eaio.platform.api.dto.TaskProgress;
import java.util.List;
import org.springframework.core.io.Resource;

/** Cross-module contract for asynchronous Excel import/export. */
public interface ExcelApi {

    String export(ExcelExportCmd cmd);

    String importData(ExcelImportCmd cmd);

    ExcelTaskDTO getTask(String taskId);

    Resource downloadResult(String taskId);

    List<ExcelRowError> getErrors(String taskId, int limit);

    void cancel(String taskId);

    /** Compatibility alias retained as a default so the frozen V1 surface stays stable. */
    default String submitImport(ExcelImportCmd cmd) {
        return importData(cmd);
    }

    /** Compatibility alias retained as a default so the frozen V1 surface stays stable. */
    default String submitExport(ExcelExportCmd cmd) {
        return export(cmd);
    }

    /** Compatibility polling shape for older platform callers. */
    default TaskProgress progress(String taskId) {
        throw new UnsupportedOperationException("请使用 getTask(taskId) 查询 Excel 任务");
    }

    /** Compatibility paged error shape for older platform callers. */
    default PageResult<ExcelTaskErrorDTO> errors(ExcelTaskErrorQuery query) {
        throw new UnsupportedOperationException("请使用 getErrors(taskId, limit) 查询 Excel 错误");
    }
}
