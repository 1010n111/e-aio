package com.eaio.app.config;

import java.util.List;

/**
 * 启动时的迁移结果（P0 册 3.6）：每个模块执行了几条脚本、目标版本是什么。
 *
 * <p>它同时是"迁移是否真的跑过"的**可断言语据**：集成测试注入本 Bean 即可断言，
 * 不必猜"Bean 有没有被实例化"。
 *
 * @param outcomes 各模块的迁移结果，顺序与 {@code eaio.flyway.modules} 登记顺序一致
 */
public record MigrationRunner(List<Outcome> outcomes) {

    public MigrationRunner {
        outcomes = List.copyOf(outcomes);
    }

    /** 单模块迁移结果。 */
    public record Outcome(String module, String schema, int migrationsExecuted, String targetVersion) {
    }
}
