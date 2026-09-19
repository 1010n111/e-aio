package com.eaio.platform.infrastructure.web;

import com.eaio.common.api.PageResult;
import com.eaio.platform.api.dto.JobCodeCmd;
import com.eaio.platform.api.dto.JobDTO;
import com.eaio.platform.api.dto.JobDelCmd;
import com.eaio.platform.api.dto.JobQuery;
import com.eaio.platform.api.dto.JobRunIdDTO;
import com.eaio.platform.api.dto.JobSaveCmd;
import com.eaio.platform.api.dto.JobTriggerCmd;
import com.eaio.platform.application.JobAppService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 定时任务 REST 入口（P1 册 5.2 的 {@code /platform/job} 8 个端点）。
 *
 * <p>三条契约（同 {@code DictController}）：
 * <ul>
 *   <li>全部 POST + JSON，路径省略 context-path（{@code /api}）；</li>
 *   <li>不自己拼返回体：出站由应用壳的 {@code ApiResponseAdvice} 统一包成 {@code Result<T>}；</li>
 *   <li>权限点与 7.3 **逐字一致**（{@code platform:job:*}）：{@code Pause}/{@code Resume} 也挂在
 *       {@code platform:job:run} 上——7.3 的 job 段只有 6 个点（没有 {@code pause}/{@code resume}），
 *       而 5.2 给这两个端点的权限点正是 {@code platform:job:run}。</li>
 * </ul>
 *
 * <p>写动作（Add/Up/Del/Run/Pause/Resume）必须带 {@code Idempotency-Key}（5.6：缺键即 10001，
 * 由入站链路的幂等过滤器强制，控制器不重复判断）；读动作（GetPage/Get）不带。
 */
@RestController
public class JobController {

    private final JobAppService service;

    public JobController(JobAppService service) {
        this.service = service;
    }

    /** 任务分页（管理页）：每行带 {@code scheduled}/{@code running} 与展示用的 {@code nextRunTime}。 */
    @PostMapping("/platform/job/GetPage")
    @PreAuthorize("hasAuthority('platform:job:list')")
    public PageResult<JobDTO> getPage(@RequestBody(required = false) JobQuery query) {
        return service.pageJobs(query);
    }

    /** 按编码查任务；不存在抛 20020。 */
    @PostMapping("/platform/job/Get")
    @PreAuthorize("hasAuthority('platform:job:get')")
    public JobDTO get(@Valid @RequestBody JobCodeCmd cmd) {
        return service.jobByCode(cmd.jobCode());
    }

    /** 新增任务：cron 非法 20022、处理点未注册 20023、编码已存在 10003。 */
    @PostMapping("/platform/job/Add")
    @PreAuthorize("hasAuthority('platform:job:add')")
    public JobDTO add(@Valid @RequestBody JobSaveCmd cmd) {
        return service.addJob(cmd);
    }

    /** 更新任务（乐观锁：version 必填，过期即 10003）；改完立刻重建调度。 */
    @PostMapping("/platform/job/Up")
    @PreAuthorize("hasAuthority('platform:job:up')")
    public JobDTO up(@Valid @RequestBody JobSaveCmd cmd) {
        return service.upJob(cmd);
    }

    /** 逻辑删除任务；正在执行抛 20021。 */
    @PostMapping("/platform/job/Del")
    @PreAuthorize("hasAuthority('platform:job:del')")
    public void del(@Valid @RequestBody JobDelCmd cmd) {
        service.delJob(cmd.jobCode(), cmd.version());
    }

    /** 手动触发（异步受理）：已停用 20024、正在执行且未允许并发 20021、处理点未注册 20023。 */
    @PostMapping("/platform/job/Run")
    @PreAuthorize("hasAuthority('platform:job:run')")
    public JobRunIdDTO run(@Valid @RequestBody JobTriggerCmd cmd) {
        return new JobRunIdDTO(service.trigger(cmd.jobCode(), cmd.params()));
    }

    /** 暂停 cron 调度（保留 enabled 与手动触发能力，3.4.6）；任务不存在抛 20020。 */
    @PostMapping("/platform/job/Pause")
    @PreAuthorize("hasAuthority('platform:job:run')")
    public void pause(@Valid @RequestBody JobCodeCmd cmd) {
        service.pause(cmd.jobCode());
    }

    /** 恢复 cron 调度；cron 非法 20022、处理点未注册 20023、已停用 20024。 */
    @PostMapping("/platform/job/Resume")
    @PreAuthorize("hasAuthority('platform:job:run')")
    public void resume(@Valid @RequestBody JobCodeCmd cmd) {
        service.resume(cmd.jobCode());
    }
}
