package com.eaio.platform.application.event;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.eaio.common.json.JsonUtils;
import com.eaio.platform.api.JobHandler;
import com.eaio.platform.api.dto.JobContext;
import com.eaio.platform.domain.event.EventDelivery;
import com.eaio.platform.domain.event.EventDeliveryStatus;
import com.eaio.platform.domain.job.JobLockHandle;
import com.eaio.platform.domain.job.JobLockWindow;
import com.eaio.platform.events.EventTypes;
import com.eaio.platform.events.PlatformEvent;
import com.eaio.platform.infrastructure.persistence.EventDeliveryStore;
import com.eaio.platform.infrastructure.scheduler.JobLocker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 事件重投扫描器（P1 册 3.9.2 第 3 条 / 4.5 的内置任务 {@code platform.event.retry}）。
 *
 * <p>本类**就是**那个内置任务的处理点（{@link JobHandler#code()} = {@code platform.event.retry}，
 * 与种子里 ID 54 的 {@code job_code}/{@code handler_code} 逐字一致）：调度器按 `&#42;/30 * * * * *`
 * 每 30 秒触发，注册表在启动期把它收集起来——T7 种下的任务行到这一刻才真正可执行（3.4.5 的清单
 * 先种行、处理点随能力的票注册）。
 *
 * <p>一轮做两件事：
 * <ol>
 *   <li><b>重投到期的 {@code RETRYING} 行</b>：按 {@code payload_json} 反序列化回事件对象，交给
 *       {@link PlatformEventDispatcher#deliver(PlatformEvent)}（与首次投递同一条路径：成功 → {@code DONE}；
 *       失败 → {@code attempt_count + 1} + 指数退避，超 {@code max_attempt} → {@code DEAD} + 死信事实）；</li>
 *   <li><b>顺带清理 {@code DONE}</b>：超过 {@code platform.event.delivery-retain-days}（默认 7 天）的行
 *       分批物理删除（{@code DEAD} 永不自动删——它等人处置，3.9.3）。</li>
 * </ol>
 *
 * <p><b>集群互斥用一把独立锁</b>（{@code platform-internal-event-retry-scan}，前缀取自
 * {@link JobLockWindow#INTERNAL_LOCK_PREFIX}）：与任何任务锁 {@code platform-job-{code}} 构造上不可能
 * 撞名，也与 T7 的内部重投扫描锁 {@code platform-internal-retry-scan} 不同名。任务框架自己那把锁
 * 只保证"同一个任务不被两个实例同时跑"，而本处理点还会被测试/运维**直接调用**（{@code scanOnce()}），
 * 锁放在这里才能覆盖全部入口。持锁上限 120 秒：正常一轮（≤100 条、监听方 <100ms）远小于它，
 * 实例真宕机时最多 2 分钟后别的实例就能接管（比任务锁 390 秒的恢复窗口短）。
 *
 * <p><b>反序列化失败 → ERROR + DEAD</b>（3.9.3 逐条）：事件类被重命名/删除后 {@code payload_json}
 * 永远解不开，留在 {@code RETRYING} 只会每 30 秒失败一次。事件类型经 {@link EventTypes} 的
 * **代码注册表**解析——{@code event_type} 是库里的字符串，不按它 {@code Class.forName}
 * （3.4.1 明确否决的"DB 字符串 → 代码路径"）。
 */
@Component
public class EventRetryScanner implements JobHandler {

    /** 处理点编码（与 4.5 种子 ID 54 的 {@code job_code}/{@code handler_code} 逐字一致）。 */
    public static final String CODE = "platform.event.retry";

    /** 扫描锁名（内部任务命名空间，见类注释）。 */
    static final String SCAN_LOCK = JobLockWindow.INTERNAL_LOCK_PREFIX + "event-retry-scan";

    /** 持锁上限（秒）：远大于正常一轮，宕机后 2 分钟内可被接管。 */
    static final Duration SCAN_LOCK_AT_MOST = Duration.ofSeconds(120);

    /** 每轮最多重投的条数（7.2 没有给事件侧的批量参数键，用代码常量：一轮跑不完下一轮继续）。 */
    private static final int RETRY_BATCH_SIZE = 100;

    /** 清理批次大小（3.4.4 同款：1000 行/批，避免一次 DELETE 撑爆 WAL）。 */
    private static final int CLEAN_BATCH_SIZE = 1000;

    /** 单次运行最多删多少批（10 万行）：积压巨大时留给下一轮，不把调度线程占死。 */
    private static final int CLEAN_MAX_BATCHES = 100;

    private static final Logger log = LoggerFactory.getLogger(EventRetryScanner.class);

    private final EventDeliveryStore deliveries;
    private final PlatformEventDispatcher dispatcher;
    private final EventParams params;
    private final JobLocker locker;

    public EventRetryScanner(EventDeliveryStore deliveries, PlatformEventDispatcher dispatcher, EventParams params,
            JobLocker locker) {
        this.deliveries = deliveries;
        this.dispatcher = dispatcher;
        this.params = params;
        this.locker = locker;
    }

    @Override
    public String code() {
        return CODE;
    }

    /** 调度器入口（每 30 秒）；{@link JobContext#expired()} 在每条之间与每批之间检查。 */
    @Override
    public void execute(JobContext ctx) {
        scan(ctx);
    }

    /** 扫一轮（测试与运维可直接调用）：返回本轮**重投成功**的条数。拿不到扫描锁时返回 0（别的实例在跑）。 */
    public int scanOnce() {
        return scan(null);
    }

    // ---------------------------------------------------------------- 内部

    private int scan(JobContext ctx) {
        if (!deliveries.available()) {
            log.warn("事件重投扫描跳过：未配置数据库（无 EventDeliveryMapper）");
            return 0;
        }
        Optional<JobLockHandle> lock = locker.tryLock(SCAN_LOCK, SCAN_LOCK_AT_MOST);
        if (lock.isEmpty()) {
            log.debug("事件重投扫描跳过：锁 {} 被其他实例持有（下一轮再试）", SCAN_LOCK);
            return 0;
        }
        try {
            int delivered = retryDue(ctx);
            cleanDone(ctx);
            return delivered;
        } finally {
            lock.get().release();
        }
    }

    /** 重投到期的 {@code RETRYING} 行；返回重投成功的条数。 */
    private int retryDue(JobContext ctx) {
        List<EventDelivery> due = deliveries.dueRetries(RETRY_BATCH_SIZE);
        int delivered = 0;
        for (EventDelivery row : due) {
            if (expired(ctx)) {
                log.warn("事件重投扫描超时退出：本轮 {} 条未处理（下一轮继续）", due.size() - delivered);
                break;
            }
            if (!EventDeliveryStatus.RETRYING.name().equals(row.getStatus())) {
                continue;
            }
            int attempt = row.getAttemptCount() == null ? 1 : row.getAttemptCount();
            int maxAttempt = row.getMaxAttempt() == null ? params.retryMax() : row.getMaxAttempt();
            if (attempt > maxAttempt) {
                // 数据异常或上限被调低：已经不该再投递，收成死信（否则每 30 秒被捞一次、永远失败）
                deliveries.markDead(row.getEventId(), attempt,
                        "尝试次数 " + attempt + " 已超过上限 " + maxAttempt + "（扫描时判定，不再投递）");
                log.error("事件尝试次数超过上限，直接转死信：eventId={} attempt={} maxAttempt={}", row.getEventId(),
                        attempt, maxAttempt);
                continue;
            }
            PlatformEvent event = deserialize(row);
            if (event == null) {
                continue;
            }
            if (dispatcher.deliver(event)) {
                delivered++;
            }
        }
        return delivered;
    }

    /**
     * {@code payload_json} → 事件对象（事件类型经 {@link EventTypes} 的**代码注册表**解析）。
     *
     * <p>不按 DB 字符串 {@code Class.forName}（那是 3.4.1 明确否决的"DB 字符串 → 代码路径"）：
     * 未登记的类型、或反序列化失败的载荷，都记 ERROR 并置 {@code DEAD}（3.9.3 逐条：不无限重试一个
     * 永远解不开的记录）；返回 {@code null} 表示本行已收成死信，调用方跳过。
     */
    private PlatformEvent deserialize(EventDelivery row) {
        String eventType = row.getEventType();
        int attempt = row.getAttemptCount() == null ? 1 : row.getAttemptCount();
        Optional<Class<? extends PlatformEvent>> type = EventTypes.resolve(eventType);
        if (type.isEmpty()) {
            String reason = "事件类型未在代码注册表中登记：" + eventType;
            deliveries.markDead(row.getEventId(), attempt, "载荷反序列化失败：" + reason);
            log.error("事件载荷无法反序列化，转死信（不无限重试）：eventId={} eventType={} 原因={}",
                    row.getEventId(), eventType, reason);
            return null;
        }
        try {
            return JsonUtils.fromJson(row.getPayloadJson(), type.get());
        } catch (RuntimeException e) {
            deliveries.markDead(row.getEventId(), attempt, "载荷反序列化失败：" + describe(e));
            log.error("事件载荷无法反序列化，转死信（不无限重试）：eventId={} eventType={} 原因={}",
                    row.getEventId(), eventType, describe(e));
            return null;
        }
    }

    /** 清理超过保留天数的 {@code DONE} 行（分批；{@code DEAD} 不动）。 */
    private void cleanDone(JobContext ctx) {
        int retainDays = params.retainDays();
        Instant before = Instant.now().minus(Duration.ofDays(retainDays));
        long deleted = 0;
        int batches = 0;
        int current;
        do {
            current = deliveries.deleteDoneBatchBefore(before, CLEAN_BATCH_SIZE);
            deleted += current;
            batches++;
        } while (current == CLEAN_BATCH_SIZE && batches < CLEAN_MAX_BATCHES && !expired(ctx));
        if (deleted > 0) {
            log.info("事件投递记录清理完成：保留 {} 天，删除 {} 行（{} 批）", retainDays, deleted, batches);
        }
    }

    private static boolean expired(JobContext ctx) {
        return ctx != null && ctx.expired();
    }

    private static String describe(Throwable cause) {
        String type = cause.getClass().getSimpleName();
        String message = cause.getMessage();
        return message == null ? type : type + ": " + message;
    }
}
