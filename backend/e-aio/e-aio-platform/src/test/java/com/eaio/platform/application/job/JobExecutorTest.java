package com.eaio.platform.application.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.eaio.common.id.IdGenerator;
import com.eaio.platform.api.JobHandler;
import com.eaio.platform.api.dto.JobContext;
import com.eaio.platform.domain.job.Job;
import com.eaio.platform.domain.job.JobRun;
import com.eaio.platform.domain.job.JobRunStatus;
import com.eaio.platform.domain.job.JobTriggerType;
import com.eaio.platform.events.JobFailedEvent;
import com.eaio.platform.infrastructure.persistence.JobRunStore;
import com.eaio.platform.infrastructure.persistence.JobStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * {@link JobExecutor} 的状态流转单测（P1 册 3.4.8 的可测点）：成功、失败重投、达到上限的终态、
 * 超时中断、运行时处理点消失、跳过。
 *
 * <p>用 mock 的 Store 把"写出去的 {@code job_run} 行"抓下来断言——不需要数据库，也不需要真实的
 * 调度器；这正是 3.4.2 把执行壳放 application 层的收益。
 *
 * <p>超时用例真的会让处理点睡 3 秒并被 {@code cancel(true)} 打断，断言两件事：状态是 {@code TIMEOUT}
 * （不是永远 RUNNING），以及线程确实被中断（3.4.8 的"handler 里 isInterrupted() 为真"）。
 */
class JobExecutorTest {

    private static final String JOB_CODE = "it.job.executor";
    private static final String HANDLER_CODE = "it.job.executor.handler";

    private JobRunStore runs;
    private JobStore jobs;
    private ApplicationEventPublisher events;
    private JobExecutor executor;

    private final AtomicInteger executions = new AtomicInteger();

    @BeforeEach
    void setUp() {
        runs = mock(JobRunStore.class);
        jobs = mock(JobStore.class);
        events = mock(ApplicationEventPublisher.class);
        executions.set(0);
    }

    /** 建执行壳；处理点为传入的实现（每次调用重新建，保证 code 不串）。 */
    private JobExecutor executorWith(JobHandler handler) {
        JobHandlerRegistry registry = new JobHandlerRegistry(List.of(handler));
        return new JobExecutor(runs, jobs, registry, new JobDtoMapper(), events, new IdGenerator(1));
    }

