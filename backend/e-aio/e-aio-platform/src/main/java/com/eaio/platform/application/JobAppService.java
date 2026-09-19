package com.eaio.platform.application;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.eaio.common.api.ErrorCode;
import com.eaio.common.api.PageResult;
import com.eaio.common.exception.BusinessException;
import com.eaio.common.id.IdGenerator;
import com.eaio.platform.api.PlatformErrorCode;
import com.eaio.platform.api.dto.JobDTO;
import com.eaio.platform.api.dto.JobDefinition;
import com.eaio.platform.api.dto.JobQuery;
import com.eaio.platform.api.dto.JobRunDTO;
import com.eaio.platform.api.dto.JobRunQuery;
import com.eaio.platform.api.dto.JobSaveCmd;
import com.eaio.platform.application.job.JobDtoMapper;
import com.eaio.platform.application.job.JobExecutor;
import com.eaio.platform.application.job.JobHandlerRegistry;
import com.eaio.platform.application.param.ParamContextProvider;
import com.eaio.platform.domain.job.Job;
import com.eaio.platform.domain.job.JobCron;
import com.eaio.platform.domain.job.JobLockHandle;
import com.eaio.platform.domain.job.JobLockWindow;
import com.eaio.platform.domain.job.JobParamRules;
import com.eaio.platform.domain.job.JobRetryPolicy;
import com.eaio.platform.domain.job.JobRun;
import com.eaio.platform.domain.job.JobRunStatus;
import com.eaio.platform.infrastructure.persistence.JobRunStore;
import com.eaio.platform.infrastructure.persistence.JobStore;
import com.eaio.platform.infrastructure.scheduler.JobLocker;
import com.eaio.platform.infrastructure.scheduler.JobSchedulerRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 定时任务应用服务（P1 册 3.4.2）：REST（{@code JobController}）与跨模块 {@code SchedulerApi}
 * **共用同一个服务**（2.4.3），校验与状态机只有一份。
 *
 * <p><b>错误码只用 20020–20025（7.1，不得新增号）</b>：
 * <ul>
 *   <li>20020 {@code JOB_NOT_FOUND}：任务/运行记录不存在（{@code jobCode}/{@code runId} 无效）；</li>
 *   <li>20021 {@code JOB_RUNNING}：未允许并发时正在执行（手动触发、重试、删除运行中的任务）；</li>
 *   <li>20022 {@code JOB_CRON_INVALID}：cron 非法（保存与 {@code resume}）；</li>
 *   <li>20023 {@code JOB_HANDLER_NOT_REGISTERED}：处理点不在代码注册表里（保存、启用、手动触发、重试）；</li>
 *   <li>20024 {@code JOB_DISABLED}：任务已停用（手动触发、重试、恢复调度）；</li>
 *   <li>20025 {@code JOB_TIMEOUT}：只出现在**运行日志状态**里（计时器到点中断），同步接口不返回它
 *       ——手动触发是异步受理，等不到超时那一刻。</li>
 * </ul>
 * 两处**必须用通用段/补全**的地方（登记在《实现注记（T7）》）：
 * <ol>
 *   <li>{@code Add} 撞上已有 {@code job_code}：段内没有"编码重复"码（20026–20029 是预留空号，不回收），
 *       用通用段 10003 {@code DATA_CONFLICT}（"数据冲突"的正牌语义，与 T5 对 {@code dictType/Add}
 *       复用 20003 同款先例）；</li>
 *   <li>字段级非法值（{@code timeoutSeconds ≤ 0}、{@code retryMax > 10}、参数键名疑似密钥）
 *       用通用段 10000 {@code PARAM_INVALID}：它们是"入参写错"，不是任务域的业务失败。</li>
 * </ol>
 *
 * <p><b>暂停是运行期状态</b>：{@code pause} 只取消内存里的 cron 调度（3.4.6 明写"不清 {@code enabled}"），
 * {@code job} 表没有 {@code paused} 列（DDL 冻结），因此**重启后暂停会失效**（按 {@code enabled} 重新排期）。
 * 要持久停用请用 {@code Up} 把 {@code enabled} 置 false（登记在《实现注记（T7）》）。
 */
