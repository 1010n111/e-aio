---
title: 企业级一体化管理系统详细设计说明书 · 第 P1 册（分册三）· audit（双审计引擎）
type: 详细设计说明书（DD）· 分册
phase: 详细设计（P1）
version: V1.0
status: 待评审
date: 2026-09-20
tags:
  - e-aio
  - DD
  - P1
  - audit
  - 双审计
  - WORM
  - 哈希链
aliases:
  - e-aio DD P1 audit
  - 详细设计说明书 P1 audit
  - 双审计引擎详细设计
related:
  - "[[04-企业级一体化管理系统-e-aio-详细设计说明书]]"
  - "[[04-企业级一体化管理系统-e-aio-详细设计说明书-P0-工程地基]]"
  - "[[04-企业级一体化管理系统-e-aio-详细设计说明书-P1-平台底座]]"
  - "[[03-企业级一体化管理系统-e-aio-概要设计说明书]]"
  - "[[02-企业级一体化管理系统-e-aio-软件需求规格说明书]]"
---

# 企业级一体化管理系统（e-aio）详细设计说明书 · 第 P1 册（分册三）· audit（双审计引擎）

> **项目名称**：企业级一体化管理系统（e-aio）
> **项目定位**：通用能力大一统 + 业务逻辑按企业定制的企业级开源系统
> **编制依据**：GB/T 8567-2006《计算机软件文档编制规范》、GB/T 9385-2008《计算机软件需求规格说明规范》
> **上游文档**：[[01-企业级一体化管理系统-e-aio-可行性研究报告|01-可行性研究报告]]、[[02-企业级一体化管理系统-e-aio-软件需求规格说明书|02-软件需求规格说明书（SRS）]]、[[03-企业级一体化管理系统-e-aio-概要设计说明书|03-概要设计说明书（HLD）]]、[[04-企业级一体化管理系统-e-aio-详细设计说明书-P0-工程地基|04-DD 第 P0 册 · 工程地基]]、[[04-企业级一体化管理系统-e-aio-详细设计说明书-P1-平台底座|04-DD P1 册 · 平台底座]]
> **版本**：V1.0（M4–M5 落地部分；M6 归档/告警收口段随下一段续编）
> **日期**：2026-09-20
> **分册归属**：本文档为详细设计说明书 **第 P1 册（分册三）· audit**，覆盖 HLD 13.2 开发队列 **顺序 5**（audit 双审计引擎，M4–M6）。

---

## 核心结论（执行摘要）

本册把 HLD 5.3「双审计引擎」与 SRS FR-AUD-01…12，落到**类级、表级、可验证**的粒度。审计模块的特殊性在于：它的正确性不是"功能对不对"，而是"**改没改得动、赖不赖得掉**"——因此本册的技术核心是 **WORM 防篡改 + 哈希链**，且每一层防线都必须给出**举证方式**（怎么证明它真的生效）。

| 设计主题 | 关键决策 | 落点 |
|---|---|---|
| 模块定位 | **双审计**：系统操作审计（`audit_events`）+ 财务专项审计（`audit_fin_trails`），另有登录认证与权限变更两张专项表 | 2.1、3.2 |
| 采集 | 注解 `@AuditLog` + `AuditAspect`（Service 层语义）+ Web 层拦截器（请求级兜底），**同一请求只留一条**（MDC 去重标记） | 3.1 |
| 写入链路 | 业务事务**提交后异步**落库（`@TransactionalEventListener(AFTER_COMMIT)` + 有界队列 + 批量 flush + 重试）；**高合规场景走 `sync=true`（默认 STRONG）** | 3.3 |
| 默认模式 | 登录/登出、权限变更、财务轨迹、导出、告警规则变更 → **STRONG**（同事务同步写）；普通查询类与低频普通写 → **ASYNC** | 3.3.2 |
| 敏感性 | 前后值快照经 `@Sensitive` 路径表**脱敏后入审计**（原文不入审计）；IP/UA 一并收敛；**明确承认"审计存不到原文"的合规代价** | 3.4 |
| WORM 三道防线 | ① 应用层仓储只暴露 `insert`/`select`（无 update/delete 代码路径）；② 数据库触发器 `BEFORE UPDATE OR DELETE` → `raise exception`；③ 应用角色 `REVOKE UPDATE, DELETE` | 3.5 |
| 哈希链 | `row_hash = SHA-256( canonical_json(业务字段有序) ‖ prev_hash )`；规范化规则精确到可复现；**禁止直接哈希 JSONB 文本输出** | 3.6、7.3 |
| 并发串行化 | 事务级 **PostgreSQL advisory lock**（`pg_advisory_xact_lock`）+ `nextval` + 唯一约束 `(chain_key, chain_seq)`；不用"锁链尾行"（会锁到用户行并放大热点） | 3.6.3 |
| 校验与举证 | 定时 `verifyChain` + 校验根 `audit_chain_roots`；不一致 → 告警 + **冻结该链后续写入**；对外 `verify` / `exportProof` | 3.7 |
| 归档 | `audit_events` 按月 `PARTITION BY RANGE (occurred_at)`；detach → 导出 → 校验和 → 对象存储 → 台账；**归档前固化区间 root，链不断** | 3.9 |
| 分级审计 | 查询强制经 `DataScopeApi` 过滤；超级审计员 `audit:query:all`；**审计查询本身不产生审计事件**（防自环），导出必留痕 | 3.8.4 |
| 本册不做什么 | 账实相符/四流合一/费用合规（SRS FR-AUD-08/09/10，P1 重要度但**P2 批次**）只留接入点，不在 M4–M5 实现 | 3.10、2.6 |

**本册的验收锚点（对齐 HLD 13.3 P1）**：**操作审计 WORM + 哈希链校验验证** —— 即"改一条审计记录会被数据库拒绝（可举证）"+"改一条审计记录（DBA 越过防线）会被校验发现（可举证）"两件事同时成立，且有可执行的测试与对外举证接口。

---

## 1. 引言

### 1.1 编写目的

本文档对 e-aio 的 **audit 模块（双审计引擎）** 进行详细设计，将其职责、边界、接口、数据结构与防篡改机制落实到**可编码、可测试、可验收**的粒度，作为 M4–M6 编码、测试与验收的直接依据。

本册同时也是 P0 册 6.3「待 P1 细化事项」中**第 2 条（`@AuditLog` 切面与 audit 接入）**与**第 5 条（事件总线可靠性）**的唯一落点：P0 的 `@AuditLog` 只有占位说明，本册给出注解定义、切面实现口径、失败语义与死信处置。

### 1.2 文档范围

| 开发队列（HLD 13.2） | 本文档覆盖 | 里程碑 | 本册章节 |
|---|---|---|---|
| 顺序 5 `audit`（双审计引擎） | ✅ 本次详设 | M4–M6 | 第 3–7 章 |
| M6 段（归档策略调优 / 告警通知渠道接 portal） | 🔶 设计已定，落地随 M6 | M6 | 3.9、3.11、6.7 |
| 顺序 6 及以后（workflow/approval/mdm/oa/portal） | ❌ 不在本册 | — | — |
| SRS FR-AUD-08/09/10（账实相符、四流合一、费用合规） | ❌ SRS 标 P1 重要度但**属 P2 批次**（HLD 13.2 顺序 12 finance 起） | P2 | 3.10.4 只留接入点 |
| P3 integration（审计对外 API/Webhook 举证） | ❌ | P3 | 7.6 登记承接 |

**范围口径的三条硬约束**：

1. **只覆盖 M4–M5 可落地的部分**：写入链路、WORM、哈希链、查询、分级审计、财务轨迹模型、告警规则引擎；归档的"历史分区搬运"与"告警通知到人"在 M6 收口（表与接口在 M4 一次建齐，避免二次搬迁）。
2. **不改任何既有代码契约**：`Result<T>` / `PageResult<T>` / `ErrorCode` / 幂等键 / 每模块独立 Schema 与 Flyway 实例（P0 册 3.2、3.6、ADR-0002）**一行不改**；本册只在 `22000–22999` 空段内新增业务错误码。
3. **不写前端代码文件、不改 `frontend/`**：3.12 只给页面清单、交互口径与权限点；前端实现随 portal 批次（顺序 9）统一挂载。

### 1.3 术语与约定

术语一律取 [`CONTEXT.md`](../CONTEXT.md)（模块 / 五层分包 / 命名接口 / 统一返回体 / HTTP 状态码恒 200 / 错误码分段 / 每模块独立 Schema / 命名接口 / 逻辑删除）。本册新增并沿用以下**本册定义**的词汇：

