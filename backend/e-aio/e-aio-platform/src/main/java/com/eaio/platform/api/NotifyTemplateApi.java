package com.eaio.platform.api;

import java.util.Map;

import com.eaio.common.api.PageResult;
import com.eaio.platform.api.dto.NotifyTemplateDTO;
import com.eaio.platform.api.dto.NotifyTemplateQuery;
import com.eaio.platform.api.dto.NotifyTemplateSaveCmd;

/** 通知模板契约；EMAIL/SMS 只保存和渲染，不在 platform 发送。 */
public interface NotifyTemplateApi {

    RenderedTemplate render(String templateCode, Map<String, String> params);

    PageResult<NotifyTemplateDTO> getPage(NotifyTemplateQuery query);

    NotifyTemplateDTO save(NotifyTemplateSaveCmd cmd);

    record RenderedTemplate(String title, String content) {
    }
}
