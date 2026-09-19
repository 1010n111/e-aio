package com.eaio.common.exception;

import com.eaio.common.api.ErrorCode;

/**
 * JSON 处理异常（序列化/反序列化失败）。
 *
 * <p>对外是可读消息 + 系统错误码：JSON 报文问题在调用方看来是"程序或报文缺陷"，不是业务失败。
 */
public class JsonException extends SystemException {

    public JsonException(String message) {
        super(message);
    }

    public JsonException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 统一从底层异常构造，避免各调用点各写一套消息。 */
    public static JsonException of(String action, Throwable cause) {
        return new JsonException("JSON " + action + "失败：" + cause.getMessage(), cause);
    }

    /** 对应错误码。 */
    public ErrorCode errorCode() {
        return ErrorCode.SYSTEM_ERROR;
    }
}
