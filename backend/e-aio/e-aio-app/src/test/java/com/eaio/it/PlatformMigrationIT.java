package com.eaio.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.eaio.app.config.MigrationRunner;
import com.eaio.app.config.ModuleInstances;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/**
 * 集成测试（P0 册 3.8 阶段 5；AC："集成测试在 CI 上真实执行（非跳过）"）。
 *
 * <p>与单元测试的分工：这里验证**真实中间件**参与的那部分——迁移在真实 PostgreSQL 上跑、
 * 幂等占位落在真实 Redis 上。这三件事在单测里无法证明（无 Docker 时本类整体跳过，权威验证在 CI）。
 *
 * <p>请求路径带 `/api`（servlet context-path，属契约）：基础配置里它是 `/api`，测试也不覆盖它
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
    @DisplayName("迁移在应用启动时已执行：平台模块执行了脚本、目标版本为 1")
    void migrationRanAtStartup() {
        assertThat(moduleInstances.modules()).hasSize(1);
        assertThat(moduleInstances.modules().get(0).schema()).isEqualTo("eaio_platform");

        assertThat(migrationRunner.outcomes()).hasSize(1);
        MigrationRunner.Outcome platform = migrationRunner.outcomes().get(0);
        assertThat(platform.module()).isEqualTo("platform");
        assertThat(platform.schema()).isEqualTo("eaio_platform");
        assertThat(platform.migrationsExecuted()).as("空库首次启动必须真的执行了基线脚本").isEqualTo(1);
        // 同样只断言语义：目标版本是 1（Flyway 12 的字符串形态可能是 "1" 或 "1.0"）
        assertThat(platform.targetVersion()).as("目标版本应表示 1").matches("1(\\.0+)?");
    }

    @Test
    @DisplayName("迁移落库：平台 Schema 存在、历史表落位、基线成功")
    void migrationCreatesSchemaAndHistory() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "select installed_rank, version, description, type, script, success"
                        + " from eaio_platform.flyway_schema_history order by installed_rank");

        // 历史表里有两类记录：建 Schema（type=SCHEMA，rank 0）与我们的基线脚本（type=SQL，rank 1）
        List<Map<String, Object>> scripts = rows.stream()
                .filter(row -> "SQL".equalsIgnoreCase(String.valueOf(row.get("type"))))
                .toList();

        assertThat(scripts).as("P0 只有一个版本化脚本（V1 基线）").hasSize(1);
        Map<String, Object> baseline = scripts.get(0);
        assertThat(baseline.get("script")).isEqualTo("V1__baseline.sql");
        assertThat(baseline.get("success")).isEqualTo(true);
        assertThat(String.valueOf(baseline.get("version"))).as("基线版本号表示 1").matches("1(\\.0+)?");

        assertThat(rows).as("建 Schema 也会留痕（createSchemas=true 的直接证据）")
                .anyMatch(row -> "SCHEMA".equalsIgnoreCase(String.valueOf(row.get("type"))));
    }

    @Test
    @DisplayName("V1 基线不含业务表（P0 不做业务建模）")
    void baselineHasNoBusinessTables() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'eaio_platform'",
                String.class);

        assertThat(tables).as("平台 Schema 里只应有 Flyway 历史表").containsExactly("flyway_schema_history");
    }

    @Test
    @DisplayName("真实 HTTP + 真实 Redis：幂等键重放返回 10501，业务不再执行")
    void idempotencyReplayOnRealRedis() {
        RestClient client = RestClient.create("http://localhost:" + port);
        String key = java.util.UUID.randomUUID().toString();

        String first = post(client, key);
        String second = post(client, key);

        assertThat(first).contains("\"code\":0");
        assertThat(second).contains("\"code\":10501");
        // 重放不得执行业务：业务数据里才有 id，错误返回体没有
        assertThat(second).doesNotContain("\"id\"");
    }

    @Test
    @DisplayName("健康检查可达（/api 前缀属契约）")
    void actuatorHealthIsReachable() {
        RestClient client = RestClient.create("http://localhost:" + port);

        var response = client.get().uri("/api/actuator/health").retrieve().toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(response.getBody()).contains("UP");
    }

    private static String post(RestClient client, String idempotencyKey) {
        return client.post()
                .uri("/api/platform/demo/Echo")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"message\":\"it\"}")
                .retrieve()
                .body(String.class);
    }
}
