package com.eaio.platform.application.job;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import com.eaio.common.id.IdGenerator;
import com.eaio.platform.api.JobHandler;
import com.eaio.platform.api.dto.JobContext;
import com.eaio.platform.domain.job.Job;
import com.eaio.platform.domain.job.JobLockHandle;
import com.eaio.platform.domain.job.JobRetryPolicy;
import com.eaio.platform.domain.job.JobRun;
import com.eaio.platform.domain.job.JobRunStatus;
import com.eaio.platform.domain.job.JobTriggerType;
import com.eaio.platform.events.JobFailedEvent;
import com.eaio.platform.infrastructure.persistence.JobRunStore;
import com.eaio.platform.infrastructure.persistence.JobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 执行壳（P1 册 3.4.2 的 {@code JobExecutor}）：写 {@code job_run(RUNNING)} → 调处理点（可超时/中断）→
 * 写终态 → 决定重投 → 终态失败时发 {@link JobFailedEvent}。
 *
 * <p><b>三条入口，一套状态机</b>：
 * <ul>
 *   <li>{@link #runUnderLock}：cron 路径。调用方（调度器）**已持有分布式锁**，本方法同步执行到终态后才返回
 *       ——锁必须在执行期间一直持有，否则第二个实例会在同一时刻跑同一任务；</li>
 *   <li>{@link #submitNew}：手动触发（5.2 的 {@code job/Run}）。立即返回 {@code runId}，执行在 worker
 *       线程；锁由调用方在请求线程上取好并交给这里（取不到就是 20021）；</li>
 *   <li>{@link #submitClaimed}：重投路径（{@code job_run} 行已被 {@code claimRetry} 原子认领为 RUNNING）。
 *       认领本身就是互斥凭据，但锁仍由调用方持着，避免与"同一任务正在执行的另一次运行"撞车。</li>
 * </ul>
 *
 * <p><b>超时怎么做到"状态明确"而不是永远 RUNNING</b>：处理点跑在 **worker 线程**上，执行壳在
 * {@code future.get(timeout)} 上等；到点先 {@code cancel(true)} 中断它，再**无条件**把状态写成
 * {@code TIMEOUT}。处理点就算吞掉中断继续跑，运行日志上的状态也是确定的（它多跑完的那点副作用由处理点
 * 自己按 {@code ctx.expired()} 检查——这也是 {@code JobHandler} javadoc 里写明的责任）。
 *
 * <p>线程账（一跑一 worker，加上等待线程）：worker 池是无界的 {@code cachedThreadPool}（守护线程、
 * 名字 {@code eaio-job-worker-N}），理由有二——处理点超时要用"另一个线程可被中断"来实现；等待线程
 * （cron 是调度器线程，手动/重投是 worker 线程）在等 handler 的 future，若用固定池就会自己在池里等自己。
 * 并发度由**调度器线程池**（{@code platform.scheduler.pool-size}，默认 4）与"同一任务不并发"共同决定。
 */
@Component
public class JobExecutor {

    private static final Logger log = LoggerFactory.getLogger(JobExecutor.class);

    /** 错误摘要的落库上限（4.3.10 的列注释：截 2000 字符，不落完整堆栈）。 */
    private static final int ERROR_MESSAGE_MAX = 2000;

    private static final AtomicLong THREAD_SEQ = new AtomicLong();

    private final JobRunStore runs;
    private final JobStore jobs;
    private final JobHandlerRegistry handlers;
    private final JobDtoMapper dtoMapper;
    private final ApplicationEventPublisher events;
    private final IdGenerator idGenerator;
    private final ExecutorService workers;
    private final String nodeId;

    public JobExecutor(JobRunStore runs, JobStore jobs, JobHandlerRegistry handlers, JobDtoMapper dtoMapper,
            ApplicationEventPublisher events, IdGenerator idGenerator) {
        this.runs = runs;
        this.jobs = jobs;
        this.handlers = handlers;
        this.dtoMapper = dtoMapper;
        this.events = events;
        this.idGenerator = idGenerator;
        this.workers = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "eaio-job-worker-" + THREAD_SEQ.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        this.nodeId = resolveNodeId();
    }

    /**
     * cron 路径：同步执行到终态（调用方持锁，返回后才放锁）。
     *
     * @param params 本次参数；{@code null} 取 {@code job.params_json}
     * @return 运行 ID
     */
    public long runUnderLock(Job job, JobTriggerType triggerType, Map<String, String> params, String traceId) {
        Map<String, String> actual = paramsOf(job, params);
        long runId = createRun(job, triggerType, 1, traceId);
        invoke(job, runId, actual, 1, traceId);
        return runId;
    }

    /**
     * 手动触发：立即返回 {@code runId}，执行在 worker 线程；锁在跑完后由本方法释放。
     *
     * @param lock 请求线程上取到的执行令牌（取不到时调用方已按 20021 拒绝，不会走到这里）
     */
    public long submitNew(Job job, Map<String, String> params, String traceId, JobLockHandle lock) {
        Map<String, String> actual = paramsOf(job, params);
        long runId = createRun(job, JobTriggerType.MANUAL, 1, traceId);
        workers.execute(() -> {
            try {
                invoke(job, runId, actual, 1, traceId);
            } finally {
                releaseQuietly(lock);
            }
        });
        return runId;
    }

    /**
     * 重投路径：行已被 {@code claimRetry} 认领（{@code status = RUNNING}，{@code attempt} 是本次的序号）。
     *
     * <p>参数取 {@code job.params_json}：{@code job_run} 没有参数列（DDL 冻结），手动触发时临时传入的
     * 参数不会跟着重投走——重投用的是任务上保存的参数（登记在《实现注记（T7）》）。
     */
    public void submitClaimed(Job job, JobRun claimed, String traceId, JobLockHandle lock) {
        Integer claimedAttempt = claimed.getAttempt();
        int attempt = claimedAttempt == null ? 1 : Math.max(1, claimedAttempt);
        Map<String, String> params = paramsOf(job, null);
        workers.execute(() -> {
            try {
                invoke(job, claimed.getId(), params, attempt, traceId);
            } finally {
                releaseQuietly(lock);
            }
        });
    }

    /**
     * 记一条"本次未执行"的运行（3.4.6：不允许并发时上一次仍在跑 → 写 {@code SKIPPED}，不写噪音就是没记录）。
     *
     * <p>{@code SKIPPED} 是 {@code job.last_status} 允许的四个值之一
     * （{@code ck_job_last_status}），所以它同时回写任务行——管理页一眼能看出"最近一次是排队没跑"。
     */
    public long recordSkipped(Job job, JobTriggerType triggerType, String reason) {
        Instant now = Instant.now();
        long runId = idGenerator.nextId();
        JobRun row = new JobRun();
        row.setId(runId);
        row.setJobCode(job.getJobCode());
        row.setTriggerType(triggerType.name());
        row.setStatus(JobRunStatus.SKIPPED.name());
        row.setAttempt(1);
        row.setStartTime(now);
        row.setEndTime(now);
        row.setDurationMs(0);
        row.setNodeId(nodeId);
        row.setErrorMessage(reason);
        row.setCreatedAt(now);
        runs.insert(row);
        jobs.updateLastRun(job.getJobCode(), now, JobRunStatus.SKIPPED.name());
        log.warn("任务本次未执行（记 SKIPPED）：jobCode={} triggerType={} 原因={}", job.getJobCode(), triggerType,
                reason);
        return runId;
    }

    /**
     * 本实例的标识（{@code host:pid}）。重投扫描器认领 {@code RETRYING} 行时也要写它，
     * 所以从执行壳暴露出来，避免两处各算一遍（算两遍就会出现"同一次运行换了个 node_id"的怪日志）。
     */
    public String nodeId() {
        return nodeId;
    }

    // ---------------------------------------------------------------- 内部

    private long createRun(Job job, JobTriggerType triggerType, int attempt, String traceId) {
        Instant now = Instant.now();
        long runId = idGenerator.nextId();
        JobRun row = new JobRun();
        row.setId(runId);
        row.setJobCode(job.getJobCode());
        row.setTriggerType(triggerType.name());
        row.setStatus(JobRunStatus.RUNNING.name());
        row.setAttempt(attempt);
        row.setStartTime(now);
        row.setNodeId(nodeId);
        row.setTraceId(traceId);
        row.setCreatedAt(now);
        runs.insert(row);
        return runId;
    }

    /** 真正调处理点并把结果写定（本方法可能在调度器线程或 worker 线程上执行）。 */
    private void invoke(Job job, long runId, Map<String, String> params, int attempt, String traceId) {
        Instant start = Instant.now();
        int timeoutSeconds = Math.max(1, job.getTimeoutSeconds());
        Instant deadline = start.plusSeconds(timeoutSeconds);
        JobHandler handler = handlers.find(job.getHandlerCode()).orElse(null);
        if (handler == null) {
            finish(job, runId, attempt, start, JobRunStatus.FAILED,
                    "任务处理点未注册：" + job.getHandlerCode() + "（可执行点只能来自代码注册的 JobHandler Bean）",
                    traceId);
            return;
        }
        JobContext context = new JobContext(job.getJobCode(), params, runId, attempt, traceId, deadline);
        Future<?> future = workers.submit(() -> handler.execute(context));
        try {
            future.get(timeoutSeconds, TimeUnit.SECONDS);
            finish(job, runId, attempt, start, JobRunStatus.SUCCESS, null, traceId);
        } catch (TimeoutException e) {
            future.cancel(true);
            finish(job, runId, attempt, start, JobRunStatus.TIMEOUT,
                    "执行超过 timeout_seconds=" + timeoutSeconds + "，线程已中断", traceId);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            finish(job, runId, attempt, start, JobRunStatus.FAILED, describe(cause), traceId);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            finish(job, runId, attempt, start, JobRunStatus.TIMEOUT, "执行被中断（实例停机或线程被抢占）", traceId);
        }
    }

    /**
     * 写终态：能重投就写 {@code RETRYING}（{@code attempt + 1} + {@code next_retry_time}），
     * 否则写终态并回写任务行 + 发失败事件。
     *
     * <p>重投的判定在 {@link JobRetryPolicy}（纯函数）：{@code attempt ≤ retry_max} 才有下一次，
     * 退避 {@code backoff × 2^(attempt-1)} 封顶 30 分钟。**不 sleep 占线程**——等待期由
     * {@code job_run.next_retry_time} + 重投扫描器承担（3.4.4）。
     */
    private void finish(Job job, long runId, int attempt, Instant start, JobRunStatus status, String error,
            String traceId) {
        Instant now = Instant.now();
        int durationMs = (int) Math.min(Integer.MAX_VALUE, Duration.between(start, now).toMillis());
        String message = truncate(error);
        boolean retry = status != JobRunStatus.SUCCESS && JobRetryPolicy.canRetry(attempt, job.getRetryMax());
        JobRun row = new JobRun();
        row.setId(runId);
        row.setEndTime(now);
        row.setDurationMs(durationMs);
        row.setErrorMessage(message);
        if (retry) {
            row.setStatus(JobRunStatus.RETRYING.name());
            row.setAttempt(attempt + 1);
            row.setNextRetryTime(JobRetryPolicy.nextRetryAt(now, job.getBackoffSeconds(), attempt));
        } else {
            row.setStatus(status.name());
            row.setAttempt(attempt);
        }
        runs.updateById(row);
        if (!retry) {
            jobs.updateLastRun(job.getJobCode(), start, status.name());
        }
        if (status == JobRunStatus.SUCCESS) {
            log.info("任务执行成功：jobCode={} runId={} attempt={} 耗时={}ms", job.getJobCode(), runId, attempt,
                    durationMs);
            return;
        }
        if (retry) {
            log.warn("任务执行失败，已排入重投：jobCode={} runId={} attempt={} 下次={} 错误={}", job.getJobCode(),
                    runId, attempt, row.getNextRetryTime(), message);
            return;
        }
        log.error("任务执行失败（终态 {}）：jobCode={} runId={} attempt={} 错误={}", status, job.getJobCode(), runId,
                attempt, message);
        events.publishEvent(new JobFailedEvent(idGenerator.nextStr(), now, job.getJobCode(), runId, attempt, message,
                traceId));
    }

    /** 本次参数：显式传入优先，否则取任务上保存的参数（永不为 {@code null}）。 */
    private Map<String, String> paramsOf(Job job, Map<String, String> overrides) {
        if (overrides != null && !overrides.isEmpty()) {
            return Map.copyOf(overrides);
        }
        return dtoMapper.decodeParams(job.getParamsJson());
    }

    /** 异常 → 2000 字符以内的错误摘要（类型 + 消息；不落完整堆栈，4.3.10）。 */
    private static String describe(Throwable cause) {
        String type = cause.getClass().getSimpleName();
        String message = cause.getMessage();
        return message == null ? type : type + ": " + message;
    }

    private static String truncate(String text) {
        if (text == null || text.length() <= ERROR_MESSAGE_MAX) {
            return text;
        }
        return text.substring(0, ERROR_MESSAGE_MAX);
    }

    private static void releaseQuietly(JobLockHandle lock) {
        if (lock == null) {
            return;
        }
        try {
            lock.release();
        } catch (RuntimeException e) {
            log.warn("释放任务锁失败（锁到期会自然失效）：{}", e.getMessage());
        }
    }

    /** 实例标识（{@code host:pid}，≤64），写进 {@code job_run.node_id}：多实例下看出"是谁跑的"。 */
    private static String resolveNodeId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown-host";
        }
        String value = host + ":" + ProcessHandle.current().pid();
        return value.length() <= 64 ? value : value.substring(0, 64);
    }
}
