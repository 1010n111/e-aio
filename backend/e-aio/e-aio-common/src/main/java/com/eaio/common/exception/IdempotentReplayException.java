package com.eaio.common.exception;

import com.eaio.common.api.ErrorCode;

/**
 * 幂等重放异常：同一幂等键重复提交触发（执行中或已完成两种状态都走这里）。
 *
 * <p>长任务不得以幂等键交付结果，需走任务 ID + 轮询（P0 册 3.2.5）。
 */
public class IdempotentReplayException extends BusinessException {

    public IdempotentReplayException() {
        super(ErrorCode.IDEMPOTENT_REPLAY);
    }
}
