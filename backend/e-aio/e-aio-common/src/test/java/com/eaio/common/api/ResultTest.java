package com.eaio.common.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 统一返回体契约（P0 册 3.2.1）。 */
class ResultTest {

    @Test
    @DisplayName("成功：code=0、带数据、消息取成功码默认消息")
    void okCarriesDataAndSuccessMessage() {
        Result<String> result = Result.ok("payload");

        assertThat(result.getCode()).isZero();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo(ErrorCode.SUCCESS.getMessage());
        assertThat(result.getData()).isEqualTo("payload");
        assertThat(result.getTraceId()).isNull();
    }

    @Test
    @DisplayName("成功：允许自定义消息；无数据版本 data 为空")
    void okSupportsCustomMessageAndEmptyPayload() {
        assertThat(Result.ok("payload", "已保存").getMessage()).isEqualTo("已保存");

        Result<Void> empty = Result.ok();
        assertThat(empty.isSuccess()).isTrue();
        assertThat(empty.getData()).isNull();
    }

    @Test
    @DisplayName("失败：显式错误码，data 为空且 isSuccess=false")
    void failWithExplicitCode() {
        Result<String> result = Result.fail(10500, "系统内部错误");

        assertThat(result.getCode()).isEqualTo(10500);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).isEqualTo("系统内部错误");
        assertThat(result.getData()).isNull();
    }

    @Test
    @DisplayName("失败：可直接使用错误码契约")
    void failFromErrorCode() {
        Result<Void> result = Result.fail(ErrorCode.DATA_NOT_FOUND);

        assertThat(result.getCode()).isEqualTo(ErrorCode.DATA_NOT_FOUND.getCode());
        assertThat(result.getMessage()).isEqualTo(ErrorCode.DATA_NOT_FOUND.getMessage());
    }

    @Test
    @DisplayName("失败：错误码契约不可为空（防止空指针变成 0 成功）")
    void failRejectsNullErrorCode() {
        assertThatThrownBy(() -> Result.fail((BusinessErrorCode) null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("traceId 可由入站链路回填")
    void traceIdIsSettable() {
        Result<Void> result = Result.ok();
        result.setTraceId("trace-1");

        assertThat(result.getTraceId()).isEqualTo("trace-1");
    }
}
