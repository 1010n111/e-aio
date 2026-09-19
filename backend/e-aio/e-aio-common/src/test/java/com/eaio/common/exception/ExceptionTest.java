package com.eaio.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.eaio.common.api.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 异常契约（P0 册 3.2.4）。 */
class ExceptionTest {

    @Test
    @DisplayName("业务异常：直接使用错误码契约")
    void businessExceptionFromErrorCode() {
        BusinessException exception = new BusinessException(ErrorCode.DATA_NOT_FOUND);

        assertThat(exception.getCode()).isEqualTo(ErrorCode.DATA_NOT_FOUND.getCode());
        assertThat(exception.getMessage()).isEqualTo(ErrorCode.DATA_NOT_FOUND.getMessage());
    }

    @Test
    @DisplayName("业务异常：可覆盖消息但保留错误码")
    void businessExceptionCanOverrideMessage() {
        BusinessException exception = new BusinessException(ErrorCode.DATA_CONFLICT, "订单已被他人修改");

        assertThat(exception.getCode()).isEqualTo(ErrorCode.DATA_CONFLICT.getCode());
        assertThat(exception.getMessage()).isEqualTo("订单已被他人修改");
    }

    @Test
    @DisplayName("业务异常：保留根因，便于日志")
    void businessExceptionKeepsCause() {
        IllegalStateException cause = new IllegalStateException("底层原因");
        BusinessException exception = new BusinessException(ErrorCode.DATA_CONFLICT, cause);

        assertThat(exception.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("系统异常：承载原始异常，不暴露给用户的消息由入站链路决定")
    void systemExceptionKeepsCause() {
        RuntimeException cause = new RuntimeException("连接断开");
        SystemException exception = new SystemException("查询失败", cause);

        assertThat(exception.getMessage()).isEqualTo("查询失败");
        assertThat(exception.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("幂等重放异常：码固定 10501、属业务异常")
    void idempotentReplayIsBusinessException() {
        IdempotentReplayException exception = new IdempotentReplayException();

        assertThat(exception).isInstanceOf(BusinessException.class);
        assertThat(exception.getCode()).isEqualTo(ErrorCode.IDEMPOTENT_REPLAY.getCode());
        assertThat(exception.getCode()).isEqualTo(10501);
    }
}
