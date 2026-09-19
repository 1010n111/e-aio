package com.eaio.platform.application.dict;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.eaio.common.exception.BusinessException;
import com.eaio.platform.api.PlatformErrorCode;
import com.eaio.platform.domain.dict.DictItem;
import com.eaio.platform.domain.dict.DictStatus;
import com.eaio.platform.domain.dict.DictType;
import com.eaio.platform.infrastructure.cache.DictL2Cache;
import com.eaio.platform.infrastructure.persistence.DictStore;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 字典读路径：两级缓存 + 标签解析（P1 册 3.2.2、3.2.3）。
 *
 * <p>取值链：L1（Caffeine，本机，键 = typeCode）→ L2（Redis）→ DB（dict_type 存在性 + dict_item 全量）
 * → 抛 20003（类型不存在，**不写空值占位**）。
 *
 * <p><b>缓存里存的是"该类型下全部未删除项"（含停用）</b>，不是"启用项"：3.2.5 要求停用项对
 * {@code getItems} 不可见、但 {@code getLabel} 仍可解析。若按 3.2.3 流程图字面只缓存启用项，
 * 停用项的历史值就会在列表页退化成原始值——那正是 3.2.1"历史引用不断"要防的事。
 *
 * <p>类型不存在的处理是**抛 20003**（3.2.5：这是配置错误，必须显式暴露），不缓存空值：
 * 脏 typeCode 是代码拼错，缓存 60s 只会把错误藏起来；错误路径也不在热路径上。
 */
@Component
public class DictResolver {

    private static final Logger log = LoggerFactory.getLogger(DictResolver.class);

    /** L1 最大条目数（字典类型量级是个位到百位，这个上限只用于挡住异常输入）。 */
    private static final long L1_MAX_SIZE = 10_000L;

    /** 项数量告警阈值（3.2.5：超过它仍全量返回，但提示"该字典应改为业务表"）。 */
    private static final int HUGE_TYPE_THRESHOLD = 2000;

    private final DictStore store;
    private final DictL2Cache l2;
    private final Cache<String, List<DictItem>> l1;
    private final boolean l1Enabled;

    public DictResolver(DictStore store, DictL2Cache l2, Environment environment) {
        this.store = store;
        this.l2 = l2;
        this.l1Enabled = environment.getProperty("eaio.cache.local.enabled", Boolean.class, true);
        Duration l1Ttl = Duration.ofSeconds(environment.getProperty("eaio.dict.cache-ttl-seconds", Long.class, 60L));
        this.l1 = Caffeine.newBuilder().maximumSize(L1_MAX_SIZE).expireAfterWrite(l1Ttl).build();
    }

    /** 类型下的**全部未删除项**（含停用），按 sortNo 升序；类型不存在抛 20003。 */
    public List<DictItem> items(String typeCode) {
        if (l1Enabled) {
            List<DictItem> hit = l1.getIfPresent(typeCode);
            if (hit != null) {
                return hit;
            }
        }
        Optional<List<DictItem>> cached = l2.get(typeCode);
        if (cached.isPresent()) {
            return cacheL1(typeCode, cached.get());
        }
        DictType type = store.rowByTypeCode(typeCode);
        if (type == null) {
            throw new BusinessException(PlatformErrorCode.DICT_TYPE_NOT_FOUND, "字典类型不存在：" + typeCode);
        }
        List<DictItem> items = store.itemsByTypeCode(typeCode);
        if (items.size() > HUGE_TYPE_THRESHOLD) {
            log.warn("字典 {} 的项数异常大（{} > {}）：仍全量返回，但该字典应改为业务表（3.2.5）",
                    typeCode, items.size(), HUGE_TYPE_THRESHOLD);
        }
        l2.put(typeCode, items);
        return cacheL1(typeCode, items);
    }

    /** 启用项（{@code getItems} 的语义），按 sortNo 升序；类型不存在抛 20003。 */
    public List<DictItem> enabledItems(String typeCode) {
        return items(typeCode).stream()
                .filter(item -> DictStatus.ENABLED.name().equals(item.getStatus()))
                .toList();
    }

    /**
     * 标签解析（3.2.1 的核心语义）：命中返回值对应的 label，**未命中返回入参原值 + WARN，不抛异常**。
     *
     * <p>类型不存在时同样返回原值 + WARN（而不是把 20003 抛出去）：3.2.1 的理由是"列表页渲染不能因为
     * 脏字典整页失败"，而"类型编码拼错"与"值不在字典里"对渲染是同一种脏数据。要暴露配置错误用
     * {@link #items(String)}（{@code getItems} 会抛 20003）。
     */
    public String getLabel(String typeCode, String value) {
        if (value == null) {
            return null;
        }
        List<DictItem> items;
        try {
            items = items(typeCode);
        } catch (BusinessException e) {
            log.warn("字典 {} 不存在，getLabel 返回原值：value={}（3.2.1：标签解析不阻断渲染）", typeCode, value);
            return value;
        }
        for (DictItem item : items) {
            if (value.equals(item.getItemValue())) {
                return item.getItemLabel();
            }
        }
        log.warn("字典 {} 中不存在值 {}，getLabel 返回原值（指标 platform.dict.label.miss 由监测票落地，3.2.5）",
                typeCode, value);
        return value;
    }

    /** 失效某类型（本机 L1 + L2）：{@code typeCode} 为空表示字典区全量失效。 */
    public void invalidate(String typeCode) {
        if (typeCode == null || typeCode.isBlank()) {
            invalidateAll();
            return;
        }
        l1.invalidate(typeCode);
        l2.evict(typeCode);
        log.debug("字典缓存已失效：typeCode={}", typeCode);
    }

    /** 全量失效：L1 清空 + 逐个类型删 L2（字典键是"一类型一键"，键集合就是类型集合）。 */
    public void invalidateAll() {
        l1.invalidateAll();
        for (DictType type : store.allTypes()) {
            l2.evict(type.getTypeCode());
        }
        log.info("字典缓存已全量失效（L1 + L2）");
    }

    private List<DictItem> cacheL1(String typeCode, List<DictItem> items) {
        if (l1Enabled) {
            l1.put(typeCode, items);
        }
        return items;
    }
}
