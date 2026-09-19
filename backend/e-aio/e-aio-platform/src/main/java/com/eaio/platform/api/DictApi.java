package com.eaio.platform.api;

import java.util.List;

import com.eaio.common.api.PageResult;
import com.eaio.platform.api.dto.DictItemDTO;
import com.eaio.platform.api.dto.DictItemSaveCmd;
import com.eaio.platform.api.dto.DictTypeDTO;
import com.eaio.platform.api.dto.DictTypeQuery;
import com.eaio.platform.api.dto.DictTypeSaveCmd;

/**
 * 数据字典（P1 册 5.4 契约 V1，冻结）。读方法有缓存（L1+L2）；写方法与 HTTP 管理端共用同一 AppService（2.4.3）。
 *
 * <p>所有方法在调用方线程**同步**执行，**不开启事务**（跨模块同事务由调用方的事务覆盖，P1 册 5.4）。
 *
 * <p><b>契约的两处落地口径（设计册 5.4 自相矛盾处，已登记在《实现注记（T5）》）</b>：
 * <ol>
 *   <li>{@link #del(long, int)} 与 {@link #delItem(long, int)} 的 javadoc 写"实际参数为类型 id：
 *       del(long id, int version)"，与签名的单参数不一致——按 javadoc 的**实际参数**落地为
 *       {@code (long id, int version)}：乐观锁版本是删除动作的一部分（与 5.2 端点入参 {@code {id, version}}
 *       逐字一致），只给 id 会让 {@code Del} 变成"无视并发改动的盲删"；</li>
 *   <li>{@link #getLabel(String, String)} 的语义在 3.2.1 已定死：**未命中返回原值 + WARN，不抛异常**。</li>
 * </ol>
 */
public interface DictApi {

    /** 启用项（按 sortNo）；类型不存在抛 20003。 */
    List<DictItemDTO> getItems(String typeCode);

    /** 标签；值未命中返回原值 + WARN（不抛异常，见 3.2.1）。 */
    String getLabel(String typeCode, String value);

    /** 字典类型分页（管理页）。 */
    PageResult<DictTypeDTO> getPage(DictTypeQuery query);

    /** 新增类型；{@code type_code} 已存在抛 20003（同码只可能是唯一键冲突）。 */
    DictTypeDTO add(DictTypeSaveCmd cmd);

    /** 更新类型（按 {@code typeCode} 定位，{@code type_code} 不可改；乐观锁过期 10003）。 */
    DictTypeDTO up(DictTypeSaveCmd cmd);

    /** 逻辑删除字典类型；仍有项时抛 20008。实际参数为类型 id：del(long id, int version)。 */
    void del(long id, int version);

    /** 清缓存并重载该类型（DBA 绕过接口改库后的兜底）；类型不存在抛 20003。 */
    void refresh(String typeCode);

    /* 字典项维护（P1 新增方法名：与类型维护区分，避免同名重载歧义，见 3.2.4） */

    /** 新增项；同类型下 {@code item_value} 已存在抛 20007。 */
    DictItemDTO addItem(DictItemSaveCmd cmd);

    /** 更新项（按 {@code typeCode + itemValue} 定位；乐观锁过期 10003）。 */
    DictItemDTO upItem(DictItemSaveCmd cmd);

    /** 逻辑删除字典项；实际参数为项 id：delItem(long id, int version)。 */
    void delItem(long id, int version);
}
