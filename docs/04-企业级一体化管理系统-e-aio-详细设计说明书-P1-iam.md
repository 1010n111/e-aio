---
title: 企业级一体化管理系统详细设计说明书 · 第 P1 册（分册二）· iam（身份与访问管理）
type: 详细设计说明书（DD）· 模块分册
phase: 详细设计
version: V1.0
status: 待评审
date: 2026-09-20
tags:
  - e-aio
  - DD
  - 详细设计
  - iam
  - 权限
aliases:
  - e-aio DD P1 iam
  - iam 详细设计
  - 身份与访问管理详细设计
related:
  - "[[04-企业级一体化管理系统-e-aio-详细设计说明书]]"
  - "[[04-企业级一体化管理系统-e-aio-详细设计说明书-P0-工程地基]]"
  - "[[04-企业级一体化管理系统-e-aio-详细设计说明书-P1-平台底座]]"
  - "[[03-企业级一体化管理系统-e-aio-概要设计说明书]]"
  - "[[02-企业级一体化管理系统-e-aio-软件需求规格说明书]]"
---

# 企业级一体化管理系统（e-aio）详细设计说明书 · 第 P1 册（分册二）· iam（身份与访问管理）

> **项目名称**：企业级一体化管理系统（e-aio）
> **编制依据**：GB/T 8567-2006《计算机软件文档编制规范》、GB/T 9385-2008《计算机软件需求规格说明规范》
> **上游文档**：[[01-企业级一体化管理系统-e-aio-可行性研究报告|01-可行性研究报告]]、[[02-企业级一体化管理系统-e-aio-软件需求规格说明书|02-软件需求规格说明书（SRS）]]、[[03-企业级一体化管理系统-e-aio-概要设计说明书|03-概要设计说明书（HLD）]]、[[04-企业级一体化管理系统-e-aio-详细设计说明书-P0-工程地基|第 P0 册 · 工程地基]]、[[04-企业级一体化管理系统-e-aio-详细设计说明书-P1-平台底座|第 P1 册 · 平台底座]]
> **模块**：`iam`（`com.eaio.iam` / Schema `eaio_iam` / 错误码段 `21000–21999` / 迁移目录 `classpath:db/migration/iam`，登记表见 P0 册 6.1）
> **版本**：V1.0（首版，待评审）
> **日期**：2026-09-20

---

## 核心结论（执行摘要）

1. **iam 是认证的唯一落点，也是数据权限的唯一裁决方**。P0 明确不落认证实现（P0 册 3.7 装配例外、`SecurityConfig` 缺席），P1 起 iam 同时承担：认证（Spring Security + 同进程自托管 OAuth2 Authorization Server）、组织与用户主档、RBAC/ABAC 授权裁决、数据行级与字段级权限、SoD、组织上下文。其他模块只经 `com.eaio.iam.api` 消费，不自建用户表、不自行裁剪字段、不自行拼数据权限条件。
2. **组织模型三件套并存，各有不可替代的用途**：`org_node.parent_id` 是唯一真源；`org_node.path`（`/1/5/23/` 物化路径）用于面包屑与子树前缀过滤；闭包表 `org_node_path` 用于精确的祖先/子树集合查询。**不采用** `ltree`/纯递归 CTE 作为唯一方案（前者引入 contrib 扩展、加重信创适配面，后者无可索引路径），**不采用** DB 触发器维护闭包表（无法表达同树写锁、单测不可覆盖），改用应用层单事务维护 + 每日一致性校验任务，取舍见 3.1.5。
3. **数据行级权限采用「统一 SQL 改写 + 白名单资源」**：复用 MyBatis-Plus 3.5.17 内置 `DataPermissionInterceptor`，只实现 `MultiDataPermissionHandler#getSqlSegment`（不重写 JSqlParser）。改写只对登记在 `eaio_iam.data_scope_resource` 的资源表生效；**未登记资源不改写**（避免误伤元数据查询），**已登记资源但用户无任何规则时注入恒假条件 `AND 1 = 0`**（fail-closed，不报错、不泄漏行数）。改写片段**禁止引用 `eaio_iam` 的表**（CONTEXT「禁止跨 Schema 关联查询」+ ADR-0002），子树集合一律由 iam 展开为内联 ID 数组（`= ANY(ARRAY[...])`）。
4. **权限判定与令牌解耦**：JWT 只携带 `sub`/`org_id`/`sid`/`ver`（权限版本）等稳定声明，**不把权限码清单塞进令牌**——权限变更后旧令牌立即按新快照生效，无需等令牌过期；撤销靠 Redis 会话索引 + 版本号。权限快照缓存不一致窗口 ≤ 30 s（见 4.7），SoD 校验与字段权限写侧直读 DB（强一致）。
5. **环打破方式明确**：`platform` 不依赖 `iam`（HLD 11.1 依赖矩阵），但 platform 的文件/任务需要组织维度标签。方案：`TenantCtx` 与 `TenantCtxProvider` 的**实现与接口都在 iam**；platform 侧只声明自有端口 `OrgContextPort`（`com.eaio.platform.api`），由 iam 提供适配器实现之（依赖方向 iam → platform，合法）。iam 对 audit 在 M2–M3 **零编译期依赖**，只发事件 + 结构化日志；M4 起加 `<optional>true</optional>` 的 `e-aio-audit` 依赖 + `ObjectProvider<PermissionAuditApi>` 可选注入，缺席即降级（见 2.4.3、3.11.4）。
6. **本册规模**：31 张表、10 个 iam 所有权契约接口（+ audit 侧 `PermissionAuditApi`）、5 个领域事件（4 个 HLD 已登记 + 本册新增 `EmployeeChangedEvent`）、66 个已分配错误码（21001–21092，留空段见表 7-1）、14 个 Flyway 迁移脚本（V1–V14）。

---

## 1. 引言

### 1.1 编写目的

本册是 e-aio 详细设计说明书第 P1 册的**第二个模块分册**，把 HLD（`03-概要设计说明书`）与 SRS（`02-软件需求规格说明书`）中关于身份与访问管理的**需求条目**落到"可直接编码、可直接建表、可直接写测试"的粒度：

- 供后端开发按第 3、4、5 章实现 `e-aio-iam` 模块，无需再回头讨论模型与契约；
- 供测试按第 6 章编写单测 / 集成测试 / 安全专项 / E2E 验收；
- 供评审核对 FR 追溯（2.2）与 P0 册遗留事项落实（7.5）；
- 供前端按 5.7 与 3.12 对接登录、个人中心、组织树与权限点。

本册**不含**前端代码实现（前端文件在 `frontend/` 下按 [frontend-conventions.md](agents/frontend-conventions.md) 独立演进），也**不改动**任何既有代码。

### 1.2 文档范围

| 项 | 内容 |
|---|---|
| 覆盖批次 | M2–M5（HLD 13.2 顺序 4，iam） |
| 覆盖能力 | 认证（账号密码/验证码/失败锁定/MFA/SSO/LDAP）、令牌与会话、组织树与生命周期、用户/岗位/员工任职、RBAC + ABAC、数据行级权限、字段级权限、SoD、组织上下文、权限审计与事件、通讯录、iam 管理前端契约 |
| 不覆盖 | 工作流/审批引擎本体（P1 顺序 5–6，iam 只提供 `SoDCheckApi`/`PermissionApi` 供其调用）、薪酬与考勤（P2 hr）、数据权限申请/授权/回收流程（FR-SEC-14，P2）、多语言（P3）、平台参数中心/字典/文件/任务实现（P1 platform 册） |
| 交付物 | 本册 + 后续由本册派生的 `e-aio-iam` 模块代码（不在本册交付） |

### 1.3 术语与约定

术语以仓库根 [CONTEXT.md](../../CONTEXT.md) 为准（模块、命名接口、契约 V1、统一返回体、HTTP 恒 200、错误码分段、幂等键、每模块独立 Schema/Flyway、逻辑删除等）。本册新增/细化的局部术语：

| 术语 | 本册定义 |
|---|---|
| 组织节点（org node） | `org_node` 中的一行，`node_type ∈ {GROUP, COMPANY, DEPT, TEAM}`，即 HLD 4.1.1 的"集团/公司/部门/团队" |
| 集团 / 公司 | `node_type = 'GROUP'` / `'COMPANY'` 的组织节点；**公司即"子公司"，无独立 `company_id` 概念**，公司标识就是节点 id |
| 当前组织上下文 | 一次请求内"以哪个组织身份视角办事"，由 `TenantCtx.orgId` 承载；同一用户可挂载多个组织（`user_org`） |
| 主组织 | `user_org.is_primary = true` 的挂载，登录默认上下文；同用户至多一条 |
| 数据范围规则 | `data_scope_rule` 中的一行：主体（角色/用户）× 资源 × 范围类型（`SELF/DEPT/DEPT_AND_SUB/ORG/ORG_AND_SUB/ALL/CUSTOM`） |
| 资源码（resource_code） | 受数据权限管辖的逻辑资源标识（如 `oa:leave`），在 `data_scope_resource` 中登记后才参与 SQL 改写 |
| 权限快照 | 某用户在某组织下的有效权限码集合 + 数据范围规则 + 字段权限矩阵，缓存于 Redis（4.7） |
| 权限版本（ver） | 用户级单调递增整数，任何授权变更 +1；JWT 与快照均携带，用于失效判定 |
| lifecycle | 组织生命周期 `PREPARING → ACTIVE → DEREGISTERED → ARCHIVED`（本册冻结，见 3.1.4） |
| 派生表 | 由主表事务维护、不独立承载业务语义的表（`org_node_path`、`permission_snapshot`、`event_outbox`、`event_consume_record`） |
| 本册新增 | 标注该内容的实现资产在 P0 尚不存在，由 P1 iam 首次引入（如 `SecurityUtils`、`CryptUtils`、`RedisKit` 门面） |

**命名与列约定（本册冻结，上游冲突见 2.4.4）**：

- 表名 `snake_case` **单数**（批次总册 3.5 全批次统一，见 2.4.4 I-4），模块内不加 `iam_` 前缀（Schema 已隔离）；DDL 一律显式 schema 限定 `eaio_iam.<table>`。**唯一例外**：用户主档用 `sys_user`（PostgreSQL 保留字 `user` 不可直接做表名，批次已裁 `sys_user`）。
- 统一列：`id BIGINT`（雪花，`IdGenerator` 生成，DBA 不设 `DEFAULT`）、`created_at TIMESTAMPTZ NOT NULL`、`created_by BIGINT`、`updated_at TIMESTAMPTZ`、`updated_by BIGINT`、`version INT NOT NULL DEFAULT 0`（乐观锁）；逻辑删除表加 `deleted BOOLEAN NOT NULL DEFAULT false`。语义沿用库内既有审计词根（`create_*`/`update_*`：谁在何时创建/修改），列名采用本册冻结形式。
- 时间列统一 `TIMESTAMPTZ`（UTC 存储，展示层按用户时区渲染）。
- 索引命名 `idx_<table>_<cols>`；唯一索引 `uk_<table>_<cols>`；主键 `pk_<table>`；外键 `fk_<table>_<col>`；检查约束 `ck_<table>_<col>`。
- 枚举列一律 `VARCHAR(n)` + `CHECK` 约束，**不使用** PostgreSQL 枚举类型（`ALTER TYPE` 演进困难、加重信创适配面）。
- 布尔列一律 `BOOLEAN NOT NULL DEFAULT false` 形态，不用 `CHAR(1)`。

### 1.4 追溯关系

| 本册章节 | 上游依据 |
|---|---|
| 2.2 能力清单 | SRS 3.1 FR-SEC-01…15、3.10 FR-HR-06/07/08；HLD 4.1.1、5.2、13.2 |
| 3.1 组织模型 | HLD 4.1.1、5.2；SRS FR-SEC-05/10、FR-HR-06 |
| 3.3–3.4 认证与会话 | SRS FR-SEC-01/02/03/04；HLD 8.1；ADR-0001 |
| 3.5–3.9 授权 | SRS FR-SEC-06/07/08/09/11/12；HLD 8.2；P0 册 6.3 第 4 条 |
| 3.10 组织上下文 | SRS FR-SEC-15；HLD 8.2；P0 册 6.3 第 1 条 |
| 3.11 审计与事件 | SRS FR-SEC-13；HLD 3.3、4.4；P0 册 6.3 第 ⑤ 条 |
| 4 数据结构 | HLD 4.2；ADR-0002；P0 册 3.6、6.1；SRS 6.1、6.2 |
| 5 接口契约 | P0 册 3.2；docs/agents/api-conventions.md；HLD 3.2 |
| 6 测试与验收 | HLD 13.3；P1 platform 册 6 章（同批次风格对齐） |

### 1.5 参考资料

1. `docs/02-企业级一体化管理系统-e-aio-软件需求规格说明书.md`（SRS V1.1）
2. `docs/03-企业级一体化管理系统-e-aio-概要设计说明书.md`（HLD V1.1）
3. `docs/04-企业级一体化管理系统-e-aio-详细设计说明书-P0-工程地基.md`（P0 册，契约与工程地基权威）
4. `docs/04-企业级一体化管理系统-e-aio-详细设计说明书-P1-平台底座.md`（同批次兄弟册，platform 契约）
5. `docs/adr/0001-unified-post-and-always-200-result-contract.md`、`docs/adr/0002-per-module-schema-and-flyway-instance.md`
6. `docs/agents/{architecture,api-conventions,database,java-conventions,frontend-conventions,build-and-test}.md`
7. `CONTEXT.md`（领域词汇表）
8. 既有代码实测：`e-aio-common`（`Result`/`PageResult`/`ErrorCode`/`BusinessErrorCode`/`IdempotencyStore`/`IdGenerator`/`JsonUtils`/`DateUtils`/`StringUtils`/`SensitiveUtils` 等）、`e-aio-app/web`（`TraceIdFilter` order = `Ordered.HIGHEST_PRECEDENCE`、`IdempotencyFilter` order = `Ordered.HIGHEST_PRECEDENCE + 20`，装配于 `WebConfig`）、`e-aio-app/config`（`ModuleFlywayProperties`：`locationOf(m) = classpath:db/migration/<m>`、`schemaOf(m) = eaio_<m>`）、`application.yml`（`eaio.flyway.modules: [platform]`）、`ArchitectureTest`（10 个 `@Test`，唯一架构落点）

> **本册不重复 P0 册已冻结内容**：统一返回体、错误码通用段、幂等、全局异常、Flyway 装配、ArchUnit 基线、前端请求封装一律引用不重述。

---

## 2. iam 总体设计

### 2.1 模块定位与职责边界

`iam` 属于 HLD 2.2.2 的**通用能力层**，是"身份与访问管理"的唯一实现方与唯一裁决方。

**iam 负责（唯一产权）**：

| 领域 | 内容 |
|---|---|
| 组织主数据 | 无限级组织树（集团/公司/部门/团队）、组织生命周期、组织与岗位挂载 |
| 人员主数据 | 用户主档、多组织挂载、员工任职主档（入转调离的"职"部分）、通讯录 |
| 认证 | 账号密码 / 验证码 / 失败锁定 / MFA(TOTP) / SSO(SAML2、OIDC) / LDAP 适配；令牌签发、刷新轮换、撤销 |
| 授权 | 角色与权限点、角色继承与覆盖、数据行级权限、ABAC 策略、字段级权限、SoD |
| 上下文 | 请求级组织上下文 `TenantCtx` 与 `TenantCtxProvider` |
| 安全裁决 | `PermissionApi.resolveDataScope` / `filterFields` / `SoDCheckApi.check` 是**全系统唯一**的权限裁决入口 |

**iam 不负责（明确外推）**：

| 不负责 | 归属 |
|---|---|
| 薪酬、考勤、绩效、假勤 | P2 `hr`（iam 只提供员工主档与任职关系） |
| 审计日志落库与 WORM/哈希链 | P1 顺序 5 `audit`（M4；iam 只发事件 + 权限审计调用） |
| 工作流/审批引擎与审批人指定 | P1 顺序 5–6 `workflow`/`approval`（调用 iam 的 `SoDCheckApi`/`PermissionApi`） |
| 菜单路由渲染、按钮显隐的最终表现 | 前端（后端只给 `menuTree` 与权限码，前端 `v-hasPermi` 展示，**后端永远二次校验**） |
| 参数、字典、文件、定时任务、缓存管理 | P1 `platform`（iam 经 `com.eaio.platform.api` 消费，不建同名表） |
| 数据权限"申请/授权/回收流程" | P2（FR-SEC-14）；P1 只提供 `DataScopeApi.save/del` 与 `access_grant` 显式授权表 |

### 2.2 能力清单与 FR 对照表

| 需求编号 | 需求（SRS 语义） | 优先级 | 本册落实章节 | 关键实现 |
|---|---|---|---|---|
| FR-SEC-01 | 用户名/密码登录、验证码、登录失败锁定 | P0 | 3.3.2–3.3.4 | `AuthnApi.login`；Argon2id；`login_attempt` + 锁定计数 |
| FR-SEC-02 | MFA（TOTP/短信） | P1 | 3.3.6 | TOTP（RFC 6238）；短信留 P2（`mfa_factors` 预留类型，本册只实现 TOTP） |
| FR-SEC-03 | SSO（SAML2/OIDC）、LDAP 对接 | P1 | 3.3.7–3.3.8 | `sso_config`/`ldap_config`；SP 侧实现；LDAP 只读同步 + 首登建号 |
| FR-SEC-04 | JWT 令牌 + Redis 会话可撤销 | P0 | 3.4 | RS256 短期 access + refresh 轮换；`auth_session`/`auth_refresh_token`；Redis 会话索引 |
| FR-SEC-05 | 无限级组织树（集团-子公司-部门） | P0 | 3.1 | `org_node` + `path` + `org_node_path` 闭包表 |
| FR-SEC-06 | RBAC、角色可限定组织范围 | P0 | 3.5 | `role.org_scope_id`；`role_permission`/`user_role` |
| FR-SEC-07 | ABAC 属性权限（组织/数据/上下文属性） | P1 | 3.7 | `abac_policy` 条件 DSL + 求值器 |
| FR-SEC-08 | 数据行级权限（组织/部门/责任人过滤） | P0 | 3.6 | `data_scope_rule` + `DataPermissionInterceptor` SQL 改写 |
| FR-SEC-09 | 字段级权限（敏感字段隐藏/脱敏） | P1 | 3.8 | `field_permission` + 统一 `FieldPermissionFilter` |
| FR-SEC-10 | 母子公司权限：集团统一管控/子公司自治可配 | P0 | 3.1.6、3.5.5、3.6.6 | 角色模板 `is_template`/`inherit_mode`；`org_scope_id`；跨公司审批 `access_grant` |
| FR-SEC-11 | 角色继承与覆盖（子公司继承集团模板） | P1 | 3.5.4 | `role.parent_id` + `role_permission.effect ∈ {GRANT, REVOKE}` |
| FR-SEC-12 | SoD 职责分离互斥校验 | P1 | 3.9 | `sod_rule`/`sod_rule_permission`；`SoDCheckApi` 三处强制校验 |
| FR-SEC-13 | 权限变更全量审计留痕 | P0 | 3.11 | `PermissionChangedEvent` + `PermissionAuditApi`（audit 落库）+ `event_outbox` |
| FR-SEC-14 | 数据权限申请/授权/回收流程 | **P2** | 3.6.7（契约预留） | P1 只落 `access_grant` 与 `DataScopeApi`；流程在 P2 |
| FR-SEC-15 | 多组织上下文（请求级公司/组织标识） | P0 | 3.10 | `TenantCtx` + `TenantContextFilter` + `X-Org-Id` |
| FR-HR-06 | 组织架构（集团-子公司-部门无限级树，归属 iam） | P0 | 3.1 | 同 FR-SEC-05；组织变更发 `OrgChangedEvent` |
| FR-HR-07 | 员工档案（入转调离、任职、通讯录，归属 iam） | P0 | 3.2、3.13 | `employee`/`employee_sensitive`；通讯录只读能力 |
| FR-HR-08 | 岗位与任职管理（多组织兼任，归属 iam） | P1 | 3.2 | `position`/`org_position`/`user_org` |

> **追溯口径**：SRS 7.1 明确"优先级语义 ≠ 开发批次"，批次以 HLD 13.2 为准——上表 `P0` 优先级的需求在 P1 的 M2–M5 实现（P0 只交付工程地基与 common）。

### 2.3 五层分包与包结构

分包遵循 CONTEXT「五层分包」与 [architecture.md](agents/architecture.md)：跨模块只允许依赖 `api`；`api` 包以 `package-info.java` + `@NamedInterface("api")` 暴露。

```
com.eaio.iam
├── api/                                  # @NamedInterface("api")，唯一跨模块可见面
│   ├── package-info.java                 # @NamedInterface("api")
│   ├── AuthnApi.java  UserApi.java  EmployeeApi.java  OrgApi.java  OrgLifecycleApi.java
│   ├── RoleApi.java   PermissionApi.java  DataScopeApi.java  SoDCheckApi.java
│   ├── TenantCtxProvider.java
│   └── dto/  LoginCmd  TokenDTO  RefreshCmd  MfaEnrollDTO  UserDTO  UserSaveCmd  UserQuery
│             UserBriefDTO  EmployeeDTO  EmployeeSaveCmd  OrgNodeDTO  OrgTreeDTO  OrgSaveCmd
│             OrgMoveCmd  OrgLifecycleDTO  OrgDeregisterPreviewDTO  RoleDTO  RoleSaveCmd
│             RoleAssignCmd  PermissionDTO  MenuNodeDTO  DataScopeDTO  FieldPermissionDTO
│             DataScopeRuleDTO  DataScopeRuleSaveCmd  SoDCheckResultDTO  SoDConflictDTO
│             SoDRuleDTO  TenantContext  PositionDTO  OrgPositionDTO  AccessGrantDTO
├── application/                          # 用例编排、事务边界、事件发布
│   ├── auth/   AuthnService  TokenService  MfaService  CaptchaService
│   │            SsoService  LdapAuthService  LdapSyncJob  SessionAdminService
│   ├── org/    OrgService  OrgClosureService  OrgLifecycleService  OrgTreeCache
│   ├── person/ UserService  EmployeeService  PositionService  ContactService
│   ├── authz/  RoleService  PermissionService  PermissionResolver  RoleInheritanceResolver
│   │            DataScopeService  FieldPermissionService  AbacEngine  AbacAttributeResolver
│   │            SodService  AccessGrantService  PermissionSnapshotService
│   ├── context/ TenantContextFilter  DefaultTenantCtxProvider  TenantContextTaskDecorator
│   └── event/   DomainEventPublisher  OutboxPublisher  EventIdempotencyGuard
├── domain/                               # 实体、值对象、领域服务、仓储接口
│   ├── org/    OrgNode  OrgNodePath  OrgLifecycle(枚举+状态机)  Position  OrgPosition
│   ├── person/ User  UserOrg  Employee  EmployeeSensitive  PasswordPolicy
│   ├── authn/  AuthSession  AuthRefreshToken  LoginAttempt  MfaRecoveryCode
│   │            SsoConfig  LdapConfig  PasswordHistory
│   ├── authz/  Role  Permission  RolePermission  UserRole  DataScopeRule  DataScopeResource
│   │            FieldPermission  AbacPolicy  SodRule  SodRulePermission  SodExemption  AccessGrant
│   ├── context/ TenantContext(record)  TenantCtxProviderContract(内部约束)
│   └── repository/ *Repository（接口，实现在 infrastructure）
├── infrastructure/
│   ├── persistence/  mapper/*Mapper.java  MybatisPlusIamConfig  DataScopeSqlHandler
│   │                 TypeHandler(OrgLifecycleTypeHandler …)  AuditMetaObjectHandler
│   ├── security/     SecurityConfig  OAuth2AuthorizationServerConfig  JwtKeyProvider
│   │                 JwtTokenCustomizer  PasswordEncoderConfig  MfaTotpVerifier
│   │                 IamAuthenticationHandlers(失败恒 200)
│   ├── web/          AuthController  UserController  OrgController  PositionController
│   │                 EmployeeController  RoleController  PermissionController
│   │                 DataScopeController  FieldPermissionController  AbacController
│   │                 SodController  SsoConfigController  LdapConfigController
│   │                 SessionController  ContactController  EventController
│   ├── cache/        PermissionSnapshotCache  CaffeineIamCacheConfig
│   ├── crypto/       FieldCipher(AES-GCM)  KeyRing(env/参数中心)  Argon2Support
│   └── integration/  PlatformParamAdapter  PlatformDictAdapter  PlatformFileAdapter
│                     PlatformSchedulerAdapter  AuditAuditPort(可选，M4)  PlatformOrgContextAdapter
└── events/            UserRoleChangedEvent  OrgChangedEvent  PermissionChangedEvent
                       OrgLifecycleChangedEvent  EmployeeChangedEvent
```

**模块声明（`com.eaio.iam.package-info.java`）**：

```java
@org.springframework.modulith.ApplicationModule(
        displayName = "iam",
        allowedDependencies = {"platform::api"})   // M4 追加 "audit::api"
package com.eaio.iam;
```

> **Maven 依赖**：`e-aio-iam` 依赖 `e-aio-common`（compile）、`e-aio-platform`（compile，只经 `com.eaio.platform.api` 使用）；`e-aio-app` 依赖 `e-aio-iam`（装配）。`e-aio-audit` 在 M4 以 `optional` 加入。**platform 不得反向依赖 iam**（见 2.4.3）。

### 2.4 依赖方向与契约

#### 2.4.1 依赖矩阵（本册落地口径，对齐 HLD 11.1）

| 方向 | 允许 | 形式 |
|---|---|---|
| iam → platform | ✅ | `ParamApi` / `DictApi` / `CacheApi` / `FileApi` / `SchedulerApi`（`com.eaio.platform.api`） |
| iam → audit（M2–M3） | ⛔ | 零编译期依赖，只发事件 + 结构化日志 |
| iam → audit（M4+） | ✅（可选） | `<optional>true</optional>` 依赖 + `ObjectProvider<PermissionAuditApi>`；缺席降级 |
| 业务模块 → iam | ✅ | `AuthnApi`/`UserApi`/`EmployeeApi`/`OrgApi`/`OrgLifecycleApi`/`RoleApi`/`PermissionApi`/`DataScopeApi`/`SoDCheckApi`/`TenantCtxProvider` |
| platform → iam | ⛔ | 不得反向依赖；platform 需要组织上下文时经自有端口（2.4.3） |
| workflow/approval/oa/… → iam | ✅ | 只依赖 `api` 包 |
| iam → `common` | ✅ | `Result`/`PageResult`/`ErrorCode`/`BusinessException`/`IdGenerator`/`JsonUtils`/`DateUtils`/`StringUtils`/`SensitiveUtils`/`ConvertUtils` |

#### 2.4.2 契约面（`com.eaio.iam.api` 冻结清单，方法签名见 5.4）

`AuthnApi`、`UserApi`、`EmployeeApi`、`OrgApi`、`OrgLifecycleApi`、`RoleApi`、`PermissionApi`、`DataScopeApi`、`SoDCheckApi`、`TenantCtxProvider` 共 10 个接口；`PermissionAuditApi` 属 audit 侧契约（见 2.4.3）。

#### 2.4.3 环打破：platform 需要"当前组织上下文"怎么办

**问题**：platform 的 `FileApi.upload` 要打 `org_id` 标签、`SchedulerApi` 的任务可能要以组织身份运行，但 HLD 11.1 的依赖矩阵没有 `platform → iam`；而 `TenantCtx` 的实现只能落在 iam（组织挂载关系、超管判定都在 `eaio_iam` 里，common 不得含业务逻辑）。

**主方案（与 P1 批次总册 3.1 一致）——"接口在 iam、platform 可选注入"**：

1. `TenantCtx`（record，`com.eaio.iam.domain.context`）、`TenantCtxProvider`（**interface**，`com.eaio.iam.api`，`@NamedInterface("api")`）、`DefaultTenantCtxProvider`（`com.eaio.iam.application.context`）——批次总册 A3 断言：实现类不得出现在 `api` 包。
2. platform 侧以 **`<optional>true</optional>`** 依赖 `e-aio-iam`（**只允许用 `com.eaio.iam.api`**），注入点写成 `ObjectProvider<TenantCtxProvider>`：

   ```java
   // platform 侧（示例：文件上传打组织标签）
   private final ObjectProvider<TenantCtxProvider> tenantCtx;   // 可缺席，不抛 NoSuchBeanDefinitionException
   Long orgId = tenantCtx.getIfAvailable() == null
           ? null                                               // 缺席降级：落 org_id = NULL（系统上下文）
           : tenantCtx.getObject().currentOrgId().orElse(null);
   ```
3. **缺席降级语义**：provider 不存在时 `currentOrgId()` 视作 `empty`；文件落 `org_id = NULL` 并记 WARN；任务的 `runAs` 不可用（`SchedulerApi` 拒绝需要组织身份的任务注册，返回 `20103` 类错误）。
4. **环的合法性**：`iam → platform`（正常依赖，`platform::api`）+ `platform → iam::api`（可选依赖）构成**模块级双向可见**。批次总册 3.1 已裁决该形态成立（"接口在上游模块、实现依赖由上上游提供"）；本册补充一条**机器判据**：M1 冻结 `iam.api` 后立即执行 `ApplicationModules.verify()`——
   - 若 `verify()` 通过（Modulith 允许显式 `allowedDependencies` 的双向 `api` 依赖），主方案即为最终方案，platform 无需任何额外端口；
   - 若 `verify()` 报 `iam -> platform -> iam` 环（Modulith 的环检测触发），则启用**备选方案 B**：platform 侧改声明自有端口 `com.eaio.platform.api.OrgContextPort`（`Optional<Long> currentOrgId()` / `Long requireOrgId()`），由 iam 提供 `PlatformOrgContextAdapter` 实现（依赖方向 iam → platform，零环）。
5. **两条路径的取舍**：主方案省一个端口与适配器，但依赖 Modulith 的环容忍度（需 M1 实测，**不得靠假设**）；备选 B 多两个类型与一层间接，但**结构上不可能成环**，且 platform 的替换/裁剪（NFR-EXT-01）不受 iam 影响。判据是 M1 实测结果，不是偏好。
6. **禁止**：platform 通过反射/bean 名查找 iam 的实现 Bean（运行期失败不可测，被否决）；platform 注入 iam 的非 `api` 类型（ArchUnit 会红）。

**audit 侧同构处理**：`PermissionAuditApi` 定义在 `com.eaio.audit.api`（audit 落库，iam 不自建审计表）；iam 在 M4 加 `optional` 依赖并 `ObjectProvider<PermissionAuditApi>` 可选注入。M2–M3 期间 iam 只发布 `PermissionChangedEvent`（`AFTER_COMMIT`）+ 结构化日志（`event=PERMISSION_CHANGE`），audit 上线后由 outbox 补投（3.11.4）。Modulith 的 `allowedDependencies` 需模块存在才能声明，故 `"audit::api"` 在 M4 随 `e-aio-audit` 落地时追加。

#### 2.4.4 上游冲突裁决（本册取指令口径，评审需确认）

| # | 冲突点 | 上游写法 | 本册裁决 | 理由/影响 |
|---|---|---|---|---|
| I-1 | 审计列名 | P0 册 4.4 `BaseDO`、P1 platform 册裁决 C-1 用 `create_by/create_time/update_by/update_time` | **采用 `created_at/created_by/updated_at/updated_by`** | 指令冻结；语义同词根。代价：与 `common.persistence.BaseDO`（P1 platform 册 2.7 规划）字段名不一致，需在 P1 收口时统一（列入 7.6 遗留 L-1） |
| I-2 | 组织生命周期枚举 | platform 册裁决 C-2 四态 `PREPARING/RUNNING/CANCELLED/ARCHIVED`；HLD 5.2 中文"筹备→运营→注销→归档" | **采用 `PREPARING/ACTIVE/DEREGISTERED/ARCHIVED`** | 指令冻结；语义与 HLD 一致，仅英文枚举名不同 |
| I-3 | 数据范围枚举 | platform 册裁决 C-3 六值 `SELF/DEPT/ORG/ORG_AND_SUB/ALL/CUSTOM` | **采用七值，增 `DEPT_AND_SUB`** | 指令冻结；`DEPT_AND_SUB` 是"本部门及下级部门"必需值，缺省会导致部门层级授权退化为 `DEPT` |
| I-4 | 表名形态 | 本册原始指令为 snake_case **复数**（`users`/`org_nodes`/`auth_sessions`）；批次总册 3.5 与 P1 platform 册裁决 C-13/C-14 为**单数** | **采用 snake_case 单数、模块内无前缀**（`org_node`、`role`、`auth_session`）；**用户主档例外用 `sys_user`**（PG 保留字 `user`） | 批次一致优先（两册并存不得漂移）；`sys_user` 是保留字硬约束下的必有例外。若评审要求复数，改名是机械操作（唯一真源在 4.3 DDL） |
| I-5 | 认证形态 | platform 册裁决 C-9：M2–M5 只做资源服务器 JWT 校验 + 自建登录端点，OAuth2 Authorization Server 留 P3 | **采用同进程自托管 OAuth2 Authorization Server**（M2–M5 落地） | 指令与 HLD 5.2 一致（HLD 优先于分册裁决）；代价：M2 需多接 `spring-security-oauth2-authorization-server`，见 5.6 |
| I-6 | `TenantCtx` 归属 | platform 册 2.7：`TenantCtx` + `TenantContextHolder` 归 `com.eaio.common.context`（common 承载、iam 填充） | **接口 `TenantCtxProvider` 在 `com.eaio.iam.api`、实现在 iam** | 指令冻结；`TenantCtx` 需要读 `user_org`/`org_node`（业务数据，common 不得含业务逻辑），放 common 会让 common 反向依赖业务语义。环打破见 2.4.3 |
| I-7 | 协议端点方法与恒 200 | ADR-0001 / api-conventions：全部接口 POST + JSON、HTTP 恒 200 | **业务接口不变；OAuth2/SAML2 协议端点按协议为 GET/302 与 `application/x-www-form-urlencoded`** | 协议强制，无法改写。需在 P1 评审把「协议端点」补登记为 ADR-0001 的第三类例外（与"文件上传下载"并列），列入 7.6 遗留 L-2 |
| I-8 | `PermissionAuditApi` 归属 | 指令列在 `com.eaio.iam.api` 清单下，同时要求"audit 落库、iam 不自建审计表" | **接口放 `com.eaio.audit.api`**（audit 侧契约），iam 可选注入 | 若放 iam 则 audit 必须依赖 iam 才能实现，且 iam 将被迫自建审计落库路径；两处措辞冲突时取"audit 落库"语义。若评审要求归属 iam，等价改法是接口放 `com.eaio.iam.api` + audit 侧适配器（依赖方向 audit → iam） |
| I-9 | iam 错误码具体号 | P1 platform 册 4.7 已按 `21100–21146` 分配 iam 错误码；批次总册 6.1 只登记段 `21000–21999` | **本册按指令采用 `21001–21092` 号表**（表 7-1），platform 册 4.7 的 21100 段**建议作废或平移**；7.1 附注给出逐条映射 | 号段的**段**由批次总册冻结、**号**冲突时以模块分册（本册）为 iam 唯一号源；同段内两套号并存会让前端与文档双写。代价：platform 册需一次小修订 |
| I-10 | 数据权限是否允许跨 Schema 谓词 | P1 platform 册 C-22：**不跨 Schema**，只生成 `orgColumn IN (:orgIds)`，超 2000 报 `21120` | **分档处理**：≤2000 内联 `= ANY(ARRAY[...])`（不跨 Schema）；2001–20000 退化为 `EXISTS (SELECT 1 FROM eaio_iam.org_node_path …)`（**跨 Schema 只读谓词例外**，需评审登记）；>20000 fail-closed `1=0` + 告警（错误码 `21037`） | 指令要求"大 IN 退化 EXISTS 子查询"，parent 同步亦确认；纯内联在万级组织下 SQL 文本与解析成本不可控。例外仅限 iam 注入的谓词、只读、单表、走 `idx_org_node_path_descendant`，见 3.6.4 |
| I-11 | 口令哈希算法 | P1 platform 册 4.8 写 `bcrypt` 校验 | **Argon2id 为默认**（`{argon2}` 前缀），BCrypt 作为兼容升级位（`{bcrypt}` 登录时透明 rehash） | 指令冻结；SRS NFR-SEC-02 只要求"加密存储"，算法以指令为准 |
| I-12 | 逻辑删除列类型 | P1 platform 册 5.1.6：布尔用 `SMALLINT`（0/1），不用 PG `boolean` | **采用 `BOOLEAN NOT NULL DEFAULT false`**（部分唯一索引用 `WHERE deleted = false`） | 指令冻结；PG 原生类型语义清晰。代价：与 platform 册不一致，需在 P1 收口时二选一并同步 `database.md`（7.6 L-1） |
| I-13 | 迁移脚本首个文件名 | 批次总册 6.3 表登记 iam 为 `V1__init.sql` | **`V1__baseline.sql`**（与 platform 的 `V1__baseline.sql` 命名一致，且 V1 只建 Schema 不建业务表，见 4.4） | 命名一致性；内容约束沿用 ADR-0002 第 4 条与 platform 基线脚本的注释头 |
| I-14 | SSO 交付范围 | P1 platform 册 C-21：M2–M5 只交付 OIDC + LDAP，SAML2 延后 | **SAML2 + OIDC 都在本册交付范围**（指令与 FR-SEC-03），但**分期**：OIDC（M3）→ LDAP（M4）→ SAML2（M5，需真实 IdP/证书环境验证） | 指令冻结；SAML2 的验证环境成本真实存在，故分期而非砍掉，验收判据见 6.2 IT-SSO-03 |

