package com.eaio.common.exception;

import com.eaio.common.api.ErrorCode;

/**
 * 系统异常：程序缺陷或环境故障（契约 V1，P0 册 3.2.4）。
 *
 * <p>与此对应的返回码是 {@link ErrorCode#SYSTEM_ERROR}，消息对用户隐藏细节，原始异常进日志。
 */
public class SystemException extends RuntimeException {

    public SystemException(String message) {
        super(message);
    }

    public SystemException(String message, Throwable cause) {
        super(message, cause);
    }
}
