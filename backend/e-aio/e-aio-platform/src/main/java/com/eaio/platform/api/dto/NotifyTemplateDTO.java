package com.eaio.platform.api.dto;

import java.util.List;

/** 通知模板出参。 */
public record NotifyTemplateDTO(
        long id,
        String templateCode,
        String templateName,
        String channel,
        String titleTemplate,
        String contentTemplate,
        List<String> variables,
        String status,
        boolean builtin,
        int version) {
}
