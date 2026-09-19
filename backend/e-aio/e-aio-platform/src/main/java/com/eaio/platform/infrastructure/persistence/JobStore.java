package com.eaio.platform.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eaio.common.exception.SystemException;
import com.eaio.platform.api.dto.JobQuery;
import com.eaio.platform.domain.job.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 定时任务注册表的持久化门面（P1 册 3.4.2 的分层：application 不直接见 Mapper）。
 *
 * <p><b>为什么 Mapper 用 {@link ObjectProvider} 注入</b>：P0 冻结了"无数据库也能启动"，缺 Mapper 时
 * 必须在**真去读写时**以 {@code SystemException} 明确失败（10500），而不是装配期炸掉或静默返回空值
 * ——与 {@code ParamStore}/{@code DictStore} 同一口径。调度器据此判断"无库 → 整体不调度"。
 *
 * <p>查询一律带 {@code deleted = false}（{@code @TableLogic} 自动追加）；排序字段走**白名单**。
 */
@Component
public class JobStore {

    private static final Logger log = LoggerFactory.getLogger(JobStore.class);

    /** 排序列白名单：DTO 字段名 → 实体列引用。 */
    private static final Map<String, SFunction<Job, ?>> ORDER_COLUMNS = Map.of(
            "jobCode", Job::getJobCode,
            "jobName", Job::getJobName,
            "lastRunTime", Job::getLastRunTime,
            "nextRunTime", Job::getNextRunTime,
            "updatedAt", Job::getUpdatedAt,
            "id", Job::getId);

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final ObjectProvider<JobMapper> mappers;

    public JobStore(ObjectProvider<JobMapper> mappers) {
        this.mappers = mappers;
    }

    /** 数据库是否可用（无库启动时调度整体停用，而不是启动失败）。 */
    public boolean available() {
        return mappers.getIfAvailable() != null;
    }

    /** 按编码取任务（未找到返回 {@code null}）。 */
    public Job rowByCode(String jobCode) {
        return mapper().selectOne(new LambdaQueryWrapper<Job>().eq(Job::getJobCode, jobCode));
    }

    /** 全部未删除任务（启动期建调度 + 日志清理任务枚举僵尸判定线用）。 */
    public List<Job> all() {
        return mapper().selectList(new LambdaQueryWrapper<Job>().orderByAsc(Job::getJobCode));
    }

    /** 分页（管理页）。 */
    public IPage<Job> page(JobQuery query) {
        JobQuery actual = query == null
                ? new JobQuery(null, null, null, null, null, null)
                : query;
        LambdaQueryWrapper<Job> wrapper = new LambdaQueryWrapper<>();
        if (actual.jobCode() != null && !actual.jobCode().isBlank()) {
            wrapper.like(Job::getJobCode, actual.jobCode().trim());
        }
        if (actual.enabled() != null) {
            wrapper.eq(Job::isEnabled, actual.enabled());
        }
        if (!applyOrder(wrapper, ORDER_COLUMNS, actual.orderBy(), actual.orderDir())) {
            wrapper.orderByAsc(Job::getJobCode);
        }
        return mapper().selectPage(new Page<>(pageNum(actual.pageNum()), pageSize(actual.pageSize())), wrapper);
    }

    /** 新增（受影响行数，正常为 1）。 */
    public int insert(Job job) {
        return mapper().insert(job);
    }

    /** 按主键更新（{@code @Version} 乐观锁：版本不匹配返回 0，由调用方转 10003）。 */
    public int updateById(Job job) {
        return mapper().updateById(job);
    }

    /** 逻辑删除（{@code @TableLogic}：写 {@code deleted = true}）。 */
    public int deleteById(Job job) {
        return mapper().deleteById(job);
    }

    /** 回写"最后一次执行"的事实（{@code last_run_time}/{@code last_status}）。 */
    public int updateLastRun(String jobCode, Instant lastRunTime, String lastStatus) {
        return mapper().updateLastRun(jobCode, lastRunTime, lastStatus);
    }

    /** 只回写展示用的下次触发时刻。 */
    public int updateNextRunTime(String jobCode, Instant nextRunTime) {
        return mapper().updateNextRunTime(jobCode, nextRunTime);
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

    private JobMapper mapper() {
        JobMapper mapper = mappers.getIfAvailable();
        if (mapper == null) {
            throw new SystemException("定时任务不可用：未配置数据库（无 DataSource/JobMapper）。"
                    + "任务注册表必须连库，不能静默返回空列表");
        }
        return mapper;
    }
}
