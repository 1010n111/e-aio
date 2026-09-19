package com.eaio.platform.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.eaio.common.api.ErrorCode;
import com.eaio.common.api.PageResult;
import com.eaio.common.exception.BusinessException;
import com.eaio.common.id.IdGenerator;
import com.eaio.platform.api.PlatformErrorCode;
import com.eaio.platform.api.dto.ParamDTO;
import com.eaio.platform.api.dto.ParamQuery;
import com.eaio.platform.api.dto.ParamSaveCmd;
import com.eaio.platform.api.dto.ParamWithSourceDTO;
import com.eaio.platform.application.param.ParamContextProvider;
import com.eaio.platform.application.param.ParamCryptoKeys;
import com.eaio.platform.application.param.ParamDtoMapper;
import com.eaio.platform.application.param.ParamResolver;
import com.eaio.platform.application.param.ResolvedParam;
import com.eaio.platform.domain.param.ParamChangeLogItem;
import com.eaio.platform.domain.param.ParamContext;
import com.eaio.platform.domain.param.ParamItem;
import com.eaio.platform.domain.param.ParamLevel;
import com.eaio.platform.domain.param.ParamOverlayResolver;
import com.eaio.platform.domain.param.ParamValueCipher;
import com.eaio.platform.domain.param.ParamValueCodec;
import com.eaio.platform.domain.param.ParamValueType;
import com.eaio.platform.events.ParamChangedEvent;
import com.eaio.platform.infrastructure.persistence.ParamStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 参数中心应用服务（P1 册 3.1.2）：读路径委托 {@link ParamResolver} 与 {@link ParamStore}，
 * 写路径做"校验 → 落库 + 留痕 → 发事件 → 失效"（3.1.4）。
 *
 * <p><b>REST 与跨模块 {@code ParamApi} 共用本服务</b>（裁决 2.4.3）：Controller 调 {@code add/up/del/refresh}，
 * {@code ParamApiImpl} 调同样的方法（跨模块写多一道"禁 SYSTEM 级"的口径，见
 * {@link #setFromModule(ParamSaveCmd)}）。校验、留痕、事件、失效各只写一遍。
 */
@Service
public class ParamAppService {

    private static final Logger log = LoggerFactory.getLogger(ParamAppService.class);

    private static final String DEFAULT_GROUP = "default";
    private static final String CHANGE_ADD = "ADD";
    private static final String CHANGE_UP = "UP";
    private static final String CHANGE_DEL = "DEL";

    private final ParamStore store;
    private final ParamResolver resolver;
    private final ParamContextProvider contexts;
    private final ParamDtoMapper dtoMapper;
    private final ParamCryptoKeys cryptoKeys;
    private final ApplicationEventPublisher events;
    private final IdGenerator idGenerator;

    public ParamAppService(ParamStore store, ParamResolver resolver, ParamContextProvider contexts,
            ParamDtoMapper dtoMapper, ParamCryptoKeys cryptoKeys, ApplicationEventPublisher events,
            IdGenerator idGenerator) {
        this.store = store;
        this.resolver = resolver;
        this.contexts = contexts;
        this.dtoMapper = dtoMapper;
        this.cryptoKeys = cryptoKeys;
        this.events = events;
        this.idGenerator = idGenerator;
    }

    // ---------------------------------------------------------------- 读（管理端）

    /**
     * 分页列表：**按行**分页（过滤条件 paramLevel/ownerId 本身就是行的属性），每行给出该行自己的值 +
     * 该键的三级候选（管理页展开看"被谁覆盖"）。
     *
     * <p>"某键在当前上下文下的生效值"用 {@link #effective(String, String, Long)}，两者刻意分开：
     * 列表要能编辑每一行，而"生效值"是一行都没有时也能回答的问题。
     */
    public PageResult<ParamWithSourceDTO> page(ParamQuery query) {
        IPage<ParamItem> page = store.page(query);
        List<ParamWithSourceDTO> records = new ArrayList<>(page.getRecords().size());
        for (ParamItem row : page.getRecords()) {
            List<ParamItem> candidates = resolver.candidates(row.getParamKey());
            records.add(new ParamWithSourceDTO(row.getParamKey(),
                    dtoMapper.maskSecret(row), "DB", row.getParamLevel(),
                    candidates.stream().map(dtoMapper::toDto).toList(), row.isHotReload()));
        }
        return PageResult.from(page.getCurrent(), page.getSize(), page.getTotal(), records);
    }

    /** 某键在指定级别/归属（未指定则当前请求上下文）下的生效值与来源；键未定义抛 20001。 */
    public ParamWithSourceDTO effective(String paramKey, String paramLevel, Long ownerId) {
        List<ParamItem> candidates = resolver.candidates(paramKey);
        ParamContext context = contextOf(paramLevel, ownerId);
        Optional<ParamItem> winner = ParamOverlayResolver.effective(candidates, context);
        if (winner.isEmpty()) {
            throw new BusinessException(PlatformErrorCode.PARAM_NOT_FOUND,
                    "参数不存在或在该上下文下不可见：" + paramKey);
        }
        ParamItem row = winner.get();
        return new ParamWithSourceDTO(paramKey, dtoMapper.maskSecret(row), "DB", row.getParamLevel(),
                candidates.stream().map(dtoMapper::toDto).toList(), row.isHotReload());
    }

    /** 按分组列出全量（每行一条，含候选）；组内没有键时返回空列表。 */
    public List<ParamWithSourceDTO> listByGroup(String paramGroup) {
        List<ParamItem> rows = paramGroup == null || paramGroup.isBlank()
                ? resolver.allRows()
                : resolver.rowsByGroup(paramGroup);
        Map<String, List<ParamItem>> byKey = groupByKey(rows);
        List<ParamWithSourceDTO> result = new ArrayList<>();
        for (ParamItem row : rows) {
            List<ParamItem> candidates = byKey.getOrDefault(row.getParamKey(), List.of());
            result.add(new ParamWithSourceDTO(row.getParamKey(), dtoMapper.maskSecret(row), "DB",
                    row.getParamLevel(), candidates.stream().map(dtoMapper::toDto).toList(), row.isHotReload()));
        }
        return result;
    }

    /** 按分组列出**行**（跨模块 {@code ParamApi.listByGroup} 用；每行一条，带各自级别与归属）。 */
    public List<ParamDTO> listRowsByGroup(String paramGroup) {
        List<ParamItem> rows = paramGroup == null || paramGroup.isBlank()
                ? resolver.allRows()
                : resolver.rowsByGroup(paramGroup);
        return rows.stream().map(dtoMapper::toDto).toList();
    }

    // ---------------------------------------------------------------- 写（REST 与 ParamApi 共用）

    /** 新增一行（管理端可写任意级别；SYSTEM 级的归属必须是 0，见 20006）。 */
    @Transactional
    public ParamDTO add(ParamSaveCmd cmd) {
        ParamLevel level = levelOf(cmd);
        ParamValueType type = ParamValueType.fromName(cmd.valueType());
        String value = ParamValueCodec.normalize(cmd.paramValue(), type);
        if (store.rowByKeyLevelOwner(cmd.paramKey(), level.name(), cmd.ownerId()) != null) {
            throw new BusinessException(PlatformErrorCode.PARAM_DUPLICATED,
                    "参数已存在：" + cmd.paramKey() + "（级别 " + level + "，归属 " + cmd.ownerId() + "）");
        }
        long operator = contexts.current().userId();
        ParamItem item = new ParamItem();
        item.setId(idGenerator.nextId());
        item.setParamKey(cmd.paramKey().trim());
        item.setParamLevel(level.name());
        item.setOwnerId(cmd.ownerId());
        item.setValueType(type.name());
        item.setParamGroup(cmd.paramGroup() == null || cmd.paramGroup().isBlank() ? DEFAULT_GROUP : cmd.paramGroup());
        item.setRemark(cmd.remark());
        item.setEncrypted(type.secret());
        item.setBuiltin(false);
        item.setHotReload(true);
        item.setParamValue(type.secret() ? ParamValueCipher.encrypt(value, cryptoKeys.key()) : value);
        item.setCreatedAt(Instant.now());
        item.setCreatedBy(operator);
        item.setVersion(0);
        item.setDeleted(false);
        store.insert(item);
        appendLog(item, null, item.getParamValue(), operator, type);
        publishChanged(CHANGE_ADD, item);
        return dtoMapper.toDto(item);
    }

    /**
     * 更新一行（按 键+级别+归属 定位，version 乐观锁；缺失即 10003）。
     *
     * <p><b>空值 = 不变更</b>（P1-2 册 331 行）：密钥类参数接口永不回显明文，管理页只能留空提交，
     * 因此"SECRET 行 + 空 paramValue"必须保留原密文——否则一次正常编辑就把密钥清成 {@code encrypt("")}，
     * 而那是不可逆的数据丢失。
     */
    @Transactional
    public ParamDTO up(ParamSaveCmd cmd) {
        ParamLevel level = levelOf(cmd);
        ParamValueType type = ParamValueType.fromName(cmd.valueType());
        String value = ParamValueCodec.normalize(cmd.paramValue(), type);
        if (cmd.version() == null) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "更新参数必须带 version（乐观锁，P1 册 5.3）");
        }
        ParamItem existing = store.rowByKeyLevelOwner(cmd.paramKey(), level.name(), cmd.ownerId());
        if (existing == null) {
            throw new BusinessException(PlatformErrorCode.PARAM_NOT_FOUND, "参数不存在：" + cmd.paramKey());
        }
        String oldValue = existing.getParamValue();
        String oldType = existing.getValueType();
        long operator = contexts.current().userId();
        existing.setValueType(type.name());
        existing.setParamGroup(cmd.paramGroup() == null || cmd.paramGroup().isBlank() ? DEFAULT_GROUP : cmd.paramGroup());
        existing.setRemark(cmd.remark());
        boolean blankValue = cmd.paramValue() == null || cmd.paramValue().isBlank();
        if (blankValue && !type.name().equals(oldType)) {
            // 留空 = 不变更（P1-2 册 331）只在"类型不变"时成立：跨类型时旧值无法充当新类型的值
            // （STRING 的明文不是密文，SECRET 的密文也不是明文），静默保留会把明文字段标成 encrypted
            // 或把密文当明文回给调用方——两者都是"配置写错却看不出来"，必须显式拒绝。
            throw new BusinessException(PlatformErrorCode.PARAM_TYPE_MISMATCH,
                    "值类型由 " + oldType + " 改为 " + type.name() + " 时必须给出新值：" + cmd.paramKey());
        }
        if (!blankValue) {
            existing.setParamValue(type.secret() ? ParamValueCipher.encrypt(value, cryptoKeys.key()) : value);
        }
        existing.setEncrypted(type.secret());
        existing.setUpdatedAt(Instant.now());
        existing.setUpdatedBy(operator);
        existing.setVersion(cmd.version());
        if (store.updateById(existing) == 0) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT,
                    "参数已被他人修改（version 过期）：" + cmd.paramKey());
        }
        appendLog(existing, oldValue, existing.getParamValue(), operator, type);
        publishChanged(CHANGE_UP, existing);
        return dtoMapper.toDto(existing);
    }

    /** 逻辑删除一行；内置参数（{@code builtin = true}）不可删（20005）。 */
    @Transactional
    public void del(long id, int version) {
        ParamItem existing = store.rowById(id);
        if (existing == null) {
            throw new BusinessException(PlatformErrorCode.PARAM_NOT_FOUND, "参数不存在：id=" + id);
        }
        if (existing.isBuiltin()) {
            throw new BusinessException(PlatformErrorCode.PARAM_BUILTIN_READONLY,
                    "平台内置参数不可删除：" + existing.getParamKey());
        }
        if (existing.getVersion() == null || existing.getVersion() != version) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "参数已被他人修改（version 过期）：id=" + id);
        }
        long operator = contexts.current().userId();
        store.deleteById(existing);
        appendLog(existing, existing.getParamValue(), null, operator,
                ParamValueType.fromName(existing.getValueType()));
        publishChanged(CHANGE_DEL, existing);
    }

    /**
     * 跨模块写入（{@code ParamApi.set} 的语义）：**只允许 ORG/USER 级**。
     *
     * <p>SYSTEM 级一律 20006——平台配置只能由管理端改，模块若能手改平台配置，"谁把最大文件大小改成
     * 1 字节"就只能靠翻日志（P1 册 5.4 的明文口径）。
     */
    @Transactional
    public ParamDTO setFromModule(ParamSaveCmd cmd) {
        if (levelOf(cmd) == ParamLevel.SYSTEM) {
            throw new BusinessException(PlatformErrorCode.PARAM_SCOPE_INVALID,
                    "模块不得写 SYSTEM 级参数（只能 ORG/USER 级）：" + cmd.paramKey());
        }
        ParamItem existing = store.rowByKeyLevelOwner(cmd.paramKey(), levelOf(cmd).name(), cmd.ownerId());
        return existing == null ? add(cmd) : up(cmd);
    }

    /** 清缓存并重载；{@code key} 为空 = 全量失效（DBA 绕过接口改库后的兜底）；键不存在抛 20001。 */
    public void refresh(String key) {
        if (key == null || key.isBlank()) {
            resolver.invalidateAll();
            return;
        }
        if (resolver.candidates(key).isEmpty()) {
            throw new BusinessException(PlatformErrorCode.PARAM_NOT_FOUND, "参数不存在：" + key);
        }
        resolver.invalidate(key);
    }

    // ---------------------------------------------------------------- 读（跨模块，走缓存）

    /** 生效值 + 来源；键未定义抛 20001（{@code ParamApi.get} 的语义）。 */
    public ParamDTO get(String key) {
        ResolvedParam resolved = resolver.resolve(key, contexts.current())
                .orElseThrow(() -> new BusinessException(PlatformErrorCode.PARAM_NOT_FOUND, "参数不存在：" + key));
        return toDto(resolved);
    }

    /** 按调用方默认值取（{@code getInt/getBool/getString} 的语义）：键未定义返回默认值，来源标 DEFAULT。 */
    public ResolvedParam resolveOrDefault(String key, String defaultValue) {
        return resolver.resolveOrDefault(key, defaultValue, contexts.current());
    }

    /** 全量键值快照（键 → DTO，值 = 当前上下文下的生效值）；一次查询取全表后在内存里分级覆盖。 */
    public Map<String, ParamDTO> getAll() {
        ParamContext context = contexts.current();
        Map<String, ParamDTO> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, List<ParamItem>> entry : groupByKey(resolver.allRows()).entrySet()) {
            ParamOverlayResolver.effective(entry.getValue(), context)
                    .ifPresent(row -> snapshot.put(entry.getKey(), new ParamDTO(row.getId(), row.getParamKey(),
                            row.getParamLevel(), row.getOwnerId(), dtoMapper.maskSecret(row), row.getValueType(),
                            row.getParamGroup(), row.isEncrypted(), row.isBuiltin(), row.isHotReload(),
                            row.getVersion() == null ? 0 : row.getVersion(), row.getUpdatedAt())));
        }
        return snapshot;
    }

    // ---------------------------------------------------------------- 内部

    private ParamDTO toDto(ResolvedParam resolved) {
        ParamItem row = resolved.row();
        if (row == null) {
            // 来源是 DEFAULT/YAML：没有行，用解析结果拼一个"只读视图"（id/version 为 0，前端据此禁用编辑）
            return new ParamDTO(0L, resolved.key(), null, 0L, resolved.value(), null, null, false, false,
                    resolved.hotReload(), 0, null);
        }
        return new ParamDTO(row.getId(), row.getParamKey(), row.getParamLevel(), row.getOwnerId(),
                dtoMapper.maskSecret(row), row.getValueType(), row.getParamGroup(), row.isEncrypted(),
                row.isBuiltin(), row.isHotReload(), row.getVersion() == null ? 0 : row.getVersion(),
                row.getUpdatedAt());
    }

    private ParamLevel levelOf(ParamSaveCmd cmd) {
        ParamLevel level = ParamLevel.fromName(cmd.paramLevel());
        if (!level.accepts(cmd.ownerId())) {
            throw new BusinessException(PlatformErrorCode.PARAM_SCOPE_INVALID,
                    "参数归属与级别不符：" + level + " 级要求 ownerId "
                            + (level == ParamLevel.SYSTEM ? "= 0" : "> 0") + "，实际 " + cmd.ownerId());
        }
        return level;
    }

    /** 显式级别/归属 → 解析上下文；未指定时用当前请求上下文。 */
    private ParamContext contextOf(String paramLevel, Long ownerId) {
        if (paramLevel == null || paramLevel.isBlank()) {
            return contexts.current();
        }
        ParamLevel level = ParamLevel.fromName(paramLevel);
        long owner = ownerId == null ? 0L : ownerId;
        if (!level.accepts(owner)) {
            throw new BusinessException(PlatformErrorCode.PARAM_SCOPE_INVALID,
                    "参数归属与级别不符：" + level + " 级要求 ownerId "
                            + (level == ParamLevel.SYSTEM ? "= 0" : "> 0") + "，实际 " + owner);
        }
        return switch (level) {
            case SYSTEM -> ParamContext.systemOnly();
            case ORG -> new ParamContext(owner, 0L);
            case USER -> new ParamContext(0L, owner);
        };
    }

    private static Map<String, List<ParamItem>> groupByKey(List<ParamItem> rows) {
        Map<String, List<ParamItem>> byKey = new LinkedHashMap<>();
        for (ParamItem row : rows) {
            byKey.computeIfAbsent(row.getParamKey(), key -> new ArrayList<>()).add(row);
        }
        return byKey;
    }

    /** 变更留痕：SECRET 类的新旧值都记 {@code ******}（值本身已在列里加密，日志再不落明文）。 */
    private void appendLog(ParamItem row, String oldStoredValue, String newStoredValue, long operator,
            ParamValueType type) {
        ParamChangeLogItem logItem = new ParamChangeLogItem();
        logItem.setId(idGenerator.nextId());
        logItem.setParamId(row.getId());
        logItem.setParamKey(row.getParamKey());
        logItem.setParamLevel(row.getParamLevel());
        logItem.setOwnerId(row.getOwnerId());
        if (type.secret()) {
            logItem.setOldValue(oldStoredValue == null ? null : ParamValueCipher.masked());
            logItem.setNewValue(newStoredValue == null ? null : ParamValueCipher.masked());
        } else {
            logItem.setOldValue(oldStoredValue);
            logItem.setNewValue(newStoredValue);
        }
        logItem.setOperatorId(operator);
        logItem.setTraceId(MDC.get("traceId"));
        logItem.setCreatedAt(Instant.now());
        logItem.setCreatedBy(operator);
        store.appendLog(logItem);
    }

    /** 事件在**事务内**注册，提交后才投递（{@code @TransactionalEventListener(AFTER_COMMIT)}，3.1.4）。 */
    private void publishChanged(String changeType, ParamItem row) {
        events.publishEvent(new ParamChangedEvent(idGenerator.nextStr(), Instant.now(), changeType, row.getParamKey(),
                row.getParamLevel(), row.getOwnerId()));
        log.info("参数变更：type={} key={} level={} ownerId={}", changeType, row.getParamKey(),
                row.getParamLevel(), row.getOwnerId());
    }
}
