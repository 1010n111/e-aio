/**
 * 数据字典应用内组件（P1 册 3.2.2）：{@code DictResolver}（读路径的 L1/L2 缓存与标签解析）与
 * {@code DictDtoMapper}（实体 → 跨模块 DTO）。
 *
 * <p>写路径（校验、落库、发事件、失效）在 {@code com.eaio.platform.application.DictAppService}：
 * REST 入站适配器与跨模块 {@code DictApiImpl} 共用同一个应用服务（裁决 2.4.3）。
 */
package com.eaio.platform.application.dict;
