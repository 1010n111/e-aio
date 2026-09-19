package com.eaio.app.web;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 请求链路标识（P0 册 3.4）：请求头透传或生成 → 日志上下文 → 回填返回体。
 *
 * <p>顺序最先（TraceId 是一切日志的前缀）；返回体的回填由 {@code ApiResponseAdvice} 从 MDC 读取。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** 链路标识请求头 / 响应头名。 */
    public static final String HEADER = "X-Trace-Id";

    /** 日志上下文键。 */
    public static final String MDC_KEY = "traceId";

    /** 请求属性键：供测试与后续组件读取。 */
    public static final String ATTRIBUTE = TraceIdFilter.class.getName() + ".traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = request.getHeader(HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        MDC.put(MDC_KEY, traceId);
        request.setAttribute(ATTRIBUTE, traceId);
        // 回写响应头：网关/前端出问题时能直接拿 traceId 找日志
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
