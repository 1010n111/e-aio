package com.eaio.common.exception;

import com.eaio.common.api.BusinessErrorCode;

/**
 * 业务异常：携带业务错误码与用户可读消息（契约 V1，P0 册 3.2.4）。
 *
 * <p>用于"可以预期、应向用户说明"的失败（参数、状态、冲突等），与
 * {@link SystemException}（程序缺陷/环境故障）区分：入站链路对两者使用不同的错误码与日志级别。
 */
public class BusinessException extends RuntimeException {

    /** 业务错误码（对应 {@link BusinessErrorCode#getCode()}）。 */
    private final int code;

    public BusinessException(BusinessErrorCode errorCode) {
        this(errorCode.getCode(), errorCode.getMessage());
    }

    public BusinessException(BusinessErrorCode errorCode, Throwable cause) {
        this(errorCode.getCode(), errorCode.getMessage(), cause);
    }

    public BusinessException(BusinessErrorCode errorCode, String message) {
        this(errorCode.getCode(), message);
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
