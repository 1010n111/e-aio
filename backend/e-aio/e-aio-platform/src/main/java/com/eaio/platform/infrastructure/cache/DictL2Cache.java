package com.eaio.platform.infrastructure.cache;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import com.eaio.common.redis.RedisKeys;
import com.eaio.common.redis.RedisKit;
import com.eaio.common.redis.RedisUnavailableException;
import com.eaio.platform.domain.dict.DictItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 字典二级缓存（L2，Redis；P1 册 4.6 的 {@code eaio:{env}:platform:dict:items:{typeCode}}）。
 *
 * <p>值是**该类型下的全部未删除项**（含停用项）：{@code getItems} 只要启用的，而 {@code getLabel}
 * 必须能解析停用项（3.2.5"停用 ≠ 不存在"），两者共用一份缓存，避免"停用项漏解析"这类只有历史
 * 数据才暴露的问题。
 *
 * <p>TTL 30min ± 10%（4.6）：抖动是为了防止同一时刻写入的键整批同时过期。
 *
 * <p>Redis 不可用（没配 / 连不上）时：读=当作未命中、写/删=记 WARN 后放弃。缓存降级只影响新鲜度，
 * 不影响"能不能读"——回源 DB 永远是可用的兜底（与 3.1.5 的口径一致）。
 */
@Component
public class DictL2Cache {

    private static final Logger log = LoggerFactory.getLogger(DictL2Cache.class);

    private final ObjectProvider<RedisKit> redisKit;
    private final String env;
    private final Duration ttl;

    public DictL2Cache(ObjectProvider<RedisKit> redisKit, Environment environment) {
        this.redisKit = redisKit;
        this.env = CacheEnv.of(environment);
        this.ttl = Duration.ofSeconds(environment.getProperty("eaio.cache.redis.ttl-seconds", Long.class, 1800L));
    }

    /** 读；未命中、无 Redis、Redis 不可用都返回空（调用方回源）。 */
    public Optional<List<DictItem>> get(String typeCode) {
        RedisKit kit = redisKit.getIfAvailable();
        if (kit == null) {
            return Optional.empty();
        }
        try {
            Payload payload = kit.get(keyOf(typeCode), Payload.class);
            return payload == null || payload.items() == null ? Optional.empty() : Optional.of(payload.items());
        } catch (RedisUnavailableException e) {
            log.warn("L2 缓存不可用，字典 {} 回源数据库。原因：{}", typeCode, e.getMessage());
            return Optional.empty();
        }
    }

    /** 写（TTL 30min ± 10%）。 */
    public void put(String typeCode, List<DictItem> items) {
        RedisKit kit = redisKit.getIfAvailable();
        if (kit == null) {
            return;
        }
        try {
            kit.set(keyOf(typeCode), new Payload(items), jitter(ttl));
        } catch (RedisUnavailableException e) {
            log.warn("L2 缓存写入跳过（Redis 不可用）：{}", typeCode);
        }
    }

    /** 删除某类型的缓存键（字典键是"一类型一键"，不需要前缀扫描）。 */
    public void evict(String typeCode) {
        RedisKit kit = redisKit.getIfAvailable();
        if (kit == null) {
            return;
        }
        try {
            kit.delete(keyOf(typeCode));
        } catch (RedisUnavailableException e) {
            log.warn("L2 缓存失效失败（key 已过期前靠 TTL 兜底）：{}，原因：{}", typeCode, e.getMessage());
        }
    }

    /** 键模式唯一构造点（4.6）：写方与失效方各拼一次是最容易漂移的一类 bug。 */
    String keyOf(String typeCode) {
        return RedisKeys.of(env, "platform", "dict", "items", typeCode);
    }

    /** 缓存载荷：包一层 record 才能让门面按具体类型反序列化（{@code RedisKit.get} 只接受 Class）。 */
    public record Payload(List<DictItem> items) {
    }

    private static Duration jitter(Duration base) {
        double factor = 0.9 + ThreadLocalRandom.current().nextDouble() * 0.2;
        return Duration.ofMillis(Math.max(1L, (long) (base.toMillis() * factor)));
    }
}
