package com.eaio.platform.api.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 通知模板保存入参；占位符声明在保存时校验。 */
public record NotifyTemplateSaveCmd(
        @NotBlank @Size(max = 64) String templateCode,
        @NotBlank @Size(max = 128) String templateName,
        @NotBlank String channel,
        @Size(max = 255) String titleTemplate,
        @NotNull String contentTemplate,
        List<String> variables,
        String status,
        @Size(max = 255) String remark,
        Integer version) {
}
