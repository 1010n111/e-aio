package com.eaio.common.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * 时间工具（工具门面，P0 册 4.2）。
 *
 * <p>口径：**存储用 {@link Instant}（UTC），展示按传入的 {@link ZoneId} 转换**；本类不隐式使用系统默认时区，
 * 调用方必须显式给出时区，避免"服务器换时区导致历史数据展示漂移"。
 */
public final class DateUtils {

    /** 常用格式。 */
    public static final String PATTERN_DATE = "yyyy-MM-dd";
    /** 常用格式。 */
    public static final String PATTERN_DATETIME = "yyyy-MM-dd HH:mm:ss";

    private DateUtils() {
    }

    /** 当前时刻（UTC 语义）。 */
    public static Instant now() {
        return Instant.now();
    }

    /** 按格式与时区格式化。 */
    public static String format(Instant instant, String pattern, ZoneId zone) {
        if (instant == null) {
            return null;
        }
        return DateTimeFormatter.ofPattern(pattern).withZone(zone).format(instant);
    }

    /** 按 yyyy-MM-dd HH:mm:ss 与时区格式化。 */
    public static String formatDateTime(Instant instant, ZoneId zone) {
        return format(instant, PATTERN_DATETIME, zone);
    }

    /** 解析为 Instant；格式非法返回 null（与 ConvertUtils 同口径）。 */
    public static Instant parse(String text, String pattern, ZoneId zone) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        try {
            return LocalDateTime.parse(text, DateTimeFormatter.ofPattern(pattern))
                    .atZone(zone)
                    .toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 解析日期（yyyy-MM-dd）为该时区的当日零点。 */
    public static Instant parseDate(String text, ZoneId zone) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        try {
            return LocalDate.parse(text, DateTimeFormatter.ofPattern(PATTERN_DATE))
                    .atStartOfDay(zone)
                    .toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 加减天数（负数即减）。 */
    public static Instant addDays(Instant instant, long days) {
        return instant == null ? null : instant.plus(days, ChronoUnit.DAYS);
    }

    /** 两个时刻之间的天数差（可能为负）。 */
    public static long betweenDays(Instant from, Instant to) {
        return ChronoUnit.DAYS.between(from, to);
    }
}
