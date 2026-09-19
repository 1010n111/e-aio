package com.eaio.platform.application.param;

import java.security.SecureRandom;
import java.util.Base64;

import com.eaio.platform.domain.param.ParamValueCipher;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 参数加密主密钥的持有者（P1 册 3.1.5、7.2）。
 *
 * <p>密钥来源是环境变量 {@code EAIO_PARAM_CRYPTO_KEY}（Spring 宽松绑定为属性
 * {@code eaio.param.crypto-key}）：**不进库、不进参数中心、不进代码**——读密钥本身需要密钥，
 * 是自举问题（3.1.5 原文）。
 *
 * <p>**懒解析 + 每次读都失败即抛**：没有 SECRET 类参数的环境（本地/单测）不需要密钥也能启动；
 * 一旦真的读写 SECRET 参数而密钥缺失/长度不对，当场失败，绝不"用空密钥凑合"。
 */
@Component
public class ParamCryptoKeys {

    private static final String PROPERTY = "eaio.param.crypto-key";

    private final Environment environment;
    private volatile byte[] key;

    public ParamCryptoKeys(Environment environment) {
        this.environment = environment;
    }

    /** 主密钥（32 字节 AES-256）；缺配置或长度不对抛 {@code SystemException}。 */
    public byte[] key() {
        byte[] cached = key;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (key == null) {
                key = ParamValueCipher.keyOf(environment.getProperty(PROPERTY));
            }
            return key;
        }
    }

    /** 生成一把测试/本地密钥（运维生成正式密钥时也用这条口径：32 字节随机 → Base64）。 */
    public static String generate() {
        byte[] material = new byte[32];
        new SecureRandom().nextBytes(material);
        return Base64.getEncoder().encodeToString(material);
    }

    /** 属性名（给错误消息与文档引用同一处字面量）。 */
    public static String propertyName() {
        return PROPERTY;
    }
}