@Service
public class JobAppService {

    private static final Logger log = LoggerFactory.getLogger(JobAppService.class);

    private static final int DEFAULT_TIMEOUT_SECONDS = 300;
    private static final int DEFAULT_RETRY_MAX = 3;
    private static final int DEFAULT_BACKOFF_SECONDS = 30;

    private final JobStore store;
    private final JobRunStore runs;
    private final JobHandlerRegistry handlers;
    private final JobDtoMapper dtoMapper;
    private final JobExecutor executor;
    private final JobSchedulerRegistrar registrar;
    private final JobLocker locker;
    private final ParamContextProvider contexts;
    private final IdGenerator idGenerator;

    public JobAppService(JobStore store, JobRunStore runs, JobHandlerRegistry handlers, JobDtoMapper dtoMapper,
            JobExecutor executor, JobSchedulerRegistrar registrar, JobLocker locker, ParamContextProvider contexts,
            IdGenerator idGenerator) {
        this.store = store;
        this.runs = runs;
        this.handlers = handlers;
        this.dtoMapper = dtoMapper;
        this.executor = executor;
        this.registrar = registrar;
        this.locker = locker;
        this.contexts = contexts;
        this.idGenerator = idGenerator;
    }

    // ---------------------------------------------------------------- 读

    /** 任务分页（管理页）；每行带 {@code scheduled}/{@code running} 两个运行期事实。 */
    public PageResult<JobDTO> pageJobs(JobQuery query) {
        IPage<Job> page = store.page(query);
        List<Job> rows = page.getRecords();
        Set<String> running = runningCodes(rows, Instant.now());
        List<JobDTO> records = rows.stream()
                .map(job -> toDto(job, running.contains(job.getJobCode())))
                .toList();
        return PageResult.from(page.getCurrent(), page.getSize(), page.getTotal(), records);
    }

    /** 任务详情；不存在抛 20020。 */
    public JobDTO jobByCode(String jobCode) {
        Job job = requireRow(jobCode);
        return toDto(job, isRunning(job));
    }

    /** 运行日志分页（默认 {@code start_time DESC}）。 */
    public PageResult<JobRunDTO> pageRuns(JobRunQuery query) {
        IPage<JobRun> page = runs.page(query);
        return PageResult.from(page.getCurrent(), page.getSize(), page.getTotal(),
                page.getRecords().stream().map(dtoMapper::toDto).toList());
    }

    /** 单条运行日志；不存在抛 20020。 */
    public JobRunDTO runById(long runId) {
        JobRun row = runs.rowById(runId);
        if (row == null) {
            throw new BusinessException(PlatformErrorCode.JOB_NOT_FOUND, "运行记录不存在：runId=" + runId);
        }
        return dtoMapper.toDto(row);
    }

    // ---------------------------------------------------------------- 写（REST 与 SchedulerApi 共用）

