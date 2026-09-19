package com.eaio.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 时间工具：存储 UTC、展示显式给时区、解析失败返回 null。 */
class DateUtilsTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final ZoneId UTC = ZoneId.of("UTC");

    @Test
    @DisplayName("格式化按传入时区，不用系统默认时区")
    void formatUsesGivenZone() {
        Instant instant = Instant.parse("2026-09-19T00:30:00Z");

        assertThat(DateUtils.formatDateTime(instant, UTC)).isEqualTo("2026-09-19 00:30:00");
        assertThat(DateUtils.formatDateTime(instant, SHANGHAI)).isEqualTo("2026-09-19 08:30:00");
        assertThat(DateUtils.format(null, DateUtils.PATTERN_DATE, UTC)).isNull();
    }

    @Test
    @DisplayName("解析与格式化互为逆运算")
    void parseIsInverseOfFormat() {
        Instant original = Instant.parse("2026-09-19T08:30:00Z");

        String text = DateUtils.formatDateTime(original, UTC);
        Instant parsed = DateUtils.parse(text, DateUtils.PATTERN_DATETIME, UTC);

        assertThat(parsed).isEqualTo(original);
    }

    @Test
    @DisplayName("非法输入返回 null，不抛异常")
    void invalidInputReturnsNull() {
        assertThat(DateUtils.parse("not-a-time", DateUtils.PATTERN_DATETIME, UTC)).isNull();
        assertThat(DateUtils.parse("", DateUtils.PATTERN_DATETIME, UTC)).isNull();
        assertThat(DateUtils.parse(null, DateUtils.PATTERN_DATETIME, UTC)).isNull();
        assertThat(DateUtils.parseDate("2026-13-40", UTC)).isNull();
    }

    @Test
    @DisplayName("日期解析为该时区当日零点")
    void parseDateStartsAtZoneMidnight() {
        assertThat(DateUtils.parseDate("2026-09-19", SHANGHAI))
                .isEqualTo(Instant.parse("2026-09-18T16:00:00Z"));
    }

    @Test
    @DisplayName("加减天数与差值")
    void addAndBetweenDays() {
        Instant start = Instant.parse("2026-09-01T00:00:00Z");

        assertThat(DateUtils.addDays(start, 10)).isEqualTo(Instant.parse("2026-09-11T00:00:00Z"));
        assertThat(DateUtils.betweenDays(start, Instant.parse("2026-09-11T00:00:00Z"))).isEqualTo(10);
        assertThat(DateUtils.betweenDays(Instant.parse("2026-09-11T00:00:00Z"), start)).isEqualTo(-10);
        assertThat(DateUtils.addDays(null, 1)).isNull();
    }
}
