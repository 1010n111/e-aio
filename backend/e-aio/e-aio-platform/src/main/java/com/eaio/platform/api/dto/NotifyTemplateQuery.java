package com.eaio.platform.api.dto;

/** 通知模板分页查询。 */
public record NotifyTemplateQuery(
        Integer pageNum,
        Integer pageSize,
        String orderBy,
        String orderDir,
        String templateCode,
        String channel,
        String status) {
}
