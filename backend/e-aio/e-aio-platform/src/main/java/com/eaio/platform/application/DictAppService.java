package com.eaio.platform.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.eaio.common.api.ErrorCode;
import com.eaio.common.api.PageResult;
import com.eaio.common.exception.BusinessException;
import com.eaio.common.id.IdGenerator;
import com.eaio.platform.api.PlatformErrorCode;
import com.eaio.platform.api.dto.DictItemDTO;
import com.eaio.platform.api.dto.DictItemQuery;
import com.eaio.platform.api.dto.DictItemSaveCmd;
import com.eaio.platform.api.dto.DictTypeDTO;
import com.eaio.platform.api.dto.DictTypeQuery;
import com.eaio.platform.api.dto.DictTypeSaveCmd;
import com.eaio.platform.application.dict.DictDtoMapper;
import com.eaio.platform.application.dict.DictResolver;
import com.eaio.platform.application.param.ParamContextProvider;
import com.eaio.platform.domain.dict.DictItem;
import com.eaio.platform.domain.dict.DictStatus;
import com.eaio.platform.domain.dict.DictType;
import com.eaio.platform.events.DictChangedEvent;
import com.eaio.platform.infrastructure.persistence.DictStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 数据字典应用服务（P1 册 3.2.2）：读路径委托 {@link DictResolver} 与 {@link DictStore}，
 * 写路径做"校验 → 落库 → 发 {@link DictChangedEvent} → 失效"（3.2.3）。
 *
 * <p><b>REST 与跨模块 {@code DictApi} 共用本服务</b>（裁决 2.4.3）：{@code DictController} 调
 * {@code addType/upType/delType/...}，{@code DictApiImpl} 调同名方法。校验、事件、失效各只写一遍。
 *
 * <p><b>错误码只用段内既有号</b>（7.1，T1 已冻结，不得新增号）：
 * <ul>
 *   <li>20003 {@code DICT_TYPE_NOT_FOUND}：类型不存在；**也用于"类型编码已存在"**（5.2 给
 *       {@code dictType/Add} 的错误码只有 20003，段内没有 dict 重复码）；</li>
 *   <li>20007 {@code DICT_ITEM_DUPLICATED}：同类型下 {@code item_value} 重复；</li>
 *   <li>20008 {@code DICT_TYPE_IN_USE}：类型下仍有项（含停用项——它们仍会被 {@code getLabel} 解析）；</li>
 *   <li>20005 {@code PARAM_BUILTIN_READONLY}：内置只读（段内唯一的内置只读码，跨能力复用）。</li>
 * </ul>
 */
@Service
public class DictAppService {

    private static final Logger log = LoggerFactory.getLogger(DictAppService.class);

    private static final String ACTION_ADD = "ADD";
    private static final String ACTION_UP = "UP";
    private static final String ACTION_DEL = "DEL";

    private final DictStore store;
    private final DictResolver resolver;
    private final DictDtoMapper dtoMapper;
    private final ParamContextProvider contexts;
    private final ApplicationEventPublisher events;
    private final IdGenerator idGenerator;

    public DictAppService(DictStore store, DictResolver resolver, DictDtoMapper dtoMapper,
            ParamContextProvider contexts, ApplicationEventPublisher events, IdGenerator idGenerator) {
        this.store = store;
        this.resolver = resolver;
        this.dtoMapper = dtoMapper;
        this.contexts = contexts;
        this.events = events;
        this.idGenerator = idGenerator;
    }

    // ---------------------------------------------------------------- 读

    /** 类型分页（管理页）；每行的 {@code itemCount} 由一次分组聚合查出，不做逐行 count。 */
    public PageResult<DictTypeDTO> pageTypes(DictTypeQuery query) {
        IPage<DictType> page = store.page(query);
        List<DictType> rows = page.getRecords();
        Map<String, Integer> counts = store.countItemsByTypeCodes(
                rows.stream().map(DictType::getTypeCode).toList());
        List<DictTypeDTO> records = new ArrayList<>(rows.size());
        for (DictType row : rows) {
            records.add(dtoMapper.toDto(row, counts.getOrDefault(row.getTypeCode(), 0)));
        }
        return PageResult.from(page.getCurrent(), page.getSize(), page.getTotal(), records);
    }

