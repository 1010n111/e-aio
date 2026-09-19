package com.eaio.platform.domain.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link JobLockWindow} 的单测（P1 册 3.4.3 的锁名与 3.4.7 的僵尸判定线）。
 *
 * <p>这两条线一旦算错，症状分别是"两个实例同时跑同一任务"（锁太短）与"永远显示运行中"（僵尸线太长），
 * 都是运行期才看得出的问题，所以在纯函数层钉死。
 */
class JobLockWindowTest {

    @Test
    @DisplayName("锁名逐字：platform-job-{jobCode}；内部扫描锁用独立命名空间，构造上与任何任务锁不相等")
    void lockNames() {
        assertThat(JobLockWindow.lockName("platform.job.log.clean")).isEqualTo("platform-job-platform.job.log.clean");
        assertThat(JobLockWindow.retryScanLockName()).isEqualTo("platform-internal-retry-scan");

        // 不变量（靠**前缀**而不是靠"没人会那么起名"）：两个命名空间互不为前缀 → 任何 job_code 都撞不上
        assertThat(JobLockWindow.INTERNAL_LOCK_PREFIX)
                .as("内部前缀必须与任务前缀不同")
                .isNotEqualTo(JobLockWindow.JOB_LOCK_PREFIX);
        assertThat(JobLockWindow.JOB_LOCK_PREFIX.startsWith(JobLockWindow.INTERNAL_LOCK_PREFIX))
                .as("任务前缀不能以内部前缀开头（否则某个 job_code 能拼出内部锁名）")
                .isFalse();
        assertThat(JobLockWindow.INTERNAL_LOCK_PREFIX.startsWith(JobLockWindow.JOB_LOCK_PREFIX))
                .as("内部前缀不能以任务前缀开头")
                .isFalse();
        assertThat(JobLockWindow.retryScanLockName())
                .as("内部扫描锁名不以任务锁前缀开头")
                .doesNotStartWith(JobLockWindow.JOB_LOCK_PREFIX);

        for (String code : List.of("retry-scan", "platform.job.retry", "internal-retry-scan", "job.retry.scan")) {
            assertThat(JobLockWindow.lockName(code))
                    .as("任务 %s 的锁名不能等于内部扫描锁名（否则扫描与任务互相饿死）", code)
                    .isNotEqualTo(JobLockWindow.retryScanLockName());
        }
        assertThat(JobLockWindow.retryScanLockName().length())
                .as("shedlock.name 是 VARCHAR(64)")
                .isLessThanOrEqualTo(64);
    }

    @Test
    @DisplayName("持锁上限 = 超时 + 退避 + 60s（3.4.3：锁要覆盖整个执行窗口，短了就等于没锁）")
    void lockAtMostForCoversExecutionWindow() {
        Job job = job(300, 30);
        assertThat(JobLockWindow.lockAtMostFor(job)).isEqualTo(Duration.ofSeconds(390));
        assertThat(JobLockWindow.lockAtMostFor(30, 0)).isEqualTo(Duration.ofSeconds(90));
        assertThat(JobLockWindow.lockAtMostFor(0, 0)).as("防御：至少 1 秒 + 60 秒余量").isEqualTo(Duration.ofSeconds(61));
        assertThat(JobLockWindow.lockAtMostFor(300, 30))
                .as("锁窗口必须长于任务自己的超时")
                .isGreaterThan(Duration.ofSeconds(300));
    }

    @Test
    @DisplayName("僵尸判定线 = now - 持锁上限 × 2（3.4.7）；比它新的 RUNNING 行算「真的在跑」")
    void staleBeforeIsTwiceTheLockWindow() {
        Instant now = Instant.parse("2026-09-19T03:00:00Z");
        Job job = job(300, 30);
        Instant staleBefore = JobLockWindow.staleBefore(job, now);
        assertThat(staleBefore).isEqualTo(now.minus(Duration.ofSeconds(780)));
        assertThat(now.minus(Duration.ofSeconds(60)))
                .as("1 分钟前的 RUNNING 行：远未到僵尸线 → 视为正在运行")
                .isAfter(staleBefore);
        assertThat(now.minus(Duration.ofHours(1)))
                .as("1 小时前的 RUNNING 行：已过僵尸线（780s）→ 视为宕机残留")
                .isBefore(staleBefore);
    }

    private static Job job(int timeoutSeconds, int backoffSeconds) {
        Job job = new Job();
        job.setTimeoutSeconds(timeoutSeconds);
        job.setBackoffSeconds(backoffSeconds);
        return job;
    }
}
