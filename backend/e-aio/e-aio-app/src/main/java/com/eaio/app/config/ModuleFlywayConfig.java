package com.eaio.app.config;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

/**
 * 每模块独立 Flyway 实例的装配（P0 册 3.6）。
 *
 * <p>单一 Flyway 实例 + 多 Schema 会让全部模块共享一个版本号序列与一张历史表，破坏"模块可独立演进"
 * （HLD 13.1），因此每模块一个实例：各自的历史表落在自己的默认 Schema 内。
 *
 * <p>三个刻意的选择：
 * <ul>
 *   <li>排除默认的 {@code FlywayAutoConfiguration}（见 {@code EaioApplication} 与 {@code application.yml}）：
 *       否则 Boot 还会按 {@code spring.flyway.*} 再建一个"全局"实例，迁移跑两遍；</li>
 *   <li>复用 Boot 的 {@link FlywayMigrationInitializer} 作为迁移触发器，而不是自己写
 *       {@code InitializingBean}——初始化顺序与失败语义交给框架，配置类只负责组装；</li>
 *   <li>"有没有数据库"在装配处**显式判定**（{@code dataSourceProvider.getIfAvailable()}），
 *       不用 {@code @ConditionalOnBean}：条件注解在用户 {@code @Configuration} 上按注册顺序求值，
 *       看不到同一批配置里声明的数据源（实测会漏装配），用它等于把关键行为留给顺序巧合。</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ModuleFlywayProperties.class)
@ConditionalOnProperty(prefix = "eaio.flyway", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ModuleFlywayConfig {

    private static final Logger log = LoggerFactory.getLogger(ModuleFlywayConfig.class);

    /**
     * 未配置数据库时返回 null：迁移不装配，应用按"无库启动"运行（P0 验收）。
     * 配置了数据库却拿不到数据源时抛错——配了库却连不上，静默跳过迁移比启动失败危险得多。
     */
    @Bean
    ModuleInstances moduleInstances(ObjectProvider<DataSource> dataSourceProvider, ModuleFlywayProperties properties,
            Environment environment) {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            if (environment.containsProperty("spring.datasource.url")
                    || environment.containsProperty("spring.datasource.jdbc-url")) {
                throw new IllegalStateException("已配置 spring.datasource.url 但没有可用的 DataSource："
                        + "检查数据源自动配置是否被排除（见 application.yml），迁移不会静默跳过");
            }
            log.warn("未配置数据库（无 DataSource、无 spring.datasource.url）：迁移装配跳过，应用以无库模式启动");
            return null;
        }
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
    List<FlywayMigrationInitializer> moduleFlywayInitializers(ObjectProvider<ModuleInstances> instancesProvider) {
        ModuleInstances instances = instancesProvider.getIfAvailable();
        if (instances == null) {
            // 未配置数据库：没有可迁移的实例，返回空列表而不是让启动失败
            return List.of();
        }
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
