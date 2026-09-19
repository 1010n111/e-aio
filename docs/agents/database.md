# 数据库约定

- 每模块独立 Schema `eaio_<module>`。
- Flyway **每模块一个实例**：脚本 `classpath:db/migration/<module>/`，命名 `V<版本>__<描述>.sql`；`locations=classpath:db/migration/<module>`、`schemas=eaio_<module>`、版本号模块内独立递增；全局 `spring.flyway.enabled=false`，详见 P0 册 3.6。
- 表主键统一雪花 long；**逻辑删除优先**；审计字段 `create_by` / `create_time` / `update_by` / `update_time` 走统一基类。
- **禁止跨 Schema 关联查询与写操作**；跨模块数据经 API 获取（模块边界见 [architecture.md](architecture.md)）。
- 数据库设计随各详细设计分册的「数据结构设计」章节维护，不单独成文档（文档命名规则见 [docs-map.md](docs-map.md)）。
