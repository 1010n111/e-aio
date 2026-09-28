package com.eaio.platform.infrastructure.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

import com.eaio.platform.api.PlatformErrorCode;
import com.eaio.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 本地盘预签名 token（P1 册 3.3.4）：{@code sig = hex(HMAC-SHA256(secret, fileId + "|" + exp))}，
 * 链接形如 {@code /api/platform/file/Download?fileId={id}&exp={epochSecond}&sig={hmac}}。
 *
 * <p><b>密钥来自环境变量 {@code EAIO_FILE_PRESIGN_SECRET}（≥32 字节）</b>，不进参数中心——参数中心要连库，
 * 而"验证下载链接"必须在一个连库失败的进程里也能给出确定的答案（3.1.5 的自举问题）。
 *
 * <p><b>密钥缺失/过短时 fail-closed</b>：启动期记 ERROR（让运维一眼看到"这台机器的下载链接全废了"），
 * 之后**所有**签名校验都判无效（20016）。绝不"没配就随机生成一个"——那会让重启后历史链接全部失效，
 * 且问题在第一次重启之后才暴露；也绝不"没配就跳过校验"——那就是任意文件下载漏洞。
 *
 * <p><b>校验顺序（3.3.8 明文）</b>：先**常量时间比较签名**，再判时效。反过来的话，攻击者可以拿
 * "过期与否"当侧信道逐位猜签名：过期判定只需要 exp，签名对不对根本不用知道。
 *
 * <p>取舍：token 不是一次性、也不可撤销（撤销 = 轮换 secret，代价是全部历史链接立刻失效）——对
 * "浏览器直下不便带 header"的场景够用；敏感文件的真正锁定靠**同一套可见性判定**（{@code FileAccessGuard}），
 * token 只证明"链接是本站签发的"。
 */
@Component
public class FilePresignTokenService {

    /** 环境变量名（7.2 的环境变量清单，不进参数中心）。 */
    public static final String SECRET_ENV = "EAIO_FILE_PRESIGN_SECRET";

    /** 允许的最小密钥长度（字节）：低于它等于用短口令签链接，可被离线爆破。 */
    public static final int MIN_SECRET_BYTES = 32;

    /** 默认有效期（秒）：调用方传非正数时用它。 */
    public static final int DEFAULT_EXPIRE_SECONDS = 300;

    /** 有效期上限（秒，7 天）：避免"传个 10 年"把时效 token 变成永久链接。 */
    public static final int MAX_EXPIRE_SECONDS = 604_800;

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private static final Logger log = LoggerFactory.getLogger(FilePresignTokenService.class);

    private final byte[] secret;

    public FilePresignTokenService(@Value("${" + SECRET_ENV + ":${eaio.file.presign-secret:}}") String secretValue) {
        this.secret = secretValue == null ? new byte[0] : secretValue.trim().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 启动期把"密钥不可用"喊出来（但不阻断启动）。
     *
     * <p>为什么不在构造器里抛异常：P0 冻结了"空的应用（无库无 Redis）也能启动"，而下载链接校验不是
     * 启动的必要条件——它的失败模式是"下载链接全返 20016"，这在运行期是可观测、可解释的。
     */
    @PostConstruct
    void warnIfSecretUnusable() {
        if (secret.length < MIN_SECRET_BYTES) {
            log.error("{} 未配置或长度不足 {} 字节（当前 {} 字节）：**全部预签名下载链接将被判无效**（20016）。"
                    + "请设置环境变量后重启，切勿使用短密钥", SECRET_ENV, MIN_SECRET_BYTES, secret.length);
        }
    }

    /** 密钥是否可用（诊断/测试用）。 */
    public boolean secretUsable() {
        return secret.length >= MIN_SECRET_BYTES;
    }

    /** 签发：返回 {@code exp} 与 {@code sig}（调用方拼 URL）；密钥不可用时**抛 20016**（5.2 给 GetUrl 列了它）。 */
    public PresignedToken sign(long fileId, int expireSeconds) {
        if (!secretUsable()) {
            // 不抛 IllegalArgumentException（那会对外变成 10500）：密钥不可用是"签名能力不可用"，
            // 与校验失败同码 20016，调用方/运维看到的都是同一句"下载签名无效或已过期"
            throw invalidSignature();
        }
        int ttl = normalizeTtl(expireSeconds);
        long exp = Instant.now().getEpochSecond() + ttl;
        return new PresignedToken(exp, hex(hmac(fileId, exp)));
    }

    /**
     * 校验顺序见类注释：**先比签名，再判时效**。
     *
     * @param fileId 链接里声明的文件 ID（签名的一部分；换 ID 用同一签名一定失败）
     * @param exp    链接里的失效时刻（epoch 秒；缺失/非数字 = 判定不了签名，直接 INVALID）
     * @param sig    链接里的签名（十六进制；缺失/非十六进制 = INVALID）
     */
    public PresignVerification verify(long fileId, String exp, String sig) {
        if (!secretUsable()) {
            return PresignVerification.INVALID;
        }
        long expSeconds;
        try {
            expSeconds = Long.parseLong(exp == null ? "" : exp.trim());
        } catch (NumberFormatException e) {
            return PresignVerification.INVALID;
        }
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(sig == null ? "" : sig.trim());
        } catch (IllegalArgumentException e) {
            return PresignVerification.INVALID;
        }
        // 常量时间比较（MessageDigest.isEqual 对两个数组逐字节比对，不提前返回）
        if (!MessageDigest.isEqual(hmac(fileId, expSeconds), provided)) {
            return PresignVerification.INVALID;
        }
        return expSeconds > Instant.now().getEpochSecond() ? PresignVerification.VALID : PresignVerification.EXPIRED;
    }

    /** 校验失败时抛 20016（HTTP 面与内部调用共用同一句话）。 */
    public static BusinessException invalidSignature() {
        return new BusinessException(PlatformErrorCode.FILE_SIGNATURE_INVALID, "下载签名无效或已过期");
    }

    /** 有效期夹取：非正数取默认，超过上限被截断（上限见 {@link #MAX_EXPIRE_SECONDS}）。 */
    public static int normalizeTtl(int expireSeconds) {
        if (expireSeconds <= 0) {
            return DEFAULT_EXPIRE_SECONDS;
        }
        return Math.min(expireSeconds, MAX_EXPIRE_SECONDS);
    }

    private byte[] hmac(long fileId, long exp) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal((fileId + "|" + exp).getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            // HmacSHA256 是 JDK 必备算法；走到这里说明运行环境被裁剪过，属于环境故障
            throw new IllegalStateException("HMAC-SHA256 不可用（JDK 算法被裁剪？）", e);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    /** 签发的 token（{@code exp} 是 epoch 秒，{@code sig} 是十六进制签名）。 */
    public record PresignedToken(long exp, String sig) {
    }

    /** 校验结果：三种状态分开表达，调用方才能把"过期"和"被篡改"记成不同的事件（对外都是 20016）。 */
    public enum PresignVerification {
        /** 签名有效且未过期。 */
        VALID,
        /** 签名无效（含密钥不可用、参数缺失/畸形、被篡改）。 */
        INVALID,
        /** 签名有效但已过期。 */
        EXPIRED
    }
}
