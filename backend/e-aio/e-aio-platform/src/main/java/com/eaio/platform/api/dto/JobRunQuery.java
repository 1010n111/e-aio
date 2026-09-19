package com.eaio.platform.api.dto;

import java.time.Instant;

/**
 * 运行日志分页查询入参（P1 册 5.3：{@code JobRunQuery}）。
 *
 * @param jobCode   任务编码（精确：运行日志总是"某任务"的日志）
 * @param status    运行状态（精确）
 * @param startFrom 开始时刻下界（含）
 * @param startTo   开始时刻上界（含）
 * @param pageNum   页码（从 1 起）
 * @param pageSize  页大小（1–200）
 * @param orderBy   排序字段（白名单外的值被忽略）
 * @param orderDir  排序方向 ASC/DESC
 */
public record JobRunQuery(
        String jobCode,
        String status,
        Instant startFrom,
        Instant startTo,
        Integer pageNum,
        Integer pageSize,
        String orderBy,
        String orderDir) {
}
