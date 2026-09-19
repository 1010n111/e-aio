package com.eaio.platform.infrastructure.cache;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

import com.eaio.platform.api.CacheRegion;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 本机一级缓存（L1，Caffeine），一个区域一个缓存实例（P1 册 3.6.1 的 L1 TTL 列）。
 *
 * <p><b>TTL 按条目而不是按缓存固定</b>：显式 TTL 的写入（{@code CacheApi.put(key, value, 30)}）必须保证
 * L1 先于 L2 过期（3.6.2 防雪崩），固定 TTL 的缓存做不到这一点——L2 已按 TTL 丢掉的值还会在本机命中。
 * 于是每个条目自带 TTL，由 {@link EntryExpiry} 实现（读操作不续期，语义等同 {@code expireAfterWrite}）。
 *
 * <p>本机缓存没有失效广播的问题：{@code CacheInvalidationSubscriber} 收到其他实例的失效消息后清本机 L1，
 * 同一实例的失效由调用方直接清（{@link #evict(CacheRegion, String)} / {@link #evictByPrefix}）。
 */
@Component
public class CaffeineRegionCache {

    /** 每个区域的最大条目数（{@code platform.cache.local.max-size} 的默认值 10000）。 */
    private static final long MAX_SIZE_PER_REGION = 10_000L;

    private final Map<CacheRegion, Cache<String, Entry>> caches = new EnumMap<>(CacheRegion.class);

    private final boolean enabled;

    public CaffeineRegionCache(Environment environment) {
        this.enabled = environment.getProperty("eaio.cache.local.enabled", Boolean.class, true);
        for (CacheRegion region : CacheRegion.values()) {
            caches.put(region, Caffeine.newBuilder()
                    .maximumSize(MAX_SIZE_PER_REGION)
                    .expireAfter(new EntryExpiry())
                    .build());
        }
    }

    /** L1 开关（{@code eaio.cache.local.enabled}，P1-2 册 3.15）：关掉时读=未命中、写=丢弃。 */
    public boolean enabled() {
        return enabled;
    }

    /** 读；未命中或已过期返回 {@code null}。 */
    public Object get(CacheRegion region, String key) {
        if (!enabled) {
            return null;
        }
        Entry entry = caches.get(region).getIfPresent(key);
        return entry == null ? null : entry.value();
    }

    /** 写；{@code ttl} 由调用方保证不晚于 L2 的过期时间（3.6.2）。 */
    public void put(CacheRegion region, String key, Object value, Duration ttl) {
        if (!enabled) {
            return;
        }
        caches.get(region).put(key, new Entry(value, ttl));
    }

    /** 单键清理（本机失效用；不广播）。 */
    public void evict(CacheRegion region, String key) {
        caches.get(region).invalidate(key);
    }

    /** 前缀清理（本机前缀失效与广播的接收侧用）：{@code keyPrefix} 为空串表示该区域全清。 */
    public void evictByPrefix(CacheRegion region, String keyPrefix) {
        if (keyPrefix == null || keyPrefix.isEmpty()) {
            caches.get(region).invalidateAll();
            return;
        }
        caches.get(region).asMap().keySet().removeIf(key -> key.startsWith(keyPrefix));
    }

    /** 该条目的 TTL；条目不存在返回 {@code null}（单测与排障用：证明 L1 先于 L2 过期）。 */
    public Duration expiresAfter(CacheRegion region, String key) {
        return caches.get(region).policy().expireVariably()
                .flatMap(variable -> variable.getExpiresAfter(key))
                .orElse(null);
    }

    /** L1 条目：值 + 该条目的 TTL（TTL 随条目走才能做到"显式 TTL 也先于 L2 过期"）。 */
    private record Entry(Object value, Duration ttl) {
    }

    /** 按条目 TTL 过期；读不续期（固定 TTL 语义）。 */
    private static final class EntryExpiry implements Expiry<String, Entry> {

        @Override
        public long expireAfterCreate(String key, Entry entry, long currentTime) {
            return entry.ttl().toNanos();
        }

        @Override
        public long expireAfterUpdate(String key, Entry entry, long currentTime, long currentDuration) {
            return entry.ttl().toNanos();
        }

        @Override
        public long expireAfterRead(String key, Entry entry, long currentTime, long currentDuration) {
            return currentDuration;
        }
    }
}
