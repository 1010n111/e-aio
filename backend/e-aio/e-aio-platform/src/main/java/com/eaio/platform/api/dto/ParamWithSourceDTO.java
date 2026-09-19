package com.eaio.platform.api.dto;

import java.util.List;

/**
 * 生效值 + 来源（跨模块 DTO，P1 册 5.3 契约 V1）。
 *
 * <p>管理页靠它回答两个问题："这个键现在到底是什么值"（{@code effectiveValue}）与
 * "这个值从哪来、被谁覆盖"（{@code source}/{@code sourceLevel}/{@code candidates}）。
 *
 * @param paramKey       参数键
 * @param effectiveValue 生效值（未定义键为空）
 * @param source         来源：DEFAULT（调用方默认值/代码默认）、YAML（Spring 配置）、DB（参数表）
 * @param sourceLevel    生效级别 SYSTEM/ORG/USER；来源为 DEFAULT/YAML 时为空
 * @param candidates     三级候选行（按 USER &gt; ORG &gt; SYSTEM 排列，管理页展示覆盖关系）
 * @param hotReload      是否热更新（false 提示"重启生效"）
 */
public record ParamWithSourceDTO(
        String paramKey,
        String effectiveValue,
        String source,
        String sourceLevel,
        List<ParamDTO> candidates,
        boolean hotReload) {
}
