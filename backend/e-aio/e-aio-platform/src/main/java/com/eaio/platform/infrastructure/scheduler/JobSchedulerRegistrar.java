package com.eaio.platform.infrastructure.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import com.eaio.common.id.IdGenerator;
import com.eaio.platform.application.job.JobExecutor;
import com.eaio.platform.application.job.JobHandlerRegistry;
import com.eaio.platform.application.job.JobParams;
import com.eaio.platform.application.job.JobRetryScanner;
import com.eaio.platform.domain.job.Job;
import com.eaio.platform.domain.job.JobCron;
import com.eaio.platform.domain.job.JobLockHandle;
import com.eaio.platform.domain.job.JobLockWindow;
import com.eaio.platform.domain.job.JobTriggerType;
import com.eaio.platform.infrastructure.persistence.JobRunStore;
import com.eaio.platform.infrastructure.persistence.JobStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

/**
 * 调度器注册（P1 册 3.4.2 的 {@code JobSchedulerRegistrar}）：按 {@code job} 表建
 * {@code ThreadPoolTaskScheduler} 的 {@code CronTrigger} 任务；{@code enable/disable/Up/Del}
 * 触发取消 + 重建。
 *
 * <p><b>启动期校验，坏任务只跳过自己</b>（3.4.2 逐字）：cron 非法（20022）或处理点未注册（20023）的任务
 * 记 ERROR 并跳过，**不阻断启动**——否则种子里有一个跑不起来的任务，整个应用就起不来。
 *
 * <p><b>为什么在 {@code ApplicationReadyEvent} 才建调度器</b>：池大小来自参数中心
 * （{@code platform.scheduler.pool-size}，7.2），而参数表由 Flyway 建；在构造期读参数就是"表还没建
 * 就去查"的竞态。ready 事件在所有迁移与装配之后，且模块的 {@code SchedulerApi.register}
 * （也在启动期调用）写下的行此时已经能读到。
 *
 * <p><b>每次执行前重新读一次任务行</b>：cron 到点时任务可能已被停用/删除/改了超时。多一次主键级查询
 * 换"配置改动立刻生效、不用等重启"，值得。
 *
 * <p><b>执行路径（3.4.3 的时序）</b>：取锁（{@code platform-job-{code}}，
 * {@code lockAtMostFor = 超时 + 退避 + 60s}）→ 未取到则跳过本次（DEBUG，不写 {@code job_run}，
 * 避免噪音）→ 取到则检查"上一次是否仍在跑"（不允许并发时记 {@code SKIPPED}）→ 同步执行到终态 → 放锁。
 */
@Component
public class JobSchedulerRegistrar {

    private static final Logger log = LoggerFactory.getLogger(JobSchedulerRegistrar.class);

    /** 重投扫描的固定间隔（3.4.4：每 30 秒一批）。 */
    private static final Duration RETRY_SCAN_INTERVAL = Duration.ofSeconds(30);

    /** 重投扫描自身的持锁上限：扫描只做"认领 + 提交"，正常是毫秒级；给 5 分钟覆盖库抖动。 */
    private static final Duration RETRY_SCAN_LOCK_AT_MOST = Duration.ofMinutes(5);

    private final JobStore store;
    private final JobRunStore runs;
    private final JobExecutor executor;
    private final JobRetryScanner retryScanner;
    private final JobHandlerRegistry handlers;
    private final JobLocker locker;
    private final JobParams params;
    private final IdGenerator idGenerator;

    /** {@code jobCode → 已注册的调度句柄}（内存即调度事实，3.4.6）。 */
    private final Map<String, ScheduledFuture<?>> scheduled = new ConcurrentHashMap<>();

    private ThreadPoolTaskScheduler scheduler;
    private volatile boolean started;

    public JobSchedulerRegistrar(JobStore store, JobRunStore runs, JobExecutor executor, JobRetryScanner retryScanner,
            JobHandlerRegistry handlers, JobLocker locker, JobParams params, IdGenerator idGenerator) {
        this.store = store;
        this.runs = runs;
        this.executor = executor;
        this.retryScanner = retryScanner;
        this.handlers = handlers;
        this.locker = locker;
        this.params = params;
        this.idGenerator = idGenerator;
    }

    /** 启动期：建线程池 → 全量校验并注册 → 挂重投扫描。无库/无锁时整体停用（记一次 WARN）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!store.available() || !runs.available() || !locker.available()) {
            log.warn("定时任务调度未启动：需要数据库（读 job 表）与分布式锁；"
                    + "本实例不会执行任何任务（无库也能启动的既定口径）");
            return;
        }
        ThreadPoolTaskScheduler pool = new ThreadPoolTaskScheduler();
        pool.setPoolSize(params.schedulerPoolSize());
        pool.setThreadNamePrefix("eaio-job-scheduler-");
        // 守护线程 + 停机等待：调度线程绝不能成为"JVM 退不出去"的原因（集成测试的 fork 会卡 30 秒被强杀）；
        // 正在跑的任务仍由 @PreDestroy 的 shutdown() 等它收尾。
        pool.setDaemon(true);
        pool.setWaitForTasksToCompleteOnShutdown(true);
        pool.setAwaitTerminationSeconds(30);
        pool.initialize();
        this.scheduler = pool;
        this.started = true;
        scheduleAll();
        scheduleRetryScan();
    }

    /**
     * 按库里的任务行全量注册（启动期与"显式重建"用）。
     *
     * @return 成功挂上调度的任务数
     */
    public int scheduleAll() {
        if (!started) {
            return 0;
        }
        int count = 0;
        List<Job> jobs = store.all();
        for (Job job : jobs) {
            if (schedule(job)) {
                count++;
            }
        }
        log.info("任务调度注册完成：库里 {} 条，成功挂上 {} 条（未启用的任务不排期）", jobs.size(), count);
        return count;
    }

