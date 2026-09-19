# ADR-0002：每模块独立 Schema 与每模块独立 Flyway 实例

- 状态：已接受
- 日期：2026-09-19
- 适用批次：P0 冻结，P1 起逐模块追加登记
- 相关：`docs/agents/database.md`、04-DD P0 册 3.6、HLD 4.2 / 13.1

## 背景

模块化单体的模块边界必须落到数据层，否则"模块可独立演进、可按 NFR-EXT-01 裁剪"只是代码层的说法。默认做法（一个应用、一个 Flyway 实例、一个版本序列、一张 `flyway_schema_history`）会让所有模块共享版本号，任一模块的迁移失败都会阻断全局启动。

## 决策

1. **每模块独立 Schema `eaio_<module>`**；模块只能访问自己的 Schema，**禁止跨 Schema 关联查询与写操作**；跨模块数据经公共 API 获取。
2. **每模块独立 Flyway 实例**：`locations=classpath:db/migration/<module>`、`schemas=eaio_<module>`，在该 Schema 内建 `flyway_schema_history`，版本号模块内独立递增。
3. 关闭 Spring Boot 默认单实例迁移（`spring.flyway.enabled=false`），由 `ModuleFlywayConfig` 按 `eaio.flyway.modules` 登记表逐模块执行 `migrate()`。
4. Schema 由各模块自身迁移脚本负责（`create-schemas=true`）；脚本内对象一律显式 schema 限定。
5. 本地开发默认不迁移（`eaio.flyway.enabled=false`），迁移路径的权威验证在 CI 集成测试（Testcontainers PostgreSQL）。

## 被否决的备选

- **单 Flyway 实例 + 多 Schema（`schemas=eaio_platform,eaio_iam,…` + `default-schema`）**：配置最省，但版本号全局共享、脚本靠 `search_path` 切换，模块无法独立演进，与 HLD 13.1 冲突。
- **单 Schema 多模块共用表前缀**：等于放弃模块数据边界，跨模块 SQL 无法被约束。

## 后果（含代价）

- 配置与注册成本更高：新增模块 = 新建迁移目录 + 登记 `eaio.flyway.modules` + 独立版本序列，多一个易漏的步骤。
- 启动期需要逐实例 `migrate()`；`eaio_platform` 在 P0 仅有基线占位（不含业务表），baseline **V1 只允许含 schema 与注释**，P1 platform 从 `V2__` 起连续编号，禁止重复创建 schema。
- 本机无 Docker 时集成测试跳过（`disabledWithoutDocker`），迁移验证只在 CI 完成：存在"本地绿、CI 红"的未闭合口子，需在提交前留意 Flyway 脚本。
- 无跨 Schema 外键与联表：跨模块一致性由应用层（同一事务内调用公共 API）保证。
