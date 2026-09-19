package com.eaio.platform.domain.param;

import java.util.Locale;

import com.eaio.common.exception.BusinessException;
import com.eaio.platform.api.PlatformErrorCode;

/** 参数值类型（P1 册 4.3.1 的 {@code ck_param_value_type}）：库里存名字，代码侧只认这个枚举。 */
public enum ParamValueType {

    /** 任意文本。 */
    STRING,
    /** 整数（{@code Long} 范围，读取时按调用方要的类型转换）。 */
    INT,
    /** 布尔：{@code true}/{@code false}（大小写不敏感）。 */
    BOOL,
    /** 十进制（{@code BigDecimal}，禁止用 double 承载）。 */
    DECIMAL,
    /** JSON 文本（落库前必须能被 json 解析）。 */
    JSON,
    /** 敏感值：AES-GCM 密文落库，接口永不回显明文。 */
    SECRET;

    /** 是否按密文处理（加密落库、接口回 {@code ******}）。 */
    public boolean secret() {
        return this == SECRET;
    }

    /** 解析类型名；非法值抛 {@code 20002 PARAM_TYPE_MISMATCH}（管理端写坏类型的唯一入口）。 */
    public static ParamValueType fromName(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(PlatformErrorCode.PARAM_TYPE_MISMATCH, "参数值类型不得为空");
        }
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(PlatformErrorCode.PARAM_TYPE_MISMATCH,
                    "未登记的参数值类型：" + name + "（只认 STRING/INT/BOOL/DECIMAL/JSON/SECRET）");
        }
    }
}
