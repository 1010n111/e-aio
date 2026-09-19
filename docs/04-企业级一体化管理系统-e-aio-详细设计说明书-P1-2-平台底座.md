---
title: 企业级一体化管理系统详细设计说明书（P1 册 · 平台底座）
type: 详细设计说明书（DD）· 第 P1 册
phase: 详细设计
version: V1.0
status: 进行中（M1–M5 段：顺序 3 platform + 顺序 4 iam）
date: 2026-09-19
tags:
  - e-aio
  - DD
  - 详细设计
  - P1
  - platform
  - iam
aliases:
  - e-aio DD P1 册
  - P1 平台底座详细设计
related:
  - "[[04-企业级一体化管理系统-e-aio-详细设计说明书]]"
  - "[[04-企业级一体化管理系统-e-aio-详细设计说明书-P0-工程地基]]"
  - "[[03-企业级一体化管理系统-e-aio-概要设计说明书]]"
---

# 企业级一体化管理系统（e-aio）详细设计说明书 · 第 P1 册（平台底座）

> **项目名称**：企业级一体化管理系统（e-aio）
> **编制依据**：GB/T 8567-2006《计算机软件文档编制规范》、GB/T 9385-2008《计算机软件需求规格说明规范》
> **上游文档**：[[01-企业级一体化管理系统-e-aio-可行性研究报告|01-可行性研究报告]]、[[02-企业级一体化管理系统-e-aio-软件需求规格说明书|02-软件需求规格说明书（SRS）]]、[[03-企业级一体化管理系统-e-aio-概要设计说明书|03-概要设计说明书（HLD）]]、[[04-企业级一体化管理系统-e-aio-详细设计说明书-P0-工程地基|04-DD 第 P0 册 · 工程地基]]
> **版本**：V1.0（**M1–M5 段**；顺序 5–9 待 M6–M9 段续编）
> **日期**：2026-09-19

---

## 核心结论（执行摘要）

1. **本册只管 P1 的顺序 3–9**（HLD 13.2 / DD 总册 1）。本次交付 **M1–M5 段**：顺序 3 `platform`（M1–M3）+ 顺序 4 `iam`（M2–M5）；顺序 5–9（audit / workflow+approval / mdm / oa / portal）**待 M6–M9 段编写**，但本册已为它们冻结必须遵守的边界（2.4 契约先行、4.5 权限事件、附录 7.4 交接清单）。
2. **P0 契约 V1 一行不改**：`Result<T>` / `PageResult<T>` / `ErrorCode` / 幂等 / 每模块独立 Schema 与 Flyway 实例全部沿用（P0 册 3.2、3.6）。P1 只在**预留段内新增**平台与 iam 的业务错误码（20000–20999 / 21000–21999）。
3. **M1–M5 的唯一新增 Maven 模块是 `e-aio-iam`**；`e-aio-platform` 在 P0 已建（迁移骨架承载），P1 起填充业务代码。P0 册 6.2 冻结清单里**尚未落地**的 common 门面（`RedisKit`/`ExcelKit`/`TreeUtils`/校验注解…）与 P1 依赖在 **M1 头两周**补齐（2.3.3、2.7）——**这是 P1 的第一件实事，不是可选项**：platform/iam 的任何一行业务代码都依赖它们。
4. **两处 HLD 未决项在本册裁决**：定时任务 = **Spring Task + ShedLock**（不用 Quartz/XXL-JOB）；租户上下文 = **iam 承载 `TenantCtx`/`TenantCtxProvider`（`com.eaio.iam.api`）、platform 与 audit 只经 `api` 可选注入读取（本册裁决，详见 4.5）**（platform 因此**不依赖 iam**，依赖矩阵不破）。
5. **`TenantCtx` 是本册最重要的技术缝**：它把「母子公司数据隔离」变成一条可测的规则——所有数据访问经 `TenantCtx` 取当前组织，并由 MyBatis-Plus 拦截器强制追加 `org_id` 过滤条件，漏带上下文即**拒绝查询并报错**（fail-closed），而不是"查全表"。
6. **验收锚点**：M1–M3 末 `platform` 可配置可调度（参数热更新、字典、文件、任务、Excel 异步任务）；M5 末 `iam` 打通「登录 → 令牌 → 鉴权 → 数据权限过滤 → 权限变更留痕」全链路，并交付 HLD 13.3 中属于本册的 **母子公司权限 E2E** 部分（组织树 / 数据权限 / SoD）。

---

## 1. 引言

### 1.1 编写目的

本册是 **P1 平台底座批次**的详细设计（DD），把 HLD 第 5.2 / 5.6 节的模块职责、SRS 3.1 / 3.22 的功能需求、以及 P0 册冻结的工程契约，落到可编码、可测试的粒度：

- 模块的五层分包与类清单（跨模块只暴露 `api`）；
- 对外 API 契约（接口方法 + DTO 字段 + 统一 POST 路径 + 动作词）；
- 业务错误码（在预留段内自增，空号不回收）；
- 数据结构设计（Schema、表 DDL、索引、迁移脚本、种子数据）——数据库设计不单独成文档（DD 总册 1）；
- 测试与验收（本册在自己那段 M1–M5 内可验证的判据）。

### 1.2 文档范围与分期

| 顺序 | 模块 | 里程碑 | 前置 | 本册状态 |
|---|---|---|---|---|
| 3 | `platform` 基础平台 | M1–M3 | common | ✅ **本次详设**（第 3、5 章） |
| 4 | `iam` 身份与访问管理 | M2–M5 | platform | ✅ **本次详设**（第 4、5 章） |
| 5 | `audit` 双审计引擎 | M4–M6 | iam、platform | ⏳ 待 M6–M9 段；本册只冻结其**入参契约**（3.13 与 4.8 的事件清单、3.10 的导出留痕） |
| 6 | `workflow` + `approval` | M5–M8 | iam、audit、platform | ⏳ 待续编；本册只冻结**权限与组织取数口**（4.6 `DataScopeApi`、`OrgApi`） |
| 7 | `mdm` 主数据 | M6–M8 | iam、audit、workflow | ⏳ 待续编 |
| 8 | `oa` 协同（首个业务模块） | M7–M9 | iam、audit、platform | ⏳ 待续编 |
| 9 | `portal` 门户收口 | M8–M9 | iam、approval、oa、platform | ⏳ 待续编 |

**不跨批次**：P2 业务域（report/project/finance/…）与 P3（integration/mobile/i18n）不在本册，即使 SRS 把某些能力标为 P1 重要度（SRS 553 行已声明"优先级语义≠开发批次"）。**唯一的例外登记**：FR-SEC-14「数据权限申请/授权/回收流程」SRS 标 P2，本册只在 `data_scope_rule` 表预留 `source` 字段，不做流程（避免 P1 提前实现 P2）。

### 1.3 术语与约定

术语一律取 [`CONTEXT.md`](../CONTEXT.md)（模块 / 五层分包 / 命名接口 / 统一返回体 / HTTP 状态码恒 200 / 错误码分段 / 幂等键 / 每模块独立 Schema / 每模块独立 Flyway 实例 / 逻辑删除）。本册新增并沿用以下**本册定义**的词汇：

| 术语 | 本册定义 |
|---|---|
| 组织节点（org node） | `eaio_iam.org_node` 一行；节点类型（`node_type`）取 `GROUP`(集团)/`COMPANY`(公司)/`DEPT`(部门)/`TEAM`(团队)，`parent_id` 无限级 |
| 闭包表（closure table） | `eaio_iam.org_node_path`：`(ancestor_id, descendant_id, depth)`，子树查询不再递归 |
| 当前组织上下文 | `TenantCtx`：请求级「当前组织 + 数据范围 + 用户」，由 iam 认证过滤器写入 iam 内部 `TenantContextHolder`（ThreadLocal，实现不外泄），全模块只读 |
| 数据范围（data scope） | 枚举 `SELF / DEPT / ORG / ORG_AND_SUB / ALL / CUSTOM`（本人 / 本部门 / 本组织 / 本组织及下级 / 全集团 / 自定义） |
| 权限点（permission） | 权限的最小判定单位，字符串 `module:resource:action`（全小写，如 `iam:user:reset`）；角色与权限点多对多 |
| 参数分级 | 参数（param）按 `scope_type` 取 `SYSTEM` / `ORG`，后者按 `org_id` 覆盖前者（三级配置：代码默认 → env 文件 → 参数中心） |
| 任务（job） | platform 内一条可调度定义（`job`），执行一次留一条 `job_run` |
| 异步导出/导入任务 | platform 的 `excel_task`：长任务**不以幂等键交付结果**，返回 `taskId` 由前端轮询（P0 册 3.2.5） |

### 1.4 参考资料

1. [[03-企业级一体化管理系统-e-aio-概要设计说明书|HLD]]：2.2 模块边界、2.6 API 规约、4.1–4.3 数据架构、5.2 iam、5.6 platform、6.1–6.3 数据/任务、7.1–7.2 错误与审计、8.1–8.5 认证授权、9.3 分层配置、11.1 依赖矩阵、13.2 开发队列、13.2.1 RuoYi 改造落点、13.3 验收。
2. [[02-企业级一体化管理系统-e-aio-软件需求规格说明书|SRS]]：3.1 FR-SEC-01…15、3.10 FR-HR-06/07/08、3.22 FR-PLT-01…08、第 4 章 NFR。
3. [[04-企业级一体化管理系统-e-aio-详细设计说明书-P0-工程地基|P0 册]]：3.1 工程初始化、3.2 契约核心、3.6 Flyway、3.7 ArchUnit、4.x common 门面、6.1 模块登记表、6.3 待 P1 细化事项。
4. [`ADR-0001`](adr/0001-unified-post-and-always-200-result-contract.md)（统一 POST + 恒 200）、[`ADR-0002`](adr/0002-per-module-schema-and-flyway-instance.md)（每模块 Schema + 独立 Flyway）。
5. [`docs/agents/`](agents/)：architecture / api-conventions / database / java-conventions / frontend-conventions / build-and-test / tech-stack（评审基准，冲突时优先级高于 03/02）。

---

## 2. P1 总体设计

### 2.1 P1 目标与交付物

P1 交付**平台底座**：让 P2 的每个业务模块**不必再发明**参数、字典、文件、任务、缓存、Excel、身份、授权、组织与数据权限。

| 里程碑 | 交付物 | 判据（本册 6.2） |
|---|---|---|
| M1–M3 | `platform`：参数中心（分级+热更新）、字典、文件 `FileApi`、任务调度（Spring Task + ShedLock）、Excel、缓存、监测、公告、消息模板 | 开关式验收：改参数不重启即生效；任务按 cron 跑且日志可查；上传/下载经 `FileApi`；10 万行 Excel 流式导出不 OOM |
| M2–M5 | `iam`：组织（无限级+闭包表）、用户/员工/岗位（多组织兼任）、认证（JWT+可撤销会话）、RBAC+菜单、数据权限（SQL 改写）、字段权限、SoD、权限变更留痕 | 母子公司权限 E2E（组织树 / 数据范围 / SoD）通过；越权查询被拦截并有审计事件 |

### 2.2 依赖与拓扑（HLD 13.4 链式，链内串行）

```
common ──▶ platform ──▶ iam ──▶ audit ──▶ workflow+approval ──▶ mdm ──▶ oa ──▶ portal
             (M1–M3)     (M2–M5)  (M4–M6)      (M5–M8)         (M6–M8) (M7–M9) (M8–M9)
```

- **契约先行**：相邻模块可重叠，但**下游只能依赖上游已冻结的 `api`**。因此本册必须先把 `platform` 的 `FileApi/ParamApi/DictApi/SchedulerApi/CacheApi`（3.4–3.9）与 `iam` 的 `OrgApi/UserApi/PermissionApi/DataScopeApi/TenantCtxProvider/AuthnApi/SoDCheckApi`（4.2–4.9）定死，audit 及以后才能开工。
- **单向**：`platform` 不依赖任何模块（HLD 11.1）；`iam` 只依赖 `platform`。反向依赖由 ArchUnit 拦（P0 册 3.7，P1 起作用面非空）。
- **禁止跨 Schema SQL**：iam 需要 platform 的文件/参数时走 `api` 接口，不 JOIN `eaio_platform`。

### 2.3 M1–M5 工程结构总览

#### 2.3.1 Maven 模块

| 模块 | 状态 | 说明 |
|---|---|---|
| `e-aio-common` | P0 已建 | 契约 V1 冻结（只允许向后兼容新增）；P1 补齐 2.7 清单：`crypto`(AES-GCM)、`persistence`(BaseDO) 等门面（`TenantCtx` 归 iam，见 4.5）、`redis`(RedisKit)、`excel`(ExcelKit)、`validation`、`TreeUtils/BeanUtils/CollectionUtils` |
| `e-aio-platform` | P0 已建（仅迁移骨架） | P1 填充 `api/application/domain/infrastructure/events` |
| `e-aio-iam` | **P1 新增** | 父 POM 登记 + `package-info`（`@ApplicationModule`）+ `api` 包（`@NamedInterface("api")`）+ 迁移目录 `classpath:db/migration/iam` + Schema `eaio_iam` |
| `e-aio-app` | P0 已建 | 装配与启动；**新增** Security 过滤器链装配、`TenantContextFilter` 注册、MyBatis-Plus 拦截器装配（4.6.3） |

新增模块的**唯一作业清单**以 P0 册 3.1.3 与附录 6.1 为准（父 POM 一行、`package-info`、`api` `package-info`、迁移目录、错误码枚举、ArchUnit 断言对象）。本册不重复该清单，只要求：**每加一个模块，P0 册 6.3 第 7 条声明的两条架构断言必须同时补测**（模块登记完备 + 业务错误码落在登记段内）。

#### 2.3.2 前端挂载点

`frontend/src/views/` 下按模块挂目录（`views/platform/**`、`views/iam/**`），`src/api/` 按模块增文件；请求层、幂等键、`10401` 跳登录沿用 P0 的 `src/api/request.js`（`docs/agents/frontend-conventions.md`）。本册不引入新前端依赖（Elixir/Element Plus 已在 P0 基线）。

#### 2.3.3 P1 新增依赖（逐项登记；M1 落地并回写 `docs/agents/tech-stack.md`）

