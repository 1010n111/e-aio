package com.eaio.platform.infrastructure.web;

import com.eaio.common.api.PageResult;
import com.eaio.platform.api.dto.EventDeliveryDTO;
import com.eaio.platform.api.dto.EventDeliveryQuery;
import com.eaio.platform.api.dto.EventIdCmd;
import com.eaio.platform.application.event.EventDeliveryAppService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 事件投递 REST 入口（P1 册 5.2 的 {@code /platform/eventDelivery} 3 个端点）。
 *
 * <p>与其它 platform 控制器同一契约（裁决 P1-C1：控制器放 {@code infrastructure/web}，
 * 与 {@code application} 的服务共用实现）：
 * <ul>
 *   <li>全部 POST + JSON，路径省略 context-path（{@code /api}）；</li>
 *   <li>不自己拼返回体：出站由应用壳的 {@code ApiResponseAdvice} 统一包成 {@code Result<T>}；</li>
 *   <li>权限点与 7.3 **逐字一致**：{@code platform:eventDelivery:list} / {@code platform:eventDelivery:replay}。</li>
 * </ul>
 *
 * <p><b>幂等键</b>（5.6）：{@code Replay} 是写动作，必须带 {@code Idempotency-Key}（缺键由入站链路的
 * {@code IdempotencyFilter} 拦下，控制器不重复判断）；{@code GetPage}/{@code Get} 是查询，不要求。
 * 死信的 UI 归 T13/告警能力的票——本票只交端点，不写页面。
 */
@RestController
public class EventDeliveryController {

    private final EventDeliveryAppService service;

    public EventDeliveryController(EventDeliveryAppService service) {
        this.service = service;
    }

    /** 投递记录分页（死信排障主入口；{@code status=DEAD} 即死信列表）。 */
    @PostMapping("/platform/eventDelivery/GetPage")
    @PreAuthorize("hasAuthority('platform:eventDelivery:list')")
    public PageResult<EventDeliveryDTO> getPage(@RequestBody(required = false) EventDeliveryQuery query) {
        return service.page(query);
    }

    /** 单条投递记录；不存在抛 20070。 */
    @PostMapping("/platform/eventDelivery/Get")
    @PreAuthorize("hasAuthority('platform:eventDelivery:list')")
    public EventDeliveryDTO get(@Valid @RequestBody EventIdCmd cmd) {
        return service.byEventId(cmd.eventId());
    }

    /** 人工重放一条死信：回 {@code RETRYING}、{@code attempt = 1}、{@code last_error} 追加一次；不存在 20070、状态不允许 20071。 */
    @PostMapping("/platform/eventDelivery/Replay")
    @PreAuthorize("hasAuthority('platform:eventDelivery:replay')")
    public EventDeliveryDTO replay(@Valid @RequestBody EventIdCmd cmd) {
        return service.replay(cmd.eventId());
    }
}
