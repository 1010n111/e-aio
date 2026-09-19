package com.eaio.platform.application.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.eaio.platform.api.dto.JobContext;
import com.eaio.platform.domain.event.EventDelivery;
import com.eaio.platform.domain.event.EventDeliveryStatus;
import com.eaio.platform.domain.job.JobLockHandle;
import com.eaio.platform.events.ParamChangedEvent;
import com.eaio.platform.infrastructure.persistence.EventDeliveryStore;
import com.eaio.platform.infrastructure.scheduler.JobLocker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link EventRetryScanner} 的单测（P1 册 3.9.4 第 4、5 条 + 3.9.3 的失败模式）：
 * 到期行重投、锁互斥（跳过而不是重复投）、反序列化失败 → DEAD、超过上限 → DEAD、
 * 超时退出、顺带清理 {@code DONE}。
 *
 * <p>"两实例并发只重投一次"的**跨上下文**证明在 {@code EventRetryIT}（真 ShedLock + 真 PG）：
 * 这里证明的是"拿不到锁就一行都不碰"。
 */
class EventRetryScannerTest {

    private final EventDeliveryStore deliveries = mock(EventDeliveryStore.class);
    private final PlatformEventDispatcher dispatcher = mock(PlatformEventDispatcher.class);
    private final EventParams params = mock(EventParams.class);
    private final JobLocker locker = mock(JobLocker.class);

    private final JobLockHandle lockHandle = mock(JobLockHandle.class);

    private EventRetryScanner scanner;

    @BeforeEach
    void setUp() {
        given(deliveries.available()).willReturn(true);
        given(params.retryMax()).willReturn(5);
        given(params.retainDays()).willReturn(7);
        given(locker.tryLock(anyString(), any(Duration.class))).willReturn(Optional.of(lockHandle));
        scanner = new EventRetryScanner(deliveries, dispatcher, params, locker);
    }

    @Test
    @DisplayName("处理点编码 = platform.event.retry（与 4.5 种子 ID 54 的 job_code/handler_code 逐字一致）")
    void codeMatchesSeededJob() {
        assertThat(scanner.code()).isEqualTo("platform.event.retry");
    }

    @Test
    @DisplayName("到期行重投：逐行交给 dispatcher，成功后释放锁；返回成功条数")
    void retriesDueRowsAndReleasesLock() {
        EventDelivery first = row("evt-1", ParamChangedEvent.class.getName(), 1, 5);
        EventDelivery second = row("evt-2", ParamChangedEvent.class.getName(), 1, 5);
        given(deliveries.dueRetries(anyInt())).willReturn(List.of(first, second));
        given(dispatcher.deliver(any())).willReturn(true, false);
        given(deliveries.deleteDoneBatchBefore(any(), anyInt())).willReturn(0);

        int delivered = scanner.scanOnce();

        assertThat(delivered).as("两条到期行、只有一条投递成功").isEqualTo(1);
        verify(dispatcher, times(2)).deliver(any());
        verify(lockHandle).release();
    }

    @Test
    @DisplayName("拿不到扫描锁（另一实例在跑）→ 一行都不碰（ShedLock 互斥的落点）")
    void skipsEverythingWhenLockIsHeld() {
        given(locker.tryLock(anyString(), any(Duration.class))).willReturn(Optional.empty());

        assertThat(scanner.scanOnce()).isZero();

        verify(deliveries, never()).dueRetries(anyInt());
        verifyNoInteractions(dispatcher);
    }

    @Test
    @DisplayName("扫描锁名在内部任务命名空间：与任何任务锁 platform-job-{code} 构造上不相等")
    void scanLockNameIsInInternalNamespace() {
        scanner.scanOnce();

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(locker).tryLock(name.capture(), any(Duration.class));
        assertThat(name.getValue()).startsWith("platform-internal-").isNotEqualTo("platform-internal-retry-scan");
    }

    @Test
    @DisplayName("事件类型未在代码注册表登记 → ERROR + DEAD（不按 DB 字符串反射加载）")
    void unregisteredEventTypeGoesDead() {
        given(deliveries.dueRetries(anyInt()))
                .willReturn(List.of(row("evt-evil", "com.eaio.platform.NotARegisteredEvent", 1, 5)));
        given(deliveries.deleteDoneBatchBefore(any(), anyInt())).willReturn(0);

        assertThat(scanner.scanOnce()).isZero();

        verify(deliveries).markDead(eq("evt-evil"), eq(1), contains("未在代码注册表中登记"));
        verify(dispatcher, never()).deliver(any());
    }

