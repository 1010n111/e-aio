package com.eaio.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import com.eaio.app.config.MigrationRunner;
import com.eaio.app.config.ModuleInstances;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/**
 * 集成测试（P0 册 3.8 阶段 5；AC："集成测试在 CI 上真实执行（非跳过）"）。
 *
 * <p>与单元测试的分工：这里验证**真实中间件**参与的那部分——迁移在真实 PostgreSQL 上跑、种子脚本可重复
 * 执行、幂等占位落在真实 Redis 上。这些在单测里无法证明（无 Docker 时本类整体跳过，权威验证在 CI）。
 *
 * <p>请求路径带 {@code /api}（servlet context-path，属契约）：基础配置里它是 {@code /api}，测试也不覆盖
 * ——覆盖不掉且不该覆盖（见 application-it.yml 的实测说明）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformMigrationIT extends IntegrationTestBase {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MigrationRunner migrationRunner;

    @Autowired
    private ModuleInstances moduleInstances;

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("迁移在应用启动时已执行：平台模块执行了脚本、目标版本为 2（V1 基线 + V2 参数中心）")
    void migrationRanAtStartup() {
        assertThat(moduleInstances.modules()).hasSize(1);
        assertThat(moduleInstances.modules().get(0).schema()).isEqualTo("eaio_platform");

        assertThat(migrationRunner.outcomes()).hasSize(1);
        MigrationRunner.Outcome platform = migrationRunner.outcomes().get(0);
        assertThat(platform.module()).isEqualTo("platform");
        assertThat(platform.schema()).isEqualTo("eaio_platform");
        assertThat(platform.migrationsExecuted()).as("空库首次启动至少执行 V1/V2 两个版本化脚本")
                .isGreaterThanOrEqualTo(2);
        // 只断言语义：目标版本是 2（Flyway 12 的字符串形态可能是 "2" 或 "2.0"）
        assertThat(platform.targetVersion()).as("目标版本应表示 2").matches("2(\\.0+)?");
    }

    @Test
    @DisplayName("迁移落库：平台 Schema 存在、历史表落位、V1 与 V2 都成功、种子（可重复迁移）已执行")
    void migrationCreatesSchemaAndHistory() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "select installed_rank, version, description, type, script, success"
                        + " from eaio_platform.flyway_schema_history order by installed_rank");

        // 历史表里有两类记录：建 Schema（type=SCHEMA，rank 0）与脚本（type=SQL）
        List<Map<String, Object>> scripts = rows.stream()
                .filter(row -> "SQL".equalsIgnoreCase(String.valueOf(row.get("type"))))
                .toList();

        assertThat(scripts).as("P1 T3 的脚本清单：V1 基线 + V2 参数中心 + 种子")
                .extracting(row -> String.valueOf(row.get("script")))
                .contains("V1__baseline.sql", "V2__param_center.sql", "R__platform_seed.sql");
        assertThat(scripts).allSatisfy(row -> assertThat(row.get("success")).isEqualTo(true));

        Map<String, Object> v2 = scripts.stream()
                .filter(row -> "V2__param_center.sql".equals(row.get("script")))
                .findFirst()
                .orElseThrow();
        assertThat(String.valueOf(v2.get("version"))).as("V2 的版本号表示 2").matches("2(\\.0+)?");

        assertThat(rows).as("建 Schema 也会留痕（createSchemas=true 的直接证据）")
                .anyMatch(row -> "SCHEMA".equalsIgnoreCase(String.valueOf(row.get("type"))));
    }

    @Test
    @DisplayName("会话时区为 UTC：TIMESTAMPTZ 的读写解释不随容器/主机时区漂移（P1 册 7.5 L10）")
    void sessionTimezoneIsUtc() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertThat(jdbc.queryForObject("select current_setting('timezone')", String.class))
                .as("会话时区必须是 UTC。实测口径：JDBC URL 的 options/TimeZone 参数被 pgjdbc 静默忽略，"
                        + "有效做法是 Hikari connection-init-sql（application.yml）")
                .isEqualTo("UTC");
    }

    @Test
    @DisplayName("平台 Schema 只含参数中心两张表 + Flyway 历史表（V1 不含业务表，后续票的表尚未落地）")
    void schemaContainsOnlyParamTables() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'eaio_platform'",
                String.class);

        assertThat(tables).containsExactlyInAnyOrder("flyway_schema_history", "param", "param_change_log");
    }

    @Test
    @DisplayName("种子（R__）落库：10 条系统参数、级别 SYSTEM、内置不可删")
    void seedRowsAreLoaded() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Integer count = jdbc.queryForObject("select count(*) from eaio_platform.param", Integer.class);
        assertThat(count).isEqualTo(10);
        assertThat(jdbc.queryForObject(
                "select param_value from eaio_platform.param where param_key = 'platform.file.max-size'",
                String.class)).isEqualTo("52428800");
        assertThat(jdbc.queryForObject(
                "select param_value from eaio_platform.param where param_key = 'platform.time.display-zone'",
                String.class)).isEqualTo("Asia/Shanghai");
        assertThat(jdbc.queryForObject(
                "select count(*) from eaio_platform.param where param_level = 'SYSTEM' and owner_id = 0"
                        + " and builtin = true", Integer.class)).isEqualTo(10);
    }

    @Test
    @DisplayName("种子可重复执行：原样重放不报错，且不覆盖用户改过的值（ON CONFLICT DO NOTHING）")
    void seedIsRepeatableAndDoesNotOverwrite() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String key = "platform.file.max-size";
        String changed = "12345678";
        jdbc.update("update eaio_platform.param set param_value = ? where param_key = ?", changed, key);

        String sql = new String(new ClassPathResource("db/migration/platform/R__platform_seed.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        jdbc.execute(stripCommentsAndTrailingSemicolon(sql));

        assertThat(jdbc.queryForObject("select param_value from eaio_platform.param where param_key = ?",
                String.class, key))
                .as("种子不得覆盖用户改过的值（改默认值必须走新的 V<n> 脚本，P1 册 4.5）")
                .isEqualTo(changed);
        // 复原，避免影响同一容器内的其他用例
        jdbc.update("update eaio_platform.param set param_value = ? where param_key = ?", "52428800", key);
    }

    @Test
    @DisplayName("真实 HTTP + 真实 Redis + 真实库：参数新增成功，幂等键重放返回 10501 且不再写库")
    void idempotencyReplayOnRealRedis() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        RestClient client = RestClient.create("http://localhost:" + port);
        String idempotencyKey = UUID.randomUUID().toString();
        String paramKey = "it.param." + UUID.randomUUID().toString().substring(0, 8);
        String body = "{\"paramKey\":\"" + paramKey + "\",\"paramLevel\":\"SYSTEM\",\"ownerId\":0,"
                + "\"paramValue\":\"it-value\",\"valueType\":\"STRING\",\"paramGroup\":\"it\"}";

        String first = post(client, idempotencyKey, body);
        String second = post(client, idempotencyKey, body);

        assertThat(first).contains("\"code\":0");
        assertThat(second).contains("\"code\":10501");
        // 重放不得执行业务：库里只有一行
        assertThat(jdbc.queryForObject("select count(*) from eaio_platform.param where param_key = ?",
                Integer.class, paramKey)).isEqualTo(1);
    }

    @Test
    @DisplayName("健康检查可达（/api 前缀属契约）")
    void actuatorHealthIsReachable() {
        RestClient client = RestClient.create("http://localhost:" + port);

        var response = client.get().uri("/api/actuator/health").retrieve().toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(response.getBody()).contains("UP");
    }

    private static String post(RestClient client, String idempotencyKey, String body) {
        return client.post()
                .uri("/api/platform/param/Add")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
    }

    /** 去掉 SQL 注释行与结尾分号：JdbcTemplate 按单条语句执行（迁移脚本本身由 Flyway 解析）。 */
    private static String stripCommentsAndTrailingSemicolon(String sql) {
        StringBuilder builder = new StringBuilder();
        for (String line : sql.split("\\R")) {
            if (!line.trim().startsWith("--")) {
                builder.append(line).append('\n');
            }
        }
        String stripped = builder.toString().trim();
        return stripped.endsWith(";") ? stripped.substring(0, stripped.length() - 1) : stripped;
    }
}