| 术语 | 本册定义 |
|---|---|
| 审计事件（audit event） | `eaio_audit.audit_events` 的一行；一次"被审计的操作"的不可变记录 |
| 双审计 | 系统操作审计（全模块全操作）+ 财务专项审计（finance/fund/crm 等财务关键链路），二者共用同一套 WORM 与哈希链机制 |
| 链（chain） | 由 `chain_key` 标识的一串同表记录；本册**每个表各成一条（或按组织分链的多条）链**，四张流水表的链互不混排（理由见 3.6.2） |
| 链键（chain_key） | `GLOBAL`（默认，全集团单链）或 `ORG:<orgId>`（按组织分链，可配） |
| 链内序号（chain_seq） | 同表同 `chain_key` 内的序号，由该表专属序列分配；唯一约束保证不重号 |
| 创世哈希（genesis hash） | 链首行的 `prev_hash`，固定为 64 个 `0`（`0000…0000`） |
| 行哈希（row_hash） | 该行业务字段规范化后的 SHA-256 摘要，见 3.6.1 与 7.3 |
| 校验根（chain root） | `audit_chain_roots` 一行：某区间 `[from_seq, to_seq]` 校验通过后的**末端行哈希**，供归档后区间外推校验 |
| 证明包（proof） | `exportProof` 导出的某区间哈希链证明文件（逐行链值 + 首尾根 + 校验方法说明），供司法/审计举证 |
| STRONG 模式 | 审计写入与业务同事务同步落库，审计写失败即业务回滚（强审计口径） |
| ASYNC 模式 | 业务事务提交后异步落库，审计写失败不回滚业务（默认，除 3.3.2 清单外的场景） |
| 链冻结（chain freeze） | 校验发现不一致后，对该 `chain_key` 停止追加写入，直到人工裁决（3.7.4） |
| 自审计抑制 | audit 自身运行产生的事件（分区创建、链校验、归档、告警写库）**不再反向写审计表**，避免自环与噪声 |
| 审计留痕 vs 审计查询 | 「留痕」= 被审计操作写事件；「查询」= 读审计表，**查询本身不写事件**（导出除外） |

### 1.4 追溯关系

| 本册章节 | 上游来源 | 对齐说明 |
|---|---|---|
| 2.1 模块定位 | HLD 5.3、HLD 2.3、CONTEXT「通用能力层」 | 双审计职责边界与"不做业务"的判定线 |
| 2.2 能力清单 | SRS 3.2 FR-AUD-01…12 | 逐条 FR → 本册章节，无遗漏映射 |
| 2.3 五层分包 | HLD 2.2.2、P0 册 3.1.3、P1 册 3.2（裁决 C-19） | Controller 落 `infrastructure/web`，`api` 只放契约 |
| 2.4 依赖方向 | HLD 11.1、`docs/agents/architecture.md`、P1 册 2.2 | audit → iam/platform（只经 `api`）；audit 被全部模块依赖 |
| 3.1 采集 | HLD 5.3、P1 册 2.4（审计挂钩冻结项）、platform 3.13 事件 | P0 册 6.3 第 2 条落点 |
| 3.3 写入可靠性 | HLD 4.4、SRS NFR-REL-02、P0 册 6.3 第 5 条 | 事件可靠性最小实现 |
| 3.5 WORM | SRS FR-AUD-03、SRS NFR-SEC-08、HLD 8.4、HLD 13.3 | 本册验收核心 |
| 3.6 哈希链 | SRS FR-AUD-04、HLD 12 第 4 条「审计哈希链的具体实现与校验工具」 | 本册验收核心 |
| 3.8 分级审计 | SRS FR-AUD-06、HLD 8.2、P1 册 4.6 `DataScopeApi` | 母子公司隔离 |
| 3.9 归档 | SRS FR-AUD-05、SRS 6.2、SRS NFR-DATA-03、HLD 4.5、HLD 6.3 | 分区 + 归档 + 链不断 |
| 3.10 财务审计 | SRS FR-AUD-07、HLD 5.3、HLD 11.2（`VoucherPostedEvent`） | 财务轨迹接入点 |
| 3.11 告警 | SRS FR-AUD-12、HLD 9.4 | 规则 + 命中 + 通知 |
| 3.12 前端 | HLD 3.4、`docs/agents/frontend-conventions.md` | 页面清单与权限点 |
| 4.x 数据结构 | HLD 4.1.4、HLD 4.3、P0 册 3.6、ADR-0002 | DDL 与迁移 |
| 6.4 防篡改专项 | HLD 13.3（P1 验收"操作审计 WORM + 哈希链校验验证"） | 专项验证用例 |

### 1.5 参考资料

1. [[03-企业级一体化管理系统-e-aio-概要设计说明书|HLD]]：2.2.2 模块化单体边界、2.3 模块划分、2.4 模块间交互机制、3.2 内部模块接口、3.3 核心事件清单、4.1.4 审计域实体、4.3 数据存储分工、4.4 数据一致性策略、5.3 audit、6.3 定时任务设计、7.1/7.2 出错处理、8.4 审计安全、8.5 等保三级对照、11.1 依赖矩阵、11.2 核心事件清单、11.3 主要技术组件清单、12 待详细设计阶段细化事项第 4 条、13.2/13.3 开发队列与验收。
2. [[02-企业级一体化管理系统-e-aio-软件需求规格说明书|SRS]]：C-07 双审计约束、2.2 角色（审计员/集团管理员/子公司管理员/财务人员）、3.2 FR-AUD-01…12、4.3 NFR-SEC-06/08、4.8 NFR-REL-02、6.1 数据分类分级、6.2 数据保留与归档。
3. [[04-企业级一体化管理系统-e-aio-详细设计说明书-P0-工程地基|P0 册]]：3.1.3 命名空间与包结构、3.2.5 幂等键拦截、3.3 全局异常处理、3.4 日志与链路基础、3.6 Flyway 每模块独立实例、3.7 ArchUnit 质量门、6.1 模块登记表、6.3 待 P1 细化事项（第 2、5 条）。
4. [[04-企业级一体化管理系统-e-aio-详细设计说明书-P1-平台底座|P1 册 · 平台底座]]：2.4 链式契约先行冻结项、2.5 裁决表（C-1、C-7、C-13、C-19）、2.6 P0 遗留销账、3.3 platform 对外契约（`FileApi`/`ExcelApi`/`ParamApi`/`CacheApi`/`SchedulerApi`）、3.10 Excel 导出留痕、3.13 事件与投递口径、4.2–4.9 iam 契约（`UserApi`/`OrgApi`/`DataScopeApi`/`PermissionApi`/权限变更事件）、4.9 事件可靠性。
5. [`ADR-0001`](adr/0001-unified-post-and-always-200-result-contract.md)（统一 POST + 恒 200）、[`ADR-0002`](adr/0002-per-module-schema-and-flyway-instance.md)（每模块 Schema + 独立 Flyway 实例）。
6. [`docs/agents/`](agents/)：architecture / api-conventions / database / java-conventions / frontend-conventions / build-and-test / tech-stack（评审基准，冲突时优先级高于 03/02）。
7. PostgreSQL 17 官方文档：`CREATE TABLE ... PARTITION BY RANGE`、`CREATE TRIGGER`、`pg_advisory_xact_lock`、`REVOKE`、`ALTER TABLE ... DETACH PARTITION`。

> **同批次兄弟册说明**：本册编写时，仓库内 P1 段仅有 `04-…-P1-平台底座.md`（platform + iam 合册，M1–M5 段），**不存在**独立的 `04-…-P1-platform.md` 与 `04-…-P1-iam.md`。本册以该合册的第 3、4 章（含 2.4 冻结项与附录 7.4 交接清单）作为兄弟册契约对齐来源；若后续拆册，`FileApi`/`ExcelApi`/`OrgApi`/`UserApi`/`DataScopeApi` 的签名以拆册后的 `api` 为准，本册据此做**向后兼容**复核（见 7.6）。

---

## 2. audit 总体设计

### 2.1 模块定位与双审计职责边界

**一句话**：audit 是 e-aio 的**证据层**——所有模块把"发生了什么"交给它，它负责**不可篡改地存下来、可校验地证明没被改过、按组织分级地查出来**。

HLD 5.3 给 audit 的职责是「系统操作审计 + 财务专项审计」。本册把这条职责拆成**四条产品线**，并明确各自的边界（越界即评审否决）：

| 产品线 | 表 | 回答的问题 | 典型消费者 | 本册实现度 |
|---|---|---|---|---|
| 系统操作审计 | `audit_events` | 谁在什么时候对什么做了什么、结果如何 | 全部模块（`@AuditLog`） | M4–M5 完整 |
| 登录认证审计 | `audit_login_logs` | 登录/登出/认证失败（含失败原因、设备、会话） | iam 认证链路 | M4–M5 完整 |
| 权限变更审计 | `audit_perm_changes` | 谁改了谁的什么权限，改前改后是什么（快照） | iam 角色/授权链路 | M4–M5 完整 |
| 财务专项审计 | `audit_fin_trails` | 凭证/应收应付/对账/报表的财务关键操作轨迹（金额+期间+币种） | finance / fund / crm（P2） | M4–M5 落**模型与接入点**；P2 业务侧填充 |

**做**：

- 采集（注解 + 切面 + Web 层兜底）、缓冲、落库、链化、校验、举证、查询、统计、导出、告警规则匹配、归档。
- 持有 `eaio_audit` Schema 下的全部表；对外只暴露 `com.eaio.audit.api` 的四个接口。
- 向 iam 注册审计自身的定时任务（链校验、分区维护、归档、告警扫描）。

