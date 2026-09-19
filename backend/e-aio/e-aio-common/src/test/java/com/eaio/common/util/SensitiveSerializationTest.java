package com.eaio.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eaio.common.json.JsonUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 脱敏的"牙齿"测试（P0 册 4.8）：序列化输出必须是掩码，不是原文。
 *
 * <p>为什么必须有：Jackson 3 换了序列化器基类与注解包，若 import 成旧代际同名类
 * （{@code com.fasterxml.jackson.databind.annotation.JsonSerialize}），Jackson 3 **不报错也不脱敏**，
 * 结果就是手机号/身份证明文外泄。这个断言是唯一能拦住它的机制——它失败意味着泄露，不是"测试要改"。
 */
class SensitiveSerializationTest {

    /** 业务私密信息。字段均为 private：显式标注 @JsonProperty 才参与序列化，与真实 DTO 一致。 */
    private static class Patient {

        @com.fasterxml.jackson.annotation.JsonProperty("mobile")
        @Sensitive(type = SensitiveType.MOBILE)
        @tools.jackson.databind.annotation.JsonSerialize(using = SensitiveSerializer.class)
        private String mobile = "13812345678";

        @com.fasterxml.jackson.annotation.JsonProperty("idCard")
        @Sensitive(type = SensitiveType.ID_CARD)
        @tools.jackson.databind.annotation.JsonSerialize(using = SensitiveSerializer.class)
        private String idCard = "110101199001011234";

        @com.fasterxml.jackson.annotation.JsonProperty("email")
        @Sensitive(type = SensitiveType.EMAIL)
        @tools.jackson.databind.annotation.JsonSerialize(using = SensitiveSerializer.class)
        private String email = "alice@example.com";

        @com.fasterxml.jackson.annotation.JsonProperty("name")
        @Sensitive(type = SensitiveType.NAME)
        @tools.jackson.databind.annotation.JsonSerialize(using = SensitiveSerializer.class)
        private String name = "张三";

        @com.fasterxml.jackson.annotation.JsonProperty("bankCard")
        @Sensitive(type = SensitiveType.BANK_CARD)
        @tools.jackson.databind.annotation.JsonSerialize(using = SensitiveSerializer.class)
        private String bankCard = "6222021234567890";

        @com.fasterxml.jackson.annotation.JsonProperty("orderNo")
        @Sensitive(type = SensitiveType.CUSTOM, prefixKeep = 2, suffixKeep = 2)
        @tools.jackson.databind.annotation.JsonSerialize(using = SensitiveSerializer.class)
        private String orderNo = "AB1234567890YZ";

        /** 有业务含义但未被标为敏感：必须原样输出（防"脱敏把正常字段也改了"）。 */
        @com.fasterxml.jackson.annotation.JsonProperty("remark")
        private String remark = "13800000000";
    }

    @Test
    @DisplayName("序列化输出为掩码：原文不得出现在 JSON 里")
    void maskedFieldsNeverLeakPlainText() {
        String json = JsonUtils.toJson(new Patient());

        assertThat(json).doesNotContain("13812345678");
        assertThat(json).doesNotContain("110101199001011234");
        assertThat(json).doesNotContain("alice@example.com");
        assertThat(json).doesNotContain("6222021234567890");
        assertThat(json).doesNotContain("AB1234567890YZ");
    }

    @Test
    @DisplayName("各类型掩码按声明生效（不是一刀切全掩）")
    void eachTypeUsesItsOwnRule() {
        String json = JsonUtils.toJson(new Patient());

        assertThat(json).contains("\"mobile\":\"138****5678\"");
        assertThat(json).contains("\"idCard\":\"110101********1234\"");
        assertThat(json).contains("\"email\":\"a****@example.com\"");
        assertThat(json).contains("\"name\":\"张*\"");
        assertThat(json).contains("\"bankCard\":\"6222********7890\"");
        assertThat(json).contains("\"orderNo\":\"AB**********YZ\"");
    }

    @Test
    @DisplayName("未加注解的字段保持原样：脱敏是逐字段声明，不是全局替换")
    void unannotatedFieldStaysPlain() {
        assertThat(JsonUtils.toJson(new Patient())).contains("\"remark\":\"13800000000\"");
    }

    @Test
    @DisplayName("Jackson 3 的注解类必须存在且是脱敏依赖的那一个（防跨代同名误用）")
    void jacksonThreeAnnotationIsTheOneInUse() {
        assertThat(tools.jackson.databind.annotation.JsonSerialize.class.getName())
                .isEqualTo("tools.jackson.databind.annotation.JsonSerialize");
        assertThat(SensitiveSerializer.class.getSuperclass().getName())
                .isEqualTo("tools.jackson.databind.ValueSerializer");
    }

    @Test
    @DisplayName("自定义类型缺保留位数时抛错，不静默输出原文")
    void customTypeRequiresKeepCounts() {
        assertThatThrownBy(() -> SensitiveUtils.mask("AB1234567890YZ", SensitiveType.CUSTOM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CUSTOM");
    }
}
