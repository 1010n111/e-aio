# 数据库约定

- 每模块独立 Schema `eaio_<module>`。
- Flyway **每模块一个实例**：脚本 `classpath:db/migration/<module>/`，命名 `V<版本>__<描述>.sql`；`locations=classpath:db/migration/<module>`、`schemas=eaio_<module>`、版本号模块内独立递增；全局 `spring.flyway.enabled=false`，详见 P0 册 3.6。
- 表主键统一雪花 long；**逻辑删除优先**；统一列 `created_at` / `created_by` / `updated_at` / `updated_by` / `version` / `deleted`（逻辑删除表，`BOOLEAN`）走统一基类。表名 `snake_case` **单数**、模块内不加前缀（含 `sys_`；唯一例外 iam 的 `sys_user` 因 `user` 是 PG 保留字）；索引 `idx_<table>_<cols>`、唯一约束 `uk_<table>_<cols>`、外键 `fk_<table>_<ref>`、触发器 `trg_<table>_<purpose>`；时间列一律 `TIMESTAMPTZ`；枚举用 `VARCHAR(32)` + `CHECK`（不用 PG `ENUM`）。以上 P1 批次总册 3.5 为准。
- **禁止跨 Schema 关联查询与写操作**；跨模块数据经 API 获取（模块边界见 [architecture.md](architecture.md)）。**已登记例外**：数据权限子树谓词对 `eaio_iam.org_node_path` 的只读单表 `SELECT`（[ADR-0004](../adr/0004-cross-schema-readonly-predicate.md)）。
- 数据库设计随各详细设计分册的「数据结构设计」章节维护，不单独成文档（文档命名规则见 [docs-map.md](docs-map.md)）。
- **Schema 权限**：本地/CI（Testcontainers 超管）验证「应用可建 Schema」路径可用；**生产由 DBA 预建 `eaio_<module>` 并授权应用角色**，部署清单包含该步骤（P0 不引入独立迁移账号）。见 P0 册 3.6。
- **本地镜像**：`pgvector/pgvector:pg17`（PostgreSQL 17 + pgvector 扩展）；Redis `redis:7-alpine`。见 [build-and-test.md](build-and-test.md)。
- **语言**：错误消息/日志全中文，i18n 留 P1（P0 册 3.6）。