**不做**（逐条给判定线，避免"顺手做了"）：

| 不做 | 判定线 | 归属 |
|---|---|---|
| 不解析业务语义（如"这张合同能不能改"） | audit 只记录，不裁决；出现任何 `if (业务规则)` 即越界 | 业务模块 |
| 不查业务表、不 JOIN 其他 Schema | 需要对象名称时经 `UserApi`/`OrgApi` 取，或由调用方在 `AuditEventCmd` 里带上 | ADR-0002 |
| 不实现鉴权判定 | `hasPermission` 属 iam；audit 只用权限码字符串做 `@PreAuthorize` | iam |
| 不做业务对账（账实相符/四流合一/费用合规） | 需要读业务单据与财务数据 → P2（3.10.4） | P2 finance |
| 不直接实现通知通道（邮件/短信/企微） | 只落 `audit_alerts` + 调 portal 通知（P1 先落库 + ERROR 日志） | portal（顺序 9） |
| 不做日志采集（应用日志） | JSON 日志走 logback（P0 册 3.4），与审计是两件事 | app |
| 不保存敏感字段原文 | 见 3.4：脱敏后入审计，原文不落审计表 | 本册硬约束 |
| 不做防篡改的"绝对承诺" | DBA 拥有物理权限；audit 保证**改得动必被发现**，不承诺改不动 | 3.5.5 诚实口径 |

### 2.2 能力清单与 FR 对照表

SRS 3.2 的 12 条 FR，逐条映射到本册章节 + 验收方式（**不允许出现"有 FR 无落点"**）：

| FR | 需求（SRS 3.2） | 优先级 | 本册章节 | 落地方式 | 验收 |
|---|---|---|---|---|---|
| FR-AUD-01 | 系统操作审计：登录/登出、增删改查、导出、权限变更全量留痕 | P0 | 3.1、3.2 | `@AuditLog` + `AuditAspect` + Web 层拦截器（MDC 去重） | 6.2.1 覆盖矩阵用例 |
| FR-AUD-02 | 审计要素：操作人、时间、IP、模块、对象、动作、前后值、结果 | P0 | 3.2.2 | `audit_events` 列与 `AuditEventCmd` 字段一一对应（7.3 逐字段规范化） | 6.1.1 字段完备性单测 |
| FR-AUD-03 | 审计日志只追加（WORM），数据库层禁止 UPDATE/DELETE | P0 | 3.5 | 三道防线：仓储无写改路径 / `BEFORE UPDATE OR DELETE` 触发器 / `REVOKE` | 6.4.1、6.4.2 |
| FR-AUD-04 | 哈希链防篡改（每条含前一条哈希，定期校验根） | P0 | 3.6、3.7 | `prev_hash`/`row_hash` + `verifyChain` + `audit_chain_roots` | 6.1.2、6.4.3 |
| FR-AUD-05 | 审计数据独立存储周期与备份策略 | P1 | 3.9 | 按月分区 + detach 归档 + `audit_archives` 台账 + 独立备份口径 | 6.2.4、6.6 |
| FR-AUD-06 | 集团分级审计：母公司全集团、子公司仅本组织 | P0 | 3.8.4 | 查询强制经 `DataScopeApi`；`audit:query:all` 超级审计员 | 6.2.3 |
| FR-AUD-07 | 财务审计：凭证/账务/报表全链路留痕 | P0 | 3.10 | `audit_fin_trails` + `AuditApi.recordFinTrail`（STRONG 默认） | 6.2.5 |
| FR-AUD-08 | 账实相符：对账任务 + 差异追踪 | P1 | 3.10.4 | **P2 承接**（需读业务单据） | 7.6 登记 |
| FR-AUD-09 | 四流合一：合同/物流/资金/发票关联校验 | P1 | 3.10.4 | **P2 承接** | 7.6 登记 |
| FR-AUD-10 | 费用合规审计：预算与制度规则引擎 | P1 | 3.10.4 | **P2 承接** | 7.6 登记 |
| FR-AUD-11 | 审计查询：多维检索、报表、导出 | P1 | 3.8 | `AuditQueryApi`（`getPage`/`stats`/`export`），导出走 `ExcelApi` 异步任务 | 6.2.2、6.5 |
| FR-AUD-12 | 审计告警：异常操作/越权访问实时告警 | P2 | 3.11 | `audit_alert_rules` + 异步匹配 + `audit_alerts` | 6.2.6 |

**非 FR 但本册必须交付的工程项**：

| 项 | 来源 | 落点 |
|---|---|---|
| `@AuditLog` 切面本体 | P0 册 6.3 第 2 条 | 3.1.2、7.5 |
| 事件总线可靠性最小实现（重试/死信/幂等） | P0 册 6.3 第 5 条、P1 册 2.6 | 3.3.5、3.3.6、7.5 |
| 审计死信表 | P1 册 3.13「死信表随 audit 落地」 | `audit_events.source='RETRY'` + 3.3.6（**不新增表**，理由见 3.3.6） |
| 业务错误码枚举 | P0 册 3.7「业务错误码落段断言」 | 7.1（`22000–22999`） |
| ArchUnit 断言对象 | P0 册 6.3 第 7 条 | 6.3 |

### 2.3 五层分包

沿用 P0 册 3.1.3 的五层分包与 P1 册裁决 C-19（**Controller 放 `infrastructure/web`**，`api` 只放跨模块契约）：

```
backend/e-aio/e-aio-audit/                                  # M4 新增 Maven 模块（P1 新增）
├─ pom.xml                                                  # 依赖：e-aio-common、e-aio-platform、e-aio-iam
└─ src/main/
   ├─ java/com/eaio/audit/
   │  ├─ package-info.java          @ApplicationModule(allowedDependencies = {"platform", "iam"})
   │  ├─ api/                       @NamedInterface("api")：只有 interface + record + 枚举
   │  │  ├─ package-info.java
   │  │  ├─ AuditApi.java                   写：record / recordBatch / recordLogin / recordPermissionChange / recordFinTrail
   │  │  ├─ AuditQueryApi.java              读：get / getPage / getPageByOperator / export / stats / getLoginPage / getFinTrailPage
   │  │  ├─ AuditChainApi.java              举证：verify / latestRoot / exportProof
   │  │  ├─ AuditAlertApi.java              告警：listRules / saveRule / listAlerts / ack
   │  │  ├─ AuditSnapshotProvider.java      前后值快照提供者（模块实现）
   │  │  ├─ AuditLog.java                   @AuditLog 注解（全模块用）
   │  │  ├─ AuditMode.java                  STRONG / ASYNC
   │  │  ├─ AuditAction.java / AuditResult.java / AuditSource.java   枚举
   │  │  └─ dto/                            Cmd / Query / DTO / Result（见 5.3）
   │  ├─ application/               用例编排：AuditRecordAppService / AuditQueryAppService /
   │  │                             AuditChainAppService / AuditAlertAppService /
   │  │                             AuditArchiveAppService（事件监听、事务边界、MapStruct 映射）
   │  ├─ domain/                    领域模型：AuditEvent / LoginLog / PermChange / FinTrail /
   │  │                             ChainRoot / Archive / AlertRule / Alert（值对象）
   │  │                             + 仓储接口（只 insert/select）+ ChainHasher（哈希算法，纯函数）
   │  ├─ infrastructure/
   │  │  ├─ persistence/            MyBatis-Plus Mapper + 仓储实现（**无 update/delete 方法**）
   │  │  ├─ queue/                  有界队列 + 批量 flush 调度器 + 重试
   │  │  ├─ chain/                  PostgresChainLock（advisory lock）+ 分区维护 + 归档搬运
   │  │  ├─ alert/                  规则表达式求值器（受限语法，非通用脚本）
   │  │  ├─ job/                    审计定时任务 handler（注册到 platform SchedulerApi）
   │  │  └─ web/                    @RestController（仅出站适配，鉴权用权限码字符串）
   │  └─ events/                    AuditChainBrokenEvent（链不一致）、AuditWriteFailedEvent（写失败）
   └─ resources/db/migration/audit/   每模块独立 Flyway 实例（P0 册 3.6、ADR-0002）
```

**新增模块的 11 个登记点**（P0 册 3.1.3 + P1 册 3.2，逐条照做，漏一条架构测试即红）：

| # | 登记点 | audit 的取值 |
|---|---|---|
| 1 | 父 POM `<modules>` | 追加 `e-aio-audit`（当前 `pom.xml` 只有 common/platform/app） |
| 2 | 模块根包 `package-info.java` | `@ApplicationModule(allowedDependencies = {"platform", "iam"})` |
| 3 | `api/package-info.java` | `@NamedInterface("api")` |
| 4 | 五层目录 | api / application / domain / infrastructure / events |
| 5 | 迁移目录 | `classpath:db/migration/audit` |
| 6 | Schema | `eaio_audit`（Flyway `createSchemas` 创建） |
| 7 | `application.yml` 的 `eaio.flyway.modules` | 追加 `- audit`（当前只有 `- platform`） |
| 8 | 错误码枚举 | `com.eaio.audit.api.AuditErrorCode implements BusinessErrorCode`，全部落在 `22000–22999` |
| 9 | `ArchitectureTest` 的 `MODULE_NAMES` 与白名单 | 追加 `"audit"`；`crossModuleRuleHasTeeth` 的 allowed 追加 `com.eaio.audit.api..` |
| 10 | 不写 `@ComponentScan` | 启动类在 `com.eaio` 自动向下扫描 |
| 11 | P0 册附录 6.1 登记表 | 已登记（`audit` / `com.eaio.audit` / `eaio_audit` / `22000–22999`），本册不改 |

