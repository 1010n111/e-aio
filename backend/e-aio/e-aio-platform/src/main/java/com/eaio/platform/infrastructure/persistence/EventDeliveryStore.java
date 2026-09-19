package com.eaio.platform.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eaio.common.exception.SystemException;
import com.eaio.platform.api.dto.EventDeliveryQuery;
import com.eaio.platform.domain.event.EventDelivery;
import com.eaio.platform.domain.event.EventDeliveryStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 事件投递记录的持久化门面（P1 册 4.3.14）。注入口径同 {@link JobRunStore}：无库时**不在装配期炸**，
 * 真去读写时才以 {@code SystemException} 明确失败（P0 冻结的"无库也能启动"）。
 *
 * <p>四次状态流转交给 {@link EventDeliveryMapper} 的原子 SQL（见其类注释）；本类只做查询组装与
 * 分页白名单。查询条件里的枚举名一律转大写（管理页可能传小写）。
 */
@Component
public class EventDeliveryStore {

    private static final Logger log = LoggerFactory.getLogger(EventDeliveryStore.class);

    /** 排序列白名单：DTO 字段名 → 实体列引用（白名单外的值忽略并 WARN，5.3 的口径）。 */
    private static final Map<String, SFunction<EventDelivery, ?>> ORDER_COLUMNS = Map.of(
            "eventId", EventDelivery::getEventId,
            "eventType", EventDelivery::getEventType,
            "status", EventDelivery::getStatus,
            "attemptCount", EventDelivery::getAttemptCount,
            "nextRetryTime", EventDelivery::getNextRetryTime,
            "createdAt", EventDelivery::getCreatedAt,
            "finishTime", EventDelivery::getFinishTime,
            "id", EventDelivery::getId);

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final ObjectProvider<EventDeliveryMapper> mappers;

    public EventDeliveryStore(ObjectProvider<EventDeliveryMapper> mappers) {
        this.mappers = mappers;
    }

    /** 数据库是否可用。 */
    public boolean available() {
        return mappers.getIfAvailable() != null;
    }

    /** 登记一条投递（**业务事务内**调用；业务回滚时这一行一起回滚，3.9.2 第 1 条）。 */
    public int insert(EventDelivery row) {
        return mapper().insert(row);
    }

    /** 按事件 ID 取（唯一索引保证至多一行；未找到返回 {@code null}）。 */
    public EventDelivery rowByEventId(String eventId) {
        return mapper().selectOne(new LambdaQueryWrapper<EventDelivery>().eq(EventDelivery::getEventId, eventId));
    }

    /** 分页（默认按 {@code created_at DESC}：排障先看最近登记的）。 */
    public IPage<EventDelivery> page(EventDeliveryQuery query) {
        EventDeliveryQuery actual = query == null
                ? new EventDeliveryQuery(null, null, null, null, null, null, null, null, null)
                : query;
        LambdaQueryWrapper<EventDelivery> wrapper = new LambdaQueryWrapper<>();
        if (actual.eventId() != null && !actual.eventId().isBlank()) {
            wrapper.eq(EventDelivery::getEventId, actual.eventId().trim());
        }
        if (actual.eventType() != null && !actual.eventType().isBlank()) {
            wrapper.eq(EventDelivery::getEventType, actual.eventType().trim());
        }
        if (actual.status() != null && !actual.status().isBlank()) {
            wrapper.eq(EventDelivery::getStatus, actual.status().trim().toUpperCase(Locale.ROOT));
        }
        if (actual.createdFrom() != null) {
            wrapper.ge(EventDelivery::getCreatedAt, actual.createdFrom());
        }
        if (actual.createdTo() != null) {
            wrapper.le(EventDelivery::getCreatedAt, actual.createdTo());
        }
        if (!applyOrder(wrapper, ORDER_COLUMNS, actual.orderBy(), actual.orderDir())) {
            wrapper.orderByDesc(EventDelivery::getCreatedAt);
        }
        return mapper().selectPage(new Page<>(pageNum(actual.pageNum()), pageSize(actual.pageSize())), wrapper);
    }

    /** 到期待重投的行（{@code status = RETRYING} 且 {@code next_retry_time <= now}，按到期时刻升序）。 */
    public List<EventDelivery> dueRetries(int batchSize) {
        int size = batchSize < 1 ? 1 : batchSize;
        return mapper().selectPage(new Page<>(1, size, false), new LambdaQueryWrapper<EventDelivery>()
                .eq(EventDelivery::getStatus, EventDeliveryStatus.RETRYING.name())
                .isNotNull(EventDelivery::getNextRetryTime)
                .le(EventDelivery::getNextRetryTime, Instant.now())
                .orderByAsc(EventDelivery::getNextRetryTime)
                .orderByAsc(EventDelivery::getId)).getRecords();
    }

    /** 投递成功（{@code RETRYING → DONE}）；返回受影响行数（1 = 记成功，0 = 已被别的流程改写）。 */
    public boolean markDone(String eventId) {
        return mapper().markDone(eventId) == 1;
    }

    /** 失败仍可重投（{@code attempt_count} 推进 + 退避到期时刻 + 错误摘要）；返回是否生效。 */
    public boolean markRetry(String eventId, int attempt, Instant nextRetryTime, String error) {
        return mapper().markRetry(eventId, attempt, nextRetryTime, error) == 1;
    }

    /** 超过上限转死信（{@code RETRYING → DEAD}）；返回是否生效。 */
    public boolean markDead(String eventId, int attempt, String error) {
        return mapper().markDead(eventId, attempt, error) == 1;
    }

    /** 人工重放（{@code DEAD → RETRYING}、{@code attempt_count = 1}、{@code last_error} 追加一次）。 */
    public boolean markReplay(String eventId, String note, Long operator) {
        return mapper().markReplay(eventId, note, operator) == 1;
    }

    /** 分批物理删除超保留天数的 {@code DONE} 行，返回本批删除行数。 */
    public int deleteDoneBatchBefore(Instant before, int batchSize) {
        return mapper().deleteDoneBatchBefore(before, batchSize);
    }

    // ---------------------------------------------------------------- 内部

    private static <T> boolean applyOrder(LambdaQueryWrapper<T> wrapper, Map<String, SFunction<T, ?>> columns,
            String orderBy, String orderDir) {
        SFunction<T, ?> column = columns.get(orderBy == null ? "" : orderBy.trim());
        if (column == null) {
            if (orderBy != null && !orderBy.isBlank()) {
                log.warn("忽略非白名单排序列 orderBy={}（登记白名单：{}）", orderBy, columns.keySet());
            }
            return false;
        }
        boolean asc = !"desc".equalsIgnoreCase(orderDir == null ? "" : orderDir.trim());
        wrapper.orderBy(true, asc, column);
        return true;
    }

    private static long pageNum(Integer requested) {
        return requested == null || requested < 1 ? 1L : requested;
    }

    private static long pageSize(Integer requested) {
        if (requested == null) {
            return DEFAULT_PAGE_SIZE;
        }
        return requested < 1 || requested > MAX_PAGE_SIZE ? DEFAULT_PAGE_SIZE : requested;
    }

    private EventDeliveryMapper mapper() {
        EventDeliveryMapper mapper = mappers.getIfAvailable();
        if (mapper == null) {
            throw new SystemException("事件投递记录不可用：未配置数据库（无 DataSource/EventDeliveryMapper）");
        }
        return mapper;
    }
}
