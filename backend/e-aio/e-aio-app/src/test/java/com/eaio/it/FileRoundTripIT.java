package com.eaio.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;

import com.eaio.common.json.JsonUtils;
import com.eaio.platform.api.FileApi;
import com.eaio.platform.api.dto.FileDTO;
import com.eaio.platform.api.port.OrgContextPort;
import com.eaio.platform.application.FileAppService;
import com.eaio.platform.infrastructure.storage.FilePresignTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * 文件中心集成测试（P1 册 6.2 的 {@code FileRoundTripIT}）：真实 PostgreSQL 17（迁移到 V4）+ 本地盘。
 *
 * <p>覆盖票面验收的 5 条里与后端相关的部分：上传→下载字节与摘要一致、列表不含物理路径、
 * 伪造/过期签名 20016、可见性判定失败 20017（且不是通用 10403）、身份上下文缺席只允许上传者、
 * 留痕降级计数（{@code AuditPort} 未装配）。孤儿/软删清理任务的可运行性由
 * {@code FileOrphanCleanHandlerTest}（单测，不依赖 Docker）与本地手动触发共同覆盖。
 *
 * <p><b>不 mock</b>：上传/下载走真实 HTTP（覆盖 multipart、响应头、入站幂等过滤器与返回体包装），
 * 跨模块调用走真实 {@link FileApi}。库与 Redis 由 {@link IntegrationTestBase} 提供；本机无 Docker 时整类跳过。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FileRoundTripIT extends IntegrationTestBase {

    /**
     * 预签名密钥与本地盘根目录在**上下文启动前**注入（测试自己设，不改共享的基类）：
     * 密钥不可用时"签发链接"会 fail-closed 返回 20016，那是配置问题、不该让用例以业务断言失败收场；
     * 根目录指向构建目录，避免往开发机的 {@code ${user.home}/.eaio/files} 里写测试数据。
     */    static {
        // 字面量而不是引用下面的常量：静态初始化块按文本顺序执行，"引用后声明的字段"是前向引用错误
        System.setProperty(FilePresignTokenService.SECRET_ENV, "it-file-presign-secret-0123456789abcdef");
        System.setProperty("eaio.file.local-root", "target/it-files");
    }

    /**
     * 组织上下文替身：iam 未交付，platform 的 {@code OrgContextPort} 由应用壳注入（P1 册 2.4.2）。
     *
     * <p><b>为什么每个用例开头都显式设置</b>：替身是**静态**的，而 Spring Test 缓存上下文、
     * 多个 IT 类共享同一个 JVM——上一个测试类留下的"当前用户/组织"会带到这里（实测：不设置时
     * 文件会以别的组织的身份上传，下载判定随上下文漂移而红）。测试必须自己钉住身份。
     */
    @TestConfiguration
    static class OrgContextStub {

        static final AtomicReference<OrgContextPort.OrgContext> CURRENT = new AtomicReference<>();

        @Bean
        OrgContextPort orgContextPort() {
            return () -> Optional.ofNullable(CURRENT.get());
        }
    }

    /** 本类用例的固定身份：组织 9100、用户 9101。 */
    private static final long IT_ORG_ID = 9100L;
    private static final long IT_USER_ID = 9101L;

    /** 集成测试用的预签名密钥（≥32 字节；与 {@link #setUpProperties()} 里注入的必须是同一个值）。 */
    private static final String IT_PRESIGN_SECRET = "it-file-presign-secret-0123456789abcdef";

    @Autowired
    private FileApi fileApi;

    @Autowired
    private FileAppService fileService;

    @Autowired
    private DataSource dataSource;

    @LocalServerPort
    private int port;

    /** 每个用例先把身份钉成"组织 9100 / 用户 9101"，用例内再按需切换。 */
    @BeforeEach
    void signIn() {
        OrgContextStub.CURRENT.set(new OrgContextPort.OrgContext(IT_ORG_ID, IT_USER_ID));
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    @Test
    @DisplayName("上传→下载→删除：字节与 sha256 一致、下载响应头齐备、删除后 20014 且行被软删")
    void uploadDownloadDeleteRoundTrip() throws Exception {
        byte[] content = ("文件中心往返 " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        FileDTO uploaded = upload(content, "round-trip.txt", "text/plain");
        assertThat(uploaded.sha256()).isEqualTo(sha256(content));
        assertThat(uploaded.sizeBytes()).isEqualTo(content.length);

        ResponseEntity<byte[]> downloaded = client().post().uri("/api/platform/file/Download")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"fileId\":" + uploaded.id() + "}")
                .retrieve().toEntity(byte[].class);

        assertThat(downloaded.getBody()).isEqualTo(content);
        String disposition = downloaded.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).as("RFC 5987 的 filename* 是中文文件名的唯一无损载体")
                .startsWith("attachment; ").contains("filename*=UTF-8''round-trip.txt");
        assertThat(downloaded.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(downloaded.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("private, no-store");
        assertThat(downloaded.getHeaders().getContentType()).as("绝不能是 JSON，否则前端会当错误响应")
                .isNotEqualTo(MediaType.APPLICATION_JSON);

        // storage_path 在 root 内，且不出现在任何对外返回体里
        Map<String, Object> row = jdbc().queryForMap(
                "select storage_path, deleted, sha256 from eaio_platform.file where id = ?", uploaded.id());
        assertThat((Boolean) row.get("deleted")).isFalse();
        assertThat(row.get("sha256")).isEqualTo(sha256(content));
        assertThat(readOutsideRootEvidence(String.valueOf(row.get("storage_path"))))
                .as("列表返回体里不得出现物理路径").isFalse();

        long fallbackAfterUpload = fallbackCount();

        String deleted = post("/api/platform/file/Del",
                "{\"fileId\":" + uploaded.id() + ",\"version\":" + uploaded.version() + "}");
        assertThat(deleted).as("删除本身要成功（不是版本冲突/权限拒绝/鉴权缺失）").contains("\"code\":0");
        assertThat(jdbc().queryForObject("select deleted from eaio_platform.file where id = ?", Boolean.class,
                uploaded.id())).as("软删标记已落库（响应：" + deleted + "）").isTrue();
        assertThat(post("/api/platform/file/GetMeta", "{\"fileId\":" + uploaded.id() + "}"))
                .as("删除后元数据 → 20014").contains("\"code\":20014");
        assertThat(downloadBody(uploaded.id())).as("删除后下载 → 20014").contains("\"code\":20014");
        assertThat(fallbackCount()).as("删除也要留痕（降级期计入 fallback 计数）").isGreaterThan(fallbackAfterUpload);
    }

    @Test
    @DisplayName("预签名：签发可下载、伪造/篡改/过期一律 20016（先比签名再判时效）")
    void presignSignatureValidation() {
        byte[] content = "presign".getBytes(StandardCharsets.UTF_8);
        FileDTO uploaded = upload(content, "presign.txt", "text/plain");

        String urlResponse = post("/api/platform/file/GetUrl",
                "{\"fileId\":" + uploaded.id() + ",\"expireSeconds\":600}");
        assertThat(urlResponse).contains("\"code\":0");
        @SuppressWarnings("unchecked")
        Map<String, Object> urlData = (Map<String, Object>) data(urlResponse);
        String url = String.valueOf(urlData.get("url"));
        assertThat(url).startsWith("/api/platform/file/Download?fileId=").contains("&exp=").contains("&sig=");

        assertThat(getBytes("http://localhost:" + port + url)).as("有效签名可下载").isEqualTo(content);

        // 篡改签名：换掉最后一个字符
        char last = url.charAt(url.length() - 1);
        String tampered = url.substring(0, url.length() - 1) + (last == '0' ? '1' : '0');
        assertThat(getBody("http://localhost:" + port + tampered)).contains("\"code\":20016");

        // 换 fileId 复用签名
        assertThat(getBody("http://localhost:" + port
                + url.replace("fileId=" + uploaded.id(), "fileId=" + (uploaded.id() + 1))))
                .contains("\"code\":20016");

        // 过期：用正确的 exp 重新签一个过去的时刻（服务端顺序是"先比签名、再判时效"）
        assertThat(getBody("http://localhost:" + port + expiredUrl(uploaded.id(), 300)))
                .as("签名对但已过期 → 20016").contains("\"code\":20016");

        assertThat(getBody("http://localhost:" + port + "/api/platform/file/Download?fileId=" + uploaded.id()))
                .as("没有 exp/sig 的裸链接 → 20016").contains("\"code\":20016");
    }

    @Test
    @DisplayName("可见性 fail-closed：上传者本人可下载；其他人（异组织、无 platform:file:download）→ 20017 且被留痕")
    void visibilityAllowsUploaderOnly() {
        byte[] content = "private".getBytes(StandardCharsets.UTF_8);
        FileDTO uploaded = upload(content, "private.txt", "text/plain");
        assertThat(uploaded.uploaderId()).as("上传者来自当前组织上下文").isEqualTo(IT_USER_ID);
        assertThat(downloadBody(uploaded.id())).as("本人可下载（不是 20014/20017）")
                .doesNotContain("20014").doesNotContain("20017");
        assertThat(getBody("http://localhost:" + port + signedUrl(uploaded.id())))
                .as("本人：预签名链接同样可下载").isEqualTo(new String(content, StandardCharsets.UTF_8));

        long fallbackBefore = fallbackCount();
        // 换成"另一个组织的另一个用户"：既非上传者、组织也不同、也没有权限点（无认证环境 = 无 authorities）
        OrgContextStub.CURRENT.set(new OrgContextPort.OrgContext(IT_ORG_ID + 1, IT_USER_ID + 1));

        String denied = post("/api/platform/file/Download", "{\"fileId\":" + uploaded.id() + "}");
        assertThat(denied).as("不可见时必须是文件级 20017（携带文件上下文），不是通用 10403")
                .contains("\"code\":20017").doesNotContain("10403");
        assertThat(fallbackCount()).as("拒绝下载也要留痕（3.3.6 的安全事件）").isGreaterThan(fallbackBefore);
        assertThat(post("/api/platform/file/GetMeta", "{\"fileId\":" + uploaded.id() + "}"))
                .as("元数据接口同样收敛（5.2 给 GetMeta 列了 20017）").contains("\"code\":20017");
    }

    @Test
    @DisplayName("校验与幂等：白名单外 20012、列表接口不需要幂等键、写动作缺键 10001、Del 版本过期 10003")
    void validationAndIdempotencyRules() {
        // 白名单外扩展名（.exe 不在 7.2 的默认白名单里）
        String rejected = uploadExpectingBody("virus.exe", "application/octet-stream",
                "MZ".getBytes(StandardCharsets.UTF_8));
        assertThat(rejected).contains("\"code\":20012");

        FileDTO uploaded = upload("idem".getBytes(StandardCharsets.UTF_8), "idem.txt", "text/plain");

        assertThat(post("/api/platform/file/GetPage", "{\"pageNum\":1,\"pageSize\":10}"))
                .as("列表是读动作：不带幂等键也正常").contains("\"code\":0");
        assertThat(post("/api/platform/file/Del", "{\"fileId\":" + uploaded.id() + ",\"version\":0}",
                false))
                .as("Del 是写动作：缺 Idempotency-Key → 10001").contains("\"code\":10001");
        assertThat(post("/api/platform/file/Del",
                "{\"fileId\":" + uploaded.id() + ",\"version\":" + (uploaded.version() + 99) + "}"))
                .as("版本过期 → 10003").contains("\"code\":10003");
    }

    @Test
    @DisplayName("绑定与分页：重复绑定幂等；按 bizType+bizId 过滤只回绑定过的文件；列表不含物理路径")
    void bindAndFilterByBiz() {
        FileDTO first = upload("a".getBytes(StandardCharsets.UTF_8), "bound-a.txt", "text/plain");
        long bizId = 770000L + System.nanoTime() % 1000L;
        String bindBody = "{\"bizType\":\"it.file.case\",\"bizId\":" + bizId + ",\"fileIds\":["
                + first.id() + "]}";

        assertThat(post("/api/platform/file/Bind", bindBody)).contains("\"code\":0");
        assertThat(post("/api/platform/file/Bind", bindBody)).as("重复绑定不报错（5.4 的幂等约定）")
                .contains("\"code\":0");
        assertThat(jdbc().queryForObject("select count(*) from eaio_platform.file_binding"
                + " where file_id = ? and biz_type = 'it.file.case'", Integer.class, first.id()))
                .as("幂等的落点是唯一键，不是应用层去重").isEqualTo(1);

        String page = post("/api/platform/file/GetPage",
                "{\"bizType\":\"it.file.case\",\"bizId\":" + bizId + ",\"pageNum\":1,\"pageSize\":10}");
        assertThat(page).contains("\"code\":0").contains("bound-a.txt");
        assertThat(page).as("列表绝不外泄物理路径").doesNotContain("storage_path").doesNotContain("/01/");
    }

    // ---------------------------------------------------------------- 辅助

    /** 真实 multipart 上传（HTTP），返回已落库的 DTO。 */
    private FileDTO upload(byte[] content, String fileName, String contentType) {
        String body = uploadExpectingBody(fileName, contentType, content);
        assertThat(body).contains("\"code\":0");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) data(body);
        return fileApi.getMeta(((Number) data.get("id")).longValue());
    }

    @SuppressWarnings("unchecked")
    private String uploadExpectingBody(String fileName, String contentType, byte[] content) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        return client().post().uri("/api/platform/file/Upload")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve().body(String.class);
    }

    /** 带幂等键的写请求（默认）；读请求与"缺键"用例传 {@code false}。 */
    private String post(String path, String json) {
        return post(path, json, true);
    }

    private String post(String path, String json, boolean idempotent) {
        RestClient.RequestBodySpec spec = client().post().uri(path).contentType(MediaType.APPLICATION_JSON);
        if (idempotent) {
            spec = spec.header("Idempotency-Key", UUID.randomUUID().toString());
        }
        return spec.body(json).retrieve().body(String.class);
    }

    private String downloadBody(long fileId) {
        return post("/api/platform/file/Download", "{\"fileId\":" + fileId + "}");
    }

    /** 取一条预签名链接（GetUrl 的同源路径，形如 {@code /api/platform/file/Download?...}）。 */
    @SuppressWarnings("unchecked")
    private String signedUrl(long fileId) {
        String response = post("/api/platform/file/GetUrl", "{\"fileId\":" + fileId + ",\"expireSeconds\":600}");
        assertThat(response).contains("\"code\":0");
        return String.valueOf(((Map<String, Object>) data(response)).get("url"));
    }

    private byte[] getBytes(String url) {
        return client().get().uri(URI.create(url)).retrieve().body(byte[].class);
    }

    private String getBody(String url) {
        return client().get().uri(URI.create(url)).retrieve().body(String.class);
    }

    /** 造一条"签名正确但已过期"的链接：测试自己算 HMAC，才能构造出签名与 exp 自洽的过期 token。 */
    private String expiredUrl(long fileId, long secondsAgo) {
        long exp = Instant.now().getEpochSecond() - secondsAgo;
        return "/api/platform/file/Download?fileId=" + fileId + "&exp=" + exp + "&sig=" + hmac(fileId, exp);
    }

    /**
     * 与 {@code FilePresignTokenService} 同算法的 HMAC：用测试自己设定的密钥
     * （见 {@link #IT_PRESIGN_SECRET}），构造"签名正确但已过期"的 token。
     */
    private static String hmac(long fileId, long exp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(IT_PRESIGN_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((fileId + "|" + exp).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] sha(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(byte[] content) {
        return HexFormat.of().formatHex(sha(content));
    }

    /** 列表返回体里是否出现了物理路径（含 root 前缀与 storage_path 字段名）。 */
    private boolean readOutsideRootEvidence(String storagePath) {
        String page = post("/api/platform/file/GetPage", "{\"pageNum\":1,\"pageSize\":200}");
        return page.contains("storage_path") || page.contains("\"" + storagePath + "\"");
    }

    private long fallbackCount() {
        return fileService.auditFallbackCount();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(String response) {
        Map<String, Object> envelope = JsonUtils.fromJson(response, Map.class);
        return (Map<String, Object>) envelope.get("data");
    }
}
