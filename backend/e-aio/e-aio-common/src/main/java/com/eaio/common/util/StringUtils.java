package com.eaio.common.util;

/**
 * 字符串工具（工具门面，P0 册 4.2）。
 *
 * <p>只放"项目口径"的字符串操作：空白判定、截断（含省略号）、掩码委托。
 * 不做全能工具箱——Hutool 已有的能力不在这里重复包装，避免门面变成第二个标准库。
 */
public final class StringUtils {

    private StringUtils() {
    }

    /** null 或全空白（含仅空白字符）为真。 */
    public static boolean isBlank(CharSequence value) {
        if (value == null) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isWhitespace(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** 非空白。 */
    public static boolean isNotBlank(CharSequence value) {
        return !isBlank(value);
    }

    /** 空值转为空串；非空原样返回。 */
    public static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 截断到最大长度；超出时补省略号（省略号计入最大长度）。
     *
     * <p>{@code maxLength < 1} 返回空串而不是抛异常：调用方常在日志与展示层用它，
     * 为了长度参数把业务打断不值得。
     */
    public static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        if (maxLength < 1) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        if (maxLength <= ELLIPSIS.length()) {
            return ELLIPSIS.substring(0, maxLength);
        }
        return value.substring(0, maxLength - ELLIPSIS.length()) + ELLIPSIS;
    }

    /** 按类型掩码（委托脱敏规则，保证全系统一套口径）。 */
    public static String mask(String value, SensitiveType type) {
        return SensitiveUtils.mask(value, type);
    }

    private static final String ELLIPSIS = "...";
}
