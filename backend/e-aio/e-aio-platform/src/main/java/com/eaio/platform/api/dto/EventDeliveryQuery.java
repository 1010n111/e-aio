package com.eaio.platform.api.dto;

import java.time.Instant;

/**
 * 事件投递分页查询入参（P1 册 5.3 的 {@code EventDeliveryQuery}）。
 *
 * <p>分页公共字段：{@code pageNum}（从 1 起，默认 1）、{@code pageSize}（1–200，默认 20）、
 * {@code orderBy}（**白名单**，非法值忽略并 WARN）、{@code orderDir}（asc/desc）。
 *
 * @param eventId     事件 ID（精确——排障时拿到的是完整 ID）
 * @param eventType   事件类型（精确）
 * @param status      投递状态（精确：{@code RETRYING}/{@code DONE}/{@code DEAD}）
 * @param createdFrom 登记时刻下界（含）
 * @param createdTo   登记时刻上界（含）
 * @param pageNum     页码（从 1 起）
 * @param pageSize    页大小（1–200）
 * @param orderBy     排序字段（白名单外的值被忽略）
 * @param orderDir    排序方向 asc/desc
 */
public record EventDeliveryQuery(
        String eventId,
        String eventType,
        String status,
        Instant createdFrom,
        Instant createdTo,
        Integer pageNum,
        Integer pageSize,
        String orderBy,
        String orderDir) {
}
