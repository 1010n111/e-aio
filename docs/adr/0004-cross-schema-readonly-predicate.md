# ADR-0004：跨 Schema 只读谓词例外（数据权限子树过滤）

- 状态：已接受
- 日期：2026-09-20
- 适用批次：P1（iam 数据权限），评审点见 04-DD P1 批次总册 3.1
- 相关：[ADR-0002](0002-per-module-schema-and-flyway-instance.md)、`docs/agents/database.md`、`04-…-P1-4-iam.md` 3.9 / 7.6

## 背景

ADR-0002 冻结"模块只能访问自己的 Schema，禁止跨 Schema 关联查询与写操作"。但 P1 的**数据权限**要求在任意业务模块的查询上追加"本组织及其下级可见"的谓词，而组织树与闭包表在 `eaio_iam`：

- **方案 A（已先落地，iam 册 3.9 现状）**：认证时解析出可见组织 id 集合并缓存，业务查询追加 `org_id IN (:orgIds)`。简单、无跨 Schema，但 `orgIds` 规模随组织数增长：iam 册设定上限 2000，超过即拒绝（`DATA_SCOPE_TOO_WIDE`）——这意味着**大型集团（>2000 个组织节点）无法用组织级数据权限**，只能退化成 `ALL` 或手写 `CUSTOM` 规则，等于把安全边界交给用户配置。
- **方案 B（本 ADR 批准）**：让数据权限拦截器对**组织树闭包表**做一次**只读谓词**（`EXISTS (SELECT 1 FROM eaio_iam.org_node_path p WHERE p.ancestor_id IN (...) AND p.descendant_id = t.org_id)`），把"可见组织集合"下推到数据库，规模不再受限（走 `idx_org_node_path_descendant`）。

## 决策

**在"跨 Schema 禁止"这条不变量上开一个逐项的、只读的、单表的例外，并把例外本身写成可被架构测试枚举的清单。**

1. **例外对象（封闭清单）**：仅允许 `eaio_iam` 的下列对象被**其他模块的数据权限拦截器**以只读谓词引用：
   | 被引用对象 | 允许的用法 | 索引依据 |
   |---|---|---|
   | `eaio_iam.org_node_path(ancestor_id, descendant_id, depth)` | `EXISTS` 子查询谓词；只允许列 `ancestor_id` / `descendant_id` | `idx_org_node_path_descendant` |
   | `eaio_iam.org_node(id, status)` | 仅当需要排除已注销/归档节点时的 `EXISTS` 谓词 | 主键 / `idx_org_node_status` |
   **清单之外一切跨 Schema 访问仍被禁止**（含 `eaio_iam` 的其他表、任何 `JOIN`、任何写操作）。
2. **只读且不可回写**：仅 `SELECT` 谓词；数据库层对应用角色**只授予上述列的 `SELECT`**（`GRANT SELECT (ancestor_id, descendant_id) ON eaio_iam.org_node_path TO eaio_app`），不授予 `INSERT/UPDATE/DELETE`。应用角色无权改组织树（组织树写入由 iam 自己的迁移/服务完成，仍在其 Schema 内）。
3. **注入点唯一**：例外只能出现在 **iam 提供的 `DataPermissionInterceptor`**（`com.eaio.iam.infrastructure`）生成的 SQL 里。**业务模块不得手写跨 Schema 子查询**——业务侧只声明"本表哪个列是 org 维度"（`@DataScope(orgColumn = "...")`），谓词由拦截器生成。
4. **降级与上限**：
   - 可见组织数 ≤ `inline-max`（默认 2000）→ 内联 `IN`/`= ANY(ARRAY[...])`（少一次子查询，命中率更高）；
   - 2000 < 可见组织数 ≤ `exists-max`（默认 50000）→ 走 `EXISTS` 谓词（谓词成本与组织数无关，只有 `ancestor_id IN (...)` 的参数长度随上限增长）；
   - 超过 `exists-max` 或闭包表不可用 → **停用该数据范围规则**：谓词取恒假（`1=0`）+ 明确错误码 + WARN 级告警（谁的范围、多少节点）。**不退化成全量可见**。
   - **为什么是"停用"不是"拒绝"**：可见组织数超限是**规则形状问题**（给集团顶层管理员配了 `ORG_AND_SUB`），不是越权企图；在使用第一天就硬拒绝会直接阻塞业务。恒假 + 告警让问题立刻可见、可处置，且不泄漏数据。
   - **为什么不做"物化可见组织快照"**：组织移动/角色变更要刷新全量快照，写入放大且易漂移；真到十万级组织时再按 ADR 修订引入，由 iam 统一维护，本批次不预留该复杂度。
5. **可枚举与被测试**：架构测试断言"`eaio_iam.org_node_path` 只被 iam 的拦截器模块引用"；集成测试断言 ① 越权取数返回空而非报错/全量、② 注入的 SQL 命中索引（`EXPLAIN` 断言不含全表扫描）、③ 应用角色对例外表无写权限（`has_table_privilege` 为 false）。

## 被否决的备选

- **纯方案 A（只内联 id 集合）**：>2000 组织即不可用（见背景），把安全能力变成规模上限的函数。
- **把组织树搬到 common 或复制一份到各模块**：组织树只有一个权威副本（iam），复制会产生同步与一致性事故；common 已冻结 V1，不承载业务数据。
- **物化"用户 → 可见组织"宽表到各模块 Schema**：写入放大 + 变更风暴（组织移动要刷全量），且各模块各存一份必然漂移。
- **让业务模块自己查 iam API 拿 id 集合**：回到方案 A 的规模问题，且每次查询多一次跨模块调用。

## 后果（含代价）

- ADR-0002 的"禁跨 Schema"从**绝对规则**变成"默认禁止 + 一张逐项清单例外"；清单必须随新增例外更新并评审（本 ADR 是清单的第一次登记）。
- 应用角色对 `eaio_iam` 有了**选择性只读授权**：DBA 建库脚本多一条 `GRANT`，且需要在 iam 迁移脚本里维护（迁移脚本必须幂等）。
- 拦截器生成的 SQL 复杂度上升：带 `EXISTS` 的查询在计划不稳时可能退化（故有 `EXPLAIN` 断言与"内联优先"策略）；DBA 手工 SQL 复核更难，排障要认得出"这段谓词是拦截器加的"。
- 组织树闭包表成为**跨模块热点读对象**：其索引质量与统计信息直接影响全站列表性能（iam 册已登记该风险；分区/PG 统计信息维护纳入运维口径）。
