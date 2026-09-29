package com.eaio.platform.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eaio.platform.domain.excel.ExcelTaskError;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ExcelTaskErrorMapper extends BaseMapper<ExcelTaskError> {
}
