package com.eaio.platform.infrastructure.cache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import com.eaio.common.redis.RedisKit;
import com.eaio.common.redis.RedisUnavailableException;
import com.eaio.platform.api.CacheRegion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 二级缓存（L2）原语 + 防护原语（P1 册 3.6.2/3.6.3、4.6 键表）。
 *
 * <p><b>这里只做"一次 Redis 动作"，不做缓存策略</b>：TTL 抖动、空值占位判定、互斥重建的等待都由
 * {@code application.cache.CacheManager} 编排——策略集中在一处，本类是"Redis 怎么用"的唯一落点。
 *
 * <p><b>失败语义（3.1.5/3.6.3）</b>：读失败 = 未命中；写/删/锁失败 = 记日志 + 累加
 * {@code platform.cache.op.error}（计数器在模块内，T13 接 Micrometer 后转成指标），<b>一律不抛</b>。
 * 唯一"反直觉"的一处：{@link #tryLock} 在 Redis 不可用时返回 {@code true}（放行回源），否则每次冷读
 * 都要白等 1s 才能回源——"Redis 不可用时读路径必须可用"包含延迟可用。
 *
 * <p>前缀删除用 {@link StringRedisTemplate} 的 SCAN 游标（3.6.3 明令不用 KEYS）：{@link RedisKit} 的
 * 冻结方法集（P0 册 4.4）没有 scan；正常读写仍走门面，不绕过它。
 */
@Component
public class RedisRegionCache {

    private static final Logger log = LoggerFactory.getLogger(RedisRegionCache.class);

    /** 锁的占位值：只用来占位，解锁是删键（TTL 10s 是兜底）。 */
    private static final String LOCK_VALUE = "1";

    /** 空值占位的值：语义在键上（键存在 = "查过且没有"），值本身不需要信息。 */
    private static final String NULL_VALUE = "NULL";

    /** 单次 SCAN 的 count 提示（与参数中心既有的 500 一致）。 */
    private static final int SCAN_BATCH = 500;

    private final ObjectProvider<RedisKit> redisKit;

    private final ObjectProvider<StringRedisTemplate> redisTemplates;

    private final String env;

    private final AtomicLong opErrors = new AtomicLong();

    public RedisRegionCache(ObjectProvider<RedisKit> redisKit, ObjectProvider<StringRedisTemplate> redisTemplates,
            Environment environment) {
        this.redisKit = redisKit;
        this.redisTemplates = redisTemplates;
        this.env = CacheEnv.of(environment);
    }

    /** 键里的 {@code {env}} 段（由 {@link CacheEnv} 唯一解析，与参数/字典/广播共用）。 */
    public String env() {
        return env;
    }

    /** 读；未命中、没配 Redis、Redis 不可用都是空（调用方回源）。 */
    public <T> Optional<T> get(CacheRegion region, String key, Class<T> type) {
        RedisKit kit = redisKit.getIfAvailable();
        if (kit == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(kit.get(region.redisKey(env, key), type));
        } catch (RedisUnavailableException e) {
            opError("get", region, e);
            return Optional.empty();
        }
    }

    /** 写（TTL 由调用方给，已含抖动）。 */
    public void put(CacheRegion region, String key, Object value, Duration ttl) {
        RedisKit kit = redisKit.getIfAvailable();
        if (kit == null) {
            return;
        }
        try {
            kit.set(region.redisKey(env, key), value, ttl);
        } catch (RedisUnavailableException e) {
            opError("put", region, e);
        }
    }

    /** 单键失效：值键 **与** 空值占位键一起删（只删值键会让"键重建后 60s 内仍读不到"）。 */
    public void delete(CacheRegion region, String key) {
        deleteKey(region.redisKey(env, key), region);
        deleteKey(region.nullKey(env, key), region);
    }

    /** 该区域该键是否有空值占位（防穿透的判定点）。 */
    public boolean isAbsent(CacheRegion region, String key) {
        RedisKit kit = redisKit.getIfAvailable();
        if (kit == null) {
            return false;
        }
        try {
            return kit.get(region.nullKey(env, key), String.class) != null;
        } catch (RedisUnavailableException e) {
            opError("null-get", region, e);
            return false;
        }
    }

    /** 写空值占位（短 TTL：3.6.1 的空值占位列）。 */
    public void markAbsent(CacheRegion region, String key, Duration ttl) {
        RedisKit kit = redisKit.getIfAvailable();
        if (kit == null) {
            return;
        }
        try {
            kit.set(region.nullKey(env, key), NULL_VALUE, ttl);
        } catch (RedisUnavailableException e) {
            opError("null-put", region, e);
        }
    }

    /**
     * SETNX 互斥重建锁（3.6.2 防击穿）。
     *
     * <p>{@code true} = 本次调用负责回源；Redis 不可用时也返回 {@code true}：拿不到锁时的等待（≤1s）
     * 是"等别人填缓存"，而 Redis 不可用时没人能填——等待只是白加延迟。
     */
    public boolean tryLock(CacheRegion region, String key, Duration ttl) {
        RedisKit kit = redisKit.getIfAvailable();
        if (kit == null) {
            return true;
        }
        try {
            return kit.setIfAbsent(region.lockKey(env, key), LOCK_VALUE, ttl);
        } catch (RedisUnavailableException e) {
            log.warn("互斥重建锁不可用，{} 直接回源（3.6.3：Redis 不可用时读路径仍要可用）：key={}",
                    region.code(), key);
            opError("lock", region, e);
            return true;
        }
    }

    /**
     * 释放锁（删键）。
     *
     * <p>已知取舍：持有者超过 TTL（10s）才回来时会删掉**下一个**持有者的锁。回源预算 &lt;20ms
     * （3.1.3），10s 只有"DB 卡住"才会越过；那时的损失是"多几个并发回源"，不是错误结果。
     * 换成 Lua 的 CAS 解锁需要给 {@link RedisKit} 加方法（P0 册 4.4 冻结），不值当。
     */
    public void unlock(CacheRegion region, String key) {
        RedisKit kit = redisKit.getIfAvailable();
        if (kit == null) {
            return;
        }
        try {
            kit.delete(region.lockKey(env, key));
        } catch (RedisUnavailableException e) {
            opError("unlock", region, e);
        }
    }

    /** SCAN 游标删除（{@code pattern} 由调用方按区域前缀拼好；3.6.3：不用 KEYS）。 */
    public void deleteByPattern(String pattern) {
        StringRedisTemplate template = redisTemplates.getIfAvailable();
        if (template == null) {
            return;
        }
        try (Cursor<String> cursor = template.scan(ScanOptions.scanOptions().match(pattern).count(SCAN_BATCH).build())) {
            List<String> batch = new ArrayList<>();
            while (cursor.hasNext()) {
                batch.add(cursor.next());
                if (batch.size() >= SCAN_BATCH) {
                    template.delete(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                template.delete(batch);
            }
        } catch (RuntimeException e) {
            opErrors.incrementAndGet();
            log.error("L2 前缀失效失败（pattern={}）：{}；L2 靠 TTL 兜底", pattern, e.getMessage());
        }
    }

    /** 缓存操作错误计数（3.7 的 {@code platform.cache.op.error}；T13 接 Micrometer 后转指标）。 */
    public long opErrorCount() {
        return opErrors.get();
    }

    private void deleteKey(String key, CacheRegion region) {
        RedisKit kit = redisKit.getIfAvailable();
        if (kit == null) {
            return;
        }
        try {
            kit.delete(key);
        } catch (RedisUnavailableException e) {
            opError("evict", region, e);
        }
    }

    private void opError(String op, CacheRegion region, RedisUnavailableException e) {
        opErrors.incrementAndGet();
        log.error("缓存操作失败（3.6.3：只记错误不抛，读路径回源）：op={}，region={}，原因={}", op, region.code(),
                e.getMessage());
    }
}
