package com.eaio.platform.domain.param;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.eaio.common.exception.SystemException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SECRET 参数加解密测试（P1 册 3.1.5）：AES-GCM、随机 IV、密钥长度硬校验、坏密文不降级。 */
class ParamValueCipherTest {

    private static final byte[] KEY = key((byte) 1);

    private static byte[] key(byte fill) {
        byte[] material = new byte[32];
        java.util.Arrays.fill(material, fill);
        return material;
    }

    @Test
    @DisplayName("密钥解析：Base64 与十六进制都可，长度不是 32 字节当场失败")
    void keyOf() {
        String base64 = Base64.getEncoder().encodeToString(KEY);
        assertThat(ParamValueCipher.keyOf(base64)).isEqualTo(KEY);

        String hex = "01".repeat(32);
        assertThat(ParamValueCipher.keyOf(hex)).isEqualTo(KEY);
        assertThat(ParamValueCipher.keyOf(hex)).hasSize(32);

        assertThatThrownBy(() -> ParamValueCipher.keyOf("短密钥")).isInstanceOf(SystemException.class);
        assertThatThrownBy(() -> ParamValueCipher.keyOf("   ")).isInstanceOf(SystemException.class);
        assertThatThrownBy(() -> ParamValueCipher.keyOf(null)).isInstanceOf(SystemException.class);
    }

    @Test
    @DisplayName("加解密往返；同一明文两次加密结果不同（随机 IV）")
    void encryptDecryptRoundTrip() {
        String plain = "smtp-password-中文";
        String first = ParamValueCipher.encrypt(plain, KEY);
        String second = ParamValueCipher.encrypt(plain, KEY);

        assertThat(first).startsWith(ParamValueCipher.PREFIX).isNotEqualTo(second);
        assertThat(first).doesNotContain(plain);
        assertThat(ParamValueCipher.decrypt(first, KEY)).isEqualTo(plain);
        assertThat(ParamValueCipher.decrypt(second, KEY)).isEqualTo(plain);
        assertThat(ParamValueCipher.encrypt(null, KEY)).isNull();
        assertThat(ParamValueCipher.decrypt("", KEY)).isEmpty();
    }

    @Test
    @DisplayName("坏密文/坏密钥不降级为明文：一律 SystemException")
    void badCiphertextFailsLoudly() {
        String encrypted = ParamValueCipher.encrypt("secret", KEY);

        assertThatThrownBy(() -> ParamValueCipher.decrypt(encrypted, key((byte) 2)))
                .isInstanceOf(SystemException.class);
        assertThatThrownBy(() -> ParamValueCipher.decrypt("plain-text-without-prefix", KEY))
                .isInstanceOf(SystemException.class)
                .hasMessageContaining(ParamValueCipher.PREFIX);

        // 篡改密文尾部（GCM tag 校验必须失败，而不是返回被篡改的明文）
        String tampered = encrypted.substring(0, encrypted.length() - 4) + "AAAA";
        assertThatThrownBy(() -> ParamValueCipher.decrypt(tampered, KEY)).isInstanceOf(SystemException.class);
    }

    @Test
    @DisplayName("落库基数：前缀 + base64，长度远大于明文（不泄漏原文长度以外的信息）")
    void storedFormat() {
        String encrypted = ParamValueCipher.encrypt("abc", KEY);
        String payload = encrypted.substring(ParamValueCipher.PREFIX.length());

        assertThat(Base64.getDecoder().decode(payload)).hasSizeGreaterThan(12);
        assertThat(new String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8)).doesNotContain("abc");
        assertThat(ParamValueCipher.masked()).isEqualTo("******");
    }
}
