package com.eaio.platform.infrastructure.persistence;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eaio.common.exception.SystemException;
import com.eaio.platform.api.dto.DictItemQuery;
import com.eaio.platform.api.dto.DictTypeQuery;
import com.eaio.platform.domain.dict.DictItem;
import com.eaio.platform.domain.dict.DictType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 数据字典的持久化门面（P1 册 3.2.2 的分层：application 不直接见 Mapper）。
 *
 * <p><b>为什么 Mapper 用 {@link ObjectProvider} 注入</b>：P0 冻结了"无数据库也能启动"，缺 Mapper 时
 * 必须在**真去读写时**以 {@code SystemException} 明确失败（10500），而不是装配期炸掉或静默返回空值
 * ——与 {@code ParamStore} 同一口径。
 *
 * <p>查询一律带 {@code deleted = false}（{@code @TableLogic} 自动追加）；排序字段走**白名单**，
 * 非法值忽略并 WARN（P1 册 5.3 分页公共入参的口径）。
 */
@Component
public class DictStore {

    private static final Logger log = LoggerFactory.getLogger(DictStore.class);

    /** 类型排序列白名单：DTO 字段名 → 实体列引用。 */
    private static final Map<String, SFunction<DictType, ?>> TYPE_ORDER_COLUMNS = Map.of(
            "typeCode", DictType::getTypeCode,
            "typeName", DictType::getTypeName,
            "status", DictType::getStatus,
            "updatedAt", DictType::getUpdatedAt,
            "id", DictType::getId);

    /** 项排序列白名单。 */
    private static final Map<String, SFunction<DictItem, ?>> ITEM_ORDER_COLUMNS = Map.of(
            "itemValue", DictItem::getItemValue,
            "itemLabel", DictItem::getItemLabel,
            "sortNo", DictItem::getSortNo,
            "status", DictItem::getStatus,
            "updatedAt", DictItem::getUpdatedAt,
            "id", DictItem::getId);

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final ObjectProvider<DictTypeMapper> typeMappers;
    private final ObjectProvider<DictItemMapper> itemMappers;

    public DictStore(ObjectProvider<DictTypeMapper> typeMappers, ObjectProvider<DictItemMapper> itemMappers) {
        this.typeMappers = typeMappers;
        this.itemMappers = itemMappers;
    }

    // ---------------------------------------------------------------- 类型

    /** 按编码取类型（未找到返回 {@code null}）。 */
    public DictType rowByTypeCode(String typeCode) {
        return types().selectOne(new LambdaQueryWrapper<DictType>().eq(DictType::getTypeCode, typeCode));
    }

    /** 按 ID 取类型（未找到返回 {@code null}）。 */
    public DictType rowById(long id) {
        return types().selectById(id);
    }

    /** 全部类型（字典区全量失效时枚举缓存键用；类型量级是个位到百位）。 */
    public List<DictType> allTypes() {
        return types().selectList(new LambdaQueryWrapper<>());
    }

    /** 分页（管理页）：条件为精确/模糊匹配，排序走白名单。 */
    public IPage<DictType> page(DictTypeQuery query) {
        DictTypeQuery actual = query == null ? new DictTypeQuery(null, null, null, null, null, null, null) : query;
        LambdaQueryWrapper<DictType> wrapper = new LambdaQueryWrapper<>();
        if (actual.typeCode() != null && !actual.typeCode().isBlank()) {
            wrapper.like(DictType::getTypeCode, actual.typeCode().trim());
        }
        if (actual.typeName() != null && !actual.typeName().isBlank()) {
            wrapper.like(DictType::getTypeName, actual.typeName().trim());
        }
        if (actual.status() != null && !actual.status().isBlank()) {
            wrapper.eq(DictType::getStatus, actual.status().trim().toUpperCase(Locale.ROOT));
        }
        if (!applyOrder(wrapper, TYPE_ORDER_COLUMNS, actual.orderBy(), actual.orderDir())) {
            wrapper.orderByAsc(DictType::getTypeCode);
        }
        return types().selectPage(new Page<>(pageNum(actual.pageNum()), pageSize(actual.pageSize())), wrapper);
    }

    /** 新增类型（返回受影响行数，正常为 1）。 */
    public int insert(DictType type) {
        return types().insert(type);
    }

    /** 按主键更新（{@code @Version} 乐观锁：版本不匹配返回 0，由调用方转 10003）。 */
    public int updateById(DictType type) {
        return types().updateById(type);
    }

    /** 逻辑删除类型（{@code @TableLogic}：写 {@code deleted = true}）。 */
    public int deleteById(DictType type) {
        return types().deleteById(type);
    }

    // ---------------------------------------------------------------- 项

    /** 某类型下的**全部未删除项**（含停用：{@code getLabel} 要能解析停用项，3.2.5），按 sort_no 升序。 */
    public List<DictItem> itemsByTypeCode(String typeCode) {
        return items().selectList(new LambdaQueryWrapper<DictItem>()
                .eq(DictItem::getTypeCode, typeCode)
                .orderByAsc(DictItem::getSortNo)
                .orderByAsc(DictItem::getId));
    }