### 2.5 认证态到授权态的请求全链路总览

```
① POST /api/iam/auth/Login（或 /oauth2/token、SSO 回调）
   └─ AuthController → AuthnService.login
      ├─ 验证码校验（ParamApi: iam.login.captcha.enabled）
      ├─ 用户查询（username 或 mobile/email）→ 状态检查（21003）
      ├─ 失败计数检查（21002 锁定）→ Argon2id 校验 → 失败递增（login_attempt）
      ├─ MFA 判定（sys_user.mfa_enabled）→ 需要则返回 mfaChallengeId（不签令牌）
      ├─ 会话建立：auth_session + auth_refresh_token（refresh_token 哈希入库）
      └─ 令牌签发（RS256，claims 见 7.4）→ TokenDTO{accessToken, refreshToken, expiresIn, orgId}

② 后续业务请求：POST /api/<module>/<resource>/<Action>
   Authorization: Bearer <JWT>    X-Org-Id: <目标组织>（切换公司时）
   ├─ TraceIdFilter（P0，order=HIGHEST_PRECEDENCE）→ MDC.traceId
   ├─ IdempotencyFilter（P0，order=HIGHEST_PRECEDENCE+20；写接口键 eaio:{env}:idem:{hash}）
   ├─ Spring Security 过滤器链（iam SecurityConfig）
   │   ├─ BearerTokenAuthenticationFilter：JWT 验签（kid 轮换）+ exp 校验 → JwtAuthenticationToken
   │   ├─ 会话校验（Redis 索引 eaio:{env}:iam:session:{sid}）：已撤销→10401；org 上下文非法→21085
   │   └─ TenantContextFilter（本册新增）：解析 org_id（claim + X-Org-Id）→ 校验 user_org 挂载 → TenantCtx
   │        + MDC{userId, orgId}
   ├─ Controller（@PreAuthorize("hasAuthority('iam:user:list')")）
   │   └─ AuthorizationManager：PermissionApi.check(userId, code)（读权限快照，4.7）
   ├─ 服务层：数据权限由 DataPermissionInterceptor 在 SQL 层注入（3.6）
   ├─ 返回前：FieldPermissionFilter 按字段权限隐藏/脱敏（3.8）→ @Sensitive 静态脱敏 → Result 包装
   └─ 请求结束：TenantContextFilter finally 清理 TenantCtx 与 MDC（防线程复用串号）
```

**关键不变量**：

- 认证失败恒 HTTP 200（ADR-0001）：登录凭据错 → `21001`；受保护端点缺/失效令牌 → `10401`；无权限 → `10403`。前端只按 `Result.code` 跳登录。
- **任何数据访问都必须在 `TenantCtx` 有值的前提下发生**（HLD 8.2）：无上下文的请求要么是白名单端点（登录/验证码/JWKS/健康检查），要么被 `TenantContextFilter` 拒绝。
- 权限裁决不发生在 Controller 之前的"路由层"，而在方法级 `AuthorizationManager` + SQL 改写 + 序列化前过滤三层，缺一层即视为缺陷（6.4 有对应测试）。

### 2.6 非目标

1. **不做多租户（SaaS）**：组织树是"一个集团内部的公司层级"，不是租户隔离（项目三原则：非 SaaS）。
2. **不做 OAuth2 第三方客户端管理平台**：只做同进程 AS + 自有前端与内部服务账号；外部系统接入的客户端管理留 P3 `integration`。
3. **不做短信/邮件通道本体**：MFA 短信、通知经 platform（`NoticeApi`）与 P3 `integration`；本册只实现 TOTP。
4. **不做字段级加密的动态密钥服务（KMS）**：P1 密钥来自环境变量/参数中心（3.8.4、4.6.4），KMS/HSM 留后续评估（SRS NFR-SEC-02 的"密钥管理"在 P1 为"外部注入 + 轮换"，见 7.6 L-3）。
5. **不做权限点的自动发现**：权限点由种子数据 + 管理页维护，不从 Controller 注解扫描生成（避免启动期反射扫描与"代码即权限"的隐式耦合）。

---

## 3. 详细设计

> 每节的固定六段：**设计目标 → 组件/类图 → 关键流程与时序 → 关键取舍与代价 → 边界与失败模式 → 测试点**。文中 `P1 新增` 指该资产在 P0 代码中不存在，由本册首次引入；`复用` 指直接使用既有代码或已选依赖，不自建。

### 3.1 组织模型（无限级组织树 / 闭包表 / 物化路径 / 生命周期状态机）

#### 3.1.1 设计目标

1. 支撑 HLD 4.1.1 与 SRS FR-SEC-05/10、FR-HR-06：**集团 → 子公司 → 部门 → 团队**无限级，单实例 10 万节点规模下"取子树 / 取祖先链 / 面包屑 / 移动子树"四类查询均走索引。
2. 组织是**权限的作用域**：数据范围规则、角色组织范围、跨公司审批都以组织节点为锚点（3.5–3.6）。因此组织的**移动**与**注销**必须触发权限侧的确定性动作，不能"只改树"。
3. 生命周期可审计、可预览：注销前能算出影响面（用户数、生效授权数、在线会话数、跨组织授权数）。

#### 3.1.2 组件与类图

```
OrgController ──► OrgService ──┬──► OrgClosureService（闭包表读写 + 一致性校验）
                               ├──► OrgLifecycleService（状态机 + 注销级联）
                               └──► OrgRepository（org_node）
                                        │
OrgTreeDTO / OrgNodeDTO ◄───────────────┘
        ▲
   缓存：Caffeine（按 orgId 的节点快照，TTL 60s）+ Redis 失效广播（OrgChangedEvent）
```

| 组件 | 职责 | 位置 |
|---|---|---|
| `OrgService` | 增删改查、move、树构建、`subtreeIds`/`pathOf` 契约实现 | `application/org` |
| `OrgClosureService` | 闭包表行维护、环检测、一致性校验（`OrgClosureAuditJob`） | `application/org` |
| `OrgLifecycleService` | 状态机、注销预览、级联副作用编排（同事务 + 事件） | `application/org` |
| `OrgNode` / `OrgNodePath` | 实体（`domain/org`），枚举 `NodeType`、`OrgLifecycle` | `domain/org` |
| `OrgNodeMapper` / `OrgNodePathMapper` | MyBatis-Plus Mapper | `infrastructure/persistence` |

#### 3.1.3 三种表示法并存（各自不可替代）

| 表示 | 列/表 | 用途 | 不用的理由 |
|---|---|---|---|
| 邻接（真源） | `org_node.parent_id` | 唯一写入真源；树编辑、同级排序 | 递归查询无法索引（PG 递归 CTE 每层一次索引探测，深树慢） |
| 物化路径 | `org_node.path`（`/1/5/23/`，含自身，首尾斜杠） | 面包屑、**子树前缀过滤**（`path LIKE '/1/5/%'`）、层级校验 | 移动子树要重写全部后代 `path`（写放大 O(子树)） |
| 闭包表 | `org_node_path(ancestor_id, descendant_id, depth)`，含自身行 `depth = 0` | **精确子树/祖先集合查询**（`WHERE ancestor_id = ?`），被数据权限改写与 SoD 作用域计算使用 | 每插入一行要写 O(深度) 行（深度通常 < 10，可接受） |

**`level` 与 `path` 的关系**：根节点 `level = 0`、`path = '/' || id || '/'`；子节点 `level = parent.level + 1`、`path = parent.path || id || '/'`。`path` 的段数 = `level + 1`（由 `ck_org_node_path_level` 近似校验：`path != '' AND level >= 0`，精确校验由一致性任务负责）。

**深度上限**：`eaio.iam.org.max-depth` 默认 12（超出返回 `21017`）。理由：闭包表行数与深度线性相关，且 12 层已覆盖"集团-区域-子公司-分公司-部门-科室-团队"的真实形态；限制是**可配的上限**而不是隐含假设。

#### 3.1.4 生命周期状态机（本册冻结）

```
        ┌───────────────┐  Activate   ┌──────────┐  Deregister  ┌───────────────┐  Archive  ┌──────────┐
新建 ──►│  PREPARING    │────────────►│  ACTIVE  │─────────────►│ DEREGISTERED  │──────────►│ ARCHIVED │
        │  筹备（可编辑） │             │ 运营     │              │ 注销（只读）    │           │ 归档（终态）│
        └───────┬───────┘             └────┬─────┘              └───────┬───────┘           └──────────┘
                │ Cancel（筹备取消）         │ Restore（仅超管，留审计）      │
                ▼                          ▼                            ▼
          DEREGISTERED ◄──────────────────┘                    不可再流转
```

| 流转 | 允许角色权限点 | 前置校验 | 副作用（同事务） |
|---|---|---|---|
| `PREPARING → ACTIVE` | `iam:org:lifecycle` | 上级为 ACTIVE；编码/名称非空 | 解冻该组织挂载与角色授权（`user_org.status: PENDING → ACTIVE`） |
| `PREPARING → DEREGISTERED` | 同上 | 无生效用户挂载 | 无 |
| `ACTIVE → DEREGISTERED` | 同上 | 无 ACTIVE 下级（先迁走或级联注销）；无在职用户（`21018`）或选择"强制注销"（超管 + 理由） | ① 组织置 `DEREGISTERED`；② `user_org` 涉及该组织 → `SUSPENDED`；③ `user_role.org_scope_id = 该组织` → `REVOKED`；④ `auth_session` 中 `org_id = 该组织` → 撤销（踢下线）；⑤ `access_grant` 涉及该组织 → `REVOKED`；⑥ 发布 `OrgLifecycleChangedEvent`（portal 生成"数据交接待办"，audit 留痕） |
| `DEREGISTERED → ACTIVE` | 仅 `super_admin` | 上级链全部 ACTIVE | 恢复挂载/授权需**人工重建**（不自动回滚 ③④，避免"注销后用旧授权复活"） |
| `DEREGISTERED → ARCHIVED` | `iam:org:lifecycle` | 无未完成交接待办 | 组织只读（`21013` 拒绝一切写操作，除归档/查询） |

> **禁止回退**：`ACTIVE → PREPARING` 不允许（状态机不提供该边，尝试即 `21013`）。理由：筹备态意味着"尚未生效"，回退会让已发生的授权/数据变成"历史上不存在"，破坏审计连续性。

#### 3.1.5 子树移动（move）算法与闭包表维护

**为什么不用 DB 触发器**：① move 需要"同树写锁"才能防止并发写偏斜（PG `READ COMMITTED` 下两条并发 move 可能各读到旧快照并写出交错的闭包行），触发器无法表达跨行集合的互斥；② 触发器逻辑无法被 `mvn test` 覆盖，只能靠 IT；③ 信创适配面多一层。**代价**：一致性靠应用层纪律 + 每日校验任务（3.1.7）。备选触发器版在 4.4 末尾给出（**未采用**，仅供小团队省事参考）。

单事务 7 步（`OrgService.move(OrgMoveCmd)`）：

```sql
-- ① 环检测：目标父节点是否在当前子树内（含自身）
SELECT 1 FROM eaio_iam.org_node_path
 WHERE ancestor_id = #{nodeId} AND descendant_id = #{newParentId};       -- 命中 → 21011
-- ② 锁：按 id 升序 FOR UPDATE 两个节点（固定顺序避免死锁）
SELECT id, path, level, lifecycle FROM eaio_iam.org_node
 WHERE id IN (#{nodeId}, #{newParentId}) ORDER BY id FOR UPDATE;
-- ③ 同树写锁（Redis，TTL 30s，失败即 21013"稍后重试"）：
--    SET eaio:{env}:lock:iam:org:tree <owner> NX PX 30000
-- ④ 子树规模校验（默认 5000，超限 21016）
SELECT count(*) FROM eaio_iam.org_node_path WHERE ancestor_id = #{nodeId};
-- ⑤ 重算 path / level（前缀替换；varchar_pattern_ops 索引可用）
UPDATE eaio_iam.org_node n
   SET path = #{newParentPath} || #{nodeId} || '/' || substring(n.path FROM length(#{oldPath}) + 1),
       level = n.level + #{levelDelta}, updated_at = now(), updated_by = #{operator}, version = n.version + 1
 WHERE n.path LIKE #{oldPath} || '%' AND n.deleted = false;
-- ⑥ 闭包表：先物化"外部祖先"与"子树"两个集合，再删旧行、插新行
WITH sub AS MATERIALIZED (SELECT descendant_id, depth FROM eaio_iam.org_node_path WHERE ancestor_id = #{nodeId}),
     sup AS MATERIALIZED (SELECT ancestor_id, depth FROM eaio_iam.org_node_path
                           WHERE descendant_id = #{nodeId} AND ancestor_id <> #{nodeId})
DELETE FROM eaio_iam.org_node_path p
 USING sub, sup WHERE p.ancestor_id = sup.ancestor_id AND p.descendant_id = sub.descendant_id;
INSERT INTO eaio_iam.org_node_path (ancestor_id, descendant_id, depth)
SELECT sup.ancestor_id, sub.descendant_id, sup.depth + 1 + sub.depth FROM sup CROSS JOIN sub;
-- ⑦ 组织编码/名称未变则不动其他列；发布 OrgChangedEvent（AFTER_COMMIT，含 oldPath/newPath）
```

**关键点**：`WITH … AS MATERIALIZED` 是 PG 12+ 语法，**必须显式 `MATERIALIZED`**——否则优化器可能把 `sub`/`sup` 内联后与 `DELETE` 的目标表产生读写顺序歧义。步骤 ⑤ 的 `LIKE` 依赖 `idx_org_node_path`（`varchar_pattern_ops`），否则非 C 排序规则下前缀 `LIKE` 退化为全表扫描。

#### 3.1.6 集团管控 / 子公司自治（FR-SEC-10）

| 管控维度 | 集团（GROUP 节点） | 子公司（COMPANY 节点） | 实现 |
|---|---|---|---|
| 组织编辑权 | 全树 | 仅本公司子树 | `iam:org:*` 权限 + 数据范围规则（3.6）；越界 `21014` |
| 角色模板 | 建 `is_template = true` 的角色（不可直接分配，`21023`） | 继承模板生成自有角色（`parent_id = 模板 id`，`inherit_mode = INHERIT_AND_OVERRIDE`） | 3.5.4 运行时继承求值，**不物化副本** |
| 权限点 | 全局唯一，仅集团可增删 | 只读 | `permission` 无 `org_id`：权限点是**全局元数据**；差异靠角色组合表达 |
| 数据范围 | 可授 `ALL` | 最多 `ORG_AND_SUB`（本公司及下级） | 分配时校验：非集团管理员不能授出超出自身范围的规则（`21014`）——**授权不可放大原则** |
| 公司上下文 | 可切换任意公司 | 仅可切到已挂载公司 | `TenantContextFilter` 校验 `user_org`（3.10.3） |

#### 3.1.7 边界与失败模式

| 失败模式 | 表现 | 处置 |
|---|---|---|
| 并发 move 同一子树 | 闭包表行交错/重复 | Redis 同树锁 + 校验任务兜底；锁获取失败返回 `21013`（提示稍后重试），不排队阻塞 |
| 闭包表与 `path` 漂移（人工改库、脚本异常） | 子树查询结果与界面不一致 | `OrgClosureAuditJob`（platform `SchedulerApi` 注册，每日 03:00）逐节点比对并输出差异清单；**只告警不自动修复**（自动修复会掩盖故障根因），提供 `OrgController.RebuildClosure`（`iam:org:admin`）显式重建 |
| 删除有下级的组织 | `21012` | 前端提示"先移动或删除下级"（`OrgApi.children` 预检） |
| 删除/注销时有在职人员 | `21018` | 预览接口 `deregisterPreview` 返回受影响用户清单，走"强制注销 + 理由"（超管） |
| 组织树接口被大集团拉爆 | 全树 JSON 数十 MB | `OrgApi.tree(rootId, depth)` **强制 depth**（默认 3，上限 6）；前端懒加载 `children(nodeId)`（3.12） |
| 归档组织被写 | `21013` | 所有写路径先校验 `lifecycle`（`OrgRepository` 统一断言，不在每个 Controller 重复写） |

#### 3.1.8 测试点

| 编号 | 层级 | 场景 | 断言 |
|---|---|---|---|
| UT-ORG-01 | 单测 | `OrgLifecycle` 状态机全边 | 允许边返回新状态；禁止边（含 `ACTIVE → PREPARING`）抛 `21013` |
| UT-ORG-02 | 单测 | `path`/`level` 计算 | 三层树 path = `/1/5/23/`，level = 2；段数 = level+1 |
| UT-ORG-03 | 单测 | `OrgTreeDTO` 构建 | depth 截断正确；不存在节点为空而非异常 |
| IT-ORG-01 | 集成（Testcontainers PG17） | move 跨子树（把 `/1/5/` 移到 `/1/9/`） | 后代 `path`/`level` 全量正确；闭包表行数 = Σ(子树大小 × 外部祖先数)；无孤儿行 |
| IT-ORG-02 | 集成 | move 成环（移到自身后代） | `21011`，且**闭包表无任何行被改动**（事务回滚验证） |
| IT-ORG-03 | 集成 | 并发 move 同一子树（2 线程） | 只有一个成功，另一个 `21013`；最终闭包表与 `path` 一致 |
| IT-ORG-04 | 集成 | `ACTIVE → DEREGISTERED` | `user_org`/`user_role`/`auth_session`/`access_grant` 四表状态齐变；发 `OrgLifecycleChangedEvent`（outbox 有记录） |
| IT-ORG-05 | 集成 | 一致性任务 | 人工写入一条错误闭包行 → 任务输出差异清单且**不改数据** |

### 3.2 用户、岗位与员工任职（多组织挂载）

#### 3.2.1 设计目标

1. 一个自然人 = 一条 `sys_user`；一个自然人在 N 个组织任职 = N 条 `user_org`（FR-HR-08 多组织兼任）；员工档案 `employee` 承载"入转调离"的**任职事实**，薪酬/考勤留在 P2 `hr`。
2. 与 hr 的边界必须可机械校验：**iam 拥有并唯一可写**的字段有白名单，hr 只能读（`EmployeeApi`）；薪酬类字段**不在 iam 建表**。
3. 高敏感字段（证件号、银行账号）与主档隔离（`employee_sensitive`），便于独立加密、独立字段权限、独立保留策略（SRS 6.1 高敏感档）。

#### 3.2.2 组件与类图

```
UserController ──► UserService ──┬──► UserRepository / UserOrgRepository
                                 ├──► PasswordPolicyService（策略 + 历史）
                                 └──► EmployeeService（同一自然人的任职视图）
EmployeeController ──► EmployeeService ──┬──► EmployeeRepository
PositionController ──► PositionService ──┴──► EmployeeSensitiveRepository（加解密经 common CryptUtils）
ContactController  ──► ContactService（只读聚合：sys_user × user_org × employee）
```

#### 3.2.3 关键流程时序（新增用户并挂载组织）

```
1. POST /iam/user/Add  (Idempotency-Key)
2. TenantContextFilter：解析 orgId（集团管理员切到子公司 ORG-B 建人）
3. @PreAuthorize("hasAuthority('iam:user:add')") → 数据范围校验：调用方必须对 ORG-B 有写范围
4. UserService.add(UserSaveCmd)：
   ├─ 用户名/手机/邮箱唯一性（uk_sys_user_username / uk_sys_user_mobile，冲突 21060/21061）
   ├─ 口令策略校验（长度/复杂度/不含用户名，violation → 21008）
   ├─ PasswordEncoder.encode（{argon2}）→ sys_user.password_hash + password_history 首条
   ├─ user_org 逐条插入（is_primary 唯一：已有 primary 时新 primary 顶替旧的，同事务 update）
   └─ employee 若带 employeeNo → 同事务插入（uk_employee_no 冲突 → 21068）
5. 事件：UserChangedEvent（本册新增）→ 权限快照失效 + （P2）hr 缓存失效
6. 返回 UserDTO（字段权限裁剪后）
```

#### 3.2.4 关键取舍与代价

| 取舍 | 选择 | 代价 / 理由 |
|---|---|---|
| 用户与员工是否合表 | **分表**（`sys_user` / `employee`） | 用户是"能登录的主体"（含服务账号，无员工档案），员工是"任职事实"（可能无登录账号，如外包/劳务）。合表会让"服务账号"或"无账号员工"出现半 Null 行 |
| 多组织挂载的表达 | `user_org`（用户 × 组织 × 岗位） | 用一张表同时表达"兼任"与"岗位"；查询"某组织所有人员"走 `idx_user_org_org` |
| 主组织唯一性 | 部分唯一索引 `uk_user_org_primary … WHERE is_primary AND deleted = false` | 由 DB 保证"至多一个主组织"，应用层 overwrite 时先清旧值（同事务），避免并发双主 |
| 岗位是否承载权限 | **不承载**：`position` 只描述职级/编制，权限只经角色获得 | 若岗位直挂权限，系统会出现"角色授权 + 岗位授权"两条路径，权限来源无法单一解释、审计无法定位；FR-HR-08 只要求"岗位与任职管理" |
| 敏感字段存储 | 独立表 `employee_sensitive`，AES-GCM（密钥环境变量注入） | 主档查询零解密开销；证件号查重走 `id_card_hash`（HMAC-SHA256，盐由密钥派生），既能唯一校验又不暴露明文 |
| 删除语义 | `sys_user.deleted = true` + `status = 'DISABLED'`；有未离职员工档案时拒绝（`21066`） | 逻辑删除保留历史引用（`created_by`/审批记录）；物理删除需评审（CONTEXT 数据约定） |

#### 3.2.5 边界与失败模式

| 场景 | 处置 |
|---|---|
| 用户名/手机号被逻辑删除的用户占用 | 部分唯一索引 `WHERE deleted = false` 允许复用；复用后历史审计仍指向旧 id（`created_by` 是 id 不是用户名） |
| 同一人被重复建号（LDAP 首登 + 人工建号） | `sys_user.source`/`external_id`（`uk_sys_user_source_external`）唯一；冲突时**拒绝自动建号**并提示绑定（`21054`） |
| 员工离职但账号未停用 | `EmployeeService.leave` 触发 `sys_user.status = DISABLED` + 会话撤销（同事务 + 事件）；反向不自动（停用账号不等于离职） |
| 手机号/邮箱被两个用户使用 | `uk_sys_user_mobile` / `uk_sys_user_email` 部分唯一索引；同一人换号走 `Up` |
| 服务账号被交互登录 | `sys_user.user_type = 'SERVICE'` 时 `AuthnApi.login` 拒绝（`21091`），仅允许客户端凭据（5.6.5） |
| 敏感字段解密失败（密钥轮换未完成） | 抛 `21092`（字段解密失败），**不返回空值**——空值会被误认为"未填写"，掩盖故障 |

#### 3.2.6 测试点

| 编号 | 场景 | 断言 |
|---|---|---|
| UT-USER-01 | 口令策略 | 8 条策略用例（长度/大小写/数字/特殊字符/含用户名/历史重复/过期/首登强制改密）逐一命中 `21008` 或通过 |
| UT-USER-02 | `{bcrypt}` 旧哈希登录 | 校验通过且 `password_hash` 被透明升级为 `{argon2}`（`password_history` 记一条） |
| IT-USER-01 | 并发新增同名用户 | 只有一个成功，另一个 `21060`（唯一索引兜底，不是应用层查重兜底） |
| IT-USER-02 | 双 primary 挂载 | 第二次 `is_primary = true` 后旧的自动置 false；DB 唯一索引不报错 |
| IT-USER-03 | 员工离职联动 | `employee.leave_date` 写入 + `sys_user.status = DISABLED` + 会话撤销 + 事件落 outbox |
| IT-USER-04 | 敏感字段读写 | 写入后直接查库是密文；解密 API 返回明文；密钥错误 → `21092`；`id_card_hash` 可查重 |
| IT-USER-05 | 逻辑删除后复用用户名 | 删除 A（username=`zhang`）→ 新建 `zhang` 成功；A 的历史记录仍可查 |

### 3.3 认证（账号密码/验证码/失败锁定/MFA/SSO/LDAP）

#### 3.3.1 设计目标

满足 SRS FR-SEC-01（账号密码 + 验证码 + 失败锁定）、FR-SEC-02（MFA TOTP）、FR-SEC-03（SSO SAML2/OIDC + LDAP）。**认证失败恒 HTTP 200**（ADR-0001），前端只按 `Result.code` 判定。认证结果必须落到可撤销的会话（3.4），而不是"签了令牌就不管"。

#### 3.3.2 认证方式与统一入口

| 方式 | 入口 | 凭据 | 落点 |
|---|---|---|---|
| 账号密码 | `POST /api/iam/auth/Login`（`AuthnApi.login`） | `username` + `password` + `captcha`（可选） | `AuthnService` |
| 账号密码（首次需 MFA） | 同上 → 返回 `mfaRequired=true` + `mfaChallengeId` | 第二步 `/iam/auth/MfaVerify` | `MfaService` |
| 刷新 | `POST /iam/auth/Refresh`（`AuthnApi.refresh`） | `refreshToken` | `TokenService` |
| OIDC（Relying Party） | `GET /api/iam/auth/oidc/callback`（协议端点，见 I-7） | `code` + `state` + `nonce` | `SsoService` |
| SAML2（SP 侧） | `POST /api/iam/auth/saml/acs` | `SAMLResponse` | `SsoService` |
| LDAP | 同账号密码入口，`AuthnService` 按 `sys_user.source = 'LDAP'` 分流 | DN + password | `LdapAuthService` |
| 客户端凭据（服务账号） | `POST /oauth2/token`（`grant_type=client_credentials`） | client id/secret | OAuth2 AS 内置 |

> **统一收敛**：所有方式最终都调用 `TokenService.issue(userId, orgId, amr)` 生成同一形状的令牌与会话（3.4），**不允许**某条路径自建令牌（否则撤销/审计会出现旁路）。

#### 3.3.3 账号密码登录流程（含验证码与锁定）

```
AuthnService.login(LoginCmd cmd):
 ① 参数与限流：RateLimiter（common，键 eaio:{env}:iam:login:ip:{ip}，默认 10/min）→ 超限 21002
 ② 用户定位：username → sys_user（含 deleted=false）；不存在 → **仍执行一次哑哈希校验**（防用户名枚举），返回 21001
 ③ 账号状态：DISABLED/PENDING → 21003；locked_until > now → 21002
 ④ 验证码：若 ParamApi(iam.login.captcha.enabled)=true 或该用户名近 3 次失败 →
      校验 Redis eaio:{env}:iam:captcha:{captchaId}（GETDEL，一次性）→ 失败 21004
 ⑤ 口令校验：PasswordEncoder.matches（DelegatingPasswordEncoder，{argon2} 默认 / {bcrypt} 旧值）
      ├─ 失败：sys_user.fail_count+1；达 eaio.iam.login.max-fail（默认 5）→ locked_until = now + lock-minutes（默认 15），
      │        写 login_attempt（append-only，含 ip/ua/traceId），返回 21001（**不区分"密码错"与"用户不存在"**）
      └─ 成功：fail_count=0、locked_until=null、last_login_at/ip 更新；若是 {bcrypt} → 透明 rehash 为 {argon2}
 ⑥ 口令过期：password_updated_at + eaio.iam.password.expire-days（默认 90）→ 21072（仍签发**受限**令牌：仅允许改密接口，claim `scope=pwd_change`）
 ⑦ MFA：sys_user.mfa_enabled → 不签发令牌，生成 mfaChallengeId（Redis，TTL 5min）→ 返回成功码 + mfaRequired=true
 ⑧ 组织上下文：默认取 user_org.is_primary；无挂载 → 拒绝登录（21085，提示先分配组织）
 ⑨ TokenService.issue(...) → TokenDTO；发 UserAuthenticatedEvent（audit 消费，含成败与 IP）→ 前端跳转
```

**枚举防护与锁定口径**：`21001` 同时覆盖"密码错"和"用户不存在"，且耗时对齐（哑哈希）；失败计数以 **`sys_user.fail_count` + `locked_until` 为真源**（跨实例一致、可审计），Redis 计数只用于 IP/用户名维度的**限流**（可丢，丢了也只是少拦几次，不会误锁）。

#### 3.3.4 MFA（TOTP，FR-SEC-02）

| 环节 | 设计 |
|---|---|
| 算法 | RFC 6238 TOTP：HMAC-SHA1、6 位、步长 30s、窗口 ±1（容忍时钟漂移 ≤ 30s） |
| 秘钥 | `sys_user.mfa_secret`（**加密存储**，AES-GCM，`mfa_secret_kid` 记录密钥版本）；`otpauth://` URI 由后端拼装、前端只渲染二维码，**明文秘钥不落日志** |
| 绑定（enroll） | 两步：`MfaEnroll` 生成秘钥并返回 URI（未激活）→ `MfaEnrollConfirm(code)` 校验一次码 → `mfa_enabled = true` + `mfa_enrolled_at`。**未确认不生效**（避免用户被自己锁死） |
| 校验 | `MfaVerify(mfaChallengeId, code)`：校验通过 → 消费 challenge（一次性）→ 签发令牌（`amr=["pwd","otp"]`）；失败 3 次作废 challenge（重新登录）→ `21007` |
| 恢复码 | 10 个一次性码，SHA-256 存 `mfa_recovery_code`；使用时标记 `used_at`；剩余 ≤ 2 时提示重生成 |
| 重置 | 仅 `iam:user:mfa` 权限（管理员）+ 强制写审计；**不提供"忘记秘钥即绕过"通道** |
| 令牌重放防护 | 同一 TOTP 码在同一用户 90s 内二次使用被拒（Redis `eaio:{env}:iam:totp:used:{userId}:{code}`） |

#### 3.3.5 SSO（OIDC / SAML2，SP 侧）

| 项 | OIDC | SAML2 |
|---|---|---|
| 实现 | `spring-boot-starter-oauth2-client`（授权码 + PKCE，`state`/`nonce` 双校验） | `spring-security-saml2-service-provider`（`RelyingPartyRegistration` 由 `sso_config` 动态装配） |
| 配置来源 | `sso_config`（`protocol='OIDC'`）：`issuer_uri`、`client_id`、`client_secret_cipher`+`kid`、`scopes`、`attribute_mapping` | `sso_config`（`protocol='SAML2'`）：`entity_id`、`metadata_xml`（IdP 元数据）、`acs_url`、SP 证书别名 |
| 多 IdP 路由 | 登录页按邮箱域名/组织选定 config（`sso_config.domain`/`org_id`）；未命中 → 本地登录 | 同 |
| 用户映射 | `external_id = sub`；命中 `sys_user.source='SSO' && external_id` → 登录；未命中 → **默认拒绝**（`21054`，`eaio.iam.idp.auto-create-user` 默认 false），管理员预建后绑定 | 同（`external_id = NameID`） |
| 属性同步 | `attribute_mapping`（JSON：`{"dept":"department","mobile":"phone_number"}`）→ 只同步到**非主档字段**（组织挂载由管理员确认），避免 IdP 覆盖本地组织模型 | 同 |
| 失败 | `21052`（断言/回调校验失败，含签名、时间窗、`InResponseTo` 不匹配）、`21053`（映射失败）、`21056`（IdP 不可用） | 同 |

#### 3.3.6 LDAP 适配（只读同步 + 首登建号）

| 项 | 设计 |
|---|---|
| 配置 | `ldap_config`：`url`（`ldaps://` 强制）、`base_dn`、`bind_dn`、`bind_password_cipher`+`kid`、`user_filter`（如 `(sAMAccountName={0})`）、`attribute_mapping`、`sync_mode ∈ {ON_LOGIN, SCHEDULED}`、`sync_cron` |
| 认证 | bind 校验：先用 `bind_dn` 搜索命中用户 DN，再用该 DN + 用户口令 bind（**不用管理员凭据代验口令**）；失败 → `21001`（对外不区分 LDAP 与本地） |
| 建号 | `ON_LOGIN`：命中且本地无账号 → 按 `eaio.iam.ldap.auto-create-user`（默认 false）决定；建号时 `sys_user.source='LDAP'`、`sys_user.status='PENDING'`（**待管理员激活**，防止目录里任何账号自动获得系统访问权） |
| 同步 | `SCHEDULED`：按 `sync_cron` 经 platform `SchedulerApi` 注册 `JobHandler(code="iam.ldap.sync")`（任务只按代码注册，platform 册 C-12 禁反射）；同步**只更新 `nickname`/`mobile`/`email` 与停用状态**，不删用户、不动组织挂载 |
| 连接失败 | `21051`（bind 失败）与 `21056`（连接/超时）分开：前者是配置或账号问题，后者是网络/服务问题 |
| 凭据加密 | `bind_password_cipher` 只出现在 `ldap_config`，**不写入日志、不进前端响应**（`@JsonIgnore` + 字段权限双保险） |

