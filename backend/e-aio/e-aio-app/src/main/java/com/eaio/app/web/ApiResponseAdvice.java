package com.eaio.app.web;

import com.eaio.common.api.Result;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.core.io.Resource;
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
 *
 * <p><b>二进制响应的旁路</b>：{@link Resource}（文件/Excel 结果下载）**必须原样透传**——把它包成
 * {@code Result} 会同时毁掉两件事：响应体不再是文件内容（变成 JSON 字符串），且 {@code Content-Type}
 * 会被改成 JSON，前端按"Content-Type 是不是 JSON"分辨成功/失败（P1-3 册 3.10.4）的判据随之失效。
 * 下载接口的响应头（{@code Content-Disposition}/{@code nosniff}/{@code no-store}）由各自的控制器设置。
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
        if (body instanceof Result<?> || body instanceof Resource) {
            return body;
        }
        Result<Object> result = Result.ok(body);
        result.setTraceId(MDC.get(TraceIdFilter.MDC_KEY));
        return result;
    }
}
