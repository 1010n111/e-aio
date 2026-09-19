package com.eaio.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.eaio.common.api.IdempotencyStore;
import com.eaio.common.exception.IdempotencyUnavailableException;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 幂等过滤器口径（P0 册 3.2.5）：缺键放行、重放拦截、失败释放、**存储不可用时 fail-closed**。
 *
 * <p>这里用内存占位与可控的假实现测判定逻辑；真实 Redis 的 SETNX/TTL 行为由 CI 的
 * Testcontainers Redis 集成测试覆盖（本机无 Docker）。
 */
class IdempotencyFilterTest {

    private static final String URI = "/platform/param/Add";
    private static final String KEY = "5f0c1f3e-0f0f-4f0f-8f0f-0f0f0f0f0f0f";

    private final Map<String, Boolean> placeholders = new ConcurrentHashMap<>();
    private final ResponseProbe probe = new ResponseProbe();
    private FilterChain chain;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    /** 成功的下游：只记录是否被调用。 */
    private static final class Downstream implements FilterChain {

        private int calls;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
            calls++;
        }
    }

    private static final class ResponseProbe implements IdempotencyFilter.ResponseWriter {

        private Integer code;

        @Override
        public void write(jakarta.servlet.http.HttpServletResponse response, com.eaio.common.api.ErrorCode errorCode) {
            this.code = errorCode.getCode();
        }
    }

    private IdempotencyStore store(boolean acquireResult) {
        return new IdempotencyStore() {
            @Override
            public boolean acquire(String key) {
                return acquireResult;
            }

            @Override
            public void complete(String key) {
                placeholders.put(key, true);
            }

            @Override
            public void release(String key) {
                placeholders.remove(key);
            }
        };
    }

    @BeforeEach
    void setUp() {
        placeholders.clear();
        probe.code = null;
        chain = new Downstream();
        request = new MockHttpServletRequest("POST", URI);
        response = new MockHttpServletResponse();
    }

    private IdempotencyFilter filter(IdempotencyStore store) {
        return new IdempotencyFilter(store, "test", probe::write);
    }

    @Test
    @DisplayName("业务失败（HTTP 200 但 code≠0）释放占位：同一幂等键可重试，且响应体照常写回")
    void businessFailureReleasesPlaceholder() throws Exception {
        request.addHeader(IdempotencyFilter.KEY_HEADER, KEY);
        FilterChain failing = (req, res) -> {
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"code\":20001,\"message\":\"参数不存在\",\"data\":null}");
        };

        filter(store(true)).doFilter(request, response, failing);

        assertThat(placeholders).as("业务失败不得写成 DONE（否则调用方带着同一个键永远 10501，重试不进来）")
                .isEmpty();
        assertThat(response.getContentAsString()).as("响应体必须写回真实响应").contains("20001");
    }

    @Test
    @DisplayName("业务成功（code=0）改写为 DONE：占位保留，同载荷再次提交被拦")
    void businessSuccessMarksPlaceholderDone() throws Exception {
        request.addHeader(IdempotencyFilter.KEY_HEADER, KEY);
        FilterChain succeeding = (req, res) -> {
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"code\":0,\"message\":\"成功\",\"data\":null}");
        };

        filter(store(true)).doFilter(request, response, succeeding);

        assertThat(placeholders).as("成功记为 DONE").hasSize(1);
        assertThat(response.getContentAsString()).contains("\"code\":0");
    }

    @Test
    @DisplayName("写动作缺少幂等键：返回 10001 且不执行业务（P1 册 5.6 收紧 P0 的“缺键放行”）")
    void missingKeyOnWriteActionIsRejected() throws Exception {
        IdempotencyStore spy = mock(IdempotencyStore.class);
        filter(spy).doFilter(request, response, chain);

        assertThat(probe.code).isEqualTo(10001);
        assertThat(((Downstream) chain).calls).isZero();
        verify(spy, never()).acquire(any());
    }

    @Test
    @DisplayName("读动作（Get*/List*/Download*）不需要幂等键：直接放行且不触碰存储")
    void readActionWithoutKeyPassesThrough() throws Exception {
        IdempotencyStore spy = mock(IdempotencyStore.class);
        MockHttpServletRequest read = new MockHttpServletRequest("POST", "/platform/param/GetPage");

        filter(spy).doFilter(read, response, chain);

        assertThat(((Downstream) chain).calls).isEqualTo(1);
        verify(spy, never()).acquire(any());
        assertThat(probe.code).isNull();
    }

    @Test
    @DisplayName("上传动作（服务端自身幂等）不需要幂等键：直接放行")
    void selfIdempotentUploadWithoutKeyPassesThrough() throws Exception {
        IdempotencyStore spy = mock(IdempotencyStore.class);
        MockHttpServletRequest upload = new MockHttpServletRequest("POST", "/platform/file/UploadChunk");

        filter(spy).doFilter(upload, response, chain);

        assertThat(((Downstream) chain).calls).isEqualTo(1);
        verify(spy, never()).acquire(any());
    }

    @Test
    @DisplayName("超长键按无键处理：不截断（截断会让不同请求撞同一个键），写动作因此被拒")
    void overlongKeyTreatedAsMissing() throws Exception {
        IdempotencyStore spy = mock(IdempotencyStore.class);
        request.addHeader(IdempotencyFilter.KEY_HEADER, "k".repeat(IdempotencyFilter.MAX_KEY_LENGTH + 1));

        filter(spy).doFilter(request, response, chain);

        assertThat(probe.code).isEqualTo(10001);
        assertThat(((Downstream) chain).calls).isZero();
        verify(spy, never()).acquire(any());
    }

    @Test
    @DisplayName("非 POST 请求不进幂等链路")
    void nonPostNotFiltered() throws Exception {
        IdempotencyStore spy = mock(IdempotencyStore.class);
        IdempotencyFilter idempotencyFilter = filter(spy);
        MockHttpServletRequest get = new MockHttpServletRequest("GET", URI);
        get.addHeader(IdempotencyFilter.KEY_HEADER, KEY);

        assertThat(idempotencyFilter.shouldNotFilter(get)).isTrue();
    }

    @Test
    @DisplayName("占位失败（执行中或已完成）：返回重复提交且不执行业务")
    void duplicateSubmissionNeverExecutesBusiness() throws Exception {
        request.addHeader(IdempotencyFilter.KEY_HEADER, KEY);

        filter(store(false)).doFilter(request, response, chain);

        assertThat(probe.code).isEqualTo(10501);
        assertThat(((Downstream) chain).calls).isZero();
    }

    @Test
    @DisplayName("X-Idempotency-Key 与 Idempotency-Key 等价（前端用 X- 前缀）")
    void xPrefixedHeaderAccepted() throws Exception {
        request.addHeader(IdempotencyFilter.KEY_HEADER_ALIAS, KEY);

        filter(store(false)).doFilter(request, response, chain);

        assertThat(probe.code).isEqualTo(10501);
        assertThat(((Downstream) chain).calls).isZero();
    }

    @Test
    @DisplayName("占位存储不可用：fail-closed —— 返回 10502 且业务逻辑绝不执行")
    void unavailableStoreFailsClosed() throws Exception {
        IdempotencyStore store = mock(IdempotencyStore.class);
        willThrow(new IdempotencyUnavailableException("redis down", new RuntimeException("connect refused")))
                .given(store).acquire(any());
        request.addHeader(IdempotencyFilter.KEY_HEADER, KEY);

        filter(store).doFilter(request, response, chain);

        assertThat(probe.code).isEqualTo(10502);
        assertThat(((Downstream) chain).calls).isZero();
    }

    @Test
    @DisplayName("业务成功：占位改写为已完成（同一键后续重放都不再执行）")
    void successMarksDone() throws Exception {
        request.addHeader(IdempotencyFilter.KEY_HEADER, KEY);
        IdempotencyFilter idempotencyFilter = filter(store(true));

        idempotencyFilter.doFilter(request, response, chain);

        assertThat(((Downstream) chain).calls).isEqualTo(1);
        assertThat(placeholders).containsEntry(idempotencyFilter.placeholderOf(URI, KEY), true);
    }

    @Test
    @DisplayName("业务失败（HTTP 4xx/5xx）：立即释放占位，允许修正后重试")
    void failureReleasesPlaceholder() throws Exception {
        request.addHeader(IdempotencyFilter.KEY_HEADER, KEY);
        IdempotencyFilter idempotencyFilter = filter(store(true));
        // chain 收到的是 ContentCachingResponseWrapper（过滤器要读响应体判业务成败），故按 HttpServletResponse 设状态
        FilterChain failing = (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(500);

        idempotencyFilter.doFilter(request, response, failing);

        assertThat(placeholders).doesNotContainKey(idempotencyFilter.placeholderOf(URI, KEY));
    }

    @Test
    @DisplayName("下游抛异常：释放占位并继续抛出（业务异常由全局处理器负责返回体）")
    void downstreamExceptionReleasesPlaceholder() {
        request.addHeader(IdempotencyFilter.KEY_HEADER, KEY);
        IdempotencyFilter idempotencyFilter = filter(store(true));
        FilterChain throwing = (req, res) -> {
            throw new IllegalStateException("boom");
        };

        assertThatThrownBy(() -> idempotencyFilter.doFilter(request, response, throwing))
                .isInstanceOf(IllegalStateException.class);
        assertThat(placeholders).doesNotContainKey(idempotencyFilter.placeholderOf(URI, KEY));
    }

    @Test
    @DisplayName("占位键按约定构造：eaio:{env}:idem:{sha256(URI+key)}")
    void placeholderKeyFormat() {
        IdempotencyFilter idempotencyFilter = filter(store(true));

        String placeholder = idempotencyFilter.placeholderOf(URI, KEY);

        assertThat(placeholder).startsWith("eaio:test:idem:")
                .hasSize("eaio:test:idem:".length() + 64)
                .matches("eaio:test:idem:[0-9a-f]{64}");
        // 同一请求同键稳定，不同键不撞
        assertThat(idempotencyFilter.placeholderOf(URI, KEY)).isEqualTo(placeholder);
        assertThat(idempotencyFilter.placeholderOf(URI, "other-key")).isNotEqualTo(placeholder);
        assertThat(idempotencyFilter.placeholderOf("/platform/demo/Other", KEY)).isNotEqualTo(placeholder);
    }

    @Test
    @DisplayName("占位 TTL 口径：执行中 10 分钟、已完成 24 小时")
    void ttlContract() {
        assertThat(IdempotencyStore.PROCESSING_TTL).isEqualTo(Duration.ofMinutes(10));
        assertThat(IdempotencyStore.DONE_TTL).isEqualTo(Duration.ofHours(24));
    }
}


