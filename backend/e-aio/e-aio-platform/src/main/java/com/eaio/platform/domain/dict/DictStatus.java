package com.eaio.platform.domain.dict;

import java.util.Locale;

/**
 * 字典类型/项的状态（P1 册 4.3.3 的 {@code ck_dict_type_status} 与 4.3.4 的 {@code ck_dict_item_status}，
 * 两处取值相同；落库是 {@code VARCHAR(32)} + CHECK，不用 PG ENUM）。
 *
 * <p><b>停用 ≠ 删除</b>（3.2.1）：停用项不出现在 {@code getItems}，但 {@code getLabel} 仍能解析它，
 * 历史单据因此仍显示标签而不是原始值；逻辑删除的项连 {@code getLabel} 都查不到。
 */
public enum DictStatus {

    /** 启用。 */
    ENABLED,

    /** 停用（管理页引导用停用替代删除，3.2.4）。 */
    DISABLED;

    /** 名字是否合法；非法名在写路径抛 10000（参数非法），不进 CHECK 约束。 */
    public static boolean isValid(String name) {
        if (name == null) {
            return false;
        }
        String normalized = name.trim().toUpperCase(Locale.ROOT);
        for (DictStatus status : values()) {
            if (status.name().equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    /** 名字 → 枚举；空值按 {@link #ENABLED}（DDL 默认值同）。 */
    public static DictStatus fromName(String name) {
        if (name == null || name.isBlank()) {
            return ENABLED;
        }
        return valueOf(name.trim().toUpperCase(Locale.ROOT));
    }
}
