package com.eaio.app.config;

import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 每模块独立 Flyway 实例的装配（P0 册 3.6）。
 *
 * <p>单一 Flyway 实例 + 多 Schema 会让全部模块共享一个版本号序列与一张历史表，破坏"模块可独立演进"
 * （HLD 13.1），因此每模块一个实例：各自的历史表落在自己的默认 Schema 内。
 *
 * <p>两个刻意的选择：
 * <ul>
 *   <li>排除默认的 {@code FlywayAutoConfiguration}（见 {@code EaioApplication}）：否则 Boot 还会按
 *       {@code spring.flyway.*} 再建一个"全局"实例，迁移跑两遍；</li>
 *   <li>复用 Boot 的 {@link FlywayMigrationInitializer} 作为迁移触发器，而不是自己写
 *       {@code InitializingBean}——初始化顺序与失败语义交给框架，配置类只负责组装。</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ModuleFlywayProperties.class)
public class ModuleFlywayConfig {

    private static final Logger log = LoggerFactory.getLogger(ModuleFlywayConfig.class);

    /**
     * 迁移总开关关闭时不注册任何迁移 Bean：本地无数据库时应用可干净启动（P0 验收）。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "eaio.flyway", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class Enabled {

        @Bean
        ModuleInstances moduleInstances(DataSource dataSource, ModuleFlywayProperties properties) {
            if (properties.modules().isEmpty()) {
                throw new IllegalStateException(
                        "eaio.flyway.modules 为空：至少登记一个模块（模块根包同名，见 P0 册附录 6.1），"
                                + "或显式设置 eaio.flyway.enabled=false 关闭迁移");
            }
            List<ModuleInstances.Module> modules = new ArrayList<>(properties.modules().size());
            for (String module : properties.modules()) {
                String schema = properties.schemaOf(module);
                Flyway flyway = Flyway.configure()
                        .dataSource(dataSource)
                        .locations(properties.locationOf(module))
                        // 12.x 的 FluentConfiguration 没有 schemas(..)；多 Schema 由 defaultSchema + createSchemas 覆盖
                        .defaultSchema(schema)
                        .createSchemas(true)
                        .failOnMissingLocations(true)
                        .load();
                modules.add(new ModuleInstances.Module(module, schema, flyway));
            }
            return new ModuleInstances(List.copyOf(modules));
        }

        /** 逐模块迁移：一个模块一个触发器，历史表与版本号互不影响。 */
        @Bean
        List<FlywayMigrationInitializer> moduleFlywayInitializers(ModuleInstances instances) {
            List<FlywayMigrationInitializer> initializers = new ArrayList<>(instances.modules().size());
            for (int i = 0; i < instances.modules().size(); i++) {
                ModuleInstances.Module module = instances.modules().get(i);
                FlywayMigrationInitializer initializer = new FlywayMigrationInitializer(module.flyway());
                // 按登记顺序迁移，日志里能看清顺序，P1 出现跨模块依赖时也只需改登记顺序
                initializer.setOrder(i);
                initializers.add(initializer);
                log.info("已登记模块迁移：module={} schema={} location={}",
                        module.name(), module.schema(), module.flyway().getConfiguration().getLocations()[0]);
            }
            return List.copyOf(initializers);
        }
    }
}
