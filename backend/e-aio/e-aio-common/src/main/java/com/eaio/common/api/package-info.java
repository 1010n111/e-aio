/**
 * 通用能力层契约包（契约 V1）：跨模块**唯一允许**依赖的包。
 *
 * <p>对外类型：{@code Result}/{@code PageResult}/{@code ErrorCode}/{@code BusinessErrorCode}/
 * {@code IdempotencyStore}。其余包（{@code exception}、{@code json}、{@code util}、{@code id}）
 * 属内部实现：Modulith 会拦下对它们的跨模块引用（"depends on non-exposed type"），
 * {@code ArchitectureTest} 另有 ArchUnit 规则兜同一件事。
 *
 * <p>本包用 {@code spring-modulith-api} 的注解，范围 **provided**：编译期可见、运行期不装进产物，
 * 通用能力层仍然保持"零运行时依赖"。
 */
@org.springframework.modulith.NamedInterface("api")
package com.eaio.common.api;