| 依赖 | 坐标 | 用途 | 落点 | 许可证 / 版本 |
|---|---|---|---|---|
| Spring Security | `org.springframework.boot:spring-boot-starter-security` + `spring-security-test`(test) | 认证过滤器链、`DelegatingPasswordEncoder`(bcrypt) | iam、app | Apache-2.0 / Boot BOM |
| JWT | `org.springframework.security:spring-security-oauth2-jose`（Nimbus JOSE+JWT） | JWT 签发与校验 | iam | Apache-2.0 / Boot BOM |
| MyBatis-Plus | `com.baomidou:mybatis-plus-spring-boot4-starter` | 持久层（含分页/数据权限拦截器） | platform、iam | Apache-2.0 / **3.5.17**（tech-stack 已定） |
| Redisson | `org.redisson:redisson-spring-boot-starter` | `RedisKit` 底座（缓存/锁/限流） | common、app | Apache-2.0 / **4.7.0**（P0 册 3.1.2 已核实坐标） |
| ShedLock | `net.javacrumbs.shedlock:shedlock-spring` + `shedlock-provider-redisson` | 定时任务分布式锁 | platform | Apache-2.0 / M1 核实后锁定 |
| Caffeine | `com.github.ben-manes.caffeine:caffeine` | 本地一级缓存 | platform、iam | Apache-2.0 / M1 核实后锁定 |
| Hutool | `cn.hutool:hutool-all` | 工具门面底座 | common | **Mulan PSL v2** / 5.8.47 |
| Lombok | `org.projectlombok:lombok`(provided) | 编码约定（java-conventions） | 全部模块 | MIT / M1 核实后锁定 |
| MapStruct | `org.mapstruct:mapstruct` + `mapstruct-processor` + `lombok-mapstruct-binding` | 跨模块契约映射（`unmappedTargetPolicy=ERROR`） | platform、iam | Apache-2.0 / M1 核实后锁定 |
| Fesod | `org.apache.fesod:fesod-sheet` | `ExcelKit` 底座 | common | Apache-2.0 / 2.0.2-incubating |
| 结构化日志 | `net.logstash.logback:logstash-logback-encoder` | JSON 日志（NFR-MAINT-03） | app（`logback-spring.xml`） | Apache-2.0 / **BOM 管，不写版本** |
| API 文档 | `org.springdoc:springdoc-openapi-starter-webmvc-ui` | OpenAPI 3.x 统一 POST 标注（HLD 2.6.4） | app | Apache-2.0 / 3.1.1 |

> **已关闭的未决项**：`docs/agents/tech-stack.md` 的"定时任务 Quartz / XXL-JOB / Spring Task + ShedLock"在本册裁决为 **Spring Task + ShedLock**（HLD 13.2.1 同向，见裁决表 C-8）；ShedLock 用 **Redisson 提供者**（对齐 tech-stack"缓存/锁 Redis（Redisson 底层）"，不引入第二套 Redis 客户端）。
> **JWT 不引入 jjwt**：用 Boot BOM 管的 `spring-security-oauth2-jose`（Nimbus JOSE+JWT），避免自管版本的加密库（NFR-SEC-01/等保三级：自管版本越少越好）。JWT 类型只允许出现在 iam 的 `infrastructure` 内，对外只暴露 `AuthnApi` 的令牌字符串与过期秒数。
> **版本纪律**：BOM 管的依赖**不写版本**（Boot/Modulith/Jackson/springdoc 由 BOM 统一；`logstash-logback-encoder` 9.0 亦交 BOM 管，P0 册 3.1.2）；精确锁定项写版本；表中标"M1 核实后锁定"的 5 项（ShedLock/Caffeine/Lombok/MapStruct 及其 binding）**在 M1 落地时核实 Maven Central 稳定版后回写本节与 tech-stack.md**——本册不预填未核实的版本号，避免假精确。
> **许可证门**：Hutool 是 **Mulan PSL v2**，`license-maven-plugin` 的 `includedLicenses` 白名单**必须同步加入**，否则 CI 阶段 6 直接红（P0 册 3.8）；其余均为 Apache-2.0/MIT。

### 2.4 链式契约先行：本册为下游冻结的边界

| 冻结项 | 内容 | 消费方 |
|---|---|---|
| 组织与用户取数 | `OrgApi`（树/子树/路径/按 id 批量取）、`UserApi`（按 id 批量取简档） | audit、workflow、oa 及全部业务模块 |
| 权限判定 | `PermissionApi.hasPermission(code)`、`DataScopeApi.appendScope(条件构建器)`、`SoDCheckApi.check(...)` | workflow/approval（审批人解析、跨公司审批） |
| 当前组织上下文 | `com.eaio.iam.api.TenantCtxProvider.current()`（platform/audit 为可选注入；`TenantContextHolder` 是 iam 内部实现） | 全部模块 |
| 权限/组织变更事件 | `UserRoleChangedEvent`、`OrgNodeChangedEvent`（AFTER_COMMIT） | audit（权限审计留痕） |
| 文件与参数 | `FileApi`、`ParamApi`、`DictApi` | 全部模块 |
| **审计挂钩（为 audit 预留，本册不实现）** | 平台侧统一发布 `AuditEvent`（操作人/时间/IP/模块/对象/动作/前后值/结果），audit 在 M4–M6 订阅落库 | audit |

> 本册**不删除**任何冻结项：冻结后再改走评审（HLD 13.4）。新增能力只能**向后兼容新增**（加方法、加 DTO 字段）。

### 2.5 上游冲突裁决表（本册为最终解释）

HLD/SRS 与 AGENTS 系列（评审基准，优先级最高）存在不一致时，按下表裁决并留痕：

| # | 冲突点 | 上游说法 | **本册裁决** | 依据 |
|---|---|---|---|---|
| C-1 | 审计字段命名 | HLD §4.3：`created_at/created_by/updated_at/updated_by/version` | 采用 **`created_by/created_at/updated_by/updated_at`**（P0 `BaseDO` 基类，AGENTS [`database.md`](agents/database.md)）；**另加 `version`** 列承载 HLD §6.2 的乐观锁要求 | AGENTS 系列 > 03 概要设计 |
| C-2 | 组织生命周期状态 | HLD §5.2：筹备→运营→注销→**归档**；§4.1.1：筹备/运营/注销 | 四态 `PREPARING/RUNNING/CANCELLED/ARCHIVED`；**注销 ≠ 归档**：注销触发权限清理（踢下线 + 停用组织内角色授权），归档只读冻结 | 取并集，语义更严格者优先（5.3.1） |
| C-3 | 数据范围枚举 | HLD §5.2 六值；§4.1.1 五值（缺"本组织及下级"） | 六值 `SELF/DEPT/ORG/ORG_AND_SUB/ALL/CUSTOM` | 取并集；`ORG_AND_SUB` 是母子公司隔离的必需值 |
| C-4 | 平台 API 清单 | HLD §5.6 有 `MonitorApi`；§3.2 未列 | **保留 `MonitorApi`**（只读，暴露 Actuator 聚合视图） | §5.6 是模块职责权威 |
| C-5 | iam API 清单 | HLD §5.2 有 `DataScopeApi`/`TenantCtxProvider`/`OrgLifecycleApi`；§3.2 未列 | **全部保留并登记**（附录 7.3） | 同上；三者已是 2.4 冻结项 |
| C-6 | 平台"文件权限复用 iam 数据权限" vs 依赖矩阵 platform 无依赖 | HLD §5.6 vs §11.1 | platform **不依赖 iam**：文件只打 `org_id`/`owner_id` 标签，**鉴权由调用方在其事务内完成**，下载用一次性签名 URL（带时效 + 绑用户） | 依赖矩阵是冻结项（AGENTS architecture.md）；破环依赖会与 ADR-0002 的模块边界冲突 |
| C-7 | 平台"审计留痕" vs platform 不依赖 audit | HLD §5.6 vs §11.1 | platform/iam 只**发布 `AuditEvent`**，audit 订阅落库（M4–M6）；发布方不依赖消费方 | Spring Modulith 事件解耦（AGENTS architecture.md） |
| C-8 | 定时任务选型 | HLD 13.2.1：Spring Task + ShedLock（Quartz 可选保留）；tech-stack：未决 | **Spring Task + ShedLock**；不引入 Quartz/XXL-JOB | 关闭未决项，见 2.3.3 |
| C-9 | 认证中心形态 | HLD §5.2："Spring Security + OAuth2 Authorization Server（自托管）" | M2–M5 只做**资源服务器的 JWT 校验**与**自建登录端点**；**不作 IdP**（OAuth2 Authorization Server 留 P3 integration：需要对外发令牌时再上）；SSO 只作**客户端**（OIDC/SAML2 Relying Party）+ LDAP | 避免 P1 过度实现；FR-SEC-03 的"对接"语义是接入上游 IdP |
| C-10 | 公司/租户字段 | HLD §2.5：业务表带 `org_id`/`company_id` | **统一只用 `org_id`**；"公司"是 `org_node.node_type = COMPANY` 的节点，不设 `company_id` 冗余列 | 一列一语义，避免双写不一致 |
| C-11 | "整体覆盖率 ≥80% 自 P1 生效" | build-and-test.md 质量门 | 生效范围 = **P1 新增模块的 `application`+`domain` 行覆盖**；`api`/DTO/装配类不计入；**不达标即 CI 红** | 机械可判定，避免把 DTO 灌水凑数 |
| C-12 | RuoYi 任务模型（DB 存 `invoke_target` 字符串 + 反射调用任意 bean 方法） | HLD 13.2.1"Quartz → Spring Task + ShedLock"未说调用方式 | **只允许代码注册的任务处理器**：`JobHandler` 接口 + `@JobHandler("code")` 启动扫描登记；DB 只存 `code`/开关/cron/超时。**禁止按 DB 字符串反射调用任意 bean**（RCE 面） | 安全边界：不可信输入不得变成方法调用（NFR-SEC-07） |
| C-13 | 表名风格 | HLD §4.3"表名 snake_case **复数**" vs §4.1.1 点名 `org_node`/`role`/`user`（单数） | 以**逐一点名的表名为准**（`org_node`/`org_node_path`/`role`/`permission`/`user_role`/`org_position`/`data_scope_rule`/`sod_rule`）；platform 用 `sys_*` 前缀。"复数"是风格句，不覆盖点名声明的专指性 | 特指 > 通则；避免为了复数改掉已点名的表 |
| C-14 | `user` 是 PostgreSQL 保留字 | HLD §4.1.1 表名 `user` | 表名用 **`sys_user`**（HLD 13.2.1 亦写 `sys_user` / ruoyi `sys_user`）；其余保持 §4.1.1 名 | 不引号化保留字，避免全库引用与 Flyway/MyBatis-Plus 踩坑 |
| C-15 | 对象存储适配时机 | HLD §5.6 列"MinIO/S3/本地盘"三适配 | M1–M3 只交付**本地盘**实现 + `FileStorage` SPI；S3/MinIO 适配**在出现部署需求时**按 tech-stack 流程引入 SDK | YAGNI：SPI 已留缝，未证实的需求不预置依赖 |
| C-16 | i18n 时机 | [`database.md`](agents/database.md)："错误消息/日志全中文，i18n 留 P1" | M1–M5 **不做**多语言机制（消息仍全中文）；i18n 模块属 P3（顺序 23）。契约留口：错误码是 `int`，P3 按 code 查资源包即可，不改 V1 | 提前引入 MessageSource 会让所有消息双写、测试翻倍 |
| C-17 | P0 册 6.2 冻结清单未落地的门面 | 6.2 写"P0 冻结 V1"，代码中不存在 | 视为**契约 V1 的实现补齐**（非契约变更），在 M1 完成，见 2.7 | 冻结的是签名与语义，不是"P0 必须写完" |
| C-18 | 装配例外 | P0 册 3.7 注 4：`ASSEMBLY_EXCEPTIONS = {app.config, app.web}` 为已知偏差 | P1 收紧为**"业务模块之间不得引用彼此装配包"**；`app.config/web` 仍是唯一例外（应用壳是唯一装配方） | 保持应用壳唯一装配，不放开模块自治装配 |
| C-19 | Controller 放五层中的哪一层 | HLD §2.2.2 五层（api/application/domain/infrastructure/events）未给入站适配器位置 | **Controller 放 `infrastructure/web`**；`api` 包只放跨模块契约（interface + DTO） | `api` 会被其他模块依赖，混入 `@RestController` 等于让契约带 Web 依赖（见 3.2） |
| C-20 | OAuth2 授权服务器时机 | HLD §5.2："Spring Security + OAuth2 Authorization Server（自托管）"（C-9 已裁"不作 IdP"） | **M1–M5 不做授权服务器**；对外发令牌（第三方客户端）属 P3 `integration`（顺序 21）。用 `spring-security-oauth2-jose` 只做签名/校验 | 对外 IdP 需要客户端注册/授权同意/scope 管理面，P1 无消费方（4.2） |
| C-21 | SSO 交付范围 | SRS FR-SEC-03：SSO(SAML2/OIDC)+LDAP | M2–M5 交付 **OIDC + LDAP** 适配，统一收敛到 `IdentityProvider` SPI，首次登录**默认拒绝**（需管理员预建账号，`auto-create-user` 默认 false）；**SAML2 留 P1 后半** | SAML2 需要证书/元数据管理面与真实 IdP 验证环境，M1–M5 无法验证 → 不假装交付（4.2） |
| C-22 | 数据权限 SQL 是否允许跨 Schema 子查询 | P0/HLD："禁跨 Schema SQL"（AGENTS database.md、HLD §4.2）；但数据权限需要组织子树 | **不跨 Schema**：`TenantCtx.orgIds` 在认证时由 iam 解析并缓存，过滤器只生成 `orgColumn IN (:orgIds)`；超 `max-org-ids`(2000) 报 `21037` | 为数据权限破例会让例外变常态；子树 id 集合在本项目量级可控，且与"禁跨 Schema"的冻结项一致（4.5） |

### 2.6 P0 遗留待细化事项的销账（P0 册 6.3，逐条）

| # | P0 遗留 | 本册处置 | 落点 |
|---|---|---|---|
| 1 | `TenantCtx` 实现与过滤器接入 | ✅ 本册定案 | 4.6.1、4.6.3 |
| 2 | `@AuditLog` 切面与 audit 接入 | 🔶 部分：本册定**事件契约**与 iam/platform 的发布点；切面本体随 audit（M4–M6） | 3.10、4.9、7.4 |
| 3 | platform 参数/字典/`FileApi`/Scheduler 表结构与契约 | ✅ 本册定案 | 3.2–3.7、5.4 |
| 4 | 数据权限 SQL 改写方案 | ✅ 本册定案 | 4.6.3 |
| 5 | 事件总线可靠性（重试/死信/幂等） | 🔶 本册定**本册模块所需的最小实现**（`@ApplicationModuleListener` + Spring Retry + 死信表），全局方案随 audit/workflow 复核 | 4.9.2 |
| 6 | 信创数据库适配层（方言抽象） | ⏭ 推迟到 P2（本册模块只用 PostgreSQL 标准 SQL + MyBatis-Plus；**不写厂商私有语法**，为将来留余地） | 5.1.3 |
| 7 | ArchUnit「模块登记完备」「业务错误码落段」断言 | ✅ 本册要求 M1 即补测（新增 iam 后作用面非空） | 6.1 |
| 8 | `eaio.idempotency.fail-open` 开关评估 | ⏭ 维持 P0 决策：**恒 fail-closed**，不加开关（无证据支持放行更优） | — |

