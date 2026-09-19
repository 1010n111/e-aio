package com.eaio.platform.infrastructure.persistence;

import java.time.Instant;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eaio.platform.domain.job.JobRun;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * {@code eaio_platform.job_run} 的 Mapper（P1 册 4.3.10）。
 *
 * <p>四条自定义 SQL 各自承担一件"必须由数据库原子完成"的事，放进 Java 里做就会有竞态：
 * <ol>
 *   <li>{@link #claimRetry}：重投认领。{@code WHERE status = 'RETRYING'} 是**认领凭据**——两个实例
 *       同时扫到同一条 RETRYING 行时，只有一个能把状态改成 RUNNING（受影响行数 1 vs 0），
 *       这就是 6.2 断言"同一 {@code job_run} 重试只被一个实例认领"的落点，不依赖先读后写；</li>
 *   <li>{@link #markRetrying}：手动"重试"一次已结束的运行（5.2 的 {@code jobRun/Retry}）。
 *       {@code WHERE status <> 'RUNNING'} 让"正在跑的那一行"无法被重试（那是 20021）；</li>
 *   <li>{@link #markStaleRunningAsFailed}：实例宕机留下的僵尸 {@code RUNNING} 行（3.4.7：
 *       超过 {@code lockAtMostFor × 2} 仍是 RUNNING → 置 FAILED，否则任务状态永远显示"运行中"）；</li>
 *   <li>{@link #deleteBatchBefore}：按保留天数**分批**删除（3.4.4：1000 行/批，避免一次 DELETE 撑爆 WAL）。</li>
 * </ol>
 */
@Mapper
public interface JobRunMapper extends BaseMapper<JobRun> {

    /**
     * 认领一条到期重投并进入 RUNNING（{@code trigger_type} 改写为 RETRY、清掉上一次的终态列与错误信息）。
     *
     * @return 受影响行数：1 = 本实例认领成功，0 = 已被别的实例（或别的扫描周期）认领
     */
    @Update("UPDATE eaio_platform.job_run SET status = 'RUNNING', trigger_type = 'RETRY', start_time = now(), "
            + "end_time = NULL, duration_ms = NULL, next_retry_time = NULL, error_message = NULL, "
            + "node_id = #{nodeId} WHERE id = #{runId} AND status = 'RETRYING'")
    int claimRetry(@Param("runId") long runId, @Param("nodeId") String nodeId);

    /**
     * 手动把一条已结束的运行置回 {@code RETRYING}（{@code attempt} +1、立即到期）。
     *
     * <p>{@code WHERE status <> 'RUNNING'}：正在执行的行不能被"重试"——那不是重试，是并发执行。
     */
    @Update("UPDATE eaio_platform.job_run SET status = 'RETRYING', next_retry_time = now(), "
            + "attempt = attempt + 1 WHERE id = #{runId} AND status <> 'RUNNING'")
    int markRetrying(@Param("runId") long runId);

    /** 僵尸 RUNNING 行置 FAILED（{@code start_time} 早于 {@code staleBefore} 才算）。 */
    @Update("UPDATE eaio_platform.job_run SET status = 'FAILED', end_time = now(), "
            + "duration_ms = (EXTRACT(EPOCH FROM (now() - start_time)) * 1000)::INT, error_message = #{message} "
            + "WHERE status = 'RUNNING' AND start_time < #{staleBefore}")
    int markStaleRunningAsFailed(@Param("staleBefore") Instant staleBefore, @Param("message") String message);

    /** 分批物理删除早于 {@code before} 的运行日志；返回本批删除行数（调用方循环到不足一批为止）。 */
    @Delete("DELETE FROM eaio_platform.job_run WHERE id IN "
            + "(SELECT id FROM eaio_platform.job_run WHERE start_time < #{before} LIMIT #{batchSize})")
    int deleteBatchBefore(@Param("before") Instant before, @Param("batchSize") int batchSize);
}