#### 3.3.7 关键取舍与代价

| 取舍 | 选择 | 代价 / 理由 |
|---|---|---|
| 认证实现位置 | iam 内自建 `AuthenticationProvider` + Spring Security，**不引入 CAS/Keycloak** | 自托管原则、单 Jar 部署；代价是 MFA/SSO 适配需自己写（已在本节写清） |
| 锁定判据 | DB `fail_count`/`locked_until` 为真源 | 多实例一致、重启不丢、可审计；代价是每次登录多 1 行 UPDATE（可接受，登录 QPS 远低于业务 QPS） |
| MFA 秘钥存储 | 加密存 `sys_user`（单因子） | 满足指令"`mfa_secret` 加密存储"；代价：多设备 TOTP 需扩表（P2 若需要，加 `user_mfa_factors`，不改 `sys_user` 语义） |
| LDAP 首登建号 | 默认关闭 + 建号后 `PENDING` | 目录里任意账号自动获得系统身份是严重越权面；代价是管理员多一步激活 |
| 密码策略来源 | platform `ParamApi`（`iam.password.*`），不在 iam 建策略表 | 复用 platform 参数中心（DRY）；代价：参数缺失时必须有**安全默认值**（代码内默认，不依赖配置） |
| SSO 属性同步范围 | 只同步非主档字段 | 防止 IdP 成为组织模型的第二写入方；代价：组织变更仍需管理员操作 |

#### 3.3.8 边界与失败模式

| 场景 | 处置 |
|---|---|
| 验证码服务 Redis 不可用 | 验证码校验 fail-closed（`21004` + ERROR 日志），**不降级为"跳过验证码"** |
| 登录接口被撞库 | IP 限流 + 用户名级锁定 + 失败审计；`login_attempt` 聚合告警（同一 IP 5 分钟 > 50 次失败） |
| 管理员误删 SSO 配置导致全员无法登录 | 删除前置校验：若该 config 是唯一 IdP 且存在 `source='SSO'` 用户 → 拒绝删除（`21050`），须先本地管理员可用 |
| IdP 重放 `SAMLResponse` | 校验 `InResponseTo` + `NotOnOrAfter` + 断言 id 一次性（Redis 去重，TTL 10min） |
| MFA challenge 被暴力尝试 | challenge 失败 3 次作废；同一用户的 challenge 全局并发上限 1（新的顶掉旧的） |

#### 3.3.9 测试点

| 编号 | 场景 | 断言 |
|---|---|---|
| UT-AUTH-01 | TOTP 校验 | RFC 6238 官方测试向量 + 窗口 ±1 通过、±2 拒绝 |
| UT-AUTH-02 | 口令编码器 | `{bcrypt}` 输入匹配成功且返回 upgraded hash；`{argon2}` 幂等 |
| UT-AUTH-03 | 锁定计数 | 第 5 次失败后 `locked_until` 生效、第 6 次返回 `21002`；成功登录清零 |
| UT-AUTH-04 | 验证码一次性 | 同一 `captchaId` 第二次校验失败（GETDEL 语义） |
| IT-AUTH-01 | 登录全路径（PG+Redis） | 正确口令 → `TokenDTO` 且 `auth_session` 有行；错误口令 → `21001` 且 `login_attempt` 有行、HTTP 200 |
| IT-AUTH-02 | 用户名枚举防护 | 不存在用户与错误口令的**响应码相同、耗时差 < 50ms** |
| IT-AUTH-03 | 口令过期 | 过期用户登录返回 `21072`，且签发的令牌只能访问改密端点（访问业务端点 → `10403`） |
| IT-AUTH-04 | MFA 绑定与校验 | 未确认不可用；确认后可登录；恢复码一次性；TOTP 重放被拒 |
| IT-SSO-01 | OIDC | Mock IDP（WireMock/内嵌）：`state`/`nonce` 不匹配 → `21052`；未绑定 → `21054` |
| IT-SSO-02 | LDAP | Testcontainers `osixia/openldap`：bind 成功建 `PENDING` 用户；`auto-create-user=false` 时拒绝（`21054`） |
| IT-SSO-03 | SAML2 | 内嵌 IdP 元数据 + 签名断言通过；重放断言被拒（M5 交付，见 I-14） |

### 3.4 令牌与会话（JWT 签发/刷新轮换/撤销/Redis 会话）

#### 3.4.1 设计目标

FR-SEC-04：JWT 短期 access + Redis 可撤销会话。三件事必须同时成立：**令牌可自校验**（无状态性能）、**会话可撤销**（登出/改密/踢人即时生效）、**权限变更即时生效**（不等令牌过期）。

#### 3.4.2 令牌模型

| 项 | 设计 |
|---|---|
| access token | JWT，RS256，TTL `eaio.iam.jwt.access-ttl` 默认 **30m**；claims 见 7.4；`kid` 头标识签名密钥 |
| refresh token | **不透明随机串**（256bit，`Base64URL`），**不落明文**：`auth_refresh_token.token_hash`（SHA-256）；TTL `eaio.iam.jwt.refresh-ttl` 默认 **14d** |
| 会话 | `auth_session`（逻辑会话，一设备一行）+ `auth_refresh_token`（该会话的令牌链，`family_id` 关联） |
| Redis 索引 | `eaio:{env}:iam:session:{sid}` → `{userId, orgId, status, ver, familyId}`，TTL = refresh TTL；`eaio:{env}:iam:session:user:{userId}`（Set，成员为 `sid`）用于"按用户踢下线" |
| 撤销 | ① 单会话：删 Redis 键 + `auth_session.status='REVOKED'`；② 全用户：`sys_user.token_version++`（JWT 的 `ver` 不匹配即失效，无需逐个删键） |
| 密钥 | `JwtKeyProvider`：从环境变量 `EAIO_IAM_JWT_KEY_<kid>`（PKCS#8 PEM）或参数中心（`is_encrypted` 项）加载，**启动期校验存在性，缺失即启动失败**（不得硬编码、不得有默认值）；JWKS 端点 `/oauth2/jwks` 暴露全部有效 `kid` |
| 轮换 | 新旧两把并存：新签发用 `active-kid`，校验接受所有未过期 `kid`；`eaio.iam.jwt.rotate-days` 默认 90；轮换公告后旧 kid 保留 ≥ access TTL + 时钟偏移 |
| 刷新轮换 | 每次 `Refresh` 签发新 refresh 并**立即作废旧的**（`used_at` + `rotated_to`）；若**已用过的 refresh 再次出现** → 判定令牌泄漏 → **撤销整个 `family_id` 会话**并写安全告警事件（`21006` + audit） |

#### 3.4.3 请求鉴权时序（为什么 30 分钟也够）

JWT 只携带稳定声明（`sub`/`org_id`/`sid`/`ver`/`amr`），**不携带权限码清单**。每次请求的权限判定读**权限快照缓存**（4.7），并以 `ver` 做失效判定：

```
请求 → JwtDecoder 验签（kid）+ exp/nbf 校验
     → 会话校验：Redis eaio:{env}:iam:session:{sid} 存在？（缺 → 10401；status=REVOKED → 10401）
     → 版本校验：claims.ver == sys_user.token_version？（不等 → 10401，覆盖"改密/全局踢下线/角色全量重算"）
     → TenantContextFilter（3.10）→ 权限快照（4.7）→ @PreAuthorize / 数据范围 / 字段权限
```

**代价**：每个受保护请求多一次 Redis GET（会话）+ 一次缓存读取（权限）。**收益**：撤销与权限变更**秒级生效**，且 JWT 体积稳定（不随角色数膨胀）。Redis 不可用时会话校验 fail-closed（`10401` + ERROR 日志）——**不放行**，理由同 P0 幂等策略（P0 册 3.2.5）。

#### 3.4.4 登出与撤销面

| 动作 | 影响范围 | 实现 |
|---|---|---|
| 登出（`Logout`） | 当前 `sid` | 删 Redis 会话键 + `auth_session.status='REVOKED'` + `revoked_reason='LOGOUT'` |
| 改密 | 该用户全部会话 | `token_version++` + 全 Set 撤销；**保留当前会话**（用户体验：改密后不掉线，但其他设备下线） |
| 管理员踢人（`SessionController.Revoke`） | 指定 `sid` | 同登出 + 审计（含操作人与理由） |
| 用户停用/离职 | 全部会话 | `token_version++`（在 `UserService.disable` 同事务） |
| 组织注销/挂载撤销 | 该组织上下文下全部会话 | 按 `org_id` 扫描会话索引（`eaio:{env}:iam:session:org:{orgId}` Set）批量撤销 |
| 密码泄露响应 | 全部会话 + 强制改密 | `must_change_password = true` + `token_version++` |
| 令牌自检（`Introspect`） | 单令牌 | OAuth2 AS 端点，**不查库**（只看签名/exp/会话索引） |

#### 3.4.5 关键取舍与代价

| 取舍 | 选择 | 代价 / 理由 |
|---|---|---|
| access token 用 JWT 而非不透明令牌 | JWT | 无状态自校验（2000 并发 NFR-PERF-01 下省一次 Redis）；代价：必须先查会话索引才能撤销 → 保留这一次 Redis GET（不接受"纯无状态 = 不可撤销"） |
| 权限是否入令牌 | **不入** | 权限变更即时生效、令牌体积稳定；代价：每请求读快照缓存（Caffeine L1 + Redis L2，4.7） |
| refresh 存哈希 | SHA-256（不加盐） | refresh 是 256bit 随机串，无需抗字典攻击；代价：库被读也不能直接冒用（还需绕过 hash） |
| 撤销粒度 | 会话 + 用户版本号双层 | 单会话撤销 O(1)、全用户撤销 O(1)；代价：需维护两个 Redis 结构（会话键 + 用户 Set），两者不一致时以**会话键为准**（更严格） |
| OAuth2 AS 形态 | 同进程自托管（I-5） | 满足 HLD 5.2；代价：多接一个 starter、`/oauth2/*` 端点属协议例外（I-7） |

#### 3.4.6 边界与失败模式

| 场景 | 处置 |
|---|---|
| Redis 整体不可用 | 会话校验 fail-closed → 全站 `10401`；告警；**不提供旁路开关**（放行等于撤销失效） |
| 服务器时钟漂移 | `JwtDecoder` 允许 `eaio.iam.jwt.clock-skew`（默认 60s）；TOTP 另算窗口 |
| 密钥轮换期间旧令牌 | 旧 `kid` 仍在校验集合内直到过期；删除 kid 前校验"无未过期令牌引用该 kid"（按 `iat` 推断，日志 WARN 兜底） |
| refresh 并发刷新（同一令牌两次） | 第二次命中"已用过" → 撤销 family（宁可误伤要求重新登录，不接受静默双发） |
| 会话数超限 | `eaio.iam.session.max-per-user` 默认 5，超限**踢最久未用**的会话（`21073` 提示为可读原因，不阻断登录） |
| 令牌被盗用 | 无源 IP 绑定（移动端场景不友好）；靠轮换检测 + 短 TTL + 会话管理页人工撤销；不引入设备指纹（P3 评估） |

#### 3.4.7 测试点

| 编号 | 场景 | 断言 |
|---|---|---|
| UT-TOKEN-01 | JWT 签发/解析 | claims 齐全（7.4 表逐项）；`kid` 正确；篡改 payload 验签失败 |
| UT-TOKEN-02 | 刷新轮换 | 旧 refresh 二次使用 → family 全撤销 + `21006` |
| UT-TOKEN-03 | 版本判定 | `token_version` 变化后旧 access 立即 `10401` |
| IT-TOKEN-01 | 登出即失效 | 登出后旧 access 调业务接口 → `10401`（HTTP 200） |
| IT-TOKEN-02 | 改密保留当前会话 | 改密后当前 access 仍可用，另一 `sid` 的 access → `10401` |
| IT-TOKEN-03 | 密钥轮换 | 双 kid 并存时两代令牌均可校验；移除旧 kid 后旧令牌 `10401` |
| IT-TOKEN-04 | Redis 故障 | 停 Redis → 受保护接口 `10401` + ERROR 日志；登录接口 `10502`（幂等不可用）不误报成功 |
| IT-TOKEN-05 | 会话数上限 | 第 6 次登录踢掉最久未用会话，其余会话可用 |

### 3.5 授权模型（RBAC + 角色继承与覆盖 + 集团管控/子公司自治）

#### 3.5.1 设计目标

SRS FR-SEC-06（RBAC + 角色限定组织范围）、FR-SEC-11（继承与覆盖）、FR-SEC-10（集团管控/子公司自治）。产出物是**一个确定性的权限码集合**与**一个确定性的数据范围规则集**，供 `PermissionApi` 三处消费（`@PreAuthorize`、SQL 改写、字段裁剪）。

#### 3.5.2 模型组件

```
permission（权限点，全局元数据，树形：MENU/BUTTON/API/DATA）
role（角色）── parent_id ──► 父角色（单亲继承，链深 ≤ 5）
  ├─ is_template     集团模板（不可直接授人，21023）
  ├─ inherit_mode    INHERIT_ONLY | INHERIT_AND_OVERRIDE | NO_INHERIT
  └─ org_scope_id    角色生效的组织范围（NULL = 全局）
role_permission（role_id, permission_id, effect ∈ {GRANT, REVOKE}）  ← DENY/覆盖位
user_role（user_id, role_id, org_scope_id, status ∈ {ACTIVE, REVOKED}, start_at, end_at）
```

**求值规则（`RoleInheritanceResolver`，确定性、可重放）**：

```
resolve(userId, orgId):
  1. 取 user_role：user_id = ? AND status = 'ACTIVE'
       AND (org_scope_id IS NULL OR org_scope_id ∈ subtreeIds(orgId) ∪ ancestors(orgId))
       AND (start_at IS NULL OR start_at <= now) AND (end_at IS NULL OR end_at > now)
  2. 每个角色沿 parent_id 上溯（深度 ≤ 5，超过 → 21025；成环 → 21025）
       - INHERIT_ONLY：只贡献权限，不贡献 org_scope 限制
       - INHERIT_AND_OVERRIDE：子角色可对父角色权限 REVOKE（覆盖）
       - NO_INHERIT：不上溯
  3. 权限码集合 = ∪ GRANT − ∪ REVOKE（REVOKE 优先，跨层级也优先：安全优先）
  4. 若 sys_user.super_admin = true → 直接返回哨兵集合 SUPER_ADMIN（不查库）
```

**超管与系统账号**：`sys_user.super_admin = true` 是**唯一绕过标记**：绕过权限点校验、数据范围与字段权限，但**不绕过审计**（每个动作记 `actor=super_admin`）。服务账号（`user_type='SERVICE'`）**不自动绕过**：其权限同样来自角色，只额外禁止交互登录（`21091`）。

#### 3.5.3 权限判定接入点

| 层 | 机制 | 说明 |
|---|---|---|
| 方法级 | `@PreAuthorize("hasAuthority('iam:user:list')")`（platform 册 C-19 同款口径） | `IamPermissionEvaluator` 把 `hasAuthority` 委托给 `PermissionApi.check(userId, code)`（读快照）；不把权限写进 `SecurityContext` 的 authorities（避免与令牌解耦目标冲突） |
| REST 契约层 | 权限码字符串与端点的一一对应表（5.2） | 前端 `v-hasPermi` 用同一份码（3.12）；**后端永远二次校验** |
| 数据层 | 3.6 SQL 改写 | 只认 `resource_code`，与权限点解耦 |
| 字段层 | 3.8 序列化前裁剪 | 同上 |

#### 3.5.4 角色继承与集团模板下发（FR-SEC-11、FR-SEC-10）

- **继承是运行时求值，不物化副本**：子公司角色 `parent_id = 集团模板`，模板增删权限立即对子公司生效（通过 `PermissionChangedEvent` 失效快照）。
- **下发动作** = 在子公司创建角色并指向模板 + 选 `inherit_mode`；覆盖通过 `role_permission.effect = 'REVOKE'` 实现（例如：模板有 `finance:voucher:approve`，某子公司显式 REVOKE）。
- **代价**：求值要上溯父链（≤5 层，且缓存在快照里）；**收益**：集团改一次模板即全集团生效，无需后台"同步任务"与副本漂移治理。
- **模板不可直授**（`is_template = true` 的角色出现在 `user_role` → `21023`），避免"某子公司直接拿到集团模板"的越权捷径。

#### 3.5.5 组织范围限定（子公司自治）

| 场景 | 规则 |
|---|---|
| 集团管理员 | 角色 `org_scope_id = NULL`（全局）；可授 `ALL` 数据范围 |
| 子公司管理员 | 角色 `org_scope_id = 本公司 id`；只能管理本公司子树（`iam:user:*` 等权限 + `ORG_AND_SUB` 数据范围） |
| 授权不可放大 | 分配角色/规则时校验：被授出的权限码 ⊆ 分配者自身权限码，且被授出的数据范围 ⊆ 分配者范围（否则 `21014`）。**这是防止"子公司管理员给自己提权"的关键闸门** |
| 岗位/组织变更 | 只影响 `user_org`，进而影响"哪些角色在该组织生效"（3.5.2 步骤 1 的 `org_scope_id` 匹配），不影响权限码定义 |
| SoD 副作用 | 角色分配是 SoD 校验点之一（3.9.3） |

#### 3.5.6 关键取舍与代价

| 取舍 | 选择 | 代价 / 理由 |
|---|---|---|
| 权限模型 | RBAC 为主 + ABAC 补充（3.7） | 纯 ABAC 需要为每个动作写策略，运维成本高；纯 RBAC 无法表达"金额 ≤ 1 万"这类属性条件 |
| 权限码形态 | 字符串 `iam:user:list`（非枚举类） | 跨模块引用不需要依赖 iam 的类型（platform 册 C-19）；代价：拼写错误无法编译期发现 → 由 A2/种子数据与 `permission` 表外键式校验兜底（分配不存在的码 → `21030`） |
| 角色继承 | 单亲（`parent_id`）+ 深度上限 5 | 多亲继承会让"覆盖"语义变成非确定性（菱形继承冲突）；代价：需要多模板组合时要建组合角色 |
| 覆盖语义 | `REVOKE` 全局优先 | 安全优先（下级不能通过继承绕过显式禁止）；代价：想要"某子公司额外放开"必须改模板或另建角色，不能用 REVOKE 反向 |
| 快照 vs 实时求值 | 快照（4.7） | 实时求值每请求要查 4 张表（`user_role`+`role`+`role_permission`+`permission`），P95 无法达标；代价：变更生效有 ≤30s 窗口，且 SoD/写侧字段权限直读 DB 兜强一致 |

#### 3.5.7 边界与失败模式

| 场景 | 处置 |
|---|---|
| 角色继承成环 | 保存时检测（DFS）→ `21025`；DB 无法表达环约束，靠应用层 + 求值时深度上限双保险 |
| 删除被继承的角色 | `21021`（先解除子角色继承或改为独立角色） |
| 删除仍被分配的角色 | `21024`（先回收 `user_role`）；`21024` 与 `21021` 分开，前端提示不同处置 |
| 权限点被删除但角色仍引用 | 禁止删除有引用的权限点（`21034`）；`role_permission` 用外键保证引用完整性 |
| 菜单树为空（用户无任何 MENU 权限） | 返回空树 + 前端显示"无可用菜单"，**不返回 500**、不回退到全量菜单 |
| 权限码被前端伪造 | 前端只做展示；后端 `@PreAuthorize` + 数据范围 + 字段裁剪三层都独立判定（6.4 有测试） |

#### 3.5.8 测试点

| 编号 | 场景 | 断言 |
|---|---|---|
| UT-RBAC-01 | 继承求值 | 三层继承 + REVOKE 覆盖：结果 = (∪GRANT) − (∪REVOKE) |
| UT-RBAC-02 | 深度上限 | 6 层继承 → `21025`，不进入无限循环 |
| UT-RBAC-03 | 组织范围匹配 | 角色 `org_scope_id = 子公司` 在集团上下文下**不生效**，在公司上下文下生效 |
| UT-RBAC-04 | 授权不可放大 | 分配者权限 ⊂ 被授权限 → `21014` |
| IT-RBAC-01 | 模板下发 | 改模板权限后子公司用户权限快照在 ≤30s 内变化（事件失效 + 缺失重建） |
| IT-RBAC-02 | 角色删除保护 | 被继承/被分配两类删除分别命中 `21021`/`21024` |
| IT-RBAC-03 | `menuTree` | 只返回有 MENU 权限的子树；父节点缺失时自动补链（不返回孤儿节点） |

### 3.6 数据行级权限（规则模型 + SQL 改写 + 跨公司审批 + 性能与索引）

> 本节落实 **P0 册 6.3 待细化第 4 条** 与 HLD 12 待细化第 3 项（数据权限过滤器实现细节 / SQL 改写方案）。

#### 3.6.1 设计目标

FR-SEC-08：按组织/部门/责任人过滤数据行。设计必须同时满足四条硬约束：

1. **默认拒绝**：没规则 ≠ 看全部；没规则 = 看不到（`1 = 0`）。
2. **不漏不改**：只改"登记过的资源表"，未登记的表**完全不注入**（否则元数据查询、字典查询会被误伤）。
3. **可解释**：为什么这人看不到这条数据，必须能回答（规则来源可追溯：哪条 `data_scope_rule`、哪个角色、哪个 `access_grant`）。
4. **性能可控**：2000 并发 / P95 < 500ms（NFR-PERF-02）下，改写不能把走索引的查询变成全表扫描。

#### 3.6.2 规则模型

```
data_scope_resource（资源登记表，白名单的唯一来源）
   resource_code  PK 语义唯一   如 oa:leave / crm:customer
   table_name                    物理表名（**只登记本模块自己的表**）
   org_column                    组织列名（如 org_id）
   owner_column                  责任人列名（如 owner_id / applicant_id，可空）
   dept_column                   部门列名（可空；无则 DEPT 范围用 org_column 的部门节点集合表达）
   enabled                       是否参与改写
   max_scope                      该资源允许的最大范围（ALL 资源可设 ALL，敏感资源设 ORG_AND_SUB）
data_scope_rule（规则，主体可选角色或用户；角色规则与用户规则取并集）
   subject_type ∈ {ROLE, USER}, subject_id
   resource_code, scope_type ∈ {SELF, DEPT, DEPT_AND_SUB, ORG, ORG_AND_SUB, ALL, CUSTOM}
   custom_org_ids TEXT（JSON 数组；仅 CUSTOM 用）
   effect ∈ {ALLOW, DENY}, priority INT
   valid_from / valid_to（可空，支持临时授权）
access_grant（显式授权：跨公司审批、临时数据范围、委托）
   grantee_user_id, grant_type ∈ {APPROVAL_CROSS_ORG, TEMP_DATA_SCOPE, DELEGATION}
   resource_code, business_id（可空 = 该资源全部）, org_ids TEXT
   valid_from, valid_to, granted_by, reason, status
```

**解析结果 DTO**（`DataScopeDTO`，5.3 给字段）：

```java
record DataScopeDTO(String resourceCode, String scopeType, String orgColumn, String ownerColumn,
                    String deptColumn, List<Long> orgIds, Long ownerId, boolean deny, String source) {}
```

#### 3.6.3 `PermissionApi.resolveDataScope(userId, resourceCode, orgId)` 合并算法

```
resolve(userId, resourceCode, orgId):
 0. resource = data_scope_resource[resourceCode]
    未登记 → 返回 EMPTY_NO_RULE（调用方不注入任何条件）；enabled = false → 同上
    已登记但用户无任何规则 → 返回 DENY_ALL（注入 1 = 0）
 1. ctxOrg = TenantCtx.orgId（必须非空；空 → 21084）
 2. rules = 生效规则集合：subject ∈ {该用户, 该用户在该组织生效的角色}
 3. 若任一 ALLOW 规则 scope_type = ALL 且未被 DENY 覆盖 → ALL（不注入）
 4. 逐条展开：
      SELF            → ownerColumn = userId（无 owner_column 的资源不允许配 SELF → 21035）
      DEPT            → orgIds += 当前组织节点所在"部门"节点的 id 集合（同人多部门 → 集合）
      DEPT_AND_SUB    → orgIds += subtreeIds(DEPT)（闭包表一次查询）
      ORG             → orgIds += {ctxOrg}
      ORG_AND_SUB     → orgIds += subtreeIds(ctxOrg)
      CUSTOM          → orgIds += custom_org_ids（解析后与调用方可见范围**求交**，防越权放大）
      ALL             → 不注入
 5. 并入有效 access_grant：grantee_user_id = userId AND resource_code ∈ {资源, '*'} AND now ∈ [valid_from, valid_to)
      AND (business_id IS NULL OR business_id = 当前业务主键)  → 追加 orgIds / 或对单条业务放开
 6. DENY 规则：对 orgIds 求差集；DENY + ALL 组合 → DENY_ALL
 7. 结果落 Caffeine（键 userId+resourceCode+orgId+ver，TTL 60s），并写 DEBUG 日志（规则来源清单）
```

**规则冲突**：同一主体同一资源的多条规则**取并集**（ALLOW ∪ ALLOW），DENY 优先（安全语义与 3.5 的 REVOKE 一致）；同优先级 ALLOW/DENY 冲突时告警（`21036`）但**按 DENY 执行**。

#### 3.6.4 SQL 改写（复用 MyBatis-Plus 内置拦截器，只写 Handler）

**不复写 JSqlParser**：MyBatis-Plus 3.5.17 自带 `com.baomidou.mybatisplus.extension.plugins.inner.DataPermissionInterceptor`，其 `beforeQuery` 与 `beforePrepare` 两处都会回调 `MultiDataPermissionHandler#getSqlSegment(Table table, Expression where, String mappedStatementId)`，因而 **SELECT / UPDATE / DELETE 统一覆盖**（`INSERT` 不注入——写入的越权防护在应用层校验 + `org_id` 由服务层写入，见"失败模式"）。本册只实现 Handler（唯一新增代码）并给出装配：

```java
@Bean
MybatisPlusInterceptor iamMybatisPlusInterceptor(DataScopeSqlHandler handler) {
    MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
    interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
    interceptor.addInnerInterceptor(new DataPermissionInterceptor(handler));   // 复用 MP 内置
    return interceptor;
}
```

`DataScopeSqlHandler#getSqlSegment` 的生成规则：

| 情形 | 注入片段（以 `t` 为别名示例） |
|---|---|
| 未登记资源 / 调用方非受管（超管、系统内部任务） | 返回 `null`（**不注入**） |
| `DENY_ALL` | `AND (1 = 0)`（PG 直接剪枝，不报错、不泄漏行数） |
| `ALL` | `null` |
| 组织范围（≤ `eaio.iam.data-scope.max-inline-ids` 默认 2000） | `AND (t.org_id = ANY(ARRAY[1,2,3]))` |
| 责任人 | `AND (t.owner_id = 1001)` |
| 两者并存 | `AND ((t.org_id = ANY(ARRAY[...])) OR (t.owner_id = 1001))` |
| 组织范围 2001–20000（**降级 EXISTS**） | `AND (EXISTS (SELECT 1 FROM eaio_iam.org_node_path p WHERE p.descendant_id = t.org_id AND p.ancestor_id = ANY(ARRAY[...上溯的共同祖先...])))` |
| 组织范围 > 20000 | `AND (1 = 0)` + ERROR 告警（`21037`），提示改用部门级授权或引入公司冗余列 |

**`EXISTS` 降级是第一类例外，必须登记**：它让业务模块的 SQL 出现一个**只读、单表、跨 Schema** 的子查询，与 CONTEXT「禁止跨 Schema 关联查询」字面冲突（见 2.4.4 I-10）。约束：① 仅 iam 注入，业务代码不得手写；② 只读 `eaio_iam.org_node_path`；③ 必须命中 `idx_org_node_path_descendant`；④ 每次使用写 DEBUG 日志（含行数估算）以便观测使用面。

**安全边界**：`getSqlSegment` 返回的是**拼接进 SQL 的字符串**，因此片段里**只能出现经 `Long` 白名单校验后 `toString()` 的数字**与固定列名（来自 `data_scope_resource` 登记值，不是请求参数）——**绝不允许**任何用户输入进入片段（NFR-SEC-07 防注入）。列名在装配时校验：`^[a-z][a-z0-9_]{0,62}$`，否则启动失败。

**显式豁免**：MP 原生 `@InterceptorIgnore(dataPermission = "true")` 会跳过注入。**业务模块禁止使用**；仅 iam 自身的元数据查询（`permission`/`role` 等管理表查询）与 platform 的字典/参数查询允许，由 ArchUnit 断言限制注解出现位置（6.3 的 A7）。

#### 3.6.5 跨公司审批（FR-SEC-10，HLD 13.3 验收项之一）

**不靠"放宽数据范围"实现**——放宽会让审批人永久看到更多数据。做法：

```
审批节点进入（workflow/approval，P2 本体）→ 调用 iam DataScopeApi.save：
  access_grant(grant_type='APPROVAL_CROSS_ORG', grantee=审批人, resource_code=单据资源,
               business_id=该单据主键, org_ids=[单据所属组织], valid_from=now, valid_to=审批完成 + 24h)
审批完成/超时 → 定时任务（platform SchedulerApi，JobHandler code="iam.access-grant.expire"）置 status='EXPIRED'
判定路径：resolveDataScope 步骤 5 并入有效 grant（或按 business_id 单条放开）
```

**为什么用 `business_id` 粒度**：审批人只应看到"待我审批的那一张/那批"，而不是整个公司。`business_id IS NULL` 的 grant（整资源）只允许 `TEMP_DATA_SCOPE` 且必须有到期时间与审批人，写入时强制审计。

#### 3.6.6 性能与索引要求（硬性）

| 要求 | 说明 |
|---|---|
| **改写列必须建索引** | 每个登记资源的 `org_column`/`owner_column` 必须有 `idx_<table>_<col>`；由 6.6 的 AC-IAM-14 检查（IT 断言 `pg_indexes` 中存在该索引，缺失即失败） |
| 大 `IN` 的规划器风险 | PG 对超大 `= ANY(ARRAY[...])` 可能放弃索引走 Seq Scan（成本估算偏差）；因此内联上限默认 2000，超过即降级 EXISTS（3.6.4），并在慢 SQL 日志（>500ms）里打 `datascope=inline, ids=1734` 便于定位 |
| `1 = 0` 的代价 | PG 常量折叠后不访问任何表数据（零 I/O），但**仍执行计划与权限检查**；因此 `DENY_ALL` 不会造成性能问题 |
| 每请求解析缓存 | `resolveDataScope` 的 Caffeine 缓存（TTL 60s + 权限版本失效）避免每请求重算子树的闭包查询；组织变更（`OrgChangedEvent`）主动失效 |
| 改写不可缓存 SQL 文本 | 片段含用户 id/组织集合，**不缓存最终 SQL**；只缓存解析结果（DTO） |
| 分页计数查询 | MP 的 `PaginationInnerInterceptor` 与数据权限拦截器共存时，`COUNT` 查询同样被注入条件（保证 `total` 与 `records` 一致，避免"总数泄漏"） |

#### 3.6.7 边界与失败模式

| 场景 | 处置 |
|---|---|
| 用户无任何组织上下文就调列表接口 | `21084`（**不允许"无上下文 = 全量"**；这是最危险的默认值） |
| 资源登记了但业务表**没有** `org_id` 列（设计漂移） | 启动期校验：扫描登记的 `table_name`/`org_column` 是否存在（`information_schema.columns`），缺失即**启动失败**（宁可起不来，也不要静默全表可见） |
| 写入越权（把行写进别人公司） | 拦截器不管 `INSERT`；防护 = 服务层从 `TenantCtx` 取 `org_id` 写入（DTO 不接受 `org_id`，见 5.3 的 `*SaveCmd` 无 orgId 字段）+ 唯一约束/触发器不做此校验 |
| 自有 SQL 手写 `WHERE org_id = ?` 绕过 | 允许（显式条件是被鼓励的）；风险是"显式条件比数据权限更宽"→ 由测试 6.4 的越权用例覆盖；`@InterceptorIgnore` 受限（3.6.4） |
| 一个资源同时被规则与 grant 命中 | 取并集；日志记录两侧来源，便于解释"为什么他看到了" |
| 组织树变化后旧快照仍生效 | `OrgChangedEvent` → 失效 `ds:cache` + 权限快照；≤30s 窗口，见 4.7 |
| 超管/系统账号 | `super_admin` 或内部任务（无 `TenantCtx`）**不注入**（3.5.2）；但必须**留审计**，且不允许经 HTTP 端点伪装成"系统任务"（5.2 的运维端点单独鉴权） |

#### 3.6.8 测试点

| 编号 | 场景 | 断言 |
|---|---|---|
| UT-DS-01 | 规则合并 | ALL/ORG/SELF/CUSTOM 组合的 `DataScopeDTO` 逐字段断言；DENY 优先 |
| UT-DS-02 | `DENY_ALL` 生成 | 未登记 → `null`；登记但无规则 → `1 = 0` |
| UT-DS-03 | 片段安全性 | 传入含 `'; DROP` 的"组织 id"被白名单拒绝（断言抛异常而非拼进 SQL） |
| UT-DS-04 | `DEPT_AND_SUB` | 闭包表查询被调用一次且结果集正确 |
| IT-DS-01 | SELECT 改写 | 两个身份（集团 `ALL` / 子公司 `ORG_AND_SUB`）调同一分页接口，`records` 集合不同且**都不含越界行**；`total` 也按范围收敛 |
| IT-DS-02 | UPDATE/DELETE 改写 | 越界用户执行 `up`/`del` 影响行数 = 0，且目标行未被修改 |
| IT-DS-03 | 未登记资源 | 同一 SQL 在未登记表上**无任何注入**（对比 `EXPLAIN` 与 SQL 日志） |
| IT-DS-04 | 降级路径 | 造 5000 个组织 → 生成 EXISTS 形态（断言无 `ARRAY[` 内联）；造 >20000 → `1 = 0` + 告警日志 |
| IT-DS-05 | 跨公司审批 grant | 审批人无 grant 时看不到该单据；写入 grant 后可见；过期后不可见 |
| IT-DS-06 | 索引存在性 | `data_scope_resource` 每行的 `org_column`/`owner_column` 都存在对应索引（AC-IAM-14 的自动化） |
| IT-DS-07 | 无上下文 | 缺失 `X-Org-Id` 且 JWT 无 `org_id` → `21084`，且**没有任何 SQL 被执行**（断言 Mapper 调用计数为 0） |