    @Test
    @DisplayName("payload_json 反序列化失败 → ERROR + DEAD（不无限重试一个永远解不开的记录）")
    void brokenPayloadGoesDead() {
        EventDelivery broken = row("evt-broken", ParamChangedEvent.class.getName(), 2, 5);
        broken.setPayloadJson("这不是 JSON");
        given(deliveries.dueRetries(anyInt())).willReturn(List.of(broken));
        given(deliveries.deleteDoneBatchBefore(any(), anyInt())).willReturn(0);

        assertThat(scanner.scanOnce()).isZero();

        verify(deliveries).markDead(eq("evt-broken"), eq(2), contains("载荷反序列化失败"));
        verify(dispatcher, never()).deliver(any());
    }

    @Test
    @DisplayName("attempt_count 已超过 max_attempt → 直接 DEAD、不再投递（否则每 30 秒失败一次）")
    void attemptBeyondMaxGoesDeadWithoutDelivery() {
        given(deliveries.dueRetries(anyInt()))
                .willReturn(List.of(row("evt-over", ParamChangedEvent.class.getName(), 6, 5)));
        given(deliveries.deleteDoneBatchBefore(any(), anyInt())).willReturn(0);

        assertThat(scanner.scanOnce()).isZero();

        verify(deliveries).markDead(eq("evt-over"), eq(6), contains("超过上限"));
        verify(dispatcher, never()).deliver(any());
    }

    @Test
    @DisplayName("deliver 返回 false（投递又失败）→ 状态由 dispatcher 写，扫描器不再自己改行")
    void failedDeliveryIsLeftToDispatcher() {
        given(deliveries.dueRetries(anyInt()))
                .willReturn(List.of(row("evt-fail", ParamChangedEvent.class.getName(), 1, 5)));
        given(dispatcher.deliver(any())).willReturn(false);
        given(deliveries.deleteDoneBatchBefore(any(), anyInt())).willReturn(0);

        assertThat(scanner.scanOnce()).isZero();

        verify(deliveries, never()).markRetry(anyString(), anyInt(), any(), anyString());
        verify(deliveries, never()).markDead(anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("处理点超时（ctx.expired()）→ 本轮不再投递（长循环主动退出，JobHandler 约定 3）")
    void expiredContextStopsBeforeDelivery() {
        given(deliveries.dueRetries(anyInt()))
                .willReturn(List.of(row("evt-late", ParamChangedEvent.class.getName(), 1, 5)));
        given(deliveries.deleteDoneBatchBefore(any(), anyInt())).willReturn(0);
        JobContext expired = new JobContext(EventRetryScanner.CODE, Map.of(), 1L, 1, "t-expired",
                Instant.now().minusSeconds(1));

        scanner.execute(expired);

        verify(dispatcher, never()).deliver(any());
    }

    @Test
    @DisplayName("顺带清理：按 platform.event.delivery-retain-days 分批删 DONE（1000/批，不足一批即停）")
    void cleansDoneInBatchesByRetainDays() {
        given(deliveries.dueRetries(anyInt())).willReturn(List.of());
        given(deliveries.deleteDoneBatchBefore(any(), anyInt())).willReturn(1000, 3);

        scanner.scanOnce();

        ArgumentCaptor<Instant> threshold = ArgumentCaptor.forClass(Instant.class);
        verify(deliveries, times(2)).deleteDoneBatchBefore(threshold.capture(), eq(1000));
        Instant expected = Instant.now().minus(Duration.ofDays(7));
        assertThat(Duration.between(expected, threshold.getAllValues().get(0)).abs())
                .as("删除线 = now - 7 天（参数默认 7）")
                .isLessThan(Duration.ofSeconds(5));
        assertThat(threshold.getAllValues().get(1)).isEqualTo(threshold.getAllValues().get(0));
    }

    @Test
    @DisplayName("数据库不可用（无 DataSource）→ 跳过本轮，不取锁（无库也能启动的既定口径）")
    void skipsWhenDatabaseUnavailable() {
        given(deliveries.available()).willReturn(false);

        assertThat(scanner.scanOnce()).isZero();

        verifyNoInteractions(locker, dispatcher);
    }

    private static EventDelivery row(String eventId, String eventType, int attempt, int maxAttempt) {
        EventDelivery row = new EventDelivery();
        row.setEventId(eventId);
        row.setEventType(eventType);
        row.setPayloadJson("{\"eventId\":\"" + eventId + "\",\"occurredAt\":\"2026-09-19T10:00:00Z\","
                + "\"changeType\":\"UP\",\"paramKey\":\"it.key\",\"paramLevel\":\"SYSTEM\",\"ownerId\":0}");
        row.setStatus(EventDeliveryStatus.RETRYING.name());
        row.setAttemptCount(attempt);
        row.setMaxAttempt(maxAttempt);
        return row;
    }
}
