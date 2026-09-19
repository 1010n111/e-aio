package com.eaio.platform.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eaio.platform.domain.dict.DictType;
import org.apache.ibatis.annotations.Mapper;

/**
 * 字典类型表 Mapper（P1 册 3.2.2：{@code infrastructure/persistence/DictTypeMapper}，MyBatis-Plus）。
 *
 * <p>{@code @Mapper} 即可：starter 的自动扫描以应用启动类所在包（{@code com.eaio}）为根。
 */
@Mapper
public interface DictTypeMapper extends BaseMapper<DictType> {
}