### 3.7 ABAC 属性策略引擎

#### 3.7.1 设计目标

FR-SEC-07：在 RBAC 之外支持"属性条件"判定（组织属性 / 数据属性 / 上下文属性），例如"金额 ≤ 10000 才可自审"、"仅工作时间可导出"。ABAC 只做**动作级**补充判定，不替代数据范围（后者管"看哪些行"）。

#### 3.7.2 策略模型与条件 DSL

```
abac_policy：code, name, resource_code, action（如 approve/export/*）, effect ∈ {PERMIT, DENY},
             condition_text（JSON，见下）, priority INT, status, description
条件 JSON（三段合取，段内可 and/or；求值器实现为 sealed 接口 PredicateNode）：
{
  "all": [
    { "attr": "resource.amount",      "op": "LTE", "value": 10000 },
    { "attr": "subject.orgLevel",     "op": "IN",  "value": [0, 1] },
    { "attr": "env.hourOfDay",        "op": "BETWEEN", "value": [9, 18] },
    { "attr": "resource.currency",    "op": "EQ",  "value": "CNY" }
  ],
  "any": [ { "attr": "action.name", "op": "IN", "value": ["export", "download"] } ],
  "none": [ { "attr": "env.ip", "op": "IN_CIDR", "value": ["10.0.0.0/8"] } ]
}
操作符：EQ NE GT GTE LT LTE IN NOT_IN BETWEEN CONTAINS STARTS_WITH EXISTS IN_CIDR（禁 REGEX：可导致 ReDoS）
属性命名空间：subject.*（用户/组织挂载/角色属性）、resource.*（业务对象属性，由调用方以 Map 传入）、
              action.*（动作名）、env.*（时间/IP/设备/UA）
```

**求值器（`AbacEngine`）**：`JsonUtils` 反序列化为 `ConditionNode` 树（`AllNode`/`AnyNode`/`NoneNode`/`PredicateNode`），属性由 `AbacAttributeResolver` SPI 解析（`subject` → `UserApi`/`OrgApi`；`resource` → 调用方传入的属性 Map；`env` → 请求上下文）。**未解析到的属性**（`EXISTS` 之外）一律判定为 `false` 并 WARN 日志——**不允许**"属性缺失即通过"。

#### 3.7.3 判定顺序与短路

```
@PreAuthorize 权限点通过（RBAC）
   → AbacEngine.evaluate(userId, resourceCode, action, attributes)
        ① 取该 (resourceCode, action) 的启用策略（Caffeine 缓存，策略变更事件失效）
        ② 命中 DENY → 拒绝（10403，带 policy code）
        ③ 无 DENY 且存在 PERMIT 策略 → 必须至少一条 PERMIT 命中，否则拒绝
        ④ 无任何策略 → **放行**（ABAC 是补充，不做默认拒绝）
```

**默认放行 vs 数据权限默认拒绝**：两者语义不同，必须说清——数据权限管"哪些行"，漏配会导致**看不到**（可用性问题，fail-closed 正确）；ABAC 管"能不能做这个动作"，漏配会导致**全站不可用**（业务停摆）。因此 ABAC 采取"无策略不参与"，并由验收清单要求关键动作（审批、导出、删除）**必须有策略或显式豁免登记**（6.6 AC-IAM-12）。

#### 3.7.4 关键取舍与代价

| 取舍 | 选择 | 代价 / 理由 |
|---|---|---|
| DSL 形态 | JSON 结构化条件（非表达式字符串） | 结构化可静态校验（属性名/操作符白名单）、可前端可视化编辑；代价：表达力弱于表达式语言（不支持算术/函数），确有需要时以自定义 `AbacAttributeResolver` 提供派生属性（如 `subject.approvalLimit`） |
| 求值位置 | 应用层（Java） | 可单测、可解释；代价：无法下沉到 SQL（与数据权限不同），因此**不能**用它做行过滤 |
| 缓存 | 策略全量缓存（Caffeine）+ 事件失效 | 求值零 DB 开销；代价：策略变更 ≤30s 生效（与权限快照同窗口） |
| 禁用 REGEX | 强制 | ReDoS 是可用性风险；等价能力用 `STARTS_WITH`/`CONTAINS`/`IN` 组合 |

#### 3.7.5 边界与失败模式 / 测试点

| 项 | 内容 |
|---|---|
| 边界 | 条件里引用不存在的属性 → 判定 false + WARN（不抛异常，避免业务中断）；策略 JSON 非法 → 保存时拒绝（`21038` 类校验错误），**不落库**；单请求求值上限 `eaio.iam.abac.max-policies` 默认 200（超限拒绝并告警，防策略爆炸拖慢请求） |
| 测试点 | UT-ABAC-01：操作符矩阵（12 个操作符 × 边界值）；UT-ABAC-02：`all/any/none` 组合真值表；UT-ABAC-03：属性缺失 → false；IT-ABAC-01：策略试算端点（`AbacController.Test`）给定用户+资源属性返回判定与命中的 policy code；IT-ABAC-02：DENY 优先于 PERMIT |

### 3.8 字段级权限（DTO 裁剪）

#### 3.8.1 设计目标

FR-SEC-09：敏感字段按角色隐藏/脱敏/只读。**统一实现，禁止各模块自行裁剪**——否则同一字段在不同接口表现不一致，且无法审计"谁在何时看不到什么"。

#### 3.8.2 组件与实现

```
字段权限数据：field_permission(role_id, resource_code, field_name, visible BOOLEAN, editable BOOLEAN, mask_type)
              mask_type ∈ {NONE, PHONE, ID_CARD, BANK_CARD, EMAIL, NAME, FULL}
DTO 标注（跨模块可用，注解放 iam.api 以免业务模块依赖实现）：
   @FieldPermission(resource = "iam:user", field = "mobile")
统一过滤器：FieldPermissionFilter（Jackson 3 的 BeanSerializerModifier，装到 app 的 ObjectMapper 上）
   在序列化前查 filterFields(userId, resourceCode, fields) → 决定「移除属性 / 输出掩码值 / 原样输出」
```

`PermissionApi.filterFields(userId, resourceCode, fields)` 返回 `Map<String, FieldPermissionDTO>`（`visible`/`editable`/`maskType`），规则合并方式与权限点一致：**多角色取最宽松的 visible 与最严格的 maskType**，但若任一角色显式 `visible=false` → 隐藏（安全优先）；`super_admin` → 全部可见可编辑。

**叠加顺序（与 P0 的 `@Sensitive` 脱敏并存）**：

```
业务对象 → ① FieldPermissionFilter（角色级：隐藏 / 可编辑判定）
        → ② @Sensitive / SensitiveSerializer（P0 已有，静态脱敏：存储原文、展示掩码）
        → ③ Result 包装
两者同时命中时取更严格者（先隐藏，其次掩码）。
```

#### 3.8.3 写侧（`editable=false`）

| 模式 | 行为 |
|---|---|
| `eaio.iam.field-permission.write-mode = STRICT`（**默认**） | 请求体含不可编辑字段 → 抛 `21032`（安全优先，避免"以为改了实际没改"） |
| `IGNORE` | 静默忽略该字段 + WARN 日志 + 审计记录被拒字段（兼容批量导入场景） |

**审计**：无论哪种模式，被拒字段都写 `FieldWriteRejectedEvent`（含 userId/resource/field/traceId）→ audit 落库。理由：字段越权尝试是攻击信号，不是普通校验失败。

#### 3.8.4 加密与密钥（与 NFR-SEC-02 对应）

| 项 | 设计 |
|---|---|
| 算法 | AES-256-GCM（`CryptUtils`，P1 新增，仅在 common，只用 JDK `javax.crypto`） |
| 密文格式 | `v1:<kid>:<base64(iv)>:<base64(ciphertext+tag)>`（单列可读、可轮换） |
| 字段 | `sys_user.mfa_secret`、`employee_sensitive.id_card_cipher`/`bank_account_cipher`、`sso_config.client_secret_cipher`、`ldap_config.bind_password_cipher` |
| 密钥来源 | 环境变量 `EAIO_IAM_FIELD_KEY_<kid>`（32 字节 base64）或 platform `ParamApi` 的 `is_encrypted` 项；**禁用硬编码与默认值**，启动期校验；缺失即启动失败 |
| 轮换 | `kid` 单调递增；解密按密文内 `kid` 选择密钥；轮换任务逐行重加密（`JobHandler code="iam.field.rekey"`），支持中断续跑（按 id 游标） |
| 查询 | 需等值查询的字段（证件号）另存 `id_card_hash = HMAC-SHA256(key_derived, 明文)`，可查重不可逆推 |
| 日志 | 明文与密钥**永不入日志**（logback 过滤器 + 代码 review 清单；`user_password_history`/`*_cipher` 列禁止出现在 SQL 日志的参数打印中，`local`/`it` profile 也仅打印参数类型） |

#### 3.8.5 边界与失败模式 / 测试点

| 项 | 内容 |
|---|---|
| 边界 | 资源未登记字段权限 → 默认全可见（否则每次新增字段都要配权限才能上线）；`field_name` 与 DTO 属性名不一致 → 序列化器忽略并在启动期扫描（`@FieldPermission` 的 field 必须在对应 DTO 上存在，由 ArchUnit/单测断言）；导出场景（platform `ExcelApi`）必须复用同一过滤器（否则"页面看不到、导得出"是真实泄漏） |
| 测试点 | UT-FP-01：多角色合并（可见性取严、掩码取严）；UT-FP-02：`@FieldPermission` 与 `@Sensitive` 叠加；IT-FP-01：同一 DTO 在三角色下返回 明文/掩码/字段缺失 三种形态；IT-FP-02：`editable=false` + STRICT → `21032` 且库值未变；IT-FP-03：导出走同一过滤器（导出文件不含隐藏字段列）；IT-FP-04：密钥缺失 → 启动失败（不是运行期 500） |

### 3.9 SoD 职责分离

#### 3.9.1 设计目标

FR-SEC-12：互斥权限不得同时落在同一人身上（"制单不能审核"）。要求：规则可配、校验在**三处强制**（角色分配、岗位变更、审批人指定）、冲突可解释、例外可留痕。

#### 3.9.2 规则模型

```
sod_rule：code, name, severity ∈ {HIGH, MEDIUM}, scope_type ∈ {GLOBAL, ORG}, org_id, status, description
sod_rule_permission：rule_id, permission_code, mutual_group SMALLINT
   —— 语义：同一 rule 内 **不同 mutual_group 之间互斥**；同组内不互斥
      例：rule「付款」= 组1 {finance:payment:create} vs 组2 {finance:payment:approve}
sod_exemption：rule_id, user_id, reason, approved_by, valid_from, valid_to, status
```

#### 3.9.3 三处强制校验点

| # | 触发点 | 调用 | 失败语义 |
|---|---|---|---|
| 1 | 角色分配（`RoleApi.assignUsers` / 用户角色变更） | `SoDCheckApi.checkAssign(RoleAssignCmd)` | 若新增角色的权限与用户现有权限构成互斥 → `21040`（消息含冲突规则与两个权限码） |
| 2 | 岗位/组织挂载变更（`UserApi.up`、`EmployeeApi.up`、`user_org` 变更） | 同上（变更后重新求值有效权限集合） | 同上；**只拦不自动调整**（不替用户决定"撤哪个角色"） |
| 3 | 审批人指定（workflow/approval，P2 调用，P1 提供契约） | `SoDCheckApi.check(userId, permissionCodes)` | 同上；审批引擎选择"违反 SoD 则换人"或"阻断并提示"由其策略决定（本册只提供判定） |

**岗位为什么不承载权限、但仍要校验**：岗位变更会改变 `user_org` 挂载 → 改变"哪些角色在该组织生效"（3.5.2 步骤 1）→ 有效权限集合变化。因此校验的对象始终是**变更后的有效权限集合**，而不是岗位本身。这保持"权限只有一条来源（角色）"的单一事实。

#### 3.9.4 判定算法与性能

```
check(userId, permissionCodes):
  1. 有效权限集合 P = permissionCodes（调用方给定）或 PermissionApi.listPermissions(userId)
  2. 载入规则（Caffeine 缓存，规则变更事件失效），过滤 scope_type=ORG 且 org 不匹配者
  3. 对每条规则：命中的 mutual_group 集合 G = { g | ∃ perm ∈ P ∩ 该组 }
     若 |G| ≥ 2 → 冲突（记录两两组合用于展示）
  4. 排除有效豁免：sod_exemption(user_id, rule_id, now ∈ [valid_from, valid_to), status='ACTIVE')
  5. 返回 SoDCheckResultDTO{passed, conflicts[]}
```

规则数 < 1000 时全内存位图判定（每规则一次 `Set` 交集），单次校验 < 1ms；**规则数量上限 `eaio.iam.sod.max-rules` 默认 1000**（超限拒绝新增并告警）。

#### 3.9.5 关键取舍与代价

| 取舍 | 选择 | 代价 / 理由 |
|---|---|---|
| 互斥表达 | 权限组（`mutual_group`）而非"权限对" | 规则可读、可扩展（三权分立是一次配置而非三对）；代价：求值稍复杂 |
| 校验时机 | **事前拦截**（分配/变更/指定），不做运行期持续扫描 | 拦截点少、性能可控；代价：**存量**用户可能已违反新规则 → 新增/修改规则时提供"影响面预览"（列出已违反的用户），并允许"仅告警不拦截"的过渡期（`sod_rule.status` 之外的 `enforce` 标志，见 4.3 DDL 备注） |
| 豁免 | 有期限 + 审批人 + 审计 | 完全无豁免会让业务被规则卡死（紧急付款场景）；代价：豁免本身成为风险点 → 到期自动失效 + 到期前 7 天门户待办（`OrgLifecycleChangedEvent` 同构的 `SodExemptionExpiringEvent`，P2 portal 消费） |
| 与 ABAC 的关系 | SoD 只管"权限组合"，不管属性条件 | 职责清晰；代价：需要"同人不得审同一单"这类**数据级**互斥时用 ABAC 条件（`resource.applicantId != subject.userId`） |

#### 3.9.6 边界与失败模式 / 测试点

| 项 | 内容 |
|---|---|
| 边界 | 规则引用不存在的权限码 → 保存拒绝；同一权限码出现在同一规则的同组内 → 允许（无意义但无害），出现在不同组内 → 那正是互斥的定义；`super_admin` **不豁免 SoD**（超管也要过校验，否则 SoD 形同虚设），但可授予豁免记录 |
| 测试点 | UT-SOD-01：二元/三元互斥矩阵；UT-SOD-02：豁免生效与过期；UT-SOD-03：`scope_type=ORG` 的规则只在本组织生效；IT-SOD-01：分配互斥角色 → `21040` 且 `user_role` 无新增行；IT-SOD-02：岗位挂载变更导致冲突 → 同样拦截；IT-SOD-03：豁免记录 → 放行 + 审计事件；AC-IAM-13：规则变更影响面预览返回真实违反用户清单 |

### 3.10 组织上下文 TenantCtx 与过滤器链

> 本节落实 **P0 册 6.3 待细化第 1 条**（`TenantCtx` 实现与过滤器接入）。接口 `TenantCtxProvider` 在 `com.eaio.iam.api`（批次总册 A3 断言：必须是 interface，实现类不得在 `api` 包）。

#### 3.10.1 数据结构与承载方式

```java
// com.eaio.iam.domain.context
public record TenantContext(long userId, String username, Long orgId, String orgPath,
                            boolean superAdmin, String sessionId, int permissionsVersion) {}
// com.eaio.iam.api（跨模块契约，5.4 给完整签名）
public interface TenantCtxProvider {
    Optional<TenantContext> current();
    TenantContext require();
    Optional<Long> currentOrgId();
    <T> T runAs(long orgId, java.util.concurrent.Callable<T> action);
}
```

| 决策 | 选择 | 取舍 |
|---|---|---|
| 承载机制 | **`ThreadLocal`**（`TenantContextHolder`，`static final ThreadLocal<TenantContext>` + `remove()`） | Java 21 的 `ScopedValue` 仍是 **preview API**（需 `--enable-preview`），生产不可用；且它无法自动跨越线程池边界（异步任务仍需显式传播）。**Java 25 转正后评估迁移**，届时 `TenantCtxProvider` 契约不变（只换实现） |
| 虚拟线程注意点 | ① `ThreadLocal` 在虚拟线程上按线程分配，**禁止缓存大对象**（`TenantContext` 是 7 字段 record，可接受）；② 不使用 `InheritableThreadLocal`（虚拟线程不继承）；③ 避免在持锁段内做阻塞 I/O（JDK 21 的 pinning）；④ 线程池复用必须 `finally` 清理（见 3.10.3 第 5 步） |
| 与 `SecurityContext` 的关系 | 认证身份来自 Spring Security；**组织上下文来自 `TenantCtx`**。两者不互相推断：`TenantCtx` 由过滤器显式构造，业务代码不得从 `SecurityContext` 里"找组织" | 单一来源；代价：过滤器是唯一写入点，必须有测试覆盖（IT-CTX-01…04） |

#### 3.10.2 过滤器链位置

```
TraceIdFilter(app, HIGHEST_PRECEDENCE)                     ← P0 既有
IdempotencyFilter(app, HIGHEST_PRECEDENCE + 20)            ← P0 既有
Spring Security FilterChainProxy
   ├─ BearerTokenAuthenticationFilter（JWT 验签 → JwtAuthenticationToken）
   ├─ SessionValidityFilter（本册新增；order = SecurityProperties.DEFAULT_FILTER_ORDER + 10）
   └─ TenantContextFilter（本册新增；order = SecurityProperties.DEFAULT_FILTER_ORDER + 20）
        ├─ 白名单端点直接放行（/iam/auth/Login|Captcha|Refresh, /oauth2/*, /actuator/health, /iam/auth/sso/**）
        ├─ 解析 orgId：请求头 X-Org-Id（切换公司）> JWT claim org_id > user_org.is_primary
        ├─ 校验挂载关系（3.10.3 第 3 步）
        ├─ 写 TenantContextHolder + MDC{userId, orgId}
        └─ finally：MDC.remove + TenantContextHolder.clear()
@PreAuthorize / 数据权限 / 字段权限                               ← 消费 TenantCtx
```

#### 3.10.3 解析与防越权切换（关键）

```
TenantContextFilter.doFilter(req, res, chain):
 1. 认证对象 = SecurityContextHolder 的 JwtAuthenticationToken；无 → 交给 Security 处理（最终 10401）
 2. 期望 orgId = req.header("X-Org-Id") ?? jwt.claim("org_id")
 3. **挂载校验（防横向越权）**：
      userOrg = user_org[user_id = sub AND org_id = 期望 && status = 'ACTIVE' && deleted = false]
      org    = org_node[期望]（lifecycle = 'ACTIVE'，否则 21013）
      ├─ user.super_admin = true → 跳过挂载校验（但必须记审计：actor=super_admin, org=期望）
      ├─ userOrg 缺失 → 21085（"无权切换到该组织"）
      └─ 组织已注销/归档 → 21013
 4. orgPath = OrgApi.pathOf(期望)（走 Caffeine 缓存）；构造 TenantContext（含 permissionsVersion = 快照 ver）
 5. try { chain.doFilter } finally { TenantContextHolder.clear(); MDC.remove("userId"); MDC.remove("orgId"); }
```

**为什么不能只信 JWT 的 `org_id`**：令牌有效期 30 分钟，期间管理员可能撤销挂载或注销组织；若只信 claim，会出现"已被移出组织的人继续以该组织身份访问"。因此**每次请求都校验挂载**（走 Caffeine 缓存的组织/挂载快照，命中时不查库；`OrgChangedEvent`/`UserRoleChangedEvent` 失效缓存）。

#### 3.10.4 异步与线程池的上下文传递

| 场景 | 方案 |
|---|---|
| 同进程 `@Async` / 自定义线程池 | `TenantContextTaskDecorator implements TaskDecorator`：提交时捕获 `TenantContext`，执行时 `set`，`finally clear`；所有 iam/platform 的 `ThreadPoolTaskExecutor` 必须装配该装饰器（缺失由 6.3 的 A8 断言拦截） |
| platform 定时任务（`SchedulerApi`） | 任务默认**无组织上下文**；需要组织身份的任务在注册时声明 `orgScope`，由 `SchedulerApi` 调用 `TenantCtxProvider.runAs(orgId, …)`；`runAs` 内部：`require()` 失败则构造系统上下文（`superAdmin=false`、`userId=SYSTEM`），**并写审计**（谁在何时以哪个组织身份跑了什么任务） |
| 虚拟线程 / `CompletableFuture` | 不允许隐式继承；跨边界一律显式传 `TenantContext` 值对象（方法参数），或经 `runAs` 包裹 |
| 事件监听（`@ApplicationModuleListener`） | 监听器运行在发布方事务提交后的独立线程 → **事件载荷必须自带 `orgId`/`operatorId`**，不得依赖 `TenantCtx`（这是 3.11 事件载荷设计的强制约束） |

#### 3.10.5 边界与失败模式 / 测试点

| 项 | 内容 |
|---|---|
| 边界 | 白名单端点无上下文（正常）；`X-Org-Id` 非数字/不存在 → `21085` 而非 500；超管切换任意组织允许但必审计；内部调用（`runAs`）产生的上下文 `sessionId = null`（不可用于对外接口） |
| 测试点 | IT-CTX-01：`X-Org-Id` 指向未挂载组织 → `21085`，且未执行任何业务 SQL；IT-CTX-02：挂载被撤销后旧令牌再请求 → `21085`（证明"每请求校验"生效）；IT-CTX-03：线程池任务内 `require()` 拿到正确 orgId，且任务结束后 ThreadLocal 已清理（断言 `current()` 为空）；IT-CTX-04：MDC 在请求结束后无残留（防串号）；UT-CTX-01：`runAs` 抛异常时上下文正确恢复（try/finally 语义） |

### 3.11 权限变更审计与事件

#### 3.11.1 设计目标

FR-SEC-13：权限变更全量留痕；HLD 3.3/4.4：事件在提交后发布、可靠性可控（P0 册 6.3 第 ⑤ 条的 iam 侧落实）。iam **不自建审计表**（audit 产权），只发事件与调用 `PermissionAuditApi`。

#### 3.11.2 事件清单（`com.eaio.iam.events`）

| 事件 | 触发 | 载荷（关键字段） | 消费方 | 语义 |
|---|---|---|---|---|
| `UserRoleChangedEvent` | 用户角色增删改（含批量） | `eventId, userId, changeType ∈ {GRANT, REVOKE, REPLACE}, roleIds, orgId, operatorId, occurredAt` | audit（HLD 3.3 已登记） | HLD 3.3 登记事件，载荷字段按 HLD 语义补全 |
| `OrgChangedEvent` | 组织新增/修改/移动/删除 | `eventId, nodeId, changeType, parentId, oldPath, newPath, operatorId, occurredAt` | platform（缓存失效）、audit | 驱动组织树缓存与数据权限缓存失效 |
| `PermissionChangedEvent` | 角色权限点变更 / 权限点变更 / 数据范围与字段权限变更 | `eventId, subjectType ∈ {ROLE, USER, PERMISSION}, subjectId, changeType, affectedUserIds（可空，空=需重算）, operatorId, occurredAt` | platform（缓存失效）、audit、（P2）hr | 权限快照失效的唯一信号 |
| `OrgLifecycleChangedEvent` | 生命周期流转（3.1.4） | `eventId, nodeId, fromState, toState, reason, affectedUserCount, handoverTo, operatorId, occurredAt` | portal（数据交接待办，P1 顺序 9 起）、audit | 注销的影响面与待办来源 |
| `EmployeeChangedEvent` | **本册新增登记**：员工任职变更（入职/调岗/离职/多组织挂载变更） | `eventId, employeeId, userId, changeType, orgId, positionId, effectiveDate, operatorId, occurredAt` | （P2）hr、audit | iam 与 hr 的边界事件：hr 不得反查 iam 表，只订阅本事件 |

**载荷硬约束**：所有事件**必须自带 `orgId` 与 `operatorId`**，不得依赖 `TenantCtx`（3.10.4）；事件 record 不可变（`record` + `List.copyOf`）。

#### 3.11.3 可靠性：事务性发件箱 + 幂等消费

```
发布（同事务）：
  业务写库 ──► INSERT eaio_iam.event_outbox(event_id, event_type, aggregate_type, aggregate_id,
                                            payload_json, status='PENDING', occurred_at, org_id, operator_id)
              （同一本地事务，业务回滚则事件不落库 → 不会出现"事件发了、数据没了"）
提交后投递：
  OutboxPublisher（@Scheduled 每 1s，ShedLock 单实例；platform 册 C-8 同款选型）
     SELECT ... FROM event_outbox WHERE status='PENDING' AND next_retry_at <= now()
       ORDER BY occurred_at LIMIT 200 FOR UPDATE SKIP LOCKED          -- 多实例安全
     → ApplicationEventPublisher.publishEvent(反序列化后的领域事件)      -- 进程内，@ApplicationModuleListener 异步消费
     → status='PUBLISHED', published_at=now()
     → 失败：retry_count+1、next_retry_at = now + backoff(1s,5s,30s,2m,10m)、last_error=ex；
             retry_count ≥ 5 → status='DEAD'（死信）+ ERROR 告警（含 traceId）
消费幂等：
  EventIdempotencyGuard：唯一键 (event_id, consumer, business_id)
     ├─ 落库：INSERT INTO eaio_iam.event_consume_record(...) ON CONFLICT DO NOTHING
     └─ 重复 → 直接跳过（不执行副作用、不报错）
死信与补偿：
  POST /api/iam/event/Retry（权限点 iam:event:retry，仅运维/超管）→ 置回 PENDING 并清零 retry
  查询：POST /api/iam/event/GetPage（按 status/event_type/时间）
```

**为什么不是"直接 `@TransactionalEventListener(AFTER_COMMIT)` 就够"**：AFTER_COMMIT 在进程崩溃时会**丢事件**（提交后、投递前）。发件箱把"是否要发"持久化，把"投递"变成可重试的幂等动作（代价：多一次写库 + 1s 级投递延迟，见 4.6/4.8 的保留与清理策略）。

#### 3.11.4 与 audit 的接入分期（I-8）

| 阶段 | 行为 |
|---|---|
| M2–M3（audit 未落地） | 只发事件 + 结构化日志（`event=PERMISSION_CHANGE, actor, subject, change, before, after, traceId`）；`event_outbox` 中 `PERMISSION_*` 事件保留 **PUBLISHED 但标记 `awaiting_consumer=true`**（audit 上线后由补偿任务重投） |
| M4+（audit 落地） | 加 `<optional>true</optional>` 依赖 `e-aio-audit`；`ObjectProvider<PermissionAuditApi>` 可选注入：存在则 `record(PermissionChangeCmd)` **同步落 audit**（强一致，权限变更必须有审计），不存在则降级为 M2–M3 行为；`@ApplicationModule(allowedDependencies)` 追加 `"audit::api"` |

**审计内容最小集（`PermissionChangeCmd`）**：`changeType`、`subjectType/subjectId`、`before`/`after`（仅变化项，JSON）、`operatorId`、`orgId`、`ip`、`userAgent`、`traceId`、`occurredAt`。**权限相关写操作必须落审计**（角色/权限点/数据范围/字段权限/SoD 豁免/超管绕过），且 audit 写入失败时**阻断关键操作**（HLD 7.2：审计写入失败阻断）。

#### 3.11.5 边界与失败模式 / 测试点

| 项 | 内容 |
|---|---|
| 边界 | outbox 表膨胀（PENDING 堆积）→ 告警阈值（`PENDING > 1000` 持续 5 分钟）；DEAD 事件需人工处置（提供查询与重投）；事件载荷含业务 id 但不含敏感明文（不得把 `id_card`/`mfa_secret` 塞进 payload）；进程内事件监听器抛异常**不影响** outbox 状态（由重试机制处理） |
| 测试点 | UT-EVT-01：事件载荷字段完整性（含 orgId/operatorId）；UT-EVT-02：退避序列与 DEAD 判定；IT-EVT-01：业务回滚 → outbox 无行；业务成功 → outbox 有行且最终 PUBLISHED；IT-EVT-02：重复消费同一 event_id 两次 → 副作用只发生一次；IT-EVT-03：audit 缺席时权限变更仍成功且日志含结构化审计行；IT-EVT-04：`event_outbox` 的 `FOR UPDATE SKIP LOCKED` 在 2 个并发发布器下不重复投递 |

### 3.12 前端页面与权限点

> **本册不含前端代码**（不改 `frontend/`）。本节给页面清单、权限点与联合调试要点，供前端按 [frontend-conventions.md](agents/frontend-conventions.md) 实现。

#### 3.12.1 页面清单

| 页面 | 路由 | 关键能力 | 权限点（见 7.2） |
|---|---|---|---|
| 登录 | `/login` | 账号密码 + 验证码 + MFA 第二步 + SSO 入口（OIDC/SAML 按钮按 `sso_config` 列表渲染） | 免鉴权 |
| 公司切换器 | 顶栏组件 | 列 `user_org`（ACTIVE）→ 写入 `X-Org-Id`（store + 后续请求头） | 登录即可 |
| 个人中心 | `/profile` | 改密、MFA 绑定/解绑、恢复码、我的会话（列表 + 撤销） | 登录即可（`iam:profile:*`） |
| 组织管理 | `/iam/org` | 树（懒加载）、增删改、拖拽移动、生命周期流转（含注销预览对话框） | `iam:org:list/add/up/del/move/lifecycle` |
| 用户管理 | `/iam/user` | 列表（数据权限过滤生效）、增删改、停用、重置口令、角色分配、多组织挂载 | `iam:user:list/add/up/del/disable/resetPwd/role` |
| 岗位管理 | `/iam/position` | 岗位主档 + 组织挂载（编制数） | `iam:position:*` |
| 员工档案 | `/iam/employee` | 任职信息（入职/调岗/离职）、敏感字段（按字段权限显示/掩码/隐藏） | `iam:employee:list/up` |
| 角色管理 | `/iam/role` | 角色 CRUD、权限点勾选（树）、继承（选父角色/模式）、模板标记、分配用户、继承链查看 | `iam:role:list/add/up/del/assign` |
| 权限点管理 | `/iam/permission` | 权限点树 CRUD、按模块筛选 | `iam:permission:list/add/up/del` |
| 数据范围 | `/iam/dataScope` | 规则列表（角色/用户 × 资源 × 范围）、自定义组织选择器、资源登记 | `iam:dataScope:list/save/del` |
| 字段权限 | `/iam/field` | 角色 × 资源 × 字段的 可见/可编辑/掩码 矩阵 | `iam:field:list/save` |
| ABAC 策略 | `/iam/abac` | 策略 CRUD + **试算**（选用户 + 填资源属性 → 看判定与命中策略） | `iam:abac:list/add/up/del/test` |
| SoD | `/iam/sod` | 规则与权限组维护、冲突检测（选用户试算）、豁免登记与到期清单 | `iam:sod:list/save/exempt/check` |
| SSO/LDAP 配置 | `/iam/idp` | `sso_config`/`ldap_config` CRUD、连接测试、字段映射 | `iam:idp:list/save/test` |
| 会话管理（管理员） | `/iam/session` | 按用户查在线会话、强制下线 | `iam:session:list/revoke` |
| 通讯录 | `/iam/contact` | 只读列表 + 组织树筛选 + 导出 | `iam:contact:list/export` |

#### 3.12.2 前端与权限的对接约定

| 约定 | 内容 |
|---|---|
| 菜单与路由 | 前端动态路由由 `PermissionApi.menuTree(userId)` 生成（`permission` 树中 `perm_type='MENU'` 的节点，含 `route_path`/`icon`/`sort`）；**不硬编码权限** |
| 按钮级 | `v-hasPermi="['iam:user:add']"`（RuoYi-Vue3 既有指令，复用不自造） |
| 字段级 | 后端已按字段权限裁剪/掩码；前端**不得**自行再对返回字段做"猜测性隐藏"（会出现"页面隐藏、接口返回"的错觉） |
| 认证失败 | 全局响应拦截器按 `Result.code`：`10401` → 清 token + 跳 `/login`；`10403` → 提示"无权限"；`21085` → 提示"当前公司上下文失效，请重新切换公司"。**禁止按 HTTP 401/403 判断**（ADR-0001） |
| 写接口 | 全部带 `Idempotency-Key`（`crypto.randomUUID()`，重试复用同键）；`21032`（字段不可编辑）需给出明确字段名提示 |
| 大列表/树 | 组织树懒加载 `children(nodeId)`；用户列表分页 + 明确 `ORDER BY`（后端强制，见 4.2） |

#### 3.12.3 组织树 × 数据权限的联合调试要点（实战清单）

1. **"列表为空"三连排查**：① 该用户有没有 `iam:xxx:list` 权限点（看 `menuTree`/`check`）；② 有没有数据范围规则（`resolveDataScope` 返回 `DENY_ALL` 会恒空）；③ 当前 `X-Org-Id` 是否在用户挂载内（`21085` 会直接报错，不会静默空）。
2. **切换公司后数据不变**：前端必须把 `X-Org-Id` 注入**所有**请求（含导出、字典、树），并清空依赖组织的本地缓存（列表、树）；后端不接受"用 query 参数传 orgId"。
3. **树节点权限差异**：同一用户在集团上下文能看到全部组织节点，在子公司上下文只看到子公司子树（树接口同样受数据权限约束）——前端不得缓存跨上下文复用。
4. **导出与列表不一致**：导出走 platform `ExcelApi` 异步任务，数据权限在任务执行线程内**必须重新解析**（`runAs(orgId)` + 任务参数带 `orgId`），不得复用提交时的结果集。

#### 3.12.4 边界与失败模式 / 测试点

| 项 | 内容 |
|---|---|
| 边界 | 无菜单权限 → 空树 + 友好页（不 500）；令牌过期期间的并发请求 → 只弹一次登录（拦截器去重）；`X-Org-Id` 与 JWT 不一致时以**请求头为准**（合法的切换），并以后端校验为准 |
| 测试点 | FE-CT-01：登录失败按 `code=21001` 提示而非跳登录；FE-CT-02：`10401` 只跳一次登录（并发 5 请求断言）；FE-CT-03：切换公司后所有请求头带新 `X-Org-Id`；FE-CT-04：导出任务参数含 `orgId` 且结果集与列表一致 |

### 3.13 通讯录（FR-SEC 相关对外只读能力）

#### 3.13.1 设计目标与边界

SRS FR-HR-07 把"通讯录"归 iam。通讯录是**只读聚合视图**（`sys_user` × `user_org` × `employee` × `org_node`），**不建独立表**（避免与用户主档双写）。

