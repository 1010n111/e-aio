package com.eaio.platform.api;

import java.util.List;
import java.util.Map;

import com.eaio.platform.api.dto.ParamDTO;
import com.eaio.platform.api.dto.ParamSaveCmd;

/**
 * 参数中心：分级读取（USER &gt; ORG &gt; SYSTEM）、写入与缓存刷新（P1 册 5.4 契约 V1，冻结）。
 *
 * <p>所有方法在调用方线程**同步**执行，**不开启事务**（跨模块同事务由调用方的事务覆盖，P1 册 5.4）。
 *
 * <p>取值链：L1（Caffeine，本机）→ L2（Redis）→ DB（该键的三级候选行）→ 调用方给的默认值（{@code get*}）
 * 或抛 20001（{@link #get}）。
 */
public interface ParamApi {

    /** 生效值 + 来源；键未定义抛 {@code BusinessException(20001)}。耗时 = 缓存命中 &lt;1ms / 回源 &lt;20ms。 */
    ParamDTO get(String key);

    /** 不存在返回 {@code defaultValue}；类型不匹配抛 {@code 20002}。 */
    int getInt(String key, int defaultValue);

    /** 不存在返回 {@code defaultValue}；类型不匹配抛 {@code 20002}。 */
    boolean getBool(String key, boolean defaultValue);

    /** 不存在返回 {@code defaultValue}；类型不匹配抛 {@code 20002}。 */
    String getString(String key, String defaultValue);

    /** 按分组取全量（管理页 / 批量读取）。 */
    List<ParamDTO> listByGroup(String paramGroup);

    /** 全量键值快照（仅缓存预热 / 导出用）；调用方拿到的是浅拷贝，改动不影响缓存。 */
    Map<String, ParamDTO> getAll();

    /**
     * 写入：仅允许 ORG/USER 级；SYSTEM 级内置参数报 {@code 20006}（防止模块改平台配置）。
     *
     * <p>调用方需自带事务语义（本方法不开启事务）。
     */
    ParamDTO set(ParamSaveCmd cmd);

    /** 清缓存并重载；{@code key} 为空 = 刷新该 region 全部。 */
    void refresh(String key);
}
