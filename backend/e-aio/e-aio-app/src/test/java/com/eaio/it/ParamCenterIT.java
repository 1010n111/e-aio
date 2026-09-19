package com.eaio.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;

import javax.sql.DataSource;

import com.eaio.common.exception.BusinessException;
import com.eaio.common.redis.RedisKeys;
import com.eaio.platform.api.ParamApi;
import com.eaio.platform.api.dto.ParamSaveCmd;
import com.eaio.platform.api.port.OrgContextPort;
import com.eaio.platform.infrastructure.cache.ParamInvalidationPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/**
 * 参数中心集成测试（P1 册 6.2 的 {@code ParamCenterIT}）：真实 PostgreSQL + 真实 Redis 上验证
 * 三级覆盖、两级缓存、SECRET 加密与留痕、未定义键语义，以及管理页读取端点。
 *
 * <p><b>为什么需要一个 {@code OrgContextPort} 替身</b>：iam 尚未交付，platform 的端口由应用壳注入
 * iam 适配器（ADR-0005）。集成测试扮演这个适配器，才能验证"用户级覆盖组织级、组织级覆盖系统级"——
 * 这是本票的第一条验收项，用 mock 掉 resolver 的方式验证不了它。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ParamCenterIT extends IntegrationTestBase {

    private static final String SEEDED_KEY = "platform.file.max-size";
    private static final long SEEDED_SYSTEM_VALUE = 52428800L;

    @TestConfiguration
    static class OrgContextStub {

        /** 当前"请求"的上下文；测试里直接改这个引用模拟不同登录用户/组织。 */
        static final AtomicReference<OrgContextPort.OrgContext> CURRENT = new AtomicReference<>();

        @Bean
        OrgContextPort orgContextPort() {
            return () -> Optional.ofNullable(CURRENT.get());
        }
    }

    @Autowired
    private ParamApi paramApi;

    @Autowired
    private DataSource dataSource;

    /** 广播发布方：用它模拟"另一个实例改完值后发的失效消息"。 */
    @Autowired
    private ParamInvalidationPublisher publisher;

    /** L2 层与广播通道都在 Redis 上：校验"L2 真的接了"与"广播真的发了"必须直接看 Redis。 */
    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private Environment environment;

    @LocalServerPort
    private int port;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    @Test
    @DisplayName("三级覆盖：用户级覆盖组织级、组织级覆盖系统级（种子里的 SYSTEM 值兜底）")
    void threeLevelOverride() {
        long orgId = 9101L;
        long userId = 9201L;
        paramApiSwitchTo(orgId, userId);
        paramApi.set(new ParamSaveCmd(SEEDED_KEY, "ORG", orgId, "33333333", "INT", "file", null, null));
        paramApi.set(new ParamSaveCmd(SEEDED_KEY, "USER", userId, "22222222", "INT", "file", null, null));

        assertThat(paramApi.getInt(SEEDED_KEY, -1)).as("用户级生效").isEqualTo(22222222);
        assertThat(paramApi.get(SEEDED_KEY).paramLevel()).isEqualTo("USER");

        paramApiSwitchTo(orgId, 0L);
        assertThat(paramApi.getInt(SEEDED_KEY, -1)).as("无用户时组织级生效").isEqualTo(33333333);

        paramApiSwitchTo(9999L, 0L);
        assertThat(paramApi.getInt(SEEDED_KEY, -1)).as("其他组织落到系统级").isEqualTo((int) SEEDED_SYSTEM_VALUE);
    }

    @Test
    @DisplayName("取值链：缓存命中不回源（直连改库读到旧值），refresh 后取到新值")
    void cacheHitThenRefresh() {
        String key = "platform.file.chunk-size";
        paramApiSwitchTo(9301L, 0L);

        assertThat(paramApi.getInt(key, -1)).as("首次回源").isEqualTo(5242880);
        jdbc().update("update eaio_platform.param set param_value = '7777777' where param_key = ?"
                + " and param_level = 'SYSTEM'", key);
        assertThat(paramApi.getInt(key, -1)).as("L1 命中：直连改库后仍读缓存值").isEqualTo(5242880);

        paramApi.refresh(key);
        assertThat(paramApi.getInt(key, -1)).as("清缓存后取到新值").isEqualTo(7777777);

        jdbc().update("update eaio_platform.param set param_value = '5242880' where param_key = ?"
                + " and param_level = 'SYSTEM'", key);
        paramApi.refresh(key);
    }

    @Test
    @DisplayName("未定义键：get* 返回调用方默认值（来源 DEFAULT），get 抛 20001；不写空值占位")
    void undefinedKey() {
        String missing = "it.param.never-defined";
        paramApiSwitchTo(9501L, 0L);

        assertThat(paramApi.getInt(missing, 7)).isEqualTo(7);
        assertThat(paramApi.getInt(missing, 7)).as("第二次仍回源（未定义键不缓存）").isEqualTo(7);
        assertThat(paramApi.getString(missing, null)).isNull();
        assertThatThrownBy(() -> paramApi.get(missing))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(20001));
    }

    @Test
    @DisplayName("SECRET 参数：密文落库、接口回 ******、内部取到明文、变更历史记 ******")
    void secretParam() {
        String key = "it.param.secret";
        paramApiSwitchTo(9401L, 9402L);
        paramApi.set(new ParamSaveCmd(key, "ORG", 9401L, "plain-secret-value", "SECRET", "it", null, null));

        String stored = jdbc().queryForObject("select param_value from eaio_platform.param"
                + " where param_key = ? and param_level = 'ORG'", String.class, key);
        assertThat(stored).as("落库必须是密文").startsWith("enc:v1:").doesNotContain("plain-secret-value");
        assertThat(paramApi.get(key).paramValue()).as("DTO 恒为占位符").isEqualTo("******");
        assertThat(paramApi.getString(key, null)).as("内部调用方拿到明文").isEqualTo("plain-secret-value");

        paramApi.set(new ParamSaveCmd(key, "ORG", 9401L, "second-secret", "SECRET", "it", null, 0));
        List<Map<String, Object>> logs = jdbc().queryForList("select old_value, new_value from"
                + " eaio_platform.param_change_log where param_key = ? order by id", key);
        assertThat(logs).as("新增 + 改值两条留痕").hasSize(2);
        assertThat(logs).allSatisfy(row -> assertThat(String.valueOf(row.get("new_value"))).isEqualTo("******"));
    }

    @Test
    @DisplayName("留空表示不变更：SECRET 行收到空 paramValue 时保留原密文（P1-2 册 331 行）")
    void blankSecretValueKeepsCiphertext() {
        String key = "it.param.secret.keep";
        paramApiSwitchTo(9411L, 9412L);
        paramApi.set(new ParamSaveCmd(key, "ORG", 9411L, "keep-me", "SECRET", "it", null, null));
        String before = jdbc().queryForObject("select param_value from eaio_platform.param"
                + " where param_key = ? and param_level = 'ORG'", String.class, key);
        assertThat(before).startsWith("enc:v1:");

        paramApi.set(new ParamSaveCmd(key, "ORG", 9411L, "", "SECRET", "it", null, 0));

        assertThat(jdbc().queryForObject("select param_value from eaio_platform.param"
                + " where param_key = ? and param_level = 'ORG'", String.class, key))
                .as("空值不得清空密文").isEqualTo(before);
        assertThat(paramApi.getString(key, null)).as("明文仍可读出").isEqualTo("keep-me");
    }

    @Test
    @DisplayName("管理页读取端点（真实 HTTP）：GetPage 给出生效值与三级候选，读动作不需要幂等键")
    void pageEndpointOverHttp() {
        RestClient client = RestClient.create("http://localhost:" + port);

        String body = client.post()
                .uri("/api/platform/param/GetPage")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"paramKey\":\"platform.file.max-size\",\"pageNum\":1,\"pageSize\":20}")
                .retrieve()
                .body(String.class);

        assertThat(body).contains("\"code\":0");
        assertThat(body).contains("effectiveValue").contains("\"source\":\"DB\"").contains("candidates");
        assertThat(body).contains("platform.file.max-size");
    }

    @Test
    @DisplayName("写动作缺幂等键即 10001（P1 册 5.6 收紧）；带键成功")
    void writeWithoutIdempotencyKeyIsRejected() {
        RestClient client = RestClient.create("http://localhost:" + port);
        String payload = "{\"paramKey\":\"it.param." + System.nanoTime()
                + "\",\"paramLevel\":\"SYSTEM\",\"ownerId\":0,\"paramValue\":\"v\",\"valueType\":\"STRING\"}";

        String withoutKey = client.post().uri("/api/platform/param/Add")
                .contentType(MediaType.APPLICATION_JSON).body(payload).retrieve().body(String.class);
        assertThat(withoutKey).contains("\"code\":10001");

        String withKey = client.post().uri("/api/platform/param/Add")
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).body(payload).retrieve().body(String.class);
        assertThat(withKey).contains("\"code\":0");
    }

    @Test
    @DisplayName("乐观锁：带过期 version 的改值被拒（复用通用数据冲突码 10003，不新造平台码）")
    void staleVersionIsRejected() {
        String key = "it.param.lock";
        paramApiSwitchTo(9601L, 9602L);
        paramApi.set(new ParamSaveCmd(key, "ORG", 9601L, "v1", "STRING", "it", null, null));
        paramApi.set(new ParamSaveCmd(key, "ORG", 9601L, "v2", "STRING", "it", null, 0));

        assertThatThrownBy(() -> paramApi.set(new ParamSaveCmd(key, "ORG", 9601L, "v3", "STRING", "it", null, 0)))
                .as("version 已从 0 推到 1，再拿 0 来写必须被乐观锁拒绝而不是覆盖")
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(10003));
        assertThat(paramApi.getString(key, null)).isEqualTo("v2");
    }

    @Test
    @DisplayName("留痕完整：HTTP 改值后历史行含旧值/新值/操作人/链路 ID（traceId 由入站链路回填）")
    void changeLogRecordsOperatorAndTraceId() {
        String key = "it.param.trace";
        long orgId = 9701L;
        long userId = 9702L;
        paramApiSwitchTo(orgId, userId);
        paramApi.set(new ParamSaveCmd(key, "ORG", orgId, "old", "STRING", "it", null, null));

        RestClient client = RestClient.create("http://localhost:" + port);
        String response = client.post()
                .uri("/api/platform/param/Up")
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"paramKey\":\"" + key + "\",\"paramLevel\":\"ORG\",\"ownerId\":" + orgId
                        + ",\"paramValue\":\"new\",\"valueType\":\"STRING\",\"paramGroup\":\"it\",\"version\":0}")
                .retrieve()
                .body(String.class);
        assertThat(response).contains("\"code\":0");

        Map<String, Object> last = jdbc().queryForMap(
                "select old_value, new_value, operator_id, trace_id from eaio_platform.param_change_log"
                        + " where param_key = ? order by id desc limit 1", key);
        assertThat(last)
                .containsEntry("old_value", "old")
                .containsEntry("new_value", "new");
        assertThat(String.valueOf(last.get("operator_id"))).isEqualTo(String.valueOf(userId));
        assertThat(last.get("trace_id")).as("链路 ID 由 TraceIdFilter 写入 MDC，留痕必须带上")
                .isNotNull();
    }

    @Test
    @DisplayName("写入范围：模块经 ParamApi 写 SYSTEM 级被拒（20006）；内置参数经管理端删除被拒（20005）")
    void moduleScopeAndBuiltinProtection() {
        paramApiSwitchTo(9801L, 9802L);

        assertThatThrownBy(() -> paramApi.set(new ParamSaveCmd("platform.file.max-size", "SYSTEM", 0L, "1",
                "INT", "file", null, null)))
                .as("跨模块入口不得改平台配置（P1 册 5.4）")
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(20006));

        Long builtinId = jdbc().queryForObject("select id from eaio_platform.param where param_key = ?",
                Long.class, SEEDED_KEY);
        Integer version = jdbc().queryForObject("select version from eaio_platform.param where id = ?",
                Integer.class, builtinId);
        RestClient client = RestClient.create("http://localhost:" + port);
        String response = client.post()
                .uri("/api/platform/param/Del")
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"id\":" + builtinId + ",\"version\":" + version + "}")
                .retrieve()
                .body(String.class);

        assertThat(response).as("平台内置参数删不得（20005）").contains("\"code\":20005");
    }

    @Test
    @DisplayName("跨实例失效广播：另一实例发布失效消息后，本实例立刻读到新值（不等 L1 的 60s TTL）")
    void crossInstanceInvalidation() throws InterruptedException {
        String key = "platform.file.session-ttl-hours";
        paramApiSwitchTo(9901L, 0L);
        assertThat(paramApi.getInt(key, -1)).as("预热 L1").isEqualTo(24);

        jdbc().update("update eaio_platform.param set param_value = '8888888' where param_key = ?"
                + " and param_level = 'SYSTEM'", key);
        assertThat(paramApi.getInt(key, -1)).as("未广播前 L1 命中：仍读缓存值").isEqualTo(24);

        publisher.publish(key);
        awaitInt(() -> paramApi.getInt(key, -1), 8888888, 5000L);

        jdbc().update("update eaio_platform.param set param_value = '24' where param_key = ?"
                + " and param_level = 'SYSTEM'", key);
        paramApi.refresh(key);
    }

    @Test
    @DisplayName("改值后另一上下文与另一缓存层读到新值：本机 L1 全变体清空 + L2 前缀删除一起生效")
    void changeIsVisibleAcrossContextsAndLayers() {
        String key = "platform.excel.error-max";
        Long keyId = jdbc().queryForObject("select id from eaio_platform.param where param_key = ?"
                + " and param_level = 'SYSTEM'", Long.class, key);
        Integer version = jdbc().queryForObject("select version from eaio_platform.param where id = ?",
                Integer.class, keyId);

        paramApiSwitchTo(9211L, 0L);
        assertThat(paramApi.getInt(key, -1)).as("组织 A 的 L1 预热").isEqualTo(1000);
        paramApiSwitchTo(9212L, 0L);
        assertThat(paramApi.getInt(key, -1)).as("组织 B 的 L1 预热").isEqualTo(1000);

        RestClient client = RestClient.create("http://localhost:" + port);
        String response = client.post()
                .uri("/api/platform/param/Up")
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"paramKey\":\"" + key + "\",\"paramLevel\":\"SYSTEM\",\"ownerId\":0,"
                        + "\"paramValue\":\"2000\",\"valueType\":\"INT\",\"paramGroup\":\"excel\",\"version\":"
                        + version + "}")
                .retrieve()
                .body(String.class);
        assertThat(response).contains("\"code\":0");

        assertThat(paramApi.getInt(key, -1)).as("发起方上下文：AFTER_COMMIT 已清 L1").isEqualTo(2000);
        paramApiSwitchTo(9211L, 0L);
        assertThat(paramApi.getInt(key, -1)).as("另一上下文：L2 前缀删除后回源读到新值").isEqualTo(2000);

        jdbc().update("update eaio_platform.param set param_value = '1000', version = version + 1"
                + " where id = ?", keyId);
        paramApi.refresh(key);
    }

    @Test
    @DisplayName("留空 = 不变更只在类型不变时成立：STRING 行改成 SECRET 且留空必须 20002，不得留明文")
    void blankValueWithTypeChangeIsRejected() {
        String key = "it.param.typechange.blank";
        paramApiSwitchTo(9701L, 9702L);
        var created = paramApi.set(new ParamSaveCmd(key, "ORG", 9701L, "plain-text", "STRING", "it", null, null));

        assertThatThrownBy(() -> paramApi.set(new ParamSaveCmd(key, "ORG", 9701L, "", "SECRET", "it", null,
                created.version())))
                .as("跨类型留空无法表达新值，必须显式拒绝而不是静默保留旧值（否则明文会被标成 encrypted）")
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(20002));

        Map<String, Object> row = jdbc().queryForMap("select param_value, encrypted, value_type"
                + " from eaio_platform.param where param_key = ? and param_level = 'ORG'", key);
        assertThat(row.get("param_value")).isEqualTo("plain-text");
        assertThat(row.get("encrypted")).isEqualTo(false);
        assertThat(row.get("value_type")).isEqualTo("STRING");
    }

    @Test
    @DisplayName("L2 真的接了：回源后 Redis 有该键，改值后键消失（不是「写了代码但从不命中」）")
    void l2CacheIsWiredAndEvicted() {
        String key = "it.param.l2.wired";
        paramApiSwitchTo(9501L, 0L);
        var created = paramApi.set(new ParamSaveCmd(key, "ORG", 9501L, "41", "INT", "it", null, null));
        String redisKey = l2Key(9501L, key);
        redisTemplate.delete(redisKey);

        assertThat(paramApi.getInt(key, -1)).isEqualTo(41);
        assertThat(redisTemplate.hasKey(redisKey)).as("回源后必须写 L2（否则 L2 等于没接）").isTrue();

        paramApi.set(new ParamSaveCmd(key, "ORG", 9501L, "42", "INT", "it", null, created.version()));

        assertThat(redisTemplate.hasKey(redisKey)).as("改值后 L2 键必须消失").isFalse();
        assertThat(paramApi.getInt(key, -1)).isEqualTo(42);
    }

    @Test
    @DisplayName("改值后监听器真的发广播：订阅同通道能收到 {env,region:param,key}")
    void changePublishesInvalidationBroadcast() throws Exception {
        String key = "it.param.broadcast.wired";
        paramApiSwitchTo(9601L, 9602L);
        AtomicReference<String> received = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        RedisMessageListenerContainer probe = new RedisMessageListenerContainer();
        probe.setConnectionFactory(Objects.requireNonNull(redisTemplate.getConnectionFactory()));
        probe.addMessageListener((message, pattern) -> {
            received.set(new String(message.getBody(), StandardCharsets.UTF_8));
            latch.countDown();
        }, new ChannelTopic(publisher.channel()));
        probe.afterPropertiesSet();
        probe.start();
        try {
            paramApi.set(new ParamSaveCmd(key, "ORG", 9601L, "7", "INT", "it", null, null));

            assertThat(latch.await(5, TimeUnit.SECONDS)).as("改值提交后必须发出跨实例失效广播").isTrue();
            assertThat(received.get()).contains(key).contains("\"region\":\"param\"");
        } finally {
            probe.stop();
            probe.destroy();
        }
    }

    /** L2 键（与 {@code ParamL2Cache} 同一构造点）：{@code eaio:{env}:platform:param:{orgId}:{key}}。 */
    private String l2Key(long orgId, String key) {
        String env = RedisKeys.envOf(environment.getProperty("eaio.env"), environment.getActiveProfiles());
        return RedisKeys.of(env, "platform", "param", Long.toString(orgId), key);
    }

    /** 轮询等待（不引 Awaitility：只为一个用例加一个测试依赖不划算）。 */
    private static void awaitInt(IntSupplier read, int expected, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        int actual = Integer.MIN_VALUE;
        while (System.currentTimeMillis() < deadline) {
            actual = read.getAsInt();
            if (actual == expected) {
                return;
            }
            Thread.sleep(50L);
        }
        assertThat(actual).as("广播在 %dms 内未生效", timeoutMillis).isEqualTo(expected);
    }

    private static void paramApiSwitchTo(long orgId, long userId) {
        OrgContextStub.CURRENT.set(new OrgContextPort.OrgContext(orgId, userId));
    }
}
