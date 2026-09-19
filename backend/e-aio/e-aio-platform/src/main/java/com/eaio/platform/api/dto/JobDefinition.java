package com.eaio.platform.api.dto;

/**
 * 任务元数据注册入参（P1 册 5.3 契约 V1）：模块在代码里声明自己的定时任务时用它。
 *
 * <p><b>与 {@code JobSaveCmd} 的分工</b>：本记录是**代码侧**的注册声明（模块启动时调
 * {@code SchedulerApi.register}），{@code JobSaveCmd} 是**管理页侧**的增改（运维改 cron/超时/开关）。
 * 两者字段名不同处见 5.3：注册用 {@code description}/{@code defaultEnabled}，管理页用
 * {@code remark}/{@code enabled}；{@code job} 表只有一列 {@code remark}，注册时
 * {@code description} 落 {@code remark}（差异登记在《实现注记（T7）》）。
 *
 * @param jobCode        任务编码（必填，≤64；与 {@code job.job_code} 一致，跨模块唯一）
 * @param jobName        任务名称（必填，≤128）
 * @param cron           Spring 6 段 cron（必填，≤64；非法抛 20022）
 * @param handlerCode    处理点编码（必填，≤128；未命中 {@code JobHandlerRegistry} 抛 20023）
 * @param description    说明（落 {@code job.remark}，≤255）
 * @param timeoutSeconds 超时秒数（默认 300，必须 &gt; 0）
 * @param retryMax       失败重投上限（默认 3，0–10；0 = 不重投）
 * @param backoffSeconds 退避基数秒（默认 30；退避 = backoff × 2^(attempt-1)，封顶 30 分钟）
 * @param allowConcurrent 是否允许并发执行（默认 false；false 时并发触发记 {@code SKIPPED}/报 20021）
 * @param defaultEnabled 首次注册时的启用状态（默认 true）；**已有行的 enabled 不被注册覆盖**
 */
public record JobDefinition(
        String jobCode,
        String jobName,
        String cron,
        String handlerCode,
        String description,
        Integer timeoutSeconds,
        Integer retryMax,
        Integer backoffSeconds,
        Boolean allowConcurrent,
        Boolean defaultEnabled) {
}
