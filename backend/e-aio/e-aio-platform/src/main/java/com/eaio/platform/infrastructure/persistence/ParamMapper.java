package com.eaio.platform.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eaio.platform.domain.param.ParamItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * 参数表 Mapper（P1 册 3.1.2：{@code infrastructure/persistence/ParamMapper}，MyBatis-Plus）。
 *
 * <p>{@code @Mapper} 即可：starter 的自动扫描以应用启动类所在包（{@code com.eaio}）为根，
 * 本接口在根下，无需 {@code @MapperScan}。
 */
@Mapper
public interface ParamMapper extends BaseMapper<ParamItem> {
}
