/**
 * 定时任务的用例层（P1 册 2.3 的 {@code application/job}）。
 *
 * <p>四个组件，职责不重叠：
 * <ul>
 *   <li>{@link com.eaio.platform.application.job.JobHandlerRegistry}：启动期收集全部
 *       {@code JobHandler} Bean → {@code Map<code, handler>}。**可执行点的唯一来源**（3.4.1），
 *       重复 code 直接启动失败；</li>
 *   <li>{@link com.eaio.platform.application.job.JobExecutor}：执行壳。写
 *       {@code job_run(RUNNING)} → 在 worker 线程上调处理点（可超时/中断）→ 写终态 →
 *       决定重投 → 终态失败时发 {@link com.eaio.platform.events.JobFailedEvent}；</li>
 *   <li>{@link com.eaio.platform.application.job.JobRetryScanner}：扫 {@code RETRYING} 且到期的行重投
 *       （认领是原子的 UPDATE，跨实例只有一个赢家，3.4.4/6.2）；</li>
 *   <li>{@link com.eaio.platform.application.job.JobParams}：任务相关参数的读取口径
 *       （保留天数、重投批量），键与默认值逐字取 7.2。</li>
 * </ul>
 *
 * <p>{@code JobAppService}（上一层 {@code application}）是 REST 与 {@code SchedulerApi} 的共用入口，
 * 校验与状态机只有一份（2.4.3）。
 *
 * <p>依赖方向：本包不引 Web；持久化经 {@code infrastructure.persistence} 的门面（与
 * {@code application/dict} 同款），调度与锁经 {@code infrastructure.scheduler}。
 */
package com.eaio.platform.application.job;
