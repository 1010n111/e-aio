package com.eaio.app.web;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.eaio.common.api.IdempotencyStore;

/**
 * 内存幂等占位（**仅用于"未配置 Redis"的单实例运行**：本地演示与单测）。
 *
 * <p>为什么存在：P0 明确"Redis 实现本体随 P1 引入"（P0 册 3.2 幂等手段），
 * 但入站链路必须先能跑起来。若在未配置 Redis 时直接 fail-closed，本机无 Redis 的启动方式
 * 一收到带幂等键的请求就返回 10502，链路等于不可用。
 *
 * <p>边界（装配处会打 WARN）：多实例部署下内存占位**不构成**跨实例幂等，
 * 生产必须配置 Redis——那时 {@code RedisIdempotencyStore} 生效，且 Redis 不可用时 fail-closed。
 */
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final Map<String, Entry> placeholders = new ConcurrentHashMap<>();

    @Override
    public boolean acquire(String key) {
        Entry existing = placeholders.get(key);
        if (existing != null && !existing.expired()) {
            return false;
        }
        placeholders.put(key, new Entry(false, Instant.now().plus(PROCESSING_TTL)));
        return true;
    }

    @Override
    public void complete(String key) {
        placeholders.put(key, new Entry(true, Instant.now().plus(DONE_TTL)));
    }

    @Override
    public void release(String key) {
        placeholders.remove(key);
    }

    /** 单实例内存实现不需要连接，因此不会抛"校验不可用"。 */

    private record Entry(boolean done, Instant expiresAt) {
        boolean expired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
