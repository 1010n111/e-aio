/**
 * 应用壳所属模块（`com.eaio.app`：Web 入站链路与基础设施装配）。
 *
 * <p>应用壳是唯一装配方与唯一 Modulith 校验落点（P0 册 3.1.3/3.7）；
 * 它不含业务代码，业务代码按模块落在 {@code com.eaio.<module>}。
 */
@org.springframework.modulith.ApplicationModule
package com.eaio.app;
