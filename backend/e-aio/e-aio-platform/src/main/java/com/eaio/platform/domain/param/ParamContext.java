package com.eaio.platform.domain.param;

/**
 * 解析参数时的上下文（P1 册 3.1.3：{@code resolve(key, ParamContext(orgId, userId))}）。
 *
 * <p>两个 ID 都允许为 0，表示"该维度缺席"：定时任务、启动预热没有用户；未登录上下文没有组织。
 * 缺席时**不放行**对应级别的覆盖（更严格的退化），而不是"当作全部匹配"。
 *
 * @param orgId  组织 ID；0 表示无组织上下文
 * @param userId 用户 ID；0 表示无用户上下文
 */
public record ParamContext(long orgId, long userId) {

    /** 只有系统级的上下文（无组织、无用户）：{@code OrgContextPort} 缺席时的取值口径。 */
    public static ParamContext systemOnly() {
        return new ParamContext(0L, 0L);
    }

    /** 该级别的行是否属于本上下文：级别不匹配（owner 与上下文不符）即不参与覆盖。 */
    public boolean owns(ParamLevel level, long ownerId) {
        return switch (level) {
            case SYSTEM -> ownerId == 0L;
            case ORG -> orgId > 0L && ownerId == orgId;
            case USER -> userId > 0L && ownerId == userId;
        };
    }
}
