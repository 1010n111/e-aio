package com.eaio.app.config;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 每模块独立 Flyway 实例的装配（P0 册 3.6）。
 *
 * <p>单一 Flyway 实例 + 多 Schema 会让全部模块共享一个版本号序列与一张历史表，破坏"模块可独立演进"
 * （HLD 13.1），因此每模块一个实例：各自的历史表落在自己的默认 Schema 内。
 *
 * <p>四个刻意的选择：
 * <ul>
 *   <li>排除默认的 {@code FlywayAutoConfiguration}（见 {@code EaioApplication} 与 {@code application.yml}）：
 *       否则 Boot 还会按 {@code spring.flyway.*} 再建一个"全局"实例，迁移跑两遍；</li>
 *   <li>"有没有数据库"在装配处**显式判定**（{@code ObjectProvider#getIfAvailable}），
 *       不用 {@code @ConditionalOnBean}：条件注解在用户 {@code @Configuration} 上按注册顺序求值，
 *       看不到同一批配置里声明的数据源（实测漏装配）；</li>
 *   <li>**迁移在上下文启动时显式执行**（{@link MigrationRunner}），不依赖"某个 Bean 恰好被实例化"：
 *       复用 Boot 的 {@code FlywayMigrationInitializer} 时实测迁移没跑（库表为空而应用照常启动），
 *       这类失败会伪装成"迁移写错"，排查成本高；显式调用让"迁移未跑"变成不可能；</li>
 *   <li>迁移失败 = Bean 创建失败 = **启动失败**：生产配了库却迁移不了，绝不允许带着旧结构起来。</li>
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
                    // 种子里的 ${user.home} 是**运行期**占位（文件中心读取时解析），不是迁移期占位；而 Flyway
                    // 默认把 ${...} 当自己的占位符并在缺值时直接失败（实测报 "No value provided for
                    // placeholder: ${user.home}"）。把它映射成自身 = 原样保留，且不关闭占位符机制——
                    // 后续脚本真要用 Flyway 占位符时仍可正常声明。
                    .placeholders(Map.of("user.home", "${user.home}"))
                    .load();
            modules.add(new ModuleInstances.Module(module, schema, flyway));
        }
        return new ModuleInstances(List.copyOf(modules));
    }

    /**
     * 启动即迁移：一个模块一次 {@code migrate()}，按登记顺序执行。
     *
     * <p>返回的 {@link MigrationRunner} 是普通单例 Bean，因此该 Bean 一旦实例化就真的把迁移跑完
     * （上下文 refresh 阶段），不依赖其他组件的实例化时机；迁移结果同时成为可断言的证据。
     */
    @Bean
    MigrationRunner moduleMigrationRunner(ObjectProvider<ModuleInstances> instancesProvider) {
        ModuleInstances instances = instancesProvider.getIfAvailable();
        if (instances == null) {
            // 未配置数据库：没有可迁移的实例，不迁移也不失败
            return new MigrationRunner(List.of());
        }
        List<MigrationRunner.Outcome> outcomes = new ArrayList<>(instances.modules().size());
        for (ModuleInstances.Module module : instances.modules()) {
            var result = module.flyway().migrate();
            outcomes.add(new MigrationRunner.Outcome(module.name(), module.schema(),
                    result.migrationsExecuted, result.targetSchemaVersion));
            log.info("模块迁移完成：module={} schema={} 执行脚本数={} 目标版本={}",
                    module.name(), module.schema(), result.migrationsExecuted,
                    result.targetSchemaVersion == null ? "(无)" : result.targetSchemaVersion);
        }
        return new MigrationRunner(List.copyOf(outcomes));
    }
}
