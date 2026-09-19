package com.eaio.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * 迁移基线的内容契约（P0 册 3.6）：V1 基线只允许 Schema 与注释，不得出现业务表。
 *
 * <p>本测试守住脚本本身；"脚本能在空库跑通"由 Testcontainers PostgreSQL 集成测试承担
 * （本机无 Docker 时跳过，权威验证在 CI）。
 */
class PlatformBaselineScriptTest {

    private static final String BASELINE = "db/migration/platform/V1__baseline.sql";

    @Test
    @DisplayName("平台基线脚本存在且可读")
    void baselineScriptIsPresent() throws IOException {
        ClassPathResource resource = new ClassPathResource(BASELINE);

        assertThat(resource.exists()).as(BASELINE).isTrue();
        assertThat(read()).isNotBlank();
    }

    @Test
    @DisplayName("建立平台自己的 Schema")
    void baselineCreatesPlatformSchema() throws IOException {
        assertThat(read()).contains("CREATE SCHEMA IF NOT EXISTS eaio_platform");
    }

    @Test
    @DisplayName("不含业务表：P0 不做业务建模")
    void baselineHasNoBusinessTables() throws IOException {
        assertThat(read()).doesNotContainIgnoringCase("CREATE TABLE");
    }

    @Test
    @DisplayName("不含数据操作：迁移脚本不当种子数据用")
    void baselineHasNoDataManipulation() throws IOException {
        String script = read();

        assertThat(script).doesNotContainIgnoringCase("INSERT INTO");
        assertThat(script).doesNotContainIgnoringCase("UPDATE ");
        assertThat(script).doesNotContainIgnoringCase("DELETE FROM");
    }

    @Test
    @DisplayName("不越界创建其他模块的 Schema")
    void baselineDoesNotCreateForeignSchemas() throws IOException {
        assertThat(read()).doesNotContain("eaio_iam").doesNotContain("eaio_audit");
    }

    @Test
    @DisplayName("每行都是注释或已声明的语句（避免半截脚本混进基线）")
    void everyLineIsCommentOrWhitelistedStatement() throws IOException {
        for (String line : read().lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            assertThat(trimmed)
                    .as("基线只允许 CREATE SCHEMA 与注释，实际出现：%s", trimmed)
                    .startsWith("CREATE SCHEMA IF NOT EXISTS eaio_platform")
                    .endsWith(";");
        }
    }

    private static String read() throws IOException {
        try (InputStream in = new ClassPathResource(BASELINE).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
