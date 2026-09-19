package com.eaio.platform.infrastructure.web;

import java.util.List;

import com.eaio.common.api.PageResult;
import com.eaio.platform.api.dto.DictDelCmd;
import com.eaio.platform.api.dto.DictGetCmd;
import com.eaio.platform.api.dto.DictItemDTO;
import com.eaio.platform.api.dto.DictItemQuery;
import com.eaio.platform.api.dto.DictItemSaveCmd;
import com.eaio.platform.api.dto.DictTypeCodeCmd;
import com.eaio.platform.api.dto.DictTypeDTO;
import com.eaio.platform.api.dto.DictTypeQuery;
import com.eaio.platform.api.dto.DictTypeSaveCmd;
import com.eaio.platform.application.DictAppService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据字典 REST 入口（P1 册 5.2 的 11 个端点：dictType 6 + dictItem 5）。
 *
 * <p>三条契约（P1 册 3.9、docs/agents/api-conventions.md）：
 * <ul>
 *   <li><b>全部 POST + JSON</b>，路径省略 context-path（{@code /api} 由 servlet context-path 承载）；
 *       两个资源前缀（{@code /platform/dictType}、{@code /platform/dictItem}）写在**方法**上而不是类上
 *       ——类级 {@code @RequestMapping} 只能选一个前缀，而 5.2 的端点路径就是两个；</li>
 *   <li>控制器**不自己拼返回体**：出站由应用壳的 {@code ApiResponseAdvice} 统一包成 {@code Result<T>}；</li>
 *   <li>权限点与设计册 7.3 <b>逐字一致</b>（{@code platform:dict:*}）：iam 交付前这些注解是契约载体；
 *       拼错的权限点不会报错、只会永远无权限，故由 T14 的 {@code PermissionCodeContractTest} 与 7.3 比对。</li>
 * </ul>
 *
 * <p>设计册 3.2.2 把入站适配器写成 {@code DictTypeController} + {@code DictItemController} 两个类；
 * 这里合成一个：两个类共用同一个 {@code DictAppService}，拆开只会多一个文件与一处权限注解抄写。
 * 差异登记在《实现注记（T5）》。
 *
 * <p>写动作（Add/Up/Del/Refresh）必须带 {@code Idempotency-Key}（5.6：缺键即 10001，由入站链路的
 * 幂等过滤器强制，控制器不重复判断）；读动作不带。
 */
@RestController
public class DictController {

    private final DictAppService service;

    public DictController(DictAppService service) {
        this.service = service;
    }

    // ---------------------------------------------------------------- dictType

    /** 类型分页（每行带 {@code itemCount}）。 */
    @PostMapping("/platform/dictType/GetPage")
    @PreAuthorize("hasAuthority('platform:dict:list')")
    public PageResult<DictTypeDTO> getTypePage(@RequestBody(required = false) DictTypeQuery query) {
        return service.pageTypes(query);
    }

    /** 按 ID 查类型；不存在抛 20003。 */
    @PostMapping("/platform/dictType/Get")
    @PreAuthorize("hasAuthority('platform:dict:get')")
    public DictTypeDTO getType(@Valid @RequestBody DictGetCmd cmd) {
        return service.typeById(cmd.id());
    }

    /** 新增类型；编码重复抛 20003（5.2）。 */
    @PostMapping("/platform/dictType/Add")
    @PreAuthorize("hasAuthority('platform:dict:add')")
    public DictTypeDTO addType(@Valid @RequestBody DictTypeSaveCmd cmd) {
        return service.addType(cmd);
    }

    /** 更新类型（乐观锁：version 必填，过期即 10003）。 */
    @PostMapping("/platform/dictType/Up")
    @PreAuthorize("hasAuthority('platform:dict:up')")
    public DictTypeDTO upType(@Valid @RequestBody DictTypeSaveCmd cmd) {
        return service.upType(cmd);
    }

    /** 逻辑删除类型；仍有项抛 20008，内置只读抛 20005。 */
    @PostMapping("/platform/dictType/Del")
    @PreAuthorize("hasAuthority('platform:dict:del')")
    public void delType(@Valid @RequestBody DictDelCmd cmd) {
        service.delType(cmd.id(), cmd.version());
    }

    /** 刷新该类型的缓存；类型不存在抛 20003。 */
    @PostMapping("/platform/dictType/Refresh")
    @PreAuthorize("hasAuthority('platform:dict:refresh')")
    public void refreshType(@Valid @RequestBody DictTypeCodeCmd cmd) {
        service.refresh(cmd.typeCode());
    }

    // ---------------------------------------------------------------- dictItem

    /** 启用项（按 sortNo，不分页）；类型不存在抛 20003。 */
    @PostMapping("/platform/dictItem/GetItems")
    @PreAuthorize("hasAuthority('platform:dict:get')")
    public List<DictItemDTO> getItems(@Valid @RequestBody DictTypeCodeCmd cmd) {
        return service.getItems(cmd.typeCode());
    }

    /** 项分页（管理页，含停用项）。 */
    @PostMapping("/platform/dictItem/GetPage")
    @PreAuthorize("hasAuthority('platform:dict:list')")
    public PageResult<DictItemDTO> getItemPage(@RequestBody(required = false) DictItemQuery query) {
        return service.pageItems(query);
    }

    /** 新增项；类型不存在抛 20003，值重复抛 20007。 */
    @PostMapping("/platform/dictItem/Add")
    @PreAuthorize("hasAuthority('platform:dict:add')")
    public DictItemDTO addItem(@Valid @RequestBody DictItemSaveCmd cmd) {
        return service.addItem(cmd);
    }

    /** 更新项（乐观锁：version 必填，过期即 10003）。 */
    @PostMapping("/platform/dictItem/Up")
    @PreAuthorize("hasAuthority('platform:dict:up')")
    public DictItemDTO upItem(@Valid @RequestBody DictItemSaveCmd cmd) {
        return service.upItem(cmd);
    }

    /** 逻辑删除项（内置类型下的项不可删）。 */
    @PostMapping("/platform/dictItem/Del")
    @PreAuthorize("hasAuthority('platform:dict:del')")
    public void delItem(@Valid @RequestBody DictDelCmd cmd) {
        service.delItem(cmd.id(), cmd.version());
    }
}
