/**
 * 平台支撑模块（模块化单体中的一个模块，P0 册 3.1.3）。
 *
 * <p>P0 阶段本模块只有迁移脚本（{@code src/main/resources/db/migration/platform}），
 * 无 Java 业务代码；这里显式声明模块，是为了让"模块侧登记"从第一天起就成立——
 * P1 新增模块遗漏声明会被架构测试拦下（{@code ArchitectureTest#everyModuleIsDeclared}）。
 *
 * <p>P1 起对外暴露 {@code com.eaio.platform.api}（带 {@code @NamedInterface("api")}），
 * 其余包（application/domain/infrastructure/events）不跨模块可见。
 */
@org.springframework.modulith.ApplicationModule
package com.eaio.platform;
