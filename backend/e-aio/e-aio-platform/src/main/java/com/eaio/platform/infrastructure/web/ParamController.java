package com.eaio.platform.infrastructure.web;

import java.util.List;

import com.eaio.common.api.PageResult;
import com.eaio.platform.api.dto.ParamDTO;
import com.eaio.platform.api.dto.ParamDelCmd;
import com.eaio.platform.api.dto.ParamGetCmd;
import com.eaio.platform.api.dto.ParamGroupCmd;
import com.eaio.platform.api.dto.ParamQuery;
import com.eaio.platform.api.dto.ParamSaveCmd;
import com.eaio.platform.api.dto.ParamWithSourceDTO;
import com.eaio.platform.application.ParamAppService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 参数中心 REST 入口（P1 册 5.2 的 7 个端点）。
 *
 * <p>三条契约（P1 册 3.9、docs/agents/api-conventions.md）：
 * <ul>
 *   <li><b>全部 POST + JSON</b>，路径省略 context-path（{@code /api} 由 servlet context-path 承载）；</li>
 *   <li>控制器**不自己拼返回体**：出站由应用壳的 {@code ApiResponseAdvice} 统一包成 {@code Result<T>}
 *       并回填 traceId；</li>
 *   <li>权限点与设计册 7.3 **逐字一致**（{@code platform:param:*}）：iam 交付前这些注解是契约载体，
 *       授权由 iam 的能力装配接管（拼错的权限点不会报错、只会永远无权限，故由 T14 的
 *       {@code PermissionCodeContractTest} 与 7.3 逐条比对）。</li>
 * </ul>
 *
 * <p>写动作（Add/Up/Del/Refresh）必须带 {@code Idempotency-Key}（5.6 收紧：缺键即 10001，由入站链路
 * 的幂等过滤器强制，控制器不重复判断）；读动作不带。
 */
@RestController
@RequestMapping("/platform/param")
public class ParamController {

    private final ParamAppService service;

    public ParamController(ParamAppService service) {
        this.service = service;
    }

    /** 分页列表（按行；每行含该键的三级候选）。 */
    @PostMapping("/GetPage")
    @PreAuthorize("hasAuthority('platform:param:list')")
    public PageResult<ParamWithSourceDTO> getPage(@RequestBody(required = false) ParamQuery query) {
        return service.page(query);
    }

    /** 单键查询：在指定级别/归属（缺省用当前请求上下文）下的生效值 + 来源 + 候选。 */
    @PostMapping("/Get")
    @PreAuthorize("hasAuthority('platform:param:get')")
    public ParamWithSourceDTO get(@Valid @RequestBody ParamGetCmd cmd) {
        return service.effective(cmd.paramKey(), cmd.paramLevel(), cmd.ownerId());
    }

    /** 按分组列表（{@code paramGroup} 为空 = 全量，5.2 的 {@code {paramGroup?}} 入参）。 */
    @PostMapping("/GetAll")
    @PreAuthorize("hasAuthority('platform:param:list')")
    public List<ParamWithSourceDTO> getAll(@Valid @RequestBody(required = false) ParamGroupCmd cmd) {
        return service.listByGroup(cmd == null ? null : cmd.paramGroup());
    }

    /** 新增（管理端可写任意级别）。 */
    @PostMapping("/Add")
    @PreAuthorize("hasAuthority('platform:param:add')")
    public ParamDTO add(@Valid @RequestBody ParamSaveCmd cmd) {
        return service.add(cmd);
    }

    /** 更新（乐观锁：version 必填，过期即 10003）。 */
    @PostMapping("/Up")
    @PreAuthorize("hasAuthority('platform:param:up')")
    public ParamDTO up(@Valid @RequestBody ParamSaveCmd cmd) {
        return service.up(cmd);
    }

    /** 逻辑删除（内置参数 20005）。 */
    @PostMapping("/Del")
    @PreAuthorize("hasAuthority('platform:param:del')")
    public void del(@Valid @RequestBody ParamDelCmd cmd) {
        service.del(cmd.id(), cmd.version());
    }

    /** 刷新缓存（{@code paramKey} 为空 = 全量失效）。 */
    @PostMapping("/Refresh")
    @PreAuthorize("hasAuthority('platform:param:refresh')")
    public void refresh(@RequestBody(required = false) ParamGetCmd cmd) {
        service.refresh(cmd == null ? null : cmd.paramKey());
    }
}
