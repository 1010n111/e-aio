package com.eaio.platform.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eaio.platform.domain.param.ParamChangeLogItem;
import org.apache.ibatis.annotations.Mapper;

/** 参数变更历史 Mapper（只追加：代码里只有 insert 与查询）。 */
@Mapper
public interface ParamChangeLogMapper extends BaseMapper<ParamChangeLogItem> {
}
