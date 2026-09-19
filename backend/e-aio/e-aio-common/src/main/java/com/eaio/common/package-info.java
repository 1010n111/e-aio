/**
 * 通用能力层（模块化单体中的一个模块）。
 *
 * <p>定位：纯技术代码，被所有模块依赖，不依赖任何模块（HLD 5.1）。**不建表、无 Schema、不含业务逻辑**。
 *
 * <p>跨模块只暴露 {@code com.eaio.common.api}（带 {@code @NamedInterface("api")}）；
 * 其余包（{@code exception}/{@code json}/{@code util}/{@code id}）是内部实现——构建议"模块只经 api 包耦合"，
 * 由 Modulith 与 {@code ArchitectureTest} 共同强制（应用壳作为装配方的例外见 P0 册 3.7 实现注记）。
 */
@org.springframework.modulith.ApplicationModule
package com.eaio.common;
