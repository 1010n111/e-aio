/**
 * 基础设施层：入站与出站适配器（P1 册 2.3）。
 *
 * <p>{@code persistence}（MyBatis-Plus Mapper 与仓储实现）、{@code storage}（文件存储适配器与签名）、
 * {@code cache}（一级/二级缓存与失效订阅）、{@code scheduler}（ShedLock 与任务注册）、
 * {@code monitor}（指标绑定与快照读取）、{@code web}（{@code @RestController} 与模块内异常补全）。
 *
 * <p>Controller 放本层而非 {@code api} 包（裁决 P1-C1）：它是入站适配器，混进契约包会让
 * 跨模块可见面带上 Web 依赖。本层不得被其他模块引用。
 */
package com.eaio.platform.infrastructure;