    /** 按 ID 查类型；不存在抛 20003（{@code /platform/dictType/Get}）。 */
    public DictTypeDTO typeById(long id) {
        DictType row = store.rowById(id);
        if (row == null) {
            throw new BusinessException(PlatformErrorCode.DICT_TYPE_NOT_FOUND, "字典类型不存在：id=" + id);
        }
        return dtoMapper.toDto(row, (int) store.countItems(row.getTypeCode()));
    }

    /** 项分页（管理页，含停用项：管理页要能重新启用它们）。 */
    public PageResult<DictItemDTO> pageItems(DictItemQuery query) {
        IPage<DictItem> page = store.pageItems(query);
        return PageResult.from(page.getCurrent(), page.getSize(), page.getTotal(),
                page.getRecords().stream().map(dtoMapper::toDto).toList());
    }

    /** 启用项（{@code getItems} 的语义，走缓存，按 sortNo）；类型不存在抛 20003。 */
    public List<DictItemDTO> getItems(String typeCode) {
        return resolver.enabledItems(typeCode).stream().map(dtoMapper::toDto).toList();
    }

    /** 标签；值未命中返回原值 + WARN（不抛异常，3.2.1）。 */
    public String getLabel(String typeCode, String value) {
        return resolver.getLabel(typeCode, value);
    }

    // ---------------------------------------------------------------- 写（REST 与 DictApi 共用）

    /** 新增类型；{@code type_code} 已存在抛 20003（5.2 给本端点的唯一业务码）。 */
    @Transactional
    public DictTypeDTO addType(DictTypeSaveCmd cmd) {
        String typeCode = cmd.typeCode().trim();
        DictStatus status = statusOf(cmd.status());
        if (store.rowByTypeCode(typeCode) != null) {
            throw new BusinessException(PlatformErrorCode.DICT_TYPE_NOT_FOUND,
                    "字典类型编码已存在：" + typeCode + "（段内没有 dict 专用的重复码，按 5.2 用 20003）");
        }
        long operator = contexts.current().userId();
        DictType type = new DictType();
        type.setId(idGenerator.nextId());
        type.setTypeCode(typeCode);
        type.setTypeName(cmd.typeName().trim());
        type.setStatus(status.name());
        type.setBuiltin(false);
        type.setRemark(cmd.remark());
        type.setCreatedAt(Instant.now());
        type.setCreatedBy(operator);
        type.setVersion(0);
        type.setDeleted(false);
        store.insert(type);
        publishChanged(ACTION_ADD, typeCode, null, operator);
        return dtoMapper.toDto(type, 0);
    }

