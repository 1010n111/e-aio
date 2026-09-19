/**
 * 应用层：用例编排、事务边界、事件发布（P1 册 2.3）。
 *
 * <p>放 {@code *AppService}（跨模块契约实现与 REST 入站适配器**共用同一个应用服务**，
 * 避免两条写路径语义漂移）、{@code param/ParamResolver}、{@code cache/CacheManager}、
 * {@code job/JobRegistry}、{@code file/FileStorageRouter}。
 *
 * <p>依赖方向：domain ← application ← infrastructure；本层不得被其他模块引用
 * （由 {@code ArchitectureTest#platformInternalIsNotReferenced} 拦截）。
 * 跨模块 DTO ⇆ 领域实体用 MapStruct 在本层映射（漏字段编译期失败）。
 */
package com.eaio.platform.application;
