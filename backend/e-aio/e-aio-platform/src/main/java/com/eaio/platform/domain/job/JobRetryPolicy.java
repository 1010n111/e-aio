package com.eaio.platform.domain.job;

import java.time.Duration;
import java.time.Instant;

/**
 * 失败重投策略（P1 册 3.4.4，纯函数、无状态——因此可以在模块内单测里 100% 覆盖）。
 *
 * <p>口径逐条对齐册面：
 * <ul>
 *   <li><b>上限</b>：{@code retry_max}（默认 3，DDL CHECK 0–10）。判定用"本次 attempt ≤ retry_max"
 *       ——attempt 从 1 起，{@code retry_max = 3} 时第 1/2/3 次失败都写 {@code RETRYING}，
 *       第 4 次失败是终态 {@code FAILED}（3.4.8 的断言）。{@code retry_max = 0} 表示一次都不重投
 *       （重投类任务本身就用它避免重试风暴，3.4.7）；</li>
 *   <li><b>退避</b>：{@code backoff_seconds × 2^(attempt-1)}，即第 1 次失败等 backoff、第 2 次等
 *       2×backoff、第 3 次等 4×backoff……**封顶 30 分钟**（常量 {@link #MAX_BACKOFF}）；</li>
 *   <li><b>不占线程</b>：策略只算出"什么时候可以重投"，等待由 {@code job_run.next_retry_time} +
 *       重投扫描器承担，失败路径不 sleep（3.4.4 的第一句）。</li>
 * </ul>
 *
 * <p>{@code attempt} 的语义由本类钉死：它是**已经失败的那次**的序号（不是下一次的序号）。
 * 这样 {@code job_run.attempt} 在库里就始终表示"这一行代表第几次执行"。
 */
public final class JobRetryPolicy {

    /** 退避封顶（3.4.4：封顶 30 分钟）。 */
    public static final Duration MAX_BACKOFF = Duration.ofMinutes(30);

    /** 重投上限的边界（DDL {@code ck_job_retry}：{@code retry_max BETWEEN 0 AND 10}）。 */
    public static final int MAX_RETRY_LIMIT = 10;

    private JobRetryPolicy() {
    }

    /** 本次失败后是否还要重投（{@code attempt} 是刚失败的那一次）。 */
    public static boolean canRetry(int attempt, int retryMax) {
        return retryMax > 0 && attempt <= retryMax;
    }

    /**
     * 下一次重投的到期时刻：{@code now + min(backoffSeconds × 2^(attempt-1), 30min)}。
     *
     * <p>用 {@code long} 位移并在溢出前就封顶：{@code attempt} 很大时 {@code 1L << (attempt-1)}
     * 会溢出成负数，把退避算成"过去的时间"（等于立刻重投，风暴）。这里先把指数夹到 30 分钟需要的
     * 位数再乘。
     */
    public static Instant nextRetryAt(Instant now, int backoffSeconds, int attempt) {
        return now.plus(backoff(backoffSeconds, attempt));
    }

    /** 退避时长（封顶 30 分钟）；{@code backoffSeconds ≤ 0} 视为 0（不等待，由扫描周期决定）。 */
    public static Duration backoff(int backoffSeconds, int attempt) {
        if (backoffSeconds <= 0) {
            return Duration.ZERO;
        }
        int exponent = Math.max(0, attempt - 1);
        // 30 分钟 / 1 秒 = 1800 < 2^11：指数到 11 就已经超过封顶值，再大也没有意义（也就不可能溢出）
        long multiplier = exponent >= 11 ? Long.MAX_VALUE : 1L << exponent;
        long capped = MAX_BACKOFF.toSeconds() / backoffSeconds;
        if (multiplier >= Math.max(1L, capped)) {
            return MAX_BACKOFF;
        }
        Duration candidate = Duration.ofSeconds((long) backoffSeconds * multiplier);
        return candidate.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : candidate;
    }
}
