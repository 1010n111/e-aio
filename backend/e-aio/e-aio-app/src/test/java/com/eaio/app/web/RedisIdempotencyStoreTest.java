package com.eaio.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import com.eaio.common.api.IdempotencyStore;
import com.eaio.common.exception.IdempotencyUnavailableException;
import com.eaio.common.redis.RedisKit;
import com.eaio.common.redis.RedisUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Redis 幂等占位（P0 册 3.2.5）：键值口径 + Redis 不可用时的 fail-closed。
 *
 * <p>占位值必须逐字是 {@code PROCESSING}/{@code DONE}、TTL 必须是 10 分钟/24 小时：
 * 这两个值决定"重放被拦"与"占位何时自然过期"，改错不会报错，只会静默多执行或少执行一次业务。
 */
class RedisIdempotencyStoreTest {

    private final RecordingRedisKit redis = new RecordingRedisKit();
    private final RedisIdempotencyStore store = new RedisIdempotencyStore(redis);

    @Test
    @DisplayName("acquire：SETNX 写 PROCESSING，TTL 10 分钟；返回原样透传")
    void acquireWritesProcessingPlaceholder() {
        redis.ifAbsentResult = true;
        assertThat(store.acquire("eaio:test:idem:x")).isTrue();
        assertThat(redis.lastIfAbsentKey).isEqualTo("eaio:test:idem:x");
        assertThat(redis.lastIfAbsentValue).isEqualTo("PROCESSING");
        assertThat(redis.lastIfAbsentTtl).isEqualTo(Duration.ofMinutes(10));

        redis.ifAbsentResult = false;
        assertThat(store.acquire("eaio:test:idem:x")).isFalse();
    }

    @Test
    @DisplayName("complete：改写为 DONE，TTL 24 小时")
    void completeMarksDone() {
        store.complete("eaio:test:idem:x");

        assertThat(redis.lastSetKey).isEqualTo("eaio:test:idem:x");
        assertThat(redis.lastSetValue).isEqualTo("DONE");
        assertThat(redis.lastSetTtl).isEqualTo(Duration.ofHours(24));
    }

    @Test
    @DisplayName("release：删除占位（业务失败后允许修正重试）")
    void releaseDeletesPlaceholder() {
        store.release("eaio:test:idem:x");

        assertThat(redis.lastDeletedKey).isEqualTo("eaio:test:idem:x");
    }

    @Test
    @DisplayName("Redis 不可用：acquire/release 抛 IdempotencyUnavailableException（fail-closed）")
    void unavailableFailsClosed() {
        redis.unavailable = true;

        assertThatThrownBy(() -> store.acquire("k")).isInstanceOf(IdempotencyUnavailableException.class);
        assertThatThrownBy(() -> store.release("k")).isInstanceOf(IdempotencyUnavailableException.class);
    }

    @Test
    @DisplayName("complete 失败不改判业务结果：只记日志，不抛异常")
    void completeFailureIsLoggedNotThrown() {
        redis.unavailable = true;

        assertThatCode(() -> store.complete("k")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("TTL 口径与契约常量一致（改接口常量即失败）")
    void ttlMatchesContract() {
        assertThat(IdempotencyStore.PROCESSING_TTL).isEqualTo(Duration.ofMinutes(10));
        assertThat(IdempotencyStore.DONE_TTL).isEqualTo(Duration.ofHours(24));
    }

    /** 只记录调用的 RedisKit 桩：本测试不碰真 Redis。 */
    private static final class RecordingRedisKit implements RedisKit {

        private String lastSetKey;
        private Object lastSetValue;
        private Duration lastSetTtl;
        private String lastIfAbsentKey;
        private Object lastIfAbsentValue;
        private Duration lastIfAbsentTtl;
        private String lastDeletedKey;
        private boolean ifAbsentResult = true;
        private boolean unavailable;

        @Override
        public void set(String key, Object value) {
            set(key, value, null);
        }

        @Override
        public void set(String key, Object value, Duration ttl) {
            failIfUnavailable();
            this.lastSetKey = key;
            this.lastSetValue = value;
            this.lastSetTtl = ttl;
        }

        @Override
        public <T> T get(String key, Class<T> clazz) {
            throw new UnsupportedOperationException("幂等占位不读取值");
        }

        @Override
        public void delete(String key) {
            failIfUnavailable();
            this.lastDeletedKey = key;
        }

        @Override
        public boolean expire(String key, Duration ttl) {
            throw new UnsupportedOperationException("幂等占位不重设 TTL");
        }

        @Override
        public boolean setIfAbsent(String key, Object value, Duration ttl) {
            failIfUnavailable();
            this.lastIfAbsentKey = key;
            this.lastIfAbsentValue = value;
            this.lastIfAbsentTtl = ttl;
            return ifAbsentResult;
        }

        @Override
        public void push(String queue, Object message) {
            throw new UnsupportedOperationException("幂等占位不用队列");
        }

        @Override
        public <T> T poll(String queue, Class<T> clazz, Duration timeout) {
            throw new UnsupportedOperationException("幂等占位不用队列");
        }

        private void failIfUnavailable() {
            if (unavailable) {
                throw new RedisUnavailableException("redis down");
            }
        }
    }
}
