package com.eaio.platform.infrastructure.web;

import java.util.Map;

import com.eaio.common.api.PageResult;
import com.eaio.platform.api.NotifyTemplateApi.RenderedTemplate;
import com.eaio.platform.api.dto.NotifyTemplateCodeCmd;
import com.eaio.platform.api.dto.NotifyTemplateDTO;
import com.eaio.platform.api.dto.NotifyTemplateQuery;
import com.eaio.platform.api.dto.NotifyTemplateRenderCmd;
import com.eaio.platform.api.dto.NotifyTemplateSaveCmd;
import com.eaio.platform.application.NotifyTemplateAppService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 通知模板 REST 入口。 */
@RestController
public class NotifyTemplateController {
    private final NotifyTemplateAppService service;

    public NotifyTemplateController(NotifyTemplateAppService service) {
        this.service = service;
    }
    @PostMapping("/platform/notifyTemplate/GetPage")
    @PreAuthorize("hasAuthority('platform:notifyTemplate:list')")
    public PageResult<NotifyTemplateDTO> getPage(@RequestBody(required = false) NotifyTemplateQuery query) {
        return service.page(query);
    }
    @PostMapping("/platform/notifyTemplate/Get")
    @PreAuthorize("hasAuthority('platform:notifyTemplate:get')")
    public NotifyTemplateDTO get(@Valid @RequestBody NotifyTemplateCodeCmd cmd) {
        return service.get(cmd.templateCode());
    }
    @PostMapping("/platform/notifyTemplate/Save")
    @PreAuthorize("hasAuthority('platform:notifyTemplate:save')")
    public NotifyTemplateDTO save(@Valid @RequestBody NotifyTemplateSaveCmd cmd) {
        return service.save(cmd);
    }
    @PostMapping("/platform/notifyTemplate/Del")
    @PreAuthorize("hasAuthority('platform:notifyTemplate:del')")
    public void del(@Valid @RequestBody NotifyTemplateCodeCmd cmd) {
        service.delete(cmd.templateCode(), cmd.version() == null ? 0 : cmd.version());
    }
    @PostMapping("/platform/notifyTemplate/Render")
    @PreAuthorize("hasAuthority('platform:notifyTemplate:get')")
    public RenderedTemplate render(@Valid @RequestBody NotifyTemplateRenderCmd cmd) {
        return service.render(cmd.templateCode(), cmd.params() == null ? Map.of() : cmd.params());
    }
}
