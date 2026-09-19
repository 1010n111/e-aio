package com.eaio.common.redis;

import java.time.Duration;

/**
 * Redis 读写门面（P0 册 4.4 冻结的方法集合 / P1-2 册 2.7）。
 *
 * <p><b>为什么是接口而不是类</b>：P0 册 4.4 冻结的是**方法集合与语义**。本工程另有两条硬约束与之冲突：
 * 通用能力层零运行时依赖、且 {@code ArchitectureTest#commonIsPure} 明令禁止把
 * {@code org.springframework.data..} 带进 common。因此底座由装配层提供（与 P0 的
 * {@code IdempotencyStore} 同款做法：契约在 common、实现随首个消费者落地），
 * 首个消费者是入站链路的幂等占位（P0 册 3.2.5 原文即"底层用 RedisKit 的 SETNX 能力"）。
 *
 * <p><b>值编码</b>：{@link String} 原样存取（P0 幂等占位的 {@code PROCESSING}/{@code DONE} 值必须逐字不变），
 * 其余类型以 JSON 编码，{@link #get(String, Class)} 按同一规则还原。
 *
 * <p><b>失败语义</b>：客户端不可用（连不上、命令失败、结果无法判定）统一抛
 * {@link RedisUnavailableException}。调用方必须显式选择 fail-closed 或降级——本门面**不做**隐式兜底：
 * 缓存类调用可回源数据库，幂等类调用必须拒绝执行（P0 册 3.2.5）。
 */
public interface RedisKit {

    /** 写入（用实现方配置的默认 TTL，见 P1-2 册 3.4 的 {@code eaio.cache.redis.ttl}）。 */
    void set(String key, Object value);

    /** 写入并设置过期时间；{@code ttl} 为空表示不过期（只有明确需要常驻时才这么用）。 */
    void set(String key, Object value, Duration ttl);

    /** 读取；键不存在返回 {@code null}（"不存在"与"不可用"必须可区分：后者抛异常）。 */
    <T> T get(String key, Class<T> clazz);

    /** 删除键（不存在不报错）。 */
    void delete(String key);

    /** 重设过期时间；键不存在返回 {@code false}。 */
    boolean expire(String key, Duration ttl);

    /**
     * SETNX + TTL：占位/互斥重建用（防穿透的空值占位、防击穿的互斥重建、幂等占位）。
     *
     * <p>{@code ttl} 必填：无 TTL 的占位在持有者崩溃后会永久挡住后续请求。
     */
    boolean setIfAbsent(String key, Object value, Duration ttl);

    /** 轻量队列投递（Redis List，右进左出）。 */
    void push(String queue, Object message);

    /** 轻量队列消费；超时无消息返回 {@code null}。 */
    <T> T poll(String queue, Class<T> clazz, Duration timeout);
}
