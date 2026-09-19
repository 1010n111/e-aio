package com.eaio.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 脱敏规则（口径：宁可多掩，长度不足时全掩）。 */
class SensitiveUtilsTest {

    @Test
    @DisplayName("手机号保留前 3 后 4")
    void mobile() {
        assertThat(SensitiveUtils.maskMobile("13812345678")).isEqualTo("138****5678");
        assertThat(SensitiveUtils.maskMobile("1381234")).isEqualTo("*******");
    }

    @Test
    @DisplayName("身份证保留前 6 后 4")
    void idCard() {
        assertThat(SensitiveUtils.maskIdCard("110101199001011234")).isEqualTo("110101********1234");
    }

    @Test
    @DisplayName("银行卡保留前 4 后 4")
    void bankCard() {
        assertThat(SensitiveUtils.maskBankCard("6222021234567890")).isEqualTo("6222********7890");
    }

    @Test
    @DisplayName("邮箱保留首字符与域名")
    void email() {
        assertThat(SensitiveUtils.maskEmail("alice@example.com")).isEqualTo("a****@example.com");
        assertThat(SensitiveUtils.maskEmail("a@example.com")).isEqualTo("a*@example.com");
        // 不合法邮箱整串掩掉，不猜结构
        assertThat(SensitiveUtils.maskEmail("not-an-email")).isEqualTo("************");
    }

    @Test
    @DisplayName("姓名保留姓氏")
    void name() {
        assertThat(SensitiveUtils.maskName("张三")).isEqualTo("张*");
        assertThat(SensitiveUtils.maskName("欧阳修")).isEqualTo("欧**");
        assertThat(SensitiveUtils.maskName("张")).isEqualTo("*");
    }

    @Test
    @DisplayName("地址保留前 6 位")
    void address() {
        assertThat(SensitiveUtils.mask("北京市朝阳区建国路88号", SensitiveType.ADDRESS))
                .isEqualTo("北京市朝阳区******");
    }

    @Test
    @DisplayName("空值原样返回：不制造 \"null\" 字符串")
    void nullAndEmptyStayAsIs() {
        assertThat(SensitiveUtils.mask(null, SensitiveType.MOBILE)).isNull();
        assertThat(SensitiveUtils.mask("", SensitiveType.MOBILE)).isEmpty();
        assertThat(SensitiveUtils.maskEmail(null)).isNull();
        assertThat(SensitiveUtils.maskName("")).isEmpty();
    }

    @Test
    @DisplayName("保留位数吃掉整串时全掩（否则脱敏等于没脱）")
    void keepsNeverSwallowWholeValue() {
        assertThat(SensitiveUtils.mask("1234", 2, 2)).isEqualTo("****");
        assertThat(SensitiveUtils.mask("12345", 3, 2)).isEqualTo("*****");
    }

    @Test
    @DisplayName("自定义保留位数：参数为负直接抛错")
    void negativeKeepCountsRejected() {
        assertThatThrownBy(() -> SensitiveUtils.mask("123456", -1, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
