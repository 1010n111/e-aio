package com.eaio.app.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.eaio.common.api.ErrorCode;
import com.eaio.common.api.IdempotencyStore;
import com.eaio.common.exception.IdempotencyUnavailableException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 幂等键拦截（P0 册 3.2.5）。
 *
 * <p>流程：SETNX 占位 → 已有占位则返回 {@code 10501}（执行中与已完成**同样**返回重复提交，
 * 不回放历史结果）→ 业务成功改写为 DONE → 业务失败立即释放占位。
 *
 * <p>三条硬口径：
 * <ul>
 *   <li>**fail-closed**：占位存储**已配置但不可用**时返回 {@code 10502} 且不执行业务，绝不静默放行；</li>
 *   <li>缺键放行 + WARN（P0 无认证、无写接口，先让链路可观测；强制在 P1 随真实写接口收紧）；</li>
 *   <li>判定依据是响应体里的 {@code code}，不是 HTTP 状态码（ADR-0001）。</li>
 * </ul>
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class IdempotencyFilter extends OncePerRequestFilter {

    /** 幂等键请求头（后端口径）。 */
    public static final String KEY_HEADER = "Idempotency-Key";

    /** 幂等键请求头（前端 `@/api/codes` 用的 X- 前缀，一并接受以免前后端对不上）。 */
    public static final String KEY_HEADER_ALIAS = "X-Idempotency-Key";

    /** 键长上限：超长键不截断（截断会让不同请求撞键），按"无键"处理。 */
    public static final int MAX_KEY_LENGTH = 200;

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

    private final IdempotencyStore store;
    private final String environment;
    private final ResponseWriter responseWriter;

    public IdempotencyFilter(IdempotencyStore store, String environment, ResponseWriter responseWriter) {
        this.store = store;
        this.environment = environment;
        this.responseWriter = responseWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 只拦写动作：GET/PUT/DELETE 等不进入幂等链路（本工程接口统一 POST，写读由键区分）
        return !"POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = keyOf(request);
        if (key == null) {
            log.warn("写请求缺少 {} 请求头，按普通请求放行：{}（调用方应在用户动作发生时生成一次幂等键）",
                    KEY_HEADER, request.getRequestURI());
            chain.doFilter(request, response);
            return;
        }

        String placeholder = placeholderOf(request.getRequestURI(), key);

        boolean acquired;
        try {
            acquired = store.acquire(placeholder);
        } catch (IdempotencyUnavailableException e) {
            // fail-closed：无法判定是否重复时拒绝执行，业务逻辑绝不进入
            log.error("幂等校验不可用，拒绝执行：uri={} reason={}", request.getRequestURI(), e.getMessage());
            responseWriter.write(response, ErrorCode.IDEMPOTENCY_UNAVAILABLE);
            return;
        }

        if (!acquired) {
            // 执行中（首个请求仍在跑）与已完成（DONE）在这里走同一条路径
            log.warn("重复提交被拦截：uri={}", request.getRequestURI());
            responseWriter.write(response, ErrorCode.IDEMPOTENT_REPLAY);
            return;
        }

        boolean failed;
        try {
            chain.doFilter(request, response);
            failed = response.getStatus() >= HttpServletResponse.SC_BAD_REQUEST;
        } catch (ServletException | IOException | RuntimeException e) {
            release(placeholder, request);
            throw e;
        }

        if (failed) {
            release(placeholder, request);
        } else {
            store.complete(placeholder);
        }
    }

    /** 占位键：`eaio:{env}:idem:{sha256(URL+key)}`（P0 册 3.2.5）。 */
    String placeholderOf(String uri, String key) {
        return "eaio:" + environment + ":idem:" + sha256(uri + key);
    }

    /** 释放占位：释放本身失败不改变已决定的结果，但要留下 ERROR 让运维看到。 */
    private void release(String placeholder, HttpServletRequest request) {
        try {
            store.release(placeholder);
        } catch (IdempotencyUnavailableException e) {
            log.error("释放幂等占位失败（占位将保留到 TTL 到期）：uri={}", request.getRequestURI(), e);
        }
    }

    private static String keyOf(HttpServletRequest request) {
        String key = request.getHeader(KEY_HEADER);
        if (key == null || key.isBlank()) {
            key = request.getHeader(KEY_HEADER_ALIAS);
        }
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            return null;
        }
        return key;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
    }

    /** 写统一返回体；由装配处注入，避免本类耦合 JSON 实现。 */
    @FunctionalInterface
    public interface ResponseWriter {
        void write(HttpServletResponse response, ErrorCode errorCode) throws IOException;
    }
}
