package com.eaio.platform.domain.param;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eaio.common.exception.BusinessException;
import com.eaio.platform.api.PlatformErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 值类型校验与转换的纯函数测试（P1 册 3.1.2；口径：类型不匹配抛 20002，绝不静默回退默认值）。 */
class ParamValueCodecTest {

    @Test
    @DisplayName("类型校验：各类型接受/拒绝的边界")
    void isValid() {
        assertThat(ParamValueCodec.isValid("42", ParamValueType.INT)).isTrue();
        assertThat(ParamValueCodec.isValid(" 42 ", ParamValueType.INT)).isTrue();
        assertThat(ParamValueCodec.isValid("42.5", ParamValueType.INT)).isFalse();
        assertThat(ParamValueCodec.isValid("", ParamValueType.INT)).isFalse();

        assertThat(ParamValueCodec.isValid("true", ParamValueType.BOOL)).isTrue();
        assertThat(ParamValueCodec.isValid("FALSE", ParamValueType.BOOL)).isTrue();
        assertThat(ParamValueCodec.isValid("1", ParamValueType.BOOL)).isFalse();

        assertThat(ParamValueCodec.isValid("1.50", ParamValueType.DECIMAL)).isTrue();
        assertThat(ParamValueCodec.isValid("1e3", ParamValueType.DECIMAL)).isTrue();
        assertThat(ParamValueCodec.isValid("abc", ParamValueType.DECIMAL)).isFalse();

        assertThat(ParamValueCodec.isValid("{\"a\":1}", ParamValueType.JSON)).isTrue();
        assertThat(ParamValueCodec.isValid("{not-json", ParamValueType.JSON)).isFalse();

        assertThat(ParamValueCodec.isValid("anything", ParamValueType.STRING)).isTrue();
        assertThat(ParamValueCodec.isValid("anything", ParamValueType.SECRET)).isTrue();
        assertThat(ParamValueCodec.isValid(null, ParamValueType.INT)).isTrue();
    }

    @Test
    @DisplayName("规范化：整数去空格、布尔统一小写、十进制去尾零")
    void normalize() {
        assertThat(ParamValueCodec.normalize(" 42 ", ParamValueType.INT)).isEqualTo("42");
        assertThat(ParamValueCodec.normalize("TRUE", ParamValueType.BOOL)).isEqualTo("true");
        assertThat(ParamValueCodec.normalize("1.50", ParamValueType.DECIMAL)).isEqualTo("1.5");
        assertThat(ParamValueCodec.normalize("原样", ParamValueType.STRING)).isEqualTo("原样");
        assertThat(ParamValueCodec.normalize(null, ParamValueType.STRING)).isNull();
    }

    @Test
    @DisplayName("规范化失败抛 20002，且消息不回显完整值（SECRET 原文不得进日志/响应）")
    void normalizeRejectsWithMaskedMessage() {
        assertThatThrownBy(() -> ParamValueCodec.normalize("not-a-number", ParamValueType.INT))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(PlatformErrorCode.PARAM_TYPE_MISMATCH.getCode()))
                .hasMessageNotContaining("not-a-number");
    }

    @Test
    @DisplayName("读取转换：INT/BOOL 正确转换，类型不符抛 20002")
    void toIntAndBool() {
        assertThat(ParamValueCodec.toInt(" 7 ", "k")).isEqualTo(7);
        assertThat(ParamValueCodec.toBool("TrUe", "k")).isTrue();

        assertThatThrownBy(() -> ParamValueCodec.toInt("abc", "k"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(PlatformErrorCode.PARAM_TYPE_MISMATCH.getCode()));
        assertThatThrownBy(() -> ParamValueCodec.toBool("yes", "k"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ParamValueCodec.toInt(null, "k"))
                .isInstanceOf(BusinessException.class);
    }
}
