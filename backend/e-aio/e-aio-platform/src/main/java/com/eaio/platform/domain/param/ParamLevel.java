package com.eaio.platform.domain.param;

import java.util.Locale;

import com.eaio.common.exception.BusinessException;
import com.eaio.platform.api.PlatformErrorCode;

/**
 * 参数级别（P1 册 3.1.2）：覆盖顺序 {@code USER > ORG > SYSTEM}。
 *
 * <p>库里存枚举**名**（{@code VARCHAR(32)} + CHECK，禁 PG ENUM），代码侧只认这一个枚举：字符串到处散着比
 * 较，写错一个字母不会报错、只会永远取不到覆盖值。
 */
public enum ParamLevel {

    /** 系统级：{@code owner_id} 恒为 0，平台自带。 */
    SYSTEM,
    /** 组织级：{@code owner_id} = 组织 ID。 */
    ORG,
    /** 用户级：{@code owner_id} = 用户 ID；按 3.1.5 只进 L1 缓存（不进 L2，避免键空间爆炸）。 */
    USER;

    /** 覆盖优先级：数字越大越优先。 */
    public int priority() {
        return switch (this) {
            case USER -> 3;
            case ORG -> 2;
            case SYSTEM -> 1;
        };
    }

    /**
     * 解析级别名；非法值抛 {@code 20006 PARAM_SCOPE_INVALID}。
     *
     * <p>不复用 {@code IllegalArgumentException}：级别名来自请求体，属"可以预期、应向用户说明"的失败。
     */
    public static ParamLevel fromName(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(PlatformErrorCode.PARAM_SCOPE_INVALID, "参数级别不得为空（SYSTEM/ORG/USER）");
        }
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(PlatformErrorCode.PARAM_SCOPE_INVALID,
                    "未登记的参数级别：" + name + "（只认 SYSTEM/ORG/USER）");
        }
    }

    /** 该级别是否允许该归属：SYSTEM 必须 0，ORG/USER 必须 &gt;0（P1 册 4.3.1 的 ck_param_owner）。 */
    public boolean accepts(long ownerId) {
        return this == SYSTEM ? ownerId == 0 : ownerId > 0;
    }
}
