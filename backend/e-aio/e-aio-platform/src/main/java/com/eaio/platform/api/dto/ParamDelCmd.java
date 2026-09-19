package com.eaio.platform.api.dto;

/**
 * 删除入参（P1 册 5.2 的 {@code /platform/param/Del} 入参 {@code {id, version}}）。
 *
 * <p><b>契约补全</b>：同 {@link ParamGetCmd}，设计册未给名字；字段与 5.2 逐字一致。
 *
 * @param id      行 ID
 * @param version 乐观锁版本（不匹配即 10003）
 */
public record ParamDelCmd(long id, int version) {
}
