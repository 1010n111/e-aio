package com.eaio.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 字符串与转换工具（口径：转换失败返回 null，不抛异常）。 */
class BasicUtilsTest {

    @Test
    @DisplayName("空白判定：null、空串、纯空白都为真")
    void blankDetection() {
        assertThat(StringUtils.isBlank(null)).isTrue();
        assertThat(StringUtils.isBlank("")).isTrue();
        assertThat(StringUtils.isBlank("  \t\n")).isTrue();
        assertThat(StringUtils.isBlank(" x ")).isFalse();
        assertThat(StringUtils.isNotBlank(" x ")).isTrue();
    }

    @Test
    @DisplayName("截断：省略号计入长度，短串原样返回")
    void truncate() {
        assertThat(StringUtils.truncate("abcdef", 10)).isEqualTo("abcdef");
        assertThat(StringUtils.truncate("abcdefghij", 7)).isEqualTo("abcd...");
        assertThat(StringUtils.truncate("abcdefghij", 3)).isEqualTo("...");
        assertThat(StringUtils.truncate("abcdefghij", 0)).isEmpty();
        assertThat(StringUtils.truncate(null, 5)).isNull();
    }

    @Test
    @DisplayName("掩码委托脱敏规则，全系统一套口径")
    void maskDelegatesToSensitiveRules() {
        assertThat(StringUtils.mask("13812345678", SensitiveType.MOBILE)).isEqualTo("138****5678");
    }

    @Test
    @DisplayName("数值转换：数字、字符串都接受，非法输入返回 null")
    void numericConversion() {
        assertThat(ConvertUtils.toLong(42)).isEqualTo(42L);
        assertThat(ConvertUtils.toLong(" 42 ")).isEqualTo(42L);
        assertThat(ConvertUtils.toLong("abc")).isNull();
        assertThat(ConvertUtils.toLong(null)).isNull();

        assertThat(ConvertUtils.toInt(42L)).isEqualTo(42);
        assertThat(ConvertUtils.toInt(5_000_000_000L)).isNull();
        assertThat(ConvertUtils.toInt("x")).isNull();

        assertThat(ConvertUtils.toBigDecimal("3.14")).isEqualByComparingTo(new BigDecimal("3.14"));
        assertThat(ConvertUtils.toBigDecimal("x")).isNull();
    }

    @Test
    @DisplayName("空值兜底")
    void defaultIfNull() {
        assertThat(ConvertUtils.defaultIfNull(null, "fallback")).isEqualTo("fallback");
        assertThat(ConvertUtils.defaultIfNull("value", "fallback")).isEqualTo("value");
    }
}
