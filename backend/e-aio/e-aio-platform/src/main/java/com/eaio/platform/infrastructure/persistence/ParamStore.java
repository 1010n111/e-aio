package com.eaio.platform.infrastructure.persistence;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eaio.common.exception.SystemException;
import com.eaio.platform.api.dto.ParamQuery;
import com.eaio.platform.domain.param.ParamChangeLogItem;
import com.eaio.platform.domain.param.ParamItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 参数中心的持久化门面（P1 册 3.1.2 的分层：application 不直接见 Mapper）。
 *
 * <p><b>为什么 Mapper 用 {@link ObjectProvider} 注入</b>：P0 冻结了"无数据库也能启动"（基础配置排除
 * 数据源自动配置，无库时 MapPer 根本不会注册）。若这里强依赖 Mapper，无库启动会直接失败——那是 P0
 * 验收的回归。所以缺 Mapper 时**不在装配期炸**，而在**真去读写时**以 {@code SystemException}
 * 明确失败（10500），不静默返回空值：参数读不到就静默用默认值，是配置事故里最难查的一类。
 *
 * <p>查询一律带 {@code deleted = false}（{@code @TableLogic} 自动追加）；排序字段走**白名单**，
 * 非法值忽略并 WARN（P1 册 5.3 分页公共入参的口径）。
 */
@Component
public class ParamStore {

    private static final Logger log = LoggerFactory.getLogger(ParamStore.class);

    /** 排序列白名单：DTO 字段名 → 实体列引用。白名单外的值忽略（不报错、不拼 SQL）。 */
    private static final Map<String, SFunction<ParamItem, ?>> ORDER_COLUMNS = Map.of(
            "paramKey", ParamItem::getParamKey,
            "paramLevel", ParamItem::getParamLevel,
            "ownerId", ParamItem::getOwnerId,
            "paramGroup", ParamItem::getParamGroup,
            "updatedAt", ParamItem::getUpdatedAt,
            "id", ParamItem::getId);

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final ObjectProvider<ParamMapper> paramMappers;
    private final ObjectProvider<ParamChangeLogMapper> logMappers;

    public ParamStore(ObjectProvider<ParamMapper> paramMappers, ObjectProvider<ParamChangeLogMapper> logMappers) {
        this.paramMappers = paramMappers;
        this.logMappers = logMappers;
    }

    /** 该键的全部候选行（0..3 行；一次查询取三级，避免三次往返，3.1.3）。 */
    public List<ParamItem> rowsByKey(String paramKey) {
        return params().selectList(new LambdaQueryWrapper<ParamItem>().eq(ParamItem::getParamKey, paramKey));
    }

    /** 按分组取全量（管理页/批量读取）。 */
    public List<ParamItem> rowsByGroup(String paramGroup) {
        return params().selectList(new LambdaQueryWrapper<ParamItem>().eq(ParamItem::getParamGroup, paramGroup));
    }

    /** 全量行（缓存预热/导出）。 */
    public List<ParamItem> allRows() {
        return params().selectList(new LambdaQueryWrapper<>());
    }

    /** 单行（含已删除行不可见）。 */
    public ParamItem rowById(long id) {
        return params().selectById(id);
    }

    /** 按唯一键（键 + 级别 + 归属）取行；未找到返回 {@code null}。 */
    public ParamItem rowByKeyLevelOwner(String paramKey, String paramLevel, long ownerId) {
        return params().selectOne(new LambdaQueryWrapper<ParamItem>()
                .eq(ParamItem::getParamKey, paramKey)
                .eq(ParamItem::getParamLevel, paramLevel)
                .eq(ParamItem::getOwnerId, ownerId));
    }

    /** 分页（管理页）：条件为精确/模糊匹配，排序走白名单。 */
    public IPage<ParamItem> page(ParamQuery query) {
        ParamQuery actual = query == null
                ? new ParamQuery(null, null, null, null, null, null, null, null)
                : query;
        long pageNum = actual.pageNum() == null || actual.pageNum() < 1 ? 1L : actual.pageNum();
        long pageSize = actual.pageSize() == null ? DEFAULT_PAGE_SIZE : actual.pageSize();
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            pageSize = DEFAULT_PAGE_SIZE;
        }
        LambdaQueryWrapper<ParamItem> wrapper = new LambdaQueryWrapper<>();
        if (actual.paramKey() != null && !actual.paramKey().isBlank()) {
            wrapper.like(ParamItem::getParamKey, actual.paramKey().trim());
        }
        if (actual.paramLevel() != null && !actual.paramLevel().isBlank()) {
            wrapper.eq(ParamItem::getParamLevel, actual.paramLevel().trim().toUpperCase(Locale.ROOT));
        }
        if (actual.ownerId() != null) {
            wrapper.eq(ParamItem::getOwnerId, actual.ownerId());
        }
        if (actual.paramGroup() != null && !actual.paramGroup().isBlank()) {
            wrapper.eq(ParamItem::getParamGroup, actual.paramGroup().trim());
        }
        applyOrder(wrapper, actual);
        return params().selectPage(new Page<>(pageNum, pageSize), wrapper);
    }

    private void applyOrder(LambdaQueryWrapper<ParamItem> wrapper, ParamQuery query) {
        SFunction<ParamItem, ?> column = ORDER_COLUMNS.get(query.orderBy() == null ? "" : query.orderBy().trim());
        if (column == null) {
            if (query.orderBy() != null && !query.orderBy().isBlank()) {
                log.warn("忽略非白名单排序列 orderBy={}（登记白名单：{}）", query.orderBy(), ORDER_COLUMNS.keySet());
            }
            wrapper.orderByAsc(ParamItem::getParamKey).orderByAsc(ParamItem::getParamLevel);
            return;
        }
        boolean asc = !"desc".equalsIgnoreCase(query.orderDir() == null ? "" : query.orderDir().trim());
        wrapper.orderBy(true, asc, column);
    }

    /** 新增（返回受影响行数，正常为 1）。 */
    public int insert(ParamItem item) {
        return params().insert(item);
    }

    /** 按主键更新（{@code @Version} 乐观锁：版本不匹配时返回 0，由调用方转 10003）。 */
    public int updateById(ParamItem item) {
        return params().updateById(item);
    }

    /** 逻辑删除（{@code @TableLogic}：写 {@code deleted = true}）。 */
    public int deleteById(ParamItem item) {
        return params().deleteById(item);
    }

    /** 追加变更历史（只追加，永不更新/删除）。 */
    public void appendLog(ParamChangeLogItem log) {
        logs().insert(log);
    }

    private ParamMapper params() {
        ParamMapper mapper = paramMappers.getIfAvailable();
        if (mapper == null) {
            throw new SystemException("参数中心不可用：未配置数据库（无 DataSource/ParamMapper）。"
                    + "参数读写必须连库，不能静默用默认值替代");
        }
        return mapper;
    }

    private ParamChangeLogMapper logs() {
        ParamChangeLogMapper mapper = logMappers.getIfAvailable();
        if (mapper == null) {
            throw new SystemException("参数中心不可用：未配置数据库（无 DataSource/ParamChangeLogMapper）");
        }
        return mapper;
    }
}
