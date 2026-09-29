package com.eaio.platform.infrastructure.web;

import com.eaio.common.api.PageResult;
import com.eaio.platform.api.dto.ExcelExportCmd;
import com.eaio.platform.api.dto.ExcelImportCmd;
import com.eaio.platform.api.dto.ExcelTaskAccepted;
import com.eaio.platform.api.dto.ExcelTaskDTO;
import com.eaio.platform.api.dto.ExcelTaskErrorDTO;
import com.eaio.platform.api.dto.ExcelTaskErrorQuery;
import com.eaio.platform.api.dto.ExcelTaskQuery;
import com.eaio.platform.application.ExcelTaskAppService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.core.io.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ExcelTaskController {

    private final ExcelTaskAppService service;

    public ExcelTaskController(ExcelTaskAppService service) {
        this.service = service;
    }

    @PostMapping("/platform/excelTask/Import")
    @PreAuthorize("hasAuthority('platform:excelTask:import')")
    public ExcelTaskAccepted importTask(@Valid @RequestBody ExcelImportCmd cmd) {
        return service.submitImport(cmd);
    }

    @PostMapping("/platform/excelTask/Export")
    @PreAuthorize("hasAuthority('platform:excelTask:export')")
    public ExcelTaskAccepted exportTask(@Valid @RequestBody ExcelExportCmd cmd) {
        return service.submitExport(cmd);
    }

    @PostMapping("/platform/excelTask/Get")
    @PreAuthorize("hasAuthority('platform:excelTask:get')")
    public ExcelTaskDTO get(@RequestBody ExcelTaskIdCmd cmd) {
        return service.task(cmd.taskId());
    }

    @PostMapping("/platform/excelTask/GetPage")
    @PreAuthorize("hasAuthority('platform:excelTask:list')")
    public PageResult<ExcelTaskDTO> page(@RequestBody(required = false) ExcelTaskQuery query) {
        return service.page(query);
    }

    @PostMapping("/platform/excelTask/GetProgress")
    @PreAuthorize("hasAuthority('platform:excelTask:list')")
    public Object progress(@RequestBody ExcelTaskIdCmd cmd) {
        return service.progress(cmd.taskId());
    }

    @PostMapping("/platform/excelTask/GetErrors")
    @PreAuthorize("hasAuthority('platform:excelTask:list')")
    public PageResult<ExcelTaskErrorDTO> errors(@Valid @RequestBody ExcelTaskErrorQuery query) {
        return service.errors(query);
    }

    @PostMapping("/platform/excelTask/GetErrorPage")
    @PreAuthorize("hasAuthority('platform:excelTask:list')")
    public PageResult<ExcelTaskErrorDTO> errorPage(@Valid @RequestBody ExcelTaskErrorQuery query) {
        return service.errors(query);
    }

    @PostMapping("/platform/excelTask/Cancel")
    @PreAuthorize("hasAuthority('platform:excelTask:cancel')")
    public void cancel(@Valid @RequestBody ExcelTaskIdCmd cmd) {
        service.cancel(cmd.taskId());
    }

    @PostMapping("/platform/excelTask/DownloadResult")
    @PreAuthorize("hasAuthority('platform:excelTask:download')")
    public Resource result(@Valid @RequestBody ExcelTaskDownloadCmd cmd) {
        return service.downloadResult(cmd.taskId(), false);
    }

    @PostMapping("/platform/excelTask/DownloadErrors")
    @PreAuthorize("hasAuthority('platform:excelTask:download')")
    public Resource errorsFile(@Valid @RequestBody ExcelTaskDownloadCmd cmd) {
        return service.downloadResult(cmd.taskId(), true);
    }

    public record ExcelTaskIdCmd(@NotBlank String taskId) {
    }

    public record ExcelTaskDownloadCmd(@NotBlank String taskId) {
    }
}
