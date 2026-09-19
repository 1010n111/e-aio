package com.eaio.platform.api.dto;

/**
 * 删除入参（P1 册 5.2 的 {@code /platform/dictType/Del} 与 {@code /platform/dictItem/Del} 入参
 * {@code {id, version}}）。
 *
 * <p><b>契约补全</b>：设计册 5.3 未给名字，与 T3 的 {@code ParamDelCmd} 同款处理。
 *
 * @param id      行 ID
 * @param version 乐观锁版本（不匹配即 10003）
 */
public record DictDelCmd(long id, int version) {
}
