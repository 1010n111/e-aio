package com.eaio.common.util;

import java.math.BigDecimal;

/**
 * 类型安全转换（工具门面，P0 册 4.2）。
 *
 * <p>统一口径：**转换失败返回 null，不抛异常**；确需区分"没有值"与"值非法"时由调用方自己解析。
 * 这条口径是为了让"外部输入不可信"的转换点在业务代码里保持短小（{@code defaultIfNull} 一行搞定）。
 */
public final class ConvertUtils {

    private ConvertUtils() {
    }

    /** 转 long；不可转换返回 null。 */
    public static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 转 int；不可转换返回 null。 */
    public static Integer toInt(Object value) {
        Long result = toLong(value);
        if (result == null) {
            return null;
        }
        if (result > Integer.MAX_VALUE || result < Integer.MIN_VALUE) {
            return null;
        }
        return result.intValue();
    }

    /** 转 BigDecimal；不可转换返回 null。 */
    public static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        try {
            return new BigDecimal(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 空值兜底。 */
    public static <T> T defaultIfNull(T value, T defaultValue) {
        return value == null ? defaultValue : value;
    }
}
