package com.eaio.platform.domain.param;

import java.math.BigDecimal;

import com.eaio.common.exception.BusinessException;
import com.eaio.common.exception.JsonException;
import com.eaio.common.json.JsonUtils;
import com.eaio.platform.api.PlatformErrorCode;

/**
 * 参数值的类型校验与转换（P1 册 3.1.2）。
 *
 * <p>**纯逻辑、无 IO**：类型转换与越界判定是参数中心第二类易错规则，单独成类便于穷举单测。
 *
 * <p>核心口径（3.1.3 第 1 条）：**类型不匹配一律抛 {@code 20002}，绝不静默回退默认值**——
 * 否则配置写错会变成"悄无声息地用了默认值"，比直接报错难查得多。
 */
public final class ParamValueCodec {

    private ParamValueCodec() {
    }

    /** 值能否按该类型解析（写入前的校验入口；{@code SECRET} 与 {@code STRING} 视为任意文本）。 */
    public static boolean isValid(String raw, ParamValueType type) {
        if (raw == null) {
            return true;
        }
        try {
            switch (type) {
                case INT -> Long.parseLong(raw.trim());
                case BOOL -> parseBool(raw);
                case DECIMAL -> new BigDecimal(raw.trim());
                case JSON -> JsonUtils.fromJson(raw, Object.class);
                default -> {
                    return true;
                }
            }
            return true;
        } catch (NumberFormatException | JsonException | BusinessException e) {
            return false;
        }
    }

    /** 按类型规范化写入值；非法值抛 {@code 20002}。 */
    public static String normalize(String raw, ParamValueType type) {
        if (raw == null) {
            return null;
        }
        if (!isValid(raw, type)) {
            throw new BusinessException(PlatformErrorCode.PARAM_TYPE_MISMATCH,
                    "参数值与类型 " + type + " 不匹配：" + abbreviate(raw));
        }
        return switch (type) {
            case INT -> String.valueOf(Long.parseLong(raw.trim()));
            case BOOL -> String.valueOf(parseBool(raw));
            case DECIMAL -> new BigDecimal(raw.trim()).stripTrailingZeros().toPlainString();
            default -> raw;
        };
    }

    /** 读成 int；类型不匹配抛 {@code 20002}。 */
    public static int toInt(String raw, String key) {
        try {
            return Integer.parseInt(require(raw, key).trim());
        } catch (NumberFormatException e) {
            throw mismatch(key, raw, ParamValueType.INT);
        }
    }

    /** 读成 boolean；类型不匹配抛 {@code 20002}（{@code true/false} 之外的值不算布尔）。 */
    public static boolean toBool(String raw, String key) {
        try {
            return parseBool(require(raw, key));
        } catch (BusinessException e) {
            throw mismatch(key, raw, ParamValueType.BOOL);
        }
    }

    private static boolean parseBool(String raw) {
        String text = raw.trim();
        if ("true".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text)) {
            return false;
        }
        throw new BusinessException(PlatformErrorCode.PARAM_TYPE_MISMATCH, "不是布尔值：" + abbreviate(text));
    }

    private static String require(String raw, String key) {
        if (raw == null) {
            throw mismatch(key, null, ParamValueType.STRING);
        }
        return raw;
    }

    private static BusinessException mismatch(String key, String raw, ParamValueType expected) {
        return new BusinessException(PlatformErrorCode.PARAM_TYPE_MISMATCH,
                "参数 " + key + " 的值无法读成 " + expected + "：" + abbreviate(raw));
    }

    /** 错误消息里不回显完整值：SECRET 键的原文不得进日志/响应体（P0 册 3.2.4 与 P1 册 3.1.1）。 */
    private static String abbreviate(String raw) {
        if (raw == null) {
            return "(null)";
        }
        return raw.length() <= 16 ? "***" : "***(" + raw.length() + " 字符)";
    }
}
