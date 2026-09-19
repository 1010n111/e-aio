package com.eaio.platform.application.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eaio.common.exception.BusinessException;
import com.eaio.platform.api.dto.EventDeliveryQuery;
import com.eaio.platform.application.param.ParamContextProvider;
import com.eaio.platform.domain.event.EventDelivery;
import com.eaio.platform.domain.event.EventDeliveryStatus;
import com.eaio.platform.domain.param.ParamContext;
import com.eaio.platform.events.ParamChangedEvent;
import com.eaio.platform.infrastructure.persistence.EventDeliveryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link EventDeliveryAppService} 的单测（P1 册 3.9.4 第 6 条 + 7.1 的 20070/20071）：
 * 重放 DEAD → {@code RETRYING}/{@code attempt=1}/{@code last_error} 保留历史；不存在 20070；
 * {@code DONE}/{@code RETRYING} 与并发重放 → 20071。
 *
 * <p>{@code last_error} 的**追加**在 SQL 里做（{@code EventDeliveryMapper#markReplay} 的 CASE 拼接），
 * 因此这里断言的是服务把"追加语义"交给了正确的原子操作、并把结果原样出参；SQL 层的真实拼接由
 * {@code EventRetryIT} 用真 PG 断言（旧错误与新的一行都在）。
 */
class EventDeliveryAppServiceTest {

    private final EventDeliveryStore deliveries = mock(EventDeliveryStore.class);
    private final ParamContextProvider contexts = mock(ParamContextProvider.class);

    private EventDeliveryAppService service;

    @BeforeEach
    void setUp() {
        given(contexts.current()).willReturn(ParamContext.systemOnly());
        service = new EventDeliveryAppService(deliveries, new EventDeliveryDtoMapper(), contexts);
    }

    @Test
    @DisplayName("分页：行 → DTO，保留分页元数据")
    void pageMapsRows() {
        Page<EventDelivery> page = new Page<>(1, 20);
        page.setRecords(List.of(row("evt-1", EventDeliveryStatus.DEAD, 5)));
        page.setTotal(1);
        given(deliveries.page(any())).willReturn(page);

        var result = service.page(new EventDeliveryQuery(null, null, "DEAD", null, null, 1, 20, null, null));

        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getPageNum()).isEqualTo(1);
        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getRecords().get(0).eventId()).isEqualTo("evt-1");
        assertThat(result.getRecords().get(0).status()).isEqualTo("DEAD");
    }

    @Test
    @DisplayName("详情：记录不存在 → 20070")
    void getMissingRowThrows20070() {
        given(deliveries.rowByEventId("evt-none")).willReturn(null);

        assertThatThrownBy(() -> service.byEventId("evt-none"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(20070));
    }

    @Test
    @DisplayName("重放 DEAD → RETRYING/attempt=1，last_error 追加一次且保留历史（3.9.4 第 6 条）")
    void replayDeadRowResetsAttemptAndKeepsHistory() {
        EventDelivery dead = row("evt-dead", EventDeliveryStatus.DEAD, 5);
        dead.setLastError("第 5 次投递失败：监听方炸了");
        EventDelivery replayed = row("evt-dead", EventDeliveryStatus.RETRYING, 1);
        replayed.setLastError("第 5 次投递失败：监听方炸了\n人工重放：operator=0 at=2026-09-19T10:05:00Z");
        given(deliveries.rowByEventId("evt-dead")).willReturn(dead, replayed);
        given(deliveries.markReplay(eq("evt-dead"), anyString(), any())).willReturn(true);

        var dto = service.replay("evt-dead");

        ArgumentCaptor<String> note = ArgumentCaptor.forClass(String.class);
        verify(deliveries).markReplay(eq("evt-dead"), note.capture(), eq(0L));
        assertThat(note.getValue())
                .as("追加的是一行新注记（旧错误由 SQL 的 CASE 拼接保留，不在这里覆盖）")
                .contains("人工重放")
                .doesNotContain("监听方炸了");
        assertThat(dto.status()).isEqualTo("RETRYING");
        assertThat(dto.attemptCount()).as("重放后重新从第 1 次开始").isEqualTo(1);
        assertThat(dto.lastError())
                .as("历史保留：旧错误 + 人工重放注记两行都在")
                .contains("第 5 次投递失败：监听方炸了")
                .contains("人工重放");
    }

    @Test
    @DisplayName("DONE 不允许重放 → 20071，且不写库")
    void replayDoneRowThrows20071() {
        given(deliveries.rowByEventId("evt-done")).willReturn(row("evt-done", EventDeliveryStatus.DONE, 1));

        assertThatThrownBy(() -> service.replay("evt-done"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(20071));
        verify(deliveries, never()).markReplay(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("正在重投（RETRYING）不允许重放 → 20071（重放它是插队，不是重试）")
    void replayRetryingRowThrows20071() {
        given(deliveries.rowByEventId("evt-retrying"))
                .willReturn(row("evt-retrying", EventDeliveryStatus.RETRYING, 2));

        assertThatThrownBy(() -> service.replay("evt-retrying"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(20071));
        verify(deliveries, never()).markReplay(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("并发重放：条件更新 0 行（已被他人重放/状态已变）→ 20071")
    void concurrentReplayThrows20071() {
        given(deliveries.rowByEventId("evt-race")).willReturn(row("evt-race", EventDeliveryStatus.DEAD, 5));
        given(deliveries.markReplay(eq("evt-race"), anyString(), any())).willReturn(false);

        assertThatThrownBy(() -> service.replay("evt-race"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(20071));
    }

    @Test
    @DisplayName("重放不存在的记录 → 20070（先于状态判定）")
    void replayMissingRowThrows20070() {
        given(deliveries.rowByEventId("evt-ghost")).willReturn(null);

        assertThatThrownBy(() -> service.replay("evt-ghost"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(20070));
    }

    private static EventDelivery row(String eventId, EventDeliveryStatus status, int attempt) {
        EventDelivery row = new EventDelivery();
        row.setId(1L);
        row.setEventId(eventId);
        row.setEventType(ParamChangedEvent.class.getName());
        row.setStatus(status.name());
        row.setAttemptCount(attempt);
        row.setMaxAttempt(5);
        row.setCreatedAt(Instant.parse("2026-09-19T10:00:00Z"));
        row.setPayloadJson("{\"eventId\":\"" + eventId + "\"}");
        return row;
    }
}