| 项 | 设计 |
|---|---|
| 可见字段 | 姓名、工号、组织路径、岗位、办公电话、邮箱（可选）——**不含**手机号明文（按字段权限显示明文/掩码/隐藏）、不含证件、不含薪酬 |
| 可见范围 | 受数据权限约束（`resource_code = iam:contact`，`org_column = org_id`）：默认同公司可见；跨公司需显式规则或 `access_grant` |
| 接口 | `POST /api/iam/contact/GetPage`（`iam:contact:list`）；导出 `POST /api/iam/contact/Export` → platform `ExcelApi` 异步任务（`iam:contact:export`）+ 导出水印与审批（NFR-SEC-05，审批走 P1 approval） |
| 与 hr 边界 | 通讯录与员工任职主档在 iam；hr（P2）不得自建员工表，需任职信息时订阅 `EmployeeChangedEvent` 或调 `EmployeeApi` |
| 失败模式 | 无范围 → 空列表（不报错）；导出超范围 → 任务失败并记录（`21014`）；被逻辑删除/停用用户不出现（除管理员查询历史） |
| 测试点 | IT-CT-01：两个公司用户的通讯录互不可见；IT-CT-02：手机号按字段权限三态（明文/掩码/缺字段）；IT-CT-03：导出文件列与列表字段一致且带水印标识列 |

---

## 4. 数据结构设计

### 4.1 Schema 与命名规范

| 项 | 约定 |
|---|---|
| Schema | `eaio_iam`（唯一），脚本内对象**一律显式限定** `eaio_iam.<object>`；**不建跨 Schema 外键**（ADR-0002） |
| Flyway 实例 | `locations = classpath:db/migration/iam`、`defaultSchema = eaio_iam`、版本号模块内独立递增；装配沿用 `ModuleFlywayProperties.locationOf("iam")` / `schemaOf("iam")`，并在 `application.yml` 的 `eaio.flyway.modules` 追加 `- iam` |
| 表名 | `snake_case` **单数**、无模块前缀；用户主档例外 `sys_user`（PG 保留字） |
| 命名 | 主键 `pk_<table>`；唯一 `uk_<table>_<cols>`（**逻辑删除表用 partial unique index `WHERE deleted = false`**）；索引 `idx_<table>_<cols>`；外键 `fk_<table>_<col>`；检查 `ck_<table>_<col>` |
| 主键 | `id BIGINT NOT NULL`，雪花 `IdGenerator` 生成（`IdType.INPUT`），无 `DEFAULT`、无自增（信创/分布式留口） |
| 统一列（业务实体表） | `created_at TIMESTAMPTZ NOT NULL`、`created_by BIGINT`、`updated_at TIMESTAMPTZ`、`updated_by BIGINT`、`version INT NOT NULL DEFAULT 0`、`deleted BOOLEAN NOT NULL DEFAULT false` |
| 例外 1：append-only 表 | `login_attempt`、`user_password_history`、`mfa_recovery_code`、`org_lifecycle_log`、`event_consume_record`、`auth_refresh_token`：只有 `created_at/created_by`（+ 必要状态列），**无 `version`/`deleted`**（不更新、不删除，按时间清理） |
| 例外 2：派生表 | `org_node_path`（无审计列，随主表事务重建）、`permission_snapshot`（有 `computed_at`/`version`，无 `deleted`）、`event_outbox`（有 `status` 与重试列） |
| 时间 | 一律 `TIMESTAMPTZ`（UTC 存储） |
| 布尔 | `BOOLEAN NOT NULL DEFAULT false`（见 2.4.4 I-12） |
| 枚举 | `VARCHAR(8/16/24/32)` + `CHECK` 约束，**不用 PG ENUM**（改值要 DDL） |
| JSON 语义列 | 一律 `TEXT` + 应用层 `JsonUtils` 校验（与 P1 platform 册 5.1.8 的"信创留口"一致，**不用 `JSONB`/PG 数组**）；字段名以 `_json`/`_ids`/`_text` 结尾明示 |
| 注释 | **每张表必须有 `COMMENT ON TABLE`**；枚举列、加密列、语义易歧义列必须有 `COMMENT ON COLUMN`；由 IT 断言"iam 新增表无空注释"（6.2 IT-META-01） |
| 行数预估量级 | 小（< 1 千）/ 中（万级）/ 大（十万级）/ 流水（百万级，需保留策略） |
| 排序 | 所有分页查询必须显式 `ORDER BY`（分页正确性 + 索引可用性），默认 `sort_order ASC, id ASC` |

### 4.2 ER 图（按域分组）

```
【组织域】                     【授权域】                          【认证域】
org_node ──1:N── org_node      role ──1:N── role_permission ──N:1── permission     auth_session ──1:N── auth_refresh_token
   │  ▲  (parent_id 自引用)        │  ▲ (parent_id 角色继承)              ▲                │  N:1
   │  └── org_node_path            │                                     │             sys_user
   │      (ancestor/descendant)    ├──1:N── user_role ──N:1── sys_user ──┘                │  1:N
   │                              │            (org_scope_id)                            ├── login_attempt
   ├──1:N── org_position ──N:1── position                                                ├── user_password_history
   │                                                                    └── mfa_recovery_code
   ├──1:N── user_org ──N:1── sys_user（多组织挂载 + position_id）
   ├──1:N── org_lifecycle_log（append-only）      【数据权限域】
   └──1:N── data_scope_rule / access_grant（org 集合以 TEXT-JSON 存）
                                                  data_scope_resource（资源白名单）
【人员域】                                        data_scope_rule（主体×资源×范围）
sys_user ──1:1── employee ──1:1── employee_sensitive             │
                       └──(P2 hr 经 EmployeeChangedEvent 消费)     ├── field_permission（角色×资源×字段）
                                                                  ├── abac_policy（资源×动作×条件）
【SoD 域】sod_rule ──1:N── sod_rule_permission                    └── permission_snapshot（派生）
          sod_rule ──1:N── sod_exemption
【身份源域】sso_config / ldap_config（sys_user.source + external_id 关联，不加外键——外部系统标识不属于本地引用完整性）
【事件域】event_outbox（发件箱）─/─ event_consume_record（消费幂等；event_id 字符串关联，不做外键）
```

**关系与引用完整性规则**：

1. **模块内**：全部单列外键（`fk_*`）；`org_node.parent_id` 自引用；`role.parent_id` 自引用；**禁止多列外键**（可读性差、索引难配）。
2. **跨 Schema**：**零外键**。`user_org.org_id` / `data_scope_rule.subject_id` 等指向 iam 内的 id 有外键；指向业务模块资源的字段（`data_scope_resource.table_name`）是**字符串登记**，不是外键（跨模块一致性由 3.6.7 的启动期校验保证）。
3. **删除策略**：物理删除一律 `RESTRICT`（有引用即报错，倒逼走逻辑删除）；`ON DELETE CASCADE` 只用于**派生表**（`org_node_path` 随 `org_node` 物理删除时级联——实际不会发生，因为 `org_node` 只逻辑删除）。
4. **`deleted` 与唯一性**：所有业务实体的唯一约束都是 partial unique index（`WHERE deleted = false`），保证"逻辑删除后可复用同名"。

### 4.3 表结构 DDL（31 张，按迁移脚本分组）

#### 4.3.1 `V1__baseline.sql`：基线

| 表 | 用途 | 行数预估 | 保留 |
|---|---|---|---|
| —（无表） | 只创建 Schema 与注释头，与 platform 的 `V1__baseline.sql` 同构（ADR-0002：V1 只允许含 schema 与注释） | — | — |

```sql
-- 模块：iam（身份与访问管理）
-- 归属：Flyway 实例 eaio_iam，脚本目录 classpath:db/migration/iam
-- V1 基线只做一件事：建立本模块 Schema。业务表自 V2__ 起连续编号。
CREATE SCHEMA IF NOT EXISTS eaio_iam;
```

#### 4.3.2 `V2__org.sql`：组织树

| 表 | 用途 | 行数预估 | 保留 |
|---|---|---|---|
| `org_node` | 组织节点（无限级，含集团/公司/部门/团队、生命周期、负责人） | 大（10 万级） | 永久（逻辑删除） |
| `org_node_path` | 闭包表（祖先-后代-深度，含自身 `depth = 0`） | 大（百万级，随深度线性） | 随主表（派生，可重建） |

```sql
CREATE TABLE eaio_iam.org_node (
  id BIGINT NOT NULL,
  code VARCHAR(64) NOT NULL, name VARCHAR(128) NOT NULL, short_name VARCHAR(64),
  node_type VARCHAR(16) NOT NULL, parent_id BIGINT, path VARCHAR(512) NOT NULL,
  level SMALLINT NOT NULL DEFAULT 0, sort_order INT NOT NULL DEFAULT 0,
  lifecycle VARCHAR(16) NOT NULL DEFAULT 'PREPARING', manager_user_id BIGINT,
  cost_center VARCHAR(64), start_date DATE, end_date DATE, description VARCHAR(255),
  created_at TIMESTAMPTZ NOT NULL, created_by BIGINT, updated_at TIMESTAMPTZ, updated_by BIGINT,
  version INT NOT NULL DEFAULT 0, deleted BOOLEAN NOT NULL DEFAULT false,
  CONSTRAINT pk_org_node PRIMARY KEY (id),
  CONSTRAINT ck_org_node_node_type CHECK (node_type IN ('GROUP','COMPANY','DEPT','TEAM')),
  CONSTRAINT ck_org_node_lifecycle CHECK (lifecycle IN ('PREPARING','ACTIVE','DEREGISTERED','ARCHIVED')),
  CONSTRAINT fk_org_node_parent FOREIGN KEY (parent_id) REFERENCES eaio_iam.org_node (id)
);
CREATE UNIQUE INDEX uk_org_node_code ON eaio_iam.org_node (code) WHERE deleted = false;
CREATE INDEX idx_org_node_parent ON eaio_iam.org_node (parent_id) WHERE deleted = false;
CREATE INDEX idx_org_node_path ON eaio_iam.org_node (path varchar_pattern_ops);
CREATE INDEX idx_org_node_type_lifecycle ON eaio_iam.org_node (node_type, lifecycle) WHERE deleted = false;
COMMENT ON TABLE eaio_iam.org_node IS '组织节点（无限级组织树：集团/公司/部门/团队）';
COMMENT ON COLUMN eaio_iam.org_node.path IS '物化路径，形如 /1/5/23/（含自身，首尾斜杠）；子树查询用 path LIKE ''前缀%''';
COMMENT ON COLUMN eaio_iam.org_node.lifecycle IS '生命周期：PREPARING 筹备 / ACTIVE 运营 / DEREGISTERED 注销 / ARCHIVED 归档（只读终态）';

CREATE TABLE eaio_iam.org_node_path (
  ancestor_id BIGINT NOT NULL, descendant_id BIGINT NOT NULL, depth SMALLINT NOT NULL,
  CONSTRAINT pk_org_node_path PRIMARY KEY (ancestor_id, descendant_id),
  CONSTRAINT ck_org_node_path_depth CHECK (depth >= 0)
);
CREATE INDEX idx_org_node_path_descendant ON eaio_iam.org_node_path (descendant_id, depth);
COMMENT ON TABLE eaio_iam.org_node_path IS '组织闭包表（派生数据，应用层单事务维护；不带审计列与逻辑删除，随主表重建）';
COMMENT ON COLUMN eaio_iam.org_node_path.depth IS '祖先到后代的边数；depth=0 表示自身行';
```

#### 4.3.3 `V3__position.sql`：岗位与组织挂载

| 表 | 用途 | 行数预估 | 保留 |
|---|---|---|---|
| `position` | 岗位主档（职级/职类/编制上限；**不承载权限**，见 3.2.4） | 中（万级） | 永久 |
| `org_position` | 岗位在组织下的挂载（编制数、在职数快照） | 大（十万级） | 永久 |

```sql
CREATE TABLE eaio_iam.position (
  id BIGINT NOT NULL, code VARCHAR(64) NOT NULL, name VARCHAR(128) NOT NULL,
  position_level SMALLINT, position_category VARCHAR(32), headcount_limit INT,
  sort_order INT NOT NULL DEFAULT 0, status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
  description VARCHAR(255),
  created_at TIMESTAMPTZ NOT NULL, created_by BIGINT, updated_at TIMESTAMPTZ, updated_by BIGINT,
  version INT NOT NULL DEFAULT 0, deleted BOOLEAN NOT NULL DEFAULT false,
  CONSTRAINT pk_position PRIMARY KEY (id),
  CONSTRAINT ck_position_status CHECK (status IN ('ENABLED','DISABLED'))
);
CREATE UNIQUE INDEX uk_position_code ON eaio_iam.position (code) WHERE deleted = false;
COMMENT ON TABLE eaio_iam.position IS '岗位主档（组织维度的编制单位，不承载权限；权限只经角色获得）';

CREATE TABLE eaio_iam.org_position (
  id BIGINT NOT NULL, org_id BIGINT NOT NULL, position_id BIGINT NOT NULL,
  headcount INT NOT NULL DEFAULT 0, sort_order INT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
  created_at TIMESTAMPTZ NOT NULL, created_by BIGINT, updated_at TIMESTAMPTZ, updated_by BIGINT,
  version INT NOT NULL DEFAULT 0, deleted BOOLEAN NOT NULL DEFAULT false,
  CONSTRAINT pk_org_position PRIMARY KEY (id),
  CONSTRAINT ck_org_position_status CHECK (status IN ('ENABLED','DISABLED')),
  CONSTRAINT fk_org_position_org FOREIGN KEY (org_id) REFERENCES eaio_iam.org_node (id),
  CONSTRAINT fk_org_position_position FOREIGN KEY (position_id) REFERENCES eaio_iam.position (id)
);
CREATE UNIQUE INDEX uk_org_position_org_position ON eaio_iam.org_position (org_id, position_id) WHERE deleted = false;
CREATE INDEX idx_org_position_position ON eaio_iam.org_position (position_id);
COMMENT ON TABLE eaio_iam.org_position IS '岗位与组织的挂载（编制/职数）；在职人数不落库，实时按 user_org 统计';
```

#### 4.3.4 `V4__user.sql`：用户与多组织挂载

| 表 | 用途 | 行数预估 | 保留 |
|---|---|---|---|
| `sys_user` | 用户主档（登录主体；本地/LDAP/SSO 来源；口令与 MFA 凭据） | 中（万级） | 永久（逻辑删除） |
| `user_org` | 用户多组织挂载（含主组织、岗位、起止日期） | 大（十万级） | 永久 |
| `user_password_history` | 口令历史（最近 N 次，防重用，SRS FR-SEC-01 策略） | 中 | 流水（每用户保留 `history-size` 条，超出清理） |
| `mfa_recovery_code` | MFA 恢复码（哈希存储，一次性） | 中 | 流水（重新生成即清理旧码） |

```sql
CREATE TABLE eaio_iam.sys_user (
  id BIGINT NOT NULL, username VARCHAR(64) NOT NULL, nickname VARCHAR(64), real_name VARCHAR(64),
  mobile VARCHAR(32), email VARCHAR(128),
  user_type VARCHAR(16) NOT NULL DEFAULT 'PERSON', status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  password_hash VARCHAR(255), password_updated_at TIMESTAMPTZ, must_change_password BOOLEAN NOT NULL DEFAULT false,
  mfa_enabled BOOLEAN NOT NULL DEFAULT false, mfa_secret TEXT, mfa_secret_kid VARCHAR(32), mfa_enrolled_at TIMESTAMPTZ,
  source VARCHAR(16) NOT NULL DEFAULT 'LOCAL', external_id VARCHAR(255),
  super_admin BOOLEAN NOT NULL DEFAULT false, token_version INT NOT NULL DEFAULT 0,
  fail_count INT NOT NULL DEFAULT 0, locked_until TIMESTAMPTZ,
  last_login_at TIMESTAMPTZ, last_login_ip VARCHAR(64), remark VARCHAR(255),
  created_at TIMESTAMPTZ NOT NULL, created_by BIGINT, updated_at TIMESTAMPTZ, updated_by BIGINT,
  version INT NOT NULL DEFAULT 0, deleted BOOLEAN NOT NULL DEFAULT false,
  CONSTRAINT pk_sys_user PRIMARY KEY (id),
  CONSTRAINT ck_sys_user_user_type CHECK (user_type IN ('PERSON','SERVICE')),
  CONSTRAINT ck_sys_user_status CHECK (status IN ('PENDING','ACTIVE','LOCKED','DISABLED')),
  CONSTRAINT ck_sys_user_source CHECK (source IN ('LOCAL','LDAP','SSO'))
);
CREATE UNIQUE INDEX uk_sys_user_username ON eaio_iam.sys_user (username) WHERE deleted = false;
CREATE UNIQUE INDEX uk_sys_user_mobile ON eaio_iam.sys_user (mobile) WHERE deleted = false AND mobile IS NOT NULL;
CREATE UNIQUE INDEX uk_sys_user_email ON eaio_iam.sys_user (email) WHERE deleted = false AND email IS NOT NULL;
CREATE UNIQUE INDEX uk_sys_user_external ON eaio_iam.sys_user (source, external_id) WHERE deleted = false AND external_id IS NOT NULL;
CREATE INDEX idx_sys_user_status ON eaio_iam.sys_user (status) WHERE deleted = false;
CREATE INDEX idx_sys_user_real_name ON eaio_iam.sys_user (real_name);
COMMENT ON TABLE eaio_iam.sys_user IS '用户主档（登录主体）；表名带 sys_ 前缀因 user 是 PostgreSQL 保留字';
COMMENT ON COLUMN eaio_iam.sys_user.password_hash IS 'DelegatingPasswordEncoder 前缀形态：{argon2} 默认，{bcrypt} 为兼容升级位（登录时透明 rehash）';
COMMENT ON COLUMN eaio_iam.sys_user.mfa_secret IS 'TOTP 秘钥密文，格式 v1:<kid>:<b64(iv)>:<b64(ct+tag)>，AES-256-GCM';
COMMENT ON COLUMN eaio_iam.sys_user.token_version IS '用户级令牌版本；自增即让该用户全部 access token 立即失效';

CREATE TABLE eaio_iam.user_org (
  id BIGINT NOT NULL, user_id BIGINT NOT NULL, org_id BIGINT NOT NULL, position_id BIGINT,
  is_primary BOOLEAN NOT NULL DEFAULT false, status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  start_date DATE, end_date DATE,
  created_at TIMESTAMPTZ NOT NULL, created_by BIGINT, updated_at TIMESTAMPTZ, updated_by BIGINT,
  version INT NOT NULL DEFAULT 0, deleted BOOLEAN NOT NULL DEFAULT false,
  CONSTRAINT pk_user_org PRIMARY KEY (id),
  CONSTRAINT ck_user_org_status CHECK (status IN ('PENDING','ACTIVE','SUSPENDED')),
  CONSTRAINT fk_user_org_user FOREIGN KEY (user_id) REFERENCES eaio_iam.sys_user (id),
  CONSTRAINT fk_user_org_org FOREIGN KEY (org_id) REFERENCES eaio_iam.org_node (id)
);
CREATE UNIQUE INDEX uk_user_org_user_org ON eaio_iam.user_org (user_id, org_id) WHERE deleted = false;
CREATE UNIQUE INDEX uk_user_org_primary ON eaio_iam.user_org (user_id) WHERE is_primary AND deleted = false;
CREATE INDEX idx_user_org_org ON eaio_iam.user_org (org_id) WHERE deleted = false;
CREATE INDEX idx_user_org_position ON eaio_iam.user_org (position_id) WHERE deleted = false;
COMMENT ON TABLE eaio_iam.user_org IS '用户与组织的挂载（多组织兼任，FR-HR-08）；同用户至多一条 is_primary=true';

CREATE TABLE eaio_iam.user_password_history (
  id BIGINT NOT NULL, user_id BIGINT NOT NULL, password_hash VARCHAR(255) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL, created_by BIGINT,
  CONSTRAINT pk_user_password_history PRIMARY KEY (id),
  CONSTRAINT fk_user_password_history_user FOREIGN KEY (user_id) REFERENCES eaio_iam.sys_user (id)
);
CREATE INDEX idx_user_password_history_user ON eaio_iam.user_password_history (user_id, created_at DESC);
COMMENT ON TABLE eaio_iam.user_password_history IS '口令历史（append-only，防重用最近 N 次；超出 N 条的旧记录按清理任务删除）';

CREATE TABLE eaio_iam.mfa_recovery_code (
  id BIGINT NOT NULL, user_id BIGINT NOT NULL, code_hash CHAR(64) NOT NULL, used_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL, created_by BIGINT,
  CONSTRAINT pk_mfa_recovery_code PRIMARY KEY (id),
  CONSTRAINT fk_mfa_recovery_code_user FOREIGN KEY (user_id) REFERENCES eaio_iam.sys_user (id)
);
CREATE INDEX idx_mfa_recovery_code_user ON eaio_iam.mfa_recovery_code (user_id) WHERE used_at IS NULL;
COMMENT ON TABLE eaio_iam.mfa_recovery_code IS 'MFA 恢复码（SHA-256 哈希存储，一次性；重新生成时删除旧码）';
```

#### 4.3.5 `V5__employee.sql`：员工任职与高敏感字段

| 表 | 用途 | 行数预估 | 保留 |
|---|---|---|---|
| `employee` | 员工任职主档（入转调离、雇佣类型、工作联系方式） | 中（万级） | 永久（逻辑删除） |
| `employee_sensitive` | 高敏感字段（证件号/银行账号，AES-GCM 密文 + 查重哈希） | 中 | 永久（离职后按 SRS 6.2 保留 ≥5 年） |

```sql
CREATE TABLE eaio_iam.employee (
  id BIGINT NOT NULL, user_id BIGINT NOT NULL, employee_no VARCHAR(64) NOT NULL,
  name VARCHAR(64) NOT NULL, gender VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN', birth_date DATE,
  hire_date DATE NOT NULL, leave_date DATE,
  employment_type VARCHAR(16) NOT NULL DEFAULT 'FULL_TIME',
  status VARCHAR(16) NOT NULL DEFAULT 'PROBATION',
  work_email VARCHAR(128), work_phone VARCHAR(32), description VARCHAR(255),
  created_at TIMESTAMPTZ NOT NULL, created_by BIGINT, updated_at TIMESTAMPTZ, updated_by BIGINT,
  version INT NOT NULL DEFAULT 0, deleted BOOLEAN NOT NULL DEFAULT false,
  CONSTRAINT pk_employee PRIMARY KEY (id),
  CONSTRAINT ck_employee_status CHECK (status IN ('PROBATION','ACTIVE','LEAVE','RESIGNED')),
  CONSTRAINT ck_employee_employment_type CHECK (employment_type IN ('FULL_TIME','PART_TIME','INTERN','OUTSOURCE')),
  CONSTRAINT fk_employee_user FOREIGN KEY (user_id) REFERENCES eaio_iam.sys_user (id)
);
CREATE UNIQUE INDEX uk_employee_no ON eaio_iam.employee (employee_no) WHERE deleted = false;
CREATE INDEX idx_employee_user ON eaio_iam.employee (user_id) WHERE deleted = false;
CREATE INDEX idx_employee_status_hire ON eaio_iam.employee (status, hire_date);
COMMENT ON TABLE eaio_iam.employee IS '员工任职主档（与 hr 的边界：薪酬/考勤在 P2 hr，本表只承载任职事实）';

CREATE TABLE eaio_iam.employee_sensitive (
  id BIGINT NOT NULL, employee_id BIGINT NOT NULL,
  id_card_cipher TEXT, id_card_hash CHAR(64), bank_account_cipher TEXT, bank_name VARCHAR(128), kid VARCHAR(32),
  created_at TIMESTAMPTZ NOT NULL, created_by BIGINT, updated_at TIMESTAMPTZ, updated_by BIGINT,
  version INT NOT NULL DEFAULT 0, deleted BOOLEAN NOT NULL DEFAULT false,
  CONSTRAINT pk_employee_sensitive PRIMARY KEY (id),
  CONSTRAINT fk_employee_sensitive_employee FOREIGN KEY (employee_id) REFERENCES eaio_iam.employee (id)
);
CREATE UNIQUE INDEX uk_employee_sensitive_employee ON eaio_iam.employee_sensitive (employee_id) WHERE deleted = false;
CREATE UNIQUE INDEX uk_employee_sensitive_id_card_hash ON eaio_iam.employee_sensitive (id_card_hash) WHERE deleted = false AND id_card_hash IS NOT NULL;
COMMENT ON TABLE eaio_iam.employee_sensitive IS '员工高敏感字段（SRS 6.1 高敏感档）：密文 + 查重哈希，独立字段权限与保留策略';
COMMENT ON COLUMN eaio_iam.employee_sensitive.id_card_hash IS 'HMAC-SHA256(派生密钥, 明文证件号)，仅用于查重与等值查询，不可逆推';
```

#### 4.3.6 `V6__rbac.sql`：角色与权限点

| 表 | 用途 | 行数预估 | 保留 |
|---|---|---|---|
| `role` | 角色（含继承 `parent_id`、集团模板 `is_template`、组织范围 `org_scope_id`） | 中 | 永久 |
| `permission` | 权限点（树形：MENU/BUTTON/API/DATA；同时是菜单树的数据源） | 中（千级） | 永久 |
| `role_permission` | 角色-权限点（`effect` 承载覆盖/REVOKE） | 大 | 永久 |
| `user_role` | 用户-角色（带组织范围与生效期） | 大 | 永久（回收置 `REVOKED`） |

```sql
CREATE TABLE eaio_iam.role (
  id BIGINT NOT NULL, code VARCHAR(64) NOT NULL, name VARCHAR(128) NOT NULL,
  parent_id BIGINT, inherit_mode VARCHAR(32) NOT NULL DEFAULT 'INHERIT_AND_OVERRIDE',
  is_template BOOLEAN NOT NULL DEFAULT false, org_scope_id BIGINT,
  role_type VARCHAR(16) NOT NULL DEFAULT 'CUSTOM', sort_order INT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT 'ENABLED', description VARCHAR(255),
  created_at TIMESTAMPTZ NOT NULL, created_by BIGINT, updated_at TIMESTAMPTZ, updated_by BIGINT,
  version INT NOT NULL DEFAULT 0, deleted BOOLEAN NOT NULL DEFAULT false,
  CONSTRAINT pk_role PRIMARY KEY (id),
  CONSTRAINT ck_role_status CHECK (status IN ('ENABLED','DISABLED')),
  CONSTRAINT ck_role_type CHECK (role_type IN ('SYSTEM','TEMPLATE','CUSTOM')),
  CONSTRAINT ck_role_inherit_mode CHECK (inherit_mode IN ('INHERIT_ONLY','INHERIT_AND_OVERRIDE','NO_INHERIT')),
  CONSTRAINT fk_role_parent FOREIGN KEY (parent_id) REFERENCES eaio_iam.role (id)
);
CREATE UNIQUE INDEX uk_role_code ON eaio_iam.role (code) WHERE deleted = false;
CREATE INDEX idx_role_parent ON eaio_iam.role (parent_id) WHERE deleted = false;
CREATE INDEX idx_role_org_scope ON eaio_iam.role (org_scope_id) WHERE deleted = false;
COMMENT ON TABLE eaio_iam.role IS '角色（单亲继承 parent_id ≤ 5 层；is_template=true 为集团模板，不可直接分配给用户）';

CREATE TABLE eaio_iam.permission (
  id BIGINT NOT NULL, code VARCHAR(128) NOT NULL, name VARCHAR(128) NOT NULL,
  perm_type VARCHAR(16) NOT NULL, parent_id BIGINT, module VARCHAR(32) NOT NULL,
  resource_code VARCHAR(64), route_path VARCHAR(255), icon VARCHAR(64),
  sort_order INT NOT NULL DEFAULT 0, status VARCHAR(16) NOT NULL DEFAULT 'ENABLED', description VARCHAR(255),
  created_at TIMESTAMPTZ NOT NULL, created_by BIGINT, updated_at TIMESTAMPTZ, updated_by BIGINT,
  version INT NOT NULL DEFAULT 0, deleted BOOLEAN NOT NULL DEFAULT false,
  CONSTRAINT pk_permission PRIMARY KEY (id),
  CONSTRAINT ck_permission_perm_type CHECK (perm_type IN ('MENU','BUTTON','API','DATA')),
  CONSTRAINT ck_permission_status CHECK (status IN ('ENABLED','DISABLED')),
  CONSTRAINT fk_permission_parent FOREIGN KEY (parent_id) REFERENCES eaio_iam.permission (id)
);
CREATE UNIQUE INDEX uk_permission_code ON eaio_iam.permission (code) WHERE deleted = false;
CREATE INDEX idx_permission_parent ON eaio_iam.permission (parent_id) WHERE deleted = false;
CREATE INDEX idx_permission_module ON eaio_iam.permission (module, perm_type) WHERE deleted = false;
COMMENT ON TABLE eaio_iam.permission IS '权限点（全局元数据，不区分组织）；perm_type=MENU 的节点同时构成前端菜单树';
COMMENT ON COLUMN eaio_iam.permission.resource_code IS '可选：关联数据权限资源码（data_scope_resource.resource_code），DATA 类权限点使用';

CREATE TABLE eaio_iam.role_permission (
  id BIGINT NOT NULL, role_id BIGINT NOT NULL, permission_id BIGINT NOT NULL,
  effect VARCHAR(8) NOT NULL DEFAULT 'GRANT',
  created_at TIMESTAMPTZ NOT NULL, created_by BIGINT, updated_at TIMESTAMPTZ, updated_by BIGINT,
  version INT NOT NULL DEFAULT 0, deleted BOOLEAN NOT NULL DEFAULT false,
  CONSTRAINT pk_role_permission PRIMARY KEY (id),
  CONSTRAINT ck_role_permission_effect CHECK (effect IN ('GRANT','REVOKE')),
  CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES eaio_iam.role (id),
  CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES eaio_iam.permission (id)
);
CREATE UNIQUE INDEX uk_role_permission_role_perm ON eaio_iam.role_permission (role_id, permission_id) WHERE deleted = false;
CREATE INDEX idx_role_permission_permission ON eaio_iam.role_permission (permission_id);
COMMENT ON TABLE eaio_iam.role_permission IS '角色-权限点；effect=REVOKE 用于子角色覆盖父角色（继承求值时 REVOKE 优先）';

CREATE TABLE eaio_iam.user_role (
  id BIGINT NOT NULL, user_id BIGINT NOT NULL, role_id BIGINT NOT NULL, org_scope_id BIGINT,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', start_at TIMESTAMPTZ, end_at TIMESTAMPTZ,
  granted_by BIGINT, granted_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL, created_by BIGINT, updated_at TIMESTAMPTZ, updated_by BIGINT,
  version INT NOT NULL DEFAULT 0, deleted BOOLEAN NOT NULL DEFAULT false,
  CONSTRAINT pk_user_role PRIMARY KEY (id),
  CONSTRAINT ck_user_role_status CHECK (status IN ('ACTIVE','REVOKED')),
  CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES eaio_iam.sys_user (id),
  CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES eaio_iam.role (id)
);
CREATE UNIQUE INDEX uk_user_role_active ON eaio_iam.user_role (user_id, role_id, coalesce(org_scope_id, 0)) WHERE status = 'ACTIVE' AND deleted = false;
CREATE INDEX idx_user_role_user ON eaio_iam.user_role (user_id) WHERE status = 'ACTIVE' AND deleted = false;
CREATE INDEX idx_user_role_role ON eaio_iam.user_role (role_id);
COMMENT ON TABLE eaio_iam.user_role IS '用户-角色授权（org_scope_id 为 NULL 表示全局生效；start_at/end_at 支持临时授权）';
```

#### 4.3.7 `V7__authz_scope.sql`：数据范围、字段权限、ABAC

| 表 | 用途 | 行数预估 | 保留 |
|---|---|---|---|
| `data_scope_resource` | 数据权限资源白名单（表名/列名映射，改写的唯一开关） | 小（百级） | 永久 |
| `data_scope_rule` | 数据范围规则（主体 × 资源 × 范围类型） | 中 | 永久 |
| `field_permission` | 字段权限（角色 × 资源 × 字段 × 可见/可编辑/掩码） | 大 | 永久 |
| `abac_policy` | ABAC 属性策略（资源 × 动作 × 条件 JSON） | 小 | 永久 |

