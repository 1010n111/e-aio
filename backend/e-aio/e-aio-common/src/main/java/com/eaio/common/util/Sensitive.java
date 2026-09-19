package com.eaio.common.util;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 展示层脱敏注解：字段在 JSON 序列化时按 {@link SensitiveType} 掩码输出，存储仍是原文。
 *
 * <p><b>必须与 {@code SensitiveSerializer} 一起使用</b>：序列化器由
 * {@code tools.jackson.databind.annotation.JsonSerialize} 跨代同名注解指定，
 * 写错 import 会导致 Jackson 3 不应用脱敏且**不报错**（明文外泄）。因此这里不提供"自动套用注解"的
 * 元注解或快捷注解，唯一入口就是 {@code SensitiveSerializer} 上的 FQN 写法。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface Sensitive {

    /** 脱敏类型。 */
    SensitiveType type();

    /** 仅 {@link SensitiveType#CUSTOM} 使用：前面保留几位。 */
    int prefixKeep() default 0;

    /** 仅 {@link SensitiveType#CUSTOM} 使用：后面保留几位。 */
    int suffixKeep() default 0;
}
