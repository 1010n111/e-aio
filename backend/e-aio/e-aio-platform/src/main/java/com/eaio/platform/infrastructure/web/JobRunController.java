package com.eaio.platform.infrastructure.web;

import com.eaio.common.api.PageResult;
import com.eaio.platform.api.dto.JobRunDTO;
import com.eaio.platform.api.dto.JobRunIdCmd;
import com.eaio.platform.api.dto.JobRunIdDTO;
import com.eaio.platform.api.dto.JobRunQuery;
import com.eaio.platform.application.JobAppService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务运行日志 REST 入口（P1 册 5.2 的 {@code /platform/jobRun} 3 个端点）。
 *
 * <p>权限点：查日志用 {@code platform:job:list}（与任务列表同一个点：看日志是"看任务"的一部分），
 * 重试用 {@code platform:job:run}——与 5.2 逐字一致。
 */
@RestController
public class JobRunController {

    private final JobAppService service;

    public JobRunController(JobAppService service) {
        this.service = service;
    }

    /** 运行日志分页（默认按 {@code start_time DESC}）。 */
    @PostMapping("/platform/jobRun/GetPage")
    @PreAuthorize("hasAuthority('platform:job:list')")
    public PageResult<JobRunDTO> getPage(@RequestBody(required = false) JobRunQuery query) {
        return service.pageRuns(query);
    }

    /** 单条运行日志；不存在抛 20020。 */
    @PostMapping("/platform/jobRun/Get")
    @PreAuthorize("hasAuthority('platform:job:list')")
    public JobRunDTO get(@Valid @RequestBody JobRunIdCmd cmd) {
        return service.runById(cmd.runId());
    }

    /**
     * 重试一次运行（异步受理，返回同一个 {@code runId}）：正在执行 20021、已停用 20024、
     * 处理点未注册 20023、运行记录不存在 20020。
     */
    @PostMapping("/platform/jobRun/Retry")
    @PreAuthorize("hasAuthority('platform:job:run')")
    public JobRunIdDTO retry(@Valid @RequestBody JobRunIdCmd cmd) {
        return new JobRunIdDTO(service.retryRun(cmd.runId()));
    }
}
