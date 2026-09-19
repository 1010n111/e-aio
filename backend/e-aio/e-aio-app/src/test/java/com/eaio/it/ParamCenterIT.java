package com.eaio.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import com.eaio.common.exception.BusinessException;
import com.eaio.platform.api.ParamApi;
import com.eaio.platform.api.dto.ParamSaveCmd;
import com.eaio.platform.api.port.OrgContextPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
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

    private static void paramApiSwitchTo(long orgId, long userId) {
        OrgContextStub.CURRENT.set(new OrgContextPort.OrgContext(orgId, userId));
    }
}
