package com.eaio.platform.api;

import java.util.Map;

import com.eaio.common.api.PageResult;
import com.eaio.platform.api.dto.JobDefinition;
import com.eaio.platform.api.dto.JobRunDTO;
import com.eaio.platform.api.dto.JobRunQuery;

/**
 * 定时任务（P1 册 5.4 契约 V1，冻结）：模块在**代码**里声明任务（可执行点必须是 {@link JobHandler} Bean），
 * DB 只决定「开/关/参数/超时/重试」。
 *
 * <p><b>安全边界（3.4.1，明确否决的写法）</b>：不存在、也不会新增任何"按 {@code job} 表里的字符串反射调用
 * 任意 Bean 方法"的能力。能写 {@code job} 表 ≠ 能执行任意方法；新增任务必须发版（处理点进代码 + 注册）。
 * 代价（新增任务不能纯配置上线）是主动接受的。
 *
 * <p><b>实现类</b> {@code SchedulerApiImpl}（application 层）：REST 与跨模块两条入口共用同一个
 * {@code JobAppService}，校验与状态机只有一份（2.4.3）。所有方法**同步返回**，长耗时执行一律异步
 * （{@code trigger} 返回 {@code runId} 之后由调用方按 {@code runId} 查运行日志）。
 */
public interface SchedulerApi {

    /**
     * 注册元数据；{@code cron} 非法抛 20022、{@code handlerCode} 未命中注册表抛 20023。
     *
     * <p>同一 {@code jobCode} 重复注册 = 覆盖元数据（不重建调度器之外的任何东西）：模块每次启动都会重新
     * 注册自己的任务，覆盖语义让"代码里改了 cron/超时"在重启后生效。已有行上的 {@code enabled} 与用户改过的
     * 参数保持不动——代码无权把运维停用的任务悄悄启用回来。
     */
    void register(JobDefinition definition);

    /**
     * 手动触发（异步执行，立即返回 {@code runId}）：任务不存在 20020、已停用 20024、
     * 正在运行且不允许并发 20021、处理点未注册 20023。
     *
     * <p>{@code params} 为空时用 {@code job.params_json} 里保存的参数。
     */
    long trigger(String jobCode, Map<String, String> params);

    /** 暂停 cron 调度：只取消调度器里的触发，保留 {@code enabled} 与手动触发能力（3.4.6）。 */
    void pause(String jobCode);

    /** 恢复 cron 调度；cron 非法或处理点未注册时拒绝（20022/20023），不会把一个跑不起来的任务挂上去。 */
    void resume(String jobCode);

    /** 运行日志分页（跨模块只读诊断；管理页走 {@code /platform/jobRun/GetPage}）。 */
    PageResult<JobRunDTO> listRuns(JobRunQuery query);
}