    /**
     * 更新类型（按 {@code typeCode} 定位；{@code type_code} 是行身份，**不可改**）。
     *
     * <p>内置类型允许改名与改状态（只有删除被拦）：平台内置类型是"预置数据"，不是"不可配置项"。
     */
    @Transactional
    public DictTypeDTO upType(DictTypeSaveCmd cmd) {
        String typeCode = cmd.typeCode().trim();
        if (cmd.version() == null) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "更新字典类型必须带 version（乐观锁，P1 册 5.3）");
        }
        DictType existing = store.rowByTypeCode(typeCode);
        if (existing == null) {
            throw new BusinessException(PlatformErrorCode.DICT_TYPE_NOT_FOUND, "字典类型不存在：" + typeCode);
        }
        DictStatus status = statusOf(cmd.status(), existing.getStatus());
        long operator = contexts.current().userId();
        existing.setTypeName(cmd.typeName().trim());
        existing.setStatus(status.name());
        existing.setRemark(cmd.remark());
        existing.setUpdatedAt(Instant.now());
        existing.setUpdatedBy(operator);
        existing.setVersion(cmd.version());
        if (store.updateById(existing) == 0) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "字典类型已被他人修改（version 过期）：" + typeCode);
        }
        publishChanged(ACTION_UP, typeCode, null, operator);
        return dtoMapper.toDto(existing, (int) store.countItems(typeCode));
    }

    /**
     * 逻辑删除类型。
     *
     * <p>判定顺序是刻意的：<b>"仍有项"（20008）先于"内置只读"（20005）</b>。种子里的 4 个内置类型
     * 都带项，先报 20008 才能让调用方拿到**可执行**的原因（"先把项删/停用"）；先报 20005 会让
     * 内置类型的删除永远只有一句"不可删"，看不出还差什么。两种情况下类型都不会被删掉。
     */
    @Transactional
    public void delType(long id, int version) {
        DictType existing = store.rowById(id);
        if (existing == null) {
            throw new BusinessException(PlatformErrorCode.DICT_TYPE_NOT_FOUND, "字典类型不存在：id=" + id);
        }
        if (store.countItems(existing.getTypeCode()) > 0) {
            throw new BusinessException(PlatformErrorCode.DICT_TYPE_IN_USE,
                    "字典类型下仍有字典项，不能删除：" + existing.getTypeCode());
        }
        requireNotBuiltin(existing);
        requireVersion(existing.getVersion(), version, "字典类型", existing.getTypeCode());
        long operator = contexts.current().userId();
        store.deleteById(existing);
        publishChanged(ACTION_DEL, existing.getTypeCode(), null, operator);
    }

    /** 清缓存并重载该类型；类型不存在抛 20003（5.2）。 */
    public void refresh(String typeCode) {
        if (store.rowByTypeCode(typeCode) == null) {
            throw new BusinessException(PlatformErrorCode.DICT_TYPE_NOT_FOUND, "字典类型不存在：" + typeCode);
        }
        resolver.invalidate(typeCode);
    }

    /** 新增项；类型不存在抛 20003，同类型下 {@code item_value} 重复抛 20007。 */
    @Transactional
    public DictItemDTO addItem(DictItemSaveCmd cmd) {
        String typeCode = cmd.typeCode().trim();
        String itemValue = cmd.itemValue().trim();
        if (store.rowByTypeCode(typeCode) == null) {
            throw new BusinessException(PlatformErrorCode.DICT_TYPE_NOT_FOUND, "字典类型不存在：" + typeCode);
        }
        if (store.rowByTypeValue(typeCode, itemValue) != null) {
            throw new BusinessException(PlatformErrorCode.DICT_ITEM_DUPLICATED,
                    "字典项值重复：" + typeCode + " / " + itemValue);
        }
        long operator = contexts.current().userId();
        DictItem item = new DictItem();
        item.setId(idGenerator.nextId());
        item.setTypeCode(typeCode);
        item.setItemValue(itemValue);
        item.setItemLabel(cmd.itemLabel().trim());
        item.setSortNo(cmd.sortNo() == null ? 0 : cmd.sortNo());
        item.setStatus(statusOf(cmd.status()).name());
        item.setIsDefault(cmd.isDefault() != null && cmd.isDefault());
        item.setExtJson(cmd.extJson());
        item.setCreatedAt(Instant.now());
        item.setCreatedBy(operator);
        item.setVersion(0);
        item.setDeleted(false);
        store.insertItem(item);
        publishChanged(ACTION_ADD, typeCode, itemValue, operator);
        return dtoMapper.toDto(item);
    }

    /**
     * 更新项（按 {@code typeCode + itemValue} 定位；两者都是行身份，**不可改**）。
     *
     * <p>不产生 20007：{@code item_value} 不可改，也就不可能在更新时撞上"同类型值重复"。5.2 给
     * {@code dictItem/Up} 列了 20007，与 T3 对 {@code param/Up} 列 20004 的处理同款——按能力落地，
     * 差异登记在《实现注记（T5）》。
     */
    @Transactional
    public DictItemDTO upItem(DictItemSaveCmd cmd) {
        String typeCode = cmd.typeCode().trim();
        String itemValue = cmd.itemValue().trim();
        if (cmd.version() == null) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "更新字典项必须带 version（乐观锁，P1 册 5.3）");
        }
        DictItem existing = store.rowByTypeValue(typeCode, itemValue);
        if (existing == null) {
            throw new BusinessException(PlatformErrorCode.DICT_TYPE_NOT_FOUND,
                    "字典项不存在：" + typeCode + " / " + itemValue);
        }
        DictStatus status = statusOf(cmd.status(), existing.getStatus());
        long operator = contexts.current().userId();
        existing.setItemLabel(cmd.itemLabel().trim());
        existing.setSortNo(cmd.sortNo() == null ? existing.getSortNo() : cmd.sortNo());
        existing.setStatus(status.name());
        existing.setIsDefault(cmd.isDefault() != null && cmd.isDefault());
        existing.setExtJson(cmd.extJson());
        existing.setUpdatedAt(Instant.now());
        existing.setUpdatedBy(operator);
        existing.setVersion(cmd.version());
        if (store.updateItemById(existing) == 0) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT,
                    "字典项已被他人修改（version 过期）：" + typeCode + " / " + itemValue);
        }
        publishChanged(ACTION_UP, typeCode, itemValue, operator);
        return dtoMapper.toDto(existing);
    }

    /**
     * 逻辑删除项；仍然只允许删**非内置类型**下的项（票面验收"内置类型/项不可删"）。
     *
     * <p>约定：逻辑删掉的项连 {@code getLabel} 都查不到（历史数据显示原始值），而"停用"仍可解析
     * ——管理页文案按 3.2.4 引导用停用。
     */
    @Transactional
    public void delItem(long id, int version) {
        DictItem existing = store.rowItemById(id);
        if (existing == null) {
            throw new BusinessException(PlatformErrorCode.DICT_TYPE_NOT_FOUND, "字典项不存在：id=" + id);
        }
        DictType type = store.rowByTypeCode(existing.getTypeCode());
        if (type != null) {
            requireNotBuiltin(type);
        }
        requireVersion(existing.getVersion(), version, "字典项", existing.getTypeCode() + " / " + existing.getItemValue());
        long operator = contexts.current().userId();
        store.deleteItemById(existing);
        publishChanged(ACTION_DEL, existing.getTypeCode(), existing.getItemValue(), operator);
    }

    // ---------------------------------------------------------------- 内部

    /** 内置只读：段内没有 dict 专用码，复用 20005（常量名带 PARAM_ 前缀，见类注释与《实现注记（T5）》）。 */
    private static void requireNotBuiltin(DictType type) {
        if (type.isBuiltin()) {
            throw new BusinessException(PlatformErrorCode.PARAM_BUILTIN_READONLY,
                    "平台内置字典类型及其字典项不可删除：" + type.getTypeCode() + "（改值/停用仍允许）");
        }
    }

    private static void requireVersion(Integer current, int requested, String what, String identity) {
        if (current == null || current != requested) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT,
                    what + "已被他人修改（version 过期）：" + identity);
        }
    }

    /**
     * 状态名校验：空值按 {@link DictStatus#ENABLED}（DDL 默认值同），非法值给可读的 10000。
     *
     * <p>不让 PG 的 CHECK 约束错误泄漏成 10500——那是"配置写错"而不是"系统故障"。
     */
    private static DictStatus statusOf(String status) {
        if (status == null || status.isBlank()) {
            return DictStatus.ENABLED;
        }
        if (!DictStatus.isValid(status)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "字典状态非法（只能是 ENABLED/DISABLED）：" + status);
        }
        return DictStatus.fromName(status);
    }

    /** 更新时的状态：请求没带状态就**保持原状态**（空值不能悄悄把停用项启用回来）。 */
    private static DictStatus statusOf(String status, String current) {
        return status == null || status.isBlank() ? DictStatus.fromName(current) : statusOf(status);
    }

    /** 事件在**事务内**注册，提交后才投递（{@code @TransactionalEventListener(AFTER_COMMIT)}，3.9.1）。 */
    private void publishChanged(String action, String typeCode, String itemValue, long operator) {
        events.publishEvent(new DictChangedEvent(idGenerator.nextStr(), Instant.now(), typeCode, itemValue,
                action, operator));
        log.info("字典变更：action={} typeCode={} itemValue={}", action, typeCode, itemValue);
    }
}
