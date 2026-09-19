/**
 * 平台跨模块 DTO（record）。
 *
 * <p>P1 册 2.3 裁决 P1-C2：{@code api} 包只放 interface + record + enum，且字段类型限
 * JDK / {@code java.time.*} / 其他 {@code api} DTO / {@code PageResult} / {@code Resource}；
 * 实体与 Mapper 不得出现在这里（实体 ↔ DTO 由 MapStruct 在 application/infrastructure 层转换）。
 */
@org.springframework.modulith.NamedInterface("api")
package com.eaio.platform.api.dto;
