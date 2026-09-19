package com.eaio.platform.infrastructure.persistence;

import java.time.Instant;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eaio.platform.domain.job.Job;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * {@code eaio_platform.job} 的 Mapper（P1 册 3.4.2：{@code infrastructure/persistence}）。
 *
 * <p>{@code @Mapper} 即可（同 {@code DictTypeMapper}：starter 的扫描以启动类所在包 {@code com.eaio} 为根）。
 *
 * <p>下面两条自定义 SQL 都是"**运行事实回写**"：它们由框架自己执行（不是用户在改任务定义），因此
 * **不能**经过 {@code @Version} 乐观锁——否则"任务跑完了"会因为用户同时改了一下 cron 而写不进去，
 * 表现为 {@code last_status} 永远停在旧值。自定义 SQL 天然绕过乐观锁拦截器，这是刻意的。
 */
@Mapper
public interface JobMapper extends BaseMapper<Job> {

    /** 回写"最后一次执行"的事实（{@code job_run} 的终态写定之后调用）。 */
    @Update("UPDATE eaio_platform.job SET last_run_time = #{lastRunTime}, last_status = #{lastStatus}, "
            + "updated_at = now() WHERE job_code = #{jobCode} AND deleted = false")
    int updateLastRun(@Param("jobCode") String jobCode, @Param("lastRunTime") Instant lastRunTime,
            @Param("lastStatus") String lastStatus);

    /**
     * 回写展示用的下次触发时刻（{@code next_run_time} 是**展示列**，不参与调度决策，4.3.9）。
     *
     * <p>传 {@code null} 表示"没有下次"（暂停/停用），此时列被清空。
     */
    @Update("UPDATE eaio_platform.job SET next_run_time = #{nextRunTime}, updated_at = now() "
            + "WHERE job_code = #{jobCode} AND deleted = false")
    int updateNextRunTime(@Param("jobCode") String jobCode, @Param("nextRunTime") Instant nextRunTime);
}
