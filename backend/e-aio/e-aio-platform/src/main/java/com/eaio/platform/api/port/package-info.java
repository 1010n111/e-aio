/**
 * 平台自有端口（由业务模块侧实现、应用壳装配）。
 *
 * <p>ADR-0005：platform 编译期对 iam/audit 零依赖，需要"外部信息"（当前组织/用户、审计落库）时
 * 声明自己的端口，实现放在对方模块——依赖方向仍是"业务模块 → platform.api"。
 *
 * <p>端口与实现分离的另一条硬要求：**实现类不得落在 {@code api} 包内**
 * （由 {@code ArchitectureTest} 的端口规则断言）。
 */
@org.springframework.modulith.NamedInterface("api")
package com.eaio.platform.api.port;