### 2.7 P1 必须补齐的 common 资产（"冻结清单已写、代码未落地"）

P0 册 6.2 把下列门面写进「契约 V1 冻结清单」，但**代码里不存在**（P0 册 4.8 注 4 已承认 Hutool 底座未引入）。它们不是契约变更，而是 **P1 的实现补齐**，且是 platform/iam 的编译前置：

| 资产 | 包 | 用途 | 备注 |
|---|---|---|---|
| `TenantCtx` + `TenantCtxProvider` | `com.eaio.iam.api`（iam 侧） | 当前组织上下文（4.5） | 值对象 + 只读接口；`TenantContextHolder`（ThreadLocal）留在 iam `infrastructure`，**不放 common** |
| `BaseDO` | `com.eaio.common.persistence`（新增） | 审计字段基类（Java 字段 `id/createdBy/createdAt/updatedBy/updatedAt/version/deleted`，对应列 `id/created_by/created_at/updated_by/updated_at/version/deleted`，见批次总册 3.5） | **纯 POJO，不带任何持久层注解**——`commonIsPure` 禁止 common 依赖 `org.apache.ibatis..`/`jakarta.persistence..`；字段由 MyBatis-Plus 全局配置识别；`deleted` 为 `Boolean`（列 `BOOLEAN`） |
| `CryptUtils` | `com.eaio.common.crypto`（新增） | AES-GCM 加解密（NFR-SEC-02） | 只用 JDK `javax.crypto`；密钥来自环境变量，**不入库、不入 Git** |
| `RedisKit` / `RedisKeys` / `DistributedLock` / `RateLimiter` | `com.eaio.common.redis`（新增） | Redis 门面（Redisson 底座） | 键规范沿用 P0 幂等键前缀 `eaio:{env}:`；`RedisKeys` 是键的唯一构造点 |
| `ExcelKit` / `ImportResult` / `ExcelError` / `ExcelReadOptions` / `ExcelWriteOptions` | `com.eaio.common.excel`（新增） | Fesod 流式读写门面 | 字段映射复用 Fesod `@ExcelProperty`（P0 册 4.3） |
| `TreeUtils` / `BeanUtils` / `CollectionUtils` | `com.eaio.common.util`（补齐） | 树构建（组织树）、低频拷贝、集合工具 | `TreeUtils` 供 iam 组织树与 platform 字典树 |
| `@EnumValid` / `@Mobile` / `@IdCard` / `@Money` | `com.eaio.common.validation`（新增） | Controller 入参校验 | 校验注解**不得**依赖 Jakarta Validation 之外的库 |
| `logback-spring.xml`（JSON 日志） | `e-aio-app`（补齐） | NFR-MAINT-03 结构化日志（ts/level/traceId/module/msg） | P0 册 3.4 声明未落地 |

> **完成判据（M1 第一周）**：以上每个类都有对应单测（`mvn -B test`），且 `ArchitectureTest` 十条断言全绿（含 `commonIsPure`——新加的 `RedisKit`/`BaseDO` 不得把 `org.springframework.data..`/`org.apache.ibatis..` 带进 common）。**先补齐、再写模块**：这是唯一能避免"边写业务边补底座"的顺序。

---

## 3 platform（基础平台能力，开发队列顺序 3，M1–M3）

### 3.1 职责与边界

**做**：参数配置中心、数据字典、统一文件（`FileApi`）、定时任务（`SchedulerApi`）、Excel 导入导出（`ExcelApi`）、缓存管理（`CacheApi`）、系统监测（`MonitorApi`）、消息模板。

**不做**（越界即评审否决）：
- 不做人员/组织/权限/认证（iam 的职责）；platform 里**不得出现** `role`/`user`/`org_node` 相关表或类。
- 不写审计数据（audit 的职责）：platform 只**发布事件**（`ParamChangedEvent`/`FileUploadedEvent`…），审计由 audit 消费落 WORM。
- 不替业务模块管存储：业务模块要存文件一律走 `FileApi`（HLD 5.6）。
- 不做业务任务：库存预警/逾期/对账等（HLD §6.3）由业务模块在 P2/P3 注册 handler，platform 只提供注册点（3.7）。
- 不实现告警推送通道（Alertmanager/Grafana 属部署组件，NFR-MAINT-04）。

**依赖方向**：`platform → common`，**不依赖任何模块**（HLD §11.1 依赖矩阵）。因此 iam 数据权限与 audit 留痕**不能反向依赖**（裁决 C-6/C-7）：鉴权用 Spring Security 标准注解（权限码为字符串），留痕用事件。

### 3.2 分包结构（五层 + 入站适配）

```
com.eaio.platform
├─ package-info.java        @ApplicationModule（放模块侧，新增模块自动纳入）
├─ api/                     @NamedInterface("api")：只有 interface + record DTO + 枚举
├─ application/             *AppService：用例编排、@Transactional 边界、发布事件
├─ domain/                  实体/值对象/领域服务/仓储接口（不依赖 Spring Web、不依赖 MyBatis）
├─ infrastructure/          persistence/(MyBatis-Plus Mapper + 仓储实现)、storage/(FileStorage)、cache/
│   └─ web/                 @RestController（入站适配器，见裁决 C-19）
└─ events/                  本模块对外事件（record）
```

**裁决 C-19（本册新增）**：五层分包（HLD 2.2.2）没有给 Controller 指定位置 → **Controller 放 `infrastructure/web`**。理由：Controller 是入站适配器（与出站 Mapper 同属 infrastructure）；`api` 包会被其他模块依赖，混入 `@RestController` 会让契约带上 Web 依赖（与 `commonIsPure` 的同类洁癖）。**`api` 包只放跨模块契约**。

**新增模块的 11 个登记点**（P0 册 3.1.3 + 实测）：父 POM `<modules>` → 模块根包 `package-info` → `api/package-info` → 五层目录 → `db/migration/platform/` → Schema `eaio_platform` → `application.yml` 的 `eaio.flyway.modules` → 错误码枚举（20000 段）→ `ArchitectureTest` 的 `MODULE_NAMES` 与白名单 → 不写 `@ComponentScan` → P0 册附录 6.1 登记表。iam 用同一份清单（4.1 只列差异）。

**DTO 与映射**：`api` 里的 DTO 一律 `record`；入参 `XxxCmd`/`XxxQuery`，出参 `XxxVO`；MapStruct `@Mapper(componentModel = "spring")` 在 `application` 层完成 领域 ↔ DTO 映射（`unmappedTargetPolicy = ERROR`，漏字段编译失败）。**禁止把实体或 Mapper 暴露到 api 包**。

### 3.3 对外契约（`com.eaio.platform.api`）

```java
public interface ParamApi {                        // 参数中心
    String getString(String key, String defaultValue);
    Optional<ParamItem> find(String key);
    void evict(String key);                        // 变更后本地缓存精确失效
}
public interface DictApi {                          // 数据字典
    List<DictItem> items(String typeCode);
    String label(String typeCode, String value);
    void evict(String typeCode);
}
public interface FileApi {                          // 统一文件
    FileMeta upload(FileUploadCmd cmd);            // multipart，见 3.6
    FileMeta meta(long fileId);
    InputStream open(long fileId);                  // 下载流（调用方负责关闭）
}
public interface SchedulerApi {                     // 定时任务
    void register(JobDescriptor descriptor);        // 代码注册（C-12）
    void enable(String jobCode);
    void disable(String jobCode);
    JobStatus status(String jobCode);
}
public interface ExcelApi {                         // 导入导出
    String submitImport(ImportCmd cmd);             // 返回 taskId
    String submitExport(ExportCmd cmd);
    TaskProgress progress(String taskId);
}
public interface CacheApi {                         // 缓存
    void evict(String namespace, String key);
    void evictNamespace(String namespace);
}
public interface MonitorApi {                       // 监测（只读）
    SystemSnapshot snapshot();
}
public interface NoticeApi {                        // 公告与站内消息（FR-PLT-08，见 3.9）
    long publish(NoticePublishCmd cmd);            // 立即发布或按 publishTime 定时发布
    List<NoticeDTO> getUnread();                   // 当前用户未读（依赖 TenantCtx，缺失时只返回 ALL 范围）
    void markRead(long noticeId);                  // 幂等：重复标记不报错
    PageResult<NoticeDTO> getPage(NoticeQuery query);
}
public interface NotifyTemplateApi {                // 通知模板（FR-PLT-08，见 3.9）
    RenderedTemplate render(String templateCode, Map<String, String> params);  // 缺变量 20025；未声明占位符报同码
    PageResult<NotifyTemplateDTO> getPage(NotifyTemplateQuery query);
    NotifyTemplateDTO save(NotifyTemplateSaveCmd cmd);                          // 模板不存在 20024
}
```

**3.9 与本节的自洽口径**：公告落 `notice` 表（收件范围与已读见 3.9），模板落 `notify_template` 表；`render` 只做 `${var}` 字符串替换（**不执行表达式**，不引 Hutool——P0 册 4.8 注记 4 未引入），渲染结果不缓存；渠道 P1 只落地站内（`SITE`），`EMAIL`/`SMS` 可保存与渲染但**不提供发送接口**（不假装发送成功）；公告内容一律文本渲染，前端禁止 `v-html`。模板不存在报 `20024 MESSAGE_TEMPLATE_NOT_FOUND`，变量缺失报 `20025 MESSAGE_VARIABLE_MISSING`（3.12 表）。

**契约规则**：
1. `api` 只放 `interface` + `record` + 枚举；实现类在 `application`/`infrastructure`。
2. **向后兼容只增不改**：新增方法给 `default` 实现，或走新接口；改签名 = 破 V1（需评审）。
3. 跨模块**禁止**引用 `domain`/`infrastructure`/`internal`（ArchUnit + Modulith 双重拦截）。
4. **鉴权不依赖 iam 类型**：Controller 用 Spring Security 注解 + 权限码字符串，例如 `@PreAuthorize("hasAuthority('platform:config:add')")`；权限码由 iam 在运行时装配（iam 的 `permission` 表存储同一字符串）。这是 platform 不依赖 iam 却能鉴权的唯一方式。
5. 对外 HTTP 路径：`/api/platform/<resource>/<Action>`（`/api` 由 context-path 承载），全 POST + JSON，动作词 `Get/GetPage/Add/Up/Del/Run/Export/Import`；上传/下载是唯一二进制例外（HLD 2.6.4）。

### 3.4 参数配置中心（FR-PLT-01）

**表**：`param`（5.4.1）。**解析优先级**（HLD §9.3）：代码默认值 < `application-{env}.yml` < **参数中心 DB 值**（DB 最高，可热更新）。

**取值路径**：`ParamApi.getString(key, def)` → Caffeine 本地缓存（TTL 60s）→ 未命中查 DB → 仍无则回退 `def`。**写路径**：Controller 改 DB + 发 `ParamChangedEvent` + `CacheApi.evict`。

**热更新机制（本册决策）**：变更事件只做**本实例**精确失效；多实例靠 **60s TTL 兜底**。**不做 Redis pub/sub 广播**——多一条通道就多一份故障面与测试面，60s 的最大不一致窗口对配置类数据可接受。若将来对某个参数要求秒级一致，再为该参数单独加广播（不提前建机制）。

**敏感参数**：`is_encrypted = true` 的值用 `CryptUtils`（AES-GCM）落库；查询接口**永不回显明文**（返回 `******`）；修改时传空表示不变更（避免误清空）。

**作用域**：`scope_type = SYSTEM` 且 `org_id = 0` 表示系统级，`scope_type = ORG` 时按 `org_id` 覆盖系统级（母子公司差异化配置，对应 FR-PLT-01 分级配置）；`ParamApi.getString` 在无 `TenantCtx` 时只读系统级。

### 3.5 数据字典（FR-PLT-02）

**表**：`dict_type`、`dict_item`（5.4.1）。`DictApi.items(typeCode)` 走两级缓存（3.8）；`DictChangedEvent` 触发精确失效。

**规则**：字典值支持 `ext_json` 扩展（颜色/图标等前端渲染信息，**不要**为每种扩展加列）；字典值**不做物理删除**，只 `status` 停用（历史数据引用不能断）；`type_code`/`value` 全局唯一（DB 唯一约束，不靠应用层查重）；字典变更**不记审计明细**（audit 由通用操作审计覆盖，无需平台重复实现）。

### 3.6 统一文件（FR-PLT-03）

**表**：`file`（5.4.1）。**存储 SPI**：`FileStorage { String store(InputStream, StoredFileMeta); InputStream open(String relativePath); void delete(String relativePath); boolean exists(String relativePath); }`，M1–M3 只实现 `LocalFileStorage`（裁决 C-15），S3/MinIO 在有部署需求时再引入 SDK。

**上传校验（信任边界，逐条实现）**：
1. 大小上限 `eaio.file.max-size`（默认 50MB）——**先看 `Content-Length` 再流式拷贝计数**，不能只看声明值。
2. MIME 白名单 `eaio.file.allowed-content-types`（默认：图片/pdf/office/文本/zip 常用集）。
3. 扩展名黑名单（`jsp/js/html/sh/exe/dll/bat/jar` 等）+ **落盘名由服务端生成**（`UUID` + 白名单内的扩展名），**绝不使用客户端文件名拼路径**。
4. 路径校验：`root.relativize(resolve(relativePath).normalize())` 必须仍在 `root` 内（防 `../` 穿越）。
5. 上传后**服务端重算 sha256** 落 `file.sha256`（不信任客户端）；**不做秒传/物理去重**——去重会把删除变成引用计数问题，P1 无此需求。
6. 下载：`Content-Disposition: attachment; filename*=UTF-8''<percent-encoded>`，文件名过滤 CR/LF（响应头注入）；`Content-Type` 回 `file.content_type`，并加 `X-Content-Type-Options: nosniff`。

