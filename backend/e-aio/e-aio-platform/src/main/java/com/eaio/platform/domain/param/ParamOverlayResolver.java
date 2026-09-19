package com.eaio.platform.domain.param;

import java.util.List;
import java.util.Optional;

/**
 * 分级覆盖：给定同一个键的 0..3 行候选，求生效行（P1 册 3.1.2、3.1.3）。
 *
 * <p>**纯函数、无 IO**：覆盖顺序是最容易出错又最容易测的规则，所以刻意从 {@code ParamResolver}
 * 里拆出来（3.1.2 的理由原文）。判定分两步：先用 {@link ParamContext#owns} 过滤"属于本上下文"
 * 的行，再取优先级最高的一行。
 *
 * <p>不在候选里做"优先级排序后取第一"的单步写法：那样上下文过滤与优先级会缠在一个比较器里，
 * "组织级值被用户级值覆盖"这类缺陷只能靠集成测试发现。
 */
public final class ParamOverlayResolver {

    private ParamOverlayResolver() {
    }

    /**
     * 求生效行。
     *
     * @param candidates 同一个键的候选行（任意顺序，可为空）
     * @param context    当前上下文
     * @return 生效行；三级都无匹配（键未定义，或只有其他组织/用户的行）时为空
     */
    public static Optional<ParamItem> effective(List<ParamItem> candidates, ParamContext context) {
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        ParamItem winner = null;
        ParamLevel winnerLevel = null;
        for (ParamItem candidate : candidates) {
            ParamLevel level = ParamLevel.fromName(candidate.getParamLevel());
            if (!context.owns(level, candidate.getOwnerId())) {
                continue;
            }
            if (winnerLevel == null || level.priority() > winnerLevel.priority()) {
                winner = candidate;
                winnerLevel = level;
            }
        }
        return Optional.ofNullable(winner);
    }

    /** 生效级别名；无生效行时为空（管理页的 {@code sourceLevel}）。 */
    public static String effectiveLevel(List<ParamItem> candidates, ParamContext context) {
        return effective(candidates, context).map(ParamItem::getParamLevel).orElse(null);
    }
}
