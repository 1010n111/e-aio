package com.eaio.app.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import com.eaio.common.exception.BusinessException;
import com.eaio.platform.api.PlatformErrorCode;
import com.eaio.platform.api.dto.ParamDTO;
import com.eaio.platform.application.ParamAppService;
import com.eaio.platform.infrastructure.web.ParamController;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 入站链路竖切（P0 册 3.3/3.4）：真实 HTTP 处理（MockMvc 独立装配）→ 统一返回体出站。
 *
 * <p>覆盖：统一 POST 入口、traceId 生成/透传与回填、异常映射（业务/校验/报文/兜底）、响应回填。
 *
 * <p><b>P1 起目标端点是真实业务接口</b>：P0 的 {@code DemoController} 按登记在该接口（{@code
 * /platform/param/Add}）落地时退役了。链路测试因此改为"真实 Controller + 被替身的应用服务"——
 * 链路本身与业务实现无关，替身让"业务异常/未预期异常"能被稳定触发，而不用在业务代码里留演示分支。
 *
 * <p>用 {@code standaloneSetup} 而不是 {@code @WebMvcTest}：Boot 4 把 Web MVC 测试切片
 * 拆到独立模块（{@code spring-boot-webmvc-test-autoconfigure}），而本工程依赖面以 starter 为限；
 * 独立装配能显式列出真正要验证的组件，也不依赖切片自动配置的隐式清单。
 */
@ActiveProfiles("test")
class InboundChainTest {

    private static final String ADD = "/platform/param/Add";

    private ParamAppService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = Mockito.mock(ParamAppService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ParamController(service))
                .setControllerAdvice(new ApiResponseAdvice(), new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter())
                .build();
    }

    @Test
    @DisplayName("POST 返回统一返回体，traceId 已回填")
    void postReturnsUnifiedEnvelopeWithTraceId() throws Exception {
        when(service.add(any())).thenReturn(new ParamDTO(1L, "platform.file.max-size", "SYSTEM", 0L, "1024",
                "INT", "file", false, false, true, 0, Instant.parse("2026-01-01T00:00:00Z")));

        mockMvc.perform(post(ADD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paramKey\":\"platform.file.max-size\",\"paramLevel\":\"SYSTEM\","
                                + "\"ownerId\":0,\"paramValue\":\"1024\",\"valueType\":\"INT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("成功"))
                .andExpect(jsonPath("$.data.paramKey").value("platform.file.max-size"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("请求头透传的 traceId 优先于生成值（网关链路不断）")
    void traceIdFromHeaderIsPropagated() throws Exception {
        when(service.add(any())).thenReturn(new ParamDTO(1L, "k", "SYSTEM", 0L, "v", "STRING", "default",
                false, false, true, 0, null));

        mockMvc.perform(post(ADD)
                        .header(TraceIdFilter.HEADER, "trace-from-gateway")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paramKey\":\"k\",\"paramLevel\":\"SYSTEM\",\"ownerId\":0,"
                                + "\"paramValue\":\"v\",\"valueType\":\"STRING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.traceId").value("trace-from-gateway"));
    }

    @Test
    @DisplayName("业务异常：返回平台业务错误码与消息，且不含堆栈")
    void businessExceptionMapped() throws Exception {
        when(service.add(any()))
                .thenThrow(new BusinessException(PlatformErrorCode.PARAM_SCOPE_INVALID, "SYSTEM 级 ownerId 必须为 0"));

        mockMvc.perform(post(ADD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paramKey\":\"k\",\"paramLevel\":\"SYSTEM\",\"ownerId\":7,"
                                + "\"paramValue\":\"v\",\"valueType\":\"STRING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(20006))
                .andExpect(jsonPath("$.message").value("SYSTEM 级 ownerId 必须为 0"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    @Test
    @DisplayName("未预期异常：兜底为系统错误，内部消息不泄漏")
    void unexpectedExceptionMappedWithoutLeaking() throws Exception {
        when(service.add(any())).thenThrow(new IllegalStateException("内部实现细节：连接串 jdbc:postgresql://..."));

        mockMvc.perform(post(ADD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paramKey\":\"k\",\"paramLevel\":\"SYSTEM\",\"ownerId\":0,"
                                + "\"paramValue\":\"v\",\"valueType\":\"STRING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10500))
                .andExpect(jsonPath("$.message").value("系统内部错误"))
                .andExpect(jsonPath("$.message").value(Matchers.not(Matchers.containsString("jdbc:postgresql"))));
    }

    @Test
    @DisplayName("参数校验失败：映射为参数校验失败，且不泄漏校验细节")
    void validationFailureMapped() throws Exception {
        mockMvc.perform(post(ADD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paramKey\":\"\",\"paramLevel\":\"SYSTEM\",\"paramValue\":\"v\","
                                + "\"valueType\":\"STRING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10000))
                .andExpect(jsonPath("$.message").value("参数校验失败"));
    }

    @Test
    @DisplayName("报文不可读：非法 JSON 映射为参数校验失败，而不是 500")
    void unreadableMessageMapped() throws Exception {
        mockMvc.perform(post(ADD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10000))
                .andExpect(jsonPath("$.message").value("参数校验失败"));
    }

    @Test
    @DisplayName("缺少请求体：映射为参数校验失败（HTTP 仍为 200，ADR-0001）")
    void missingBodyMapped() throws Exception {
        mockMvc.perform(post(ADD).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10000));
    }
}
