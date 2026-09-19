/**
 * 平台模块契约包（跨模块**唯一允许**依赖的包）。
 *
 * <p><b>只放 interface + record + enum</b>（P1 册 2.3 裁决 P1-C2）：9 个命名接口
 * （ParamApi/DictApi/FileApi/SchedulerApi/ExcelApi/CacheApi/MonitorApi/NoticeApi/NotifyTemplateApi）、
 * 跨模块 DTO（record）、缓存区枚举与键、以及由业务模块反向实现的两个端口（AuditPort/OrgContextPort）。
 *
 * <p><b>不放</b>领域实体、Mapper、{@code @RestController}：入站适配器在 {@code infrastructure/web}，
 * 出站持久化在 {@code infrastructure/persistence}（裁决 P1-C1）。
 *
 * <p>本包用 {@code spring-modulith-api} 的注解声明命名接口，Modulith 与
 * {@code ArchitectureTest#everyApiPackageIsNamedInterface} 共同强制其成立。
 */
@org.springframework.modulith.NamedInterface("api")
package com.eaio.platform.api;
