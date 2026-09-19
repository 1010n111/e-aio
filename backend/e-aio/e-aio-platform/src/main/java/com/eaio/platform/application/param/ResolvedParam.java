package com.eaio.platform.application.param;

import com.eaio.platform.domain.param.ParamItem;

/**
 * 一次解析的产物（application 内部值对象，不外泄到 api）。
 *
 * @param key          参数键
 * @param value        **生效值原文**（SECRET 类是解密后的明文；给管理端的脱敏在 DTO 映射处做，
 *                     因为 {@code getString()} 这类内部调用方需要真值）
 * @param source       来源：{@code DB}（参数表）或 {@code DEFAULT}（调用方给的默认值）
 * @param sourceLevel  生效级别 SYSTEM/ORG/USER；来源为 DEFAULT 时为空
 * @param hotReload    是否热更新（来源为 DEFAULT 时恒 true）
 * @param row          生效行（来源为 DEFAULT 时为空）
 */
public record ResolvedParam(
        String key,
        String value,
        String source,
        String sourceLevel,
        boolean hotReload,
        ParamItem row) {

    /** 键未定义时的"调用方默认值"结果（{@code get*}/{@code Get} 的兜底路径）。 */
    public static ResolvedParam ofDefault(String key, String defaultValue) {
        return new ResolvedParam(key, defaultValue, "DEFAULT", null, true, null);
    }

    /** 键未定义**且调用方没给默认值**的标记值：{@code get(key)} 走这条时抛 20001。 */
    public boolean fromDefault() {
        return "DEFAULT".equals(source);
    }
}
