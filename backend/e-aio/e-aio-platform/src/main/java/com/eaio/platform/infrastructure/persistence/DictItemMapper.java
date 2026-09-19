package com.eaio.platform.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eaio.platform.domain.dict.DictItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * 字典项表 Mapper（P1 册 3.2.2：{@code infrastructure/persistence/DictItemMapper}，MyBatis-Plus）。
 *
 * <p>{@code @Mapper} 即可：starter 的自动扫描以应用启动类所在包（{@code com.eaio}）为根。
 */
@Mapper
public interface DictItemMapper extends BaseMapper<DictItem> {
}
