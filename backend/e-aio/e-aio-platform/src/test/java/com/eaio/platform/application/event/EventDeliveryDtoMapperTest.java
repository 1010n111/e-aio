package com.eaio.platform.application.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import com.eaio.platform.domain.event.EventDelivery;
import com.eaio.platform.events.ParamChangedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link EventDeliveryDtoMapper} 的单测（P1 册 5.3 的 {@code EventDeliveryDTO} 字段与
 * {@code payloadPreview} ≤1000 字符的安全口径）。
 */
class EventDeliveryDtoMapperTest {

    private final EventDeliveryDtoMapper mapper = new EventDeliveryDtoMapper();

    @Test
    @DisplayName("字段逐条映射：eventId/eventType/status/attemptCount/maxAttempt/nextRetryTime/lastError/traceId/createdAt/finishTime")
    void mapsAllFields() {
        Instant createdAt = Instant.parse("2026-09-19T10:00:00Z");
        Instant finishTime = Instant.parse("2026-09-19T10:00:30Z");
        Instant nextRetry = Instant.parse("2026-09-19T10:01:00Z");
        EventDelivery row = new EventDelivery();
        row.setEventId("evt-1");
        row.setEventType(ParamChangedEvent.class.getName());
        row.setStatus("RETRYING");
        row.setAttemptCount(2);
        row.setMaxAttempt(5);
        row.setNextRetryTime(nextRetry);
        row.setLastError("第 1 次投递失败：boom");
        row.setTraceId("t-1");
        row.setCreatedAt(createdAt);
        row.setFinishTime(finishTime);
        row.setPayloadJson("{\"eventId\":\"evt-1\"}");

        var dto = mapper.toDto(row);

        assertThat(dto.eventId()).isEqualTo("evt-1");
        assertThat(dto.eventType()).isEqualTo(ParamChangedEvent.class.getName());
        assertThat(dto.status()).isEqualTo("RETRYING");
        assertThat(dto.attemptCount()).isEqualTo(2);
        assertThat(dto.maxAttempt()).isEqualTo(5);
        assertThat(dto.nextRetryTime()).isEqualTo(nextRetry);
        assertThat(dto.lastError()).isEqualTo("第 1 次投递失败：boom");
        assertThat(dto.traceId()).isEqualTo("t-1");
        assertThat(dto.createdAt()).isEqualTo(createdAt);
        assertThat(dto.finishTime()).isEqualTo(finishTime);
        assertThat(dto.payloadPreview()).isEqualTo("{\"eventId\":\"evt-1\"}");
    }

    @Test
    @DisplayName("载荷预览 ≤1000 字符：1500 字符的载荷只给前 1000（管理页不是数据外泄面）")
    void payloadPreviewIsTruncatedToOneThousand() {
        String longPayload = "{\"blob\":\"" + "x".repeat(1500) + "\"}";
        EventDelivery row = new EventDelivery();
        row.setEventId("evt-long");
        row.setEventType(ParamChangedEvent.class.getName());
        row.setStatus("DONE");
        row.setAttemptCount(1);
        row.setMaxAttempt(5);
        row.setPayloadJson(longPayload);

        var dto = mapper.toDto(row);

        assertThat(longPayload.length()).isGreaterThan(1000);
        assertThat(dto.payloadPreview()).hasSize(1000).isEqualTo(longPayload.substring(0, 1000));
    }

    @Test
    @DisplayName("计数列为空（历史/手工数据）→ 出参为 0，不抛 NPE")
    void nullCountsBecomeZero() {
        EventDelivery row = new EventDelivery();
        row.setEventId("evt-null");
        row.setEventType(ParamChangedEvent.class.getName());
        row.setStatus("DEAD");

        var dto = mapper.toDto(row);

        assertThat(dto.attemptCount()).isZero();
        assertThat(dto.maxAttempt()).isZero();
        assertThat(dto.payloadPreview()).isNull();
    }
}