    /** 某类型下未删除项的**数量**（类型列表的 {@code itemCount}，5.3）。 */
    public long countItems(String typeCode) {
        return items().selectCount(new LambdaQueryWrapper<DictItem>().eq(DictItem::getTypeCode, typeCode));
    }

    /**
     * 一批类型的项数量（类型列表页一次查完，避免"每行一次 count"）。
     *
     * <p>用 {@code selectMaps} 做分组聚合：逻辑删除条件由 MyBatis-Plus 自动追加（与其余 select 同源）。
     */
    public Map<String, Integer> countItemsByTypeCodes(Collection<String> typeCodes) {
        if (typeCodes == null || typeCodes.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> rows = items().selectMaps(new QueryWrapper<DictItem>()
                .select("type_code", "count(*) as item_count")
                .in("type_code", typeCodes)
                .groupBy("type_code"));
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object code = row.get("type_code");
            Object count = row.get("item_count");
            if (code != null && count instanceof Number number) {
                counts.put(String.valueOf(code), number.intValue());
            }
        }
        return counts;
    }

    /** 按 {@code (typeCode, itemValue)} 取项（未找到返回 {@code null}）。 */
    public DictItem rowByTypeValue(String typeCode, String itemValue) {
        return items().selectOne(new LambdaQueryWrapper<DictItem>()
                .eq(DictItem::getTypeCode, typeCode)
                .eq(DictItem::getItemValue, itemValue));
    }

    /** 按 ID 取项（未找到返回 {@code null}）。 */
    public DictItem rowItemById(long id) {
        return items().selectById(id);
    }

    /** 分页（管理页）；{@code typeCode} 为精确匹配（项列表总是"某类型下"的列表）。 */
    public IPage<DictItem> pageItems(DictItemQuery query) {
        DictItemQuery actual = query == null ? new DictItemQuery(null, null, null, null, null, null, null) : query;
        LambdaQueryWrapper<DictItem> wrapper = new LambdaQueryWrapper<>();
        if (actual.typeCode() != null && !actual.typeCode().isBlank()) {
            wrapper.eq(DictItem::getTypeCode, actual.typeCode().trim());
        }
        if (actual.itemValue() != null && !actual.itemValue().isBlank()) {
            wrapper.like(DictItem::getItemValue, actual.itemValue().trim());
        }
        if (actual.status() != null && !actual.status().isBlank()) {
            wrapper.eq(DictItem::getStatus, actual.status().trim().toUpperCase(Locale.ROOT));
        }
        if (!applyOrder(wrapper, ITEM_ORDER_COLUMNS, actual.orderBy(), actual.orderDir())) {
            wrapper.orderByAsc(DictItem::getTypeCode).orderByAsc(DictItem::getSortNo);
        }
        return items().selectPage(new Page<>(pageNum(actual.pageNum()), pageSize(actual.pageSize())), wrapper);
    }

    /** 新增项。 */
    public int insertItem(DictItem item) {
        return items().insert(item);
    }

    /** 按主键更新项（乐观锁同上）。 */
    public int updateItemById(DictItem item) {
        return items().updateById(item);
    }

    /** 逻辑删除项。 */
    public int deleteItemById(DictItem item) {
        return items().deleteById(item);
    }

    // ---------------------------------------------------------------- 内部

    /** 应用白名单排序；返回是否真的应用了（未应用时由调用方补默认排序，避免"无 ORDER BY"的分页）。 */
    private static <T> boolean applyOrder(LambdaQueryWrapper<T> wrapper, Map<String, SFunction<T, ?>> columns,
            String orderBy, String orderDir) {
        SFunction<T, ?> column = columns.get(orderBy == null ? "" : orderBy.trim());
        if (column == null) {
            if (orderBy != null && !orderBy.isBlank()) {
                log.warn("忽略非白名单排序列 orderBy={}（登记白名单：{}）", orderBy, columns.keySet());
            }
            return false;
        }
        boolean asc = !"desc".equalsIgnoreCase(orderDir == null ? "" : orderDir.trim());
        wrapper.orderBy(true, asc, column);
        return true;
    }

    private static long pageNum(Integer requested) {
        return requested == null || requested < 1 ? 1L : requested;
    }

    private static long pageSize(Integer requested) {
        if (requested == null) {
            return DEFAULT_PAGE_SIZE;
        }
        return requested < 1 || requested > MAX_PAGE_SIZE ? DEFAULT_PAGE_SIZE : requested;
    }

    private DictTypeMapper types() {
        DictTypeMapper mapper = typeMappers.getIfAvailable();
        if (mapper == null) {
            throw new SystemException("数据字典不可用：未配置数据库（无 DataSource/DictTypeMapper）。"
                    + "字典读写必须连库，不能静默返回空列表");
        }
        return mapper;
    }

    private DictItemMapper items() {
        DictItemMapper mapper = itemMappers.getIfAvailable();
        if (mapper == null) {
            throw new SystemException("数据字典不可用：未配置数据库（无 DataSource/DictItemMapper）。"
                    + "字典读写必须连库，不能静默返回空列表");
        }
        return mapper;
    }
}
