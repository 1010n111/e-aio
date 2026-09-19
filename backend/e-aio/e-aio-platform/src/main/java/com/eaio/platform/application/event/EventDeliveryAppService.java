package com.eaio.platform.application.event;

import java.time.Instant;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.eaio.common.api.PageResult;
import com.eaio.common.exception.BusinessException;
import com.eaio.platform.api.PlatformErrorCode;
import com.eaio.platform.api.dto.EventDeliveryDTO;
import com.eaio.platform.api.dto.EventDeliveryQuery;
import com.eaio.platform.application.param.ParamContextProvider;
import com.eaio.platform.domain.event.EventDelivery;
import com.eaio.platform.domain.event.EventDeliveryStatus;
import com.eaio.platform.infrastructure.persistence.EventDeliveryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 事件投递记录应用服务（P1 册 5.2 的 {@code /platform/eventDelivery} 三个端点）：死信列表、详情与
 * **人工重放**。
 *
 * <p>REST 与跨模块调用共用同一实现（裁决 2.4.3）。
 *
 * <p><b>错误码只用段内两个既有号</b>（7.1）：
 * <ul>
 *   <li>20070 {@code EVENT_DELIVERY_NOT_FOUND}：{@code eventId} 没有登记行（{@code Get} 与 {@code Replay} 都会遇到）；</li>
 *   <li>20071 {@code EVENT_REPLAY_NOT_ALLOWED}：状态不允许重放——{@code DONE}（已经投递成功）与
 *       {@code RETRYING}（还在重投队列里，重放它等于插队）都拒。</li>
 * </ul>
 *
 * <p><b>重放是"改状态"不是"同步投递"</b>：把行改回 {@code RETRYING}（{@code attempt_count = 1}、
 * 立即到期、{@code last_error} 追加一次），由 {@code platform.event.retry} 处理点在下一轮（≤30s）
 * 真正重投。理由有二——接口不该被监听方的耗时拖住（5.5 约定单监听器 <100ms，但那是约定不是保证），
 * 且"重投"与自动重投必须走同一条代码路径（{@link PlatformEventDispatcher#deliver}），否则两条路径的
 * 落库口径会各自漂移。
 */
@Service
public class EventDeliveryAppService {

    private static final Logger log = LoggerFactory.getLogger(EventDeliveryAppService.class);

    private final EventDeliveryStore deliveries;
    private final EventDeliveryDtoMapper dtoMapper;
    private final ParamContextProvider contexts;

    public EventDeliveryAppService(EventDeliveryStore deliveries, EventDeliveryDtoMapper dtoMapper,
            ParamContextProvider contexts) {
        this.deliveries = deliveries;
        this.dtoMapper = dtoMapper;
        this.contexts = contexts;
    }

    /** 投递记录分页（死信排障主入口，默认按 {@code created_at DESC}）。 */
    public PageResult<EventDeliveryDTO> page(EventDeliveryQuery query) {
        IPage<EventDelivery> page = deliveries.page(query);
        return PageResult.from(page.getCurrent(), page.getSize(), page.getTotal(),
                page.getRecords().stream().map(dtoMapper::toDto).toList());
    }

    /** 单条投递记录；不存在抛 20070。 */
    public EventDeliveryDTO byEventId(String eventId) {
        return dtoMapper.toDto(requireRow(eventId));
    }

    /**
     * 人工重放一条死信（5.2 的 {@code eventDelivery/Replay}）：{@code DEAD → RETRYING}、
     * {@code attempt_count = 1}、{@code last_error} **追加**一次（保留历史）。
     *
     * <p>资格判定在 {@code EventDeliveryMapper.markReplay} 的 {@code WHERE status = 'DEAD'} 上（原子）：
     * 本方法先读一次是为了给得出"为什么不能重放"的可读原因（20071 带状态），写用的仍是条件 UPDATE，
     * 所以并发两次重放只有一次生效，第二次拿到 0 行 → 20071。
     */
    @Transactional
    public EventDeliveryDTO replay(String eventId) {
        EventDelivery row = requireRow(eventId);
        if (!EventDeliveryStatus.DEAD.name().equals(row.getStatus())) {
            throw new BusinessException(PlatformErrorCode.EVENT_REPLAY_NOT_ALLOWED,
                    "该事件状态不允许重放：" + row.getStatus() + "（只有 DEAD 死信可以人工重放）eventId=" + eventId);
        }
        long operator = contexts.current().userId();
        String note = "人工重放：operator=" + operator + " at=" + Instant.now();
        if (!deliveries.markReplay(eventId, note, operator)) {
            throw new BusinessException(PlatformErrorCode.EVENT_REPLAY_NOT_ALLOWED,
                    "该事件状态已变化，重放未生效（可能已被他人重放）：eventId=" + eventId);
        }
        EventDelivery updated = requireRow(eventId);
        log.info("事件已人工重放（回 RETRYING，等待 platform.event.retry 重投）：eventId={} operator={}", eventId,
                operator);
        return dtoMapper.toDto(updated);
    }

    // ---------------------------------------------------------------- 内部

    private EventDelivery requireRow(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            throw new BusinessException(PlatformErrorCode.EVENT_DELIVERY_NOT_FOUND, "eventId 不能为空");
        }
        EventDelivery row = deliveries.rowByEventId(eventId.trim());
        if (row == null) {
            throw new BusinessException(PlatformErrorCode.EVENT_DELIVERY_NOT_FOUND,
                    "事件投递记录不存在：eventId=" + eventId);
        }
        return row;
    }
}
