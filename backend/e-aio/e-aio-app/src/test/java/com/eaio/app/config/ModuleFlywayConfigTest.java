package com.eaio.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.context.ActiveProfiles;

/**
 * 迁移装配的门槛行为（P0 册 3.6）。
 *
 * <p>这里不连真实数据库：验证"开关关闭时不装配""登记为空时启动失败""配置了库却没有数据源时启动失败"
 * "迁移在启动时确实被执行（连不上就启动失败，而不是静默跳过）"。
 * **迁移真的跑通**由集成测试 `PlatformMigrationIT` 在真实 PostgreSQL 上验证（本机无 Docker 时跳过，权威在 CI）。
 */
@ActiveProfiles("test")
class ModuleFlywayConfigTest {

    /**
     * 只提供一个**连不上**的数据源：足以让装配发生，且让"迁移必须执行"这件事以连接失败的形式暴露出来。
     */
    @Configuration(proxyBeanMethods = false)
    static class WithUnreachableDataSource {

        @Bean
        DataSource dataSource() {
            SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
            dataSource.setUrl("jdbc:postgresql://127.0.0.1:1/eaio-nope");
            dataSource.setUsername("nobody");
            dataSource.setPassword("nobody");
            return dataSource;
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of())
            .withUserConfiguration(ModuleFlywayConfig.class);

    @Test
    @DisplayName("未配置数据库：不装配迁移，应用按\"无库启动\"运行")
    void withoutDataSourceNoMigration() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean("moduleInstances").getClass().getSimpleName()).isEqualTo("NullBean");
            assertThat(context.getBean(MigrationRunner.class).outcomes()).isEmpty();
        });
    }

    @Test
    @DisplayName("开关关闭：即使有数据源也不装配迁移")
    void disabledRegistersNothing() {
        runner.withUserConfiguration(WithUnreachableDataSource.class)
                .withPropertyValues("eaio.flyway.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ModuleInstances.class);
                    assertThat(context).doesNotHaveBean(MigrationRunner.class);
                });
    }

    @Test
    @DisplayName("配置了数据库却没起数据源：启动失败（不静默跳过迁移）")
    void configuredButNoDataSourceFails() {
        runner.withPropertyValues("spring.datasource.url=jdbc:postgresql://localhost:5432/eaio")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("已配置 spring.datasource.url");
                });
    }

    @Test
    @DisplayName("模块登记为空：启动失败并给出可读原因")
    void emptyRegistryFailsLoudly() {
        runner.withUserConfiguration(WithUnreachableDataSource.class)
                .withPropertyValues("eaio.flyway.modules=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause()
                            .hasMessageContaining("eaio.flyway.modules");
                });
    }

    @Test
    @DisplayName("迁移在启动时真的执行：数据源连不上就启动失败（而不是带着旧结构起来）")
    void migrationRunsAtStartupAndFailsLoudly() {
        runner.withUserConfiguration(WithUnreachableDataSource.class)
                .withPropertyValues("eaio.flyway.modules=platform")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("moduleMigrationRunner");
                });
    }

    @Test
    @DisplayName("模块登记：脚本目录与默认 Schema 由模块名推导")
    void propertiesDeriveLocationAndSchema() {
        ModuleFlywayProperties properties = new ModuleFlywayProperties(true, List.of("platform", "iam"));

        assertThat(properties.locationOf("platform")).isEqualTo("classpath:db/migration/platform");
        assertThat(properties.schemaOf("platform")).isEqualTo("eaio_platform");
        assertThat(properties.schemaOf("iam")).isEqualTo("eaio_iam");
        assertThat(properties.modules()).containsExactly("platform", "iam");
        assertThat(properties.enabled()).isTrue();
    }

    @Test
    @DisplayName("模块登记不可变：外部 List 的后续改动不影响配置")
    void propertiesAreImmutable() {
        List<String> mutable = new java.util.ArrayList<>(List.of("platform"));
        ModuleFlywayProperties properties = new ModuleFlywayProperties(true, mutable);
        mutable.add("iam");

        assertThat(properties.modules()).containsExactly("platform");
    }

    @Test
    @DisplayName("模块登记为空：视为空列表而不是 null（由装配处报出可读错误）")
    void propertiesTolerateNullModules() {
        assertThat(new ModuleFlywayProperties(true, null).modules()).isEmpty();
    }
}