> **`allowedDependencies` 的写法**：Modulith 的模块依赖**不按包名**（无 `com.eaio.iam.api..` 这种写法），只按模块名或命名接口名。本册采用 `{"platform", "iam"}`（依赖对方模块的**共享面**，即其命名接口）——写法比 `{"platform::api", "iam::api"}` 更宽松，但更稳：**收紧到 `::api` 需要对方模块的命名接口已在同一构建中可见**，而 iam 的 `api` 命名接口在 M4 才随 iam 落地；M4 落地后若 `::api` 可用，按 7.6 收紧。两种写法都不改变"只许用 api 包"的实质约束（由 ArchUnit 的跨模块规则兜底）。

### 2.4 依赖方向与契约

```
common ──▶ platform ──▶ iam ──▶ audit ──▶ workflow/approval ──▶ …
                                                        （HLD 13.2 / 11.1）
```

| 方向 | 内容 | 约束 |
|---|---|---|
| audit → platform | `ParamApi`（阈值/开关）、`FileApi`（归档文件、举证文件）、`ExcelApi`（导出任务）、`CacheApi`（统计缓存失效）、`SchedulerApi`（注册定时任务） | 只经 `com.eaio.platform.api` |
| audit → iam | `UserApi`（操作人/目标用户名称）、`OrgApi`（组织名称与子树）、`DataScopeApi`（分级审计过滤）、`PermissionApi`（`hasPermission` 判定，供超级审计员例外） | 只经 `com.eaio.iam.api` |
| audit ← 全部模块 | `@AuditLog` 注解 + `AuditApi` 写入 + `AuditSnapshotProvider` 实现 | 消费方只依赖 `com.eaio.audit.api` |
| audit ✗ workflow / 业务模块 | **禁止**：审计不得依赖任何业务模块（否则业务模块 → audit → 业务模块成环） | ArchUnit + Modulith 双拦 |
| audit ✗ 其他 Schema | **禁止跨 Schema 查询/写入**：audit 只写 `eaio_audit` | ADR-0002 |

**Modulith 事件与同步调用的分工（对齐 P1 册 3.13 裁决 C-7）**：

- **业务审计走同步 `AuditApi`（或注解切面）**，不走事件总线。理由：事件是"解耦的异步副作用"，而审计**需要携带上下文**（前后值、对象、结果），这些信息只在调用点的栈上；把审计做成事件订阅会让每个模块都要自己拼事件载荷，反而更脆。**P1 册 3.13 冻结的 `FileUploadedEvent`/`ExcelExportedEvent`/`ParamChangedEvent` 等平台事件由 audit 用 `@ApplicationModuleListener` 订阅**（platform 不依赖 audit，只能走事件）。
- **iam 的 `UserRoleChangedEvent`（P1 册 4.5/4.9，AFTER_COMMIT）由 audit 订阅**，但 iam 侧**同时**会调 `AuditApi.recordPermissionChange`（STRONG）——二者以 `event_uid` 派生幂等键去重（见 3.3.4），不会双份。

**审计模块对 iam/platform 未就绪的降级（M4 早期）**：

| 缺失依赖 | 降级行为 | 恢复条件 |
|---|---|---|
| `UserApi` 不可用 | 展示层**不解析用户名**，`operator_name` 存 `AuditEventCmd` 传入的原值（可为空），查询降级为显示 `operator_id` | iam `api` 就绪后自动生效（`ObjectProvider` 注入，运行期探测） |
| `OrgApi` 不可用 | 组织树展示降级为扁平的 `org_id` 列表 | 同上 |
| `DataScopeApi` 不可用 | **fail-closed**：除 `audit:query:all` 持有者外，一律只返回**本组织**（由 `TenantCtx` 取 `org_id`；无上下文则拒绝查询并报 `10403`） | iam 就绪 |

> 注入一律用 `ObjectProvider<T>`（`getIfAvailable()`），**不用 `@Autowired(required=false)` 的隐式语义**——降级路径必须是显式代码，能在测试里被覆盖（6.1.3）。

### 2.5 审计数据流总览

```
① 采集                ② 缓冲                  ③ 落库                ④ 链化
┌──────────────┐   ┌──────────────────┐   ┌────────────────┐   ┌──────────────────┐
│ @AuditLog    │   │ 有界队列          │   │ INSERT 批量     │   │ prev_hash=链尾    │
│ AuditAspect  │──▶│ ArrayBlockingQueue│──▶│ (JDBC batch)   │──▶│ row_hash=H(canon)│
│ Web 拦截器    │   │ 容量 10000（可配） │   │ 500 条/批       │   │ advisory lock 串行│
└──────────────┘   └──────────────────┘   └────────────────┘   └──────────────────┘
        │                    │                                            │
        │ STRONG：同事务直插 ─┘（不经队列）                                 │
        ▼                                                                 ▼
⑤ 校验                          ⑥ 归档                         ⑦ 查询
┌────────────────────┐   ┌────────────────────┐   ┌──────────────────────────┐
│ verifyChain(定时)   │   │ 分区 detach → 导出   │   │ DataScopeApi 过滤         │
│ root → chain_roots  │──▶│ → 对象存储 → 台账    │   │ 热表 + 归档文件（跨区间）  │
│ 不一致 → 告警 + 冻结 │   │ root 先固化（链不断）│   │ 导出 → ExcelApi 异步任务  │
└────────────────────┘   └────────────────────┘   └──────────────────────────┘
```

**七段的失败模式各不同**，本册逐段给失败处置（这是审计模块与普通业务模块最大的差别）：

| 段 | 失败 | 处置 | 章节 |
|---|---|---|---|
| ① 采集 | 切面抛异常 / 注解写错（SpEL 解析失败） | **绝不影响业务**：切面异常只记 ERROR + `traceId`；SpEL 解析失败编译期无法发现 → 单测覆盖（6.1.4） | 3.1.5 |
| ② 缓冲 | 队列满 | ASYNC：本地溢出文件兜底 + 告警；STRONG：等待 200ms 后失败并阻断业务（`22009`） | 3.3.3 |
| ③ 落库 | 数据库不可用 | 指数退避重试 3 次 → 进死信（`audit_events.source='RETRY'` + 溢出文件重投） | 3.3.5 |
| ④ 链化 | 拿不到 advisory lock（超时） | 整批失败 → 重试；**不允许"跳过链化直接插入"**（宁可慢，不可断链） | 3.6.3 |
| ⑤ 校验 | 发现不一致 | 告警 + **冻结该链**；人工裁决（继续断点 / 从备份恢复） | 3.7.4 |
| ⑥ 归档 | 校验和不匹配 | 归档状态置 `FAILED`，分区**不 drop**（数据不动），告警 | 3.9.4 |
| ⑦ 查询 | 区间过大 / 归档文件缺失 | `22002`；归档缺失 → `22006` + 提示区间（**不静默返回空**） | 3.8.5 |

### 2.6 非目标

1. **不做防篡改的"物理不可改"承诺**：DBA 持有 `ALTER TABLE ... DISABLE TRIGGER` 权限；本册承诺的是**改得动必被发现**（哈希链 + 校验根 + 归档校验和），并在 3.5.5 明确写出这一诚实口径，不用"WORM"一词暗示绝对。
2. **不做业务对账引擎**（FR-AUD-08/09/10）：SRS 标 P1 重要度，但开发批次属 P2（HLD 13.2 顺序 12+）。本册只保证 `audit_fin_trails` 的模型与接入点，财务规则引擎不在 M4–M5。
3. **不做审计的全文检索**（OpenSearch）：HLD 4.3 把 OpenSearch 列为全文检索存储，但那是知识库/文档/工单场景；审计检索靠 PostgreSQL 索引 + 分区裁剪，M4–M5 **不引入 OpenSearch 同步链路**（引入即多一条最终一致链路与一个故障面）。
4. **不做数仓同步**（ClickHouse 审计分析，HLD 4.3）：P2/P3 随 report 模块；本册 `stats` 只做 PostgreSQL 聚合，并给出容量上限（4.7）。
5. **不做审计数据的跨实例一致性广播**：与 P1 册 3.4/3.8 同策略（短 TTL 兜底，不建 pub/sub 通道）。
6. **不做前端代码**：3.12 只给页面与权限点；`frontend/` 不由本册修改。
7. **不引入新的第三方依赖**：哈希用 JDK `MessageDigest`，Base64/Hex 用 JDK，压缩/流式导出用 common 门面（`ExcelKit`）。本册**零新增 Maven 坐标**（唯一例外：M4 需要 `spring-boot-starter-aspectj`，P0 册 3.1.2 已引入）。

