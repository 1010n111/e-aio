package com.eaio.platform.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eaio.common.exception.SystemException;
import com.eaio.platform.api.dto.JobRunQuery;
import com.eaio.platform.domain.job.JobRun;
import com.eaio.platform.domain.job.JobRunStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 任务运行日志的持久化门面（P1 册 4.3.10）。注入口径同 {@link JobStore}（无库时真去用时才失败）。
 *
 * <p>三件事刻意交给 Mapper 的原子 SQL（见 {@link JobRunMapper} 的类注释）：重投认领、
 * 僵尸 RUNNING 置 FAILED、分批删除。
 */
@Component
public class JobRunStore {

    private static final Logger log = LoggerFactory.getLogger(JobRunStore.class);

    /** 排序列白名单：DTO 字段名 → 实体列引用。 */
    private static final Map<String, SFunction<JobRun, ?>> ORDER_COLUMNS = Map.of(
            "jobCode", JobRun::getJobCode,
            "status", JobRun::getStatus,
            "startTime", JobRun::getStartTime,
            "endTime", JobRun::getEndTime,
            "durationMs", JobRun::getDurationMs,
            "id", JobRun::getId);

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final ObjectProvider<JobRunMapper> mappers;

    public JobRunStore(ObjectProvider<JobRunMapper> mappers) {
        this.mappers = mappers;
    }

    /** 数据库是否可用。 */
    public boolean available() {
        return mappers.getIfAvailable() != null;
    }

    /** 新增一条运行记录（{@code RUNNING}）。 */
    public int insert(JobRun run) {
        return mapper().insert(run);
    }

    /** 按 ID 取（未找到返回 {@code null}）。 */
    public JobRun rowById(long runId) {
        return mapper().selectById(runId);
    }

    /** 写终态（{@code null} 字段不覆盖：逻辑删除与终态列写定后不再回改）。 */
    public int updateById(JobRun run) {
        return mapper().updateById(run);
    }

    /** 分页（默认按 {@code start_time DESC}，5.3）。 */
    public IPage<JobRun> page(JobRunQuery query) {
        JobRunQuery actual = query == null
                ? new JobRunQuery(null, null, null, null, null, null, null, null)
                : query;
        LambdaQueryWrapper<JobRun> wrapper = new LambdaQueryWrapper<>();
        if (actual.jobCode() != null && !actual.jobCode().isBlank()) {
            wrapper.eq(JobRun::getJobCode, actual.jobCode().trim());
        }
        if (actual.status() != null && !actual.status().isBlank()) {
            wrapper.eq(JobRun::getStatus, actual.status().trim().toUpperCase(Locale.ROOT));
        }
        if (actual.startFrom() != null) {
            wrapper.ge(JobRun::getStartTime, actual.startFrom());
        }
        if (actual.startTo() != null) {
            wrapper.le(JobRun::getStartTime, actual.startTo());
        }
        if (!applyOrder(wrapper, ORDER_COLUMNS, actual.orderBy(), actual.orderDir())) {
            wrapper.orderByDesc(JobRun::getStartTime);
        }
        return mapper().selectPage(new Page<>(pageNum(actual.pageNum()), pageSize(actual.pageSize())), wrapper);
    }

    /** 到期待重投的行（{@code status = RETRYING} 且 {@code next_retry_time <= now}，按到期时刻升序）。 */
    public List<JobRun> dueRetries(int batchSize) {
        int size = batchSize < 1 ? 1 : batchSize;
        return mapper().selectPage(new Page<>(1, size, false), new LambdaQueryWrapper<JobRun>()
                .eq(JobRun::getStatus, JobRunStatus.RETRYING.name())
                .isNotNull(JobRun::getNextRetryTime)
                .le(JobRun::getNextRetryTime, Instant.now())
                .orderByAsc(JobRun::getNextRetryTime)
                .orderByAsc(JobRun::getId)).getRecords();
    }

    /** 认领一条重投（原子：{@code 1} = 本实例拿到，{@code 0} = 已被别处认领）。 */
    public boolean claimRetry(long runId, String nodeId) {
        return mapper().claimRetry(runId, nodeId) == 1;
    }

    /** 手动把一条已结束的运行置回 {@code RETRYING}（{@code attempt} +1、立即到期）；正在跑的行返回 false。 */
    public boolean markRetrying(long runId) {
        return mapper().markRetrying(runId) == 1;
    }

    /**
     * 与"运行中"判定有关的行（只要 {@code job_code}/{@code start_time} 两列，避免把日志正文整表捞出来）。
     *
     * <p>调用方按**每个任务各自的**僵尸判定线再过滤一次（各任务的 {@code lockAtMostFor} 不同，
     * 所以查询用最宽的 {@code since}，精确判定留给 {@code JobAppService}）。
     */
    public List<JobRun> runningRows(Instant since) {
        return mapper().selectList(new LambdaQueryWrapper<JobRun>()
                .select(JobRun::getJobCode, JobRun::getStartTime)
                .eq(JobRun::getStatus, JobRunStatus.RUNNING.name())
                .ge(JobRun::getStartTime, since));
    }

    /** 该任务是否存在"未结束且不算僵尸"的运行（跨实例判定并发，3.4.6/3.4.7）。 */
    public boolean hasRunning(String jobCode, Instant staleBefore) {
        return mapper().selectCount(new LambdaQueryWrapper<JobRun>()
                .eq(JobRun::getJobCode, jobCode)
                .eq(JobRun::getStatus, JobRunStatus.RUNNING.name())
                .ge(JobRun::getStartTime, staleBefore)) > 0;
    }

    /** 僵尸 {@code RUNNING} 行置 FAILED，返回受影响行数（3.4.7）。 */
    public int markStaleRunningAsFailed(Instant staleBefore, String message) {
        return mapper().markStaleRunningAsFailed(staleBefore, message);
    }

    /** 分批物理删除早于 {@code before} 的行，返回本批删除行数（3.4.4：调用方循环到不足一批为止）。 */
    public int deleteBatchBefore(Instant before, int batchSize) {
        return mapper().deleteBatchBefore(before, batchSize);
    }

    // ---------------------------------------------------------------- 内部

    private static <T> boolean applyOrder(LambdaQueryWrapper<T> wrapper, Map<String, SFunction<T, ?>> columns,
            String orderBy, String orderDir) {
        SFunction<T, ?> column = columns.get(orderBy == null ? "" : orderBy.trim());
        if (column == null) {
            if (orderBy != null && !orderBy.isBlank()) {
                log.warn("忽略非白名单排序列 orderBy={}（登记白名单：{}）", orderBy, columns.keySet());
            }
            return false;
        }
        boolean asc = !"desc".equalsIgnoreCase(orderDir == null ? "" : orderDir.trim());
        wrapper.orderBy(true, asc, column);
        return true;
    }

    private static long pageNum(Integer requested) {
        return requested == null || requested < 1 ? 1L : requested;
    }

    private static long pageSize(Integer requested) {
        if (requested == null) {
            return DEFAULT_PAGE_SIZE;
        }
        return requested < 1 || requested > MAX_PAGE_SIZE ? DEFAULT_PAGE_SIZE : requested;
    }

    private JobRunMapper mapper() {
        JobRunMapper mapper = mappers.getIfAvailable();
        if (mapper == null) {
            throw new SystemException("任务运行日志不可用：未配置数据库（无 DataSource/JobRunMapper）");
        }
        return mapper;
    }
}
