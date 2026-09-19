package com.eaio.platform.domain.job;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.scheduling.support.CronExpression;

/**
 * cron 校验（P1 册 3.4.6 的 cron 方言取舍：**Spring {@code CronExpression} 语法，6 段，且拒绝 Quartz 方言**）。
 *
 * <p>为什么把校验放 domain 层：它是纯函数（不碰库、不碰容器），domain 单测里 100% 覆盖；
 * 而"哪一步拒绝"（保存 20022、{@code resume} 20022）是 application 层的事。
 *
 * <p><b>为什么还要自己拦 Quartz 方言（实测结论，2026-09-19）</b>：册面 3.4.6 的方言口径是
 * "Spring 6 段，**不支持** Quartz 的 {@code ?}/{@code L}/{@code W}/{@code #}"，但 Spring Framework 7 的
 * {@code CronExpression} **其实接受** {@code ?}、{@code L}、{@code #}（{@code CronTrigger} 的 javadoc 也
 * 明说支持 day-of-month/week 的 {@code L/#} 表达式）。只依赖 {@code parse} 的结果就会出现
 * "保存成功（没报 20022）、运行时按另一套语义触发"——从 RuoYi/Quartz 迁来的表达式正好落在这个缝里。
 * 因此这里在语法校验之外**显式拒绝**这四个字符。
 *
 * <p><b>判定的坑：方言字符也出现在合法的名字里</b>。{@code WED} 含 {@code W}、{@code JUL} 含 {@code L}
 * ——直接 {@code contains("W")} 会把"每周三 09:00（{@code 0 0 9 * * WED}）"和"每年 7 月 1 日"判成非法。
 * 所以判定顺序是：**先剔除月名/周名**（JAN…DEC、MON…SUN），再在剩下的部分里找 {@code ?}/{@code L}/
 * {@code W}/{@code #}。这样 {@code 15W}、{@code LW}、{@code 1#2}、{@code ?} 仍然被拦，而
 * {@code MON-FRI}、{@code WED}、{@code JUL} 不受影响。
 *
 * <p>另外 Spring 的 {@code CronExpression} 是 6 段（秒 分 时 日 月 周）；5 段（Quartz 的"分 时 日 月 周"）
 * 与 7 段（带年）都会被判非法——多一段少一段都是"以为配上了、实际没跑"的经典事故。
 */
public final class JobCron {

    /** 月名与周名（Spring 支持它们；它们含方言字符，必须先剔除再判方言）。 */
    private static final Pattern NAMES = Pattern.compile(
            "(?i)JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC|MON|TUE|WED|THU|FRI|SAT|SUN");

    /** Quartz 专有方言字符（3.4.6 明确不支持）；{@code L}/{@code W} 大小写都拦。 */
    private static final char[] QUARTZ_DIALECT = {'?', 'L', 'W', '#'};

    private JobCron() {
    }

    /** 是否为合法的 6 段 Spring cron，且不含 Quartz 方言字符。 */
    public static boolean isValid(String cron) {
        if (cron == null || cron.isBlank()) {
            return false;
        }
        String expression = cron.trim();
        if (containsQuartzDialect(expression)) {
            return false;
        }
        return CronExpression.isValidExpression(expression);
    }

    /**
     * 表达式中是否出现 Quartz 方言字符（先剔除月名/周名，见类注释的坑）。
     *
     * <p>{@code null} 返回 {@code false}：本方法只回答"有没有方言"，空值由 {@link #isValid} 负责拒绝。
     */
    public static boolean containsQuartzDialect(String cron) {
        if (cron == null) {
            return false;
        }
        String remaining = NAMES.matcher(cron).replaceAll("").toUpperCase(Locale.ROOT);
        for (char dialect : QUARTZ_DIALECT) {
            if (remaining.indexOf(dialect) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 严格晚于 {@code after} 的下一个触发时刻（cron 非法或表达式无后续时刻时返回空）。
     *
     * <p>时区口径：**JVM 默认时区**（Spring {@code CronTrigger} 不带时区时的行为，也是
     * {@code ThreadPoolTaskScheduler} 的行为）。册面未指定时区，选默认时区的理由是自托管部署里
     * "03:00 就是这个机器所在地的凌晨三点"；{@code platform.time.display-zone}（7.2）管的是**展示**，
     * 不参与调度计算（登记在《实现注记（T7）》）。
     *
     * <p>本方法只用于**展示**（{@code job.next_run_time} 与 {@code JobDTO.nextRunTime}）与
     * "下一次大概是几点"的日志；真正的触发由 {@code CronTrigger} 决定，两者用同一个
     * {@link CronExpression}，语义一致（{@code CronTrigger} 的 lenient 语义 = 每次从"现在"往后取下一个点）。
     */
    public static Optional<Instant> nextRun(String cron, Instant after) {
        if (!isValid(cron)) {
            return Optional.empty();
        }
        ZonedDateTime next = CronExpression.parse(cron.trim()).next(after.atZone(ZoneId.systemDefault()));
        return Optional.ofNullable(next).map(ZonedDateTime::toInstant);
    }
}
