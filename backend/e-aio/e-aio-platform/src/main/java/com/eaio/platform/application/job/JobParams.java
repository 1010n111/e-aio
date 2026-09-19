package com.eaio.platform.application.job;

import com.eaio.platform.application.param.ParamResolver;
import com.eaio.platform.domain.param.ParamContext;
import org.springframework.stereotype.Component;

/**
 * 任务相关参数的读取口径（P1 册 7.2 的三个 {@code platform.job.*} 键）。
 *
 * <p>为什么单独一个类：键字符串与默认值散落在执行壳、重投扫描、日志清理三处时，任何一处写错都只会
 * 静默退回默认值（"{@code platform.job.log-retain-days}" 写成 "{@code platform.job.log.retain-days}"
 * 在参数中心是**另一个键**，永远解析不到默认值之外的任何东西）。这里一处声明、一处默认值。
 *
 * <p>读取上下文用 {@link ParamContext#systemOnly()}：后台任务没有用户与组织（3.1.3 明写
 * "定时任务、启动预热没有用户"），因此只看 SYSTEM 级，不放行 ORG/USER 覆盖。
 *
 * <p>{@code platform.job.alert.fail-threshold}（7.2，默认 3）不在本类：T7 不发告警（{@code alert} 表在
 * V9、{@code NoticeApi} 未交付），阈值由承接告警的能力在订阅 {@code JobFailedEvent} 时自己读。
 */
@Component
public class JobParams {

    /** {@code job_run} 保留天数（7.2：默认 90）。 */
    public static final String LOG_RETAIN_DAYS = "platform.job.log-retain-days";

    /** 重投扫描每批条数（7.2：默认 100）。 */
    public static final String RETRY_BATCH_SIZE = "platform.job.retry-batch-size";

    private static final int DEFAULT_LOG_RETAIN_DAYS = 90;
    private static final int DEFAULT_RETRY_BATCH_SIZE = 100;

    private final ParamResolver params;

    public JobParams(ParamResolver params) {
        this.params = params;
    }

    /** {@code job_run} 保留天数；非法值（非数字/≤0）回落默认值并记 WARN（参数中心读的是人填的字符串）。 */
    public int logRetainDays() {
        return intValue(LOG_RETAIN_DAYS, DEFAULT_LOG_RETAIN_DAYS);
    }

    /** 重投扫描每批条数；非法值回落默认值。 */
    public int retryBatchSize() {
        return intValue(RETRY_BATCH_SIZE, DEFAULT_RETRY_BATCH_SIZE);
    }

    /** {@code platform.scheduler.pool-size}（7.2：默认 4，**非热更新**——调度器线程池在启动期建好）。 */
    public int schedulerPoolSize() {
        return intValue("platform.scheduler.pool-size", 4);
    }

    private int intValue(String key, int defaultValue) {
        String raw = params.resolveOrDefault(key, String.valueOf(defaultValue), ParamContext.systemOnly()).value();
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
