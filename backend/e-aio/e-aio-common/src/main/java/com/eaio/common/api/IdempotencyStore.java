package com.eaio.common.api;

import java.time.Duration;

/**
 * 幂等占位存储（契约 V1，P0 册 3.2.5）。
 *
 * <p>只描述"占位"这件事，不描述 Redis：入口过滤器依赖本接口，
 * 于是"判定/释放"这一串逻辑可以用内存实现测清楚，Redis 适配器只负责键与 TTL。
 *
 * <p>实现必须保证：{@link #acquire} 是原子的（并发下只有一个请求能拿到占位）。
 */
public interface IdempotencyStore {

    /** 执行中占位的存活时长：防并发重入，超时后允许重试。 */
    Duration PROCESSING_TTL = Duration.ofMinutes(10);

    /** 已完成占位的存活时长：这段时间内同键重放都算重复提交。 */
    Duration DONE_TTL = Duration.ofHours(24);

    /**
     * 尝试占位（SETNX 语义）。
     *
     * @return true = 占位成功，调用方可以执行业务；false = 已有占位（执行中或已完成），属重复提交
     */
    boolean acquire(String key);

    /** 业务成功：把占位改写为"已完成"（TTL 换成长存活期）。 */
    void complete(String key);

    /** 业务失败：立即释放占位，允许用户修正后重试，不锁死到 TTL。 */
    void release(String key);
}
