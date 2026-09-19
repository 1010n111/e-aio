package com.eaio.platform.application.cache;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import com.eaio.common.json.JsonUtils;
import com.eaio.platform.api.CacheKey;
import com.eaio.platform.api.CacheRegion;
import com.eaio.platform.infrastructure.cache.CacheInvalidationPublisher;
import com.eaio.platform.infrastructure.cache.CaffeineRegionCache;
import com.eaio.platform.infrastructure.cache.RedisRegionCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 统一缓存管理（P1 册 3.6.2/3.6.3/3.6.4）：两级缓存 + 三防护 + 统一的失效口径。
 *
 * <p><b>三防护各自落在哪一行</b>：
 * <ul>
 *   <li><b>穿透</b>：{@link #isAbsent}/{@link #markAbsent} 的空值占位（短 TTL，仅 3.6.1 标"是"的区域，
 *       默认 60s）——回源查到"没有"时写占位，后续请求在 L1/L2 就返回空，不再落到 DB。</li>
 *   <li><b>击穿</b>：{@link #tryLock}（SETNX，TTL 10s）+ {@link #awaitValue}（等待 ≤1s、50ms 轮询、
 *       超时直接回源）。只有拿到锁的调用者回源，同一进程/多进程都适用。</li>
 *   <li><b>雪崩</b>：{@link #jittered} 的 ±10% TTL 抖动 + L1 必然短于 L2（{@link #l1Ttl} 取 min）。</li>
 * </ul>
 *
 * <p><b>两条读写入口，别混</b>：{@link #get}/{@link #put}/{@link #evict}/{@link #evictByPrefix} 是
 * {@code CacheApi} 的跨模块口径（带本机 L1）；{@code l2*} 前缀的是 L2 原语，给**自带 L1** 的既有能力用
 * （参数中心/数据字典的 L1 在各自的 resolver 里，T3/T4 已交付）：同一区域不能有两个 L1 同时写，
 * 故它们只经 L2，L1 由自己的 resolver 清——失效时两边都被清（本机调用方清自己，广播清其他实例）。
 *
 * <p><b>失效是"三件事"</b>（3.6.3）：删 L2（值 + 空值占位）→ 清本机 L1 → 广播其他实例；前缀失效走
 * SCAN 游标而不是 KEYS，且**连空值占位一起扫**（漏扫占位会让"键重建后 60s 内仍读不到"）。
 */
@Component
public class CacheManager {

    private static final Logger log = LoggerFactory.getLogger(CacheManager.class);

    /** 单值上限：序列化后超过它就拒绝入缓存（3.6.4），业务仍返回正确结果。 */
    public static final int MAX_VALUE_BYTES = 64 * 1024;

    /** 互斥重建锁的 TTL（3.6.2：SETNX 10s）。 */
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);

    /** 未拿到锁时的等待上限（3.6.2：≤1s，超时直接回源）。 */
    public static final long LOCK_WAIT_MILLIS = 1000L;

    /** 等待时的轮询间隔（3.6.2：50ms）。 */
    static final long LOCK_POLL_MILLIS = 50L;

    /** TTL 抖动下界（3.6.2：±10%）。 */
    static final double JITTER_MIN = 0.9;

    /** TTL 抖动幅度。 */
    static final double JITTER_SPAN = 0.2;

    /** 空值占位 TTL 的配置键（7.2 的 {@code platform.cache.null-ttl-seconds}，默认 60）。 */
    private static final String NULL_TTL_PROPERTY = "eaio.cache.null-ttl-seconds";

    /** 按区域覆盖 L2 TTL 的配置键前缀（7.2 的 {@code platform.cache.*.l2-ttl-seconds}）。 */
    private static final String L2_TTL_PREFIX = "eaio.cache.";

    /** 既有的全局 L2 TTL（P1-2 册 3.15 已登记，不能静默失效；按区域覆盖用上面那个）。 */
    private static final String L2_TTL_FALLBACK = "eaio.cache.redis.ttl-seconds";

    private final CaffeineRegionCache l1;

    private final RedisRegionCache l2;

    private final CacheInvalidationPublisher publisher;

    private final Environment environment;

    private final String env;

    private final Map<CacheRegion, AtomicLong> rebuilds = new EnumMap<>(CacheRegion.class);

    public CacheManager(CaffeineRegionCache l1, RedisRegionCache l2, CacheInvalidationPublisher publisher,
            Environment environment) {
        this.l1 = l1;
        this.l2 = l2;
        this.publisher = publisher;
        this.environment = environment;
        this.env = l2.env();
        for (CacheRegion region : CacheRegion.values()) {
            rebuilds.put(region, new AtomicLong());
        }
    }

    // ------------------------------------------------------------------ CacheApi 口径（L1 + L2）

    /** 读：L1 → L2（都未命中、Redis 不可用都是空；调用方回源）。 */
    public <T> Optional<T> get(CacheKey key, Class<T> type) {
        Object local = l1.get(key.region(), key.key());
        if (local != null) {
            return Optional.of(type.cast(local));
        }
        return l2.get(key.region(), key.key(), type);
    }

    /** 写：值为 {@code null} 或序列化后超过 64KB 时拒绝入缓存（3.6.4），其余写 L2（抖动 TTL）+ L1。 */
    public void put(CacheKey key, Object value, int ttlSeconds) {
        if (rejected(key.region(), key.key(), value)) {
            return;
        }
        l2.put(key.region(), key.key(), value, jittered(l2Ttl(key.region(), ttlSeconds)));
        l1.put(key.region(), key.key(), value, l1Ttl(key.region(), ttlSeconds));
    }

    /** 失效单键：删 L2（值 + 空值占位）+ 清本机 L1 + 广播。 */
    public void evict(CacheKey key) {
        l1.evict(key.region(), key.key());
        l2.delete(key.region(), key.key());
        publisher.publishKey(key.region(), key.key());
    }

    /** 按前缀失效（SCAN，不用 KEYS）；{@code keyPrefix} 为空串表示该区域全量。 */
    public void evictByPrefix(CacheRegion region, String keyPrefix) {
        if (keyPrefix == null) {
            throw new IllegalArgumentException("缓存键前缀不得为空（空串表示该区域全量失效）：region=" + region.code());
        }
        CacheRegion.requirePlainKey(keyPrefix);
        clearLocalByPrefix(region, keyPrefix);
        l2EvictByPrefix(region, keyPrefix);
        publisher.publishPrefix(region, keyPrefix);
    }

    // ------------------------------------------------------------------ 广播接收侧（只清本机 L1）

    /** 清本机 L1 的单键（失效广播的接收侧）。 */
    public void clearLocal(CacheKey key) {
        l1.evict(key.region(), key.key());
    }

    /** 清本机 L1 的前缀（{@code keyPrefix} 为空串表示该区域全清）。 */
    public void clearLocalByPrefix(CacheRegion region, String keyPrefix) {
        l1.evictByPrefix(region, keyPrefix == null ? "" : keyPrefix);
    }

    // ------------------------------------------------------------------ L2 原语（给自带 L1 的既有能力用）

    /** L2 读（不经本机 L1）。 */
    public <T> Optional<T> l2Get(CacheRegion region, String key, Class<T> type) {
        return l2.get(region, key, type);
    }

    /** L2 写（{@code ttlSeconds ≤ 0} 用区域默认 TTL，实际写入叠加 ±10% 抖动）。 */
    public void l2Put(CacheRegion region, String key, Object value, int ttlSeconds) {
        if (rejected(region, key, value)) {
            return;
        }
        l2.put(region, key, value, jittered(l2Ttl(region, ttlSeconds)));
    }

    /** L2 单键失效（值 + 空值占位）。 */
    public void l2Evict(CacheRegion region, String key) {
        l2.delete(region, key);
    }

    /** L2 前缀失效：{@code keyPrefix} 是区域内前缀（空串 = 全区域）。 */
    public void l2EvictByPrefix(CacheRegion region, String keyPrefix) {
        l2EvictByPattern(region, keyPrefix + "*");
    }

    /**
     * L2 通配失效：{@code patternSuffix} 是区域前缀之后的部分，可含 {@code *}。
     *
     * <p>存在的理由是参数区：参数键在 L2 里是 {@code {orgId}:{key}}，改 SYSTEM 级值要清**所有组织**的
     * 变体（3.1.5），而组织清单在 platform 侧不可枚举——只能用 {@code *:{key}} 这种"后缀精确"的通配，
     * 前缀语义表达不了它。值键与空值占位键用同一个后缀一起扫。
     */
    public void l2EvictByPattern(CacheRegion region, String patternSuffix) {
        l2.deleteByPattern(region.prefix(env) + patternSuffix);
        l2.deleteByPattern(region.nullPrefix(env) + patternSuffix);
    }

    /** 该区域该键是否有空值占位（有 = 回源查过且没有，直接返回空）。 */
    public boolean isAbsent(CacheRegion region, String key) {
        return l2.isAbsent(region, key);
    }

    /** 写空值占位；区域未登记空值占位（3.6.1 的最后一列为"否"）时是空操作。 */
    public void markAbsent(CacheRegion region, String key) {
        if (!region.nullCaching()) {
            return;
        }
        l2.markAbsent(region, key, Duration.ofSeconds(environment.getProperty(NULL_TTL_PROPERTY, Long.class, 60L)));
    }

    /** 取互斥重建锁；拿到返回 {@code true}（同时累加"重建授权"计数，供 3.7 的命中/失效指标用）。 */
    public boolean tryLock(CacheRegion region, String key) {
        boolean acquired = l2.tryLock(region, key, LOCK_TTL);
        if (acquired) {
            rebuilds.get(region).incrementAndGet();
        }
        return acquired;
    }

    /** 等别人把缓存填好（≤{@code timeoutMillis}，50ms 轮询）；等不到就返回空，调用方直接回源。 */
    public <T> Optional<T> awaitValue(CacheRegion region, String key, Class<T> type, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMillis);
        while (true) {
            Optional<T> value = l2.get(region, key, type);
            if (value.isPresent() || l2.isAbsent(region, key) || System.currentTimeMillis() >= deadline) {
                return value;
            }
            try {
                Thread.sleep(LOCK_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
    }

    /** 释放互斥重建锁。 */
    public void unlock(CacheRegion region, String key) {
        l2.unlock(region, key);
    }

    // ------------------------------------------------------------------ 键与 TTL、指标

    /** 区域内的值键（含 env 与区域前缀）：键的唯一构造点在 {@link CacheRegion}。 */
    public String redisKey(CacheRegion region, String key) {
        return region.redisKey(env, key);
    }

    /** L2 TTL：显式 &gt; {@code eaio.cache.<region>.l2-ttl-seconds} &gt; {@code eaio.cache.redis.ttl-seconds} &gt; 区域登记值。 */
    public Duration l2Ttl(CacheRegion region, int ttlSeconds) {
        if (ttlSeconds > 0) {
            return Duration.ofSeconds(ttlSeconds);
        }
        Long perRegion = environment.getProperty(L2_TTL_PREFIX + kebab(region) + ".l2-ttl-seconds", Long.class);
        if (perRegion != null && perRegion > 0) {
            return Duration.ofSeconds(perRegion);
        }
        Long global = environment.getProperty(L2_TTL_FALLBACK, Long.class);
        if (global != null && global > 0) {
            return Duration.ofSeconds(global);
        }
        return Duration.ofSeconds(region.l2TtlSeconds());
    }

    /** 缓存操作错误计数（3.7 的 {@code platform.cache.op.error}；T13 接 Micrometer 后转指标）。 */
    public long opErrorCount() {
        return l2.opErrorCount();
    }

    /** 某区域"被授权重建"的次数（互斥重建是否真的只放行了一个人，集成测试直接断它）。 */
    public long rebuildCount(CacheRegion region) {
        return rebuilds.get(region).get();
    }

    /** TTL ±10% 抖动（纯函数，单测直击上下界）。 */
    static Duration jittered(Duration base) {
        double factor = JITTER_MIN + ThreadLocalRandom.current().nextDouble() * JITTER_SPAN;
        return Duration.ofMillis(Math.max(1L, (long) (base.toMillis() * factor)));
    }

    /** L1 条目的 TTL：区域登记值封顶（显式 TTL 更短时用显式值），保证 L1 先于 L2 过期（3.6.2）。 */
    private static Duration l1Ttl(CacheRegion region, int ttlSeconds) {
        if (ttlSeconds <= 0) {
            return Duration.ofSeconds(region.l1TtlSeconds());
        }
        return Duration.ofSeconds(Math.min(ttlSeconds, region.l1TtlSeconds()));
    }

    private static String kebab(CacheRegion region) {
        return region.code().replace('_', '-');
    }

    /** 值能不能入缓存：{@code null} 与超过 64KB 的序列化结果都拒绝（3.6.4），业务结果不受影响。 */
    private static boolean rejected(CacheRegion region, String key, Object value) {
        if (value == null) {
            log.warn("缓存值为 null，拒绝入缓存（空值请用空值占位表达）：region={}，key={}", region.code(), key);
            return true;
        }
        int bytes = JsonUtils.toJson(value).getBytes(StandardCharsets.UTF_8).length;
        if (bytes <= MAX_VALUE_BYTES) {
            return false;
        }
        log.warn("缓存值序列化后 {} 字节，超过 {} 字节上限，拒绝入缓存（3.6.4：业务仍返回正确结果）：region={}，key={}",
                bytes, MAX_VALUE_BYTES, region.code(), key);
        return true;
    }
}