**权限与带外下载**：`FileApi` 的**跨模块调用**不校验权限（调用方已鉴权，遵循"调用方鉴权"裁决 C-6）。**HTTP 下载** `/platform/file/Download` 必须鉴权：`uploader_id` 本人 / 本组织（数据权限，由 iam 提供的切面按 `uploader_org_id` 过滤）/ 管理员。带外预签名：`/platform/file/Presign` 返回 `/api/platform/file/Download?fileId=..&exp=..&sig=..`，`sig = HMAC-SHA256(secret, fileId + exp + uploaderId)`；**token 只解决"带 cookie/header 不便"的浏览器直下，不绕过数据权限**——校验 sig 后仍按 `uploader_id/uploader_org_id` 做同一套可见性判定。

**留痕**：`FileUploadedEvent` / `FileDeletedEvent`（含 fileId/bizType/bizId/操作者）→ audit。业务删除走**逻辑删除**（`deleted` 标志），物理清理由 `platform.file.orphan.clean` 定时任务负责（3.7），删除前记录事件。

### 3.7 定时任务（FR-PLT-04）

**表**：`job`、`job_run`（5.4.1）。**选型**：Spring Task + ShedLock（裁决 C-8），ShedLock 用 Redisson provider。

**注册（安全边界的核心，C-12）**：任务的**可执行点只能来自代码**——
```java
public interface JobHandler { String code(); void execute(JobContext ctx); }
@Service @JobHandler(code = "platform.file.orphan.clean")   // 启动扫描登记
class FileOrphanCleanHandler implements JobHandler { ... }
```
`job.handler_code` 必须命中 `JobHandlerRegistry`，否则保存/启用直接报 `20103`。**禁止按 DB 字符串反射调用任意 bean 方法**（RuoYi `invoke_target` 模式的 RCE 面，AGENTS 安全边界不允许）。

**调度**：启动时按 `job` 建 `CronTask` 注册到 `TaskScheduler`；`enable/disable/Up` 触发重建（取消后重注册）；`SchedulerApi.register` 供模块**在代码里**声明任务（是否启用仍由 DB 决定，代码不启停）。多实例互斥靠 `@SchedulerLock(name = jobCode, lockAtMostFor = timeout × 重试次数 + 缓冲)`。

**超时与重试**：`timeout_seconds` 到点取消（`Future.cancel(true)` + 中断），状态 `TIMEOUT`；失败按 `retry_times`/`retry_interval_seconds` **在同一把锁内**延迟重投（不 sleep 占线程）。

**日志**：每次执行写 `job_run`（含 `trace_id`，与日志可对齐）；保留 90 天，由 `platform.joblog.clean`（每日 03:00）清理。**告警**：同一任务连续失败 ≥ `eaio.scheduler.alert.fail-threshold`（默认 3）→ 发站内消息（3.9）+ 日志 ERROR；日志明细保留在任务日志接口。

**首批注册任务（M1–M3 必须落地）**：`platform.joblog.clean`、`platform.file.orphan.clean`、`platform.cache.warmup`（可选，默认关闭）。HLD §6.3 的业务任务一律**不在此列**（业务模块 P2 注册）。

### 3.8 缓存管理（FR-PLT-06）

**两级**：Caffeine 本地（`eaio.cache.local.enabled` 默认 true，TTL 60s，`maximumSize` 10000）+ Redis（`eaio.cache.redis.ttl` 默认 30 分钟）。**键规范**：`eaio:{env}:cache:{namespace}:{key}`，由 `com.eaio.common.redis.RedisKeys` 统一构造（禁止散落拼串）。
**穿透/雪崩防护（NFR，必须实现）**：空值占位（`NULL` + 60s 短 TTL）、互斥重建（Redisson 锁，等待 1s 超时后放手让请求回源）、TTL 随机抖动 ±10%。

**失效**：`CacheApi.evict(namespace, key)` / `evictNamespace`；跨实例一致性与 3.4 同策略（短 TTL 兜底，不建广播通道）。

**M1–M3 的实际缓存消费者**：字典、参数、消息模板、（可选）文件元数据。**iam 的权限缓存属 iam 自己**（不放进 platform 的 namespace）。

### 3.9 消息模板（FR-PLT-08）

**表**：`notify_template`、`notice`（5.4.1）。**本册决策（C-15 同类）**：M1–M3 只落地**站内**通道；`MessageSender` SPI 留口（`SITE` 有实现，`EMAIL`/`SMS` 无实现时**记 WARN + 降级为站内 + 日志**，**不假装发送成功**）。

**渲染**：`${var}` 占位符，用 Hutool `StrUtil.format`（common 底座已含）；模板变量必须在 `variables_json` 声明，渲染时缺变量报 `20110`（而不是渲染出 `${}` 给用户看）。**只做字符串替换，不执行表达式**。

**前端约定**：站内消息内容**一律文本渲染，禁止 `v-html`**（模板内容含用户数据，XSS 面）。这条写进 frontend-conventions，M1–M3 的前端只做消息列表/已读。

**消费方**：portal（顺序 9）的待办/消息聚合读 `notice`；本册只保证表与 API，不做推送（WebSocket 属 portal/P3）。

### 3.10 Excel 导入导出（FR-PLT-05）

**表**：`excel_task`（**一张表覆盖导入与导出**，见 5.4.1；不为两种方向各建一套代码）。

**契约**：`ExcelApi.submitImport/submitExport` 只**受理**（校验参数 → 落 `excel_task` → 投递到 `eaio-excel-*` 线程池 → 立即返回 `taskId`）；`progress(taskId)` 先读内存进度（`ConcurrentHashMap`），未命中回查 DB（**跨实例可查，这也是把进度按 500 行落库的原因**）。**长任务用 taskId + 轮询交付，不占幂等键**（P0 决策 3.2.5 同向）。

**导入**：Fesod 流式读（`ExcelKit`）→ 逐行校验 → `ExcelError(rowNum, column, value, message)` 收集（**上限 1000 条**，超出只计数，避免 OOM）→ 写错误文件（复用 `FileApi`，`bizType = "excel.import.error"`）。
**事务口径（本册决策）**：行数 ≤ `eaio.excel.atomic-max-rows`（默认 20000）时**整批一个事务**（要么全成、要么全不成）；超过阈值降级为**每 1000 行一批提交**，任务结果明确记 `PARTIAL` + 成功行数 + 错误文件——**不允许出现"报了失败但其实提交了一半又不说"**。
**并发幂等**：同一 `bizType + 提交人 + 文件 sha256` 在运行中重复提交 → 直接返回既有 taskId（`20112` 提示语义改为"已存在相同导入任务"由前端展示，不新建任务）。

**导出**：分页游标流式写；上限 `eaio.excel.max-export-rows`（默认 100 万），超限报 `20105` 并要求缩小范围（**不静默截断**）。导出**必须留痕**：`excel_task` 记操作者/行数/条件摘要 + 发 `ExcelExportedEvent` → audit（NFR-SEC-05 的"导出留痕"在 P1 落地；**二进制水印（PDF/Excel 内嵌）留 P2**，本册只做留痕与前端提示，不宣称已做水印）。模板文件放 `classpath:excel/templates/*.xlsx`，支持"按模板导出"。

### 3.11 系统监测（FR-PLT-07）

- **指标**：Actuator + `prometheus` 端点（`management.endpoints.web.exposure.include: health,info,prometheus`；`health.show-details: when-authorized`），含 JVM、HTTP、DataSource(HikariCP)、Redis、`eaio.job.*`（自建 Micrometer 计数器：执行数/失败数/耗时）。
- **链路**：P0 的 `TraceIdFilter` 已提供 `traceId`；日志与之关联（NFR-MAINT-03）。**Micrometer Tracing/OTel 桥接留 P2**（没有 collector 时它只增加依赖与配置，不产生任何可观测收益）。
- **`MonitorApi.snapshot()`**：只读聚合（JVM 内存/CPU、线程、Hikari 连接、Redis 连通、任务最近失败数），仅管理员权限。
- **告警**：应用侧只做"任务连续失败 → 站内消息 + ERROR 日志"；阈值告警与通知由部署侧（Prometheus Alertmanager/Grafana）承担，**应用不实现推送通道**（NFR-MAINT-04）。

### 3.12 错误码（platform 段 20000–20999，本册分配）

| 码 | 枚举 | 消息 | 场景 |
|---|---|---|---|
| 20001 | `PARAM_NOT_FOUND` | 参数不存在 | `param` 查无 |
| 20002 | `PARAM_KEY_DUPLICATED` | 参数键已存在 | 新增重键 |
| 20003 | `PARAM_VALUE_INVALID` | 参数值不符合声明类型 | 类型校验失败 |
| 20004 | `PARAM_READONLY` | 系统内置参数不可修改 | `is_builtin=1` |
| 20005 | `DICT_TYPE_NOT_FOUND` | 字典类型不存在 | 引用未定义类型 |
| 20006 | `DICT_VALUE_DUPLICATED` | 字典值重复 | 同类型下 value 重复 |
| 20007 | `DICT_TYPE_IN_USE` | 字典类型已被引用，不能删除 | 有 `dict_item` |
| 20008 | `FILE_NOT_FOUND` | 文件不存在 | fileId 无效 |
| 20009 | `FILE_EMPTY` | 上传文件为空 | 0 字节 |
| 20010 | `FILE_TOO_LARGE` | 文件超过大小上限 | `eaio.file.max-size` |
| 20011 | `FILE_TYPE_NOT_ALLOWED` | 文件类型不在白名单 | MIME/扩展名校验 |
| 20012 | `FILE_STORAGE_ERROR` | 文件存储失败 | 磁盘/IO 异常 |
| 20013 | `FILE_ACCESS_DENIED` | 无权访问该文件 | 数据权限判定失败（**不是** 10403：携带文件上下文，便于定位） |
| 20014 | `SIGNATURE_INVALID` | 下载签名无效或已过期 | 预签名校验失败 |
| 20015 | `JOB_NOT_FOUND` | 任务不存在 | jobCode 无效 |
| 20016 | `JOB_HANDLER_NOT_REGISTERED` | 任务处理器未注册 | `handler_code` 不在注册表（C-12） |
| 20017 | `JOB_CRON_INVALID` | Cron 表达式非法 | 解析失败 |
| 20018 | `JOB_RUNNING` | 任务正在执行 | 禁止重复手动触发 |
| 20019 | `JOB_TIMEOUT` | 任务执行超时 | 超过 `timeout_seconds` |
| 20020 | `CACHE_UNAVAILABLE` | 缓存服务不可用 | Redis 不可达 |
| 20021 | `EXCEL_TEMPLATE_INVALID` | Excel 模板不匹配 | 表头/列不匹配 |
| 20022 | `EXCEL_ROW_LIMIT_EXCEEDED` | 超出单次导出/导入行数上限 | 见 3.10 |
| 20023 | `TASK_NOT_FOUND` | 异步任务不存在 | taskId 无效 |
| 20024 | `MESSAGE_TEMPLATE_NOT_FOUND` | 消息模板不存在 | templateCode 无效 |
| 20025 | `MESSAGE_VARIABLE_MISSING` | 消息模板变量缺失 | 渲染缺变量 |

> 空号不回收；新增码**只追加**。`common` 通用码（10000 段）不得被 platform 复用。

### 3.13 事件（`com.eaio.platform.events`）

`ParamChangedEvent(key, oldValue, newValue, operatorId, orgId)`、`DictChangedEvent(typeCode)`、`FileUploadedEvent(fileId, bizType, bizId, size, operatorId)`、`FileDeletedEvent(fileId, operatorId)`、`JobFailedEvent(jobCode, failCount, lastError, traceId)`、`ExcelExportedEvent(taskId, rows, operatorId)`。

**投递口径**（HLD 2.2.2 + 2.6）：发布用 `@ApplicationModuleListener`/`@TransactionalEventListener(AFTER_COMMIT)`；消费方（audit）失败**不得**影响已提交事务；失败进重试队列（Spring Retry，3 次指数退避）→ 仍失败进 `event_dead_letter`？——**本册决策**：死信表随 **audit** 模块落地（audit 是第一个真实消费者，M4–M6），platform 侧只保证"发布不抛异常 + 记录发布失败日志"。全局事件可靠性方案在 P1 册 2.6 已列为审计/工作流阶段复核，**本册不为 platform 预建死信表**。

### 3.14 关键流程（平台侧，写代码按此实现）

**上传**：Controller（`@PreAuthorize` 鉴权 → 幂等键）→ 校验（大小→MIME→扩展名）→ 流式落盘（服务端 UUID 命名）→ 重算 sha256 → `file` 落库（事务）→ `FileUploadedEvent`（AFTER_COMMIT）。
**补偿**：落盘成功但 DB 事务回滚 → **删除已落盘文件**（`try/catch` 补偿，失败仅记 ERROR + 留孤儿，由 3.7 的孤儿清理兜底）；DB 成功而事件订阅失败 → **不回滚**（AFTER_COMMIT 语义，仅记日志）。

**定时任务执行**：调度触发 → ShedLock 抢锁（抢不到直接 `SKIPPED`）→ `job_run(RUNNING, trace_id)` → `JobHandler.execute`（超时监控）→ 更新日志（`SUCCESS`/`FAILED`/`TIMEOUT`）→ 失败按重试策略重投 → 连续失败达阈值发告警。锁释放失败 → ERROR 日志，`lockAtMostFor` 到期自动释放兜底。

**异步导入**：`submitImport`（参数校验 + 同文件运行中去重）→ `excel_task(PENDING)` → 线程池 → 流式读 + 校验 + 分批/整批事务 → 每 500 行更新进度 → 错误文件 → `FINISHED`/`PARTIAL`/`FAILED`。

**参数读取/变更**：读 = 本地缓存 → DB → 默认值；变更 = DB 事务 → 本地缓存精确失效 → 事件（AFTER_COMMIT）。

### 3.15 配置项（框架级走 yml/环境变量，**不进参数中心**）

> **规则（本册决策）**：区分两类配置——**框架级/安全级配置**（启动期就要用、影响安全边界）**只走 yml + 环境变量**；**业务可调项**才进参数中心（DB 热更新）。把 `eaio.file.max-size` 这类放进 DB 等于允许在线放宽安全阈值且无部署记录。

