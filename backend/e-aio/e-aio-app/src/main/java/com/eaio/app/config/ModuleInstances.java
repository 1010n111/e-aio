package com.eaio.app.config;

import java.util.List;

import org.flywaydb.core.Flyway;

/**
 * 已装配的模块迁移实例（模块名 → Schema → Flyway 实例），供迁移触发器与诊断使用。
 *
 * @param modules 模块实例列表，顺序与 {@code eaio.flyway.modules} 登记顺序一致
 */
public record ModuleInstances(List<Module> modules) {

    public ModuleInstances {
        modules = List.copyOf(modules);
    }

    /** 单个模块的迁移实例。 */
    public record Module(String name, String schema, Flyway flyway) {
    }
}
