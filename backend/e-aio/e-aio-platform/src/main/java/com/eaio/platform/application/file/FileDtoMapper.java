package com.eaio.platform.application.file;

import com.eaio.platform.api.dto.FileDTO;
import com.eaio.platform.domain.file.FileMetaFile;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/** 文件实体到跨模块 DTO 的编译期映射；下载链接按调用场景单独注入。 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface FileDtoMapper {

    /** 列表/元数据使用，不填充带时效链接。 */
    @Mapping(target = "url", ignore = true)
    FileDTO toDto(FileMetaFile file);

    /** GetUrl 场景填充带时效链接。 */
    @Mapping(target = "url", source = "url")
    FileDTO toDto(FileMetaFile file, String url);
}