---

## 3. 详细设计

> **本节每小节的固定结构**：设计目标 → 组件与类 → 关键流程 → 关键取舍与代价 → 边界与失败模式 → 测试点。凡涉及金额、权限、审计的写入路径，取舍必须写出**代价**，不允许只写优点。

### 3.1 审计采集（@AuditLog 注解 + 切面 + 自动采集范围）

#### 3.1.1 设计目标

1. **零侵入的业务接入**：业务代码加一行注解即完成留痕；不加注解的操作由 Web 层兜底留痕（FR-AUD-01"全量留痕"）。
2. **上下文完整**：审计要素（操作人/时间/IP/模块/对象/动作/前后值/结果，FR-AUD-02）在**调用点**采集，不靠事后拼装。
3. **绝不因审计失败影响业务**（ASYNC 模式）；STRONG 模式例外且显式声明（3.3.2）。
4. **同一请求只有一条**：注解切面与 Web 兜底拦截器不得双写。

#### 3.1.2 组件与类

```java
// com.eaio.audit.api.AuditLog —— 全模块可用的采集注解（跨模块契约）
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuditLog {

    /** 模块名（与模块根包同名，如 "iam" / "finance"）。 */
    String module();

    /** 动作（见 7.2 动作字典）。 */
    AuditAction action();

    /** 对象类型（业务实体名，如 "contract" / "user_role"）。 */
    String objectType() default "";

    /**
     * 对象 ID 取值表达式（SpEL，作用于方法参数）。
     * 例：objectId = "#cmd.id"、objectId = "#id"；空表示不采集对象 ID。
     */
    String objectId() default "";

    /** 是否采集前置快照（调用 AuditSnapshotProvider.before）。 */
    boolean captureBefore() default false;

    /** 是否采集后置快照。 */
    boolean captureAfter() default false;

    /** 写入模式；默认 ASYNC（提交后异步落库），关键路径显式写 STRONG。 */
    AuditMode mode() default AuditMode.ASYNC;

    /** 概要文案（SpEL 可选），不写则由切面按 module+action+objectType 生成。 */
    String summary() default "";
}
```

```java
// com.eaio.audit.api.AuditSnapshotProvider —— 前后值快照来源（业务模块实现）
public interface AuditSnapshotProvider {

    /** 该提供者负责的对象类型（与 @AuditLog.objectType 对应）。 */
    String objectType();

    /**
     * 采集快照。返回**可序列化的普通对象**（Map / record / DTO），
     * audit 侧会用规范化器 + 脱敏器处理，业务模块不得自行拼 JSON 字符串。
     *
     * @param objectId 对象 ID；captureBefore 在方法执行前调用，captureAfter 在执行后调用
     */
    Object snapshot(String objectId);
}
```

**切面**（`com.eaio.audit.application.AuditAspect`，`@Aspect` + `@Order(0)`）：

| 关注点 | 实现 |
|---|---|
| 拦截点 | `@annotation(com.eaio.audit.api.AuditLog)`（只注解驱动，不做包名切面——包名切面会在新增模块时静默失效） |
| 前置 | 生成 `event_uid`（UUIDv4）、记录 `occurred_at`（UTC，`Instant.now()`）、解析 SpEL 取 `objectId`、按需调 `captureBefore` |
| 后置 | 按需调 `captureAfter`、取 `Result`/返回值的成败、捕获异常类型与错误码 |
| 结果判定 | 业务异常 → `result=FAILURE` + `error_code`；正常返回 → `SUCCESS`（即使返回 `Result.fail` 也按 `code != 0` 判 `FAILURE`，与 HTTP 恒 200 的现实对齐） |
| 上下文 | 从 `TenantCtx`（common `TenantContextHolder`）取 `operator_id`/`org_id`；从 MDC 取 `traceId`；从请求作用域取 `ip`/`user_agent`/`request_path`（`RequestContextHolder`，无请求时为 null） |
| 写入 | `mode=STRONG` → 直接 `AuditApi.record`（同事务）；`ASYNC` → 投递到队列（3.3） |
| 去重标记 | 成功采集后设 `MDC["audit.annotated"]="1"`，Web 拦截器见标记即跳过 |
| 异常隔离 | 切面内部任何异常都**吞掉并记 ERROR**（ASYNC）；STRONG 模式下**审计写失败必须抛出**（否则"强审计"是假的） |

```java
@Aspect
@Component
@Order(0)   // 必须在业务事务切面之内：ASYNC 需要在提交后触发，STRONG 需要在同一事务内
@RequiredArgsConstructor
public class AuditAspect {

    private final AuditRecordGateway gateway;   // application 层门面（内部，不是 api）
    private final ObjectProvider<AuditSnapshotProvider> snapshotProviders;

    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint pjp, AuditLog auditLog) throws Throwable {
        AuditDraft draft = gateway.begin(pjp, auditLog);       // 采集前置上下文（不写库）
        try {
            Object result = pjp.proceed();
            gateway.finishSuccess(draft, result);              // ASYNC 入队 / STRONG 不在此刻写
            return result;
        } catch (Throwable t) {
            gateway.finishFailure(draft, t);
            throw t;                                           // 业务异常原样上抛，绝不被审计改写
        }
    }
}
```

> **`finishSuccess` 与 STRONG 的落点**：STRONG 必须"业务与审计同一事务"，因此实际写入点在 `AuditRecordGateway` 的 `@Transactional(propagation = MANDATORY)` 方法内——**调用方没有事务直接报错**（而不是悄悄降级为 ASYNC）。这是"强审计"唯一的可验证定义（6.1.5）。

#### 3.1.3 自动采集范围（FR-AUD-01 的"全量"如何做到）

"全量留痕"不能靠"每个 Service 都记得加注解"——漏加是静默失败。因此采用**双层采集**：

| 层 | 覆盖 | 采集内容 | 实现 |
|---|---|---|---|
| L1 注解层（Service） | 有业务语义的操作（前后值、对象 ID、结果） | 完整审计要素 | `@AuditLog` + `AuditSnapshotProvider` |
| L2 请求层（Web 兜底） | 所有 `/api/**` 请求（除白名单） | 请求级要素（路径、方法、操作人、IP、UA、耗时、HTTP 结果） | `AuditWebInterceptor`（`HandlerInterceptor`，注册在 `com.eaio.audit.infrastructure.web`） |

**L2 白名单（不产生审计事件，写死在代码并单测锁定）**：

| 路径模式 | 理由 |
|---|---|
| `/audit/**`（读接口） | **防自环**：审计查询不写审计（导出接口除外，导出走 L1 显式注解） |
| `/actuator/**` | 健康检查高频噪声 |
| `/auth/captcha/**` | 验证码高频；登录成败已由 `recordLogin` 覆盖 |
| 静态资源与 `/error` | 非业务 |

**L2 的动作映射**（`request_path` → `action`，覆盖 FR-AUD-01 的"增删改查"）：

| 路径后缀 | action | 是否 ASYNC |
|---|---|---|
| `/Add` | `CREATE` | ASYNC |
| `/Up`、`/Submit`、`/Approve`、`/Reject` | `UPDATE` | ASYNC |
| `/Del` | `DELETE` | ASYNC |
| `/Get`、`/GetPage` | `READ` | **默认不采集**（见下） |
| `/Export`、`/Download` | `EXPORT` | **STRONG**（NFR-SEC-05 导出留痕） |
| `/Grant`、`/Revoke`、`/Assign` | `GRANT` / `REVOKE` | **STRONG** |

> **"查"要不要留痕——本册的取舍**：FR-AUD-01 字面含"查"，但**对每次 `GetPage` 写一条审计事件会把审计表变成业务表的 100 倍**（4.7 容量测算：读取量是写入量的 50–200 倍），代价是存储与链化吞吐被查询流量吃掉，且真正的审计信号被噪声淹没。本册裁决：**默认不采集 `READ`**；改为提供 `@AuditLog(action = READ, mode = STRONG)` 显式标注的"敏感读取"（如薪酬、银行账号、财务报表明细）留痕，并把"敏感读取清单"作为各模块的评审项（谁的敏感数据谁申报）。**代价明确写出**：这是对 FR-AUD-01 字面的收窄，收窄范围有清单、可配置（`eaio.audit.capture.read-paths`），不是"漏了"。该裁决登记进 7.6 提请架构评审。

**L1/L2 去重**：`MDC["audit.annotated"]="1"`（L1 设置）→ L2 在 `afterCompletion` 检查，命中则不写。去重**按请求**而非按方法，因此一个请求内多次 `@AuditLog` 调用会产生多条 L1 事件（正确：一个请求确实改了两件事），而 L2 一条都不补（正确：细节已由 L1 覆盖）。

#### 3.1.4 关键流程

