package com.eaio.app.web;

import com.eaio.common.api.ErrorCode;
import com.eaio.common.api.Result;
import com.eaio.common.exception.BusinessException;
import com.eaio.common.exception.IdempotencyUnavailableException;
import com.eaio.common.exception.SystemException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理（P0 册 3.3）：把各类失败统一映射为约定错误码，**不向外泄漏堆栈**。
 *
 * <p>三条口径：
 * <ul>
 *   <li>HTTP 恒 200：业务结果由响应体 {@code code} 表达（ADR-0001）；</li>
 *   <li>可预期失败（业务/校验/缺参/报文）用 WARN 或 DEBUG，程序缺陷用 ERROR 并只在日志里带堆栈；</li>
 *   <li>对外消息用错误码的固定文案，不把内部异常消息透给调用方（可能含表名、路径等内部信息）。</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务失败：可预期，消息是写给用户看的，原样返回。 */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e, HttpServletRequest request) {
        log.warn("业务失败：code={} uri={} message={}", e.getCode(), request.getRequestURI(), e.getMessage());
        return fail(e.getCode(), e.getMessage());
    }

    /** 演示接口的业务失败（P1 有真实业务模块后由模块异常替换，见 DemoController 说明）。 */
    @ExceptionHandler(DemoController.DemoBusinessException.class)
    public Result<Void> handleDemoBusiness(DemoController.DemoBusinessException e, HttpServletRequest request) {
        ErrorCode errorCode = DemoController.demoErrorCode();
        log.warn("业务失败：code={} uri={} message={}", errorCode.getCode(), request.getRequestURI(), e.getMessage());
        return fail(errorCode.getCode(), e.getMessage());
    }

    /** 系统异常：消息可能含内部细节，对外只给固定文案，堆栈进日志。 */
    @ExceptionHandler(SystemException.class)
    public Result<Void> handleSystem(SystemException e, HttpServletRequest request) {
        log.error("系统异常：uri={}", request.getRequestURI(), e);
        return fail(ErrorCode.SYSTEM_ERROR.getCode(), ErrorCode.SYSTEM_ERROR.getMessage());
    }

    @ExceptionHandler(IdempotencyUnavailableException.class)
    public Result<Void> handleIdempotencyUnavailable(IdempotencyUnavailableException e, HttpServletRequest request) {
        log.error("幂等校验不可用：uri={}", request.getRequestURI(), e);
        return fail(ErrorCode.IDEMPOTENCY_UNAVAILABLE.getCode(), ErrorCode.IDEMPOTENCY_UNAVAILABLE.getMessage());
    }

    /** 参数校验失败（@Valid / @Validated）。 */
    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    public Result<Void> handleValidation(Exception e, HttpServletRequest request) {
        log.debug("参数校验失败：uri={} message={}", request.getRequestURI(), e.getMessage());
        return fail(ErrorCode.PARAM_INVALID.getCode(), ErrorCode.PARAM_INVALID.getMessage());
    }

    /** 缺少必要参数。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParameter(MissingServletRequestParameterException e,
            HttpServletRequest request) {
        log.debug("缺少必要参数：uri={} name={}", request.getRequestURI(), e.getParameterName());
        return fail(ErrorCode.PARAM_MISSING.getCode(), ErrorCode.PARAM_MISSING.getMessage());
    }

    /** 报文不可读：JSON 格式错误、缺 body 等，归入参数类失败。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleUnreadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        log.debug("报文不可读：uri={} message={}", request.getRequestURI(), e.getMessage());
        return fail(ErrorCode.PARAM_INVALID.getCode(), ErrorCode.PARAM_INVALID.getMessage());
    }

    /** 兜底：未预期的异常一律系统错误，绝不把堆栈或内部消息返回给调用方。 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("未预期异常：uri={}", request.getRequestURI(), e);
        return fail(ErrorCode.SYSTEM_ERROR.getCode(), ErrorCode.SYSTEM_ERROR.getMessage());
    }

    private static Result<Void> fail(int code, String message) {
        Result<Void> result = Result.fail(code, message);
        result.setTraceId(MDC.get(TraceIdFilter.MDC_KEY));
        return result;
    }
}
