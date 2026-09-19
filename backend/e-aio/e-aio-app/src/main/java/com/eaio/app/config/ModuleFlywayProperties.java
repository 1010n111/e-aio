package com.eaio.app.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 迁移配置（P0 册 3.6）。
 *
 * @param enabled 迁移总开关。默认 {@code true}：生产不得静默跳过迁移；本地无数据库演示时显式设
 *                {@code false}，缺少数据源的真实故障因此不会被吞掉。
 * @param modules 已登记模块的列表，取值与模块根包同名（P0 册附录 6.1 为唯一登记表）。
 *                每个模块对应一个独立 Flyway 实例：脚本目录 {@code classpath:db/migration/<module>}、
 *                默认 Schema {@code eaio_<module>}、版本号在模块内独立递增。
 */
@ConfigurationProperties(prefix = "eaio.flyway")
public record ModuleFlywayProperties(boolean enabled, List<String> modules) {

    public ModuleFlywayProperties {
        modules = modules == null ? List.of() : List.copyOf(modules);
    }

    /** 模块的迁移脚本目录。 */
    public String locationOf(String module) {
        return "classpath:db/migration/" + module;
    }

    /** 模块的默认 Schema（Flyway 在该 Schema 内建 {@code flyway_schema_history}）。 */
    public String schemaOf(String module) {
        return "eaio_" + module;
    }
}
