package com.eaio.platform.application;

import java.util.Map;

import com.eaio.common.api.PageResult;
import com.eaio.platform.api.NotifyTemplateApi;
import com.eaio.platform.api.dto.NotifyTemplateDTO;
import com.eaio.platform.api.dto.NotifyTemplateQuery;
import com.eaio.platform.api.dto.NotifyTemplateSaveCmd;
import org.springframework.stereotype.Component;

/** NotifyTemplateApi 跨模块薄适配器。 */
@Component
public class NotifyTemplateApiImpl implements NotifyTemplateApi {
    private final NotifyTemplateAppService service;

    public NotifyTemplateApiImpl(NotifyTemplateAppService service) {
        this.service = service;
    }

    @Override
    public RenderedTemplate render(String templateCode, Map<String, String> params) {
        return service.render(templateCode, params);
    }
    @Override
    public PageResult<NotifyTemplateDTO> getPage(NotifyTemplateQuery query) {
        return service.page(query);
    }
    @Override
    public NotifyTemplateDTO save(NotifyTemplateSaveCmd cmd) {
        return service.save(cmd);
    }
}
