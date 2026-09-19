package com.eaio.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 迁移装配的门槛行为（P0 册 3.6）。
 *
 * <p>这里不连数据库：只验证"开关关闭时不注册迁移 Bean""开启时迁移确实需要数据源"。
 * 真实迁移（建 Schema、历史表落位、版本号=1）由 Testcontainers PostgreSQL 集成测试承担。
 */
class ModuleFlywayConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of())
            .withUserConfiguration(ModuleFlywayConfig.class);

    @Test
    @DisplayName("开关关闭：不注册任何迁移 Bean，缺失数据源也不报错（本地无数据库可启动）")
    void disabledRegistersNoMigrationBeans() {
        runner.withPropertyValues("eaio.flyway.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(FlywayMigrationInitializer.class);
                    assertThat(context).doesNotHaveBean(ModuleInstances.class);
                    assertThat(context).doesNotHaveBean(Flyway.class);
                });
    }

    @Test
    @DisplayName("开关开启：迁移必须拿到数据源——没有数据源就启动失败，不静默跳过")
    void enabledRequiresDataSource() {
        runner.withPropertyValues("eaio.flyway.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("默认开启：未声明 enabled 时同样要求数据源（生产不得静默跳过）")
    void enabledByDefault() {
        runner.run(context -> assertThat(context).hasFailed());
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