```sql
CREATE TABLE eaio_iam.data_scope_resource (
  id BIGINT NOT NULL, resource_code VARCHAR(64) NOT NULL, table_name VARCHAR(64) NOT NULL,
  org_column VARCHAR(64), owner_column VARCHAR(64), dept_column VARCHAR(64),
  max_scope VARCHAR(16) NOT NULL DEFAULT 'ORG_AND_SUB', enabled BOOLEAN NOT NULL DEFAULT true,
  description VARCHAR(255),
  created_at TIMESTAMPTZ NOT NULL, created_by BIGINT, updated_at TIMESTAMPTZ, updated_by BIGINT,
  version INT NOT NULL DEFAULT 0, deleted BOOLEAN NOT NULL DEFAULT false,
  CONSTRAINT pk_data_scope_resource PRIMARY KEY (id),
  CONSTRAINT ck_data_scope_resource_max_scope CHECK (max_scope IN ('SELF','DEPT','DEPT_AND_SUB','ORG','ORG_AND_SUB','ALL','CUSTOM'))
);
CREATE UNIQUE INDEX uk_data_scope_resource_code ON eaio_iam.data_scope_resource (resource_code) WHERE deleted = false;
COMMENT ON TABLE eaio_iam.data_scope_resource IS '数据权限资源白名单：只有登记且 enabled 的表参与 SQL 改写（未登记=不注入）';
COMMENT ON COLUMN eaio_iam.data_scope_resource.table_name IS '物理表名；启动期校验其与 org_column/owner_column 在 information_schema 中存在，缺失即启动失败';

CREATE TABLE eaio_iam.data_scope_rule (
  id BIGINT NOT NULL, subject_type VARCHAR(8) NOT NULL, subject_id BIGINT NOT NULL,
  resource_code VARCHAR(64) NOT NULL, scope_type VARCHAR(16) NOT NULL, custom_org_ids TEXT,
  effect VARCHAR(8) NOT NULL DEFAULT 'ALLOW', priority INT NOT NULL DEFAULT 0,
  valid_from TIMESTAMPTZ, valid_to TIMESTAMPTZ, description VARCHAR(255),
  created_at TIMESTAMPTZ NOT NULL, created_by BIGINT, updated_at TIMESTAMPTZ, updated_by BIGINT,
  version INT NOT NULL DEFAULT 0, deleted BOOLEAN NOT NULL DEFAULT false,
  CONSTRAINT pk_data_scope_rule PRIMARY KEY (id),
  CONSTRAINT ck_data_scope_rule_subject_type CHECK (subject_type IN ('ROLE','USER')),
  CONSTRAINT ck_data_scope_rule_scope_type CHECK (scope_type IN ('SELF','DEPT','DEPT_AND_SUB','ORG','ORG_AND_SUB','ALL','CUSTOM')),
  CONSTRAINT ck_data_scope_rule_effect CHECK (effect IN ('ALLOW','DENY'))
);
CREATE INDEX idx_data_scope_rule_subject ON eaio_iam.data_scope_rule (subject_type, subject_id) WHERE deleted = false;
CREATE INDEX idx_data_scope_rule_resource ON eaio_iam.data_scope_rule (resource_code) WHERE deleted = false;
COMMENT ON TABLE eaio_iam.data_scope_rule IS '数据范围规则（主体可为角色或用户；同主体同资源多条取并集，DENY 优先）';
COMMENT ON COLUMN eaio_iam.data_scope_rule.custom_org_ids IS '仅 CUSTOM 使用：组织 id 的 JSON 数组文本（TEXT 而非 JSONB，信创留口）';

CREATE TABLE eaio_iam.field_permission (
  id BIGINT NOT NULL, role_id BIGINT NOT NULL, resource_code VARCHAR(64) NOT NULL,
  field_name VARCHAR(64) NOT NULL, visible BOOLEAN NOT NULL DEFAULT true, editable BOOLEAN NOT NULL DEFAULT true,
  mask_type VARCHAR(16) NOT NULL DEFAULT 'NONE',
  created_at TIMESTAMPTZ NOT NULL, created_by BIGINT, updated_at TIMESTAMPTZ, updated_by BIGINT,
  version INT NOT NULL DEFAULT 0, deleted BOOLEAN NOT NULL DEFAULT false,
  CONSTRAINT pk_field_permission PRIMARY KEY (id),
  CONSTRAINT ck_field_permission_mask_type CHECK (mask_type IN ('NONE','PHONE','ID_CARD','BANK_CARD','EMAIL','NAME','FULL')),
  CONSTRAINT fk_field_permission_role FOREIGN KEY (role_id) REFERENCES eaio_iam.role (id)
);
CREATE UNIQUE INDEX uk_field_permission_role_res_field ON eaio_iam.field_permission (role_id, resource_code, field_name) WHERE deleted = false;
COMMENT ON TABLE eaio_iam.field_permission IS '字段级权限（角色 × 资源 × 字段）：序列化前统一裁剪/掩码，禁止各模块自行裁剪';

CREATE TABLE eaio_iam.abac_policy (
  id BIGINT NOT NULL, code VARCHAR(64) NOT NULL, name VARCHAR(128) NOT NULL,
  resource_code VARCHAR(64) NOT NULL, action VARCHAR(64) NOT NULL,
  effect VARCHAR(8) NOT NULL, condition_text TEXT NOT NULL, priority INT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT 'ENABLED', description VARCHAR(255),
  created_at TIMESTAMPTZ NOT NULL, created_by BIGINT, updated_at TIMESTAMPTZ, updated_by BIGINT,
  version INT NOT NULL DEFAULT 0, deleted BOOLEAN NOT NULL DEFAULT false,
  CONSTRAINT pk_abac_policy PRIMARY KEY (id),
  CONSTRAINT ck_abac_policy_effect CHECK (effect IN ('PERMIT','DENY')),
  CONSTRAINT ck_abac_policy_status CHECK (status IN ('ENABLED','DISABLED'))
);
CREATE UNIQUE INDEX uk_abac_policy_code ON eaio_iam.abac_policy (code) WHERE deleted = false;
CREATE INDEX idx_abac_policy_target ON eaio_iam.abac_policy (resource_code, action, status) WHERE deleted = false;
COMMENT ON TABLE eaio_iam.abac_policy IS 'ABAC 属性策略（动作级判定，不做行过滤）；action 支持通配 *';
COMMENT ON COLUMN eaio_iam.abac_policy.condition_text IS '条件 DSL 的 JSON 文本：{"all":[…],"any":[…],"none":[…]}，操作符白名单见 3.7.2（禁 REGEX）';
```

#### 4.3.8 `V8__sod.sql`：职责分离

| 表 | 用途 | 行数预估 | 保留 |
|---|---|---|---|
| `sod_rule` | SoD 规则（严重级、作用域、是否强制拦截 `enforce`） | 小（百级） | 永久 |
| `sod_rule_permission` | 规则内的权限分组（不同 `mutual_group` 互斥） | 小（千级） | 永久 |
| `sod_exemption` | 豁免（带期限、审批人、理由） | 小 | 永久（过期保留供审计） |

```sql
CREATE TABLE eaio_iam.sod_rule (
  id BIGINT NOT NULL, code VARCHAR(64) NOT NULL, name VARCHAR(128) NOT NULL,
  severity VARCHAR(8) NOT NULL DEFAULT 'HIGH', scope_type VARCHAR(8) NOT NULL DEFAULT 'GLOBAL', org_id BIGINT,
  enforce BOOLEAN NOT NULL DEFAULT true, status VARCHAR(16) NOT NULL DEFAULT 'ENABLED', description VARCHAR(255),
  created_at TIMESTAMPTZ NOT NULL, created_by BIGINT, updated_at TIMESTAMPTZ, updated_by BIGINT,
  version INT NOT NULL DEFAULT 0, deleted BOOLEAN NOT NULL DEFAULT false,
  CONSTRAINT pk_sod_rule PRIMARY KEY (id),
  CONSTRAINT ck_sod_rule_severity CHECK (severity IN ('HIGH','MEDIUM')),
  CONSTRAINT ck_sod_rule_scope_type CHECK (scope_type IN ('GLOBAL','ORG')),
  CONSTRAINT ck_sod_rule_status CHECK (status IN ('ENABLED','DISABLED'))
);
CREATE UNIQUE INDEX uk_sod_rule_code ON eaio_iam.sod_rule (code) WHERE deleted = false;
CREATE INDEX idx_sod_rule_scope ON eaio_iam.sod_rule (scope_type, org_id) WHERE deleted = false;
COMMENT ON TABLE eaio_iam.sod_rule IS 'SoD 互斥规则；enforce=false 为过渡期（仅告警不拦截），存量违规清理后置 true';

CREATE TABLE eaio_iam.sod_rule_permission (
  id BIGINT NOT NULL, rule_id BIGINT NOT NULL, permission_code VARCHAR(128) NOT NULL, mutual_group SMALLINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL, created_by BIGINT, updated_at TIMESTAMPTZ, updated_by BIGINT,
  version INT NOT NULL DEFAULT 0, deleted BOOLEAN NOT NULL DEFAULT false,
  CONSTRAINT pk_sod_rule_permission PRIMARY KEY (id),
  CONSTRAINT ck_sod_rule_permission_group CHECK (mutual_group BETWEEN 1 AND 9),
  CONSTRAINT fk_sod_rule_permission_rule FOREIGN KEY (rule_id) REFERENCES eaio_iam.sod_rule (id)
);
CREATE UNIQUE INDEX uk_sod_rule_permission_rule_perm ON eaio_iam.sod_rule_permission (rule_id, permission_code) WHERE deleted = false;
COMMENT ON TABLE eaio_iam.sod_rule_permission IS 'SoD 规则内权限分组：同一 rule 内不同 mutual_group 之间互斥，同组内不互斥';

CREATE TABLE eaio_iam.sod_exemption (
  id BIGINT NOT NULL, rule_id BIGINT NOT NULL, user_id BIGINT NOT NULL, reason VARCHAR(255) NOT NULL,
  approved_by BIGINT NOT NULL, valid_from TIMESTAMPTZ NOT NULL, valid_to TIMESTAMPTZ NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMPTZ NOT NULL, created_by BIGINT, updated_at TIMESTAMPTZ, updated_by BIGINT,
  version INT NOT NULL DEFAULT 0, deleted BOOLEAN NOT NULL DEFAULT false,
  CONSTRAINT pk_sod_exemption PRIMARY KEY (id),
  CONSTRAINT ck_sod_exemption_status CHECK (status IN ('ACTIVE','EXPIRED','REVOKED')),
  CONSTRAINT fk_sod_exemption_rule FOREIGN KEY (rule_id) REFERENCES eaio_iam.sod_rule (id),
  CONSTRAINT fk_sod_exemption_user FOREIGN KEY (user_id) REFERENCES eaio_iam.sys_user (id)
);
CREATE INDEX idx_sod_exemption_user ON eaio_iam.sod_exemption (user_id, status);
CREATE INDEX idx_sod_exemption_valid_to ON eaio_iam.sod_exemption (status, valid_to);
COMMENT ON TABLE eaio_iam.sod_exemption IS 'SoD 豁免（有期限 + 审批人 + 理由）；到期由任务置 EXPIRED 并产生复核待办';
```

#### 4.3.9 `V9__access_grant.sql`：显式授权（跨公司审批/临时范围/委托）

| 表 | 用途 | 行数预估 | 保留 |
|---|---|---|---|
| `access_grant` | 显式授权：跨公司审批、临时数据范围、委托代理 | 流水（百万级） | 保留 3 年（过期后按任务归档清理） |

```sql
CREATE TABLE eaio_iam.access_grant (
  id BIGINT NOT NULL, grantee_user_id BIGINT NOT NULL,
  grant_type VARCHAR(24) NOT NULL, resource_code VARCHAR(64) NOT NULL, business_id VARCHAR(64),
  org_ids TEXT, valid_from TIMESTAMPTZ NOT NULL, valid_to TIMESTAMPTZ NOT NULL,
  granted_by BIGINT NOT NULL, reason VARCHAR(255), status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMPTZ NOT NULL, created_by BIGINT, updated_at TIMESTAMPTZ, updated_by BIGINT,
  version INT NOT NULL DEFAULT 0, deleted BOOLEAN NOT NULL DEFAULT false,
  CONSTRAINT pk_access_grant PRIMARY KEY (id),
  CONSTRAINT ck_access_grant_type CHECK (grant_type IN ('APPROVAL_CROSS_ORG','TEMP_DATA_SCOPE','DELEGATION')),
  CONSTRAINT ck_access_grant_status CHECK (status IN ('ACTIVE','EXPIRED','REVOKED')),
  CONSTRAINT fk_access_grant_grantee FOREIGN KEY (grantee_user_id) REFERENCES eaio_iam.sys_user (id)
);
CREATE INDEX idx_access_grant_grantee ON eaio_iam.access_grant (grantee_user_id, resource_code, status);
CREATE INDEX idx_access_grant_business ON eaio_iam.access_grant (resource_code, business_id) WHERE business_id IS NOT NULL;
CREATE INDEX idx_access_grant_expire ON eaio_iam.access_grant (status, valid_to);
COMMENT ON TABLE eaio_iam.access_grant IS '显式授权：跨公司审批（business_id 粒度）、临时数据范围、委托；过期由 JobHandler code=iam.access-grant.expire 处理';
COMMENT ON COLUMN eaio_iam.access_grant.business_id IS '业务主键；非空表示只放开"这一条/这一批"数据，为空表示放开该资源（仅 TEMP_DATA_SCOPE 允许）';
```

#### 4.3.10 `V10__auth.sql`：会话、刷新令牌、登录流水

| 表 | 用途 | 行数预估 | 保留 |
|---|---|---|---|
| `auth_session` | 逻辑会话（一设备一行；Redis 索引的真源） | 中（在线数 × 会话倍数） | 撤销/过期后保留 180 天（安全排查） |
| `auth_refresh_token` | 刷新令牌链（哈希存储、轮换、复用检测） | 流水 | 180 天 |
| `login_attempt` | 登录尝试流水（append-only，用于暴力破解分析与审计取证） | 流水（百万级） | 180 天 |

```sql
CREATE TABLE eaio_iam.auth_session (
  id BIGINT NOT NULL, sid VARCHAR(64) NOT NULL, user_id BIGINT NOT NULL, org_id BIGINT,
  refresh_family_id VARCHAR(64) NOT NULL, login_type VARCHAR(16) NOT NULL DEFAULT 'PASSWORD',
  device VARCHAR(128), user_agent VARCHAR(255), login_ip VARCHAR(64),
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', revoked_reason VARCHAR(32),
  last_seen_at TIMESTAMPTZ, expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL, created_by BIGINT, updated_at TIMESTAMPTZ, updated_by BIGINT,
  version INT NOT NULL DEFAULT 0, deleted BOOLEAN NOT NULL DEFAULT false,
  CONSTRAINT pk_auth_session PRIMARY KEY (id),
  CONSTRAINT ck_auth_session_status CHECK (status IN ('ACTIVE','REVOKED','EXPIRED')),
  CONSTRAINT ck_auth_session_login_type CHECK (login_type IN ('PASSWORD','MFA','OIDC','SAML2','LDAP','CLIENT')),
  CONSTRAINT fk_auth_session_user FOREIGN KEY (user_id) REFERENCES eaio_iam.sys_user (id)
);
CREATE UNIQUE INDEX uk_auth_session_sid ON eaio_iam.auth_session (sid);
CREATE INDEX idx_auth_session_user ON eaio_iam.auth_session (user_id, status);
CREATE INDEX idx_auth_session_org ON eaio_iam.auth_session (org_id, status);
CREATE INDEX idx_auth_session_expire ON eaio_iam.auth_session (status, expires_at);
COMMENT ON TABLE eaio_iam.auth_session IS '登录会话（多设备）；Redis 键 eaio:{env}:iam:session:{sid} 为在线判定快路径，本表为真源';

CREATE TABLE eaio_iam.auth_refresh_token (
  id BIGINT NOT NULL, session_id BIGINT NOT NULL, family_id VARCHAR(64) NOT NULL,
  token_hash CHAR(64) NOT NULL, parent_hash CHAR(64), rotated_to CHAR(64),
  issued_at TIMESTAMPTZ NOT NULL, expires_at TIMESTAMPTZ NOT NULL, used_at TIMESTAMPTZ,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMPTZ NOT NULL, created_by BIGINT,
  CONSTRAINT pk_auth_refresh_token PRIMARY KEY (id),
  CONSTRAINT ck_auth_refresh_token_status CHECK (status IN ('ACTIVE','USED','REVOKED')),
  CONSTRAINT fk_auth_refresh_token_session FOREIGN KEY (session_id) REFERENCES eaio_iam.auth_session (id)
);
CREATE UNIQUE INDEX uk_auth_refresh_token_hash ON eaio_iam.auth_refresh_token (token_hash);
CREATE INDEX idx_auth_refresh_token_family ON eaio_iam.auth_refresh_token (family_id, status);
CREATE INDEX idx_auth_refresh_token_session ON eaio_iam.auth_refresh_token (session_id);
COMMENT ON TABLE eaio_iam.auth_refresh_token IS '刷新令牌链（只存 SHA-256 哈希）：used_at 非空再次出现 = 令牌泄漏 → 撤销整个 family';

CREATE TABLE eaio_iam.login_attempt (
  id BIGINT NOT NULL, username VARCHAR(64), user_id BIGINT, org_id BIGINT,
  result VARCHAR(16) NOT NULL, reason VARCHAR(64), ip VARCHAR(64), user_agent VARCHAR(255), trace_id VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT pk_login_attempt PRIMARY KEY (id),
  CONSTRAINT ck_login_attempt_result CHECK (result IN ('SUCCESS','FAIL_PASSWORD','FAIL_LOCKED','FAIL_CAPTCHA','FAIL_MFA','FAIL_DISABLED','FAIL_IDP'))
);
CREATE INDEX idx_login_attempt_username_time ON eaio_iam.login_attempt (username, created_at DESC);
CREATE INDEX idx_login_attempt_ip_time ON eaio_iam.login_attempt (ip, created_at DESC);
COMMENT ON TABLE eaio_iam.login_attempt IS '登录尝试流水（append-only）；本地只做安全分析与取证，完整审计归 audit（M4）';
```

#### 4.3.11 `V11__idp.sql`：外部身份源

| 表 | 用途 | 行数预估 | 保留 |
|---|---|---|---|
| `sso_config` | OIDC / SAML2 身份源配置（SP 侧；凭据加密） | 小 | 永久（删除前置校验见 3.3.8） |
| `ldap_config` | LDAP 目录配置（bind 凭据加密、同步策略） | 小 | 永久 |

```sql
CREATE TABLE eaio_iam.sso_config (
  id BIGINT NOT NULL, code VARCHAR(64) NOT NULL, name VARCHAR(128) NOT NULL,
  protocol VARCHAR(8) NOT NULL, issuer_uri VARCHAR(512), client_id VARCHAR(255), client_secret_cipher TEXT,
  metadata_xml TEXT, entity_id VARCHAR(255), acs_url VARCHAR(512), scopes VARCHAR(255),
  attribute_mapping TEXT, domain VARCHAR(128), org_id BIGINT,
  auto_create_user BOOLEAN NOT NULL DEFAULT false, enabled BOOLEAN NOT NULL DEFAULT true, kid VARCHAR(32),
  created_at TIMESTAMPTZ NOT NULL, created_by BIGINT, updated_at TIMESTAMPTZ, updated_by BIGINT,
  version INT NOT NULL DEFAULT 0, deleted BOOLEAN NOT NULL DEFAULT false,
  CONSTRAINT pk_sso_config PRIMARY KEY (id),
  CONSTRAINT ck_sso_config_protocol CHECK (protocol IN ('OIDC','SAML2'))
);
CREATE UNIQUE INDEX uk_sso_config_code ON eaio_iam.sso_config (code) WHERE deleted = false;
CREATE INDEX idx_sso_config_domain ON eaio_iam.sso_config (domain) WHERE deleted = false AND enabled;
COMMENT ON TABLE eaio_iam.sso_config IS 'SSO 身份源（SP 侧配置）；client_secret_cipher 为 AES-GCM 密文，永不出现在响应中';
COMMENT ON COLUMN eaio_iam.sso_config.attribute_mapping IS '属性映射 JSON，如 {"dept":"department","mobile":"phone_number"}；只映射到非主档字段';

CREATE TABLE eaio_iam.ldap_config (
  id BIGINT NOT NULL, code VARCHAR(64) NOT NULL, name VARCHAR(128) NOT NULL, url VARCHAR(512) NOT NULL,
  base_dn VARCHAR(255) NOT NULL, bind_dn VARCHAR(255), bind_password_cipher TEXT, kid VARCHAR(32),
  user_filter VARCHAR(255) NOT NULL, attribute_mapping TEXT,
  sync_mode VARCHAR(16) NOT NULL DEFAULT 'ON_LOGIN', sync_cron VARCHAR(64),
  auto_create_user BOOLEAN NOT NULL DEFAULT false, enabled BOOLEAN NOT NULL DEFAULT true, last_sync_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL, created_by BIGINT, updated_at TIMESTAMPTZ, updated_by BIGINT,
  version INT NOT NULL DEFAULT 0, deleted BOOLEAN NOT NULL DEFAULT false,
  CONSTRAINT pk_ldap_config PRIMARY KEY (id),
  CONSTRAINT ck_ldap_config_sync_mode CHECK (sync_mode IN ('ON_LOGIN','SCHEDULED','DISABLED'))
);
CREATE UNIQUE INDEX uk_ldap_config_code ON eaio_iam.ldap_config (code) WHERE deleted = false;
COMMENT ON TABLE eaio_iam.ldap_config IS 'LDAP 目录配置；url 强制 ldaps://；bind_password_cipher 为 AES-GCM 密文';
COMMENT ON COLUMN eaio_iam.ldap_config.user_filter IS '用户过滤器（含 {0} 占位，如 (sAMAccountName={0})）；禁止拼接未转义的用户输入';
```

#### 4.3.12 `V12__lifecycle_snapshot.sql`：生命周期流水与权限快照

| 表 | 用途 | 行数预估 | 保留 |
|---|---|---|---|
| `org_lifecycle_log` | 组织生命周期流转流水（含影响面与交接对象） | 小（千级） | 永久（append-only，合规留痕） |
| `permission_snapshot` | 权限快照（Redis 的可重建副本，派生） | 中（用户 × 组织） | 覆盖式（每次重算更新一行） |

```sql
CREATE TABLE eaio_iam.org_lifecycle_log (
  id BIGINT NOT NULL, node_id BIGINT NOT NULL, from_state VARCHAR(16), to_state VARCHAR(16) NOT NULL,
  reason VARCHAR(255), affected_user_count INT NOT NULL DEFAULT 0, handover_to_org_id BIGINT,
  operator_id BIGINT, created_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT pk_org_lifecycle_log PRIMARY KEY (id),
  CONSTRAINT fk_org_lifecycle_log_node FOREIGN KEY (node_id) REFERENCES eaio_iam.org_node (id)
);
CREATE INDEX idx_org_lifecycle_log_node ON eaio_iam.org_lifecycle_log (node_id, created_at DESC);
COMMENT ON TABLE eaio_iam.org_lifecycle_log IS '组织生命周期流转流水（append-only）：注销/归档的影响面与数据交接对象留痕';

CREATE TABLE eaio_iam.permission_snapshot (
  id BIGINT NOT NULL, user_id BIGINT NOT NULL, org_id BIGINT NOT NULL, permissions_version INT NOT NULL,
  permission_codes TEXT NOT NULL, data_scope_summary TEXT, field_permission_summary TEXT,
  computed_at TIMESTAMPTZ NOT NULL, expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL, created_by BIGINT, updated_at TIMESTAMPTZ, updated_by BIGINT,
  version INT NOT NULL DEFAULT 0,
  CONSTRAINT pk_permission_snapshot PRIMARY KEY (id),
  CONSTRAINT fk_permission_snapshot_user FOREIGN KEY (user_id) REFERENCES eaio_iam.sys_user (id)
);
CREATE UNIQUE INDEX uk_permission_snapshot_user_org ON eaio_iam.permission_snapshot (user_id, org_id);
COMMENT ON TABLE eaio_iam.permission_snapshot IS '权限快照（派生表，Redis 之外的可重建副本）：Redis 全丢时可回源重建，无需重算全部用户';
```

#### 4.3.13 `V13__event.sql`：事务性发件箱与消费幂等

| 表 | 用途 | 行数预估 | 保留 |
|---|---|---|---|
| `event_outbox` | 事务性发件箱（与业务同事务写入，发布器投递） | 流水（百万级） | PUBLISHED 保留 30 天；DEAD 长期保留待人工处置 |
| `event_consume_record` | 消费幂等记录（`event_id + consumer + business_id` 去重） | 流水 | 30 天（与事件保留期一致） |

```sql
CREATE TABLE eaio_iam.event_outbox (
  id BIGINT NOT NULL, event_id VARCHAR(64) NOT NULL, event_type VARCHAR(64) NOT NULL,
  aggregate_type VARCHAR(64), aggregate_id VARCHAR(64), payload_json TEXT NOT NULL,
  org_id BIGINT, operator_id BIGINT, status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  retry_count INT NOT NULL DEFAULT 0, next_retry_at TIMESTAMPTZ, last_error VARCHAR(512),
  awaiting_consumer BOOLEAN NOT NULL DEFAULT false,
  occurred_at TIMESTAMPTZ NOT NULL, published_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT pk_event_outbox PRIMARY KEY (id),
  CONSTRAINT ck_event_outbox_status CHECK (status IN ('PENDING','PUBLISHED','DEAD'))
);
CREATE UNIQUE INDEX uk_event_outbox_event_id ON eaio_iam.event_outbox (event_id);
CREATE INDEX idx_event_outbox_status ON eaio_iam.event_outbox (status, next_retry_at);
CREATE INDEX idx_event_outbox_aggregate ON eaio_iam.event_outbox (aggregate_type, aggregate_id);
COMMENT ON TABLE eaio_iam.event_outbox IS '事务性发件箱：与业务写同一本地事务；发布器 FOR UPDATE SKIP LOCKED 批量投递，5 次退避失败进 DEAD';
COMMENT ON COLUMN eaio_iam.event_outbox.awaiting_consumer IS 'true = 已发布但目标消费者（audit）尚未上线，上线后由补偿任务重投（见 3.11.4）';

CREATE TABLE eaio_iam.event_consume_record (
  id BIGINT NOT NULL, event_id VARCHAR(64) NOT NULL, consumer VARCHAR(64) NOT NULL, business_id VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT pk_event_consume_record PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_event_consume_record_dedupe ON eaio_iam.event_consume_record (event_id, consumer, coalesce(business_id, ''));
CREATE INDEX idx_event_consume_record_created ON eaio_iam.event_consume_record (created_at);
COMMENT ON TABLE eaio_iam.event_consume_record IS '消费幂等记录：去重键 = 事件ID + 消费者 + 业务ID（HLD 4.4 的"事件ID+业务ID去重"落地）';
```

### 4.4 迁移脚本组织（`V1__` 起清单）

| 版本 | 文件 | 内容 | 可重复性 |
|---|---|---|---|
| V1 | `V1__baseline.sql` | `CREATE SCHEMA IF NOT EXISTS eaio_iam;` + 注释头 | 一次性（与 platform 同构） |
| V2 | `V2__org.sql` | `org_node`、`org_node_path` | 一次性 |
| V3 | `V3__position.sql` | `position`、`org_position` | 一次性 |
| V4 | `V4__user.sql` | `sys_user`、`user_org`、`user_password_history`、`mfa_recovery_code` | 一次性 |
| V5 | `V5__employee.sql` | `employee`、`employee_sensitive` | 一次性 |
| V6 | `V6__rbac.sql` | `role`、`permission`、`role_permission`、`user_role` | 一次性 |
| V7 | `V7__authz_scope.sql` | `data_scope_resource`、`data_scope_rule`、`field_permission`、`abac_policy` | 一次性 |
| V8 | `V8__sod.sql` | `sod_rule`、`sod_rule_permission`、`sod_exemption` | 一次性 |
| V9 | `V9__access_grant.sql` | `access_grant` | 一次性 |
| V10 | `V10__auth.sql` | `auth_session`、`auth_refresh_token`、`login_attempt` | 一次性 |
| V11 | `V11__idp.sql` | `sso_config`、`ldap_config` | 一次性 |
| V12 | `V12__lifecycle_snapshot.sql` | `org_lifecycle_log`、`permission_snapshot` | 一次性 |
| V13 | `V13__event.sql` | `event_outbox`、`event_consume_record` | 一次性 |
| V14 | `V14__seed_baseline.sql` | 内置权限点、内置角色、首个超管占位（幂等 `INSERT … ON CONFLICT DO NOTHING`） | 一次性（内容幂等） |

**脚本纪律**：① 已发布的迁移**不可修改**（Flyway 校验和会拦下）；② 每个脚本只做一件事（一个业务域）；③ 建索引与建表**同一脚本**（避免"表建了索引忘了"）；④ 破坏性变更（删列/改类型）必须拆成"新增 → 双写 → 切换 → 删除"多版本（P1 无破坏性变更）；⑤ 迁移在 CI 的 Testcontainers（PG17）上真实执行（platform 册 6.2 阶段 5），本机无 Docker 时跳过——存在"本地绿、CI 红"的口子，提交前留意 SQL。

**备选：闭包表触发器版（未采用，见 3.1.5）**：若团队规模小、无法保证应用层纪律，可加 `R__org_closure_trigger.sql`（`AFTER INSERT OR UPDATE OF parent_id OR DELETE ON eaio_iam.org_node` 的 PL/pgSQL 函数重建该节点相关闭包行）。**不采用的理由**：无法表达同树写锁、无法被单测覆盖、把业务规则移进数据库。**若采用，必须同时**：禁用应用层的闭包维护路径（避免双重维护）、保留一致性校验任务。

### 4.5 种子数据（`V14__seed_baseline.sql` + dev 初始化器）

| 类别 | 内容 | 写入方式 |
|---|---|---|
| 权限点 | 7.2 的权限点总表（`iam:*` 共 54 条，含 MENU/BUTTON/API 三类） | Flyway V14（幂等 upsert） |
| 内置角色 | `SUPER_ADMIN`（全部权限）、`GROUP_ADMIN`（集团管理员）、`COMPANY_ADMIN`（子公司管理员）、`AUDITOR`（只读审计）、`BUSINESS_USER`（基础业务角色）——`role_type='SYSTEM'`，**不可删除** | Flyway V14 |
| 首个超管 | `sys_user.username = 'admin'`，`password_hash = {argon2}`（口令从环境变量 `EAIO_IAM_BOOTSTRAP_ADMIN_PASSWORD` 读取，**脚本内不写明文**），`status='ACTIVE'`、`must_change_password=true`、`super_admin=true`、`source='LOCAL'` | 启动初始化器（`BootstrapAdminInitializer`，`@Order` 在迁移之后；已存在则跳过） |
| 数据权限资源 | `iam:user`/`iam:org`/`iam:employee`/`iam:contact` 四条登记（`org_column=org_id` 或空 + `owner_column`） | Flyway V14 |
| SoD 示例规则 | 一条 HIGH 规则（付款制单 vs 付款审批），`enforce=false`（过渡期） | Flyway V14 |
| 字典 | `iam.org.node_type`、`iam.org.lifecycle`、`iam.user.status`、`iam.data_scope.type`、`iam.field.mask_type`（经 platform `DictApi` 写入） | 启动初始化器（调 platform，**不在 iam 建字典表**） |
| 示例组织/示例用户 | 集团 A → 子公司 B → 部门 C、示例用户 `test.user`（口令来自 `EAIO_IAM_DEMO_PASSWORD`） | **仅 `dev` profile 的 `IamDemoDataInitializer`**——生产绝不插入示例组织（避免"演示数据进了生产"） |

**幂等要求**：所有种子脚本用 `INSERT … ON CONFLICT (唯一键) DO NOTHING`（或 `DO UPDATE SET` 仅限描述类字段）；启动初始化器必须先查后写（`exists → skip`）。

### 4.6 Redis 键空间与 TTL

统一前缀 `eaio:{env}:iam:`（`{env}` 为激活 profile，沿用 P0 幂等键规范；键由 `RedisKeys`（common，P1 新增）构造，禁止散拼）。

| 用途 | 键 | 值 | TTL | 失效方式 |
|---|---|---|---|---|
| 会话索引 | `eaio:{env}:iam:session:{sid}` | `{userId, orgId, status, ver, familyId}` | = refresh TTL（14d） | 登出/撤销删除；无 |
| 用户会话集合 | `eaio:{env}:iam:session:user:{userId}` | Set(`sid`) | 同上 | 撤销成员；集合空则删键 |
| 组织会话集合 | `eaio:{env}:iam:session:org:{orgId}` | Set(`sid`) | 同上 | 组织注销时批量撤销 |
| 权限快照 | `eaio:{env}:iam:perm:{userId}:{orgId}` | JSON（权限码集合 + 数据范围 + 字段权限摘要） | 30m | 版本号变化/事件失效主动删 |
| 权限版本 | `eaio:{env}:iam:perm:ver:{userId}` | int | 30m | `INCR`（授权变更时） |
| 数据范围解析缓存 | `eaio:{env}:iam:ds:{userId}:{resource}:{orgId}` | JSON（`DataScopeDTO`） | 60s | 事件失效 |
| 组织树/路径缓存 | `eaio:{env}:iam:org:snapshot`（本地 Caffeine 为主，Redis 仅跨实例广播失效信号） | 版本号 | 无（信号键 TTL 5s） | `OrgChangedEvent` 后写信号 |
| 验证码 | `eaio:{env}:iam:captcha:{captchaId}` | 明文码（短 TTL 可接受） | 120s | `GETDEL` 一次性 |
| 登录限流（IP） | `eaio:{env}:iam:login:ip:{ip}` | 计数 | 60s | 过期 |
| TOTP 重放防护 | `eaio:{env}:iam:totp:used:{userId}:{code}` | `1` | 90s | 过期 |
| MFA challenge | `eaio:{env}:iam:mfa:challenge:{challengeId}` | `{userId, expiresAt}` | 5m | 一次性消费 |
| SSO state/nonce | `eaio:{env}:iam:sso:{state}` | `{nonce, configId, redirect}` | 10m | 一次性消费 |
| SAML 断言去重 | `eaio:{env}:iam:saml:assertion:{id}` | `1` | 10m | 过期 |
| 组织树写锁 | `eaio:{env}:iam:lock:org:tree` | owner token | 30s | 释放/过期 |
| 发布器锁（ShedLock 复用 platform 机制） | `eaio:{env}:iam:outbox:lock` | ShedLock 管理 | 租约 | 任务结束 |
| LDAP 同步锁 | `eaio:{env}:iam:lock:ldap:sync:{configId}` | owner token | 10m | 释放/过期 |

**约束**：① 值中**禁止**出现明文口令、MFA 秘钥、SSO client secret（只放 id 与状态）；② 所有键必须能被 `RedisKeys` 构造并出现在本表（新增键即更新本表）；③ Redis 不可用时的行为在 3.4.6/3.3.8 逐项定义（会话与验证码 fail-closed，缓存类回源 DB）。

### 4.7 权限快照与缓存一致性

**快照内容**（`PermissionSnapshotService`）：权限码集合、数据范围摘要（每资源一行 `DataScopeDTO` 的紧凑形态）、字段权限矩阵摘要（`resource → field → {visible, editable, maskType}`）。

**读写路径**：

```
读：@PreAuthorize / 数据权限 / 字段裁剪
    → 本地 Caffeine（L1，TTL 30s，键 userId+orgId+ver）
    → Redis（L2，eaio:{env}:iam:perm:{userId}:{orgId}，TTL 30m）
    → 回源 DB（user_role → role → role_permission → permission，+ data_scope_rule/field_permission）并写回 L1/L2
失效：任何授权写操作（同事务）
    ① users.token_version 不变（令牌仍有效，权限即时重算）
    ② Redis INCR eaio:{env}:iam:perm:ver:{userId}
    ③ 删 L2 快照键 + 删 ds 缓存键
    ④ 发 PermissionChangedEvent（AFTER_COMMIT，经 outbox）→ 其他实例失效 L1（本地 Caffeine 有 TTL 上限，故最坏 30s）
```

| 一致性等级 | 场景 | 手段 | 最坏窗口 |
|---|---|---|---|
| 最终一致（≤30s） | `@PreAuthorize` 权限点判定、字段权限展示、数据范围 SQL 改写 | L1 TTL + 事件失效 | 30s |
| **强一致（读 DB）** | SoD 校验、字段权限**写侧**校验、授权不可放大校验、超管标记判定 | 直读 DB（不经缓存） | 0s |

**为什么写侧必须直读**：`editable=false` 的字段若因缓存滞后被写进去，是**数据被越权修改**；展示滞后只是"晚 30 秒看到变化"。两者的风险不对称，因此分开处理。

**Redis 全丢的恢复**：`permission_snapshot` 表保留最近一次快照（4.3.12），重建任务按需回填 L2（避免一次全量重算打爆 DB）；`token_version` 与 `auth_session` 是 DB 真源，会话丢失时用户重新登录即可（安全优先）。

### 4.8 数据分类分级与保留

（分级口径见 SRS 6.1；保留口径见 SRS 6.2）

