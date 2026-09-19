package com.eaio.platform.domain.param;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.eaio.common.exception.SystemException;

/**
 * SECRET 参数值的加解密（P1 册 3.1.5、4.3.1 的 {@code encrypted} 列）。
 *
 * <p>算法 AES-GCM：既保密又防篡改（明文一旦落库，备份、慢 SQL、日志都是泄漏面，NFR-SEC-02）。
 * 主密钥来自环境变量 {@code EAIO_PARAM_CRYPTO_KEY}（**不进库、不进参数中心**：读密钥本身要密钥，
 * 是自举问题，见 3.1.5）。密文格式：{@code base64(IV[12] || ciphertext||tag)} —— IV 每次随机，
 * 同一明文两次加密结果不同（避免"看密文猜值"与重放）。
 */
public final class ParamValueCipher {

    /** 密文前缀：区分"加密值"与历史明文值，便于迁移期识别（写路径只产这种格式）。 */
    public static final String PREFIX = "enc:v1:";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int AES_KEY_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ParamValueCipher() {
    }

    /**
     * 解析主密钥（Base64 或十六进制均可）。
     *
     * <p>密钥长度必须是 32 字节（AES-256）：短密钥/占位密钥当场失败，不留"能跑但很弱"的中间态。
     */
    public static byte[] keyOf(String text) {
        if (text == null || text.isBlank()) {
            throw new SystemException("缺少参数加密主密钥 EAIO_PARAM_CRYPTO_KEY（SECRET 类参数无法读写）");
        }
        byte[] key = decodeKey(text.trim());
        if (key.length != AES_KEY_BYTES) {
            throw new SystemException("EAIO_PARAM_CRYPTO_KEY 必须是 32 字节（AES-256），当前 " + key.length + " 字节");
        }
        return key;
    }

    /** 加密：返回 {@code enc:v1:base64(iv||ciphertext)}；{@code plain} 为空时返回空（"未设置"与"空串"可区分）。 */
    public static String encrypt(String plain, byte[] key) {
        if (plain == null) {
            return null;
        }
        byte[] iv = new byte[IV_LENGTH];
        RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException e) {
            throw new SystemException("参数值加密失败（检查 JVM 是否为受限策略）", e);
        }
    }

    /**
     * 解密。
     *
     * <p>不是本系统写出的密文（缺前缀、Base64 坏、tag 校验失败）一律抛 {@code SystemException}：
     * 这是数据损坏或密钥不匹配，**不得**降级成"返回原文"或空值。
     */
    public static String decrypt(String stored, byte[] key) {
        if (stored == null || stored.isEmpty()) {
            return stored;
        }
        if (!stored.startsWith(PREFIX)) {
            throw new SystemException("SECRET 参数值不是本系统密文格式（缺 " + PREFIX + " 前缀），拒绝按明文返回");
        }
        byte[] payload = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
        if (payload.length <= IV_LENGTH) {
            throw new SystemException("SECRET 参数密文长度非法");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, payload, 0, IV_LENGTH));
            byte[] plain = cipher.doFinal(payload, IV_LENGTH, payload.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new SystemException("参数值解密失败（密钥不匹配或密文被篡改）", e);
        }
    }

    /** 密文的展示占位（{@code ParamDTO.paramValue}）：管理端与跨模块调用方都拿到这个，永不回显明文。 */
    public static String masked() {
        return "******";
    }

    private static byte[] decodeKey(String text) {
        byte[] fromBase64 = tryBase64(text);
        if (fromBase64 != null && fromBase64.length == AES_KEY_BYTES) {
            return fromBase64;
        }
        byte[] fromHex = tryHex(text);
        if (fromHex != null) {
            return fromHex;
        }
        if (fromBase64 != null) {
            // 两种形态都能解但长度都不对：按 Base64 的结果报错，消息更贴近输入形态
            return fromBase64;
        }
        throw new SystemException("EAIO_PARAM_CRYPTO_KEY 既不是 Base64 也不是十六进制");
    }

    private static byte[] tryBase64(String text) {
        try {
            return Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 十六进制解析（运维常见写法：64 个字符 = 32 字节）。
     *
     * <p>必须先试 Base64 再试十六进制：十六进制字符集是 Base64 的子集，64 个十六进制字符**也是合法
     * Base64**，直接按 Base64 解会得到 48 字节而后报"长度不对"——把"能用的密钥"判成坏密钥。
     */
    private static byte[] tryHex(String text) {
        if (text.length() % 2 != 0) {
            return null;
        }
        byte[] bytes = new byte[text.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int high = Character.digit(text.charAt(i * 2), 16);
            int low = Character.digit(text.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                return null;
            }
            bytes[i] = (byte) ((high << 4) + low);
        }
        return bytes;
    }
}