```
Client ──▶ TraceIdFilter (P0) ──▶ IdempotencyFilter (P0) ──▶ AuditWebInterceptor.preHandle
                                                                   │ 记录开始时间/路径/操作人
                                                                   ▼
                                                            Controller → AppService
                                                                   │  ① 业务事务开始
                                                                   │  ② @AuditLog 切面 begin（采集前置）
                                                                   │  ③ 业务写库
                                                                   │  ④ 切面 finish：
                                                                   │     ASYNC → 入队（事务提交后由监听器 flush）
                                                                   │     STRONG→ 同事务 INSERT（含链化）
                                                                   │  ⑤ 提交
                                                                   ▼
                                            AuditWebInterceptor.afterCompletion
                                                                   │ MDC 有 audit.annotated ？→ 跳过
                                                                   │ 否则按映射投递 L2 事件（ASYNC/STRONG）
                                                                   ▼
                                                              响应返回（HTTP 200）
```

**幂等键与 traceId 入审计（与 P0 入站链路对齐，本册硬要求）**：

| 字段 | 来源 | 用途 |
|---|---|---|
| `idempotency_key` | `Idempotency-Key` 请求头（P0 册 3.2.5 已由 `IdempotencyFilter` 读取） | 关联"同一次用户动作"的重试；解释"为什么有两条相似事件" |
| `trace_id` | MDC（`TraceIdFilter` 写入，P0 册 3.4） | 与 JSON 日志、`sys_job_log`（P1 册 3.7）串联排障 |

> 采集实现从 MDC / 请求头读取，**不改 P0 的过滤器**（`IdempotencyFilter` 已把键放进请求属性，audit 侧只读不写）。

#### 3.1.5 关键取舍与代价

| 取舍 | 选择 | 代价 |
|---|---|---|
| 注解 vs 包名切面 | **注解** | 漏加 = 无审计；用 L2 兜底 + 6.2.1 覆盖矩阵用例降低风险 |
| 切面顺序 `@Order(0)` | 在业务事务切面内层 | STRONG 才能进同一事务；代价是切面必须在 `spring-boot-starter-aspectj` 生效的代理链里，`final` 类/自调用方法**不会**被切（这是 Spring AOP 的已知边界，作为**已知限制**登记：自调用需要显式 `AopContext.currentProxy()` 或改由上层 Service 注解） |
| 异常隔离 | 切面吞异常 | ASYNC 下**审计可能静默丢失**；用"队列积压指标 + 写失败告警规则"（3.11）补 |
| 快照来源 | `AuditSnapshotProvider`（模块实现） | 每个模块要写一个 provider；不实现则该字段为空（记 WARN，不报错）——**不假装有值** |
| 前后值粒度 | 整对象快照 | 大对象（如含 500 行的单据）会撑大 JSONB；单条快照上限 `eaio.audit.snapshot.max-bytes`（默认 64KB），超限**截断并置 `summary` 标注 `TRUNCATED`**，不静默丢 |

#### 3.1.6 边界与失败模式

| 场景 | 行为 |
|---|---|
| SpEL 表达式非法 / 参数名不可解析（未开 `-parameters`） | 切面记 ERROR（含 `traceId` 与表达式），`objectId` 置空继续；**不阻断业务**。构建要求：`maven-compiler-plugin` 开 `-parameters`（编译期即可发现"参数名不可用"，用单测锁定） |
| 方法抛异常且异常类型是 `BusinessException` | `result=FAILURE`，`error_code` 取 `BusinessException.getCode()`；异常继续上抛，全局处理器按 P0 册 3.3 输出 `Result` |
| 无 HTTP 上下文（定时任务、事件监听内部调用） | `ip`/`user_agent`/`request_path` 为 null，`operator_id` 取 `TenantCtx` 或标注为 `SYSTEM`（`operator_id = 0`，约定见 7.2） |
| 同一方法被嵌套调用两次 | 产生两条事件（真实发生了两次操作），`event_uid` 不同；不合并 |
| 切面在事务回滚后仍入队（ASYNC） | **必须避免**：ASYNC 的入队动作在 `@TransactionalEventListener(AFTER_COMMIT)` 内执行（3.3.1），回滚事务不入队 |
| `captureBefore` 与业务读取顺序 | 前置快照在**业务方法执行前**读取；若业务方法自身修改了实体且是同一持久化上下文，前置快照必须在切面 `begin` 时立即深拷贝（不持有实体引用）——否则前后值相同（这是最容易被写出来的假审计） |

#### 3.1.7 测试点

| 编号 | 用例 | 断言 |
|---|---|---|
| 6.1.4-a | `@AuditLog(objectId="#cmd.id", captureBefore=true, captureAfter=true)` 正常路径 | 事件 1 条，`object_id` 等于 `cmd.id`，`before_value != after_value`（且是**深拷贝**，改实体不影响 before） |
| 6.1.4-b | 方法抛 `BusinessException(22004)`（示例） | 事件 `result=FAILURE`、`error_code=22004`、异常仍上抛 |
| 6.1.4-c | 切面内抛出异常（模拟快照 provider 报错） | 业务方法**正常返回**（异常被隔离），有 ERROR 日志 |
| 6.1.4-d | L1 + L2 同请求 | 只 1 条事件（MDC 去重生效） |
| 6.1.4-e | 敏感读取标注 | 事件 `action=READ` 且 `mode=STRONG`（事务内可见） |
| 6.1.4-f | 快照超 64KB | `before_value` 被截断，`summary` 含 `TRUNCATED` |
| 6.1.4-g | 编译期 `-parameters` | 反射取参数名非 `arg0`（构建期断言） |

### 3.2 审计事件模型与要素（FR-AUD-02）

#### 3.2.1 设计目标

1. **要素齐全**：SRS FR-AUD-02 的八个要素（操作人、时间、IP、模块、对象、动作、前后值、结果）一个不少，且每个要素有唯一来源。
2. **可判等**：同一个操作在 STRONG/ASYNC/重试三种路径下产生的事件**内容一致**（否则链校验与去重都靠运气）。
3. **可索引**：查询模式（3.8）能命中索引，不做全表扫（审计表是最大表，4.7）。

#### 3.2.2 要素定义（`AuditEventCmd` ↔ `audit_events` 列）

| 要素 | Cmd 字段 | 表列 | 类型 | 来源 | 缺失时 |
|---|---|---|---|---|---|
| 事件标识 | `eventUid` | `event_uid` | UUID | 切面/调用方生成（UUIDv4） | **不允许为空**（幂等键基座） |
| 业务引用 ID | —（落库时生成） | `id` | BIGINT | `IdGenerator`（雪花，common） | 由 audit 生成 |
| 链键 | `chainKey` | `chain_key` | VARCHAR(64) | 默认 `GLOBAL`；`eaio.audit.chain.per-org=true` 时 `ORG:<orgId>` | 默认 `GLOBAL` |
| 链序号 | —（落库时分配） | `chain_seq` | BIGINT | 序列 `audit_chain_seq` | 由 audit 分配 |
| 操作时间 | `occurredAt` | `occurred_at` | TIMESTAMPTZ | 切面 `Instant.now()`（UTC） | **不允许为空**（分区键） |
| 操作人 | `operatorId` / `operatorName` | `operator_id` / `operator_name` | BIGINT / VARCHAR(128) | `TenantCtx` + `UserApi`（io 未就绪则用 Cmd 传入值） | `operator_id=0` + `operator_name='SYSTEM'` |
| 组织 | `orgId` | `org_id` | BIGINT | `TenantCtx` | 系统级操作写 `0` |
| 模块 | `module` | `module` | VARCHAR(64) | `@AuditLog.module` | **不允许为空** |
| 动作 | `action` | `action` | VARCHAR(32) | `@AuditLog.action`（7.2 字典） | **不允许为空** |
| 对象类型 | `objectType` | `object_type` | VARCHAR(64) | `@AuditLog.objectType` | 允许为空（READ 类） |
| 对象 ID | `objectId` | `object_id` | VARCHAR(64) | `@AuditLog.objectId`（SpEL） | 允许为空 |
| 概要 | `summary` | `summary` | VARCHAR(512) | `@AuditLog.summary` 或自动生成 | 自动生成 |
| 前后值 | `beforeValue` / `afterValue` | `before_value` / `after_value` | JSONB | `AuditSnapshotProvider` → 规范化 + 脱敏 | 允许为空（无快照） |
| 结果 | `result` | `result` | VARCHAR(16) | 切面判定 | **不允许为空** |
| 错误码 | `errorCode` | `error_code` | INTEGER | `BusinessException.getCode()` | 成功时为 null |
| 网络 | `ip` / `userAgent` | `ip` / `user_agent` | VARCHAR(64) / VARCHAR(512) | 请求上下文（脱敏后，见 3.4.4） | 无请求时为 null |
| 链路 | `traceId` | `trace_id` | VARCHAR(64) | MDC | 允许为空 |
| 路径 | `requestPath` | `request_path` | VARCHAR(256) | 请求上下文 | 允许为空 |
| 耗时 | `durationMs` | `duration_ms` | INTEGER | 切面/拦截器计时 | 允许为空 |
| 幂等键 | `idempotencyKey` | `idempotency_key` | VARCHAR(200) | `Idempotency-Key` 请求头 | 允许为空（查询类无键） |
| 写入来源 | `source` | `source` | VARCHAR(16) | `SYNC` / `ASYNC` / `IMPORT` / `RETRY`（7.2 字典） | 由写入链路决定 |
| 链 | —（落库时计算） | `prev_hash` / `row_hash` / `hash_algo` | CHAR(64)/CHAR(64)/VARCHAR(16) | `ChainHasher` | 由 audit 计算 |

