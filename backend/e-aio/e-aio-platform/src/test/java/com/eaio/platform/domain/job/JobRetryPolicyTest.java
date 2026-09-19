package com.eaio.platform.domain.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link JobRetryPolicy} 的单测（P1 册 3.4.4/3.4.8）。
 *
 * <p>退避与上限是"最容易算错、又完全不需要数据库"的部分：指数写错一位就是"退避成 0 秒"（重试风暴），
 * 上限差一个 1 就是"少跑一次重试"或"永远重试"。这里把边界逐条钉死。
 */
class JobRetryPolicyTest {

    @Test
    @DisplayName("重投上限：attempt ≤ retry_max 才有下一次（retry_max = 3 → 第 4 次失败是终态）")
    void canRetryBoundaries() {
        assertThat(JobRetryPolicy.canRetry(1, 3)).as("第 1 次失败 → 还有 2 次机会").isTrue();
        assertThat(JobRetryPolicy.canRetry(3, 3)).as("第 3 次失败 → 还有 1 次机会").isTrue();
        assertThat(JobRetryPolicy.canRetry(4, 3)).as("第 4 次失败 → 终态 FAILED（3.4.8）").isFalse();
        assertThat(JobRetryPolicy.canRetry(1, 0)).as("retry_max = 0 → 一次都不重投（3.4.7 的重投类任务）").isFalse();
        assertThat(JobRetryPolicy.canRetry(1, -1)).as("负数防御：不重投").isFalse();
    }

    @Test
    @DisplayName("指数退避：backoff × 2^(attempt-1)，封顶 30 分钟（3.4.4）")
    void backoffIsExponentialAndCapped() {
        assertThat(JobRetryPolicy.backoff(30, 1)).as("第 1 次：30s").isEqualTo(Duration.ofSeconds(30));
        assertThat(JobRetryPolicy.backoff(30, 2)).as("第 2 次：60s").isEqualTo(Duration.ofSeconds(60));
        assertThat(JobRetryPolicy.backoff(30, 3)).as("第 3 次：120s").isEqualTo(Duration.ofSeconds(120));
        assertThat(JobRetryPolicy.backoff(30, 4)).isEqualTo(Duration.ofSeconds(240));
        assertThat(JobRetryPolicy.backoff(30, 5)).isEqualTo(Duration.ofSeconds(480));
        assertThat(JobRetryPolicy.backoff(30, 6)).isEqualTo(Duration.ofSeconds(960));
        assertThat(JobRetryPolicy.backoff(30, 7)).as("32×30s = 960s … 64×30s = 1920s > 30min → 封顶")
                .isEqualTo(JobRetryPolicy.MAX_BACKOFF);
        assertThat(JobRetryPolicy.backoff(30, 30)).as("指数很大时不溢出成负数（会退化成立刻重投）")
                .isEqualTo(JobRetryPolicy.MAX_BACKOFF);
        assertThat(JobRetryPolicy.backoff(0, 5)).as("backoff = 0（测试常用）：不等待").isEqualTo(Duration.ZERO);
        assertThat(JobRetryPolicy.backoff(1800, 1)).as("基数本身就到封顶").isEqualTo(JobRetryPolicy.MAX_BACKOFF);
        assertThat(JobRetryPolicy.backoff(3600, 1)).as("基数超过封顶也封顶").isEqualTo(JobRetryPolicy.MAX_BACKOFF);
    }

    @Test
    @DisplayName("nextRetryAt = now + 退避；attempt 指的是**刚失败的那一次**")
    void nextRetryAtUsesFailedAttemptNumber() {
        Instant now = Instant.parse("2026-09-19T03:00:00Z");
        assertThat(JobRetryPolicy.nextRetryAt(now, 30, 1)).isEqualTo(now.plusSeconds(30));
        assertThat(JobRetryPolicy.nextRetryAt(now, 30, 3)).isEqualTo(now.plusSeconds(120));
        assertThat(JobRetryPolicy.nextRetryAt(now, 30, 99)).isEqualTo(now.plus(JobRetryPolicy.MAX_BACKOFF));
    }

    @Test
    @DisplayName("封顶常量与 DDL 的 CHECK 边界一致（retry_max 0–10）")
    void constantsMatchDdl() {
        assertThat(JobRetryPolicy.MAX_BACKOFF).isEqualTo(Duration.ofMinutes(30));
        assertThat(JobRetryPolicy.MAX_RETRY_LIMIT).as("ck_job_retry: retry_max BETWEEN 0 AND 10").isEqualTo(10);
    }
}