| 键 | 默认 | 说明 |
|---|---|---|
| `eaio.param.cache-ttl-seconds` | 60 | 参数本地缓存 TTL（多实例一致性窗口） |
| `eaio.dict.cache-ttl-seconds` | 300 | 字典本地缓存 TTL |
| `eaio.file.max-size` | 50MB | 单文件上限 |
| `eaio.file.allowed-content-types` | 常用图片/PDF/Office/文本/zip | MIME 白名单 |
| `eaio.file.local.root` | `/data/eaio/files` | 本地盘根目录（须可写；启动时校验并 `mkdirs`） |
| `eaio.file.presign-ttl-seconds` | 600 | 下载签名有效期 |
| `eaio.scheduler.pool-size` | 4 | 任务线程池 |
| `eaio.scheduler.alert.fail-threshold` | 3 | 连续失败告警阈值 |
| `eaio.joblog.retain-days` | 90 | 任务日志保留 |
| `eaio.cache.local.enabled` | true | 本地一级缓存开关 |
| `eaio.cache.redis.ttl-seconds` | 1800 | 二级缓存 TTL |
| `eaio.excel.atomic-max-rows` | 20000 | 整批事务上限（超过则分批，3.10） |
| `eaio.excel.batch-rows` / `eaio.excel.max-export-rows` | 1000 / 1000000 | 分批大小 / 导出上限 |
| `eaio.excel.thread-pool-size` / `queue-capacity` | 2 / 20 | 导入导出线程池（满了直接报 20022 语义"系统繁忙"，不无限排队） |

### 3.16 platform 验收（M1–M3 出口条件）

1. `mvn -B verify` 全绿：单测（覆盖率：`application`+`domain` 行覆盖 ≥80%，C-11）、ArchUnit（P0 十条 + 本册新增两条）、许可证门。
2. 集成测试（Testcontainers：PostgreSQL 17 + Redis 7）逐条通过：参数热更新生效（改 DB → 60s 内或事件路径即时生效）、字典缓存失效、上传→下载往返 sha256 一致、越权下载 20013、路径穿越被拒、超限 20010、类型白名单 20011；任务注册/手动 Run/日志/失败重试/连续失败告警；导入 10 万行成功且**记录实测耗时**（NFR-PERF-04 证据）；导出超限报 20022；两级缓存空值占位生效。
3. 依赖矩阵断言：`platform` 不依赖任何模块（ArchUnit 白名单只含 `com.eaio.common.api..` 与三方包）。
4. `job` 里**不存在**未注册 `handler_code` 的行（C-12 的机械证据）。

---

## 4 iam（身份与访问管理，开发队列顺序 4，M2–M5）

### 4.1 模块结构与新增登记

**唯一新增 Maven 模块 `e-aio-iam`**（`com.eaio.iam`，Schema `eaio_iam`，错误码 **21000–21999**，Flyway 目录 `e-aio-iam/src/main/resources/db/migration/iam/`）。落 **P0 册 3.1.3 的 11 个登记点**，另外三处必须同步改（P0 的机制记住不它们）：

1. `backend/e-aio/pom.xml` `<modules>` 插入 `e-aio-iam`（顺序：common → platform → iam → app）。
2. `e-aio-app/src/main/resources/application.yml` 的 `eaio.flyway.modules` 追加 `- iam`（字符串列表，**不是** P0 册 3.5.2 示例里的对象）。
3. `ArchitectureTest`：`MODULE_NAMES` 追加 `"iam"`；`crossModuleRuleHasTeeth` 白名单追加 `com.eaio.iam.api..`。

**依赖**：`iam → platform.api + common`（不依赖 app，不依赖任何业务模块）。`e-aio-app` 增加 `e-aio-iam` 依赖与 Security 装配。

```
com.eaio.iam
├─ api/         AuthnApi, PermissionApi, RoleApi, DataScopeApi, SoDCheckApi, OrgApi,
│               UserApi, EmployeeApi, OrgLifecycleApi, TenantCtxProvider
│               + @FieldPermission, @DataScope 注解 + record DTO
├─ application/ 登录/授权/组织/用户 用例服务
├─ domain/      用户/组织/角色/权限/SoD 实体与领域服务
├─ infrastructure/persistence(Mapper) · security(JWT/过滤器/密码) · idp(OIDC/LDAP 适配) · cache
│   └─ web/     AuthController, OrgController, RoleController, UserController…
└─ events/      LoginEvent, LogoutEvent, UserRoleChangedEvent, OrgChangedEvent, PermissionChangedEvent
```

**关闭的未决项**：`TenantCtxProvider`/`DataScopeApi`/`OrgLifecycleApi` 虽未出现在 HLD §3.2 API 列表，但 §5.2 明确要求对应能力 → **全部保留**（裁决 C-5）。

### 4.2 认证（FR-SEC-01/02/03/04）

**技术栈**：Spring Security（过滤器链）+ Spring Security OAuth2 Resource Server 的 JWT 解码（`spring-security-oauth2-jose`，裁决见 2.3.3）+ Redis 会话/撤销。**不使用** OAuth2 Authorization Server（HLD 5.2 提"OAuth2 授权服务器（自托管）"→ **裁决 C-20**：M1–M5 只交付**自有登录 + JWT**；对外授权服务器（`spring-security-oauth2-authorization-server`）属 P3 开放平台（integration，顺序 21）的职责，提前引入会把令牌模型绑到第三方客户端语义上）。

**登录**（`POST /api/iam/auth/Login`）：用户名+密码 → 校验锁定状态 → bcrypt 校验 → （可选）图形验证码 → （可选）TOTP → 签发 access token（`30m`，可配）+ refresh token（`7d`，存 Redis 可撤销）→ `LoginEvent`。

| 项 | 默认 | 说明 |
|---|---|---|
| 失败锁定 | 连续 **5** 次锁 **15 分钟** | 计数键 `eaio:{env}:iam:login:fail:{username}`；成功登录清零 |
| 同 IP 限流 | 10 次/分钟 | `RateLimiter`（common.redis），超限 21002 |
| 密码策略 | ≥12 位，含大小写+数字+符号，不含用户名 | `eaio.iam.password.*`；**等保三级**（NFR-SEC-06） |
| 密码有效期 | 90 天 | 到期返回 21072 强制改密（`0` = 不限） |
| 历史密码 | 不可重复最近 5 次 | 只存哈希 |
| 枚举防护 | 账号不存在与密码错误**返回同一码** `21001` | 不泄漏账号是否存在 |
| 会话上限 | 每用户 5 个并发会话 | 超出踢最早（记 `LogoutEvent` 原因 `EVICTED`） |
| 验证码 | 登录失败 ≥3 次后必填；TTL 2 分钟；**一次性**（`getAndDelete`） | 图形码用 JDK `BufferedImage` 生成（无新依赖）；启动加 `java.awt.headless=true` |
| MFA | `eaio.iam.mfa.enabled` 默认 **false** | TOTP（RFC 6238）**用 JDK `HmacSHA1` 自实现**（约 40 行），**必须用 RFC 6238 附录 B 官方测试向量写单测**；恢复码 10 个一次性 |

**令牌**：HS256，密钥 `eaio.iam.jwt.secret`（环境变量，≥32 字节；**启动时校验长度，不合规直接启动失败**）；claims：`sub`(userId)/`orgId`/`orgPath`/`jti`/`exp`/`iat`/`tokenType`。**撤销**：登出/改密/角色变更把 `jti` 写入 Redis 黑名单至 `exp`。
**Redis 不可用时的口径（安全优先）**：`eaio.iam.token.revocation-check` 默认 `required` → 校验路径**拒绝认证**（fail-closed，返回 10401）；显式配置 `degraded` 时允许仅验签+过期放行，并打 WARN + Micrometer 计数（让"降级"可见、可审计）。**没有"静默降级"这一档。**

**SSO/LDAP（范围裁决 C-21）**：M2–M5 实现 **OIDC（`spring-boot-starter-oauth2-client`）与 LDAP（`spring-security-ldap`）适配**，统一收敛到 `IdentityProvider` SPI → 本地用户绑定（首次登录**按配置**自动建用户或拒绝，默认**拒绝并要求管理员预建**，避免陌生人自助进系统）；**SAML2 留 P1 后半**（需证书/元数据管理面与真实 IdP 验证环境，M1–M5 无法验证 → 不假装交付）。

### 4.3 授权（FR-SEC-06/07/08/09/10/11/12/13）

**模型**：`sys_user` / `role` / `permission` / `user_role` / `user_org` / `org_position` / `data_scope_rule` / `sod_rule` / `field_permission`（5.4.2）。

- **RBAC + 组织范围**：`user_role(user_id, role_id, org_id)` —— 角色在**指定组织范围内**生效（FR-SEC-06"角色可限组织范围"）。
- **角色继承**：`role.parent_id` **单亲继承** + `role_permission.effect(ALLOW|DENY)`；**deny 优先**（安全默认），**不做多继承**（菱形冲突无解且无需求）。子角色继承父角色权限，显式 DENY 覆盖继承来的 ALLOW（FR-SEC-11）。
- **ABAC**：`permission.condition_json`（属性条件，如"金额 ≤ 1 万"），M2–M5 只实现**结构化条件的求值框架 + 少数内置算子**（`eq/lt/gt/in` 与 `deptId/orgId/ownerId` 变量）；复杂表达式留 P2。
- **数据权限（六值，裁决 C-3）**：`role.data_scope ∈ {SELF, DEPT, ORG, ORG_AND_SUB, ALL, CUSTOM}`，`CUSTOM` 由 `data_scope_rule` 明细（组织/部门/责任人）。
- **字段权限**：`field_permission(role_id, resource, field, access ∈ {VISIBLE, MASK, HIDDEN})`；业务 DTO 字段标 `@FieldPermission(resource=..., field=...)`（注解在 `iam.api`）；序列化时由 **iam 提供的 Jackson 序列化修改器**（在 app 装配注册）按当前用户规则裁剪/脱敏（明文脱敏复用 common 的 `SensitiveUtils`）。
- **SoD（FR-SEC-12）**：`sod_rule` 定义互斥权限对（如"合同创建"vs"合同审核"）；`SoDCheckApi.check(userId, permissionCodes)`（结果里返回 21040）。**两个校验点**：(1) 分配角色时 —— 违规**拒绝分配**（21040）；(2) 高危操作执行时（`@SoDCheck` 注解）。
- **权限码**：`<module>:<resource>:<action>`（如 `platform:config:add`、`iam:user:reset`），存 `permission.perm_code`，**唯一约束**；platform/iam/业务模块的 Controller 都直接写字符串（3.3 规则 4）。
- **授权缓存**：用户权限集合（Caffeine 60s + Redis 5min）；`UserRoleChangedEvent`/`PermissionChangedEvent` 触发失效。
- **权限变更留痕（FR-SEC-13）**：角色/权限/用户角色的增删改**全部发事件**（含变更前后值）→ audit WORM；iam 不留自己的审计表。

### 4.4 组织与人员（FR-SEC-05/10 + FR-HR-06/07/08）

**无限级组织树**：`org_node`（`parent_id`、`node_type ∈ GROUP|COMPANY|DEPT|TEAM`、`code`（唯一）、`level`、`sort_no`、`status`）+ **闭包表** `org_node_path(ancestor_id, descendant_id, depth)`（唯一键 `(ancestor_id, descendant_id)`，反查索引用 `(descendant_id, depth)`）。

- **闭包表维护在应用层、同一事务内**（不用 DB 触发器：可测试、可迁移、可回滚）：新增节点 = 自身 depth 0 + 父节点的全部祖先 depth+1；移动子树 = 删除"子树 × 子树"内部路径后重建。
- **移动守卫**：目标节点不得是自己的后代（查 `org_node_path`，违规报 `21011`）；子树节点数 > `eaio.iam.org-move-max-nodes`（默认 5000）时**明确报错**要求分批（M2–M5 不做异步移动，不静默半移动）。
- **生命周期四态（裁决 C-2）**：`筹备(PREPARING) → 运营(RUNNING) → 注销(CANCELLED) → 归档(ARCHIVED)`（**注销 ≠ 归档**：注销触发权限清理与踢下线，归档只读冻结）；非法跃迁报 `21013`；注销前置校验：**无在职人员 + 无未完成审批**（审批校验器留 SPI，workflow 落地后接；未接时按"无在职人员"判定并在返回值里注明校验范围）；`ARCHIVED` 只读（写操作报 `21013`）。
- **人员**：`sys_user`（见 5.4.2）+ `org_position`（岗位定义：`org_id`/`name`/`code`）+ `user_org`（**多组织兼任**：`user_id`/`org_id`/`position_id`/`is_primary`/`status`/`joined_at`/`left_at`）—— FR-HR-08 的"多组织兼任"与"入转调离"由 `user_org` 承载，**不往 `sys_user` 堆组织字段**（只有 `primary_org_id` 便于列表展示）。
- **通讯录**（FR-HR-07）：`EmployeeApi` 支持"按组织子树 + 关键词"查询；手机/邮箱按 4.3 字段权限裁剪。
- `OrgChangedEvent`（组织或任职变动）→ audit + 数据权限缓存失效。

### 4.5 TenantCtx 与数据权限落地（FR-SEC-15 + HLD §12 待细化第 3 项）

**`TenantCtx`（`com.eaio.iam.api`）**：
```java
record TenantCtx(long userId, String username, long orgId, String orgPath,
                 List<Long> orgIds,      // 已解析的数据权限组织范围（见下）
                 boolean crossOrg,       // 集团级（ALL）
                 Set<String> perms) {}
```
**填充与清理**：`AuthContextFilter`（iam 提供、app 装配，位于 `TraceIdFilter`/`IdempotencyFilter` 之后）解析 JWT → 经 `TenantCtxProvider.runAs(...)` 写入 iam 内部上下文 → `TenantContextHolder.set(ctx)` → `finally clear()`。**禁止跨线程泄漏**：线程池任务必须显式传递——`TenantContextHolder.runWith(ctx, supplier)`，异步任务/定时任务在提交时**快照**上下文。这是硬规则（ThreadLocal + 线程池 = 串号事故）。
**系统上下文**：定时任务/初始化用 `TenantContextHolder.system()`（`userId=0`、`crossOrg=true`、`perms=∅`）；系统上下文下**数据权限不生效**（否则任务查不到数据），因此它**只允许出现在任务/初始化代码**，不得出现在 HTTP 处理路径（评审必查项 + 代码注释强制标注）。

**数据权限 SQL 改写（唯一落点 + 关键决策 C-22）**：MyBatis-Plus `DataPermissionInterceptor` + `MultiDataPermissionHandler`（由 iam 实现、app 装配）；Mapper 方法用 `@DataScope(resource, orgColumn, userColumn)` **显式标注**（opt-in）。

