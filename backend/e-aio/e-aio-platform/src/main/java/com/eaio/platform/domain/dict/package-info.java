/**
 * 数据字典领域模型（P1 册 3.2.2）：{@code DictType}/{@code DictItem} 实体与状态枚举。
 *
 * <p>只放实体与纯逻辑：读路径的缓存编排在 {@code application/dict/DictResolver}，
 * 持久化在 {@code infrastructure/persistence}（依赖方向 domain ← application ← infrastructure）。
 */
package com.eaio.platform.domain.dict;
