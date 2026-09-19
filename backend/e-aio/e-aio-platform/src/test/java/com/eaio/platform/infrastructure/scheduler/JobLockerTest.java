package com.eaio.platform.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * {@link JobLocker} 的单测：**无 DataSource 时锁不可用**（P0 冻结的"无库也能启动"口径）。
 *
 * <p>这条断言的价值不在"少建一个 bean"，而在"绝不用进程内锁假装互斥"：两个实例各持一把进程内锁 = 同一
 * 任务被执行两次，比"不调度"危险得多。有 DataSource 时只断言 provider 建起来了（真抢锁在
 * {@code SchedulerLockIT} 里用真实的 PostgreSQL 证明）。
 *
 * <p>用 {@link DefaultListableBeanFactory} 手工注册单例，而不是起一个 Spring 上下文：
 * 这里要证的只是"按 DataSource 的有无分流"这一条分支，不需要容器。
 */
class JobLockerTest {

    @Test
    @DisplayName("没有 DataSource：available() = false，试锁恒返回空（调度整体停用，不降级成进程内锁）")
    void withoutDataSourceLockIsUnavailable() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        JobLocker locker = new JobLocker(factory.getBeanProvider(DataSource.class));

        assertThat(locker.available()).isFalse();
        assertThat(locker.tryLock("it-lock", Duration.ofSeconds(10)))
                .as("拿不到锁就返回空——调用方（调度/手动触发）据此跳过或报 20021")
                .isEmpty();
    }

    @Test
    @DisplayName("有 DataSource：锁可用（构造期不连库：provider 只在真取锁时才发 SQL）")
    void withDataSourceLockIsAvailable() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("dataSource", mock(DataSource.class));
        JobLocker locker = new JobLocker(factory.getBeanProvider(DataSource.class));

        assertThat(locker.available()).isTrue();
        assertThat(JobLocker.TABLE).as("锁表名逐字对齐 4.3.11（禁止加列改名）")
                .isEqualTo("eaio_platform.shedlock");
    }
}
