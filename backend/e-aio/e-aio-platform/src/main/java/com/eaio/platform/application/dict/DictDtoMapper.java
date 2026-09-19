package com.eaio.platform.application.dict;

import com.eaio.platform.api.dto.DictItemDTO;
import com.eaio.platform.api.dto.DictTypeDTO;
import com.eaio.platform.domain.dict.DictItem;
import com.eaio.platform.domain.dict.DictType;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * 实体 ↔ DTO 映射（P1 册裁决 P1-C3：MapStruct、{@code componentModel = "spring"}、
 * {@code unmappedTargetPolicy = ERROR}）。
 *
 * <p>{@code ERROR} 是刻意的：新增实体字段却忘了进 DTO 时，编译失败比"接口悄悄少一个字段"便宜得多。
 *
 * <p>类型多一个源参数 {@code itemCount}：5.3 要求它在列表页展示，而它是 {@code dict_item} 的聚合值，
 * 不在 {@code dict_type} 行上——只能由调用方查出来传进来（MapStruct 无法凭空聚合）。
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface DictDtoMapper {

    /** 类型行 + 项的聚合数量 → DTO。 */
    @Mapping(target = "itemCount", source = "itemCount")
    DictTypeDTO toDto(DictType type, int itemCount);

    /** 项行 → DTO。 */
    DictItemDTO toDto(DictItem item);
}
