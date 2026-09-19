package com.eaio.platform.application.cache;

import java.util.Optional;

import com.eaio.platform.api.CacheApi;
import com.eaio.platform.api.CacheKey;
import com.eaio.platform.api.CacheRegion;
import org.springframework.stereotype.Component;

/**
 * {@link CacheApi} 的跨模块实现（P1 册 2.4.3 的薄壳口径）：全部委托 {@link CacheManager}。
 *
 * <p>薄是有意的：算法（三防护、TTL、失效）只有一份，接口只是"跨模块可见面"。本票**没有** REST 端点
 * （P1 册 5.2 的资源清单里没有 cache、7.3 的权限点表里也没有），所以"薄壳"的共用方目前只有应用内调用者；
 * 将来若加管理端点（如"按区域清缓存"），端点也必须走本类而不是自己拼 Redis 命令。
 */
@Component
public class CacheApiImpl implements CacheApi {

    private final CacheManager cache;

    public CacheApiImpl(CacheManager cache) {
        this.cache = cache;
    }

    @Override
    public <T> Optional<T> get(CacheKey key, Class<T> type) {
        return cache.get(key, type);
    }

    @Override
    public void put(CacheKey key, Object value, int ttlSeconds) {
        cache.put(key, value, ttlSeconds);
    }

    @Override
    public void evict(CacheKey key) {
        cache.evict(key);
    }

    @Override
    public void evictByPrefix(CacheRegion region, String keyPrefix) {
        cache.evictByPrefix(region, keyPrefix);
    }
}