    @Test
    @DisplayName("成功：先写 RUNNING，再写 SUCCESS（带 end_time/duration_ms）并回写任务的 last_run_time/last_status")
    void successWritesRunningThenSuccess() {
        executor = executorWith(handler(HANDLER_CODE, ctx -> executions.incrementAndGet()));

        long runId = executor.runUnderLock(job(300, 3, 30), JobTriggerType.CRON, Map.of("days", "7"), "t-1");

        ArgumentCaptor<JobRun> inserted = ArgumentCaptor.forClass(JobRun.class);
        verify(runs).insert(inserted.capture());
        assertThat(inserted.getValue().getStatus()).isEqualTo(JobRunStatus.RUNNING.name());
        assertThat(inserted.getValue().getTriggerType()).isEqualTo(JobTriggerType.CRON.name());
        assertThat(inserted.getValue().getAttempt()).isEqualTo(1);
        assertThat(inserted.getValue().getId()).isEqualTo(runId);

        ArgumentCaptor<JobRun> updated = ArgumentCaptor.forClass(JobRun.class);
        verify(runs).updateById(updated.capture());
        assertThat(updated.getValue().getStatus()).isEqualTo(JobRunStatus.SUCCESS.name());
        assertThat(updated.getValue().getEndTime()).isNotNull();
        assertThat(updated.getValue().getDurationMs()).isNotNull();
        assertThat(updated.getValue().getNextRetryTime()).as("成功不排重投").isNull();

        verify(jobs).updateLastRun(eq(JOB_CODE), any(Instant.class), eq(JobRunStatus.SUCCESS.name()));
        verifyNoInteractions(events);
        assertThat(executions.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("失败且还有机会：写 RETRYING（attempt+1 + next_retry_time = now + backoff），不发失败事件")
    void failureWithinRetryLimitSchedulesRetry() {
        executor = executorWith(handler(HANDLER_CODE, ctx -> {
            throw new IllegalStateException("库连接超时");
        }));

        Instant before = Instant.now();
        executor.runUnderLock(job(300, 3, 30), JobTriggerType.CRON, null, "t-2");

        ArgumentCaptor<JobRun> updated = ArgumentCaptor.forClass(JobRun.class);
        verify(runs).updateById(updated.capture());
        JobRun row = updated.getValue();
        assertThat(row.getStatus()).isEqualTo(JobRunStatus.RETRYING.name());
        assertThat(row.getAttempt()).as("行上的 attempt 指向下一次执行").isEqualTo(2);
        assertThat(row.getErrorMessage()).isEqualTo("IllegalStateException: 库连接超时");
        assertThat(row.getNextRetryTime())
                .as("退避 = backoff × 2^(attempt-1) = 30s")
                .isAfterOrEqualTo(before.plusSeconds(29))
                .isBeforeOrEqualTo(before.plusSeconds(35));
        verify(jobs, never()).updateLastRun(anyString(), any(Instant.class), anyString());
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("第 4 次失败（retry_max = 3）：终态 FAILED、不再写 RETRYING、发 JobFailedEvent")
    void failureBeyondRetryLimitBecomesTerminal() {
        executor = executorWith(handler(HANDLER_CODE, ctx -> {
            throw new IllegalStateException("还是失败");
        }));
        JobRun claimed = new JobRun();
        claimed.setId(999L);
        claimed.setJobCode(JOB_CODE);
        claimed.setAttempt(4);

        executor.submitClaimed(job(300, 3, 30), claimed, "t-3", null);

        ArgumentCaptor<JobRun> updated = ArgumentCaptor.forClass(JobRun.class);
        verify(runs, timeout(5000)).updateById(updated.capture());
        assertThat(updated.getValue().getStatus()).isEqualTo(JobRunStatus.FAILED.name());
        assertThat(updated.getValue().getNextRetryTime()).isNull();
        verify(jobs, timeout(5000)).updateLastRun(eq(JOB_CODE), any(Instant.class), eq(JobRunStatus.FAILED.name()));

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(events, timeout(5000)).publishEvent(event.capture());
        assertThat(event.getValue()).isInstanceOf(JobFailedEvent.class);
        JobFailedEvent failed = (JobFailedEvent) event.getValue();
        assertThat(failed.jobCode()).isEqualTo(JOB_CODE);
        assertThat(failed.runId()).isEqualTo(999L);
        assertThat(failed.attempt()).isEqualTo(4);
        assertThat(failed.traceId()).isEqualTo("t-3");
    }

    @Test
    @DisplayName("超时：到 timeout_seconds 中断线程 → 状态 TIMEOUT（retry_max = 0 时是终态），线程确实被打断")
    void timeoutInterruptsHandlerAndWritesTimeout() throws Exception {
        AtomicBoolean interrupted = new AtomicBoolean();
        executor = executorWith(handler(HANDLER_CODE, ctx -> {
            try {
                Thread.sleep(3000L);
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
        }));

        executor.runUnderLock(job(1, 0, 30), JobTriggerType.CRON, null, "t-4");

        ArgumentCaptor<JobRun> updated = ArgumentCaptor.forClass(JobRun.class);
        verify(runs).updateById(updated.capture());
        assertThat(updated.getValue().getStatus()).as("超时必须有明确状态，不能永远 RUNNING").isEqualTo(
                JobRunStatus.TIMEOUT.name());
        assertThat(updated.getValue().getErrorMessage()).contains("timeout_seconds=1");
        assertThat(interrupted).as("3.4.8：处理点线程被 cancel(true) 中断").isTrue();
        verify(jobs).updateLastRun(eq(JOB_CODE), any(Instant.class), eq(JobRunStatus.TIMEOUT.name()));
    }

    @Test
    @DisplayName("错误摘要截 2000 字符（4.3.10）：不落完整堆栈、也不让超长异常撑爆日志表")
    void errorMessageIsTruncated() {
        String longMessage = "x".repeat(5000);
        executor = executorWith(handler(HANDLER_CODE, ctx -> {
            throw new IllegalStateException(longMessage);
        }));

        executor.runUnderLock(job(300, 0, 30), JobTriggerType.CRON, null, "t-5");

        ArgumentCaptor<JobRun> updated = ArgumentCaptor.forClass(JobRun.class);
        verify(runs).updateById(updated.capture());
        assertThat(updated.getValue().getErrorMessage()).hasSize(2000);
    }

    @Test
    @DisplayName("运行时处理点消失：记 FAILED 并说明原因，不把异常抛给调度线程")
    void missingHandlerAtRuntimeIsRecorded() {
        executor = executorWith(handler("it.job.other", ctx -> executions.incrementAndGet()));
        Job job = job(300, 0, 30);

        executor.runUnderLock(job, JobTriggerType.CRON, null, "t-6");

        ArgumentCaptor<JobRun> updated = ArgumentCaptor.forClass(JobRun.class);
        verify(runs).updateById(updated.capture());
        assertThat(updated.getValue().getStatus()).isEqualTo(JobRunStatus.FAILED.name());
        assertThat(updated.getValue().getErrorMessage()).contains("处理点未注册").contains(HANDLER_CODE);
        assertThat(executions.get()).isZero();
    }

    @Test
    @DisplayName("跳过：写 SKIPPED 行 + 回写 last_status，且不调处理点")
    void skippedRunIsRecorded() {
        executor = executorWith(handler(HANDLER_CODE, ctx -> executions.incrementAndGet()));

        long runId = executor.recordSkipped(job(300, 3, 30), JobTriggerType.CRON, "上一次仍在运行");

        ArgumentCaptor<JobRun> inserted = ArgumentCaptor.forClass(JobRun.class);
        verify(runs).insert(inserted.capture());
        assertThat(inserted.getValue().getStatus()).isEqualTo(JobRunStatus.SKIPPED.name());
        assertThat(inserted.getValue().getErrorMessage()).isEqualTo("上一次仍在运行");
        assertThat(inserted.getValue().getId()).isEqualTo(runId);
        verify(jobs).updateLastRun(eq(JOB_CODE), any(Instant.class), eq(JobRunStatus.SKIPPED.name()));
        assertThat(executions.get()).isZero();
    }

    @Test
    @DisplayName("手动触发：立即拿到 runId，执行在后台完成（参数用调用方给的）")
    void manualTriggerRunsAsynchronously() {
        AtomicInteger paramDays = new AtomicInteger(-1);
        executor = executorWith(handler(HANDLER_CODE, ctx -> paramDays.set(Integer.parseInt(ctx.params().get("days")))));

        long runId = executor.submitNew(job(300, 3, 30), Map.of("days", "7"), "t-7", null);

        verify(runs, timeout(5000)).updateById(any(JobRun.class));
        assertThat(runId).isPositive();
        assertThat(paramDays.get()).as("手动触发的参数优先于任务保存的参数").isEqualTo(7);
    }

    @Test
    @DisplayName("手动触发：没给参数时用 job.params_json 里保存的参数；坏 JSON 按无参数处理不炸")
    void manualTriggerFallsBackToSavedParams() {
        AtomicInteger executionsWithParams = new AtomicInteger();
        executor = executorWith(handler(HANDLER_CODE, ctx -> {
            if ("90".equals(ctx.params().get("days"))) {
                executionsWithParams.incrementAndGet();
            }
        }));
        Job job = job(300, 3, 30);
        job.setParamsJson("{\"days\":\"90\"}");

        executor.submitNew(job, null, "t-8", null);
        verify(runs, timeout(5000)).updateById(any(JobRun.class));
        assertThat(executionsWithParams.get()).isEqualTo(1);

        Job broken = job(300, 3, 30);
        broken.setParamsJson("not-json");
        executionsWithParams.set(0);
        executor.submitNew(broken, null, "t-9", null);
        verify(runs, timeout(5000).times(2)).updateById(any(JobRun.class));
        assertThat(executionsWithParams.get()).as("坏参数按空参数跑，任务不因为参数脏就彻底不可用").isZero();
    }

    @Test
    @DisplayName("重投路径：用行上的 attempt（不是固定 1），executor 不乱改次数")
    void claimedRetryKeepsAttemptNumber() {
        executor = executorWith(handler(HANDLER_CODE, ctx -> executions.incrementAndGet()));
        JobRun claimed = new JobRun();
        claimed.setId(123L);
        claimed.setJobCode(JOB_CODE);
        claimed.setAttempt(3);

        executor.submitClaimed(job(300, 3, 30), claimed, "t-10", null);

        verify(runs, timeout(5000)).updateById(any(JobRun.class));
        assertThat(executions.get()).isEqualTo(1);
        verify(jobs, timeout(5000)).updateLastRun(eq(JOB_CODE), any(Instant.class), anyString());
        verify(runs, times(0)).insert(any(JobRun.class));
    }

    private static Job job(int timeoutSeconds, int retryMax, int backoffSeconds) {
        Job job = new Job();
        job.setId(1L);
        job.setJobCode(JOB_CODE);
        job.setJobName("执行壳单测任务");
        job.setHandlerCode(HANDLER_CODE);
        job.setCron("0 0 3 * * *");
        job.setEnabled(true);
        job.setTimeoutSeconds(timeoutSeconds);
        job.setRetryMax(retryMax);
        job.setBackoffSeconds(backoffSeconds);
        job.setAllowConcurrent(false);
        return job;
    }

    private static JobHandler handler(String code, java.util.function.Consumer<JobContext> body) {
        return new JobHandler() {
            @Override
            public String code() {
                return code;
            }

            @Override
            public void execute(JobContext ctx) {
                body.accept(ctx);
            }
        };
    }
}
