package com.eaio.platform.infrastructure.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import javax.sql.DataSource;

import com.eaio.platform.domain.job.JobLockHandle;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 分布式锁（P1 册 3.4.2 的 {@code ShedLockConfig} 落地形态）：ShedLock 7.10.1 的
 * {@link JdbcTemplateLockProvider} + {@code usingDbTime()}，锁表 {@code eaio_platform.shedlock}。
 *
 * <p><b>与册面的差异（登记在《实现注记（T7）》）</b>：
 * <ol>
 *   <li><b>不启用 {@code @EnableSchedulerLock}</b>：那套注解 AOP 服务的是"用 {@code @Scheduled} 声明的
 *       静态任务"，而本票的任务是**从 {@code job} 表动态建 CronTrigger**（3.4.2 的
 *       {@code JobSchedulerRegistrar}），没有注解可挂。ShedLock 对这种情况给的是程序化用法
 *       {@code LockProvider.lock(LockConfiguration)}，本类就是它的封装。**没有注解不等于漏了**——
 *       锁仍然真实存在、仍然进 {@code shedlock} 表、仍然跨实例互斥（由 {@code SchedulerLockIT} 证明）；</li>
 *   <li><b>不是一个 {@code LockProvider} Bean，而是持有 provider 的组件</b>：把 provider 直接做成 Bean
 *       就必须面对"没有 DataSource 时怎么办"——{@code @Bean} 返回 {@code null}（NullBean）与用户配置类上的
 *       {@code @ConditionalOnBean}（在用户配置里按 bean 顺序判定，不可靠）都会把"无库也能启动"
 *       （P0 冻结口径）变成"启动结果取决于装配顺序"。这里的判定只有一句：有 DataSource 才建 provider，
 *       没有就是 {@link #available()} = false，调度整体停用（**不是**降级成进程内锁——那会让两个实例
 *       同时执行同一任务，比"不调度"危险得多）。</li>
 * </ol>
 *
 * <p>{@code lockAtMostFor} 由调用方按任务算（{@link com.eaio.platform.domain.job.JobLockWindow}），
 * {@code lockAtLeastFor} 恒为 0：任务跑完就该立刻让别人接手，而不是为了"防抖"多占一会儿锁。
 */
@Component
public class JobLocker {

    /** 锁表（4.3.11；列名/类型是 provider 的契约，不可改）。 */
    public static final String TABLE = "eaio_platform.shedlock";

    private static final Logger log = LoggerFactory.getLogger(JobLocker.class);

    private final LockProvider provider;

    public JobLocker(ObjectProvider<DataSource> dataSources) {
        DataSource dataSource = dataSources.getIfAvailable();
        this.provider = dataSource == null ? null : new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .withTableName(TABLE)
                        .usingDbTime()
                        .build());
        if (this.provider == null) {
            log.warn("未配置 DataSource：任务分布式锁不可用，定时任务整体不调度（无库也能启动的既定口径）");
        } else {
            log.info("任务分布式锁就绪：表={} provider=ShedLock JdbcTemplateLockProvider（usingDbTime）", TABLE);
        }
    }

    /** 锁是否可用（无库时为 false；调度器据此整体停用，不当成"拿到了锁"）。 */
    public boolean available() {
        return provider != null;
    }

    /**
     * 试锁（**非阻塞**）：拿到就返回执行令牌，没拿到（别的实例在跑）返回空。
     *
     * <p>锁服务不可用（库抖动/网络断）时按"没拿到"处理并记 WARN：调度器下一轮会再试，
     * 而把异常抛给 cron 线程只会每 30 秒刷一条堆栈。手动触发路径不会走到这里——它在更早的
     * "读任务行"那一步就已经以 10500 明确失败了。
     */
    public Optional<JobLockHandle> tryLock(String name, Duration lockAtMostFor) {
        if (provider == null) {
            return Optional.empty();
        }
        try {
            LockConfiguration configuration = new LockConfiguration(Instant.now(), name, lockAtMostFor, Duration.ZERO);
            return provider.lock(configuration).map(ShedLockHandle::new);
        } catch (RuntimeException e) {
            log.warn("取锁失败（按未取到处理，下一轮重试）：name={} 原因={}", name, e.getMessage());
            return Optional.empty();
        }
    }

    /** 令牌实现：把 ShedLock 的 {@link SimpleLock} 关在基础设施层（application 只见 {@code JobLockHandle}）。 */
    private static final class ShedLockHandle implements JobLockHandle {

        private final SimpleLock lock;

        private ShedLockHandle(SimpleLock lock) {
            this.lock = lock;
        }

        @Override
        public void release() {
            try {
                lock.unlock();
            } catch (RuntimeException e) {
                // 放锁失败不影响本次执行结果；锁到期会自然失效（provider 内部已重试 10 次）
                log.warn("释放任务锁失败（锁到期会自然失效）：{}", e.getMessage());
            }
        }
    }
}
