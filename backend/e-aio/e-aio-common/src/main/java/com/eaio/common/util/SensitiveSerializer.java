package com.eaio.common.util;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/**
 * 脱敏序列化器（Jackson 3），按字段上的 {@link Sensitive} 声明决定掩码规则。
 *
 * <p><b>跨代同名陷阱</b>：Jackson 3 的序列化基类叫 {@link ValueSerializer}（Jackson 2 叫
 * {@code JsonSerializer}），注解叫 {@code tools.jackson.databind.annotation.JsonSerialize}
 * （Jackson 2 叫 {@code com.fasterxml.jackson.databind.annotation.JsonSerialize}）。
 * 写成旧代际的注解时，Jackson 3 **既不应用本序列化器也不报错**，明文照常输出——
 * 所以使用处必须写全限定名，且测试必须断言输出是掩码（{@code SensitiveSerializationTest}）。
 *
 * <p>用法：
 * <pre>{@code
 * @Sensitive(type = SensitiveType.MOBILE)
 * @tools.jackson.databind.annotation.JsonSerialize(using = SensitiveSerializer.class)
 * private String mobile;
 * }</pre>
 */
public class SensitiveSerializer extends ValueSerializer<String> {

    @Override
    public ValueSerializer<?> createContextual(SerializationContext context, BeanProperty property) {
        Sensitive sensitive = property == null ? null : property.getAnnotation(Sensitive.class);
        SensitiveType type = sensitive == null ? SensitiveType.MOBILE : sensitive.type();
        return new OfType(type, sensitive == null ? 0 : sensitive.prefixKeep(),
                sensitive == null ? 0 : sensitive.suffixKeep());
    }

    @Override
    public void serialize(String value, JsonGenerator generator, SerializationContext context) {
        generator.writeString(SensitiveUtils.mask(value, SensitiveType.MOBILE));
    }

    /** 绑定到具体脱敏规则的实例。 */
    private static final class OfType extends ValueSerializer<String> {

        private final SensitiveType type;
        private final int prefixKeep;
        private final int suffixKeep;

        private OfType(SensitiveType type, int prefixKeep, int suffixKeep) {
            this.type = type;
            this.prefixKeep = prefixKeep;
            this.suffixKeep = suffixKeep;
        }

        @Override
        public void serialize(String value, JsonGenerator generator, SerializationContext context) {
            String masked = type == SensitiveType.CUSTOM
                    ? SensitiveUtils.mask(value, prefixKeep, suffixKeep)
                    : SensitiveUtils.mask(value, type);
            generator.writeString(masked);
        }
    }
}