| 数据 | 分级 | 存储措施 | 访问措施 | 保留 |
|---|---|---|---|---|
| 口令哈希 / MFA 秘钥 / SSO·LDAP 凭据 | 高敏感 | 口令 Argon2id 单向；秘钥与凭据 AES-GCM | 不返回前端；秘钥列禁入日志 | 会话期内 + 变更历史 |
| 证件号 / 银行账号（`employee_sensitive`） | 高敏感 | AES-GCM + `id_card_hash` | 字段权限 + 数据权限 + 审计；导出需审批 + 水印 | 员工离职后 ≥ 5 年 |
| 手机号 / 邮箱（`sys_user`） | 敏感 | 原文存储 | `@Sensitive` 脱敏 + 字段权限 | 账号存续期 + 180 天 |
| 组织 / 岗位 / 任职 | 内部 | — | 数据权限（同公司可见） | 永久 |
| 角色 / 权限点 / SoD 规则 | 内部 | — | 管理员权限点 | 永久（删除走逻辑删除） |
| 权限快照 / 缓存 | 内部 | Redis + `permission_snapshot` | 仅内部读取 | 覆盖式 + 30m 过期 |
| 会话 / 刷新令牌 / 登录流水 | 敏感 | 令牌只存哈希 | 本人可见自己的会话；管理员可见元数据 | 180 天 |
| 事件发件箱 | 内部 | `payload_json` 不含敏感明文 | 运维权限点 | PUBLISHED 30 天；DEAD 长期 |
| 生命周期流水 | 内部 | — | 组织管理权限点 | 永久 |

**清理任务**（platform `SchedulerApi` 注册，`JobHandler` 代码登记，禁止按字符串反射）：`iam.session.cleanup`（每日 02:00：过期会话与令牌、180 天前 `login_attempt`）、`iam.outbox.cleanup`（每日 03:00：30 天前 PUBLISHED）、`iam.password-history.trim`（每日 03:30）、`iam.closure.audit`（每日 04:00，只告警不修）、`iam.access-grant.expire`（每小时）、`iam.sod.exemption.expire`（每小时）。

---

## 5. 接口契约（冻结 V1）

### 5.1 契约总览

| 契约面 | 位置 | 冻结规则 | 本册章节 |
|---|---|---|---|
| REST 端点 | `com.eaio.iam.infrastructure.web.*Controller` | 全部 POST + JSON（协议端点例外见 I-7）；动作词 `Get/GetPage/Add/Up/Del` + 业务动作词；路径 `/api/iam/<resource>/<Action>`（`/api` 由 `server.servlet.context-path` 承载） | 5.2 |
| DTO | `com.eaio.iam.api.dto` | 跨模块 DTO 一律 `record`（不可变）；**入参 DTO 不含 `orgId`/`createdBy`**（组织与操作人来自 `TenantCtx`，防越权写入）；校验注解走 `@Validated` + common 自定义约束 | 5.3 |
| 跨模块 Java API | `com.eaio.iam.api`（`@NamedInterface("api")`） | 只增不改；不暴露实体、Mapper、Redis、JWT/密钥类型；接口名与 DTO 名即本册清单（10 个接口） | 5.4 |
| 事件 | `com.eaio.iam.events` | 不可变 record；**必须自带 `orgId`/`operatorId`**；`AFTER_COMMIT` 经 outbox 投递 | 5.5 |
| 认证协议端点 | OAuth2 Authorization Server（同进程）+ SSO 回调 | 按协议方法（GET/302、`x-www-form-urlencoded`）；HTTP 状态码遵循协议而非恒 200 | 5.6 |
| 前端约定 | `src/api/iam/*.js` | 统一 POST + 幂等键；按 `Result.code` 判定认证失败 | 5.7 |
| 错误码 | `com.eaio.iam.api.IamErrorCode`（**本册新增枚举，实现 `BusinessErrorCode`**） | 取值全部落在 `21000–21999`（批次总册 A2 断言）；已用 66 个号 | 7.1 |

### 5.2 REST 端点清单

> 幂等列：`是` = 写接口必须带 `Idempotency-Key`；`—` = 查询/协议端点不需要。资源码列 = 该端点数据是否受 `data_scope_resource` 改写（`—` 表示 iam 自身管理表，不参与改写）。**所有 `iam/*` 管理表查询属于 iam 内部元数据，不使用 `@InterceptorIgnore` 之外的豁免。**

| # | 端点（`/api/iam/...`） | 权限点 | 幂等 | 资源码 | 说明 |
|---|---|---|---|---|---|
| 1 | `auth/Login` | 免鉴权 | — | — | 账号密码登录；返回 `TokenDTO` 或 `mfaRequired=true` |
| 2 | `auth/Captcha` | 免鉴权 | — | — | 生成验证码（`captchaId` + base64 图片） |
| 3 | `auth/MfaVerify` | 免鉴权 | — | — | 用 `mfaChallengeId` + TOTP 码换取令牌 |
| 4 | `auth/MfaEnroll` | 登录即可 | — | — | 生成 TOTP 秘钥与 `otpauth://` URI（未生效） |
| 5 | `auth/MfaEnrollConfirm` | 登录即可 | 是 | — | 校验一次码 → `mfa_enabled=true` 并返回恢复码 |
| 6 | `auth/MfaReset` | `iam:user:mfa` | 是 | — | 管理员重置某用户 MFA（强制审计） |
| 7 | `auth/Refresh` | 免鉴权（凭 refresh） | — | — | 刷新令牌（轮换） |
| 8 | `auth/Logout` | 登录即可 | 是 | — | 撤销当前会话 |
| 9 | `auth/ChangePassword` | 登录即可 | 是 | — | 改密（保留当前会话，撤销其他会话） |
| 10 | `auth/Profile` | 登录即可 | — | — | 当前用户信息 + 可切换组织列表 |
| 11 | `session/GetPage` | `iam:session:list` | — | — | 会话列表（本人 / 管理员按用户） |
| 12 | `session/Revoke` | `iam:session:revoke` | 是 | — | 强制下线（理由必填） |
| 13 | `org/Get` / `org/GetPage` | `iam:org:list` | — | `iam:org` | 单条 / 分页 |
| 14 | `org/Tree` | `iam:org:list` | — | `iam:org` | `{rootId, depth}`，**depth 必填**（默认 3，上限 6） |
| 15 | `org/Children` | `iam:org:list` | — | `iam:org` | 懒加载直接子节点 |
| 16 | `org/Descendants` / `org/SubtreeIds` / `org/PathOf` | `iam:org:list` | — | — | 子树节点 / 子树 id 集合 / 路径（内部与前端面包屑共用） |
| 17 | `org/Add` / `org/Up` / `org/Del` | `iam:org:add/up/del` | 是 | — | 增改删（删除受 `21012`/`21018` 约束） |
| 18 | `org/Move` | `iam:org:move` | 是 | — | 移动子树（3.1.5 算法） |
| 19 | `org/Transition` | `iam:org:lifecycle` | 是 | — | 生命周期流转 |
| 20 | `org/DeregisterPreview` | `iam:org:lifecycle` | — | — | 注销影响面预览 |
| 21 | `position/Get` / `GetPage` / `Add` / `Up` / `Del` | `iam:position:*` | 写接口=是 | — | 岗位主档 |
| 22 | `position/Assign` / `Unassign` | `iam:position:up` | 是 | — | 岗位在组织下挂载/摘除（`org_position`） |
| 23 | `user/Get` / `GetPage` / `BatchGet` | `iam:user:list` | — | `iam:user` | 用户查询（数据权限生效） |
| 24 | `user/Add` / `Up` / `Del` | `iam:user:add/up/del` | 是 | — | 用户增改删（删=逻辑删除 + 停用） |
| 25 | `user/Disable` / `Unlock` | `iam:user:disable` | 是 | — | 停用/启用、解锁（清 `fail_count`/`locked_until`） |
| 26 | `user/ResetPassword` | `iam:user:resetPwd` | 是 | — | 管理员重置口令（返回一次性初始口令，强制改密） |
| 27 | `user/AssignRoles` / `user/RevokeRoles` | `iam:user:role` | 是 | — | 角色分配/回收（**SoD 校验点 1**） |
| 28 | `user/AssignOrg` / `user/UnassignOrg` | `iam:user:up` | 是 | — | 多组织挂载变更（**SoD 校验点 2**） |
| 29 | `employee/GetByUserId` / `employee/GetPage` / `employee/Up` | `iam:employee:list/up` | 写接口=是 | `iam:employee` | 员工任职（敏感字段经字段权限） |
| 30 | `role/Get` / `GetPage` / `Add` / `Up` / `Del` | `iam:role:*` | 写接口=是 | — | 角色 CRUD（继承/模板字段在此） |
| 31 | `role/AssignPermissions` | `iam:role:assign` | 是 | — | 角色权限点（含 `effect=REVOKE` 覆盖） |
| 32 | `role/InheritedRoles` | `iam:role:list` | — | — | 继承链（含有效权限合并结果） |
| 33 | `permission/Get` / `GetPage` / `Tree` | `iam:permission:list` | — | — | 权限点查询与树 |
| 34 | `permission/Add` / `Up` / `Del` | `iam:permission:add/up/del` | 是 | — | 权限点维护（种子之外的增量） |
| 35 | `permission/Check` / `ListPermissions` / `MenuTree` | 登录即可 | — | — | 当前用户权限判定 / 权限码 / 菜单树 |
| 36 | `dataScope/Rules` / `Save` / `Del` | `iam:dataScope:list/save/del` | 写接口=是 | — | 数据范围规则 |
| 37 | `dataScope/Resource` / `ResourceSave` | `iam:dataScope:list/save` | 写接口=是 | — | 资源白名单登记（唯一开关） |
| 38 | `field/List` / `field/Save` / `field/Del` | `iam:field:list/save` | 写接口=是 | — | 字段权限矩阵 |
| 39 | `abac/GetPage` / `Add` / `Up` / `Del` / `Test` | `iam:abac:*` | 写接口=是 | — | ABAC 策略 + 试算 |
| 40 | `sod/ListRules` / `Save` / `Del` / `Check` / `Exempt` | `iam:sod:*` | 写接口=是 | — | SoD 规则、试算、豁免 |
| 41 | `idp/ListSso` / `SaveSso` / `DelSso` / `TestSso` | `iam:idp:list/save/test` | 写接口=是 | — | SSO 配置与连通测试 |
| 42 | `idp/ListLdap` / `SaveLdap` / `DelLdap` / `SyncLdap` | `iam:idp:list/save/test` | 写接口=是 | — | LDAP 配置、同步触发 |
| 43 | `contact/GetPage` / `contact/Export` | `iam:contact:list/export` | 导出=是 | `iam:contact` | 通讯录（3.13） |
| 44 | `event/GetPage` / `event/Retry` | `iam:event:list/retry` | 重投=是 | — | 发件箱查询与死信重投（运维） |
| 45 | 协议端点：`/oauth2/authorize`、`/oauth2/token`、`/oauth2/jwks`、`/oauth2/revoke`、`/oauth2/introspect` | 按 OAuth2 规范 | — | — | 见 5.6；不进权限点体系 |
| 46 | 协议端点：`iam/auth/oidc/callback`、`iam/auth/saml/acs`、`iam/auth/saml/metadata` | 按协议 | — | — | 浏览器重定向，返回 302 而非恒 200（I-7） |

**约定**：`GetPage` 入参统一 `pageNum`/`pageSize`/`orderBy`（白名单枚举，禁止任意列名）；出参 `Result<PageResult<T>>`；`Del` 一律逻辑删除；所有写接口幂等键缺失时按 P0 规则放行并 WARN（不是拒绝）。

### 5.3 DTO 定义（record 签名，`com.eaio.iam.api.dto`）

```java
// ── 认证 ──
record LoginCmd(@NotBlank String username, @NotBlank String password, String captchaId, String captcha,
                Long orgId, String device) {}
record RefreshCmd(@NotBlank String refreshToken, Long orgId) {}
record TokenDTO(String accessToken, String refreshToken, String tokenType, long expiresIn,
                Long orgId, boolean mfaRequired, String mfaChallengeId) {}
record MfaEnrollDTO(String secret, String otpauthUri, List<String> recoveryCodes) {}   // recoveryCodes 仅 Confirm 返回
record ChangePasswordCmd(@NotBlank String oldPassword, @NotBlank String newPassword) {}
// ── 用户 / 员工 / 岗位 ──
record UserDTO(Long id, String username, String nickname, String realName, String mobile, String email,
               String userType, String status, boolean mfaEnabled, boolean superAdmin, String source,
               Instant lastLoginAt, Instant lockedUntil, List<UserOrgDTO> orgs, List<RoleBriefDTO> roles) {}
record UserBriefDTO(Long id, String username, String nickname, String realName, String mobile) {}
record UserSaveCmd(Long id, @NotBlank String username, String nickname, String realName,
                   @Mobile String mobile, @Email String email, List<UserOrgCmd> orgs, String remark) {}
record UserQuery(String keyword, String status, Long orgId, Boolean includeSubOrg, Boolean mfaEnabled,
                 int pageNum, int pageSize, String orderBy) {}
record EmployeeDTO(Long id, Long userId, String employeeNo, String name, String gender, LocalDate birthDate,
                   LocalDate hireDate, LocalDate leaveDate, String employmentType, String status,
                   String workEmail, String workPhone, String idCardMasked, String bankAccountMasked) {}
record EmployeeSaveCmd(Long id, Long userId, String employeeNo, String name, String hireDate,
                       LocalDate leaveDate, String employmentType, String status, String idCard,
                       String bankAccount, String bankName) {}   // idCard/bankAccount 为明文入参，落库前加密
record PositionDTO(Long id, String code, String name, Short positionLevel, Integer headcountLimit, String status) {}
record OrgPositionDTO(Long id, Long orgId, Long positionId, Integer headcount, String status) {}
// ── 组织 ──
record OrgNodeDTO(Long id, String code, String name, String shortName, String nodeType, Long parentId,
                  String path, int level, String lifecycle, Long managerUserId, int sortOrder) {}
record OrgTreeDTO(OrgNodeDTO node, List<OrgTreeDTO> children) {}
record OrgSaveCmd(Long id, @NotBlank String code, @NotBlank String name, String shortName,
                  @NotBlank String nodeType, Long parentId, Long managerUserId, Integer sortOrder, String description) {}
record OrgMoveCmd(@NotNull Long nodeId, @NotNull Long newParentId, Integer sortOrder) {}
record OrgLifecycleDTO(Long nodeId, String fromState, String toState, Instant occurredAt, String reason,
                       int affectedUserCount, Long handoverToOrgId) {}
record OrgDeregisterPreviewDTO(Long nodeId, int descendantCount, int userCount, int activeRoleGrantCount,
                               int onlineSessionCount, int activeCrossOrgGrantCount, List<String> blockers) {}
// ── 角色 / 权限 ──
record RoleDTO(Long id, String code, String name, Long parentId, String inheritMode, boolean template,
               Long orgScopeId, String roleType, String status, List<Long> permissionIds) {}
record RoleSaveCmd(Long id, @NotBlank String code, @NotBlank String name, Long parentId, String inheritMode,
                   Boolean template, Long orgScopeId, String description) {}
record RoleAssignCmd(@NotNull Long roleId, List<Long> userIds, Long orgScopeId, Instant startAt, Instant endAt) {}
record RoleBriefDTO(Long id, String code, String name) {}
record PermissionDTO(Long id, String code, String name, String permType, Long parentId, String module,
                     String routePath, String icon, int sortOrder) {}
record MenuNodeDTO(Long id, String code, String name, String routePath, String icon, int sortOrder,
                   List<MenuNodeDTO> children) {}
// ── 数据范围 / 字段 / ABAC / SoD ──
record DataScopeDTO(String resourceCode, String scopeType, String orgColumn, String ownerColumn,
                    String deptColumn, List<Long> orgIds, Long ownerId, boolean deny, String source) {}
record DataScopeRuleDTO(Long id, String subjectType, Long subjectId, String resourceCode, String scopeType,
                        List<Long> customOrgIds, String effect, int priority, Instant validFrom, Instant validTo) {}
record DataScopeRuleSaveCmd(Long id, @NotBlank String subjectType, @NotNull Long subjectId,
                           @NotBlank String resourceCode, @NotBlank String scopeType, List<Long> customOrgIds,
                           String effect, Integer priority, Instant validFrom, Instant validTo) {}
record FieldPermissionDTO(String resourceCode, String fieldName, boolean visible, boolean editable, String maskType) {}
record SoDRuleDTO(Long id, String code, String name, String severity, String scopeType, Long orgId,
                  boolean enforce, String status, List<SodPermissionGroupDTO> groups) {}
record SoDCheckResultDTO(boolean passed, List<SoDConflictDTO> conflicts) {}
record SoDConflictDTO(String ruleCode, String ruleName, String permissionA, String permissionB, String severity) {}
record AccessGrantDTO(Long id, Long granteeUserId, String grantType, String resourceCode, String businessId,
                      List<Long> orgIds, Instant validFrom, Instant validTo, Long grantedBy, String status) {}
// ── 上下文 ──
record TenantContext(long userId, String username, Long orgId, String orgPath, boolean superAdmin,
                     String sessionId, int permissionsVersion) {}
// ── 审计（audit 侧 DTO，iam 只构造） ──
record PermissionChangeCmd(String changeType, String subjectType, Long subjectId, String beforeJson,
                           String afterJson, Long operatorId, Long orgId, String ip, String userAgent,
                           String traceId, Instant occurredAt) {}
```

**DTO 纪律**：① 入参 DTO（`*Cmd`/`*SaveCmd`/`*Query`）**绝不含 `orgId`/`createdBy`/`superAdmin`**（防越权写入，见 3.6.7）；② 出参 DTO 中受字段权限管辖的字段带 `@FieldPermission(resource=..., field=...)`；③ 手机号/证件号等带 `@Sensitive`；④ 跨模块 DTO 只增字段不改语义（MapStruct `unmappedTargetPolicy=ERROR` 编译期拦截遗漏映射）。

### 5.4 跨模块 Java API 签名（冻结 V1）

```java
package com.eaio.iam.api;   // package-info.java: @NamedInterface("api")

/** 认证：登录/刷新/登出/撤销/MFA/改密。所有方法失败均抛 BusinessException(210xx)，不返回 null。 */
public interface AuthnApi {
    TokenDTO login(LoginCmd cmd);
    TokenDTO refresh(RefreshCmd cmd);
    void logout(String refreshToken);
    void revoke(long userId);                                  // 全量踢下线（token_version++）
    MfaEnrollDTO mfaEnroll(long userId);
    void mfaVerify(long userId, String totpCode);               // 校验并激活（绑定确认）
    void changePassword(long userId, String oldPassword, String newPassword);
}

public interface UserApi {
    Optional<UserDTO> get(long userId);
    Optional<UserDTO> getByUsername(String username);
    PageResult<UserDTO> getPage(UserQuery query);
    long add(UserSaveCmd cmd);
    void up(UserSaveCmd cmd);
    void del(long userId);
    void disable(long userId, boolean disabled, String reason);
    String resetPassword(long userId);                          // 返回一次性初始口令
    List<UserBriefDTO> batchGet(Collection<Long> userIds);
}

public interface EmployeeApi {
    Optional<EmployeeDTO> getByUserId(long userId);
    PageResult<EmployeeDTO> getPage(EmployeeQuery query);
    void up(EmployeeSaveCmd cmd);
}

public interface OrgApi {
    Optional<OrgNodeDTO> get(long nodeId);
    OrgTreeDTO tree(Long rootId, int depth);                    // depth 必填（1..6）
    List<OrgNodeDTO> children(long nodeId);
    List<OrgNodeDTO> descendants(long nodeId);
    List<Long> subtreeIds(long nodeId);
    String pathOf(long nodeId);
    PageResult<OrgNodeDTO> getPage(OrgQuery query);
    long add(OrgSaveCmd cmd);
    void up(OrgSaveCmd cmd);
    void move(OrgMoveCmd cmd);
    void del(long nodeId);
}

public interface OrgLifecycleApi {
    OrgLifecycleDTO transition(long nodeId, String targetState, String reason);
    OrgDeregisterPreviewDTO deregisterPreview(long nodeId);
}

public interface RoleApi {
    Optional<RoleDTO> get(long roleId);
    PageResult<RoleDTO> getPage(RoleQuery query);
    long add(RoleSaveCmd cmd);
    void up(RoleSaveCmd cmd);
    void del(long roleId);
    void assignPermissions(long roleId, List<Long> permissionIds);
    void assignUsers(RoleAssignCmd cmd);                        // SoD 校验点 1
    List<RoleDTO> inheritedRoles(long roleId);                   // 继承链（含有效权限合并结果）
}

public interface PermissionApi {
    boolean check(long userId, String permissionCode);
    boolean checkAll(long userId, Collection<String> codes);
    List<String> listPermissions(long userId);
    List<MenuNodeDTO> menuTree(long userId);
    DataScopeDTO resolveDataScope(long userId, String resourceCode, Long orgId);
    Map<String, FieldPermissionDTO> filterFields(long userId, String resourceCode, Collection<String> fields);
}

public interface DataScopeApi {
    List<DataScopeRuleDTO> rules(Long roleId, Long userId);      // 二者传其一
    long save(DataScopeRuleSaveCmd cmd);
    void del(long ruleId);
}

public interface SoDCheckApi {
    SoDCheckResultDTO check(long userId, Collection<String> permissionCodes);
    SoDCheckResultDTO checkAssign(RoleAssignCmd cmd);
    List<SoDRuleDTO> listRules();
}

public interface TenantCtxProvider {
    Optional<TenantContext> current();
    TenantContext require();                                     // 无上下文抛 BusinessException(21084)
    Optional<Long> currentOrgId();
    <T> T runAs(long orgId, Callable<T> action);                  // 无返回场景用 runAs(orgId, () -> { …; return null; })
    void runAs(long orgId, Runnable action);
}
```

**跨模块契约纪律**：

1. `api` 包只放 interface + record + 枚举；**不得出现** `@RestController`/Mapper/实体/JWT 类型（批次总册 A3/A5、platform 册 C-19）。
2. 方法只增不改；签名变更走评审（HLD 13.4）。
3. 所有方法在调用方线程内使用 `TenantCtx`：**跨模块调用不得绕过 `TenantCtxProvider` 自造上下文**。
4. `PermissionAuditApi`（`record(PermissionChangeCmd)`）**定义在 `com.eaio.audit.api`**（I-8）：iam 在 M4 以 `optional` 依赖 + `ObjectProvider` 可选注入；M2–M3 只发事件与结构化日志。
5. 业务模块（oa/crm/hr…）**只允许**依赖本清单中的接口；`iam.domain`/`iam.infrastructure`/`iam.application` 一律不可见（Modulith + ArchUnit 双重拦截）。

### 5.5 事件契约

| 事件 | 载荷 record（`com.eaio.iam.events`） | 发布时机 | 消费方与幂等键 |
|---|---|---|---|
| `UserRoleChangedEvent` | `(String eventId, long userId, String changeType, List<Long> roleIds, Long orgId, Long operatorId, Instant occurredAt)` | 角色分配/回收（同事务写 outbox） | audit；幂等键 `eventId + userId` |
| `OrgChangedEvent` | `(String eventId, long nodeId, String changeType, Long parentId, String oldPath, String newPath, Long orgId, Long operatorId, Instant occurredAt)` | 组织增/改/移/删 | platform（缓存失效）、audit；幂等键 `eventId + nodeId` |
| `PermissionChangedEvent` | `(String eventId, String subjectType, long subjectId, String changeType, List<Long> affectedUserIds, Long orgId, Long operatorId, Instant occurredAt)` | 角色权限/权限点/数据范围/字段权限变更 | platform、audit；幂等键 `eventId + subjectType + subjectId` |
| `OrgLifecycleChangedEvent` | `(String eventId, long nodeId, String fromState, String toState, String reason, int affectedUserCount, Long handoverToOrgId, Long operatorId, Instant occurredAt)` | 生命周期流转 | portal（数据交接待办，P1 顺序 9 起）、audit；幂等键 `eventId + nodeId + toState` |
| `EmployeeChangedEvent`（**本册新增登记**） | `(String eventId, long employeeId, long userId, String changeType, Long orgId, Long positionId, LocalDate effectiveDate, Long operatorId, Instant occurredAt)` | 任职变更（入职/调岗/离职/挂载变化） | （P2）hr、audit；幂等键 `eventId + employeeId` |

**语义与可靠性**：① 全部经 `event_outbox` 同事务落库 → 提交后投递（3.11.3）；② 消费幂等由 `event_consume_record(event_id, consumer, business_id)` 保证（HLD 4.4 "事件ID+业务ID去重"）；③ 事件是**通知**不是命令：消费方失败不回滚发布方事务（`audit` 例外——`PermissionAuditApi` 同步调用失败时阻断关键操作，见 3.11.4）；④ 载荷不含敏感明文；⑤ 事件版本演进：新增字段默认值向后兼容，删除/改义走新事件类型。

### 5.6 认证协议细节

#### 5.6.1 OAuth2 Authorization Server（同进程自托管，I-5）

| 端点 | 方法 | 用途 | 说明 |
|---|---|---|---|
| `/oauth2/authorize` | GET/POST | 授权码 + PKCE（自有前端为 public client） | `code_challenge_method=S256` 强制 |
| `/oauth2/token` | POST | `authorization_code` / `refresh_token` / `client_credentials` | refresh 轮换对本端点同样生效 |
| `/oauth2/jwks` | GET | JWKS（全部有效 `kid`） | 缓存 `Cache-Control: max-age=300` |
| `/oauth2/revoke` | POST | 撤销 refresh / access | 撤销 refresh 即撤销整个 family |
| `/oauth2/introspect` | POST | 令牌自检 | 只查签名/exp/会话索引，不查库 |
| `/oauth2/.well-known/openid-configuration` | GET | OIDC 发现文档 | 供 P3 integration 消费 |

#### 5.6.2 令牌生命周期

```
登录/授权 → access(30m) + refresh(14d，一次性轮换)
   ├─ access 过期 → 前端静默 Refresh（同 sid）
   ├─ refresh 过期 → 重新登录
   ├─ refresh 被复用 → family 全撤销 + 安全事件（21006）
   └─ 撤销面 → 会话级/用户级/组织级（3.4.4）
生成/校验实现只允许出现在 iam.infrastructure.security（JWT 类型不外泄）
```

#### 5.6.3 错误码映射（关键边界，前端据此分支）

| 场景 | body.code | 说明 |
|---|---|---|
| 登录凭据错误（含用户不存在） | `21001` | **不跳登录**（用户就在登录页） |
| 受保护端点无/失效令牌、会话已撤销 | `10401`（通用段） | 前端清 token → 跳登录（ADR-0001） |
| 权限点缺失 | `10403`（通用段）；需带权限码上下文时用 `21031` | 前端提示"无权限" |
| 超出数据权限范围 | `21014` | 提示"当前范围不足"，不要提示"系统错误" |
| 组织上下文缺失/非法 | `21084` / `21085` | 提示"请切换公司/重新登录" |
| 刷新令牌无效/已轮换 | `21006` | 清 token → 跳登录 |
| 组织状态不允许操作 | `21013` | 提示"组织已注销/归档" |

#### 5.6.4 关键配置项（`eaio.iam.*`，框架级走 yml/环境变量）

| 键 | 默认 | 键 | 默认 |
|---|---|---|---|
| `eaio.iam.jwt.access-ttl` | `30m` | `eaio.iam.jwt.refresh-ttl` | `14d` |
| `eaio.iam.jwt.rotate-days` | `90` | `eaio.iam.jwt.clock-skew` | `60s` |
| `eaio.iam.login.max-fail` | `5` | `eaio.iam.login.lock-minutes` | `15` |
| `eaio.iam.login.captcha.enabled` | `true` | `eaio.iam.login.captcha.after-failures` | `3` |
| `eaio.iam.login.ip-rate-limit` | `10/min` | `eaio.iam.password.min-length` | `10` |
| `eaio.iam.password.expire-days` | `90` | `eaio.iam.password.history-size` | `5` |
| `eaio.iam.session.max-per-user` | `5` | `eaio.iam.mfa.issuer` | `e-aio` |
| `eaio.iam.mfa.window` | `1` | `eaio.iam.data-scope.max-inline-ids` | `2000` |
| `eaio.iam.data-scope.exists-max-ids` | `20000` | `eaio.iam.field-permission.write-mode` | `STRICT` |
| `eaio.iam.org.max-depth` | `12` | `eaio.iam.org.move-max-nodes` | `5000` |
| `eaio.iam.idp.auto-create-user` | `false` | `eaio.iam.ldap.auto-create-user` | `false` |
| `eaio.iam.abac.max-policies` | `200` | `eaio.iam.sod.max-rules` | `1000` |
| `eaio.iam.permission.cache-ttl` | `30m` | `eaio.iam.datascope.cache-ttl` | `60s` |
| `eaio.iam.bootstrap-admin.enabled` | `true`（仅首启） | `eaio.iam.outbox.retry-max` | `5` |

> **密钥类配置不在本表**：`EAIO_IAM_JWT_KEY_<kid>`、`EAIO_IAM_FIELD_KEY_<kid>`、`EAIO_IAM_BOOTSTRAP_ADMIN_PASSWORD` 一律**只经环境变量/参数中心注入**，无默认值，缺失即启动失败（3.3.7、3.8.4）。

### 5.7 与前端约定

| 约定 | 内容 |
|---|---|
| API 文件 | `src/api/iam/{auth,org,user,role,permission,dataScope,field,abac,sod,idp,session,contact,event}.js`，按动作词导出 `get/getPage/add/up/del` + 业务动作（`move/transition/assignRoles/mfaEnroll…`） |
| 公司切换 | 顶栏切换器写 `X-Org-Id` 到请求头工厂（`src/utils/request.js` 的 header 钩子）；切换后清空依赖组织的本地缓存 |
| 幂等 | 写接口带 `Idempotency-Key`（`crypto.randomUUID()`）；**重试必须复用同键**；`10501` 提示"请勿重复提交"，`10502` 提示"稍后重试" |
| 认证失败 | 按 `code`：`10401` → 清 token + 跳登录（并发请求只跳一次）；`10403`/`21031` → 提示；`21085` → 提示切换公司；**禁止按 HTTP 401/403** |
| 权限指令 | `v-hasPermi="['iam:user:add']"`（RuoYi-Vue3 既有指令）；菜单与路由由 `MenuTree` 驱动，不硬编码 |
| 字段权限 | 后端已裁剪/掩码；前端不再二次猜测隐藏；`21032` 需在表单上标出"该字段不可编辑" |
| 长任务 | 通讯录导出等走 platform `ExcelApi`：提交得任务 ID + 轮询 `progress`（不用幂等键承载结果，ADR-0001） |

---

## 6. 测试与验收

### 6.1 单元测试点（不依赖容器，`mvn -B test`）

| 被测类 | 用例数（目标） | 关键断言 |
|---|---|---|
| `OrgLifecycle`（状态机） | 12 | 允许边/禁止边；`ACTIVE → PREPARING` 必须抛 `21013` |
| `OrgClosureService` | 8 | path/level 计算；环检测；子树规模；集合物化顺序 |
| `PasswordPolicyService` | 8 | 长度/复杂度/含用户名/历史/过期；策略缺失时落安全默认值 |
| `TokenService` | 10 | claims 完整性；轮换链；复用检测 → 撤销 family；`token_version` 判定 |
| `MfaTotpVerifier` | 6 | RFC 6238 向量；窗口 ±1；重放防护 |
| `RoleInheritanceResolver` | 10 | 传递闭包；REVOKE 优先；深度上限；成环；`org_scope_id` 匹配 |
| `PermissionSnapshotService` | 8 | 快照构建/失效/版本号；Redis 缺失时回源 DB |
| `DataScopeService` | 14 | 7 种范围展开；并集/差集；`DENY_ALL`；grant 并入；CUSTOM 求交 |
| `DataScopeSqlHandler` | 10 | 片段形态（inline/EXISTS/1=0/null）；id 白名单防注入；列名正则 |
| `FieldPermissionService` | 8 | 多角色合并（可见取严、掩码取严）；`super_admin`；叠加 `@Sensitive` |
| `AbacEngine` | 12 | 12 个操作符边界；`all/any/none` 真值表；属性缺失 → false |
| `SodService` | 8 | 二元/三元互斥；豁免；`scope_type=ORG` |
| `TenantContextHolder` | 6 | set/clear；`runAs` 异常时恢复；嵌套 `runAs` |
| `OutboxPublisher` | 8 | 退避序列；DEAD 阈值；`awaiting_consumer` 补偿 |
| `IamErrorCode` | 2 | **全部取值落在 21000–21999**（批次总册 A2 的单元级对应）；无重复号 |

> 覆盖率口径（P1 platform 册 C-11）：**iam 的 `application` + `domain` 行覆盖 ≥ 80%**，`api`/DTO/装配类不计入；不达标即 CI 红。

### 6.2 集成测试（Testcontainers：`pgvector/pgvector:pg17` + `redis:7-alpine`）

沿用 P0 既有 `IntegrationTestBase`（`disabledWithoutDocker`）；iam 新增 IT 类与断言：

| 编号 | IT 类 | 场景 | 关键断言 |
|---|---|---|---|
| IT-MIG-01 | `IamMigrationIT` | 执行 `db/migration/iam/V1..V14` | 31 张表存在；`flyway_schema_history` 在 `eaio_iam` 内；脚本可重复执行（二次启动无变更） |
| IT-META-01 | `IamSchemaMetaIT` | 注释与索引元数据 | 每张 iam 表有 `obj_description`；`data_scope_resource` 每行的 `org_column` 均有对应索引（AC-IAM-14） |
| IT-ORG-01…05 | 见 3.1.8 | 树形维护、move、生命周期 | 同 3.1.8 |
| IT-USER-01…05 | 见 3.2.6 | 用户/挂载/员工/敏感字段 | 同 3.2.6 |
| IT-AUTH-01…04、IT-SSO-01…03 | 见 3.3.9 | 登录/锁定/MFA/SSO/LDAP | 同 3.3.9 |
| IT-TOKEN-01…05 | 见 3.4.7 | 令牌与撤销 | 同 3.4.7 |
| IT-RBAC-01…03 | 见 3.5.8 | 继承/保护/菜单树 | 同 3.5.8 |
| IT-DS-01…07 | 见 3.6.8 | 数据权限改写与降级 | 同 3.6.8 |
| IT-FP-01…04、IT-ABAC-01…02 | 见 3.8.5 / 3.7.5 | 字段权限、ABAC | 同前 |
| IT-SOD-01…03 | 见 3.9.6 | SoD 三处校验 | 同 3.9.6 |
| IT-CTX-01…04 | 见 3.10.5 | 上下文与防越权切换 | 同 3.10.5 |
| IT-EVT-01…04 | 见 3.11.5 | 发件箱可靠性与幂等 | 同 3.11.5 |
| IT-CT-01…03 | 见 3.13.1 | 通讯录可见性与导出 | 同 3.13.1 |
| IT-PLAT-01 | `IamPlatformContractIT` | 经 platform `ParamApi`/`DictApi`/`SchedulerApi` 的调用 | 参数缺失落安全默认；任务按代码注册成功（禁反射） |