> **字段与表的双向一致性由测试锁定**：`AuditEventCmd` 的每个分量都必须在表中有对应列（6.1.1 用反射比对 DTO 分量与 DDL 列清单），防止"加了字段但没落库"。

#### 3.2.3 组件与类

```
com.eaio.audit.domain
├─ AuditEvent          领域值对象（与表同构；equals 用 event_uid）
├─ LoginLog / PermChange / FinTrail
├─ ChainRoot / Archive / AlertRule / Alert
├─ AuditEventRepository      interface { AuditEvent insert(AuditEvent); Optional<AuditEvent> findById(long);
│                                        List<AuditEvent> selectPage(AuditEventQuery);
│                                        Optional<AuditEvent> findChainTail(String chainKey);       // 供链化
│                                        List<AuditEvent> selectChainRange(String chainKey, long from, long to);
│                                        long nextChainSeq(String chainKey); }
│                            // **没有 update / delete 方法**（WORM 第一道防线，3.5.1）
├─ LoginLogRepository / PermChangeRepository / FinTrailRepository（同形）
└─ ChainHasher              纯函数：canonicalJson(...) → byte[] → SHA-256 → hex（3.6.1）
```

#### 3.2.4 关键流程：一条事件的完整生命周期

```
@AuditLog 触发
  → AuditDraft（内存对象：要素 + 快照原始对象，**尚未序列化**）
  → normalize()：快照 → 规范化 Map（键排序、时间精度、数字归一）→ 脱敏 → CanonicalJson.bytes()
  → AuditEvent（值对象，含 before/after 的规范化 bytes 与 JSONB 文本）
  → [ASYNC] 入队 / [STRONG] 同事务链化 + INSERT
  → INSERT 成功后：row_hash 回填到内存对象（供同批次下一行 prev_hash；ASYNC 在链化时一次算完）
  → 批量 flush 完成 → 发 AuditWriteFailedEvent（仅失败时）/ 更新队列指标
```

#### 3.2.5 关键取舍与代价

| 取舍 | 选择 | 代价 |
|---|---|---|
| 一个大表 vs 分表 | **四表分列**（`audit_events` / `audit_login_logs` / `audit_perm_changes` / `audit_fin_trails`） | 四套链、四套查询；换来"登录日志不污染操作审计检索"与财务轨迹的专项字段（金额/期间/币种）不塞进通用表 |
| `before/after` 用 JSONB | JSONB | 存储膨胀（1.3–2×文本）；换来可用于 containment 查询与统一规范化 |
| 对象 ID 用 `VARCHAR(64)` 而非 BIGINT | VARCHAR | 支持复合业务键（如 `contract:2026:000123`）；代价是索引更大、无类型校验 |
| `operator_name` 冗余存储 | 存快照名 | 用户改名后历史事件仍显示当时的名字（审计语义正确）；代价是冗余存储 |

#### 3.2.6 边界与失败模式

| 场景 | 行为 |
|---|---|
| `module`/`action` 为空 | `AuditApi.record` 直接抛 `BusinessException(PARAM_MISSING)`（10001）——**审计要素缺失不是审计模块的失败，是调用方的 bug** |
| `objectId` 含超长/非法字符 | 截断到 64 字符并记 WARN（不拒绝，避免因业务 ID 形态变化丢审计） |
| `occurred_at` 不在任何分区 | 落入 `p_default` 默认分区（3.9.1），并由分区任务告警 |
| `occurred_at` 与 `created_at` 差超过 `eaio.audit.clock.skew-warn`（默认 1 小时） | 记 WARN（时钟漂移或补录），不拒绝 |

#### 3.2.7 测试点

| 编号 | 用例 | 断言 |
|---|---|---|
| 6.1.1-a | DTO ↔ 表列完备性 | DTO 每个分量都有对应列（反射比对） |
| 6.1.1-b | `module` 为空 | 抛 `10001` |
| 6.1.1-c | `module`/`action` 超长 | 截断且不抛异常（DDL `VARCHAR` 限制内） |
| 6.1.1-d | 同一 SpEL 解析 | `objectId="#cmd.id"` 与 `"#root.id"` 都能解析（`-parameters` 生效） |

### 3.3 写入链路与可靠性（AFTER_COMMIT 异步 / STRONG 模式 / 队列 / 重试 / 幂等 / 崩溃补偿）

#### 3.3.1 设计目标

1. **审计写失败不回滚业务**（ASYNC）——否则"审计模块抖动导致全系统写不可用"。
2. **审计不拖慢 P0 写接口**（NFR-PERF-02：常规接口 P95 < 500ms）——审计的额外开销必须可控且可测（6.5）。
3. **高合规场景可换 STRONG**——权限变更、财务轨迹默认为 STRONG（HLD 7.2"强审计场景失败即拒绝"）。
4. **崩溃窗口有界且可举证**（ASYNC 的代价必须写清 + 有补偿手段）。

#### 3.3.2 两种模式的定义与默认分配

| 模式 | 事务边界 | 语义 | 失败时 |
|---|---|---|---|
| `ASYNC`（默认） | 业务事务提交后（`@TransactionalEventListener(AFTER_COMMIT)` → 入队 → 批量 flush） | 尽力交付，不阻断业务 | 重试 3 次 → 死信 + 告警 |
| `STRONG` | **同一事务**（`Propagation.MANDATORY`） | 审计与业务同生共死 | 审计写失败 → 抛 `22009` → 业务事务回滚 |

**默认分配表（照此实现，不由各模块"凭感觉"选）**：

| 场景 | 模式 | 依据 |
|---|---|---|
| 登录/登出/认证失败（`LoginAuditCmd`） | **STRONG** | 认证是安全事件，且登录本身无长事务；HLD 8.4 |
| 权限变更（`PermissionChangeCmd`：角色授权、数据范围、SoD） | **STRONG** | SRS FR-SEC-13；HLD 7.2「强审计场景失败即拒绝」 |
| 财务轨迹（`FinTrailCmd`：凭证/账务/报表） | **STRONG** | SRS FR-AUD-07；财务不可"记不上就算了" |
| 导出（`EXPORT`） | **STRONG** | SRS NFR-SEC-05 导出管控 |
| 告警规则变更（`saveRule`） | **STRONG** | 改审计规则本身必须是可审计的强动作 |
| 归档批次创建（内部） | **STRONG** | 归档台账必须与链根同事务一致 |
| 普通业务增删改（CREATE/UPDATE/DELETE） | **ASYNC** | 量最大；写失败不该让库存/合同写不进去 |
| 敏感读取（显式 `READ`） | **STRONG**（由注解显式指定） | 读取本身短，可承受 |

> **可配但要留痕**：`eaio.audit.mode.default=ASYNC`、`eaio.audit.mode.strong-actions=LOGIN,LOGOUT,GRANT,REVOKE,EXPORT`（逗号分隔的 action 清单）。参数改动本身走 platform 参数中心 → 触发 `ParamChangedEvent` → audit 订阅后写一条 `module=audit, action=UPDATE` 的 STRONG 事件（**改审计配置这件事本身被审计**）。

#### 3.3.3 异步写入链路（组件与流程）

```
业务事务提交
   │  Spring 发布 AuditEnqueuedEvent（@TransactionalEventListener(AFTER_COMMIT)）
   ▼
AuditQueueProducer.offer(draft)
   │  队列：ArrayBlockingQueue<AuditEvent>（容量 eaio.audit.queue.capacity，默认 10000）
   │  满时：
   │    - ASYNC → 写本地溢出文件（audit-overflow-<date>.jsonl，追加 + fsync），
   │               记 WARN + 指标 eaio_audit_queue_overflow_total++，返回不阻塞
   │    - STRONG → 不会走队列（见 3.3.2），故此处不考虑
   ▼
AuditBatchFlusher（独立线程/调度器，每 200ms 或攒够 eaio.audit.write.batch-size=500 条）
   │  ① 开启**一个**事务：pg_advisory_xact_lock(chainKey, 0)（3.6.3）
   │  ② SELECT 链尾（prev row_hash + max(chain_seq)）
   │  ③ 逐条算 row_hash（链内顺序 = 入队顺序，即事件发生顺序）
   │  ④ JDBC batch INSERT（同批次提交，一条 SQL 批次）
   │  ⑤ 提交 → 释放 advisory lock
   ▼
失败 → 重试（指数退避 200ms/400ms/800ms，最多 3 次）
   ▼
仍失败 → 死信：写溢出文件 + 更新 audit_write_failures 指标 + 触发 3.11 规则"审计写入失败率"
        + 启动补偿任务（AuditOverflowReplayer）在下次启动/每小时扫描溢出文件重投
```

