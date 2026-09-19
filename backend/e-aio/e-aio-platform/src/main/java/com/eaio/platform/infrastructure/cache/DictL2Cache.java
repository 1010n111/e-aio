package com.eaio.platform.infrastructure.cache;

import java.util.List;
import java.util.Optional;

import com.eaio.platform.api.CacheRegion;
import com.eaio.platform.application.cache.CacheManager;
import com.eaio.platform.domain.dict.DictItem;
import org.springframework.stereotype.Component;

/**
 * 字典二级缓存（L2，Redis；P1 册 3.6、4.6 的 {@code eaio:{env}:platform:dict:items:{typeCode}}）。
 *
 * <p>值是**该类型下的全部未删除项**（含停用项）：{@code getItems} 只要启用的，而 {@code getLabel}
 * 必须能解析停用项（3.2.5"停用 ≠ 不存在"），两者共用一份缓存，避免"停用项漏解析"这类只有历史
 * 数据才暴露的问题。
 *
 * <p><b>T6 起它只是 {@link CacheManager} 的薄适配器</b>（收敛而不是重写）：键前缀、TTL 抖动、
 * 空值占位、互斥重建、失败降级都只有 CacheManager 一份实现，本类保留的是**字典区的键形状**
 * （{@code typeCode}）与"一类型一键"这一条区域语义。L1 仍在 {@code DictResolver} 里（T5 已交付），
 * 所以这里只走 L2 原语。
 */
@Component
public class DictL2Cache {

    private final CacheManager cache;

    public DictL2Cache(CacheManager cache) {
        this.cache = cache;
    }

    /** 读；未命中、无 Redis、Redis 不可用都返回空（调用方回源）。 */
    public Optional<List<DictItem>> get(String typeCode) {
        return items(cache.l2Get(CacheRegion.DICT, regionKey(typeCode), Payload.class));
    }

    /** 写（TTL 30min ± 10%）。 */
    public void put(String typeCode, List<DictItem> items) {
        cache.l2Put(CacheRegion.DICT, regionKey(typeCode), new Payload(items), 0);
    }

    /** 删除某类型的缓存键（值 + 空值占位：只删值键会让"类型重建后 60s 内仍读不到"）。 */
    public void evict(String typeCode) {
        cache.l2Evict(CacheRegion.DICT, regionKey(typeCode));
    }

    /** 全量失效：按区域前缀 SCAN（3.6.3：扫描而不是枚举类型清单，新增类型不会被漏掉）。 */
    public void evictAll() {
        cache.l2EvictByPrefix(CacheRegion.DICT, "");
    }

    /** 该类型是否有空值占位（3.6.1：DICT 的空值占位 = 是(60s)）——命中即"查过且没有"，不再回源。 */
    public boolean isAbsent(String typeCode) {
        return cache.isAbsent(CacheRegion.DICT, regionKey(typeCode));
    }

    /** 写空值占位（短 TTL 防穿透：脏 typeCode 不再反复打库，但 20003 照抛）。 */
    public void markAbsent(String typeCode) {
        cache.markAbsent(CacheRegion.DICT, regionKey(typeCode));
    }

    /** 取互斥重建锁（3.6.2 防击穿）：拿到的人回源，其他人等这位填缓存。 */
    public boolean tryLock(String typeCode) {
        return cache.tryLock(CacheRegion.DICT, regionKey(typeCode));
    }

    /** 等锁的持有者填好缓存（≤{@code timeoutMillis}）；没等到返回空列表，调用方直接回源。 */
    public List<DictItem> awaitValue(String typeCode, long timeoutMillis) {
        return items(cache.awaitValue(CacheRegion.DICT, regionKey(typeCode), Payload.class, timeoutMillis))
                .orElse(List.of());
    }

    /** 释放互斥重建锁。 */
    public void unlock(String typeCode) {
        cache.unlock(CacheRegion.DICT, regionKey(typeCode));
    }

    /**
     * 完整 Redis 键（4.6 逐字：{@code eaio:{env}:platform:dict:items:{typeCode}}）。
     *
     * <p>env 与区域前缀由 {@link CacheRegion#DICT} 提供、由 {@link CacheManager} 拼接（键的唯一构造点仍
     * 是区域枚举）；本方法是"键模式"的单点：排障与单测按它断言，不必在各处复刻字面量。
     */
    String keyOf(String typeCode) {
        return cache.redisKey(CacheRegion.DICT, regionKey(typeCode));
    }

    /** 区域内的键形状（{@code typeCode}）：缓存操作传它，env 与区域前缀由 CacheManager 加。 */
    private static String regionKey(String typeCode) {
        if (typeCode == null || typeCode.isBlank()) {
            throw new IllegalArgumentException("字典类型编码不得为空（L2 键 = 区域前缀 + typeCode）");
        }
        return typeCode;
    }

    /** 缓存载荷：包一层 record 才能让门面按具体类型反序列化（{@code RedisKit.get} 只接受 Class）。 */
    public record Payload(List<DictItem> items) {
    }

    private static Optional<List<DictItem>> items(Optional<Payload> payload) {
        return payload.map(Payload::items).filter(items -> items != null);
    }
}
