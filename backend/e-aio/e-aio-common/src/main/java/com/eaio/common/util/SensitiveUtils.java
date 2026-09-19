package com.eaio.common.util;

/**
 * 脱敏规则实现（纯逻辑，便于单测与复用）。
 *
 * <p>规则口径：**宁可多掩**。长度不足时全掩，而不是"掩不了就原样返回"——
 * 后者会让短数据在接口上明文外泄，且没有测试能发现。
 */
public final class SensitiveUtils {

    /** 掩码字符。 */
    public static final char MASK_CHAR = '*';

    private SensitiveUtils() {
    }

    /** 按类型掩码；空值返回空值（不制造 "null" 字符串）。 */
    public static String mask(String value, SensitiveType type) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return switch (type) {
            case MOBILE -> keep(value, 3, 4);
            case ID_CARD -> keep(value, 6, 4);
            case BANK_CARD -> keep(value, 4, 4);
            case EMAIL -> maskEmail(value);
            case NAME -> maskName(value);
            case ADDRESS -> keep(value, 6, 0);
            case CUSTOM -> throw new IllegalArgumentException(
                    "SensitiveType.CUSTOM 需要显式给出保留位数，请调用 mask(value, prefixKeep, suffixKeep)");
        };
    }

    /** 自定义保留位数：前面保留 prefixKeep 位、后面保留 suffixKeep 位。 */
    public static String mask(String value, int prefixKeep, int suffixKeep) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (prefixKeep < 0 || suffixKeep < 0) {
            throw new IllegalArgumentException("保留位数不能为负：prefixKeep=" + prefixKeep + ", suffixKeep=" + suffixKeep);
        }
        return keep(value, prefixKeep, suffixKeep);
    }

    /** 手机号。 */
    public static String maskMobile(String mobile) {
        return mask(mobile, SensitiveType.MOBILE);
    }

    /** 身份证号。 */
    public static String maskIdCard(String idCard) {
        return mask(idCard, SensitiveType.ID_CARD);
    }

    /** 银行卡号。 */
    public static String maskBankCard(String bankCard) {
        return mask(bankCard, SensitiveType.BANK_CARD);
    }

    /** 邮箱。 */
    public static String maskEmail(String email) {
        if (email == null || email.isEmpty()) {
            return email;
        }
        int at = email.indexOf('@');
        if (at <= 0 || at == email.length() - 1) {
            // 不是合法邮箱：整串掩掉，不猜测结构
            return repeat(email.length());
        }
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() == 1) {
            return local + MASK_CHAR + domain;
        }
        return local.charAt(0) + repeat(local.length() - 1) + domain;
    }

    /** 姓名。 */
    public static String maskName(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        if (name.length() == 1) {
            return String.valueOf(MASK_CHAR);
        }
        return name.charAt(0) + repeat(name.length() - 1);
    }

    private static String keep(String value, int prefixKeep, int suffixKeep) {
        int length = value.length();
        if (prefixKeep + suffixKeep >= length) {
            // 保留位数吃掉整串就没有脱敏意义：全掩
            return repeat(length);
        }
        return value.substring(0, prefixKeep)
                + repeat(length - prefixKeep - suffixKeep)
                + value.substring(length - suffixKeep);
    }

    private static String repeat(int count) {
        return String.valueOf(MASK_CHAR).repeat(Math.max(count, 0));
    }
}
