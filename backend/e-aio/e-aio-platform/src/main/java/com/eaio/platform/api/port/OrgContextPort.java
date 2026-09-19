package com.eaio.platform.api.port;

import java.util.Optional;

/**
 * 组织/用户上下文端口（platform 自有端口，ADR-0005 / P1 册 2.4.2）。
 *
 * <p><b>为什么是 platform 自己的端口</b>：platform 编译期对 iam 零依赖（连 {@code iam.api} 都不依赖），
 * 而"当前请求属于哪个组织/用户"必须由 iam 的认证链路提供。解耦办法是 platform 声明端口、应用壳注入
 * iam 侧适配器——与 P0 册 4.4 的 {@code IdempotencyStore} 同款做法（契约在模块内，实现随首个消费者）。
 *
 * <p><b>缺席（无适配器，iam 未交付）时的降级</b>：{@link #current()} 恒为
 * {@link Optional#empty()}，参数解析退化为**只看 SYSTEM 级**（不放行组织级/用户级覆盖）——
 * 这是更严格的退化，不是"全量可见"（P1 册 3.8 安全底线：不得静默降级为放行）。
 *
 * <p>本接口由**平台侧定义、业务侧实现**：实现类必须在 iam 模块（不得落在 {@code api} 包内），
 * 由应用壳装配。
 */
public interface OrgContextPort {

    /** 当前请求的组织/用户上下文；无请求上下文（定时任务、启动预热）返回空。 */
    Optional<OrgContext> current();

    /**
     * 组织与用户标识。
     *
     * @param orgId  组织 ID（&gt;0）
     * @param userId 用户 ID（&gt;0）
     */
    record OrgContext(long orgId, long userId) {
    }
}
