package com.eaio.app.web;

import com.eaio.common.api.Result;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 响应回填（P0 册 3.4）：控制器只返回业务数据，出站统一包成 {@code Result<T>} 并回填 traceId。
 *
 * <p>约定：控制器**不自己拼返回体**（那样每个接口都要记得填 traceId，且容易与异常处理器的形状不一致）；
 * 已经返回 {@link Result} 的处理器（如全局异常处理）不二次包装。
 */
@RestControllerAdvice
public class ApiResponseAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType, ServerHttpRequest request,
            ServerHttpResponse response) {
        if (body instanceof Result<?>) {
            return body;
        }
        Result<Object> result = Result.ok(body);
        result.setTraceId(MDC.get(TraceIdFilter.MDC_KEY));
        return result;
    }
}
