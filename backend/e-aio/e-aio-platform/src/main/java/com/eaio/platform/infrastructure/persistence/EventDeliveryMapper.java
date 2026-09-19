package com.eaio.platform.infrastructure.persistence;

import java.time.Instant;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eaio.platform.domain.event.EventDelivery;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * {@code eaio_platform.event_delivery} 的 Mapper（P1 册 4.3.14）。
 *
 * <p>四次状态流转全部是**条件 UPDATE**：{@code WHERE event_id = ? AND status = ?} 的受影响行数就是
 * 并发正确性的凭据——两个实例同时扫到同一条（或"人工重放"与"重投扫描"同时动手）时，只有一个能把状态
 * 推进一步（受影响行数 1 vs 0），不依赖先读后写。这与 {@code JobRunMapper#claimRetry} 是同一手法。
 *
 * <p>{@code version = version + 1} 在 SQL 里自增：显式 UPDATE 不走 MyBatis-Plus 的乐观锁拦截器，
 * 版本列若不在 SQL 里维护就会永远停在 0（那时 {@code @Version} 只是摆设，谁也不知道改过几次）。
 */
@Mapper
public interface EventDeliveryMapper extends BaseMapper<EventDelivery> {

    /**
     * 投递成功：{@code RETRYING → DONE} + 写 {@code finish_time}、清掉下次重投时刻。
     *
     * <p>{@code last_error} **不清**：它是"这条记录为什么被重投过"的唯一线索，成功之后仍然有用
     * （排障时看得到第几次失败、失败在谁身上）。
     *
     * @return 受影响行数：1 = 本次投递被记为成功，0 = 行已被别的流程改写（重复投递/已死信）
     */
    @Update("UPDATE eaio_platform.event_delivery SET status = 'DONE', finish_time = now(), "
            + "next_retry_time = NULL, updated_at = now(), version = version + 1 "
            + "WHERE event_id = #{eventId} AND status = 'RETRYING'")
    int markDone(@Param("eventId") String eventId);

    /**
     * 投递失败但仍可重投：{@code attempt_count} 推进一步 + 退避到期时刻 + 错误摘要。
     *
     * <p>只改仍为 {@code RETRYING} 的行：已 {@code DONE}/{@code DEAD} 的行不会被一次迟到的失败回调
     * 重新拉回重投队列。
     */
    @Update("UPDATE eaio_platform.event_delivery SET status = 'RETRYING', attempt_count = #{attempt}, "
            + "next_retry_time = #{nextRetryTime}, last_error = #{error}, updated_at = now(), "
            + "version = version + 1 WHERE event_id = #{eventId} AND status = 'RETRYING'")
    int markRetry(@Param("eventId") String eventId, @Param("attempt") int attempt,
            @Param("nextRetryTime") Instant nextRetryTime, @Param("error") String error);

    /** 超过 {@code max_attempt}：{@code RETRYING → DEAD}（死信，终态；只能人工重放改回 {@code RETRYING}）。 */
    @Update("UPDATE eaio_platform.event_delivery SET status = 'DEAD', attempt_count = #{attempt}, "
            + "next_retry_time = NULL, last_error = #{error}, updated_at = now(), version = version + 1 "
            + "WHERE event_id = #{eventId} AND status = 'RETRYING'")
    int markDead(@Param("eventId") String eventId, @Param("attempt") int attempt, @Param("error") String error);

    /**
     * 人工重放：{@code DEAD → RETRYING}、{@code attempt_count = 1}、立即到期，{@code last_error}
     * **追加**一次（保留历史——重放是人的动作，抹掉"为什么进死信"会让下一次排障从零开始）。
     *
     * <p>{@code WHERE status = 'DEAD'} 是重放资格的唯一判定：{@code DONE}（已投递成功）与
     * {@code RETRYING}（在重投队列里）都不允许重放，那正是 20071 的语义；并发两次重放只有一次生效。
     *
     * @return 受影响行数：1 = 重放成功，0 = 行不是 {@code DEAD}（调用方据此报 20071）
     */
    @Update("UPDATE eaio_platform.event_delivery SET status = 'RETRYING', attempt_count = 1, "
            + "next_retry_time = now(), finish_time = NULL, "
            + "last_error = CASE WHEN last_error IS NULL OR last_error = '' THEN #{note} "
            + "ELSE last_error || E'\\n' || #{note} END, "
            + "updated_at = now(), updated_by = #{operator}, version = version + 1 "
            + "WHERE event_id = #{eventId} AND status = 'DEAD'")
    int markReplay(@Param("eventId") String eventId, @Param("note") String note,
            @Param("operator") Long operator);

    /**
     * 按保留天数分批物理删除 {@code DONE} 的行（{@code DEAD} 永不自动删，3.9.3）。
     *
     * <p>删除线用 {@code coalesce(finish_time, created_at)}：正常路径下 {@code DONE} 必有
     * {@code finish_time}，但手工改库/历史数据可能留下空值——按 {@code finish_time} 单独判定会让这类行
     * **永远删不掉**（静默堆积），兜底到 {@code created_at} 才不会在表里留不可回收的行。
     *
     * @return 本批删除行数（调用方循环到不足一批为止）
     */
    @Delete("DELETE FROM eaio_platform.event_delivery WHERE id IN "
            + "(SELECT id FROM eaio_platform.event_delivery WHERE status = 'DONE' "
            + "AND coalesce(finish_time, created_at) < #{before} LIMIT #{batchSize})")
    int deleteDoneBatchBefore(@Param("before") Instant before, @Param("batchSize") int batchSize);
}
