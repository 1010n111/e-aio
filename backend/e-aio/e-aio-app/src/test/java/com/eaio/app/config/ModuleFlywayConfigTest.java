package com.eaio.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * 迁移装配的门槛行为（P0 册 3.6）。
 *
 * <p>这里不连数据库：只验证"没有数据源就不注册迁移 Bean""登记为空要在启动时报出可读错误"。
 * 真实迁移（建 Schema、历史表落位、版本号=1）由 Testcontainers PostgreSQL 集成测试承担。
 */
class ModuleFlywayConfigTest {

    /** 只提供一个未连接的数据源：足以让装配发生，但不会真的连库（迁移在触发器里才会连）。 */
    @Configuration(proxyBeanMethods = false)
    static class WithDataSource {

        @Bean
        DataSource dataSource() {
            return new SimpleDriverDataSource();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of())
            .withUserConfiguration(ModuleFlywayConfig.class);

    @Test
    @DisplayName("未配置数据库：不产生迁移实例与触发器，应用按\"无库启动\"运行")
    void withoutDataSourceNoMigrationBeans() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            // 显式判定为"无库"时 @Bean 返回 null，resolver 给出 Spring 的 NullBean
            assertThat(context.getBean("moduleInstances").getClass().getSimpleName()).isEqualTo("NullBean");
            assertThat(context.getBean("moduleFlywayInitializers", List.class)).isEmpty();
            assertThat(context).doesNotHaveBean(FlywayMigrationInitializer.class);
        });
    }

    @Test
    @DisplayName("配置了数据库却没起数据源：启动失败（不静默跳过迁移）")
    void configuredButNoDataSourceFails() {
        runner.withPropertyValues("spring.datasource.url=jdbc:postgresql://localhost:5432/eaio")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("开关关闭：即使有数据源也不注册迁移 Bean")
    void disabledRegistersNoMigrationBeans() {
        runner.withUserConfiguration(WithDataSource.class)
                .withPropertyValues("eaio.flyway.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ModuleInstances.class);
                    assertThat(context).doesNotHaveBean(Flyway.class);
                });
    }
    @Test
    @DisplayName("有数据源但模块登记为空：启动即失败，并给出可读原因")
    void emptyRegistryFailsLoudly() {
        runner.withUserConfiguration(WithDataSource.class)
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
    @DisplayName("有数据源且登记了模块：按登记逐模块建实例与触发器")
    void registersOneInstanceAndInitializerPerModule() {
        runner.withUserConfiguration(WithDataSource.class)
                .withPropertyValues("eaio.flyway.modules=platform")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ModuleInstances.class);
                    ModuleInstances instances = context.getBean(ModuleInstances.class);
                    assertThat(instances.modules()).hasSize(1);
                    assertThat(instances.modules().get(0).name()).isEqualTo("platform");
                    assertThat(instances.modules().get(0).schema()).isEqualTo("eaio_platform");
                    assertThat(context).hasBean("moduleFlywayInitializers");
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
