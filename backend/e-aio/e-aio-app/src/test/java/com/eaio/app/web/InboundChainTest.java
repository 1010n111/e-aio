package com.eaio.app.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 入站链路竖切（P0 册 3.3/3.4）：真实 HTTP 处理（MockMvc 独立装配）→ 统一返回体出站。
 *
 * <p>覆盖：统一 POST 入口、traceId 生成/透传与回填、异常映射（业务/校验/报文/兜底）、响应回填。
 *
 * <p>用 {@code standaloneSetup} 而不是 {@code @WebMvcTest}：Boot 4 把 Web MVC 测试切片
 * 拆到独立模块（{@code spring-boot-webmvc-test-autoconfigure}），而本工程当前依赖面以 starter 为限；
 * 独立装配能显式列出本票真正要验证的四个组件，反而不依赖切片自动配置的隐式清单。
 */
class InboundChainTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new DemoController())
                .setControllerAdvice(new ApiResponseAdvice(), new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter())
                .build();
    }

    @Test
    @DisplayName("POST 返回统一返回体，traceId 已回填")
    void postReturnsUnifiedEnvelopeWithTraceId() throws Exception {
        mockMvc.perform(post("/platform/demo/Echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("成功"))
                .andExpect(jsonPath("$.data.message").value("hello"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.data.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("请求头透传的 traceId 优先于生成值（网关链路不断）")
    void traceIdFromHeaderIsPropagated() throws Exception {
        mockMvc.perform(post("/platform/demo/Echo")
                        .header(TraceIdFilter.HEADER, "trace-from-gateway")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value("trace-from-gateway"))
                .andExpect(jsonPath("$.data.traceId").value("trace-from-gateway"));
    }

    @Test
    @DisplayName("业务异常：返回业务错误码与消息，且不含堆栈")
    void businessExceptionMapped() throws Exception {
        mockMvc.perform(post("/platform/demo/Fail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10003))
                .andExpect(jsonPath("$.message").value("演示用业务失败"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    @Test
    @DisplayName("未预期异常：兜底为系统错误，内部消息不泄漏")
    void unexpectedExceptionMappedWithoutLeaking() throws Exception {
        mockMvc.perform(post("/platform/demo/Crash"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10500))
                .andExpect(jsonPath("$.message").value("系统内部错误"))
                .andExpect(jsonPath("$.message").value(Matchers.not(Matchers.containsString("演示用内部异常"))));
    }

    @Test
    @DisplayName("参数校验失败：映射为参数校验失败，且不泄漏校验细节")
    void validationFailureMapped() throws Exception {
        mockMvc.perform(post("/platform/demo/Validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10000))
                .andExpect(jsonPath("$.message").value("参数校验失败"));
    }

    @Test
    @DisplayName("报文不可读：非法 JSON 映射为参数校验失败，而不是 500")
    void unreadableMessageMapped() throws Exception {
        mockMvc.perform(post("/platform/demo/Echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10000))
                .andExpect(jsonPath("$.message").value("参数校验失败"));
    }

    @Test
    @DisplayName("缺少请求体：映射为参数校验失败（HTTP 仍为 200，ADR-0001）")
    void missingBodyMapped() throws Exception {
        mockMvc.perform(post("/platform/demo/Echo").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10000));
    }
}

