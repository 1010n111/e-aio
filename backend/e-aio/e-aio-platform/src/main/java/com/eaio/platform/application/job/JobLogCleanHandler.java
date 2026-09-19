package com.eaio.platform.application.job;

import java.time.Duration;
import java.time.Instant;

import com.eaio.platform.api.JobHandler;
import com.eaio.platform.api.dto.JobContext;
import com.eaio.platform.domain.job.Job;
import com.eaio.platform.domain.job.JobLockWindow;
import com.eaio.platform.infrastructure.persistence.JobRunStore;
import com.eaio.platform.infrastructure.persistence.JobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 内置任务 {@code platform.job.log.clean} 的处理点（P1 册 3.4.5 第 1 行、3.4.4 的"保留"、
 * 3.4.7 的宕机收尾）：每天 03:00 由 cron 触发，干两件事。
 *
 * <ol>
 *   <li><b>收僵尸</b>：把"超过 {@code lockAtMostFor × 2} 仍是 {@code RUNNING}"的行置 {@code FAILED}
 *       ——实例在任务执行中宕机时，ShedLock 的锁会到期让别的实例接管，但那条运行记录不会被任何人改写，
 *       表现是任务状态永远"运行中"（3.4.7）。判定线按**每个任务自己**的超时与退避算，
 *       所以这里逐个任务算（{@link JobLockWindow#staleBefore(Job, Instant)}）；</li>
 *   <li><b>按保留天数分批删</b>：{@code platform.job.log-retain-days}（默认 90）天之前的行，
 *       **1000 行/批**，避免一次 DELETE 撑爆 WAL（3.4.4）。单次最多 100 批（10 万行）——
 *       剩下的一次跑不完就留给明天，绝不在一个任务里无限循环把调度线程占死。</li>
 * </ol>
 *
 * <p>{@link JobContext#expired()} 在每批之间检查：超时中断是"另一线程 cancel(true)"，长循环主动退出
 * 才是处理点的责任（{@link JobHandler} 的 javadoc 约定 3）。
 */
@Component
public class JobLogCleanHandler implements JobHandler {

    /** 处理点编码（与 {@code job.handler_code}、种子 ID 51 逐字一致）。 */
    public static final String CODE = "platform.job.log.clean";

    /** 删除批次大小（3.4.4 逐字：1000 行/批）。 */
    private static final int DELETE_BATCH_SIZE = 1000;

    /** 单次运行最多删多少批（10 万行）：防止"某次积压巨大"把调度线程占死。 */
    private static final int MAX_BATCHES = 100;

    private static final Logger log = LoggerFactory.getLogger(JobLogCleanHandler.class);

    private final JobStore jobs;
    private final JobRunStore runs;
    private final JobParams params;

    public JobLogCleanHandler(JobStore jobs, JobRunStore runs, JobParams params) {
        this.jobs = jobs;
        this.runs = runs;
        this.params = params;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public void execute(JobContext ctx) {
        Instant now = Instant.now();
        int stale = 0;
        for (Job job : jobs.all()) {
            if (ctx.expired()) {
                log.warn("任务日志清理超时退出（僵尸行收尾阶段）：jobCode={} runId={}", ctx.jobCode(), ctx.runId());
                return;
            }
            stale += runs.markStaleRunningAsFailed(JobLockWindow.staleBefore(job, now),
                    "疑似实例宕机：超过 lockAtMostFor × 2 仍为 RUNNING（3.4.7）");
        }
        int retainDays = params.logRetainDays();
        Instant before = now.minus(Duration.ofDays(retainDays));
        long deleted = 0;
        int batches = 0;
        int current;
        do {
            current = runs.deleteBatchBefore(before, DELETE_BATCH_SIZE);
            deleted += current;
            batches++;
        } while (current == DELETE_BATCH_SIZE && batches < MAX_BATCHES && !ctx.expired());
        log.info("任务运行日志清理完成：jobCode={} runId={} 僵尸行收尾={} 按保留 {} 天删除={} 批数={}",
                ctx.jobCode(), ctx.runId(), stale, retainDays, deleted, batches);
    }
}
