package com.eaio.common.exception;

import com.eaio.common.api.ErrorCode;

/**
 * 幂等校验不可用：无法判定是否重复（Redis 连接失败/超时）。
 *
 * <p>对应 fail-closed 口径（P0 册 3.2.5）：拒绝执行并返回 {@link ErrorCode#IDEMPOTENCY_UNAVAILABLE}，
 * 不静默放行——放行会在存储抖动期间真实产生重复写入。
 */
public class IdempotencyUnavailableException extends SystemException {

    public IdempotencyUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 对应错误码。 */
    public ErrorCode errorCode() {
        return ErrorCode.IDEMPOTENCY_UNAVAILABLE;
    }
}
