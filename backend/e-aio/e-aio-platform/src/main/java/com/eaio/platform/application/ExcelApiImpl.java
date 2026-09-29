package com.eaio.platform.application;

import com.eaio.common.api.PageResult;
import com.eaio.platform.api.ExcelApi;
import com.eaio.platform.api.dto.ExcelExportCmd;
import com.eaio.platform.api.dto.ExcelImportCmd;
import com.eaio.platform.api.dto.ExcelRowError;
import com.eaio.platform.api.dto.ExcelTaskDTO;
import com.eaio.platform.api.dto.ExcelTaskErrorDTO;
import com.eaio.platform.api.dto.ExcelTaskErrorQuery;
import com.eaio.platform.api.dto.TaskProgress;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
public class ExcelApiImpl implements ExcelApi {

    private final ExcelTaskAppService service;

    public ExcelApiImpl(ExcelTaskAppService service) {
        this.service = service;
    }

    @Override
    public String export(ExcelExportCmd cmd) {
        return service.submitExport(cmd).taskId();
    }

    @Override
    public String importData(ExcelImportCmd cmd) {
        return service.submitImport(cmd).taskId();
    }

    @Override
    public ExcelTaskDTO getTask(String taskId) {
        return service.task(taskId);
    }

    @Override
    public Resource downloadResult(String taskId) {
        return service.downloadResult(taskId, false);
    }

    @Override
    public List<ExcelRowError> getErrors(String taskId, int limit) {
        int size = Math.max(1, Math.min(limit, 1_000));
        return service.errors(new ExcelTaskErrorQuery(taskId, 1, size)).getRecords().stream()
                .map(error -> new ExcelRowError(Math.toIntExact(error.rowNum()), error.columnName(), error.cellValue(),
                        error.errorMessage()))
                .toList();
    }

    @Override
    public TaskProgress progress(String taskId) {
        return service.progress(taskId);
    }

    @Override
    public PageResult<ExcelTaskErrorDTO> errors(ExcelTaskErrorQuery query) {
        return service.errors(query);
    }

    @Override
    public void cancel(String taskId) {
        service.cancel(taskId);
    }
}
