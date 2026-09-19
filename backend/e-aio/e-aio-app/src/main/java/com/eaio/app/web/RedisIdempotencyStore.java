package com.eaio.app.web;

import com.eaio.common.api.ErrorCode;
import com.eaio.common.api.IdempotencyStore;
import com.eaio.common.exception.IdempotencyUnavailableException;
import com.eaio.common.redis.RedisKit;
import com.eaio.common.redis.RedisUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis 幂等占位（P0 册 3.2.5）：键 `eaio:{env}:idem:{sha256(URL+key)}`，
 * 值 `PROCESSING`（TTL 10min）/ `DONE`（TTL 24h）。
 *
 * <p>底层经 {@link RedisKit}（P0 册 3.2.5 的口径："底层用 RedisKit 的 SETNX 能力"）：
 * 任何 Redis 不可用都转成 {@link IdempotencyUnavailableException}，
 * 由过滤器按 fail-closed 返回 {@link ErrorCode#IDEMPOTENCY_UNAVAILABLE}——不静默放行。
 */
public class RedisIdempotencyStore implements IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyStore.class);

    private static final String PROCESSING = "PROCESSING";
    private static final String DONE = "DONE";

    private final RedisKit redis;

    public RedisIdempotencyStore(RedisKit redis) {
        this.redis = redis;
    }

    @Override
    public boolean acquire(String key) {
        try {
            // SETNX + TTL：占位是原子的，并发下只有一个请求能拿到
            return redis.setIfAbsent(key, PROCESSING, PROCESSING_TTL);
        } catch (RedisUnavailableException e) {
            throw unavailable(key, e);
        }
    }

    @Override
    public void complete(String key) {
        try {
            redis.set(key, DONE, DONE_TTL);
        } catch (RedisUnavailableException e) {
            // 占位可能仍是 PROCESSING：业务已成功，不能因此把成功改成失败，
            // 但要让运维知道"这段时间内同键重放会被判为重复提交"这一点未被完整保证
            log.error("幂等占位改写为 DONE 失败：key={}", key, e);
        }
    }

    @Override
    public void release(String key) {
        try {
            redis.delete(key);
        } catch (RedisUnavailableException e) {
            throw unavailable(key, e);
        }
    }

    private static IdempotencyUnavailableException unavailable(String key, Throwable cause) {
        return new IdempotencyUnavailableException("Redis 幂等占位不可用：key=" + key, cause);
    }
}
