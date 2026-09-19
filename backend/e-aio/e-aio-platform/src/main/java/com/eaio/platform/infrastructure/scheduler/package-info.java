/**
 * 调度与互斥（P1 册 2.3 的 {@code infrastructure/scheduler}）。
 *
 * <p>两个组件：
 * <ul>
 *   <li>{@link com.eaio.platform.infrastructure.scheduler.JobLocker}：ShedLock（JDBC template provider +
 *       {@code usingDbTime()}，锁表 {@code eaio_platform.shedlock}）的封装。程序化取锁
 *       （{@code LockProvider.lock}），不用 {@code @EnableSchedulerLock} 的注解 AOP——理由见类注释；</li>
 *   <li>{@link com.eaio.platform.infrastructure.scheduler.JobSchedulerRegistrar}：按 {@code job} 表建
 *       Spring {@code ThreadPoolTaskScheduler} 的 {@code CronTrigger} 任务，并注册内部的"重投扫描"
 *       固定间隔任务。</li>
 * </ul>
 *
 * <p>本层的两条硬口径：
 * <ol>
 *   <li><b>调度事实在内存，不在库</b>（3.4.6）：{@code job.next_run_time} 只作展示，
 *       "暂停/恢复/重建"都是内存动作（{@code pause} 因此不落库，重启后按 {@code enabled} 重新调度）；</li>
 *   <li><b>无库即不调度</b>：没有 DataSource 时锁不可用，调度整体停用并记一次 WARN，
 *       绝不用进程内锁假装互斥（两个实例同时执行同一任务比不执行更糟）。</li>
 * </ol>
 */
package com.eaio.platform.infrastructure.scheduler;
