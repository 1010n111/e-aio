package com.eaio.platform.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 类型编码入参（P1 册 5.2 的 {@code /platform/dictItem/GetItems} 与 {@code /platform/dictType/Refresh}
 * 入参 {@code {typeCode}}——两个端点同形，共用一个 record）。
 *
 * <p><b>契约补全</b>：设计册 5.3 未给名字；两个端点共用是刻意的：形状与语义都是"指定一个类型"，
 * 拆成两个同名不同义的 record 只会让人以为它们不一样。
 *
 * @param typeCode 字典类型编码
 */
public record DictTypeCodeCmd(@NotBlank String typeCode) {
}
