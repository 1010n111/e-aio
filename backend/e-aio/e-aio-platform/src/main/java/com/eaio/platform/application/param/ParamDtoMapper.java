package com.eaio.platform.application.param;

import com.eaio.platform.api.dto.ParamDTO;
import com.eaio.platform.domain.param.ParamItem;
import com.eaio.platform.domain.param.ParamValueCipher;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * 实体 ↔ DTO 映射（P1 册裁决 P1-C3：MapStruct、{@code componentModel = "spring"}、
 * {@code unmappedTargetPolicy = ERROR}）。
 *
 * <p>{@code ERROR} 是刻意的：新增实体字段却忘了进 DTO 时，编译失败比"接口悄悄少一个字段"便宜得多。
 *
 * <p>只有 {@code paramValue} 需要表达式：SECRET 类（{@code encrypted = true}）对**所有**调用方
 * 都回 {@code ******}——内部要真值请用 {@code ParamApi.getString/getInt/getBool}（P1 册 5.3）。
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ParamDtoMapper {

    /** 行 → DTO（SECRET 值脱敏）。 */
    @Mapping(target = "paramValue", expression = "java(maskSecret(item))")
    ParamDTO toDto(ParamItem item);

    /** SECRET 值恒为占位符；非 SECRET 原样。 */
    default String maskSecret(ParamItem item) {
        if (item == null) {
            return null;
        }
        return item.isEncrypted() ? ParamValueCipher.masked() : item.getParamValue();
    }
}