**批内顺序 = 链内顺序**：入队顺序即事件 `occurred_at` 顺序（切面在方法返回后立即入队）。因此同批次的 `chain_seq` 严格递增且相邻，`prev_hash` 首尾相连。跨批次由 advisory lock + 链尾查询保证连续。

**事务边界（必须写清）**：

- 一个批次 = 一个事务。批次内要么全部落库（链连续），要么全部失败重试。**不允许部分提交**（部分提交会造成链内空洞，且空洞无法区分"崩了"和"被删了"）。
- 重试必须**重算整批**（重新取链尾）——不能复用上次算出的 `prev_hash`（链尾可能已被 STRONG 写入推进）。
- `source` 字段：正常异步批次写 `ASYNC`；重试写入写 `RETRY`；溢出文件重投写 `RETRY`；同事务同步写 `SYNC`；批量导入通道写 `IMPORT`。

#### 3.3.4 幂等（同一事件不会写两次）

| 层次 | 机制 | 覆盖 |
|---|---|---|
| 入队 | `event_uid` 在内存 `ConcurrentHashMap` 去重表（LRU，容量 `eaio.audit.dedup.cache-size=200000`） | 同一 Java 进程内的重复投递（如监听器被多次触发） |
| 落库 | 插入前按 `event_uid` 查存在性（**同批次内先在内存比对，跨批次查库**） | 重试与溢出重投 |
| 唯一约束 | 索引 `idx_audit_events_uid`（非唯一）+ `event_uid` 的**数据库层唯一性由 `occurred_at` 分区的本地唯一索引 `uq_audit_events_uid` 保证**（默认分区 `p_default` 上另有全表兜底唯一索引，见 4.3.1） | 并发重复 |
| 平台事件去重 | iam 的 `UserRoleChangedEvent` 与 iam 主动调用的 `recordPermissionChange` 用**派生幂等键**：`event_uid = uuid_v5(namespace, "perm:" + userId + ":" + roleId + ":" + changeType + ":" + occurredAt.truncatedTo(SECONDS))` | 同一次变更的两条投递只会落一条 |

> **为什么幂等不靠"唯一约束报错后忽略"**：批量插入中一条唯一冲突会让**整批**失败（PG 默认语句级原子），重试整批又冲突 → 死循环。因此采用"先查后插"（幂等表在 `event_uid` 上有索引，代价是一次轻量索引探测），唯一索引只作为最后一道并发兜底，冲突时**把整批降级为逐条插入 + 忽略重复**（代码路径显式存在并有测试）。

#### 3.3.5 崩溃窗口与补偿（ASYNC 的代价，必须承认）

| 窗口 | 会丢什么 | 缓解 | 残留风险 |
|---|---|---|---|
| ① 事务提交后、入队前（进程崩溃/被 kill） | 该次操作的事件 | 无（窗口毫秒级） | **存在**；用 STRONG 覆盖关键动作（3.3.2） |
| ② 队列内（进程崩溃） | 队列中未 flush 的事件（最多 `batch-size` + 队列长度） | 溢出文件兜底（队列满时同步落盘）；`AuditQueueDrainHook`（`@PreDestroy`，优雅停机时 flush 剩余队列） | **存在**；优雅停机可消除，强杀不可 |
| ③ 数据库不可用 | 整批事件 | 重试 3 次 + 死信 + 溢出重投 | 数据库长时间不可用期间的事件落溢出文件 |
| ④ 分区边界（`occurred_at` 落在未建分区） | 无（落 `p_default`） | 分区预建任务提前 3 个月建好 | 无 |
| ⑤ 时钟跳变导致链内 `occurred_at` 乱序 | 无（`chain_seq` 才决定链序） | 校验按 `chain_seq` 排序，不按时间 | 无 |

**可举证的窗口登记**：`audit_chain_roots` 每轮校验都记录 `from_seq`–`to_seq`；若某轮校验发现"链尾曾中断"（`max(chain_seq)` 有空洞），写一条 `verified=false` 的根记录并把 `mismatch_seq` 指向**本该存在但缺失的序号**。这样"丢了哪个窗口"在数据库里有记录，不靠运维口头解释。

> **`source` 之外的关联手段**：崩溃窗口内的事件确实不在审计表里；调查时的替代证据是（a）应用 JSON 日志（P0 册 3.4，可按 `traceId` 检索）、（b）反向代理/WAF 访问日志（保留策略由部署方定）、（c）平台 `sys_job_log`（P1 册 3.7）。**本册不假装这些自动可用**——它是一条部署方需要评估的运维依赖，登记在 7.6。

#### 3.3.6 死信：为什么不新增一张死信表

P1 册 3.13 说"死信表随 audit 落地"。本册的落地方式是**不新增表**：

- 审计事件写失败的死信**本身就是"一条没能落进审计表的审计事件"**，把它落进 `eaio_audit` 的另一张普通表并不比落进 overflow 文件更强（同样在同一个库、同样可能写不进去——数据库挂了两个都写不进）。
- 因此死信载体 = **进程外的追加文件**（`${eaio.audit.overflow-dir}`，默认 `<work-dir>/audit-overflow/`，权限 600，每行一条 JSON，写入即 `fsync`），配合 `AuditOverflowReplayer`（每小时 + 启动时）重投，重投成功即从文件归档为 `.done` 后缀（保留 30 天供人工核查）。
- **代价与边界**：溢出文件在**应用容器**上，容器重建即丢；因此（a）溢出目录必须挂持久卷（部署清单一条），（b）溢出文件积压本身触发 STRONG 级告警（3.11 规则 `AUDIT_QUEUE_BACKLOG`），（c）**溢出期间"审计完整性"是降级状态**，需人工确认后才算闭环。

> 若将来需要"多实例共享的死信队列"，再引入 Redis Stream 或独立表——**P1 不为未被证实的需求预置机制**（YAGNI，P1 册 2.5 裁决风格一致）。

#### 3.3.7 关键取舍与代价

| 取舍 | 选择 | 代价 |
|---|---|---|
| AFTER_COMMIT 异步 vs 同事务 | **以异步为默认**，STRONG 显式声明 | 崩溃窗口（3.3.5）；换来"审计不拖慢 P0 写接口"与"审计失败不回滚业务" |
| 有界队列 vs 无界队列 | **有界**（10000） | 满时溢出到文件（多一条 I/O 路径与一个目录要运维）；无界会在数据库故障时 OOM——**这是必须选有界的理由** |
| 批量 flush（200ms/500 条） | 批量 | 最坏 200ms 的可见延迟（STRONG 无延迟）；换来写入吞吐与链化的锁竞争降低 |
| 溢出文件 vs Redis 队列 | 文件 | 多实例下各自本地；换来"数据库/Redis 同时挂也能留住事件" |
| 幂等先查后插 | 查 | 每条约一次索引探测；换来批量插入不被唯一冲突整批击穿 |

#### 3.3.8 边界与失败模式

| 场景 | 行为 |
|---|---|
| 队列满 + 溢出文件目录不可写（磁盘满/只读） | 记 ERROR + 指标 `eaio_audit_overflow_write_failed_total++`，**放弃该条**（ASYNC）；STRONG 场景此时失败会阻断业务——这是"磁盘满"这一真实故障的正确表现 |
| 批量 flush 中途 JVM OOM | 整批未提交（一个事务），数据不落；由溢出文件与重放补（若已入队未落盘则丢，窗口②） |
| 优雅停机 | `AuditQueueDrainHook` 先停止接收 → flush 剩余 → 再关闭线程池；超时 `eaio.audit.shutdown.timeout=30s` |
| 多实例并发写同一链 | advisory lock 串行化（3.6.3）；等待超时 `eaio.audit.chain.lock-timeout=5s`，超时报 `22003`（链区间非法/锁失败类）并整批重试 |
| STRONG 在无事务调用（如定时任务内部） | 立即抛 `SystemException`（编程错误，不是业务错误）——**不允许静默降级为 ASYNC** |

#### 3.3.9 测试点

| 编号 | 用例 | 断言 |
|---|---|---|
| 6.2.1-a | ASYNC 正常路径 | 业务提交后 ≤ 1s 内落库，`source='ASYNC'`，`chain_seq` 连续 |
| 6.2.1-b | 队列满 | 溢出文件出现一行；指标 +1；业务不受影响 |
| 6.2.1-c | 落库失败注入（数据库断开 1 次） | 重试成功，`source='RETRY'`，无重复记录 |
| 6.2.1-d | 连续失败 4 次 | 溢出文件落盘 + 告警记录（`audit_alerts` 一行） |
| 6.2.1-e | STRONG 审计写失败 | 业务事务回滚，响应 `22009` |
| 6.2.1-f | 重复投递同一 `event_uid` | 只落一行 |
| 6.2.1-g | 批次内 500 条 | 500 行链连续，`prev_hash` 首尾相接 |
| 6.2.1-h | 优雅停机时队列有 100 条 | 全部落库（drain 生效） |

