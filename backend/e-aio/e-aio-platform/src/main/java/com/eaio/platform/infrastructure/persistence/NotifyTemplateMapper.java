package com.eaio.platform.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eaio.platform.domain.notice.NotifyTemplate;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface NotifyTemplateMapper extends BaseMapper<NotifyTemplate> {
}
