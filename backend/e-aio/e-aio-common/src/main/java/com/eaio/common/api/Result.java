package com.eaio.common.api;

import java.util.Objects;

/**
 * 统一响应体（契约 V1，P0 册 3.2.1）。
 *
 * <p>HTTP 状态码恒为 200，业务结果全部由 {@code code} 表达（ADR-0001）；
 * {@code traceId} 由入站链路从日志上下文回填，此处只承载。
 *
 * @param <T> 业务数据类型
 */
public class Result<T> {

    /** 业务错误码，0 表示成功。 */
    private int code;

    /** 提示消息。 */
    private String message;

    /** 业务数据。 */
    private T data;

    /** 链路追踪 ID，由入站链路回填。 */
    private String traceId;

    public Result() {
    }

    public Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /** 成功：携带数据，消息取成功码的默认消息。 */
    public static <T> Result<T> ok(T data) {
        return ok(data, ErrorCode.SUCCESS.getMessage());
    }

    /** 成功：携带数据与自定义消息。 */
    public static <T> Result<T> ok(T data, String message) {
        return new Result<>(ErrorCode.SUCCESS_CODE, message, data);
    }

    /** 成功：无数据。 */
    public static <T> Result<T> ok() {
        return ok(null);
    }

    /** 失败：直接给出错误码与消息。 */
    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }

    /** 失败：使用错误码契约的码与消息。 */
    public static <T> Result<T> fail(BusinessErrorCode errorCode) {
        Objects.requireNonNull(errorCode, "errorCode 不能为空");
        return fail(errorCode.getCode(), errorCode.getMessage());
    }

    /** 是否成功。 */
    public boolean isSuccess() {
        return code == ErrorCode.SUCCESS_CODE;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }
}
