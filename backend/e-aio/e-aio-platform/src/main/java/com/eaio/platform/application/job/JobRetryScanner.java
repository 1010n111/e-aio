package com.eaio.platform.application.job;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.eaio.common.id.IdGenerator;
import com.eaio.platform.domain.job.Job;
import com.eaio.platform.domain.job.JobLockHandle;
import com.eaio.platform.domain.job.JobLockWindow;
import com.eaio.platform.domain.job.JobRun;
import com.eaio.platform.domain.job.JobRunStatus;
import com.eaio.platform.infrastructure.persistence.JobRunStore;
import com.eaio.platform.infrastructure.persistence.JobStore;
import com.eaio.platform.infrastructure.scheduler.JobLocker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 重投扫描器（P1 册 3.4.2 的 {@code JobRetryScanner}）：扫 {@code RETRYING} 且到期的 {@code job_run} 重投。
 *
 * <p><b>它自己不是 job 行</b>（差异登记在《实现注记（T7）》）：3.4.4 写它是
 * "每 30 秒取一批"，4.5 的种子又只有 3.4.5 的 6 个任务（里面没有它），因此它由调度器**内部**按固定
 * 30 秒注册，用**独立的锁名**（{@code platform-job-retry-scan}）保证集群内只有一个实例在扫。
 * 代价明确：管理页看不到它、也不能暂停/手动触发它（那正是 3.4.7 想要的"不重试自己"：它坏了只记
 * ERROR，不产生重试风暴）。
 *
 * <p><b>认领是原子的</b>：{@code UPDATE ... WHERE status = 'RETRYING'} 的受影响行数就是凭据
 * ——两个实例扫到同一行，只有一个能认领（6.2 的断言）。锁只用来避免"同一任务的另一次运行正在跑"时
 * 又来重投（拿不到锁就下一轮再扫，行仍是 RETRYING，不丢）。
 *
 * <p>三种"不能跑"的处理口径：
 * <ul>
 *   <li>任务行已删除 → 记 {@code FAILED}（再也没人会跑它，留着 RETRYING 只会每 30 秒被扫一次）；</li>
 *   <li>任务被停用 / 处理点未注册 → **留在 RETRYING 跳过**（停用是"暂时不可执行"，处理点可能随模块
 *       回滚暂时缺席；两种情况恢复后都应当继续重投）；</li>
 *   <li>拿不到锁 → 跳过，下一轮再来。</li>
 * </ul>
 */
@Component
public class JobRetryScanner {

    private static final Logger log = LoggerFactory.getLogger(JobRetryScanner.class);

    private final JobRunStore runs;
    private final JobStore jobs;
    private final JobHandlerRegistry handlers;
    private final JobExecutor executor;
    private final JobLocker locker;
    private final JobParams params;
    private final IdGenerator idGenerator;

    public JobRetryScanner(JobRunStore runs, JobStore jobs, JobHandlerRegistry handlers, JobExecutor executor,
            JobLocker locker, JobParams params, IdGenerator idGenerator) {
        this.runs = runs;
        this.jobs = jobs;
        this.handlers = handlers;
        this.executor = executor;
        this.locker = locker;
        this.params = params;
        this.idGenerator = idGenerator;
    }

    /** 扫一轮（由调度器每 30 秒调用；也可在测试里直接调）。返回本轮认领并重投的条数。 */
    public int scanOnce() {
        List<JobRun> due = runs.dueRetries(params.retryBatchSize());
        int claimed = 0;
        for (JobRun row : due) {
            Job job = jobs.rowByCode(row.getJobCode());
            if (job == null) {
                markAbandoned(row.getId(), "任务定义已删除，不再重投");
                continue;
            }
            if (!job.isEnabled()) {
                log.debug("任务已停用，本轮不重投（行保持 RETRYING）：jobCode={} runId={}", job.getJobCode(),
                        row.getId());
                continue;
            }
            if (!handlers.contains(job.getHandlerCode())) {
                log.warn("任务处理点未注册，本轮不重投（行保持 RETRYING）：jobCode={} handler={}", job.getJobCode(),
                        job.getHandlerCode());
                continue;
            }
            Optional<JobLockHandle> lock = locker.tryLock(JobLockWindow.lockName(job.getJobCode()),
                    JobLockWindow.lockAtMostFor(job));
            if (lock.isEmpty()) {
                log.debug("同类任务正在执行，本轮不重投：jobCode={} runId={}", job.getJobCode(), row.getId());
                continue;
            }
            if (!runs.claimRetry(row.getId(), executor.nodeId())) {
                // 已被别的实例（或别的扫描周期）认领：本实例什么都没做，把锁还回去
                lock.get().release();
                continue;
            }
            claimed++;
            executor.submitClaimed(job, row, idGenerator.nextStr(), lock.get());
        }
        return claimed;
    }

    /** 任务定义已删除：把这行收成终态，不再让部分索引每 30 秒把它捞出来。 */
    private void markAbandoned(long runId, String reason) {
        Instant now = Instant.now();
        JobRun row = new JobRun();
        row.setId(runId);
        row.setStatus(JobRunStatus.FAILED.name());
        row.setEndTime(now);
        row.setErrorMessage(reason);
        runs.updateById(row);
        log.warn("运行日志行不再重投（{}）：runId={}", reason, runId);
    }
}
