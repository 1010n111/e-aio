package com.eaio.platform.domain.job;

import java.util.Locale;

/**
 * 任务触发来源（P1 册 4.3.10 的 {@code ck_job_run_trigger}；字典 {@code platform_job_trigger_type}
 * 的项值与之逐字一致，种子 ID 34–36）。
 *
 * <p>为什么把"谁触发的"写进运行日志：运维看到失败时要能区分"cron 到点自己跑的"与"人手动跑的"
 * ——两者的排查路径完全不同（前者看调度与互斥，后者看参数与权限）。
 */
public enum JobTriggerType {

    /** cron 到点触发。 */
    CRON,

    /** 管理页手动触发。 */
    MANUAL,

    /** 失败后的重投（重投扫描器认领 RETRYING 行时改写为它，3.4.4）。 */
    RETRY;

    /** 名字是否合法。 */
    public static boolean isValid(String name) {
        if (name == null) {
            return false;
        }
        String normalized = name.trim().toUpperCase(Locale.ROOT);
        for (JobTriggerType type : values()) {
            if (type.name().equals(normalized)) {
                return true;
            }
        }
        return false;
    }
}
