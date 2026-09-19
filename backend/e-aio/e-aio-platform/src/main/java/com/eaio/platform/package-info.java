/**
 * 平台支撑模块（模块化单体中的一个模块，P0 册 3.1.3）。
 *
 * <p>P0 阶段本模块只有迁移脚本（{@code src/main/resources/db/migration/platform}），
 * 无 Java 业务代码；这里显式声明模块，是为了让"模块侧登记"从第一天起就成立——
 * P1 新增模块遗漏声明会被架构测试拦下（{@code ArchitectureTest#everyModuleIsDeclared}）。
 *
 * <p>P1 起对外暴露 {@code com.eaio.platform.api}（带 {@code @NamedInterface("api")}），
 * 其余包（application/domain/infrastructure/events）不跨模块可见。
 *
 * <p><b>依赖方向（P1 册 2.4.1，ADR-0005）</b>：本模块编译期只依赖 {@code common}，
 * <b>对 iam/audit 等业务模块零依赖（连 api 包都不依赖）</b>——需要组织上下文与审计时走自有端口
 * {@code api.port}，由应用壳注入适配器；依赖成环由架构测试 {@code platformDoesNotDependOnBusinessModules} 拦截。
 *
 * <p><b>为什么写 {@code "common::api"} 而不只是 {@code "common"}</b>：Modulith 把命名接口当作独立目标，
 * 只写模块名时"Allowed targets"仅剩模块根包（实测：引用 {@code com.eaio.common.redis} 会报
 * "depends on named interface(s) 'common :: api' … Allowed targets: common"）。而 common 的对外面
 * （{@code api}/{@code exception}/{@code json}/{@code util}/{@code id}/{@code redis}）按 P0 册 3.7 全部
 * 以 {@code @NamedInterface("api")} 暴露，所以必须同时登记该命名接口——它仍属 common，
 * 不引入任何业务模块依赖；架构测试断言"只允许 common 与其命名接口"。
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"common", "common::api"})
package com.eaio.platform;
