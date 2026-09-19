package com.eaio.platform.domain.job;

import java.time.Duration;
import java.time.Instant;

/**
 * 任务互斥的窗口计算（P1 册 3.4.3 的时序图 + 3.4.7 的宕机场景，纯函数）。
 *
 * <p>三件事只有一个来源，避免"加锁的地方"和"判定僵尸的地方"各算一套：
 * <ol>
 *   <li><b>锁名</b>：{@code platform-job-{jobCode}}（3.4.3 逐字）；</li>
 *   <li><b>持锁上限</b> {@code lockAtMostFor = timeout_seconds + backoff_seconds + 60s}
 *       ——超时 + 重试窗口 + 60 秒。它是"实例真死了、锁什么时候能被别人接管"的上界：
 *       比乐观值大才有意义（小了下场是"任务还在跑，锁先过期，第二个实例进来跑第二遍"）；</li>
 *   <li><b>僵尸判定</b> {@code staleBefore = now - lockAtMostFor × 2}（3.4.7 逐字：超过
 *       {@code lockAtMostFor × 2} 仍为 {@code RUNNING} 的行按"疑似实例宕机"置 {@code FAILED}）。</li>
 * </ol>
 *
 * <p>注意：**持锁上限只用于锁**，不影响执行本身——任务该跑多久由 {@code timeout_seconds} 决定
 * （超时中断，3.4.4）。
 */
public final class JobLockWindow {

    /** 任务锁的固定前缀：**任何 job 行派生的锁名都长这样**（锁定"命名空间"这件事本身）。 */
    public static final String JOB_LOCK_PREFIX = "platform-job-";

    /**
     * 内部任务（不是 job 行）的锁名前缀：与 {@link #JOB_LOCK_PREFIX} 不同。
     *
     * <p>这不是"命名风格"，是**构造上的不相等保证**：{@code lockName(code)} 恒以
     * {@code platform-job-} 开头，而内部锁恒以 {@code platform-internal-} 开头，因此
     * "内部扫描锁"与"任何任务锁"不可能撞名——撞名会让两者互相饿死（谁先抢到谁跑，另一个永远跳过）。
     * 反面教材正是本类的前一版：内部扫描锁写成 {@code platform-job-retry-scan}，一旦有人真建一个
     * {@code job_code = retry-scan} 的任务，两者就同名了（`JobLockWindowTest` 把这个不变量钉住）。
     */
    public static final String INTERNAL_LOCK_PREFIX = "platform-internal-";

    /** 锁的额外余量（秒）：覆盖"取锁 → 写 job_run → 真正开始执行"这段开销。 */
    public static final int LOCK_OVERHEAD_SECONDS = 60;

    /** 僵尸判定的倍数（3.4.7：{@code lockAtMostFor × 2}）。 */
    public static final int STALE_MULTIPLIER = 2;

    private JobLockWindow() {
    }

    /** 分布式锁名（同一 {@code job_code} 在集群内任一时刻最多一个实例执行）。 */
    public static String lockName(String jobCode) {
        return JOB_LOCK_PREFIX + jobCode;
    }

    /**
     * 重投扫描器自身的锁名（它不是 job 行，但同样必须集群内互斥，否则两个实例会把同批重投跑两遍）。
     *
     * <p>用 {@link #INTERNAL_LOCK_PREFIX} 命名空间，与任何任务锁名**构造上不相等**（见该常量的说明）。
     */
    public static String retryScanLockName() {
        return INTERNAL_LOCK_PREFIX + "retry-scan";
    }

    /** 持锁上限。 */
    public static Duration lockAtMostFor(int timeoutSeconds, int backoffSeconds) {
        return Duration.ofSeconds(Math.max(1, timeoutSeconds) + Math.max(0, backoffSeconds) + LOCK_OVERHEAD_SECONDS);
    }

    /** 持锁上限（按任务行）。 */
    public static Duration lockAtMostFor(Job job) {
        return lockAtMostFor(job.getTimeoutSeconds(), job.getBackoffSeconds());
    }

    /** 僵尸 {@code RUNNING} 行的判定线：早于它就按"疑似实例宕机"处理。 */
    public static Instant staleBefore(int timeoutSeconds, int backoffSeconds, Instant now) {
        return now.minus(lockAtMostFor(timeoutSeconds, backoffSeconds).multipliedBy(STALE_MULTIPLIER));
    }

    /** 僵尸判定线（按任务行）。 */
    public static Instant staleBefore(Job job, Instant now) {
        return staleBefore(job.getTimeoutSeconds(), job.getBackoffSeconds(), now);
    }
}