**测试数据策略**：每个 IT 用独立事务或 `@Sql` 清理脚本；组织/用户数据由 `IamTestDataFactory` 构造（固定 snowflake 段避免碰撞）；**不共享可变全局状态**（并行执行安全）。

### 6.3 架构测试（`e-aio-app/src/test/java/com/eaio/arch/ArchitectureTest.java` 扩展）

P0 已有 10 个 `@Test`（模块边界、命名接口、模块登记、common 纯净性、错误码分段及其控制组）；本册新增（批次总册 3.3 的 A1–A6 + iam 专项）：

| 编号 | 断言 | 违反示例（必须有控制组证明规则会红） |
|---|---|---|
| A1 | `MODULE_NAMES` 纳入 `iam`；`eaio.flyway.modules` 含 `iam` | 漏登记 → 失败 |
| A2 | `IamErrorCode` 取值全部落在 21000–21999；模块必须存在该枚举 | 写 `20999` → 失败 |
| A3 | `TenantCtxProvider` 是 `com.eaio.iam.api` 中的 interface；实现类不在 `api` 包 | 实现类放 `api` → 失败 |
| A4 | 装配例外收紧：业务模块之间不得引用彼此装配包 | `iam` 引用 `platform.infrastructure` → 失败 |
| A5 | P1 模块出现 `internal` 包后，跨模块引用必须被拦下 | 业务模块引用 `iam.internal.*` → 失败 |
| A6 | 业务模块不得依赖 `iam` 的非 `api` 包 | 引用 `iam.application.*` → 失败 |
| A7 | `@InterceptorIgnore(dataPermission="true")` 只允许出现在 `com.eaio.iam..` 与 `com.eaio.platform..` 的元数据查询类 | 业务模块加该注解 → 失败 |
| A8 | 线程池 Bean 必须装配 `TenantContextTaskDecorator` | 新建 `ThreadPoolTaskExecutor` 未装配 → 失败 |
| A9 | `api` 包不得依赖 Web/持久层类型（`org.springframework.web..`、`org.apache.ibatis..`） | `api` 里放 `@RestController` → 失败 |
| A10 | JWT/密钥类型只允许出现在 `com.eaio.iam.infrastructure.security..` | `api` 暴露 `Jwt` → 失败 |

> **规则必须有牙齿**：每条规则除断言现有代码通过，还要断言"违规输入确实被判违规"（控制组），以及作用面非空——沿用 P0 册 3.7 的写法，避免假绿灯。

### 6.4 安全专项测试（越权/横向越权/令牌重放/暴力破解/数据权限绕过/SoD 绕过）

| # | 攻击方式 | 期望结果 | 自动化断言 |
|---|---|---|---|
| 1 | 纵向越权：子公司管理员调 `iam:permission:Add` | `10403`/`21031`，无任何写库 | 断言 HTTP 200 + body.code + 表计数不变 |
| 2 | 横向越权：改请求体里的 `id` 去改别的公司的用户 | `21014`（范围不足）或影响行数 0 | 断言目标行未被修改 |
| 3 | 组织上下文伪造：`X-Org-Id` 指向未挂载公司 | `21085`，**未执行任何业务 SQL** | Mapper 调用计数 = 0 |
| 4 | 令牌重放：登出后用旧 access | `10401` | 断言会话键已删 |
| 5 | 刷新令牌重放：已用过的 refresh 二次使用 | `21006` + family 全撤销（其他设备也掉线） | 断言同 family 全部 `status='REVOKED'` |
| 6 | 暴力破解：连续 10 次错误口令 | 第 5 次起 `21002`；`login_attempt` 10 行；IP 限流触发 | 断言锁定时间与流水条数 |
| 7 | 数据权限绕过：手写 SQL 忽略组织条件 | 拦截器仍注入（断言最终 SQL 含 `org_id`） | 断言返回集合不含越界行 |
| 8 | 数据权限绕过：`@InterceptorIgnore` 标注业务方法 | A7 架构断言失败（CI 红） | ArchUnit 控制组 |
| 9 | 字段越权写：请求体带 `editable=false` 字段 | `21032` + 库值未变 + 审计事件 | 断言 `FieldWriteRejectedEvent` 落 outbox |
| 10 | 字段越权读：请求 `fields` 参数索要隐藏字段 | 响应中该字段缺失/掩码 | 断言序列化结果 |
| 11 | SoD 绕过：先分配 A 角色再分配互斥 B 角色 | 第二步 `21040` | 断言 `user_role` 无新增行 |
| 12 | SoD 绕过：并发同时分配 A、B | 只有一个成功（行锁 + 校验在同一事务） | 并发 2 线程断言 |
| 13 | 权限提升：子公司管理员给自己加 `ALL` 范围 | `21014`（授权不可放大） | 断言规则未落库 |
| 14 | SSO 断言重放 / `state` 篡改 | `21052` | 断言断言 id 去重键存在 |
| 15 | 超管绕过留痕：超管执行敏感操作 | 成功 + 审计事件 `actor=super_admin` | 断言审计流水存在 |
| 16 | 敏感数据泄漏：日志中检索证件号/口令/秘钥 | 命中数为 0 | 断言日志文本（含 SQL 日志）不含明文 |

### 6.5 母子公司权限 E2E（HLD 13.3 P1 验收核心）

**数据基线**：集团 `ORG-G`（GROUP）→ 子公司 `ORG-A`/`ORG-B`（COMPANY）→ 部门 `ORG-A1`/`ORG-A2`（DEPT）；用户 `group.admin`（集团管理员，`ALL`）、`a.admin`（子公司 A 管理员，`ORG_AND_SUB`）、`a.user`（A 公司普通用户，`SELF`）、`b.user`（B 公司）；资源 `oa:leave`（登记 `org_column=org_id`、`owner_column=applicant_id`）；SoD 规则"制单 vs 审核"。

| 场景 | 步骤 | 断言（必须查数据，不能只看状态码） |
|---|---|---|
| E2E-1 组织树 | ① 集团管理员 `org/Tree`；② `a.admin` 切到 `ORG-A` 后 `org/Tree`；③ `org/Move` 把 `ORG-A2` 移到 `ORG-B` | ① 全树；② 只含 A 子树；③ 移动后 `path`/闭包表正确、A/B 的数据范围立即反映变化 |
| E2E-2 数据权限 | 同一条请假列表接口，三个身份分别调用 | `group.admin` 见 A+B；`a.admin` 只见 A（含 A1/A2）；`a.user` 只见自己；**越界行的 id 不出现在结果集中**；`total` 同步收敛 |
| E2E-3 SoD | 给 `a.user` 分配"制单"角色 → 再分配"审核"角色 | 第二次 `21040`，消息含规则名与两个权限码；`user_role` 无新增；审计有记录 |
| E2E-4 跨公司审批 | `b.user` 提交单据 → 指定 `a.admin` 为审批人 → 审批人查询该单据 | 无 grant 时 `a.admin` 看不到该单据；写入 `APPROVAL_CROSS_ORG` grant 后**只能看到该单据**（不是整个 B 公司）；审批完成/过期后不可见 |
| E2E-5 注销影响面 | `deregisterPreview(ORG-A1)` → `transition(DEREGISTERED)` | 预览计数与实际一致（用户/授权/会话/grant）；注销后 A1 的会话被撤销、`user_role` 置 `REVOKED`、待办事件发出 |

> E2E 用 Testcontainers + MockMvc/RestClient 全链路（不打桩 iam 内部），作为 M5 出口条件；**审批引擎本体在 P2**，E2E-4 用"审批人指定 + 数据可见性"模拟其调用契约。

### 6.6 本册验收清单（每条可执行验证方式）

| 编号 | 验收项 | 验证方式（可执行） |
|---|---|---|
| AC-IAM-01 | 迁移可执行且幂等 | `mvn -B verify`（IT-MIG-01）在 CI 上执行 `db/migration/iam/V1..V14` 两次无变更 |
| AC-IAM-02 | 31 张表注释齐全 | IT-META-01 查询 `obj_description`，空注释即失败 |
| AC-IAM-03 | 错误码段内且无重复 | `IamErrorCodeTest` + A2 架构断言 |
| AC-IAM-04 | 组织树三表示法一致 | IT-ORG-01/05：`path`/`level` 与闭包表逐行比对 |
| AC-IAM-05 | move 防环与并发安全 | IT-ORG-02/03（含 2 线程并发） |
| AC-IAM-06 | 生命周期副作用完整 | IT-ORG-04 断言四表状态齐变 + outbox 有行 |
| AC-IAM-07 | 登录/锁定/验证码/MFA | IT-AUTH-01…04 |
| AC-IAM-08 | 令牌可撤销且轮换可检测 | IT-TOKEN-01…05 |
| AC-IAM-09 | 角色继承与覆盖 | IT-RBAC-01/02 + UT-RBAC-01 |
| AC-IAM-10 | 数据权限改写生效且不越界 | IT-DS-01/02/03 |
| AC-IAM-11 | 无权限 = 恒假而非报错 | IT-DS-04 + UT-DS-02 |
| AC-IAM-12 | ABAC 关键动作有策略或豁免登记 | 查询 `abac_policy` 覆盖审批/导出/删除三类动作（脚本断言） |
| AC-IAM-13 | SoD 三处校验 + 影响面预览 | IT-SOD-01…03 + SoD 规则变更预览返回违反用户清单 |
| AC-IAM-14 | 改写列均有索引 | IT-META-01 的索引断言 |
| AC-IAM-15 | 上下文每请求校验 | IT-CTX-01…04 |
| AC-IAM-16 | 事件可靠与幂等 | IT-EVT-01…04 |
| AC-IAM-17 | 字段权限三态与写侧拦截 | IT-FP-01…04 |
| AC-IAM-18 | 母子公司权限 E2E | 6.5 的 E2E-1…5 全绿 |
| AC-IAM-19 | 审计留痕（iam 侧） | 权限变更产生 `PermissionChangedEvent` + 结构化日志；M4 起 `PermissionAuditApi` 落库断言 |
| AC-IAM-20 | 覆盖率 | iam `application`+`domain` 行覆盖 ≥ 80%（CI 门禁） |

### 6.7 里程碑（M2–M5，对齐 P1 批次总册 2.3 与 HLD 13.2）

| 里程碑 | iam 交付物 | 出口条件 |
|---|---|---|
| M2 | `iam.api` 冻结（10 接口 + DTO + 5 事件 + 错误码表）；`V1..V6` 迁移（组织/岗位/用户/员工/RBAC）；登录与令牌（账号密码 + 验证码 + 锁定 + JWT/refresh）；组织树与生命周期 | IT-MIG/ORG/USER/AUTH/TOKEN 绿；A1–A3、A9、A10 架构断言绿 |
| M3 | 授权与数据权限：角色继承、权限快照、`data_scope_rule` + SQL 改写、`TenantCtx` 与过滤器链、OIDC 接入 | IT-RBAC/DS/CTX 绿；IT-DS-04 降级路径可用；A7/A8 绿 |
| M4 | 字段权限、ABAC、SoD、LDAP、`access_grant` 与跨公司审批授权、审计接入（optional + `PermissionAuditApi`） | IT-FP/ABAC/SOD/SSO-02 绿；audit 联调通过（权限变更落库） |
| M5 | SAML2、通讯录、运维端点（事件重投）、示例数据与手册；母子公司权限 E2E 收口 | 6.5 E2E-1…5 全绿；AC-IAM-01…20 全绿；覆盖率达标 |

---

## 7. 附录

### 7.1 错误码表（`eaio_iam` 段 21000–21999，本册为 iam 唯一号源）

**已分配 66 个号**（其余为留空段，空号不回收）：

| 码 | 枚举名 | 消息（中文） | 场景 |
|---|---|---|---|
| 21001 | `LOGIN_FAILED` | 用户名或密码错误 | 含账号不存在（枚举防护，3.3.3） |
| 21002 | `ACCOUNT_LOCKED` | 账号已锁定，请稍后重试 | 失败锁定 / IP 限流 |
| 21003 | `ACCOUNT_DISABLED` | 账号已停用 | `status ∈ {DISABLED, PENDING}` |
| 21004 | `CAPTCHA_INVALID` | 验证码错误或已过期 | 一次性消费 |
| 21005 | `TOKEN_INVALID` | 令牌无效或已失效 | 主动校验（Introspect/Refresh）发现无效 |
| 21006 | `REFRESH_TOKEN_INVALID` | 刷新令牌无效或已被使用 | 轮换重放 → 撤销 family |
| 21007 | `MFA_VERIFY_FAILED` | 动态口令校验失败 | TOTP 错误 / 重放 / challenge 作废 |
| 21008 | `PASSWORD_POLICY_VIOLATION` | 密码不符合安全策略 | 复杂度/长度/含用户名/历史重复 |
| 21009 | *（留空）* | — | 认证段保留 |
| 21010 | `ORG_NOT_FOUND` | 组织节点不存在 | — |
| 21011 | `ORG_TREE_CYCLE` | 组织移动会形成环 | 目标为自身或后代 |
| 21012 | `ORG_HAS_CHILDREN` | 组织存在下级，不可删除 | 删除前置 |
| 21013 | `ORG_STATE_NOT_ALLOWED` | 组织当前状态不允许该操作 | 含非法生命周期流转、归档组织被写 |
| 21014 | `DATA_SCOPE_DENIED` | 超出数据权限范围 | 含"授权不可放大"拦截 |
| 21015 | `ORG_CODE_DUPLICATED` | 组织编码已存在 | 部分唯一索引 |
| 21016 | `ORG_MOVE_TOO_LARGE` | 组织子树过大，需分批移动 | > `eaio.iam.org.move-max-nodes` |
| 21017 | `ORG_DEPTH_EXCEEDED` | 组织层级超出上限 | > `eaio.iam.org.max-depth` |
| 21018 | `ORG_HAS_ACTIVE_MEMBERS` | 组织下仍有在职人员或生效授权 | 注销/删除前置 |
| 21019 | *（留空）* | — | 组织段保留 |
| 21020 | `ROLE_NOT_FOUND` | 角色不存在 | — |
| 21021 | `ROLE_INHERITED` | 角色被继承，不可删除 | 先解除子角色继承 |
| 21022 | `ROLE_CODE_DUPLICATED` | 角色编码已存在 | — |
| 21023 | `ROLE_TEMPLATE_NOT_ASSIGNABLE` | 集团模板角色不可直接分配给用户 | `is_template = true` |
| 21024 | `ROLE_IN_USE` | 角色已被分配或存在生效授权 | 先回收 `user_role` |
| 21025 | `ROLE_INHERIT_INVALID` | 角色继承链非法（成环或超深） | 深度上限 5 |
| 21026–21029 | *（留空）* | — | 角色段保留 |
| 21030 | `PERMISSION_NOT_FOUND` | 权限点不存在 | 分配不存在的权限码 |
| 21031 | `PERMISSION_DENIED` | 无权限执行该操作 | 带权限码上下文（与通用 10403 并存） |
| 21032 | `FIELD_PERMISSION_DENIED` | 字段权限不足（不可编辑） | 写侧 STRICT 模式 |
| 21033 | `PERMISSION_CODE_DUPLICATED` | 权限码已存在 | — |
| 21034 | `PERMISSION_IN_USE` | 权限点存在子节点或被角色引用 | 删除前置 |
| 21035 | `DATA_SCOPE_RULE_NOT_FOUND` | 数据范围规则不存在 | 或资源未登记却有 SELF 配置 |
| 21036 | `DATA_SCOPE_RULE_CONFLICT` | 数据范围规则冲突 | 同主体同资源 ALLOW/DENY 冲突（按 DENY 执行） |
| 21037 | `DATA_SCOPE_TOO_WIDE` | 数据权限组织范围过大 | > `exists-max-ids`，退化为恒假并告警 |
| 21038 | `AUTHZ_CONFIG_INVALID` | 字段权限或 ABAC 策略配置非法 | 字段名与 DTO 不一致 / 策略 JSON 非法 |
| 21039 | *（留空）* | — | 权限段保留 |
| 21040 | `SOD_CONFLICT` | 违反职责分离约束 | 三处校验点（3.9.3） |
| 21041 | `SOD_RULE_DUPLICATED` | SoD 规则重复 | 编码/权限组重复 |
| 21042 | `SOD_RULE_NOT_FOUND` | SoD 规则不存在 | — |
| 21043 | `SOD_EXEMPTION_INVALID` | SoD 豁免无效或已过期 | — |
| 21044–21049 | *（留空）* | — | SoD 段保留 |
| 21050 | `SSO_CONFIG_INVALID` | SSO 配置错误 | 含"唯一 IdP 不可删除"前置 |
| 21051 | `LDAP_CONNECT_FAILED` | LDAP 连接或绑定失败 | bind 失败 |
| 21052 | `SSO_ASSERTION_INVALID` | SSO 断言或回调校验失败 | 签名/时间窗/state/nonce/重放 |
| 21053 | `IDP_MAPPING_FAILED` | 外部身份与本地账号映射失败 | 属性缺失/格式错误 |
| 21054 | `IDP_BINDING_REQUIRED` | 该外部身份未绑定本地账号 | `auto-create-user=false` |
| 21055 | `LDAP_USER_NOT_FOUND` | 目录中不存在该用户 | — |
| 21056 | `IDP_UNAVAILABLE` | 外部身份提供方不可用 | 超时/5xx |
| 21057 | `IDP_BINDING_DUPLICATED` | 该外部身份已绑定其他账号 | — |
| 21058–21059 | *（留空）* | — | 外部身份源段保留 |
| 21060 | `USERNAME_DUPLICATED` | 用户名已存在 | — |
| 21061 | `MOBILE_DUPLICATED` | 手机号已存在 | — |
| 21062 | `EMAIL_DUPLICATED` | 邮箱已存在 | — |
| 21063 | `USER_NOT_FOUND` | 用户不存在 | — |
| 21064 | `EMPLOYEE_NOT_FOUND` | 员工档案不存在 | — |
| 21065 | `USER_ORG_AMBIGUOUS` | 用户存在多个组织挂载，请指定组织 | 缺 `orgId` 的歧义调用 |
| 21066 | `USER_STILL_EMPLOYED` | 用户仍在职，不可删除 | 删除前置 |
| 21067 | `ID_CARD_DUPLICATED` | 证件号已存在 | `id_card_hash` 唯一 |
| 21068 | `EMPLOYEE_NO_DUPLICATED` | 员工编号已存在 | — |
| 21069 | *（留空）* | — | 用户/员工段保留 |
| 21070 | `SESSION_REVOKED` | 会话已撤销 | 被管理员踢下线时明确告知 |
| 21071 | `SESSION_NOT_FOUND` | 会话不存在或已过期 | 管理页/撤销入参 |
| 21072 | `PASSWORD_EXPIRED` | 密码已过期，请修改 | 90 天策略，受限令牌 |
| 21073 | `SESSION_LIMIT_EXCEEDED` | 在线会话数超限，已下线最久未用会话 | 提示可读原因 |
| 21074–21079 | *（留空）* | — | 会话段保留 |
| 21080 | `DATA_MODIFIED` | 数据已被他人修改，请刷新后重试 | 乐观锁冲突（iam 侧；通用冲突仍用 10003） |
| 21081 | `EVENT_PUBLISH_FAILED` | 事件发布失败 | outbox 投递失败（对外仅运维端点可见） |
| 21082 | `EVENT_ALREADY_CONSUMED` | 事件已消费（幂等拦截） | 消费方重复触发 |
| 21083 | `ACCESS_GRANT_INVALID` | 跨组织授权无效或已过期 | `access_grant` 超期/撤销 |
| 21084 | `TENANT_CONTEXT_MISSING` | 缺少组织上下文 | `TenantCtxProvider.require()` |
| 21085 | `TENANT_CONTEXT_INVALID` | 当前组织上下文非法 | 无挂载/组织已注销（防越权切换） |
| 21086–21089 | *（留空）* | — | 事件/上下文段保留 |
| 21090 | `CONTACT_NOT_VISIBLE` | 无数据权限查看该联系人 | 通讯录 |
| 21091 | `SERVICE_ACCOUNT_LOGIN_FORBIDDEN` | 服务账号不允许交互登录 | `user_type='SERVICE'` |
| 21092 | `FIELD_DECRYPT_FAILED` | 字段解密失败（密钥缺失或版本不匹配） | 不返回空值（3.2.5） |
| 21093–21999 | *（留空）* | — | 本册未分配，供 iam 后续演进与 P2–P3 使用 |

**与 P1 platform 册 4.7（21100–21146）的映射**（I-9：建议以本册为 iam 唯一号源，platform 册作废或平移）：

| platform 册 | 本册 | platform 册 | 本册 |
|---|---|---|---|
| 21100 `LOGIN_FAILED` | 21001 | 21123 `ORG_CODE_DUPLICATED` | 21015 |
| 21101 `ACCOUNT_LOCKED` | 21002 | 21124 `ORG_NOT_FOUND` | 21010 |
| 21102 `ACCOUNT_DISABLED` | 21003 | 21130 `ORG_STATUS_TRANSITION_INVALID` | 21013 |
| 21103 `CAPTCHA_INVALID` | 21004 | 21131 `ORG_ARCHIVED_READONLY` | 21013（并入"状态不允许"） |
| 21104 `MFA_REQUIRED` | **不占码**：由 `TokenDTO.mfaRequired` 表达（正常流程，非错误） | 21132 `ORG_HAS_ACTIVE_MEMBERS` | 21018 |
| 21105 `MFA_INVALID` | 21007 | 21140 `USER_NOT_FOUND` | 21063 |
| 21106 `PASSWORD_EXPIRED` | 21072 | 21141 `USERNAME_DUPLICATED` | 21060 |
| 21107 `TOKEN_INVALID` | 21005 | 21142 `PASSWORD_POLICY_VIOLATION` | 21008 |
| 21108 `TOKEN_REVOKED` | 21070（撤销会话时）；受保护端点缺令牌仍用通用 `10401` | 21143 `PASSWORD_REUSED` | 21008（并入策略） |
| 21109 `REFRESH_TOKEN_INVALID` | 21006 | 21144 `ROLE_IN_USE` | 21024 |
| 21110 `SOD_CONFLICT` | 21040 | 21145 `IDP_BINDING_REQUIRED` | 21054 |
| 21111 `PERMISSION_DENIED` | 21031 | 21146 `IDENTITY_PROVIDER_ERROR` | 21056 |
| 21112 `DATA_SCOPE_DENIED` | 21014 | 21120 `DATA_SCOPE_TOO_WIDE` | 21037 |
| 21121 `ORG_TREE_CYCLE` | 21011 | 21122 `ORG_MOVE_TOO_LARGE` | 21016 |

### 7.2 权限点命名规范与总表

**规范**：`iam:<资源>:<动作>`（小写驼峰资源名 + 动作词），动作词取自 P0 动作词表（`list/get/add/up/del`）+ 业务动作（`move/transition/assign/lifecycle/resetPwd/disable/revoke/test/export/retry/exempt/check/mfa`）。**共 54 条**（14 条 `MENU` + 40 条 `BUTTON`/`API`）：

| 资源 | 权限点（`perm_type=MENU` 的排在首位） | 数量 |
|---|---|---|
| 组织 | `iam:org:list`(MENU) · `iam:org:add` · `iam:org:up` · `iam:org:del` · `iam:org:move` · `iam:org:lifecycle` | 6 |
| 用户 | `iam:user:list`(MENU) · `iam:user:add` · `iam:user:up` · `iam:user:del` · `iam:user:disable` · `iam:user:resetPwd` · `iam:user:role` · `iam:user:mfa` | 8 |
| 岗位 | `iam:position:list`(MENU) · `iam:position:add` · `iam:position:up` · `iam:position:del` | 4 |
| 员工 | `iam:employee:list`(MENU) · `iam:employee:up` | 2 |
| 角色 | `iam:role:list`(MENU) · `iam:role:add` · `iam:role:up` · `iam:role:del` · `iam:role:assign` | 5 |
| 权限点 | `iam:permission:list`(MENU) · `iam:permission:add` · `iam:permission:up` · `iam:permission:del` | 4 |
| 数据范围 | `iam:dataScope:list`(MENU) · `iam:dataScope:save` · `iam:dataScope:del` | 3 |
| 字段权限 | `iam:field:list`(MENU) · `iam:field:save` · `iam:field:del` | 3 |
| ABAC | `iam:abac:list`(MENU) · `iam:abac:add` · `iam:abac:up` · `iam:abac:del` · `iam:abac:test` | 5 |
| SoD | `iam:sod:list`(MENU) · `iam:sod:save` · `iam:sod:del` · `iam:sod:exempt` · `iam:sod:check` | 5 |
| 身份源 | `iam:idp:list`(MENU) · `iam:idp:save` · `iam:idp:test` | 3 |
| 会话 | `iam:session:list`(MENU) · `iam:session:revoke` | 2 |
| 通讯录 | `iam:contact:list`(MENU) · `iam:contact:export` | 2 |
| 事件运维 | `iam:event:list`(MENU) · `iam:event:retry` | 2 |

**注**：`permission/Check`、`ListPermissions`、`MenuTree`、`Profile`、`auth/*`、`session/GetPage`（本人）为登录即可（不占权限点）；`iam:user:mfa` 同时覆盖"管理员重置 MFA"。

### 7.3 系统内置角色与权限矩阵

| 角色（`role.code`） | 类型 | 权限范围 | 数据范围 | 说明 |
|---|---|---|---|---|
| `SUPER_ADMIN` | SYSTEM | 全部 54 条 | `ALL` | 同时置 `sys_user.super_admin`（唯一绕过标记，留审计） |
| `GROUP_ADMIN` | SYSTEM | 全部 54 条 | `ALL` | 集团管理员：组织全树、模板下发、跨公司授权 |
| `COMPANY_ADMIN` | SYSTEM | 除 `iam:org:del`/`iam:org:lifecycle` 外的组织与用户管理（44 条） | `ORG_AND_SUB` | 子公司自治：本公司子树内用户/角色/数据范围（授权不可放大） |
| `AUDITOR` | SYSTEM | 只读类 15 条（`*:list`/`session:list`/`sod:check`） | `ORG_AND_SUB` | 审计员：只读 + 会话查看（不分配角色） |
| `BUSINESS_USER` | SYSTEM | `iam:contact:list`、`iam:contact:export` + 后续业务模块权限 | `SELF`（可被规则放宽到 `DEPT`） | 基础业务角色，登录后可用通讯录与个人中心 |

**矩阵约束**：① 数据范围不得超过角色 `org_scope_id` 允许的范围（3.5.5 授权不可放大）；② 内置角色 `role_type='SYSTEM'` 不可删除，可复制后修改（复制体为 `CUSTOM`）；③ `SUPER_ADMIN` 不豁免 SoD（3.9.6）。

### 7.4 JWT claim 表（access token，RS256）

| claim | 类型 | 必填 | 语义 |
|---|---|---|---|
| `iss` | string | ✅ | 签发者（配置 `eaio.iam.jwt.issuer`，默认服务基址） |
| `sub` | string | ✅ | 用户 id（`sys_user.id` 的十进制字符串） |
| `aud` | string/array | ✅ | 受众（自有前端 client id / 内部服务 id） |
| `exp` / `iat` / `nbf` | number | ✅ | 过期/签发/生效（秒）；`clock-skew` 60s |
| `jti` | string | ✅ | 令牌唯一 id（用于审计与单令牌排查） |
| `sid` | string | ✅ | 会话 id（对应 `auth_session.sid` 与 Redis 会话键） |
| `org_id` | number | ✅ | 登录时默认组织上下文（可被 `X-Org-Id` 覆盖，覆盖时校验挂载） |
| `org_path` | string | — | 物化路径（前端面包屑预热；**校验时以后端为准**，不信任客户端回传） |
| `ver` | number | ✅ | 权限版本（`perm:ver:{userId}`，用于快照失效判定；不等于 `token_version`） |
| `tv` | number | ✅ | `sys_user.token_version`（用于用户级全量失效判定） |
| `amr` | array | ✅ | 认证方式：`["pwd"]` / `["pwd","otp"]` / `["oidc"]` / `["ldap"]` / `["client"]` |
| `scope` | string | — | 受限令牌用（如 `pwd_change`：只允许改密端点） |
| `typ` | string | ✅ | `at`（access）/ `rt` 由 refresh 端点单独签发（refresh 不是 JWT） |

**不放进令牌的声明**：权限码清单、数据范围、字段权限、手机号/邮箱等敏感属性——理由见 3.4.3（撤销与一致性）。

### 7.5 与 P0 册 6.3 待细化事项的落实对照

| P0 册 6.3 | 本册处置 | 落点 |
|---|---|---|
| ① `TenantCtx` 实现与过滤器接入 | ✅ 本册定案：`TenantContext` record + `TenantContextHolder`（ThreadLocal）+ `TenantContextFilter`（链位、防越权切换、MDC、异步传播） | 3.10、5.4 |
| ② `@AuditLog` 切面与 audit 接入 | 🔶 部分：本册定**iam 侧事件契约与 `PermissionAuditApi` 可选注入分期**；切面本体随 audit（M4） | 3.11、5.4、7.6 L-4 |
| ③ platform 参数/字典/File/Scheduler 契约 | ➖ 非本册范围（platform 册 3.x）；本册只消费：`ParamApi`（策略参数）、`DictApi`（字典）、`SchedulerApi`（清理任务）、`ExcelApi`（导出） | 3.3.7、4.5、4.8 |
| ④ 数据权限 SQL 改写方案 | ✅ 本册定案：MP 内置拦截器 + `MultiDataPermissionHandler`；白名单资源、恒假兜底、超管绕过、跨公司 `access_grant`、性能与索引要求、三档降级 | 3.6、4.3.7 |
| ⑤ 事件总线可靠性（重试/死信/幂等） | ✅ iam 侧定案：`event_outbox` + `event_consume_record`（事件ID+消费者+业务ID）+ 指数退避 + DEAD + 运维重投端点 | 3.11、4.3.13 |
| ⑥ 信创数据库适配层 | ⏭ 推迟（P2）：本册只用 PG 标准 SQL 与 `TIMESTAMPTZ`/`BOOLEAN`/`CHECK`，未用 PG ENUM、`JSONB`、厂商私有语法；闭包表维护**不用触发器/存储过程**（这一条同时服务于信创适配） | 2.4.4 I-12、3.1.5、4.1 |
| ⑦ ArchUnit「模块登记完备」「业务错误码落段」断言 | ✅ 本册要求：A1/A2 + A3–A10 共 10 条新增断言，M2 起生效（含控制组） | 6.3 |
| ⑧ `eaio.idempotency.fail-open` 评估 | ⏭ 维持 P0 决策：恒 fail-closed，不加开关；iam 侧新增的"会话校验/验证码校验"同样 fail-closed（3.4.6） | 3.3.8、3.4.6 |

### 7.6 遗留与后续分册承接

| 编号 | 遗留项 | 影响 | 承接 |
|---|---|---|---|
| L-1 | 审计列名与 `common.persistence.BaseDO` 不一致（本册 `created_at/…` vs platform 册裁决的 `create_time/…`；逻辑删除列 `BOOLEAN` vs `SMALLINT`） | 两册并存时 DO 基类需二选一；iam 在 M2 先用自有 `IamBaseDO`，不阻塞 | P1 收口评审：统一后同步 `database.md` 与 P0 册 4.4（见 2.4.4 I-1/I-12） |
| L-2 | OAuth2/SAML2 协议端点与 ADR-0001「全部 POST + JSON + 恒 200」冲突 | 需在 ADR-0001 补登"协议端点例外"（第三类例外，与文件上传下载并列） | P1 评审 → ADR-0001 修订（本册不改 ADR） |
| L-3 | 密钥管理（KMS/HSM）未引入：P1 为"环境变量/参数中心注入 + 手工轮换" | 密钥轮换需重启加载（`JwtKeyProvider` 支持双 kid 热切换；字段密钥轮换走 `iam.field.rekey` 任务） | P3 评估 KMS（SRS NFR-SEC-02 的长期口径） |
| L-4 | 短信 MFA 未实现（只 TOTP） | FR-SEC-02 的"短信"通道缺失 | P2/P3 随 `integration`（短信通道）+ `NoticeApi` 落地；`sys_user` 无需改表（新增 `user_mfa_factor` 表即可） |
| L-5 | SAML2 依赖真实 IdP 与证书环境验证 | M5 交付时若环境未就绪，只能交付单元/内嵌 IdP 验证 | M5 评审确认；必要时顺延到 M6（需在批次内登记，不静默延期） |
| L-6 | 数据权限"申请/授权/回收流程"（FR-SEC-14）未实现 | P1 只有 `DataScopeApi.save/del` + `access_grant` 表 | P2 册（流程本体）+ approval 模块 |
| L-7 | 与 P1 platform 册（第 4 章 iam 段、5.4.2 表清单）的一致性收口：表名（`sys_user`/`org_node` 等）、错误码（21100 段）、`api` 方法名（platform 册 4.6 的 `has/hasAll/rolesOf/…` 与本册契约清单不同） | 两册并存期间会产生"同名不同形" | 以本册为 iam 契约与号源，platform 册第 4 章改为"指向本册"；差异清单见 7.1 附注与本节表 |
| L-8 | 迁移首个文件名（批次总册写 `V1__init.sql`，本册用 `V1__baseline.sql`） | 仅命名，不影响执行 | 批次评审确认命名（本册按 platform 一致命名，见 I-13） |
| L-9 | 组织闭包表一致性校验任务只告警不自动修复 | 人工介入成本 | 保留设计（自动修复会掩盖根因）；若运维要求，M6 评估"半自动修复 + 差异报告" |

---

## 修订记录

| 版本 | 日期 | 修订人 | 说明 |
|---|---|---|---|
| V1.0 | 2026-09-20 | iam 模块设计（代理编写） | 首版：第 P1 册（分册二）iam 详细设计。含 31 张表 DDL、10 个跨模块契约接口（+ audit 侧 `PermissionAuditApi`）、5 个领域事件、66 个错误码、14 个迁移脚本；落实 P0 册 6.3 第 ①④⑤⑦ 条与批次总册 A1–A6；上游冲突裁决 I-1…I-14 见 2.4.4 |

*本册为 P1 批次 iam 模块的唯一详细设计依据；与 P1 批次总册（范围与裁决）、P0 册（契约与工程地基）、platform 册（平台能力契约）配套阅读。表中"P1 新增"标注的资产在 P0 代码中不存在，由 M2–M5 实现并按 6.6 验收。*