    /**
     * 新增任务（5.2 的 {@code job/Add}）。
     *
     * <p>{@code job_code} 已存在抛 10003（段内没有重复码，见类注释）；保存即校验 cron（20022）与
     * 处理点（20023），并拒绝疑似密钥的参数键（4.7）。
     */
    @Transactional
    public JobDTO addJob(JobSaveCmd cmd) {
        String jobCode = cmd.jobCode().trim();
        if (store.rowByCode(jobCode) != null) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "任务编码已存在：" + jobCode);
        }
        requireValidCron(cmd.cron());
        requireRegisteredHandler(cmd.handlerCode());
        requireSafeParams(cmd.params());
        long operator = contexts.current().userId();
        Job job = new Job();
        job.setId(idGenerator.nextId());
        job.setJobCode(jobCode);
        job.setJobName(cmd.jobName().trim());
        job.setHandlerCode(cmd.handlerCode().trim());
        job.setCron(cmd.cron().trim());
        job.setParamsJson(dtoMapper.encodeParams(cmd.params()));
        job.setEnabled(cmd.enabled() == null || cmd.enabled());
        job.setTimeoutSeconds(requireTimeout(cmd.timeoutSeconds(), DEFAULT_TIMEOUT_SECONDS));
        job.setRetryMax(requireRetryMax(cmd.retryMax(), DEFAULT_RETRY_MAX));
        job.setBackoffSeconds(requireBackoff(cmd.backoffSeconds(), DEFAULT_BACKOFF_SECONDS));
        job.setAllowConcurrent(cmd.allowConcurrent() != null && cmd.allowConcurrent());
        job.setRemark(cmd.remark());
        job.setCreatedAt(Instant.now());
        job.setCreatedBy(operator);
        job.setVersion(0);
        job.setDeleted(false);
        store.insert(job);
        reschedule(job);
        log.info("任务已新增：jobCode={} handler={} cron={} enabled={}", jobCode, job.getHandlerCode(), job.getCron(),
                job.isEnabled());
        return toDto(job, false);
    }

    /** 更新任务（按 {@code jobCode} 定位，编码不可改；乐观锁过期 10003）。 */
    @Transactional
    public JobDTO upJob(JobSaveCmd cmd) {
        if (cmd.version() == null) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "更新任务必须带 version（乐观锁，P1 册 5.3）");
        }
        requireValidCron(cmd.cron());
        requireRegisteredHandler(cmd.handlerCode());
        requireSafeParams(cmd.params());
        Job existing = requireRow(cmd.jobCode());
        long operator = contexts.current().userId();
        existing.setJobName(cmd.jobName().trim());
        existing.setHandlerCode(cmd.handlerCode().trim());
        existing.setCron(cmd.cron().trim());
        existing.setParamsJson(dtoMapper.encodeParams(cmd.params()));
        if (cmd.enabled() != null) {
            existing.setEnabled(cmd.enabled());
        }
        existing.setTimeoutSeconds(requireTimeout(cmd.timeoutSeconds(), existing.getTimeoutSeconds()));
        existing.setRetryMax(requireRetryMax(cmd.retryMax(), existing.getRetryMax()));
        existing.setBackoffSeconds(requireBackoff(cmd.backoffSeconds(), existing.getBackoffSeconds()));
        existing.setAllowConcurrent(cmd.allowConcurrent() != null && cmd.allowConcurrent());
        existing.setRemark(cmd.remark());
        existing.setUpdatedAt(Instant.now());
        existing.setUpdatedBy(operator);
        existing.setVersion(cmd.version());
        if (store.updateById(existing) == 0) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "任务已被他人修改（version 过期）：" + existing.getJobCode());
        }
        reschedule(existing);
        log.info("任务已更新：jobCode={} enabled={} cron={}", existing.getJobCode(), existing.isEnabled(),
                existing.getCron());
        return toDto(existing, isRunning(existing));
    }

    /** 逻辑删除任务；正在执行抛 20021（5.2），版本过期 10003。 */
    @Transactional
    public void delJob(String jobCode, int version) {
        Job existing = requireRow(jobCode);
        if (isRunning(existing)) {
            throw new BusinessException(PlatformErrorCode.JOB_RUNNING, "任务正在执行，不能删除：" + jobCode);
        }
        if (existing.getVersion() == null || existing.getVersion() != version) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "任务已被他人修改（version 过期）：" + jobCode);
        }
        store.deleteById(existing);
        registrar.cancel(existing.getJobCode());
        log.info("任务已删除（逻辑删除）：jobCode={}", jobCode);
    }

    /**
     * 手动触发（5.2 的 {@code job/Run}）：立即返回 {@code runId}，执行在后台。
     *
     * <p>并发口径（3.4.6/3.4.7）：不允许并发时先用 {@code job_run} 判定"正在跑"（20021），再取分布式锁
     * 做**原子**互斥——两道一起用，是因为前者给得出可读的原因，后者挡得住"同一毫秒两个实例都判为没在跑"。
     * 允许并发时不取锁（锁是独占的），直接排一次执行。
     */
    public long trigger(String jobCode, Map<String, String> params) {
        Job job = requireRow(jobCode);
        if (!job.isEnabled()) {
            throw new BusinessException(PlatformErrorCode.JOB_DISABLED,
                    "任务已停用，不能手动触发：" + jobCode + "（要临时停 cron 但保留手动能力，用 pause）");
        }
        requireRegisteredHandler(job.getHandlerCode());
        requireSafeParams(params);
        if (job.isAllowConcurrent()) {
            return executor.submitNew(job, params, idGenerator.nextStr(), null);
        }
        if (isRunning(job)) {
            throw new BusinessException(PlatformErrorCode.JOB_RUNNING, "任务正在执行且未允许并发：" + jobCode);
        }
        Optional<JobLockHandle> lock = locker.tryLock(JobLockWindow.lockName(jobCode), JobLockWindow.lockAtMostFor(job));
        if (lock.isEmpty()) {
            throw new BusinessException(PlatformErrorCode.JOB_RUNNING,
                    "任务正在执行（执行令牌被其他实例持有）且未允许并发：" + jobCode);
        }
        try {
            return executor.submitNew(job, params, idGenerator.nextStr(), lock.get());
        } catch (RuntimeException e) {
            lock.get().release();
            throw e;
        }
    }

    /**
     * 暂停 cron 调度（5.2 的 {@code job/Pause}）：保留 {@code enabled} 与手动触发能力（3.4.6）。
     *
     * <p>运行期状态：不落库（{@code job} 表没有 paused 列，DDL 冻结），重启后按 {@code enabled} 重新排期。
     */
    @Transactional
    public void pause(String jobCode) {
        requireRow(jobCode);
        registrar.cancel(jobCode);
        log.info("任务已暂停（保留 enabled 与手动触发能力）：jobCode={}", jobCode);
    }

    /** 恢复 cron 调度（5.2 的 {@code job/Resume}）：cron 非法 20022、处理点未注册 20023、已停用 20024。 */
    @Transactional
    public void resume(String jobCode) {
        Job job = requireRow(jobCode);
        requireValidCron(job.getCron());
        requireRegisteredHandler(job.getHandlerCode());
        if (!job.isEnabled()) {
            throw new BusinessException(PlatformErrorCode.JOB_DISABLED,
                    "任务已停用，无法恢复调度（先 Up 把 enabled 置 true）：" + jobCode);
        }
        if (!registrar.isStarted()) {
            log.warn("调度器未启动（无库/无锁或尚在启动期），resume 只做了校验：jobCode={}", jobCode);
            return;
        }
        if (!registrar.schedule(job)) {
            throw new BusinessException(PlatformErrorCode.JOB_HANDLER_NOT_REGISTERED,
                    "任务无法排期（cron 非法或处理点未注册）：" + jobCode);
        }
    }

    /**
     * 重试一次已结束的运行（5.2 的 {@code jobRun/Retry}）：复用**同一条** {@code job_run} 行
     * （{@code attempt + 1}），因此返回的 {@code runId} 与传入的一致。
     *
     * <p>顺序是刻意的：先取执行令牌 → 再把行置回 {@code RETRYING} → 原子认领 → 提交执行。反过来的话，
     * "取锁失败"会留下一条 {@code RETRYING} 行让重投扫描器在 30 秒内把它跑掉——接口报错但任务真跑了，
     * 那是比报错更糟的语义。
     */
    public long retryRun(long runId) {
        JobRun row = runs.rowById(runId);
        if (row == null) {
            throw new BusinessException(PlatformErrorCode.JOB_NOT_FOUND, "运行记录不存在：runId=" + runId);
        }
        Job job = requireRow(row.getJobCode());
        if (!job.isEnabled()) {
            throw new BusinessException(PlatformErrorCode.JOB_DISABLED, "任务已停用，不能重试：" + job.getJobCode());
        }
        requireRegisteredHandler(job.getHandlerCode());
        if (JobRunStatus.RUNNING.name().equals(row.getStatus())) {
            throw new BusinessException(PlatformErrorCode.JOB_RUNNING, "该次运行仍在执行，不能重试：runId=" + runId);
        }
        Optional<JobLockHandle> lock = job.isAllowConcurrent()
                ? Optional.empty()
                : locker.tryLock(JobLockWindow.lockName(job.getJobCode()), JobLockWindow.lockAtMostFor(job));
        if (!job.isAllowConcurrent() && lock.isEmpty()) {
            throw new BusinessException(PlatformErrorCode.JOB_RUNNING,
                    "任务正在执行（执行令牌被其他实例持有）且未允许并发：" + job.getJobCode());
        }
        try {
            if (!runs.markRetrying(runId)) {
                throw new BusinessException(PlatformErrorCode.JOB_RUNNING,
                        "该次运行状态已变化（可能正在执行），不能重试：runId=" + runId);
            }
            if (!runs.claimRetry(runId, executor.nodeId())) {
                throw new BusinessException(PlatformErrorCode.JOB_RUNNING,
                        "重试未认领成功（可能已被其他实例认领）：runId=" + runId);
            }
            JobRun claimed = runs.rowById(runId);
            executor.submitClaimed(job, claimed, idGenerator.nextStr(), lock.orElse(null));
            log.info("运行已手动重试：jobCode={} runId={} attempt={}", job.getJobCode(), runId,
                    claimed == null || claimed.getAttempt() == null ? 0 : claimed.getAttempt());
            return runId;
        } catch (RuntimeException e) {
            lock.ifPresent(JobLockHandle::release);
            throw e;
        }
    }

    /**
     * 跨模块注册任务元数据（5.4 的 {@code SchedulerApi.register}）：{@code jobCode} 已存在时**覆盖元数据**
     * （{@code enabled} 与用户存过的参数保持不动——代码无权把运维停用的任务悄悄启用回来）。
     */
    @Transactional
    public JobDTO register(JobDefinition definition) {
        String jobCode = definition.jobCode() == null ? "" : definition.jobCode().trim();
        if (jobCode.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "register 的 jobCode 不能为空");
        }
        requireValidCron(definition.cron());
        requireRegisteredHandler(definition.handlerCode());
        long operator = contexts.current().userId();
        Job existing = store.rowByCode(jobCode);
        if (existing == null) {
            Job job = new Job();
            job.setId(idGenerator.nextId());
            job.setJobCode(jobCode);
            job.setJobName(definition.jobName().trim());
            job.setHandlerCode(definition.handlerCode().trim());
            job.setCron(definition.cron().trim());
            job.setEnabled(definition.defaultEnabled() == null || definition.defaultEnabled());
            job.setTimeoutSeconds(requireTimeout(definition.timeoutSeconds(), DEFAULT_TIMEOUT_SECONDS));
            job.setRetryMax(requireRetryMax(definition.retryMax(), DEFAULT_RETRY_MAX));
            job.setBackoffSeconds(requireBackoff(definition.backoffSeconds(), DEFAULT_BACKOFF_SECONDS));
            job.setAllowConcurrent(definition.allowConcurrent() != null && definition.allowConcurrent());
            job.setRemark(definition.description());
            job.setCreatedAt(Instant.now());
            job.setCreatedBy(operator);
            job.setVersion(0);
            job.setDeleted(false);
            store.insert(job);
            reschedule(job);
            log.info("任务已注册（新增）：jobCode={} handler={} cron={} enabled={}", jobCode, job.getHandlerCode(),
                    job.getCron(), job.isEnabled());
            return toDto(job, false);
        }
        existing.setJobName(definition.jobName().trim());
        existing.setHandlerCode(definition.handlerCode().trim());
        existing.setCron(definition.cron().trim());
        existing.setTimeoutSeconds(requireTimeout(definition.timeoutSeconds(), existing.getTimeoutSeconds()));
        existing.setRetryMax(requireRetryMax(definition.retryMax(), existing.getRetryMax()));
        existing.setBackoffSeconds(requireBackoff(definition.backoffSeconds(), existing.getBackoffSeconds()));
        if (definition.allowConcurrent() != null) {
            existing.setAllowConcurrent(definition.allowConcurrent());
        }
        if (definition.description() != null) {
            existing.setRemark(definition.description());
        }
        existing.setUpdatedAt(Instant.now());
        existing.setUpdatedBy(operator);
        store.updateById(existing);
        reschedule(existing);
        log.info("任务已注册（覆盖元数据，enabled/参数保持不动）：jobCode={} cron={} enabled={}", jobCode,
                existing.getCron(), existing.isEnabled());
        return toDto(existing, isRunning(existing));
    }

    // ---------------------------------------------------------------- 内部

    private JobDTO toDto(Job job, boolean running) {
        boolean scheduled = registrar.isScheduled(job.getJobCode());
        Instant next = scheduled ? JobCron.nextRun(job.getCron(), Instant.now()).orElse(null) : null;
        return dtoMapper.toDto(job, scheduled, running, next);
    }

    /** 任务是否存在未结束的运行（跨实例；僵尸行不算，3.4.7）。 */
    private boolean isRunning(Job job) {
        return runs.hasRunning(job.getJobCode(), JobLockWindow.staleBefore(job, Instant.now()));
    }

    /**
     * 一页任务里"哪些正在运行"：一次查询取回所有相关的 {@code RUNNING} 行，
     * 再按**每个任务各自的**僵尸判定线过滤（不同任务的超时不同，判定线也不同）。
     */
    private Set<String> runningCodes(List<Job> rows, Instant now) {
        if (rows.isEmpty()) {
            return Set.of();
        }
        Instant widest = rows.stream()
                .map(job -> JobLockWindow.staleBefore(job, now))
                .min(Comparator.naturalOrder())
                .orElse(now);
        Map<String, Instant> latest = new HashMap<>();
        for (JobRun run : runs.runningRows(widest)) {
            latest.merge(run.getJobCode(), run.getStartTime(),
                    (left, right) -> left.isAfter(right) ? left : right);
        }
        Set<String> running = new HashSet<>();
        for (Job job : rows) {
            Instant start = latest.get(job.getJobCode());
            if (start != null && !start.isBefore(JobLockWindow.staleBefore(job, now))) {
                running.add(job.getJobCode());
            }
        }
        return running;
    }

    /** 排期（启用）或取消（停用）：{@code Add/Up/register} 后的唯一切口。 */
    private void reschedule(Job job) {
        if (!job.isEnabled()) {
            registrar.cancel(job.getJobCode());
            return;
        }
        registrar.schedule(job);
    }

    private Job requireRow(String jobCode) {
        if (jobCode == null || jobCode.isBlank()) {
            throw new BusinessException(PlatformErrorCode.JOB_NOT_FOUND, "jobCode 不能为空");
        }
        Job job = store.rowByCode(jobCode.trim());
        if (job == null) {
            throw new BusinessException(PlatformErrorCode.JOB_NOT_FOUND, "任务不存在：" + jobCode);
        }
        return job;
    }

    private static void requireValidCron(String cron) {
        if (!JobCron.isValid(cron)) {
            throw new BusinessException(PlatformErrorCode.JOB_CRON_INVALID,
                    "Cron 表达式非法：" + cron + "（Spring 6 段，不支持 Quartz 的 ?/L/W/#，3.4.6）");
        }
    }

    private void requireRegisteredHandler(String handlerCode) {
        handlers.require(handlerCode);
    }

    /** 4.7：{@code params_json} 拒绝含 password/secret/token/key 的键名。 */
    private static void requireSafeParams(Map<String, String> params) {
        Optional<String> secretKey = JobParamRules.firstSecretKey(params);
        if (secretKey.isPresent()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "任务参数键名疑似密钥，拒绝保存（4.7）：" + secretKey.get() + "（任务参数会进管理页与运维导出）");
        }
    }

    private static int requireTimeout(Integer value, int fallback) {
        int actual = value == null ? fallback : value;
        if (actual <= 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "timeoutSeconds 必须大于 0：" + actual);
        }
        return actual;
    }

    private static int requireRetryMax(Integer value, int fallback) {
        int actual = value == null ? fallback : value;
        if (actual < 0 || actual > JobRetryPolicy.MAX_RETRY_LIMIT) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "retryMax 必须在 0–" + JobRetryPolicy.MAX_RETRY_LIMIT + " 之间：" + actual);
        }
        return actual;
    }

    private static int requireBackoff(Integer value, int fallback) {
        int actual = value == null ? fallback : value;
        if (actual < 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "backoffSeconds 不能为负：" + actual);
        }
        return actual;
    }
}
