package com.eaio.platform.application.event;

import com.eaio.platform.api.dto.EventDeliveryDTO;
import com.eaio.platform.domain.event.EventDelivery;
import org.springframework.stereotype.Component;

/**
 * 投递记录实体 → DTO 映射（P1 册 5.3）。
 *
 * <p>手写而非 MapStruct（与 {@code JobDtoMapper} 同一先例，裁决 P1-C3 的例外）：{@code payloadPreview}
 * 不是字段搬运，是"截断到 1000 字符"的安全变换——用 MapStruct 只能靠 {@code expression} 硬写，
 * 而"预览到底怎么截"恰恰是最需要一眼看懂的一段。
 */
@Component
public class EventDeliveryDtoMapper {

    /** 载荷预览上限（5.3 逐字：≤1000 字符）。 */
    static final int PREVIEW_MAX = 1000;

    /** 投递行 → DTO；{@code payloadPreview} 超长截断（完整载荷不出接口）。 */
    public EventDeliveryDTO toDto(EventDelivery row) {
        return new EventDeliveryDTO(
                row.getEventId(),
                row.getEventType(),
                row.getStatus(),
                row.getAttemptCount() == null ? 0 : row.getAttemptCount(),
                row.getMaxAttempt() == null ? 0 : row.getMaxAttempt(),
                row.getNextRetryTime(),
                row.getLastError(),
                row.getTraceId(),
                row.getCreatedAt(),
                row.getFinishTime(),
                preview(row.getPayloadJson()));
    }

    /** 载荷预览：{@code null}/空 → {@code null}；超 1000 字符截断（不加省略号，长度上限即约束本身）。 */
    static String preview(String payloadJson) {
        if (payloadJson == null || payloadJson.isEmpty()) {
            return payloadJson;
        }
        return payloadJson.length() <= PREVIEW_MAX ? payloadJson : payloadJson.substring(0, PREVIEW_MAX);
    }
}