> **裁决 C-22（本册新增，2026-09-21 按 [ADR-0004](../adr/0004-cross-schema-readonly-predicate.md)/[ADR-0005](../adr/0005-platform-zero-dependency-org-context.md) 定案）**：数据权限过滤的取舍与上限：
> - **注入点唯一**：谓词只由 iam 的 `DataPermissionInterceptor` 生成，业务模块只声明 `@DataScope(resource, orgColumn, userColumn)`；**platform 与业务模块不得手写组织谓词**。
> - **三档降级**：可见组织数 ≤ `inline-max`（默认 2000）→ 内联 `orgColumn = ANY(ARRAY[...])`；≤ `exists-max`（默认 50000）→ `EXISTS` 只读谓词（对 `eaio_iam.org_node_path`，ADR-0004 登记的跨 Schema 例外，应用角色**只授列级 SELECT**）；超限 → **停用该数据范围规则**（恒假 `1=0` + 明确错误码 + WARN 告警），**不退化为全量可见，也不硬拒绝业务请求**。
> - **platform 零反向依赖**：platform 不 import `com.eaio.iam.*`（连 `api` 都不依赖，ADR-0005），需要"当前组织/用户"时只经自有端口 `com.eaio.platform.api.port.OrgContextPort`（`currentUserId()`/`currentOrgId()`/`isSystemContext()`），实现由 iam 适配器提供、`e-aio-app` 装配；缺席时按系统上下文降级（只读系统级参数/字典、文件 `org_id` 落 `NULL` 并记 WARN），**fail-closed 不放行**。

SQL 片段口径：`SELF → userColumn = :userId`；`DEPT → orgColumn = 用户所属部门`；`ORG → orgColumn = :orgId`；`ORG_AND_SUB → orgColumn IN (:orgIds)`；`ALL → 无附加条件`；`CUSTOM → orgColumn IN (:ruleOrgIds) OR userColumn IN (:ruleUserIds)`。多个角色**取并集**（授权取并、DENY 不参与数据范围）。
**安全兜底（必须实现）**：带 `@DataScope` 的方法若取不到 `TenantCtx`（或未认证）→ **抛 10401 拒绝执行**，绝不"降级为不过滤"。

### 4.6 对外契约（`com.eaio.iam.api`）

```java
// 权威签名以 04-…-P1-4-iam.md 第 7 章为准；本册只镜像，改签名先改 iam 册
public interface AuthnApi {                        // 非 HTTP 场景（任务、消息）构造身份
    TokenDTO login(LoginCmd cmd);
    TokenDTO refresh(RefreshCmd cmd);
    void logout(String refreshToken);
    void revoke(long userId);                      // 全量踢下线（token_version++）
    MfaEnrollDTO mfaEnroll(long userId);
    void mfaVerify(long userId, String totpCode);   // 校验并激活（绑定确认）
    void changePassword(long userId, String oldPassword, String newPassword);
}
public interface TenantCtxProvider {                // 全模块使用
    Optional<TenantContext> current();
    TenantContext require();                        // 无上下文抛 21084
    Optional<Long> currentOrgId();
    void runAs(long orgId, Runnable action);
}
public interface UserApi {
    Optional<UserDTO> get(long userId);
    Optional<UserDTO> getByUsername(String username);
    PageResult<UserDTO> getPage(UserQuery query);
    long add(UserSaveCmd cmd);
    void up(UserSaveCmd cmd);
    void del(long userId);
    void disable(long userId, boolean disabled, String reason);
    String resetPassword(long userId);              // 返回一次性初始口令
    List<UserBriefDTO> batchGet(Collection<Long> userIds);
}
public interface EmployeeApi {
    Optional<EmployeeDTO> getByUserId(long userId);
    PageResult<EmployeeDTO> getPage(EmployeeQuery query);
    void up(EmployeeSaveCmd cmd);
}
public interface OrgApi {
    Optional<OrgNodeDTO> get(long nodeId);
    OrgTreeDTO tree(Long rootId, int depth);        // depth 必填（1..6）
    List<OrgNodeDTO> children(long nodeId);
    List<OrgNodeDTO> descendants(long nodeId);
    List<Long> subtreeIds(long nodeId);             // 闭包表查询
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
    void assignUsers(RoleAssignCmd cmd);            // SoD 校验点 1
    List<RoleDTO> inheritedRoles(long roleId);      // 继承链（含有效权限合并）
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
    List<DataScopeRuleDTO> rules(Long roleId, Long userId);   // 二者传其一
    long save(DataScopeRuleSaveCmd cmd);
    void del(long ruleId);
}
public interface SoDCheckApi {
    SoDCheckResultDTO check(long userId, Collection<String> permissionCodes);  // 违规在结果里返回 21040
    SoDCheckResultDTO checkAssign(RoleAssignCmd cmd);
    List<SoDRuleDTO> listRules();
}
```
**规则（与 iam 册同源）**：接口名与方法签名以 iam 单模块册第 7 章为唯一权威，本册仅镜像；以下同 3.3（接口+record、只增不改、`api` 不暴露实体/Mapper/JWT 类型、Controller 用权限码字符串）。`@FieldPermission` 与 `@DataScope` 注解也放在 `iam.api`（业务模块可依赖通用能力层的 api）。**JWT 类型与密钥只允许出现在 iam 的 `infrastructure/security`**。

### 4.7 错误码（iam 段 21000–21999）

> **本册不自持 iam 号表**：`eaio_iam` 段（21000–21999）的**唯一号源是 `04-企业级一体化管理系统-e-aio-详细设计说明书-P1-4-iam.md` 表 7-1**（已分配 `21001–21092`，其余留空，空号不回收）。本册旧版自持的 `21100–21146` 号表**作废**，正文引用已按下表改写；platform 段仍见 3.12（20000–20999）。

**本册正文引用的 iam 码 → iam 册表 7-1**

| 本册旧号（作废） | iam 册表 7-1 | 本册正文位置 |
|---|---|---|
| `21100 LOGIN_FAILED` | `21001 LOGIN_FAILED` | 4.3 认证（枚举防护：账号不存在与密码错误同码） |
| `21101 ACCOUNT_LOCKED` | `21002 ACCOUNT_LOCKED` | 4.3 登录策略（连续失败锁定、同 IP 限流） |
| `21102 ACCOUNT_DISABLED` | `21003 ACCOUNT_DISABLED` | 4.3 登录策略 |
| `21103 CAPTCHA_INVALID` | `21004 CAPTCHA_INVALID` | 4.3 验证码 |
| `21104 MFA_REQUIRED` | **不占码**（由 `TokenDTO.mfaRequired` 表达，正常流程不是错误） | 4.3 MFA |
| `21105 MFA_INVALID` | `21007 MFA_VERIFY_FAILED` | 4.3 MFA 校验 |
| `21106 PASSWORD_EXPIRED` | `21072 PASSWORD_EXPIRED` | 4.3 密码 90 天策略 |
| `21107 TOKEN_INVALID` / `21108 TOKEN_REVOKED` / `21109 REFRESH_TOKEN_INVALID` | `21005` / `21070`（缺令牌仍用通用 `10401`） / `21006` | 4.3 令牌 |
| `21110 SOD_CONFLICT` | `21040 SOD_CONFLICT` | 4.5 SoD（分配拒绝、执行拦截） |
| `21111 PERMISSION_DENIED` / `21112 DATA_SCOPE_DENIED` | `21031` / `21014` | 4.5 权限与数据权限 |
| `21120 DATA_SCOPE_TOO_WIDE` | `21037 DATA_SCOPE_TOO_WIDE` | 裁决 C-22（`orgIds` 超上限，结论不变） |
| `21121 ORG_TREE_CYCLE` / `21122 ORG_MOVE_TOO_LARGE` / `21123 ORG_CODE_DUPLICATED` / `21124 ORG_NOT_FOUND` | `21011` / `21016` / `21015` / `21010` | 4.5 组织 |
| `21130 ORG_STATUS_TRANSITION_INVALID` / `21131 ORG_ARCHIVED_READONLY` | `21013 ORG_STATE_NOT_ALLOWED`（两者**合并**） | 4.5 生命周期四态与归档只读 |
| `21132 ORG_HAS_ACTIVE_MEMBERS` | `21018 ORG_HAS_ACTIVE_MEMBERS` | 4.5 注销前置 |
| `21140 USER_NOT_FOUND` / `21141 USERNAME_DUPLICATED` | `21063` / `21060` | 4.4 用户 |
| `21142 PASSWORD_POLICY_VIOLATION` / `21143 PASSWORD_REUSED` | `21008`（**合并**） | 4.3 密码策略与历史密码 |
| `21144 ROLE_IN_USE` | `21024 ROLE_IN_USE` | 4.5 角色 |
| `21145 IDP_BINDING_REQUIRED` / `21146 IDENTITY_PROVIDER_ERROR` | `21054` / `21056 IDP_UNAVAILABLE` | 4.6 外部身份源 |

> **与 iam 册的有意收敛（本册不另行主张独立码）**：`MFA_REQUIRED` 改为响应字段而非错误码；`ORG_ARCHIVED_READONLY` 并入 `ORG_STATE_NOT_ALLOWED`；`PASSWORD_REUSED` 并入 `PASSWORD_POLICY_VIOLATION`；`TOKEN_REVOKED` 用会话撤销码 `21070`。

### 4.8 关键流程

**登录**：`AuthController.Login`（幂等键可选）→ 限流（IP）→ 锁定检查（Redis）→ bcrypt 校验 → 验证码（失败 ≥3）→ MFA（开启时）→ 签发 access/refresh → 解析权限与数据范围并缓存 → 写 `LoginEvent`（含成败、IP、UA、traceId）。
**请求鉴权**：`AuthContextFilter` 解 JWT（验签→过期→撤销名单）→ `TenantCtxProvider` 解析权限（缓存）→ Spring Security `@PreAuthorize`（权限码）→ `@DataScope` 拦截器改写 SQL → `@FieldPermission` 序列化裁剪 → 业务执行 → 通用操作审计（audit 模块消费/切面）。
**登出/撤销**：`Logout` → `jti` 入黑名单至 `exp` → `LogoutEvent`；改密/角色变更 → 该用户全部会话失效（按 `userId` 维度的令牌版本号 `tokenVersion` 校验，比逐个 `jti` 更省 Redis）。
**角色/权限变更**：写库 → 失效权限缓存（本实例 + Redis）→ `UserRoleChangedEvent`/`PermissionChangedEvent` → audit。

### 4.9 配置项与验收（M2–M5 出口条件）

| 键 | 默认 | 键 | 默认 |
|---|---|---|---|
| `eaio.iam.jwt.secret` | **无默认，必须显式配置（≥32 字节）** | `eaio.iam.jwt.access-ttl` | 30m |
| `eaio.iam.jwt.refresh-ttl` | 7d | `eaio.iam.token.revocation-check` | required |
| `eaio.iam.login.max-failures` | 5 | `eaio.iam.login.lock-minutes` | 15 |
| `eaio.iam.login.ip-rate-limit` | 10/min | `eaio.iam.password.expire-days` | 90 |
| `eaio.iam.password.history-size` | 5 | `eaio.iam.session.max-per-user` | 5 |
| `eaio.iam.mfa.enabled` | false | `eaio.iam.data-scope.max-org-ids` | 2000 |
| `eaio.iam.org-move-max-nodes` | 5000 | `eaio.iam.captcha.after-failures` | 3 |
| `eaio.iam.permission.cache-ttl` | 5m | `eaio.iam.idp.auto-create-user` | false |

**验收（P1 切片，与 §7 的批次验收对齐）**：
1. `mvn -B verify` 全绿（含 iam 覆盖率 ≥80%、ArchUnit 新增断言）。
2. **母子公司权限 E2E**（HLD 13.3 点名）：集团/子公司/部门三级组织 + 两套角色（集团管理员 = `ALL`、子公司管理员 = `ORG_AND_SUB`）→ 同一列表接口在两个身份下返回**不同数据集**（集成测试断言集合差异，不是只看 200）。
3. 失败锁定、验证码、MFA（RFC 6238 向量）、令牌撤销（登出后旧令牌 401）、`tokenVersion` 全体失效。
4. SoD 两个校验点各有用例（分配被拒 / 操作被拒）。
5. 字段权限：同一 DTO 在两个角色下手机号分别明文/掩码/不出现。
6. 数据权限兜底：`@DataScope` 方法在无上下文时抛 10401（**防"忘了上下文 = 全量"**）。
7. `orgIds` 超上限报 21037；组织成环移动报 21011；归档组织写入报 21013。

---

## 5. 数据结构设计（platform + iam，M1–M5 全量）

### 5.1 通用约定（对齐 [`database.md`](agents/database.md) + P0 册 3.5）

1. **Schema**：`eaio_platform` / `eaio_iam`，脚本内对象**显式 Schema 限定**；**不建跨 Schema 外键**；跨模块引用一律走 `api`（裁决 C-22：数据权限也不跨 Schema）。
2. **主键**：`id BIGINT`（雪花 `IdGenerator`，`IdType.INPUT`），**不用自增**（信创/分布式留口）。`workerId` 由 `eaio.id.worker-id` 配置，多实例必须区分。
3. **统一列（每张业务表都有，共 7 个）**：`id BIGINT`（雪花）、`created_at TIMESTAMPTZ`、`created_by BIGINT`、`updated_at TIMESTAMPTZ`、`updated_by BIGINT`、`version INT NOT NULL DEFAULT 0`（乐观锁 `@Version`）、`deleted BOOLEAN NOT NULL DEFAULT false`（逻辑删除 `@TableLogic`）。**列名口径以 P1 批次总册 3.5 为准**（`created_at`/`created_by`/`updated_at`/`updated_by`），旧写法 `created_at`/`created_by` 已废止。
4. **派生表例外**：`org_node_path` 是闭包表（派生数据），**不带审计列与 `deleted`**，重建而非删除。
5. **时间**：一律 `TIMESTAMPTZ`，**UTC 存储**，展示层按时区转换（`DateUtils`）。
6. **布尔**：业务布尔标志用 `SMALLINT`（0/1），**不用 PG `boolean`**（信创留口）；**唯一例外**是统一列的 `deleted`，用 `BOOLEAN NOT NULL DEFAULT false`（与批次总册 3.5 一致）。
7. **枚举**：`VARCHAR(16|32)` + 应用层枚举，**不用 PG enum 类型**（改值要 DDL，迁移成本高）。
8. **JSON 列**：一律 `TEXT` + 应用层 `JsonUtils` 校验；**不用 `JSONB`**（信创留口，5.1.3）。
9. **唯一约束**：用 **partial unique index（`WHERE deleted = 0`）**——普通唯一索引会让"逻辑删除后的同名记录"永远建不出来。
10. **索引**：只用普通 B-tree，建在"实际查询列"上；**不建 partial index 做查询优化**（本册数据量不需要）；分页查询必须显式 `ORDER BY`。
11. **注释**：每张表、每个列必须有 `COMMENT`（PG `COMMENT ON`）；**IT 用 `information_schema`/`pg_description` 断言"P1 新增表无空注释"**（约定要有牙齿，6.1）。
12. **SQL 安全**：参数一律 `#{}`，**禁止 `${}` 拼接**；动态排序/列名必须走白名单枚举（NFR-SEC-07）。
13. **信创留口（5.1.3）**：只写 PostgreSQL 标准 SQL；不用 `JSONB`/PG enum/`ON CONFLICT` 之外的 PG 私有语法？——例外：`upsert` 在种子脚本中使用（可重复迁移需要幂等），已在 5.5 登记；其余私有语法禁止。