    /** 注册/重建单个任务；返回是否挂上（未启用、cron 非法、处理点未注册都返回 false 并记日志）。 */
    public boolean schedule(Job job) {
        if (!started) {
            // 启动期（ready 之前）由 scheduleAll 统一处理，这里不重复建
            return false;
        }
        String jobCode = job.getJobCode();
        if (!job.isEnabled()) {
            cancel(jobCode);
            return false;
        }
        if (!JobCron.isValid(job.getCron())) {
            cancel(jobCode);
            log.error("任务 cron 非法，已跳过调度（不阻断启动）：jobCode={} cron={}（Spring 6 段，"
                    + "不支持 Quartz 的 ?/L/W/#，3.4.6）", jobCode, job.getCron());
            return false;
        }
        if (!handlers.contains(job.getHandlerCode())) {
            cancel(jobCode);
            log.error("任务处理点未注册，已跳过调度（不阻断启动）：jobCode={} handlerCode={}"
                    + "（可执行点只能来自代码注册的 JobHandler Bean，3.4.1）", jobCode, job.getHandlerCode());
            return false;
        }
        cancel(jobCode);
        CronTrigger trigger = new CronTrigger(job.getCron().trim());
        ScheduledFuture<?> future = scheduler.schedule(() -> run(jobCode), trigger);
        scheduled.put(jobCode, future);
        Instant next = JobCron.nextRun(job.getCron(), Instant.now()).orElse(null);
        store.updateNextRunTime(jobCode, next);
        log.info("任务已排期：jobCode={} cron={} 下次={}", jobCode, job.getCron(), next);
        return true;
    }

    /** 取消调度（停用/删除/暂停共用）；只动内存与展示列，不改 {@code enabled}（3.4.6 的 pause 语义）。 */
    public void cancel(String jobCode) {
        ScheduledFuture<?> future = scheduled.remove(jobCode);
        if (future != null) {
            future.cancel(false);
            log.info("任务已取消调度：jobCode={}", jobCode);
        }
        if (started) {
            store.updateNextRunTime(jobCode, null);
        }
    }

    /** 本实例当前是否挂着该任务的 cron 调度（管理页展示用；多实例下"某实例挂着"就为 true）。 */
    public boolean isScheduled(String jobCode) {
        ScheduledFuture<?> future = scheduled.get(jobCode);
        return future != null && !future.isCancelled() && !future.isDone();
    }

    /** 调度器是否已启动（无库/无锁时为 false；{@code resume} 据此区分"排不上"与"还没启动"）。 */
    public boolean isStarted() {
        return started;
    }

    /**
     * 执行一次 cron 任务（3.4.3 的时序）。**public 是为了给集成测试一个确定的触发点**
     * （cron 到点不可控，测试直接调它走的是同一条生产路径：取锁 → 并发检查 → 执行 → 放锁）。
     */
    public void run(String jobCode) {
        Job job = store.rowByCode(jobCode);
        if (job == null) {
            log.warn("任务已不存在，取消调度：jobCode={}", jobCode);
            cancel(jobCode);
            return;
        }
        Optional<JobLockHandle> lock = locker.tryLock(JobLockWindow.lockName(jobCode),
                JobLockWindow.lockAtMostFor(job));
        if (lock.isEmpty()) {
            // 别的实例正在跑（或锁服务不可用）：**不写 job_run**，避免每个实例每轮都留一行噪音（3.4.3）
            log.debug("未取到任务锁，跳过本次：jobCode={}", jobCode);
            return;
        }
        try {
            if (!job.isEnabled()) {
                log.info("任务已停用，取消调度并跳过本次：jobCode={}", jobCode);
                cancel(jobCode);
                return;
            }
            if (!handlers.contains(job.getHandlerCode())) {
                log.error("任务处理点未注册，跳过本次：jobCode={} handlerCode={}", jobCode, job.getHandlerCode());
                return;
            }
            Instant now = Instant.now();
            if (!job.isAllowConcurrent() && runs.hasRunning(jobCode, JobLockWindow.staleBefore(job, now))) {
                executor.recordSkipped(job, JobTriggerType.CRON,
                        "上一次执行仍在运行且未允许并发（allow_concurrent = false，3.4.6）");
                return;
            }
            executor.runUnderLock(job, JobTriggerType.CRON, null, idGenerator.nextStr());
        } finally {
            lock.get().release();
        }
    }

    /** 重投扫描：固定 30 秒一次，自身也互斥（用独立锁名，与任务锁不冲突）。 */
    private void scheduleRetryScan() {
        scheduler.scheduleWithFixedDelay(() -> {
            Optional<JobLockHandle> lock = locker.tryLock(JobLockWindow.retryScanLockName(), RETRY_SCAN_LOCK_AT_MOST);
            if (lock.isEmpty()) {
                return;
            }
            try {
                retryScanner.scanOnce();
            } catch (Exception e) {
                // 固定延迟任务里抛异常会让它被静默取消（Spring 的既定行为）→ 必须自己吞掉并记 ERROR
                log.error("重投扫描失败（本轮不做任何事，30 秒后重试）：{}", e.toString(), e);
            } finally {
                lock.get().release();
            }
        }, RETRY_SCAN_INTERVAL);
        log.info("重投扫描已挂载：每 {} 秒一批（platform.job.retry-batch-size={}），锁名={}",
                RETRY_SCAN_INTERVAL.toSeconds(), params.retryBatchSize(), JobLockWindow.retryScanLockName());
    }

    /** 停机：等正在跑的任务收尾（最多 30 秒），不再接新的触发。 */
    @PreDestroy
    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdown();
            log.info("任务调度器已停止");
        }
    }
}
