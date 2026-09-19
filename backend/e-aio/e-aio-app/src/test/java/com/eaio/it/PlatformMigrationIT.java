package com.eaio.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import com.eaio.app.config.MigrationRunner;
import com.eaio.app.config.ModuleInstances;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
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
 *
 * <p><b>库与 Redis 是所有集成测试类共享的</b>（{@link IntegrationTestBase} 的容器是 JVM 内单例）：
 * 种子相关断言必须按 {@code param_level = 'SYSTEM' and owner_id = 0} 限定，不能按"表内只有种子行"写
 * ——其他测试类会往同一张表里插自己的 ORG/USER 级行。
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
    @DisplayName("迁移在应用启动时已执行：平台模块执行了脚本，目标版本 = 迁移目录里的最高版本")
    void migrationRanAtStartup() throws Exception {
        assertThat(moduleInstances.modules()).hasSize(1);
        assertThat(moduleInstances.modules().get(0).schema()).isEqualTo("eaio_platform");

        assertThat(migrationRunner.outcomes()).hasSize(1);
        MigrationRunner.Outcome platform = migrationRunner.outcomes().get(0);
        assertThat(platform.module()).isEqualTo("platform");
        assertThat(platform.schema()).isEqualTo("eaio_platform");
        // 共享库（IntegrationTestBase 的容器是 JVM 内单例）下**不能**断言"本上下文执行了 N 条"：
        // 先跑的测试类可能已经让迁移落库（本上下文于是是 0 条，与产品行为无关）。要守的性质是
        // "启动后没有待执行的脚本"，它等价于"版本化脚本（V*.sql）都已落库且成功"——期望值按迁移
        // 目录推导，新增一票迁移时这里不必手改（手改反而会漏）。
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer recorded = jdbc.queryForObject("select count(*) from eaio_platform.flyway_schema_history"
                + " where type = 'SQL' and version is not null and success", Integer.class);
        assertThat(recorded).as("启动后 V*.sql 都已执行落库且成功（pending = 0；条数按迁移目录推导）")
                .isEqualTo((int) versionedScriptCount());
        // 不能断言"本次运行的目标版本"：共享库下别的测试类已经迁移过，Flyway 无可迁移项时 target 为 null
        // （与产品行为无关）。要守的性质是"库内已应用的最高版本 = 迁移目录里的最高脚本号"——它同时
        // 覆盖"新脚本加了却没跑"（版本落后）与"跑了更早的脚本"（版本超前）两种坏情况。
        // 版本列是 VARCHAR，用 cast 比较避免 '9' > '10' 的字典序坑。
        Integer currentVersion = jdbc.queryForObject("select max(cast(version as integer))"
                + " from eaio_platform.flyway_schema_history where type = 'SQL' and success", Integer.class);
        assertThat(currentVersion).as("库内已应用的最高版本应等于迁移目录里的最高脚本号 %d", latestVersionedScript())
                .isEqualTo(latestVersionedScript());
    }

    @Test
    @DisplayName("迁移落库：平台 Schema 存在、历史表落位、脚本清单 = 迁移目录全部脚本、全部成功")
    void migrationCreatesSchemaAndHistory() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "select installed_rank, version, description, type, script, success"
                        + " from eaio_platform.flyway_schema_history order by installed_rank");

        // 历史表里有两类记录：建 Schema（type=SCHEMA，rank 0）与脚本（type=SQL）
        List<Map<String, Object>> scripts = rows.stream()
                .filter(row -> "SQL".equalsIgnoreCase(String.valueOf(row.get("type"))))
                .toList();

        // 期望值从迁移目录推导：新增一票迁移时不必手改清单（漏改会变成"新脚本没跑也照样绿"）
        assertThat(scripts).as("脚本清单应等于 db/migration/platform 下的全部脚本")
                .extracting(row -> String.valueOf(row.get("script")))
                .containsExactlyInAnyOrderElementsOf(migrationScriptNames());
        assertThat(scripts).allSatisfy(row -> assertThat(row.get("success")).isEqualTo(true));

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
    @DisplayName("平台 Schema 只含迁移脚本建出来的表 + Flyway 历史表（无越界建表）")
    void schemaContainsOnlyParamTables() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'eaio_platform'",
                String.class);

        // 期望值同样从迁移脚本推导（不写死清单）：既不会因为新增票的迁移而过期，也不依赖"哪张票先提交"
        assertThat(tables).as("表清单 = 迁移脚本里的 CREATE TABLE + flyway_schema_history")
                .containsExactlyInAnyOrderElementsOf(expectedTables());
    }

    /** 期望表集合：迁移脚本里的 {@code CREATE TABLE eaio_platform.<name>} + Flyway 历史表。 */
    private static List<String> expectedTables() throws IOException {
        Pattern createTable = Pattern.compile("CREATE TABLE\\s+eaio_platform\\.(\\w+)", Pattern.CASE_INSENSITIVE);
        Set<String> tables = new TreeSet<>();
        tables.add("flyway_schema_history");
        for (Resource resource : new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/platform/*.sql")) {
            String sql = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = createTable.matcher(sql);
            while (matcher.find()) {
                tables.add(matcher.group(1).toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(tables);
    }

    /** 迁移目录下的脚本文件名（`db/migration/platform/*.sql`）：脚本清单与目标版本的期望值都由它推导。 */
    private static List<String> migrationScriptNames() throws IOException {
        return Arrays.stream(new PathMatchingResourcePatternResolver()
                        .getResources("classpath*:db/migration/platform/*.sql"))
                .map(Resource::getFilename)
                .filter(Objects::nonNull)
                .sorted()
                .toList();
    }

    /**
     * 迁移目录里**版本化脚本（{@code V<n>__}）的条数**。
     *
     * <p>刻意与 {@link #latestVersionedScript()} 分开：**版本号 ≠ 条数**——编号允许跳号（本批次就有
     * V1/V2/V3/V5，V4 属后续的文件中心票），拿"最高版本号"当条数会得到一个永远偏大的期望值。
     */
    private static long versionedScriptCount() throws IOException {
        return migrationScriptNames().stream().filter(name -> name.startsWith("V")).count();
    }

    /** 迁移目录里的最高版本化脚本号（{@code V<n>__}），用于推导目标版本。 */
    private static int latestVersionedScript() throws IOException {
        Pattern pattern = Pattern.compile("^V(\\d+)__.*");
        return migrationScriptNames().stream()
                .map(pattern::matcher)
                .filter(Matcher::matches)
                .mapToInt(matcher -> Integer.parseInt(matcher.group(1)))
                .max()
                .orElseThrow(() -> new IllegalStateException("迁移目录下没有版本化脚本"));
    }

    @Test
    @DisplayName("种子（R__）落库：13 条系统参数（1–10 + T8 的 61–63）、级别 SYSTEM、内置不可删")
    void seedRowsAreLoaded() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // 计数按**种子 ID 区间**限定（1–10 是 R__platform_seed.sql 的固定字面量 ID）：容器与库是所有
        // 集成测试类共享的，其他类的运行会往 param 表里插自己的行，用"表内总行数"会误判成种子多灌了。
        Integer count = jdbc.queryForObject("select count(*) from eaio_platform.param where id between 1 and 10",
                Integer.class);
        assertThat(count).isEqualTo(10);
        assertThat(jdbc.queryForObject(
                "select param_value from eaio_platform.param where param_key = 'platform.file.max-size'"
                        + " and param_level = 'SYSTEM' and owner_id = 0",
                String.class)).isEqualTo("52428800");
        assertThat(jdbc.queryForObject(
                "select param_value from eaio_platform.param where param_key = 'platform.time.display-zone'"
                        + " and param_level = 'SYSTEM' and owner_id = 0",
                String.class)).isEqualTo("Asia/Shanghai");
        // T8 追加 3 个事件可靠性参数（ID 61–63，P1 册 7.2 的 platform.event.*）→ 内置 SYSTEM 参数 13 条
        assertThat(jdbc.queryForObject(
                "select count(*) from eaio_platform.param where param_level = 'SYSTEM' and owner_id = 0"
                        + " and builtin = true", Integer.class)).isEqualTo(13);
        assertThat(jdbc.queryForObject(
                "select param_value from eaio_platform.param where param_key = 'platform.event.retry-max'"
                        + " and param_level = 'SYSTEM' and owner_id = 0",
                String.class)).as("T8 的参数随种子落库，默认值与 7.2 一致").isEqualTo("5");
    }

    @Test
    @DisplayName("种子可重复执行：原样重放不报错，且不覆盖用户改过的值（ON CONFLICT DO NOTHING）")
    void seedIsRepeatableAndDoesNotOverwrite() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String key = "platform.file.max-size";
        String changed = "12345678";
        // 种子行是 SYSTEM 级、owner_id = 0：限定级别+归属，否则会连带改到其他集成测试类建的 ORG/USER 级同键行
        jdbc.update("update eaio_platform.param set param_value = ? where param_key = ?"
                + " and param_level = 'SYSTEM' and owner_id = 0", changed, key);

        String sql = new String(new ClassPathResource("db/migration/platform/R__platform_seed.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        jdbc.execute(stripCommentsAndTrailingSemicolon(sql));

        assertThat(jdbc.queryForObject("select param_value from eaio_platform.param where param_key = ?"
                + " and param_level = 'SYSTEM' and owner_id = 0",
                String.class, key))
                .as("种子不得覆盖用户改过的值（改默认值必须走新的 V<n> 脚本，P1 册 4.5）")
                .isEqualTo(changed);
        // 复原，避免影响同库的其他用例（容器与库是所有集成测试类共享的）
        jdbc.update("update eaio_platform.param set param_value = ? where param_key = ?"
                + " and param_level = 'SYSTEM' and owner_id = 0", "52428800", key);
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
