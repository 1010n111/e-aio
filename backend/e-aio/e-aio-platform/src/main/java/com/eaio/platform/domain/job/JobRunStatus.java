package com.eaio.platform.domain.job;

import java.util.Locale;

/**
 * 任务运行状态（P1 册 4.3.10 的 {@code ck_job_run_status}；落库 {@code VARCHAR(32)} + CHECK，不用 PG ENUM）。
 *
 * <p>六态是一次执行的完整生命周期（3.4.3 的时序图）：
 * <pre>
 *   RUNNING ──成功──→ SUCCESS
 *      │  ──失败且 attempt ≤ retry_max──→ RETRYING ──到期重投──→ RUNNING（attempt+1）
 *      │  ──失败且 attempt &gt; retry_max──→ FAILED
 *      │  ──超时（线程被中断）──────────→ TIMEOUT（同样进入重试判定）
 *      └─ 未取到并发执行权（不允许并发时）→ SKIPPED
 * </pre>
 *
 * <p>{@code RETRYING} 既是"等待重投"的状态也是重投扫描器的工作队列（{@code idx_job_run_retry} 是
 * {@code WHERE status = 'RETRYING'} 的部分索引）——所以它必须落库而不是放内存队列（跨实例可接管，
 * 3.4.7 的宕机场景）。
 */
public enum JobRunStatus {

    /** 执行中。 */
    RUNNING,

    /** 成功（终态）。 */
    SUCCESS,

    /** 失败且不再重投（终态，3.4.8 的"第 4 次失败（retry_max = 3）"）。 */
    FAILED,

    /** 超时（中断后按失败判定重投；超过上限则成为终态 TIMEOUT）。 */
    TIMEOUT,

    /** 失败待重投（非终态；{@code next_retry_time} 到期由重投扫描器认领）。 */
    RETRYING,

    /** 本次未执行：不允许并发时上一次仍在跑（3.4.6）。 */
    SKIPPED;

    /** 是否终态（终态不再被任何扫描器改写）。 */
    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == TIMEOUT;
    }

    /** 名字是否合法；非法名在写路径抛 10000（参数非法），不进 CHECK 约束。 */
    public static boolean isValid(String name) {
        if (name == null) {
            return false;
        }
        String normalized = name.trim().toUpperCase(Locale.ROOT);
        for (JobRunStatus status : values()) {
            if (status.name().equals(normalized)) {
                return true;
            }
        }
        return false;
    }
}
