package com.eaio.platform.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import com.eaio.common.api.PageResult;
import com.eaio.platform.api.dto.EventDeliveryDTO;
import com.eaio.platform.application.event.EventDeliveryAppService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * {@link EventDeliveryController} 的层测试（P1 册 5.2 的三个端点 + 7.3 的权限点逐字比对）。
 *
 * <p>两件事分开证：
 * <ol>
 *   <li><b>路由与出参</b>（{@code MockMvc} standalone）：路径、POST、{@code {eventId}} 入参、
 *       重放出参的 {@code status/attemptCount/lastError} 形状；</li>
 *   <li><b>权限点字符串</b>（反射读注解）：{@code PermissionCodeContractTest} 依赖的就是这两个字符串与
 *       7.3 逐字一致，写错一个字符不会报错、只会永远无权限，因此用断言钉死。</li>
 * </ol>
 *
 * <p>{@code Replay} 是写动作 → 必须带 {@code Idempotency-Key}（5.6）：该判定在入站链路的
 * {@code IdempotencyFilter}（应用壳）里，不在控制器——控制器只声明它是 {@code @PostMapping} 写端点，
 * 集成层面的幂等行为由应用壳的测试与 {@code EventRetryIT} 覆盖。
 */
class EventDeliveryControllerTest {

    private final EventDeliveryAppService service = mock(EventDeliveryAppService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new EventDeliveryController(service)).build();
    }

    @Test
    @DisplayName("GetPage：POST /platform/eventDelivery/GetPage → PageResult（含 records）")
    void getPageReturnsPageResult() throws Exception {
        given(service.page(any())).willReturn(PageResult.from(1, 20, 1, List.of(dto("evt-1", "DEAD", 5))));

        mockMvc.perform(post("/platform/eventDelivery/GetPage").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DEAD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.records[0].eventId").value("evt-1"))
                .andExpect(jsonPath("$.records[0].status").value("DEAD"));
    }

    @Test
    @DisplayName("GetPage：请求体可以完全为空（查询入参全可选）")
    void getPageAcceptsEmptyBody() throws Exception {
        given(service.page(any())).willReturn(PageResult.from(1, 20, 0, List.of()));

        mockMvc.perform(post("/platform/eventDelivery/GetPage")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Get：按 eventId 取详情 → DTO")
    void getReturnsDto() throws Exception {
        given(service.byEventId("evt-1")).willReturn(dto("evt-1", "DONE", 1));

        mockMvc.perform(post("/platform/eventDelivery/Get").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"evt-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-1"))
                .andExpect(jsonPath("$.status").value("DONE"));

        verify(service).byEventId("evt-1");
    }

    @Test
    @DisplayName("Replay：出参是重放后的行（RETRYING/attempt=1/last_error 保留历史）")
    void replayReturnsReplayedRow() throws Exception {
        EventDeliveryDTO replayed = new EventDeliveryDTO("evt-dead", "com.eaio.platform.events.ParamChangedEvent",
                "RETRYING", 1, 5, Instant.parse("2026-09-19T10:05:00Z"),
                "第 5 次投递失败：boom\n人工重放：operator=0", "t-1",
                Instant.parse("2026-09-19T10:00:00Z"), null, "{}");
        given(service.replay("evt-dead")).willReturn(replayed);

        mockMvc.perform(post("/platform/eventDelivery/Replay").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"evt-dead\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETRYING"))
                .andExpect(jsonPath("$.attemptCount").value(1))
                .andExpect(jsonPath("$.lastError").value("第 5 次投递失败：boom\n人工重放：operator=0"))
                .andExpect(jsonPath("$.finishTime").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("权限点与 7.3 逐字一致：list × 2（查询不要求幂等键）+ replay × 1（写动作）")
    void permissionStringsMatchDesign() throws Exception {
        assertThat(preAuthorizeOf("getPage")).isEqualTo("hasAuthority('platform:eventDelivery:list')");
        assertThat(preAuthorizeOf("get")).isEqualTo("hasAuthority('platform:eventDelivery:list')");
        assertThat(preAuthorizeOf("replay")).isEqualTo("hasAuthority('platform:eventDelivery:replay')");
    }

    @Test
    @DisplayName("三个端点都是 POST + JSON，路径与 5.2 逐字一致")
    void endpointsArePostWithDesignPaths() throws Exception {
        assertThat(postPathOf("getPage")).isEqualTo("/platform/eventDelivery/GetPage");
        assertThat(postPathOf("get")).isEqualTo("/platform/eventDelivery/Get");
        assertThat(postPathOf("replay")).isEqualTo("/platform/eventDelivery/Replay");
    }

    // ---------------------------------------------------------------- 辅助

    private static String preAuthorizeOf(String methodName) throws Exception {
        Method method = methodOf(methodName);
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        assertThat(annotation).as("端点 %s 必须带 @PreAuthorize（7.3 逐字）", methodName).isNotNull();
        return annotation.value();
    }

    private static String postPathOf(String methodName) throws Exception {
        Method method = methodOf(methodName);
        PostMapping annotation = method.getAnnotation(PostMapping.class);
        assertThat(annotation).as("端点 %s 必须是 @PostMapping", methodName).isNotNull();
        return annotation.value()[0];
    }

    private static Method methodOf(String methodName) throws NoSuchMethodException {
        for (Method method : EventDeliveryController.class.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                return method;
            }
        }
        throw new NoSuchMethodException(methodName);
    }

    private static EventDeliveryDTO dto(String eventId, String status, int attempt) {
        return new EventDeliveryDTO(eventId, "com.eaio.platform.events.ParamChangedEvent", status, attempt, 5, null,
                null, "t-1", Instant.parse("2026-09-19T10:00:00Z"), null, "{}");
    }
}
