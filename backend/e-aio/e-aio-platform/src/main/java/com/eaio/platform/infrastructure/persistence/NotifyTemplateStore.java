package com.eaio.platform.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eaio.common.exception.SystemException;
import com.eaio.platform.api.dto.NotifyTemplateQuery;
import com.eaio.platform.domain.notice.NotifyTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class NotifyTemplateStore {

    private final ObjectProvider<NotifyTemplateMapper> mappers;

    public NotifyTemplateStore(ObjectProvider<NotifyTemplateMapper> mappers) {
        this.mappers = mappers;
    }
    public NotifyTemplate rowByCode(String code) {
        return mapper().selectOne(new LambdaQueryWrapper<NotifyTemplate>()
                .eq(NotifyTemplate::getTemplateCode, code));
    }

    public IPage<NotifyTemplate> page(NotifyTemplateQuery query) {
        NotifyTemplateQuery actual = query == null
                ? new NotifyTemplateQuery(null, null, null, null, null, null, null) : query;
        LambdaQueryWrapper<NotifyTemplate> wrapper = new LambdaQueryWrapper<>();
        if (actual.templateCode() != null && !actual.templateCode().isBlank()) {
            wrapper.like(NotifyTemplate::getTemplateCode, actual.templateCode().trim());
        }
        if (actual.channel() != null && !actual.channel().isBlank()) {
            wrapper.eq(NotifyTemplate::getChannel, actual.channel().trim().toUpperCase());
        }
        if (actual.status() != null && !actual.status().isBlank()) {
            wrapper.eq(NotifyTemplate::getStatus, actual.status().trim().toUpperCase());
        }
        wrapper.orderByAsc(NotifyTemplate::getTemplateCode);
        return mapper().selectPage(new Page<>(pageNum(actual.pageNum()), pageSize(actual.pageSize())), wrapper);
    }

    public int insert(NotifyTemplate row) {
        return mapper().insert(row);
    }
    public int updateById(NotifyTemplate row) {
        return mapper().updateById(row);
    }
    public int deleteById(NotifyTemplate row) {
        return mapper().deleteById(row);
    }
    private NotifyTemplateMapper mapper() {
        NotifyTemplateMapper mapper = mappers.getIfAvailable();
        if (mapper == null) {
            throw new SystemException("通知模板不可用：未配置数据库");
        }
        return mapper;
    }
    private static long pageNum(Integer value) {
        return value == null || value < 1 ? 1 : value;
    }
    private static long pageSize(Integer value) {
        return value == null || value < 1 || value > 200 ? 20 : value;
    }
}
