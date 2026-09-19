/**
 * 定时任务的领域层（P1 册 2.3 的 {@code domain/job}）。
 *
 * <p>放三样东西：
 * <ul>
 *   <li>表实体 {@link com.eaio.platform.domain.job.Job}（{@code eaio_platform.job}）与
 *       {@link com.eaio.platform.domain.job.JobRun}（{@code eaio_platform.job_run}）——后者只有
 *       {@code @TableId}，因为运行日志是只追加的（4.3.10：没有 version/deleted/updated 三列，
 *       也就没有"更新语义"）；</li>
 *   <li>状态与触发来源枚举 {@link com.eaio.platform.domain.job.JobRunStatus} /
 *       {@link com.eaio.platform.domain.job.JobTriggerType}（落库仍是 VARCHAR + CHECK）；</li>
 *   <li>纯逻辑 {@link com.eaio.platform.domain.job.JobRetryPolicy}（指数退避、封顶 30 分钟、重投上限）、
 *       {@link com.eaio.platform.domain.job.JobCron}（6 段 Spring cron 校验）与
 *       {@link com.eaio.platform.domain.job.JobLockWindow}（锁名与持锁/僵尸窗口）——这几处最容易算错、
 *       又完全不需要数据库，模块内单测直接覆盖。</li>
 * </ul>
 *
 * <p>依赖方向：domain 不依赖 application/infrastructure（实体上的 MyBatis-Plus 注解是映射元数据，
 * 与 {@code domain/dict} 同款口径），也不被其他模块引用。
 */
package com.eaio.platform.domain.job;