### 5.2 持久层约定（MyBatis-Plus 3.5.17）

| 项 | 决定 |
|---|---|
| Mapper 位置 | `<module>/infrastructure/persistence/**Mapper.java`；**模块内不写 `@MapperScan`**，由 app 统一 `@MapperScan("com.eaio.**.infrastructure.persistence")` 装配（P0 规则：模块不写组件扫描） |
| 实体 | **DO 即实体**（承载表映射 + 领域行为），**不做 DO/Entity 双层**（本册无"换 ORM"需求，多一层只有维护成本）；跨模块与 HTTP 边界才用 DTO |
| `BaseDO` | 在 `common`（5.2/2.7），**纯 POJO 无持久层注解**（`commonIsPure` 限制）；snake_case ↔ camelCase 靠 `map-underscore-to-camel-case: true` |
| 逻辑删除 | `deleted`（1 删除 / 0 正常）全局配置；查询自动追加条件 |
| 乐观锁 | `version` 全局配置；冲突返回通用 `10003`（`DATA_CONFLICT`） |
| 分页 | MyBatis-Plus 分页插件；出参统一 `PageResult`（契约 V1） |
| 数据权限 | `DataPermissionInterceptor` + iam 的 `MultiDataPermissionHandler`，**只对 `@DataScope` 方法生效**（4.5 + C-22） |
| SQL 日志 | `local`/`it` profile 打印 SQL 与耗时；`prod` 只留慢 SQL（> 500ms）WARN |

### 5.3 表清单索引（本册新增，登记进 P0 册附录 6.1）

| 模块 | 表 | 归属 | 阶段 | 说明 |
|---|---|---|---|---|
| platform | `param` | 本册新增 | M1 | 参数中心（含分级） |
| platform | `dict_type` / `dict_item` | 本册新增 | M1 | 字典 |
| platform | `file` | 本册新增 | M1 | 统一文件（AES 不适用，密钥类不落库） |
| platform | `job` / `job_run` | 本册新增 | M2 | 定时任务与执行日志 |
| platform | `excel_task` | 本册新增 | M2 | 导入/导出异步任务（**一表覆盖两向**） |
| platform | `notify_template` / `notice` | 本册新增 | M3 | 消息模板与站内消息 |
| iam | `sys_user` | **C-14 改名**（HLD 的 `user` 是保留字） | M2 | 用户主档 |
| iam | **iam 全部 31 张表**（含 `user_password_history`、`mfa_recovery_code`、`position`、`user_org`、`employee`、`auth_session`、`auth_refresh_token`、`login_attempt`、`sso_config`、`ldap_config`、`abac_policy`、`access_grant`、`sod_rule_permission`、`sod_exemption`、`field_permission`、`permission_snapshot`、`org_lifecycle_log`、`event_outbox`、`event_consume_record` …） | 逐表见 `04-…-P1-4-iam.md` 第 4 章（**唯一权威**，本册不复制） | M2–M5 | 组织/人员/认证/授权/数据权限/SoD/外部身份/会话/发件箱 |
| iam | `org_node` / `org_node_path` | HLD §4.1.1 点名 | M2 | 无限级组织树 + 闭包表 |
| iam | `position` / `org_position` / `user_org` | 见 iam 册（岗位定义与挂载、多组织兼任 FR-HR-08） | M3 | 岗位与任职 |
| iam | `role` / `permission` / `role_permission` / `user_role` | 见 iam 册 | M2/M3 | RBAC |
| iam | `data_scope_rule` / `data_scope_resource` / `sod_rule` / `sod_rule_permission` / `field_permission` / `abac_policy` | 见 iam 册 | M3/M4 | 数据范围、SoD、字段权限、ABAC |

> **登录日志/审计日志不在 iam**：归 audit（顺序 5，M4–M6），iam 只发 `LoginEvent`/`AuditEvent`（C-7）。
> **菜单/前端路由**：RuoYi 的 `sys_menu` 在 HLD 中并入 `permission`（`perm_type = MENU`），**不另建菜单表**——菜单树 = `permission` 树，前端按权限点渲染（避免权限与菜单两套数据源）。

### 5.4 DDL 骨架（业务列；每表另加 5.1 的 7 个审计列）

#### 5.4.1 platform（`eaio_platform`）

| 表 | 业务列（类型 / 约束） | 索引与唯一 |
|---|---|---|
| `param` | `config_key VARCHAR(128) NOT NULL`、`config_value TEXT`、`value_type VARCHAR(16) NOT NULL DEFAULT 'STRING'`、`scope_type VARCHAR(16) NOT NULL DEFAULT 'SYSTEM'`、`org_id BIGINT NOT NULL DEFAULT 0`（0 = 系统级）、`config_group VARCHAR(64)`、`description VARCHAR(255)`、`is_builtin SMALLINT NOT NULL DEFAULT 0`、`is_encrypted SMALLINT NOT NULL DEFAULT 0`、`status SMALLINT NOT NULL DEFAULT 1` | UNIQUE `(config_key, org_id) WHERE deleted = false`；idx `(config_group)` |
| `dict_type` | `type_code VARCHAR(64) NOT NULL`、`type_name VARCHAR(64) NOT NULL`、`status`、`remark VARCHAR(255)` | UNIQUE `(type_code) WHERE deleted = false` |
| `dict_item` | `type_code VARCHAR(64) NOT NULL`、`dict_value VARCHAR(64) NOT NULL`、`dict_label VARCHAR(128) NOT NULL`、`sort_no INT NOT NULL DEFAULT 0`、`ext_json TEXT`、`status`、`remark` | UNIQUE `(type_code, dict_value) WHERE deleted = false` |
| `file` | `original_name VARCHAR(255)`、`stored_name VARCHAR(128) NOT NULL`、`storage_type VARCHAR(16) NOT NULL DEFAULT 'LOCAL'`、`relative_path VARCHAR(512) NOT NULL`、`content_type VARCHAR(128)`、`file_size BIGINT NOT NULL`、`sha256 CHAR(64) NOT NULL`、`biz_type VARCHAR(64)`、`biz_id VARCHAR(64)`、`uploader_id BIGINT NOT NULL`、`uploader_org_id BIGINT NOT NULL`、`status VARCHAR(16) NOT NULL DEFAULT 'NORMAL'` | idx `(sha256)`、`(biz_type, biz_id)`、`(uploader_id)`、`(status, created_at)` |
| `job` | `job_code VARCHAR(64) NOT NULL`、`job_name VARCHAR(128) NOT NULL`、`handler_code VARCHAR(128) NOT NULL`、`cron VARCHAR(64) NOT NULL`、`timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai'`、`params_json TEXT`、`timeout_seconds INT NOT NULL DEFAULT 300`、`misfire_policy VARCHAR(16) NOT NULL DEFAULT 'SKIP'`、`retry_times SMALLINT NOT NULL DEFAULT 0`、`retry_interval_seconds INT NOT NULL DEFAULT 60`、`status SMALLINT NOT NULL DEFAULT 1`、`last_fire_time TIMESTAMPTZ`、`next_fire_time TIMESTAMPTZ`、`remark VARCHAR(255)` | UNIQUE `(job_code) WHERE deleted = false`；idx `(status, next_fire_time)` |
| `job_run` | `job_code VARCHAR(64) NOT NULL`、`fire_time TIMESTAMPTZ NOT NULL`、`start_time TIMESTAMPTZ`、`end_time TIMESTAMPTZ`、`duration_ms BIGINT`、`status VARCHAR(16) NOT NULL`、`result_summary VARCHAR(512)`、`error_stack TEXT`、`trace_id VARCHAR(64)`、`trigger_type VARCHAR(16) NOT NULL DEFAULT 'AUTO'` | idx `(job_code, fire_time DESC)`、`(status, fire_time DESC)`；**日志表按 `fire_time` 定期清理，不参与逻辑删除** |
| `excel_task` | `task_id VARCHAR(64) NOT NULL`、`task_type VARCHAR(16) NOT NULL`、`biz_type VARCHAR(64) NOT NULL`、`file_id BIGINT`、`result_file_id BIGINT`、`status VARCHAR(16) NOT NULL`、`total_rows BIGINT`、`success_rows BIGINT`、`fail_rows BIGINT`、`progress INT NOT NULL DEFAULT 0`、`params_json TEXT`、`error_summary TEXT`、`operator_id BIGINT NOT NULL`、`started_at TIMESTAMPTZ`、`finished_at TIMESTAMPTZ`、`trace_id VARCHAR(64)` | UNIQUE `(task_id) WHERE deleted = false`；idx `(operator_id, created_at DESC)`、`(status)` |
| `notify_template` | `template_code VARCHAR(64) NOT NULL`、`channel VARCHAR(16) NOT NULL DEFAULT 'SITE'`、`title_template VARCHAR(255) NOT NULL`、`content_template TEXT NOT NULL`、`variables_json TEXT`、`status` | UNIQUE `(template_code) WHERE deleted = false` |
| `notice` | `receiver_id BIGINT NOT NULL`、`channel VARCHAR(16) NOT NULL DEFAULT 'SITE'`、`template_code VARCHAR(64)`、`title VARCHAR(255) NOT NULL`、`content TEXT NOT NULL`、`read_flag SMALLINT NOT NULL DEFAULT 0`、`read_time TIMESTAMPTZ`、`biz_type VARCHAR(64)`、`biz_id VARCHAR(64)`、`trace_id VARCHAR(64)` | idx `(receiver_id, read_flag, created_at DESC)` |

#### 5.4.2 iam（`eaio_iam`）

> **表清单以 iam 单模块册为唯一权威**：见 `04-…-P1-4-iam.md` 第 4 章（31 张表、逐表 DDL + `COMMENT ON`、69 个索引/约束、迁移 `V1__`–`V14__` + 种子）。本册不再复制 iam 逐表清单，避免两处漂移——**本册曾列出的 iam 表已被 iam 册取代**，两处差异一并作废：
>
> - 本册旧稿的 `user_mfa` → iam 册用 `sys_user.mfa_enabled` + `mfa_recovery_code`；`identity_provider` → iam 册用 `sys_user.source` + `external_id` 表达外部身份（SSO 配置在 `sso_config` / `ldap_config`）；
> - iam 册另含 `position`、`employee`、`employee_sensitive`、`abac_policy`、`access_grant`、`auth_session`、`auth_refresh_token`、`login_attempt`、`data_scope_resource`、`field_permission`、`sod_rule_permission`、`sod_exemption`、`org_lifecycle_log`、`permission_snapshot`、`event_outbox`、`event_consume_record` 等表。
>
> **模块边界不受影响**：org / user / role / permission / data-scope / SoD 的所有权与契约归属仍是 iam（本册 4.1–4.6 的定位与依赖结论不变）；本册只保留"跨模块读取时用到的最小事实"——`eaio_iam.org_node` / `org_node_path` / `sys_user` / `position` / `user_org` 的语义（名称与列定义以 iam 册为准）。

### 5.4.3 索引与约束命名清单（具体名字，替代匿名写法）

命名规则：索引 `idx_<table>_<cols>`、唯一约束 `uk_<table>_<cols>`、主键 `pk_<table>`、外键 `fk_<table>_<ref>`（批次总册 3.5）。5.4.1/5.4.2 的"索引与唯一"列描述**列组合**，具体名字如下（platform 侧）：

| 表 | 具体索引名 |
|---|---|
| `param` | `uk_param_config_key_org`（`config_key, org_id`，partial）、`idx_param_config_group` |
| `dict_type` | `uk_dict_type_type_code`（partial） |
| `dict_item` | `uk_dict_item_type_value`（`type_code, dict_value`，partial） |
| `file` | `idx_file_sha256`、`idx_file_biz`（`biz_type, biz_id`）、`idx_file_uploader`、`idx_file_status_created_at` |
| `job` | `uk_job_job_code`（partial）、`idx_job_status_next_fire_time` |
| `job_run` | `idx_job_run_code_fire`（`job_code, fire_time DESC`）、`idx_job_run_status_fire`（`status, fire_time DESC`） |
| `excel_task` | `uk_excel_task_task_id`（partial）、`idx_excel_task_operator_created_at`、`idx_excel_task_status` |
| `notify_template` | `uk_notify_template_code`（partial）、`idx_notify_template_channel` |
| `notice` | `idx_notice_receiver_read_created_at`（`receiver_id, read_flag, created_at DESC`） |

iam 侧同规则（示例）：`uk_user_username`、`idx_user_primary_org`、`uk_org_node_org_code`、`idx_org_node_parent`、`uk_role_role_code`、`uk_permission_perm_code`、`uk_user_role_triple`（`user_id, role_id, org_id`）；完整清单在 iam 分册，本册只保证命名规则一致。

**日志/关系表例外声明**：`job_run`（任务日志）与 `user_password_history` **不参与逻辑删除**（不带 `deleted`，按时间定期清理）；这是 5.1 第 3 条的显式例外。
### 5.5 初始化数据与首个管理员

- **种子数据用可重复迁移**（`R__seed_platform.sql` / `R__seed_iam.sql`），全部 `INSERT ... ON CONFLICT DO NOTHING`（幂等，后续新增权限点会自动补）。**这是 5.1 第 13 条允许的 PG 私有语法唯一例外。**
- 种子内容：内置参数（`is_builtin=1`，如 `sys.name`、`sys.file.max-upload-mb` 的展示项）、内置字典（性别/状态/组织类型…）、**权限点全集**（`platform:*`、`iam:*` 的 `perm_code`，与 3.12/4.7 的错误码同级维护）、内置角色模板（集团管理员 `ALL` / 子公司管理员 `ORG_AND_SUB` / 业务用户 `SELF` / 审计员只读）。
- **首个管理员不写进迁移脚本**（迁移里塞密码 = 全库同密码）：`AdminBootstrap` 在启动时检测"无任何用户"→ 读环境变量 `EAIO_BOOTSTRAP_ADMIN_PASSWORD`；未提供时 **`local`/`it` profile 用 `admin/Admin@12345` 并打 WARN**，**其他 profile 直接启动失败并给出明确指引**；无论哪种方式都置 `password_updated_at = null`（首次登录强制改密）。

