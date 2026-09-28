package com.eaio.platform.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.eaio.common.exception.BusinessException;
import com.eaio.platform.api.PlatformErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 本地盘预签名 token 的测试（P1 册 3.3.9 第 3 行：签名有效/过期/被篡改三种独立用例）。
 *
 * <p>另加三类边界：密钥缺失（fail-closed：全部判无效 + 签发抛 20016）、换 fileId 复用签名、
 * {@code exp} 畸形。这些是"签名校验"最容易被绕过的地方，必须有独立用例。
 */
class FilePresignTokenServiceTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    private static FilePresignTokenService service(String secret) {
        FilePresignTokenService service = new FilePresignTokenService(secret);
        service.warnIfSecretUnusable();
        return service;
    }

    @Test
    @DisplayName("签名有效：sign 出的 exp/sig 能通过 verify")
    void validTokenPasses() {
        FilePresignTokenService service = service(SECRET);

        FilePresignTokenService.PresignedToken token = service.sign(42L, 300);

        assertThat(token.sig()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(token.exp()).isGreaterThan(Instant.now().getEpochSecond());
        assertThat(service.verify(42L, String.valueOf(token.exp()), token.sig()))
                .isEqualTo(FilePresignTokenService.PresignVerification.VALID);
    }

    @Test
    @DisplayName("已过期：签名对但 exp 在过去 → EXPIRED（对外 20016）")
    void expiredTokenIsRejected() {
        FilePresignTokenService service = service(SECRET);
        long past = Instant.now().getEpochSecond() - 60;

        assertThat(service.verify(42L, String.valueOf(past), hmac(42L, past)))
                .isEqualTo(FilePresignTokenService.PresignVerification.EXPIRED);
    }

    @Test
    @DisplayName("被篡改：sig 改一个字符 / exp 被改大 / 换 fileId → INVALID（对外 20016）")
    void tamperedTokenIsRejected() {
        FilePresignTokenService service = service(SECRET);
        FilePresignTokenService.PresignedToken token = service.sign(42L, 300);

        String flipped = flipLastChar(token.sig());
        assertThat(service.verify(42L, String.valueOf(token.exp()), flipped))
                .isEqualTo(FilePresignTokenService.PresignVerification.INVALID);
        assertThat(service.verify(42L, String.valueOf(token.exp() + 600), token.sig()))
                .as("把 exp 改大 = 换签名，必须 INVALID（先比签名再判时效）")
                .isEqualTo(FilePresignTokenService.PresignVerification.INVALID);
        assertThat(service.verify(43L, String.valueOf(token.exp()), token.sig()))
                .as("换 fileId 复用同一签名必须失败")
                .isEqualTo(FilePresignTokenService.PresignVerification.INVALID);
        assertThat(service.verify(42L, "not-a-number", token.sig()))
                .as("exp 畸形 = 签名不可判定")
                .isEqualTo(FilePresignTokenService.PresignVerification.INVALID);
        assertThat(service.verify(42L, String.valueOf(token.exp()), "zzzz"))
                .as("sig 非十六进制")
                .isEqualTo(FilePresignTokenService.PresignVerification.INVALID);
        assertThat(service.verify(42L, null, null)).isEqualTo(FilePresignTokenService.PresignVerification.INVALID);
    }

    @Test
    @DisplayName("密钥缺失/过短：全部校验 INVALID，签发抛 20016（不是 10500、更不是跳过校验）")
    void unusableSecretFailsClosed() {
        for (String secret : new String[] {null, "", "short-secret"}) {
            FilePresignTokenService service = service(secret);

            assertThat(service.secretUsable()).isFalse();
            assertThat(service.verify(42L, String.valueOf(Instant.now().getEpochSecond() + 300), "00"))
                    .isEqualTo(FilePresignTokenService.PresignVerification.INVALID);
            assertThatThrownBy(() -> service.sign(42L, 300))
                    .as("签发也要 20016：GetUrl 的错误码清单里有它（5.2）")
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getCode())
                            .isEqualTo(PlatformErrorCode.FILE_SIGNATURE_INVALID.getCode()));
        }
    }

    @Test
    @DisplayName("有效期夹取：非正数用默认 300、超长截到上限；轮换 secret 后旧链接立刻失效")
    void ttlIsClampedAndSecretRotationInvalidatesOldTokens() {
        assertThat(FilePresignTokenService.normalizeTtl(0))
                .isEqualTo(FilePresignTokenService.DEFAULT_EXPIRE_SECONDS);
        assertThat(FilePresignTokenService.normalizeTtl(-1))
                .isEqualTo(FilePresignTokenService.DEFAULT_EXPIRE_SECONDS);
        assertThat(FilePresignTokenService.normalizeTtl(Integer.MAX_VALUE))
                .isEqualTo(FilePresignTokenService.MAX_EXPIRE_SECONDS);

        FilePresignTokenService first = service(SECRET);
        FilePresignTokenService rotated = service("another-secret-of-32-bytes-length!!");
        FilePresignTokenService.PresignedToken token = first.sign(42L, 300);

        assertThat(rotated.verify(42L, String.valueOf(token.exp()), token.sig()))
                .as("轮换 secret 后历史链接立刻失效（3.3.4 的取舍）")
                .isEqualTo(FilePresignTokenService.PresignVerification.INVALID);
    }

    /** 按服务声明的算法独立算一次签名：测试自己拼 HMAC 才能构造"签名对但已过期"的 token。 */
    private static String hmac(long fileId, long exp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((fileId + "|" + exp).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String flipLastChar(String value) {
        char last = value.charAt(value.length() - 1);
        return value.substring(0, value.length() - 1) + (last == '0' ? '1' : '0');
    }
}
