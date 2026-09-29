package com.eaio.platform.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eaio.platform.domain.monitor.AlertRule;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AlertRuleMapper extends BaseMapper<AlertRule> {}
