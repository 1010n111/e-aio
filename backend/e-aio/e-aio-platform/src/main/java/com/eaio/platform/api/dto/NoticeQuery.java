package com.eaio.platform.api.dto;

/** 公告管理分页查询。 */
public record NoticeQuery(
        Integer pageNum,
        Integer pageSize,
        String orderBy,
        String orderDir,
        String title,
        String publishStatus,
        String scopeType) {
}