---

## 6. 质量门、交付范围与文档同步

### 6.1 架构断言（P0 十条 + 本册新增，M1 即生效）

P0 册 6.3 与 3.7 注 5 留下一批"P0 无可断言对象、留到 P1 首个业务模块补测"的规则。**iam 落地后作用面非空，必须在 M1 一并补齐**（不是 M5 收尾时补）：

| 编号 | 断言 | 落点 | 说明 |
|---|---|---|---|
| A1 | 模块登记完备：`MODULE_NAMES` 含 `app/platform/iam/common`，且每个模块根包 `package-info` 带 `@ApplicationModule`、`api` 包带 `@NamedInterface` | `ArchitectureTest` | P0 册 6.3 第 7 条 |
| A2 | 业务错误码落段：扫描所有 `implements BusinessErrorCode` 的枚举，值必须落在其模块段（platform 20000–20999、iam 21000–21999）且不重复 | `ArchitectureTest` | P0 册 3.7 注 5 |
| A3 | `domain`/`infrastructure`/`internal` 不得被跨模块引用（iam 落地后才有断言对象） | `ArchitectureTest` | P0 册 3.7 注 5 |
| A4 | 跨模块依赖只允许 `com.eaio.<module>.api..`（P0 的 app 例外收紧为"业务模块之间不得互相引用装配包"） | `ArchitectureTest` | P0 册 3.7 注 4 + C-18 |
| A5 | `platform` 不依赖任何模块（依赖矩阵断言） | `ArchitectureTest` | HLD §11.1 |
| A6 | `iam` 只依赖 `common` + `platform.api` | `ArchitectureTest` | HLD §11.1 |
| A7 | `common` 纯净性**白名单式**（只允许 JDK/Hutool/SLF4J/Jackson/Redisson/Fesod/`spring-modulith-api`） | `ArchitectureTest` | P0 册 3.7 留下项 |
| A8 | **禁止跨 Schema SQL**：源码与 MyBatis XML 中不得出现 `eaio_` 字面量（迁移脚本除外） | `ArchitectureTest`（资源文件断言） | P0 册 3.7 表 + C-22 |
| A9 | **表注释完备**：P1 新增表/列在 `pg_description` 中无空注释 | 集成测试（Testcontainers） | 5.1 第 11 条 |
| A10 | `@DataScope` 方法在无 `TenantCtx` 时抛 10401（不降级为不过滤） | 单测 | 4.5 安全兜底 |

**覆盖率门（C-11）**：platform/iam 的 `application` + `domain` 行覆盖 ≥ 80%，`api`/DTO/装配类不计入；不达标 CI 红。

### 6.2 测试缝（沿用 P0 三缝，只加不改）

1. **缝一（后端）**：`mvn -B verify` —— 单测（`mvn test`）+ ArchUnit + 许可证门 + **集成测试**（failsafe `*IT`，Testcontainers：`pgvector/pgvector:pg17` + `redis:7-alpine`）。集成测试走真实 HTTP（`/api` 前缀，按 `application-it.yml`）。**3.16 / 4.9 的验收清单必须逐条有对应用例**（尤其"两个身份返回不同数据集"这类**集合断言**，不能只断言 HTTP 200）。
2. **缝二（前端）**：`npm run lint`（`--max-warnings 0`）+ `npm run test`（vitest，纯函数/请求封装）+ `npm run build`。
3. **缝三（环境）**：`docker compose up` + 启动健康检查 + Flyway 迁移到最新（M1 起 `eaio.flyway.modules` 含 platform+iam）。
4. **无容器降级**（P0 已有）：本地无 Docker 时 IT 跳过并打印原因；**CI 必须跑**（runner 自带 Docker）。

**CI 阶段扩展**：P0 六阶段 → M3 前加**阶段 7 镜像构建**（`Dockerfile` + 构建产物校验）；**阶段 8（发布/回滚）不在 M1–M5 内**（需部署目标环境，本册不假装交付）。checkstyle 规则集与门禁级别在 M1 冻结并写进 [`build-and-test.md`](agents/build-and-test.md)；启用 **OWASP Dependency-Check**（P1 要求）。

> **实现注记（T1 已落地）**：规则集在 `backend/e-aio/config/checkstyle/checkstyle.xml`（头部逐条写明纳入与**有意豁免**的理由），门禁级别 = 违规即失败，绑定 `validate` 阶段（`mvn -B compile|test|verify` 都先过 Lint），测试源码同样在范围内；启动类 `EaioApplication` 按文件豁免 `HideUtilityClassConstructor`（`@SpringBootApplication` 是配置类，加私有构造器会让应用起不来）。落地时修掉 6 处既有违规（2 处 `public` 构造器在包私有测试类里多余、1 处超长行、1 处 `equals` 常量未前置、1 处启动类豁免），并用 7 类故意违规验证过规则会判红。

> **实现注记（T2 已落地，issue #13）**：① **阶段 6 = 许可证 + CVE**：`org.owasp:dependency-check-maven` 锁 `12.1.9`（`13.0.0` 匿名同步有回归），**不绑定生命周期阶段**（NVD 同步太慢，绑进 `verify` 会拖垮本地与 IT 构建），由 `.github/workflows/ci.yml` 的 `license` 作业显式跑 `mvn -B org.owasp:dependency-check-maven:check`；`failBuildOnCVSS=7` + 空抑制文件 `backend/e-aio/config/dependency-check/suppressions.xml` = **高危未评审即阻断**。② **阶段 7 = 镜像构建**：根 `Dockerfile` 改多阶段（builder 用 Maven 打可执行 jar + BuildKit `~/.m2` cache mount，runtime 用 JRE 21、非 root、`HEALTHCHECK` → `/api/actuator/health`），实测可构建并以「空应用」形态（无库、无 Redis）启动且健康检查 `healthy`。③ **阶段 8 = tag 发布（本册声明"不交付"的部分按"仅产物可下载"落地）**：新建 `.github/workflows/release.yml`（`on.push.tags: ['v*']` → 门禁 `mvn -B verify` → 推 `ghcr.io/<repo>:<tag>` → GitHub Release 附 `e-aio-app-*.jar`），**不含部署与回滚**——部署目标环境仍不在 M1–M5 范围内，本册"不假装交付部署"的结论不变。三项门禁的启用状态与失败语义登记在 [`build-and-test.md`](agents/build-and-test.md)「三项工程门禁」。

### 6.3 前端交付范围（M1–M5 切片）

**复用 P0 前端**：`src/api/request.js`（全 POST 硬校验、`ApiError`）、`src/api/codes.js`、`src/api/idempotency.js`、`src/auth/session.js`、`LoginView`/`AppLayout`/`HomeView`。

**M1–M5 交付**：登录页接真实 `AuthnApi`（含验证码、TOTP 分支；`21001/21102/21072` 分别给出可理解的提示）、组织树管理、用户管理（列表/新增/停用/重置密码/分配角色）、角色与权限（权限树 + 数据范围）、参数/字典/定时任务/文件的列表与最小 CRUD 页、站内消息列表。

**规则**：所有写操作走 `src/api/` 封装并带 `X-Idempotency-Key`（`newIdempotencyKey()`）；路由与菜单由 `permission` 树（`perm_type=MENU`）驱动，前端守卫用权限码；**站内消息一律文本渲染，禁止 `v-html`**（3.9）。

**不做**（避免把 P1 前半拖成 P2）：工作流/审批界面、门户首页定制、移动端 H5、报表图表、拖拽移动组织节点（用"选择父节点"表单代替）。

### 6.4 文档同步（提交时同批完成，否则文档会变成谎言）

| 文档 | 动作 |
|---|---|
| [`tech-stack.md`](agents/tech-stack.md) | 关闭"未决项（P1 定）"：登记 2.3.3 全部依赖与版本；GraalVM 原生编译从"P1 验证"改为**推迟到 P3**（与 MyBatis-Plus/反射/动态表单冲突，M1–M5 无收益证据） |
| [`build-and-test.md`](agents/build-and-test.md) | checkstyle 门禁级别、OWASP 依赖扫描、覆盖率口径（C-11）、CI 阶段 7 |
| [`architecture.md`](agents/architecture.md) | `TenantCtx` 落地（不再是"P1 落地"）；`@AuditLog` 接入点标注 M4–M6 |
| [`database.md`](agents/database.md) | i18n 一条按 C-16 改口径；新增表登记指向本册 5.3 |
| P0 册附录 6.1 / 6.3 | 登记 `iam` 行；platform 状态改"进行中"；6.3 事项按 2.6 销账 |
| [`CONTEXT.md`](../CONTEXT.md) | 新增术语：`TenantCtx`、`JobHandler`、`数据范围`、`权限码`、`命名接口`补 `api` 包说明 |
| `docs/adr/0003-*.md`、`0004-*.md` | C-19（入站适配器放 `infrastructure/web`）与 C-22（数据权限只用 `TenantCtx`、不跨 Schema）**入 ADR**（难逆 + 需背景 + 真实取舍）；C-1–C-18 留在本册 |

### 6.5 提交与发布节奏

- 一个主题一次提交：`chore(deps): P1 依赖与 common 门面补齐`、`feat(platform): 参数/字典/文件`、`feat(iam): 认证与令牌`、`feat(iam): RBAC/数据权限/SoD`、`docs(p1): …`。
- 每次提交前跑缝一（后端 `mvn -B verify`）与受影响的前端缝二；**不跑通不提交**。
- 里程碑 tag：M3 末 `v0.2.0-p1-platform`，M5 末 `v0.3.0-p1-iam`。

---

## 7. 验收对照与引用

### 7.1 与 HLD 13.3（P1 批次验收）的对照

| HLD 13.3 项目 | 本册（M1–M5）覆盖情况 |
|---|---|
| 母子公司权限 E2E（组织树/数据权限/SoD/跨公司审批） | 组织树 + 数据权限 + SoD **本册交付并有用例**（4.9）；**跨公司审批**依赖 approval（顺序 6，M5–M8）→ 本册只保证 iam 侧就绪（组织范围、跨组织角色、`TenantCtx`） |
| 操作审计 WORM + 哈希链校验 | **不在本册**（audit 顺序 5，M4–M6）；本册保证**事件出口**（3.13/4.8 的事件清单是 audit 的输入契约） |
| 工作流 + 审批全链路 | **不在本册**（顺序 6）；本册保证 iam/platform 契约冻结（2.4） |
| MVP 种子企业试用 | 阶段 1（M2–M9）整体目标；本册提供 platform + iam 的可运行、可验收切片 |

### 7.2 上游条款 → 本册章节（可追溯）

| 上游 | 本册 |
|---|---|
| HLD §5.6 / SRS FR-PLT-01…08 | §3（含 3.12 错误码、3.15 配置） |
| HLD §5.2 / SRS FR-SEC-01…15、FR-HR-06/07/08 | §4（含 4.7 错误码、4.9 配置） |
| HLD §2.6.4 / 2.2.2 / 2.4 / 2.5 / 8.1 / 8.5 / 9.3 / 13.2.1 / 13.3 / 13.4 | 2.3、3.3、4.2、4.5、5.x、6.x |
| P0 册 3.1.3 / 3.5 / 3.7 / 4.x / 4.8 / 6.1 / 6.2 / 6.3 | 2.3.2、2.7、5.1、6.1、6.4 |
| SRS 非功能（PERF-02/03/04、SEC-02/05/06/07、REL-01/02、MAINT-02/03/04） | 3.6、3.10、3.11、3.15、4.2、4.7、6.1、6.2 |

### 7.3 附录：API 登记表（本册冻结，2.4）

| 模块 | 接口 | 归属裁决 |
|---|---|---|
| platform | `ParamApi`、`DictApi`、`FileApi`、`SchedulerApi`、`ExcelApi`、`CacheApi`、`MonitorApi` | C-4（`MonitorApi` 保留） |
| iam | `AuthnApi`、`PermissionApi`、`RoleApi`、`DataScopeApi`、`SoDCheckApi`、`TenantCtxProvider`、`OrgApi`、`UserApi`、`EmployeeApi`、`OrgLifecycleApi` | C-5（后三者保留） |
| common | `Result`/`PageResult`/`ErrorCode`/`BusinessErrorCode`/`IdempotencyStore`（P0）**不变**；P1 只在 common 补门面（`RedisKit`/`SecurityUtils`/加解密/`ExcelKit`），**不承载 `TenantCtx`** | P0 契约 V1 |

### 7.4 本册交付物（M1–M5 文件级）

**后端**：`e-aio-common` 补齐 2.7 全部类 + 单测；`e-aio-platform`（api/application/domain/infrastructure/events + `V1__baseline.sql` 之后的 `V2__…`、`R__seed_platform.sql`）；`e-aio-iam`（新增模块 + `V1__baseline.sql`、`V2__…`、`R__seed_iam.sql`）；`e-aio-app`（Security/JWT 装配、`AuthContextFilter`、数据权限拦截器装配、`@MapperScan`、`logback-spring.xml`、删除 `DemoController`）；`ArchitectureTest` 增补 A1–A8；集成测试用例（3.16/4.9 清单）；父 POM 依赖与许可证白名单（含 Mulan PSL v2）。
**前端**：6.3 范围内的页面与 `src/api/` 模块。
**文档**：本册；6.4 的全部同步项；ADR-0003/0004。

### 7.5 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| V0.1 | 2026-09-19 | 初稿：M1–M5（platform 顺序 3 + iam 顺序 4），含 22 条上游冲突裁决、common 补齐清单、24 张表 DDL 骨架、10 条架构断言、两模块验收清单 |
| V0.2 | 2026-09-20 | 批次一致性对齐：platform 表去 `sys_` 前缀（9 张）、统一列改 `created_at/created_by/updated_at/updated_by/version/deleted`（布尔）、新增 5.4.3 索引与约束命名清单、补 `NoticeApi`/`NotifyTemplateApi`、iam 号表改为指向 iam 单模块册表 7-1（旧号逐条映射）、iam 接口签名按 iam 册对齐、iam 表清单改为指向 iam 册第 4 章、`TenantCtx`/`TenantCtxProvider` 归 iam（**不放 common**） |

> **待办（进入编码后逐条消账）**：本册"5 项依赖版本 M1 核实后锁定"（2.3.3）、P0 册 6.3 事项销账（`checkstyle` 规则集冻结已于 T1 销账，见 `build-and-test.md` 与 6.2）；`docs/agents/*` 同步项按第 6 章清单逐条回写（`architecture.md`/`database.md`/`api-conventions.md` 已同步，`CONTEXT.md` 术语补充待办）。

