# 企业级一体化管理系统（e-aio）概要设计说明书（HLD）

> **项目名称**：企业级一体化管理系统（e-aio）
> **项目定位**：通用能力大一统 + 业务逻辑按企业定制的企业级开源系统
> **编制依据**：GB/T 8567-2006《计算机软件文档编制规范》、GB/T 9385-2008《计算机软件需求规格说明规范》、GB/T 9386-2008《计算机软件测试文档编制规范》
> **上游文档**：01-可行性研究报告、02-软件需求规格说明书（SRS）
> **版本**：V1.0（草案）
> **日期**：2026-09-16

---

## 核心结论（执行摘要）

本文档在可研与 SRS 基础上，完成 e-aio 的**概要设计**：确定以 **Spring Modulith 模块化单体**为架构形态，划定 **23 个模块（22 个业务/能力模块 + common 技术底座）**及其边界、依赖方向与交互机制；确定**通用能力层与业务模块层分层、单向依赖**的总体结构；确定**母子公司多级权限**、**双审计**、**主数据共享访问**三大核心引擎的设计方案；确定接口、数据结构、运行、出错处理、安全保密与部署运维方案。本设计可作为详细设计、编码、测试与验收的依据。

| 设计主题 | 关键决策 |
|------|----------|
| 架构形态 | Spring Modulith 模块化单体，单一可执行产物，不采用微服务 |
| 模块划分 | 23 个模块（22 个功能模块 + common 技术底座），独立 Schema，应用层接口访问 |
| 依赖方向 | 业务模块层 → 通用能力层，禁止反向依赖，ArchUnit 强制校验 |
| 模块协作 | 同步走模块公共 API；异步走 Spring 应用事件（事务事件监听器） |
| 主数据 | 模块内共享访问（实时、事务内强一致），变更发事件驱动副作用，仅跨系统边界才分发 |
| 权限 | 无限级组织树 + RBAC/ABAC 混合 + 数据权限 + 字段权限 + SoD |
| 审计 | 系统操作审计（追加式防篡改）+ 财务专项审计（账实相符/四流合一/费用合规） |
| 工作流 | Flowable 二次封装，动态表单绑定，跨组织流转，审批中心统一收口 |
| 部署 | Docker Compose 单机起步，可横向扩展；等保三级目标，信创适配规划 |

---

## 1. 引言

### 1.1 编写目的

本文档描述 e-aio 系统的**概要设计**结果，包括总体架构、模块划分、模块间接口、数据结构、关键机制（权限、审计、工作流、主数据、AI）与运行/部署设计，作为后续**详细设计、编码实现、集成测试与验收**的技术基线。

读者对象：架构师、模块负责人、开发工程师、测试工程师、实施运维人员、开源贡献者、评审与验收人员。

### 1.2 项目背景

e-aio 定位为**全开源、自托管、模块化单体**的企业级一体化管理系统，将 OA、CRM、库存、财务、HRM、项目、供应链、工作流、审批、权限、审计、报表 BI、AI、门户等通用能力大一统到同一平台，业务逻辑通过主数据与配置化能力按企业定制，原生支持**母子公司多级组织**与**系统+财务双审计**。项目不采用 SaaS、不做微服务，采用 **Spring Boot 4.1.x + Spring Modulith 2.1.x**，全栈开源优先，接受社区 PR。

### 1.3 术语与缩写

| 术语 | 说明 |
|------|------|
| Modulith | Spring Modulith，模块化单体架构框架 |
| 模块（Module） | 按业务域划分、有明确公共 API 边界的代码单元，对应独立 Schema |
| 公共 API（API） | 模块对外暴露的只读/写入口（Spring 托管服务），外部模块仅能经此访问 |
| 应用事件（Event） | Spring 应用事件，模块间异步解耦的消息机制，事务事件监听器保证一致性 |
| 通用能力层 | OA/CRM/库存/财务/工作流/审批/权限/审计/AI/报表等平台统一提供的模块 |
| 业务模块层 | 按行业/企业定制的具体业务逻辑模块，单向调用通用能力 |
| 组织树 | 集团-子公司-部门-岗位-用户的多级组织模型 |
| SoD | 职责分离（Segregation of Duties） |
| MDM | 主数据管理 |
| WORM | Write Once Read Many，审计日志只追加防篡改 |
| Vibe Coding | 以 AI 辅助编码为核心的开发范式 |
| common | 通用工具库模块（Excel / Redis / 通用工具类） |

### 1.4 参考资料

1. GB/T 8567-2006《计算机软件文档编制规范》
2. GB/T 9385-2008《计算机软件需求规格说明规范》
3. GB/T 9386-2008《计算机软件测试文档编制规范》
4. Spring Modulith 官方文档（docs.spring.io/spring-modulith）
5. Spring Boot 4.1.x 官方文档
6. 《企业级一体化管理系统可行性研究报告》（01）
7. 《企业级一体化管理系统软件需求规格说明书》（02）

---

## 2. 总体设计

### 2.1 设计原则与目标

| 原则 | 说明 | 落地手段 |
|------|------|----------|
| 模块化单体 | 单一进程、单一可执行产物，模块边界清晰 | Spring Modulith 命名空间约束 + ArchUnit |
| 高内聚低耦合 | 模块内聚业务，模块间仅经公共 API / 事件交互 | 公共 API 包 + 事件发布订阅 |
| 单向依赖 | 业务模块 → 通用能力，禁止反向与循环依赖 | ArchUnit 依赖规则强制 |
| 数据自治 | 每模块独立 Schema，跨模块禁止直连数据表 | 应用层接口 + 数据访问审计 |
| 事务强一致 | 财务、库存、审批等关键链路事务强一致 | 单体事务 + 事件事务监听器 |
| 可观测可审计 | 全链路留痕、指标、日志、链路可观测 | 双审计引擎 + Actuator/Prometheus |
| 开源优先 | 技术选型全开源，无商业闭源依赖 | 组件选型清单（见 2.5） |
| 可演进 | 未来可平滑拆分为独立服务 | 模块接口稳定、事件解耦 |

### 2.2 架构设计总览

#### 2.2.1 逻辑架构（分层）

```
┌────────────────────────────────────────────────────────────┐
│                      接入层（Presentation）                  │
│  Web 门户（Vue3 + Vite + Element Plus（配置化渲染）） │ 移动端 │ OpenAPI │ Webhook │
├────────────────────────────────────────────────────────────┤
│                    通用能力层（平台统一提供）                    │
│  OA │ CRM │ 库存 │ 财务 │ HRM │ 项目 │ 供应链 │ 营销 │ 售后 │ 资金 │
│  工作流 │ 审批 │ 权限 │ 审计 │ 报表BI │ AI │ 门户消息 │ 主数据MDM │
├────────────────────────────────────────────────────────────┤
│                    业务模块层（按企业定制）                     │
│  行业业务模块（仅依赖通用能力，禁止反向）       │
├────────────────────────────────────────────────────────────┤
│                  配置化定制引擎（元数据 / 规则 / 表单）          │
├────────────────────────────────────────────────────────────┤
│            Spring Modulith 模块化单体（Spring Boot 4.x）      │
│   security │ org │ audit │ workflow │ mdm │ report │ ai │ …  │
├────────────────────────────────────────────────────────────┤
│                  数据与基础设施（开源组件）                     │
│  PostgreSQL │ Redis │ OpenSearch │ ClickHouse │ 向量库 │ 对象存储 │
└────────────────────────────────────────────────────────────┘
```

#### 2.2.2 模块化单体边界（Spring Modulith）

- **命名空间规范**：模块根包 `com.eaio.&lt;module&gt;`，模块内部按 `api / application / domain / infrastructure / events` 分包；`api` 为对外公共接口包，`internal` 为模块内部实现，禁止跨模块引用 `internal`。
- **技术底座 common**：`com.eaio.common` 为最底层技术底座包，被所有模块共享；common 不依赖任何模块。
- **Spring Modulith 验证**：`ApplicationModules.verify()` 在测试阶段自动校验模块依赖、禁止循环依赖、禁止访问他人 `internal`。
- **单一可执行产物**：整个系统构建为一个 Spring Boot 可执行 Jar，通过配置按需启用模块。
- **独立 Schema**：每个模块一个数据库 Schema（`eaio_oa`、`eaio_crm`、`eaio_fin`、…），跨模块数据访问一律经模块公共 API，禁止跨 Schema SQL。

#### 2.2.3 物理部署架构

```
                    ┌──────────────────────┐
  浏览器/移动端 ────▶│  Nginx / Caddy（TLS） │
                    └──────────┬───────────┘
                               ▼
                    ┌──────────────────────┐
                    │   e-aio 单体应用实例   │  （可多实例，前置负载均衡）
                    │  Spring Boot 4.x      │
                    └──┬────┬────┬────┬─────┘
                       ▼    ▼    ▼    ▼
                PostgreSQL Redis OpenSearch ClickHouse
                       │    │
                       └────┴──▶ 向量库(pgvector) / 对象存储(S3/MinIO)
```

- **单机起步**：Docker Compose 一键启动（应用 + PostgreSQL + Redis + 中间件）。
- **水平扩展**：应用无状态化（会话入 Redis），多实例 + 负载均衡；定时任务经 ShedLock 分布式锁防重。
- **读写分离与数仓**：在线交易走 PostgreSQL；BI/报表大查询走 ClickHouse（事件驱动同步），搜索走 OpenSearch。

### 2.3 模块划分

系统划分为 **23 个模块**：22 个功能模块与 SRS 3.1–3.22 一一对应，另含 **common（技术底座）**，按职责归为四类（技术底座 / 平台底座 / 通用业务 / 平台支撑）：

| 类别 | 模块 | 对应 SRS |
|------|------|----------|
| 技术底座 | common（通用工具库：Excel / Redis / 通用工具类） | 全局横切（无业务需求） |
| 平台底座 | security（认证授权与权限引擎） | 3.11 权限 |
| 平台底座 | org（组织与用户） | 3.5 HRM-01/02、3.16 PLT-02 |
| 平台底座 | audit（双审计） | 3.10 审计 |
| 平台底座 | workflow（工作流引擎）+ approval（审批中心） | 3.8、3.9 |
| 平台底座 | mdm（主数据管理） | 3.15 主数据 |
| 平台底座 | report（报表 BI） | 3.12 报表 |
| 平台底座 | ai（AI 能力） | 3.13 AI |
| 平台底座 | platform（基础平台：参数/字典/文件/定时任务/Excel/缓存/监测） | 3.16 基础平台 |
| 通用业务 | oa（OA 协同） | 3.1 OA |
| 通用业务 | crm（客户关系） | 3.2 CRM |
| 通用业务 | inventory（库存） | 3.3 库存 |
| 通用业务 | finance（财务） | 3.4 财务 |
| 通用业务 | hr（人力资源） | 3.5 HRM |
| 通用业务 | project（项目管理） | 3.6 项目 |
| 通用业务 | scm（供应链） | 3.7 供应链 |
| 通用业务 | marketing（营销） | 3.17 营销 |
| 通用业务 | service（售后客服） | 3.18 售后 |
| 通用业务 | fund（资金管理） | 3.19 资金 |
| 平台支撑 | portal（门户消息） | 3.14 门户 |
| 平台支撑 | integration（开放平台集成） | 3.20 开放平台 |
| 平台支撑 | mobile（移动端） | 3.21 移动端 |
| 平台支撑 | i18n（多语言多币种） | 3.22 国际化 |

> **说明**：SRS 中"人力资源 HRM"的组织架构能力（FR-HR-01/02）划入 `org` 模块统一支撑权限与通讯录，`hr` 模块承载考勤/薪酬/招聘/绩效等事务能力；两模块通过公共 API 协作。

### 2.4 模块间交互机制

#### 2.4.1 依赖方向与分层

- **技术底座层**（common）：无状态通用工具库（Excel 工具、Redis 工具、通用工具类、ID 生成、统一返回体等），不依赖任何模块，被所有模块依赖。
- **通用能力层**（platform/security/audit/workflow/mdm/report/ai 等）彼此独立或仅依赖平台底座，不依赖任何业务模块。
- **业务模块层**（oa/crm/inventory/finance/hr/project/scm/marketing/service/fund）单向依赖通用能力层，**禁止反向依赖**。
- **平台支撑层**（portal/integration/mobile/i18n）面向接入与集成，仅依赖通用能力层。
- ArchUnit 依赖规则固化：`业务模块 → 通用能力`，任何反向边在 CI 中失败。

#### 2.4.2 同步调用：模块公共 API

- 每个模块在 `api` 包暴露 Spring 服务接口（如 `CustomerApi`、`InventoryApi`），跨模块调用方仅依赖接口与 DTO。
- DTO 使用模块独立的传输对象，禁止直接暴露领域实体，隔离模块内部模型演进。
- 跨模块调用在**同一事务**内完成（单体事务强一致），关键链路（合同→应收、出库→凭证）保证原子性。

#### 2.4.3 异步解耦：Spring 应用事件

- 模块通过 `ApplicationEventPublisher` 发布领域事件（如 `ContractSignedEvent`、`GoodsReceivedEvent`），监听方通过 `@TransactionalEventListener(AFTER_COMMIT)` 消费，保证**事务提交后副作用才执行**。
- 典型异步副作用：缓存刷新、审计留痕（部分场景）、通知推送、搜索索引同步、数仓同步、对外 Webhook。
- 事件 DTO 与消息载荷轻量化，事件失败进入重试队列（Spring Retry + 死信处理）。

#### 2.4.4 数据访问边界

- 模块数据库 Schema 独立；持久层以 MyBatis-Plus 为主（承接 RuoYi 蓝本），限定本模块 Mapper / 实体扫描范围。
- 跨模块查询（如客户 360 视图）通过聚合服务在应用层多次调用各模块 API 组装，或通过**只读视图/数仓**实现，不跨 Schema 直查。

### 2.5 关键技术设计决策

| 主题 | 决策 | 理由 |
|------|------|------|
| 事务边界 | 单体本地事务；异步副作用 AFTER_COMMIT | 财务/库存强一致，避免分布式事务 |
| 并发模型 | 单实例 2,000 并发目标；Tomcat 线程池 + 异步 IO | NFR-PERF-04 |
| 缓存策略 | Caffeine 本地二级缓存 + Redis 分布式缓存；变更事件失效 | 降低热点读，防穿透/击穿/雪崩 |
| 分布式锁 | ShedLock 用于定时任务防重 | 多实例部署安全 |
| 幂等 | 关键写接口幂等键（业务单号/ID）+ 唯一约束 | NFR-REL-03 |
| 多组织 | 数据行级组织维度（org_id/company_id）+ 权限过滤器 | 母子公司数据隔离 |
| 审计 | 审计事件切面 + 追加式审计表 + 哈希链校验 | 防篡改、可溯源 |
| 搜索 | OpenSearch 承载全文检索；事件驱动同步索引 | 知识库/文档/工单检索 |
| 报表 | ClickHouse 数仓 + 预聚合，在线交易库不下沉大查询 | NFR-PERF-03 |
| 工作流 | Flowable 内嵌模式二次封装，动态表单绑定 | 流程可视化、审批联动 |
| AI | Spring AI 网关 + RAG（pgvector）+ Agent 编排 | 多模型接入、成本可控 |

---

### 2.6 实现蓝本：基于 RuoYi（MIT）重写

e-aio 采用**前后端分离**架构，基于 **RuoYi-Vue（前后端分离版）** 为代码蓝本（MIT 许可，可合规复用）：后端为 Spring Modulith 模块化单体（RESTful API + JWT 认证），前端为独立工程（Vue3）。在保留 RuoYi 成熟系统管理功能集的前提下，用 **Spring Boot 4.x + Spring Modulith 2.x 重构后端为模块化单体**。RuoYi-Vue 官方 master 分支已基于 Spring Boot 4.x（JDK 17+），与 e-aio 技术基线一致，可平滑移植并模块化改造。

#### 2.6.1 RuoYi → e-aio 模块映射

| RuoYi 模块 | RuoYi 功能 | e-aio Modulith 模块 | 改造要点 |
|-----------|-----------|---------------------|----------|
| ruoyi-common | 常量、AjaxResult/BaseEntity、核心工具、Redis 工具、安全工具、注解 | common | 工具下沉 common；注解与 AOP 基类归 platform |
| ruoyi-framework | SecurityConfig、JWT、拦截器、AOP（操作日志/防重）、Web 配置 | security + audit + platform | 认证授权归 security；操作日志归 audit；拦截器/配置归 platform |
| ruoyi-system | 用户主档（sys_user） | org | 用户主档（账号/姓名/状态/多组织挂载）归 org，与 5.3 一致；密码凭证与登录策略（MFA/SSO）归 security |
| ruoyi-system | 角色 / 菜单 / 权限 | security | 扩展母子公司多级组织权限（组织树/数据权限/SoD） |
| ruoyi-system | 部门 / 岗位 | org | 组织树升级为无限级（集团-子公司-部门） |
| ruoyi-system | 字典 / 参数 / 公告 | platform | 保留并扩展分级配置 |
| ruoyi-system | 操作日志 / 登录日志 | audit | 升级为 WORM 防篡改双审计 |
| ruoyi-quartz | 定时任务 | platform（Scheduler） | Spring Task + ShedLock，保留 Quartz 可选 |
| ruoyi-generator | 代码生成 | devtools（开发工具链） | 服务 Vibe Coding，不入运行时 |
| ruoyi-ui（RuoYi-Vue3 前端工程） | Vue3 + Vite + Element Plus 管理界面 | portal + 前端工程 | 前后端分离：RESTful API 交互；保留菜单/路由/权限指令，扩展配置化渲染与移动端 |

#### 2.6.2 重写技术要点

| 主题 | RuoYi 现状 | e-aio 重写方案 |
|------|-----------|----------------|
| 架构形态 | 经典单体（多 Maven 模块，运行时单进程） | Spring Modulith 模块化单体：模块边界 + 公共 API + 事件解耦，ArchUnit 校验 |
| 持久层 | MyBatis / MyBatis-Plus + Druid | 保留 MyBatis-Plus（PostgreSQL 适配），模块级 Mapper 限定本模块 Schema |
| 认证授权 | Spring Security + JWT + Redis | security 模块：保留 JWT/SSO 能力，扩展母子公司多级权限、数据权限、SoD |
| 审计 | 操作日志（AOP + 表） | audit 模块：升级 WORM 防篡改 + 财务专项审计双体系 |
| 定时任务 | Quartz | platform Scheduler：Spring Task + ShedLock 分布式锁，Quartz 可选保留 |
| 代码生成 | ruoyi-generator | 保留为开发工具（devtools），辅助 Vibe Coding 生成模块骨架 |
| 前端工程 | RuoYi-Vue3（Vue3 + Vite + Element Plus，独立仓库） | 前后端分离独立工程：保留路由/权限指令/字典/水印组件，扩展配置化渲染引擎与移动端 H5 |

#### 2.6.3 复用边界（许可证与合规）

- RuoYi-Vue 采用 **MIT** 许可证，代码可自由复用、修改、分发（比 Apache-2.0 更宽松）；e-aio 整体继续采用 **Apache-2.0**，保留 RuoYi 原版权声明与 LICENSE 即可合规。
- 重写过程以"模块迁移 + 能力增强"为原则：**优先复用 RuoYi 成熟实现（系统管理、认证、字典、日志），叠加 e-aio 差异化能力（母子公司权限、双审计、主数据、AI）**，避免从零重复造轮子。

#### 2.6.4 API 对接约定（统一 POST + JSON）

前后端与外部集成统一采用 **RESTful 风格 API，全部接口使用 POST 方法 + JSON body**（含查询/删除/导出），以降低多方法语义带来的客户端封装复杂度：

| 维度 | 约定 |
|------|------|
| 协议 | HTTP/HTTPS，统一 POST，`Content-Type: application/json` |
| URL 风格 | `/api/&lt;module&gt;/&lt;resource&gt;/&lt;action&gt;`，动作体现在接口名中（动作词规约见下），如 `POST /api/crm/customer/Get`、`POST /api/crm/customer/Add`、`POST /api/crm/customer/Up`、`POST /api/crm/customer/Del` |
| 请求体 | 全部参数（条件/分页/排序/ID 等）置于 JSON body，查询与写操作一致 |
| 响应体 | 统一 `Result&lt;T&gt;`（code/message/data/traceId）；分页统一 `PageResult&lt;T&gt;` |
| 鉴权 | `Authorization: Bearer &lt;JWT&gt;` 请求头（不因 POST 变化） |
| 幂等 | 写接口携带幂等键（`Idempotency-Key` 头或业务单号字段），支撑防重（NFR-REL-03） |
| 例外 | 文件上传走 multipart/form-data、文件下载走二进制流，不在 JSON 约定内 |
| 文档 | OpenAPI 3.x 统一标注 POST；网关限流/日志/审计按路径维度 |

**动作词规约**（接口名 action 段统一英文，基础动作固定缩写）：

| 动作词 | 含义 | 典型接口示例 |
|--------|------|--------------|
| Get | 查询（单条/分页/列表） | `POST /api/crm/customer/Get`、`POST /api/crm/customer/GetPage` |
| Add | 新增/创建 | `POST /api/crm/customer/Add` |
| Up | 更新/修改 | `POST /api/crm/customer/Up` |
| Del | 删除（逻辑删除优先） | `POST /api/crm/customer/Del` |
| 业务动作 | 模块自定义，统一英文动词 | `Submit`（提交）、`Approve`（审批）、`Export`（导出）、`Import`（导入）、`Audit`（审计查询）等 |

> **理由**：统一 POST + JSON 简化前端单一封装、规避 QueryString 编码与长度问题、便于网关统一过滤与审计；代价是非标准 REST 语义（缓存/幂等需显式处理），由幂等键与统一错误码兜底。

## 3. 接口设计

### 3.1 外部接口

| 接口 | 协议 | 说明 | 关联模块 |
|------|------|------|----------|
| OpenAPI | RESTful（统一 POST + JSON，OpenAPI 3.x 文档） | 第三方 / 二次开发集成；鉴权（OAuth2 Client Credentials / API Key）、限流、日志 | integration |
| Webhook | HTTPS POST + 签名（HMAC-SHA256） | 业务事件推送外部系统，重试 + 幂等 | integration |
| 大模型 API | OpenAI 兼容协议 | 接入企业自有或第三方大模型（可配多个） | ai |
| SSO/LDAP | SAML2 / OIDC / LDAP | 企业统一身份认证对接 | security |
| 消息推送 | 企微/钉钉/邮件/SMS 适配器 | 通知推送渠道 | portal |
| 对象存储 | S3 兼容协议 | 文件存储（MinIO/公有云 OSS） | platform |
| 电子签章 | 第三方 CA 服务 API | 合同在线签署 | crm |

### 3.2 内部模块接口（核心）

| 提供方 | 接口（概要） | 消费方 |
|--------|--------------|--------|
| security | `AuthnApi`（登录/SSO/令牌）、`PermissionApi`（鉴权/数据权限过滤）、`RoleApi`、`SoDCheckApi` | 全部模块 |
| org | `OrgApi`（组织树）、`UserApi`（用户/岗位）、`EmployeeApi` | 全部模块 |
| audit | `AuditApi`（写审计事件）、`AuditQueryApi`（查询/报表） | 全部模块 |
| workflow | `ProcessApi`（发起/流转/催办/撤回）、`FormApi`（表单绑定） | 全部业务模块 |
| approval | `ApprovalApi`（统一审批中心聚合） | 全部业务模块 |
| mdm | `MasterDataApi`（主数据读写/查重/审批）、`MdEvent`（变更事件） | 全部业务模块 |
| platform | `DictApi`、`FileApi`（统一文件上传/下载）、`ParamApi`、`SchedulerApi`、`ExcelApi`、`CacheApi` | 全部模块 |
| report | `ReportApi`（报表取数/看板） | 全部模块 |
| ai | `AiGatewayApi`（模型调用）、`RagApi`（问答）、`OcrApi` | 全部模块 |
| finance | `AccountApi`（科目）、`VoucherApi`（凭证生成） | crm、inventory、scm、hr、fund |
| inventory | `InventoryApi`（库存变动/可用量） | scm、crm、service |
| fund | `PaymentApi`（收付款登记） | crm、finance |

### 3.3 核心事件清单

| 事件 | 发布方 | 典型监听方 | 副作用 |
|------|--------|------------|--------|
| ContractSignedEvent | crm | finance、report | 生成应收计划、同步报表 |
| GoodsReceivedEvent | scm | inventory、finance | 入库、生成应付/存货凭证 |
| InventoryChangedEvent | inventory | report、ai | 库存看板、安全库存预警 |
| VoucherPostedEvent | finance | audit、report | 财务审计留痕、报表更新 |
| MasterDataChangedEvent | mdm | 各模块 | 缓存刷新、索引同步、对外同步 |
| ApprovalCompletedEvent | approval | 发起模块 | 业务状态推进、通知 |
| UserRoleChangedEvent | security | audit | 权限审计留痕 |
| PaymentCompletedEvent | fund | finance、crm | 应收核销、回款更新 |
| DocUploadedEvent | oa | ai | 知识库索引、RAG 更新 |

### 3.4 用户界面设计

- **统一工作台**：门户框架（导航/菜单/待办聚合/消息中心）+ 各模块页面按菜单注册挂载；支持配置化动态渲染（菜单、列表、表单按元数据渲染）。
- **响应式**：桌面 Web + 移动端 H5；移动端经同一套 OpenAPI 与后端交互。
- **权限驱动渲染**：菜单/按钮/字段级可见性由 security 下发，前端不硬编码权限。
- **主题与国际化**：i18n 模块提供语言包与币种汇率，界面可切换。

---

## 4. 数据结构设计

### 4.1 逻辑结构设计（核心领域实体）

#### 4.1.1 组织与权限域（security / org）

```
组织节点(org_node) ─┬─ 类型: 集团/公司/部门/团队
                   ├─ parent_id（无限级树）
                   ├─ 生命周期状态（筹备/运营/注销）
用户(user) ──── 多对多 ──── 岗位(org_position) ── 组织节点
用户 ──── 角色分配(user_role) ──── 角色(role) ──── 权限点(permission)
数据范围规则(data_scope_rule): 角色/用户 → 数据范围(本人/部门/组织/全集团/自定义)
SoD 规则(sod_rule): 互斥权限组，分配时校验
```

#### 4.1.2 主数据域（mdm）

```
主数据类型(md_type) ── 属性模型(md_attribute) ── 校验规则
主数据实例(md_instance) ── 状态: 草稿/待审/生效/停用/归档
                         ├─ 编码(md_code，全局唯一)
                         ├─ 分类(md_category，层级)
                         └─ 变更历史(md_change_log，留痕)
```

#### 4.1.3 工作流与审批域（workflow / approval）

```
流程定义(process_definition) ── 节点配置(node_config) ── 条件规则
流程实例(process_instance) ── 任务(task) ── 审批记录(approval_record)
表单模板(form_template) ── 表单数据(form_data，JSON)
动态表单与流程变量绑定(process_form_binding)
```

#### 4.1.4 审计域（audit）

```
审计事件(audit_event)：
  事件ID(雪花) | 时间 | 操作人 | 用户/组织 | IP | 模块 | 对象类型 | 对象ID |
  动作 | 前后值快照(JSON) | 结果 | 请求ID | 哈希链校验值
审计归档(audit_archive)：按时间分区归档
财务审计轨迹(fin_audit_trail)：凭证/账务/报表关键操作专项留痕
```

#### 4.1.5 业务域核心实体（示例）

| 模块 | 核心实体 |
|------|----------|
| crm | 客户、联系人、线索、商机、合同、回款计划、跟进记录、签章记录 |
| inventory | 物料、仓库、库位、库存余额、出入库单、库存流水、盘点单、批次 |
| finance | 科目、期初、凭证、凭证分录、应收/应付、对账单、预算、固定资产、发票 |
| scm | 供应商、采购申请、询价、采购订单、到货单、验收单 |
| hr | 员工、任职、考勤、薪酬项、工资单、招聘职位、绩效指标 |
| project | 项目、WBS、任务、里程碑、工时、资源分配 |
| fund | 银行账户、收付款单、资金计划、资金调拨单 |
| marketing | 线索池、营销活动、活动效果 |
| service | 工单、服务记录、满意度、客服知识条目 |

### 4.2 物理结构设计（Schema 划分）

| Schema | 模块 |
|--------|------|
| `eaio_security` | security |
| `eaio_org` | org |
| `eaio_audit` | audit |
| `eaio_wf` | workflow、approval |
| `eaio_mdm` | mdm |
| `eaio_platform` | platform |
| `eaio_report` | report |
| `eaio_ai` | ai |
| `eaio_oa` | oa |
| `eaio_crm` | crm |
| `eaio_inventory` | inventory |
| `eaio_fin` | finance |
| `eaio_hr` | hr |
| `eaio_project` | project |
| `eaio_scm` | scm |
| `eaio_marketing` | marketing |
| `eaio_service` | service |
| `eaio_fund` | fund |
| `eaio_portal` | portal |
| `eaio_integration` | integration |
| `eaio_i18n` | i18n |

- **common 无 Schema**：common 为纯代码工具库，不建表、无数据存储，不占用独立 Schema。
- **mobile 无独立 Schema**：mobile 为网关侧统一入口，复用各业务模块 API 与数据，不建独立业务表。
- 公共维度：组织/用户/角色等由 `eaio_security`、`eaio_org` 统一承载，业务表通过**组织 ID、用户 ID 外键引用**（逻辑引用，不跨 Schema 建物理外键，避免耦合）。
- 命名规范：表名 `snake_case` 复数；主键统一 `id BIGINT`（雪花算法）；审计字段 `created_at/created_by/updated_at/updated_by/version` 统一附带。

### 4.3 数据存储分工

| 存储 | 用途 | 说明 |
|------|------|------|
| PostgreSQL（主库） | 全部在线交易数据 | 事务强一致、行级安全（RLS 可作数据隔离兜底） |
| Redis | 会话、缓存、分布式锁、消息队列（轻量） | 缓存失效走事件；ShedLock 锁存储 |
| OpenSearch | 全文检索（知识库/文档/工单/日志检索） | 事件驱动同步，近实时 |
| ClickHouse | BI 报表、审计分析、大查询 | 事件驱动同步 + 预聚合表 |
| pgvector（PostgreSQL 扩展） | RAG 向量检索 | 文档/知识切片向量化 |
| 对象存储（MinIO/S3） | 文件、附件、导出产物 | 统一经 platform `FileApi` 访问（预签名 URL + 权限 + 审计） |

### 4.4 数据一致性策略

- **强一致**：财务凭证、库存变动、审批状态等关键写路径在同一本地事务内完成；跨模块联动在同一事务经公共 API 完成。
- **最终一致**：搜索索引、数仓同步、缓存刷新、Webhook 推送等异步副作用，经 `@TransactionalEventListener(AFTER_COMMIT)` + 重试保证最终一致。
- **幂等**：事件消费按 `事件ID + 业务ID` 幂等去重；外部 Webhook 回调带幂等键。
- **对账**：数仓与在线库定期对账（计数/汇总校验），不一致告警重同步。

### 4.5 数据初始化与迁移

- **初始化**：Flyway 管理各 Schema 迁移脚本（`V1__init.sql`…），启动时自动执行；内置种子数据（字典、默认角色、系统参数、示例流程模板）。
- **导入**：integration 提供数据迁移工具（Excel/CSV/API），支持映射、校验、试运行与回滚。
- **归档**：审计日志按月分区，长期保留；业务历史数据按策略归档至冷存储。

---

## 5. 模块设计

> 每模块给出：职责、核心对象、对外 API（概要）、关键流程、协作关系。详细类图、表结构、接口签名见详细设计文档。

### 5.1 common（通用工具库）— 技术底座

**职责**：提供跨模块复用的**无状态通用工具**：Excel 工具、Redis 工具、通用工具类（日期/字符串/集合/JSON/Bean/树形）、ID 生成、统一返回体与异常、常量、加密与脱敏工具等，并统一接入 **Lombok**（编译期样板代码生成）、**Hutool**（Java 工具库底座）、**Bean Validation**（参数校验）等常用开源工具。common 为纯技术代码库，**不包含业务逻辑、不建表、无独立 Schema**，被所有模块（含 platform）依赖，不依赖任何模块。

**核心设计**：

- **Excel 工具（ExcelKit）**：基于 **Apache Fesod**（原 FastExcel / EasyExcel 生态，Apache 孵化，Apache-2.0）封装流式读写、样式、多 Sheet、大数据量导出（10 万 + 行）、导入解析与错误定位；为 platform 的 Excel 导入导出服务（FR-PLT-05）及全部业务模块提供底层能力。
- **Redis 工具（RedisKit）**：RedisTemplate / Redisson 封装：缓存读写、分布式锁、限流器、轻量队列；支撑缓存管理（FR-PLT-07）与定时任务分布式锁防重（ShedLock 兼容）。
- **通用工具类**：以 **Hutool** 为基础工具库底座（字符串/日期/集合/IO/加密/树形等），在其上做薄封装形成 e-aio 统一工具门面（DateUtils、StringUtils、CollectionUtils、JsonUtils（Jackson）、BeanUtils、树形工具、脱敏工具、雪花 ID 生成器等），避免重复造轮子。
- **Lombok**：编译期注解处理器（@Data / @Builder / @Slf4j / @RequiredArgsConstructor 等），简化 POJO 与日志样板代码，纳入统一代码规范（需 IDE 插件支持）。
- **Bean Validation**：jakarta.validation + Hibernate Validator 标准参数校验（@NotNull / @Size / @Valid / 自定义约束），DTO 入参统一校验、校验错误码映射，全模块复用。
- **MapStruct**：DTO / 实体对象映射编译期生成，替代手写 BeanUtils 反射拷贝（性能敏感与跨模块 DTO 转换场景）。
- **统一返回体与异常**：`Result&lt;T&gt;` 统一响应、`BusinessException` 与错误码体系（`ErrorCode`）、全局异常处理基础类，供全模块复用。
- **对外**：以静态工具类 / Spring Bean 形式提供：`ExcelKit`、`RedisKit`、`DistributedLock`、`RateLimiter`、`IdGenerator`、`JsonUtils`、`SecurityUtils`、`SensitiveUtils` 等。
- **约束**：common 内禁止引入业务模块依赖与业务配置；对外 API 变更需保持向后兼容（被全模块引用）。

### 5.2 security（认证授权与权限引擎）— 平台底座

**职责**：认证（账号/SSO/LDAP/MFA）、令牌、RBAC/ABAC 授权、母子公司数据权限、字段权限、SoD、权限审计。

**核心设计**：
- **认证**：Spring Security + OAuth2 Authorization Server（自托管）；JWT（短期）+ Redis 会话（可撤销）；SSO（SAML2/OIDC）、LDAP 适配器；登录失败锁定、防暴力破解。
- **组织权限模型**：组织树（`org_node` 无限级，类型：集团/公司/部门/团队）；用户可多组织挂载；数据行级权限由 `数据范围规则` 生成 SQL 过滤条件（本人/本部门/本组织/本组织及下级/全集团/自定义）。
- **ABAC**：属性策略引擎（用户属性、数据属性、环境属性），规则可配置；ABAC 作为 RBAC 的补充（高安全场景）。
- **字段权限**：字段级可见/可编辑矩阵（角色 × 字段），前端渲染 + 后端 DTO 裁剪双重控制。
- **SoD**：互斥权限组配置（如"创建合同"与"审核合同"），角色分配与任务指派时校验冲突。
- **集团管控/子公司自治**：权限策略可配置为集团统一下发或子公司自主；角色继承与覆盖。
- **对外**：`AuthnApi`、`PermissionApi`、`RoleApi`、`DataScopeApi`、`SoDCheckApi`、`TenantCtxProvider`（当前组织上下文，全模块使用）。

**关键流程**：登录 → 令牌签发 → 请求鉴权（认证→授权→数据权限过滤）→ 操作审计切面记录。

### 5.3 org（组织与用户）— 平台底座

**职责**：组织节点、岗位、用户主档、员工任职管理；组织生命周期（成立/合并/注销）；通讯录。
**边界**：用户主档（账号/姓名/状态/多组织挂载）在 org；密码凭证、MFA/SSO 绑定与登录策略在 security；角色/菜单/权限点在 security。

**核心设计**：组织树闭包表（`org_node_path`）加速子树查询；组织生命周期状态机（筹备→运营→注销→归档），注销触发权限清理（协同 security）；员工任职与组织节点关联，支撑"兼任多组织"。

**对外**：`OrgApi`（树查询/子树/路径）、`UserApi`、`EmployeeApi`、`OrgLifecycleApi`。

### 5.4 audit（双审计引擎）— 平台底座

**职责**：系统操作审计 + 财务专项审计。

**核心设计**：
- **系统操作审计**：AOP 切面（`@AuditLog` 注解 + 自动捕获）统一采集登录/登出、增删改查、导出、权限变更等操作；审计要素：操作人、时间、IP、模块、对象、动作、前后值快照、结果、请求 ID。
- **防篡改（WORM）**：审计表只追加（无 UPDATE/DELETE 权限，数据库层约束）；哈希链校验（每条记录含前一条哈希，定期生成校验根）；审计日志独立存储周期与备份策略。
- **集团分级审计**：母公司可查全集团，子公司仅查本组织（数据权限复用）。
- **财务专项审计**：凭证/账务/报表全链路留痕；账实相符（对账任务+差异追踪）、四流合一（合同/物流/资金/发票关联校验引擎）、费用合规审计（预算与制度规则引擎）。
- **对外**：`AuditApi`（事件写入）、`AuditQueryApi`（多维检索/报表/导出）、`FinAuditApi`。

**关键流程**：业务操作 → 切面采集 → 审计事件写入（同事务）→ 哈希链计算 → 归档。

### 5.5 workflow（工作流引擎）+ approval（审批中心）— 平台底座

**职责**：流程建模、流转控制、动态表单绑定、跨组织流转、统一审批中心。

**核心设计**：
- Flowable 内嵌模式（同库同事务），`workflow` 模块封装流程定义管理、实例管理、任务操作（同意/驳回/转办/加签/撤回/催办）、条件分支、子流程、超时升级。
- **动态表单绑定**：`form_template`（JSON Schema）与流程变量绑定，审批表单按模板渲染，审批结果回写业务单据。
- **跨组织流转**：审批节点支持按组织层级动态指定审批人（上级公司/集团角色），支撑母子公司跨层级审批。
- **统一审批中心**：`approval` 模块聚合全部业务模块审批待办，提供统一入口、分级审批规则（金额/职级/公司）、时效统计。
- **对外**：`ProcessApi`、`TaskApi`、`FormApi`、`ApprovalApi`；发布 `ApprovalCompletedEvent`。

**关键流程**：业务发起（调 ProcessApi）→ 流程实例创建 → 审批任务流转（含跨组织）→ 完成 → 事件回写业务状态。

### 5.6 mdm（主数据管理）— 平台底座

**职责**：主数据类型/编码/属性/分类建模，主数据全生命周期（草稿→待审→生效→停用→归档），查重合并，共享访问与对外集成。

**核心设计**：
- **共享访问（不复制分发）**：主数据模块运行于同一进程/数据库，各业务模块通过 `MasterDataApi` **实时读取（事务内强一致）**，不做"分发复制"到业务模块；主数据变更发布 `MasterDataChangedEvent` 驱动缓存刷新、审计、通知、搜索/数仓同步。
- **仅跨系统边界分发**：`integration` 模块对第三方异构系统（外部 ERP/数据平台）经 OpenAPI/Webhook 做数据分发同步，含同步监控、失败重试、一致性校验。
- **编码规则引擎**：自动/手动/分段/前缀，全局唯一（数据库唯一约束 + 分布式锁生成）。
- **查重合并**：基于编码/关键属性相似度检测，合并时历史关联迁移。
- **分级维护**：集团统一主数据（如科目体系）与子公司局部主数据（如物料扩展属性）按组织控制维护范围。
- **对外**：`MasterDataApi`、`MdTypeApi`、`MdQualityApi`；发布 `MasterDataChangedEvent`。

### 5.7 platform（基础平台能力）— 平台底座

**职责**：参数配置、数据字典、文件存储、定时任务、Excel 导入导出、缓存管理、系统监测、消息模板。

**核心设计**：
- **定时任务**：Spring Task + ShedLock 分布式锁；任务注册/启停/日志；支持 cron、失败重试、告警（关联 system 监测）。
- **Excel 导入导出**：Apache Fesod 流式读写（10 万+行）；模板管理、字段校验、错误回显、异步导入任务 + 进度。
- **缓存管理**：Caffeine（本地）+ Redis（分布式）两级缓存；缓存键规范与变更失效事件；穿透/击穿/雪崩防护（空值缓存、互斥重建、随机过期）。
- **系统监测**：Actuator 指标 → Prometheus 采集 → Grafana 看板；日志（结构化）+ 链路（Micrometer Tracing）；告警规则（内存/线程/慢 SQL/任务失败）。
- **文件存储（统一文件能力中心）**：全系统统一文件上传/下载入口 `FileApi`，业务模块只经 `FileApi` 引用文件、**不自行管理文件存储**；对象存储适配（MinIO/S3/本地盘），预签名 URL（带时效），文件权限（复用 security 数据权限）与审计（audit 留痕）；文件上传走 multipart/form-data、下载走二进制流（2.6.4 例外约定）。
- **对外**：`DictApi`、`FileApi`、`ParamApi`、`SchedulerApi`、`ExcelApi`、`CacheApi`、`MonitorApi`。

### 5.8 report（报表 BI）— 平台底座

**职责**：报表引擎、数据看板、集团汇总、多维分析、定时报表、数据导出。

**核心设计**：报表定义（数据集 SQL/API + 指标 + 维度）配置化；在线库报表走 PostgreSQL 只读；大查询/集团汇总走 ClickHouse 数仓；定时报表生成 + 推送（邮件/站内）；权限继承（数据权限过滤）；审计（报表访问留痕）。**对外**：`ReportApi`、`DashboardApi`、`ExportApi`。

### 5.9 ai（AI 能力）— 平台底座

**职责**：大模型网关、RAG 知识问答、OCR、智能填单/审单/风控、Agent 编排。

**核心设计**：
- **大模型网关**：Spring AI 适配多模型（企业自有/第三方，OpenAI 兼容）；Key 管理与成本控制、调用审计。
- **RAG**：文档切片 → 向量化（pgvector）→ 检索增强问答；知识库与 oa/知识库联动；权限过滤（按组织范围检索）。
- **OCR**：PaddleOCR/Tesseract 服务化封装，票据/合同/证件识别；智能填单（OCR + LLM 抽取 → 预填表单）。
- **智能审单/风控**：审批辅助（风险提示）、财务风控（异常识别），以"建议"形式呈现，人工最终决策。
- **Agent 编排**：多步任务编排（自然语言 → 计划 → 工具调用），受权限与审计约束。
- **对外**：`AiGatewayApi`、`RagApi`、`OcrApi`、`AgentApi`；模型调用入审计。

### 5.10 oa（OA 协同）— 通用业务

**职责**：待办中心、日程、会议、公告、文档、知识库、通讯录、站内消息。

**核心设计**：待办中心聚合各模块待办（经 approval/workflow 查询）；文档/知识库文件走 platform 文件存储，索引走 OpenSearch；知识库与 AI-RAG 联动；通讯录来自 org。**对外**：`TodoApi`、`DocApi`、`KbApi`、`NoticeApi`、`MessageApi`；发布 `DocUploadedEvent`。

### 5.11 crm（客户关系）— 通用业务

**职责**：线索、客户/联系人、商机、合同、电子签章、回款、跟进、客户360视图。

**核心设计**：客户/商机/合同数据主体在 crm，主数据（客户档案）与 mdm 协同（客户主数据统一）；合同审批走 workflow/approval；合同签署走电子签章（第三方 CA 适配）；签约事件驱动财务应收；客户 360 视图聚合 crm + finance + service + project 数据（应用层组装）。**对外**：`CustomerApi`、`OpportunityApi`、`ContractApi`、`Crm360Api`；发布 `ContractSignedEvent`。

### 5.12 inventory（库存管理）— 通用业务

**职责**：物料档案、出入库、库存台账、盘点、批次/序列号、预警、财务联动。

**核心设计**：库存余额表（仓库×物料×批次）与库存流水表分离，变动经 `InventoryApi` 事务化处理；出入库自动生成存货/成本凭证（调 finance VoucherApi，同事务）；批次/序列号/效期管理；安全库存预警（事件 + 定时任务扫描）。**对外**：`InventoryApi`、`StockApi`、`StocktakeApi`；发布 `InventoryChangedEvent`。

### 5.13 finance（财务管理）— 通用业务

**职责**：总账、应收应付、凭证、对账、成本核算、固定资产、发票税务、费用报销、预算、财务报表。

**核心设计**：凭证为财务核心，所有业务模块经 `VoucherApi` 自动生成凭证（同事务）；会计期间管理（开账/结账/反结账权限与审计）；科目主数据来自 mdm（集团统一）；应收应付与 crm/scm/fund 联动；发票税务与应收应付联动；预算控制（报销/采购执行时校验）；报表由 report 承载；全部财务关键操作入财务审计。**对外**：`AccountApi`、`VoucherApi`、`ArApApi`、`BudgetApi`、`ReimburseApi`；发布 `VoucherPostedEvent`。

### 5.14 hr（人力资源）— 通用业务

**职责**：考勤、薪酬、招聘、绩效（组织/员工在 org）。

**核心设计**：考勤（排班/打卡/请假/加班）与审批联动；薪酬核算（工资项规则、社保公积金）、工资条；招聘（职位/简历/面试/录用）；绩效（指标/考核/评分）。薪酬数据高敏感：字段权限 + 加密 + 审计。**对外**：`AttendanceApi`、`PayrollApi`、`RecruitApi`、`PerfApi`。

### 5.15 project（项目管理）— 通用业务

**职责**：项目立项、WBS、任务、进度、里程碑、资源、工时、看板。

**核心设计**：立项审批走 workflow；任务依赖与甘特图；工时填报审批（与 hr 考勤区分）；资源分配冲突检测；项目看板/报表由 report 承载。**对外**：`ProjectApi`、`TaskApi`、`TimesheetApi`。

### 5.16 scm（供应链）— 通用业务

**职责**：采购、供应商、到货验收、采购对账、采购审计。

**核心设计**：供应商主数据与 mdm 协同；采购申请→审批→询价→采购订单→到货（入库联动 inventory）→对账（应付联动 finance）；采购全链路留痕（采购审计）。**对外**：`PurchaseApi`、`SupplierApi`、`ReceiptApi`；发布 `GoodsReceivedEvent`。

### 5.17 marketing（营销管理）— 通用业务

**职责**：线索池、线索分配、营销活动、市场分析。

**核心设计**：线索池（公海/私有池）；线索分配规则（自动/手动、回收、防撞单）；营销活动（目标/预算/执行/效果）；市场分析与 crm 线索转化联动。**对外**：`LeadPoolApi`、`CampaignApi`。

### 5.18 service（售后客服）— 通用业务

**职责**：售后工单、服务台、服务跟踪、客服知识库。

**核心设计**：工单（创建/分类/派单/处理/回访/闭环）与客户/合同/产品关联；退换货联动库存；多渠道客服接入（电话/在线/邮件）；客服知识库与 AI 智能回复联动。**对外**：`TicketApi`、`ServiceDeskApi`。

### 5.19 fund（资金管理）— 通用业务

**职责**：银行账户、资金收付款、现金流、资金调拨。

**核心设计**：银行账户主数据与 mdm 协同；收付款登记审批后联动 finance（应收核销/应付核销）与总账；现金流分析与资金计划；集团内资金调拨/归集（母子公司内部往来）。**对外**：`BankAccountApi`、`PaymentApi`、`TransferApi`；发布 `PaymentCompletedEvent`。

### 5.20 portal（门户与消息）— 平台支撑

**职责**：统一工作台、消息中心、通知推送、个人中心。

**核心设计**：门户框架（菜单/快捷入口/待办聚合，聚合 approval 与各模块）；消息中心（分类/已读回执/@提醒）；通知推送渠道适配（邮件/短信/企微/钉钉）；个人中心（资料/密码/偏好）。**对外**：`PortalApi`、`NotifyApi`。

### 5.21 integration（开放平台与集成）— 平台支撑

**职责**：API 网关、OpenAPI、Webhook、第三方集成、数据迁移、**跨系统主数据分发**。

**核心设计**：统一 API 网关（鉴权/限流/日志，基于 Spring Cloud Gateway 能力内嵌或自研过滤器链）；OpenAPI 文档与版本管理；Webhook（签名、重试、幂等）；第三方适配器（企微/钉钉/邮件/短信/外部系统）；数据迁移工具；**主数据对外分发**（监听 `MasterDataChangedEvent`，跨系统同步 + 监控）。**对外**：`GatewayApi`、`WebhookApi`、`MigrationApi`、`MdSyncApi`。

### 5.22 mobile（移动端）— 平台支撑

**职责**：移动门户、移动审批、移动业务、移动消息、移动安全。

**核心设计**：H5/小程序方案，复用 Web API；移动审批（意见/附件/加签转签）；扫码（库存/入库）；签到打卡；设备绑定、远程注销、数据防泄露（截屏/复制控制）。**对外**：`MobileApi`（网关侧统一入口）。

### 5.23 i18n（多语言与多币种）— 平台支撑

**职责**：界面多语言、币种主数据、汇率、多币种财务。

**核心设计**：语言包（数据库驱动 + 前端动态加载）；币种主数据（与 mdm 协同）、汇率管理；多币种财务（外币凭证、汇兑损益、多币种报表——finance 协作）。**对外**：`I18nApi`、`CurrencyApi`、`ExchangeRateApi`。

---

## 6. 运行设计

### 6.1 运行模块组合

| 运行模式 | 启用的模块 | 场景 |
|----------|------------|------|
| 全量模式 | 全部 22 模块 | 集团企业一体化部署 |
| 精简模式 | security + org + platform + audit + workflow + approval + oa + portal | 轻量协同起步（MVP） |
| 业务裁剪 | 全量 −（按企业配置关闭模块） | 按需启用（模块可插拔，NFR-EXT-01） |

### 6.2 运行控制

- **启动顺序**：数据库迁移（Flyway）→ 应用启动 → 模块初始化（字典/参数/默认角色/流程模板种子）→ 健康检查（Actuator `/health`）→ 对外就绪。
- **生命周期**：优雅停机（等待进行中事务与任务完成）；配置热更新（参数中心，非关键配置无需重启）。
- **并发控制**：Tomcat 线程池调优；写接口乐观锁（version）；定时任务 ShedLock。

### 6.3 定时任务设计

| 任务 | 周期 | 说明 |
|------|------|------|
| 库存预警扫描 | 每日/每小时 | 安全库存、临期预警生成通知 |
| 应收/应付逾期提醒 | 每日 | 逾期单据提醒 |
| 财务对账任务 | 每日 | 银行对账、内部往来对账 |
| 账实相符核对 | 定期（月） | 账面与实物核对、差异报告 |
| 审计日志归档 | 定期 | 分区归档、哈希校验根生成 |
| 数仓同步校验 | 每日 | 在线库 ↔ ClickHouse 对账 |
| 定时报表 | 按报表配置 | 生成并推送 |
| 会话/令牌清理 | 每小时 | 过期清理 |
| Webhook 重试 | 每 5 分钟 | 失败重试队列 |

---

## 7. 出错处理设计

### 7.1 错误处理机制

- **统一异常模型**：`BusinessException`（业务错误码 + 消息 + 提示级别）、`SystemException`；全局异常处理器输出统一 JSON（`{code, message, traceId}`），不泄漏堆栈与敏感信息。
- **错误码规范**：模块前缀（如 `CRM-1001`）+ 递增；文档化维护错误码表。
- **事务回滚**：业务异常自动回滚；补偿场景（异步副作用失败）走重试 + 死信 + 人工处理。

### 7.2 出错信息

| 场景 | 用户可见提示 | 系统处理 |
|------|--------------|----------|
| 业务校验失败 | 明确中文提示 + 错误码 | 回滚，记录审计（失败结果） |
| 并发冲突（乐观锁） | "数据已被他人修改，请刷新后重试" | 返回新版本数据引导重试 |
| 幂等重复提交 | "请求已提交，请勿重复操作" | 返回原结果 |
| 外部依赖失败（AI/短信/签章） | 降级提示 | 重试队列 + 告警 |
| 审计写入失败 | 阻断关键操作（财务/权限） | 强审计场景失败即拒绝 |

### 7.3 故障恢复与备份

- **备份**：PostgreSQL 定时全量 + WAL 归档（PITR）；Redis 持久化（AOF）；对象存储跨区冗余。
- **恢复演练**：定期恢复演练，RTO/RPO 目标在运维手册定义（建议 RPO ≤ 24h、RTO ≤ 4h 起步）。
- **审计日志**：独立备份，哈希校验根定期验证，防篡改可举证。
- **多实例容灾**：应用多实例 + 负载均衡；数据库主从；可跨机房部署（规划）。

---

## 8. 安全保密设计

### 8.1 认证设计

- 密码策略（强度/有效期/失败锁定）；多因素认证（MFA，可选）；SSO（SAML2/OIDC）、LDAP 对接；令牌（JWT 短期 + Redis 可撤销）；设备绑定（移动端）。

### 8.2 授权设计（母子公司）

- 无限级组织树 + 数据隔离（行级数据权限，按组织/部门/责任人过滤）；集团统一管控/子公司自治可配；角色继承与覆盖；ABAC 补充；字段权限；SoD 互斥校验；权限变更全量审计。
- **多组织上下文**：请求级 `TenantCtx`（当前公司/组织），所有数据访问强制携带并过滤。

### 8.3 数据安全

- 传输加密（HTTPS/TLS）；敏感字段加密存储（薪酬、证件、银行账号，AES-GCM，密钥管理服务）；脱敏展示（手机号/证件/账号）；数据分类分级（见 SRS 6.1）；导出水印与管控；对象存储访问控制（预签名 + 时效）。

### 8.4 审计安全

- 双审计体系全量留痕；审计日志 WORM（只追加、哈希链、定期校验）；审计数据独立存储与备份；审计查询权限分级（母公司全集团、子公司本组织）。

### 8.5 等保三级对照（目标）

| 等保要求 | 设计落地 |
|----------|----------|
| 身份鉴别 | 强密码 + MFA + 登录失败锁定 + 双因素 |
| 访问控制 | RBAC/ABAC + 数据权限 + SoD + 最小权限 |
| 安全审计 | 双审计全量留痕 + 防篡改 + 留存 |
| 入侵防范 | OWASP 防护（防注入/XSS/CSRF）、安全组件版本治理 |
| 数据完整性/保密性 | 传输加密 + 存储加密 + 备份 |
| 可信验证/恢复 | 备份恢复演练、容灾规划 |

---

## 9. 部署与运维设计

### 9.1 部署拓扑

- **单机（MVP/小企业）**：Docker Compose：`e-aio-app` + `postgres` + `redis` + `opensearch`（可选）+ `clickhouse`（可选）。
- **标准（中大型集团）**：应用多实例 + Nginx/LB；PostgreSQL 主从；Redis Sentinel/Cluster；中间件独立节点。
- **信创**：适配麒麟/统信 OS、国产 CPU（ARM/飞腾/鲲鹏，规划）、国产数据库（达梦/人大金仓，通过数据访问层适配）、国产中间件。

### 9.2 容器化与编排

- 官方 Dockerfile（多阶段构建：编译 → 精简运行镜像）；docker-compose 一键启动；Kubernetes Helm Chart（规划）。
- 配置外部化：环境变量 / 配置中心（Nacos 或 Spring Cloud Config，开源），敏感配置加密。

### 9.3 配置管理

- 分层配置：默认配置（代码内）→ 环境配置（application-{env}.yml）→ 运行时参数（参数中心，DB 驱动，热更新）。

### 9.4 监控告警

- 指标：Actuator + Micrometer → Prometheus → Grafana（JVM、线程池、QPS、RT、慢 SQL、任务执行）。
- 日志：结构化日志（JSON）+ 集中收集（Loki/ELK 选型）；链路：Micrometer Tracing（OpenTelemetry）。
- 告警：系统资源、服务健康、错误率、任务失败、审计异常；告警渠道（邮件/企微）。

---

## 10. 质量与架构治理

### 10.1 ArchUnit 架构约束

- 模块依赖规则（禁止业务模块被通用能力依赖；禁止循环依赖）；`internal` 包访问禁止；跨 Schema 数据访问禁止（代码评审 + 静态检查）。
- 在 CI 中执行 `ApplicationModules.verify()` 与自定义规则集。

### 10.2 测试策略

| 层级 | 范围 | 工具 |
|------|------|------|
| 单元测试 | 领域逻辑、规则引擎 | JUnit 5 + AssertJ |
| 模块集成测试 | 模块公共 API、事务边界 | @SpringBootTest + Testcontainers |
| 契约测试 | 跨模块接口 DTO | Spring Cloud Contract（或等价方案） |
| E2E | 关键业务链路（合同→应收、出库→凭证、母子公司审批） | Playwright + Testcontainers |
| 架构测试 | Modulith 边界、依赖方向 | ArchUnit |
| 性能测试 | 2,000 并发、列表查询、导入导出 | JMeter / Gatling |

### 10.3 CI/CD

- GitHub Actions：编译 → 单元测试 → 架构测试 → 集成测试 → 安全扫描（依赖漏洞/license）→ 镜像构建 → 发布。
- PR 质量门：全部通过方可合并（NFR-OSS-03）；贡献指南与行为准则（CONTRIBUTING、CODE_OF_CONDUCT）。

---

## 11. 附录

### 11.1 模块依赖矩阵（概览）

> 说明：所有模块均依赖 **common**（技术底座：Excel / Redis / 通用工具），矩阵中不再重复列出。

| 模块 | 主要依赖（→） | 被依赖（←，主要） |
|------|----------------|-------------------|
| common | —（技术底座，无模块依赖） | 全部模块 |
| security | org | 全部模块 |
| org | platform | security、hr、oa |
| audit | security、org、platform | 全部模块 |
| workflow / approval | security、audit、platform、org | 全部业务模块 |
| mdm | security、org、audit、workflow | crm、inventory、finance、scm、fund |
| platform | — | 全部模块 |
| report | security、audit | 全部模块 |
| ai | security、audit、platform | oa、crm、service 等 |
| oa | security、org、audit、platform、ai（可选渐进） | portal |
| crm | security、mdm、workflow、audit、finance、platform | marketing、service |
| inventory | security、mdm、audit、finance | scm、crm、service |
| finance | security、mdm、audit、workflow、fund、platform | crm、inventory、scm、hr、fund |
| hr | security、org、audit、finance | — |
| project | security、org、audit、workflow | — |
| scm | security、mdm、audit、workflow、inventory、finance | — |
| marketing | security、crm、audit | — |
| service | security、crm、inventory、ai | — |
| fund | security、mdm、finance、audit、workflow | finance、crm |
| portal | security、approval、oa、platform | 全部模块 |
| integration | security、mdm、audit、platform | 外部系统 |
| mobile | security、approval、portal、inventory | 移动端 |
| i18n | security、mdm、platform | 全部模块 |

### 11.2 核心事件清单（补充）

| 事件 | 载荷（概要） | 消费方 | 事务性 |
|------|--------------|--------|--------|
| MasterDataChangedEvent | 类型、ID、操作、组织 | 各模块缓存、integration、report、ai | AFTER_COMMIT |
| ContractSignedEvent | 合同ID、客户ID、金额、公司 | finance、report | AFTER_COMMIT |
| GoodsReceivedEvent | 到货单ID、物料、数量 | inventory、finance | AFTER_COMMIT |
| InventoryChangedEvent | 物料、仓库、变动量 | report、ai | AFTER_COMMIT |
| VoucherPostedEvent | 凭证ID、期间、科目 | audit、report | AFTER_COMMIT |
| ApprovalCompletedEvent | 实例ID、结果、业务类型/ID | 发起模块 | AFTER_COMMIT |
| PaymentCompletedEvent | 收付款单ID、金额 | finance、crm | AFTER_COMMIT |
| DocUploadedEvent | 文档ID、组织 | ai | AFTER_COMMIT |

### 11.3 主要技术组件清单（开源优先）

| 组件 | 用途 | 许可证（拟） |
|------|------|--------------|
| Spring Boot 4.1.x / Spring Modulith 2.1.x | 应用框架 / 模块化 | Apache-2.0 |
| Spring Security + OAuth2 | 认证授权 | Apache-2.0 |
| PostgreSQL + pgvector | 主库 / 向量 | PostgreSQL |
| Redis | 缓存 / 会话 / 锁 | BSD-3 |
| Lombok | 编译期样板代码生成（POJO / 日志） | MIT |
| Hutool | Java 工具库底座（字符串/日期/集合/加密/Excel 等） | MPL-2.0 |
| Bean Validation（Hibernate Validator） | 参数校验标准 + 参考实现 | Apache-2.0 |
| MapStruct | DTO / 实体映射（编译期） | Apache-2.0 |
| MyBatis-Plus | 持久层（承接 RuoYi 蓝本） | Apache-2.0 |
| HikariCP / Druid | 数据库连接池 | Apache-2.0 |
| Vue3 + Vite + Element Plus | 前端工程（RuoYi-Vue3 蓝本） | MIT |
| jjwt / Nimbus JOSE | JWT 令牌 | Apache-2.0 |
| OpenSearch | 全文检索 | Apache-2.0 |
| ClickHouse | 数仓 / BI | Apache-2.0 |
| Flowable | 工作流引擎 | Apache-2.0 |
| Apache Fesod（原 FastExcel / EasyExcel 生态，Apache 孵化） | Excel 导入导出（高性能流式读写） | Apache-2.0 |
| PaddleOCR / Tesseract | OCR | Apache-2.0 |
| Quartz / ShedLock | 定时任务 / 分布式锁 | Apache-2.0 |
| Prometheus / Grafana | 监控告警 | Apache-2.0 |
| Spring AI / LangChain4j | AI 网关 / Agent | Apache-2.0 |
| MinIO | 对象存储 | AGPL-3.0（可换 S3 兼容替代） |
| Flyway | 数据库迁移 | Apache-2.0 |
| ArchUnit / Testcontainers | 架构测试 / 集成测试 | Apache-2.0 |
| Maven / GitHub Actions | 构建 / CI | Apache-2.0 |

> **许可证注意**：MinIO 为 AGPL-3.0，若企业介意可替换为 S3 兼容的开源实现（如 SeaweedFS、Ceph RGW，均 AGPL/Apache 可选）；整体选型以 Apache-2.0 友好为主，规避 GPL 传染性组件（NFR-OSS-02）。

---

## 12. 待详细设计阶段细化事项

1. 各模块表结构 DDL 与索引设计（详细设计）；
2. 模块公共 API 接口签名与 DTO 定义（契约先行）；
3. 数据权限过滤器实现细节（SQL 改写方案）；
4. 审计哈希链的具体实现与校验工具；
5. Flowable 与动态表单绑定的实现方案；
6. 事件总线可靠性（重试、死信、幂等）具体实现；
7. 信创数据库适配层（方言抽象）方案。

---

## 13. 模块开发顺序与实施计划

### 13.1 开发顺序总原则

1. **依赖前置（拓扑排序）**：按 11.1 模块依赖矩阵推导开发次序——无依赖者先行，被依赖者先于依赖者落地，顺序为 **工程骨架（RuoYi 蓝本初始化）→ common → 平台底座 → 通用业务 → 平台支撑**。
2. **契约先行**：被依赖模块先定义公共 API（接口 + DTO + 事件契约）并冻结版本，实现可与依赖方并行推进；跨模块联调以契约为准。
3. **关键链路优先**：母子公司权限、双审计、主数据三大引擎最先成型，决定全局架构正确性，后续模块全部复用。
4. **MVP 闭环**：9 个月内交付"平台底座 + OA + 审批 + 工作流 + 操作审计"可运行 MVP，供种子企业验证后再铺开业务模块。
5. **可插拔演进**：每个模块独立开发/测试/打包，随时可按 NFR-EXT-01 裁剪，不影响主线交付。

### 13.2 开发队列（批次与顺序）

| 批次 | 顺序 | 模块 | 前置依赖（来自 11.1） | 里程碑 |
|------|------|------|----------------------|--------|
| P0 工程地基 | 1 | 工程骨架（以 RuoYi-Vue 为蓝本初始化：Modulith 命名空间 + ArchUnit 质量门 + Flyway + CI 流水线 + 统一异常/错误码） | 无（RuoYi 蓝本工程初始化） | M0–M1 |
| P0 工程地基 | 2 | common（技术底座：ExcelKit/RedisKit/通用工具/统一返回体；迁移 ruoyi-common 工具集） | 工程骨架（模块载体，非业务依赖） | M0–M1 |
| P1 平台底座 | 3 | platform（参数/字典/文件/定时任务/Excel/缓存/监测） | common | M1–M3 |
| P1 平台底座 | 4 | org（组织与用户） | platform | M2–M4 |
| P1 平台底座 | 5 | security（认证授权与权限引擎：母子公司） | org | M3–M5 |
| P1 平台底座 | 6 | audit（操作审计，WORM 防篡改） | security、org、platform | M4–M6 |
| P1 平台底座 | 7 | workflow + approval（工作流与统一审批中心） | security、audit、platform、org | M5–M8 |
| P1 平台底座 | 8 | mdm（主数据管理） | security、org、audit、workflow | M6–M8 |
| P1 平台底座 | 9 | oa（OA 协同，首个业务模块验证） | security、org、audit、platform（ai 可选渐进） | M7–M9 |
| P1 平台底座 | 10 | portal（门户收口：菜单/待办聚合/消息） | security、approval、oa、platform | M8–M9 |
| P2 业务闭环 | 11 | report（报表 BI） | security、audit | M9–M12 |
| P2 业务闭环 | 12 | project（项目管理） | security、org、audit、workflow | M9–M12 |
| P2 业务闭环 | 13 | finance（财务，与 fund 契约先行） | security、mdm、audit、workflow、fund（契约）、platform | M10–M14 |
| P2 业务闭环 | 14 | fund（资金管理，与 finance 契约并行） | security、mdm、finance（契约）、audit、workflow | M11–M14 |
| P2 业务闭环 | 15 | inventory（库存） | security、mdm、audit、finance | M12–M16 |
| P2 业务闭环 | 16 | crm（客户关系） | security、mdm、workflow、audit、finance、platform | M12–M17 |
| P2 业务闭环 | 17 | hr（人力资源） | security、org、audit、finance | M13–M18 |
| P2 业务闭环 | 18 | scm（供应链） | security、mdm、audit、workflow、inventory、finance | M14–M20 |
| P2 业务闭环 | 19 | marketing（营销） | security、crm、audit | M16–M21 |
| P2 业务闭环 | 20 | ai（AI 增强：RAG/OCR/智能审单，契约先行供 service） | security、audit、platform | M15–M22 |
| P2 业务闭环 | 21 | service（售后客服） | security、crm、inventory、ai | M17–M23 |
| P3 平台化开放 | 22 | integration（开放平台：API 网关/OpenAPI/Webhook/主数据分发） | security、mdm、audit、platform | M24–M28 |
| P3 平台化开放 | 23 | mobile（移动端 H5/小程序） | security、approval、portal、inventory | M24–M30 |
| P3 平台化开放 | 24 | i18n（多语言多币种） | security、mdm、platform | M26–M32 |
| P3 平台化开放 | 25 | 行业业务模块（按企业定制） | 通用能力层全部 | M28–M36 |

> **说明**：finance ↔ fund 存在接口级相互依赖（凭证↔收付款核销），按"契约先行"处理——先冻结 `VoucherApi` / `PaymentApi` 契约，双方并行实现，运行时经公共 API 调用，不构成包级循环。

> **说明**：oa 对 ai 为**可选渐进依赖**——AI PoC 于阶段 0（M0–M2）验证，ai 完整模块在 P2（顺序 20）落地；MVP 阶段 oa 可无 AI 运行，接口层预留 ai 调用位，P2 后渐进接入（RAG / OCR / 智能审单）。

> **说明**：行业业务模块（顺序 25）为**扩展模块**，不在 11.1 固定依赖矩阵内，依赖通用能力层全部，按企业定制落地。

#### 13.2.1 RuoYi 蓝本改造落点（对应 2.6.1 模块映射）

| 批次 | 顺序 | 改造内容（RuoYi 模块 → e-aio 模块） | 说明 |
|------|------|--------------------------------------|------|
| P0 | 1 工程骨架 | ruoyi-framework（基础部分）→ 工程骨架 | 以 RuoYi-Vue（前后端分离版）为蓝本搭建：Maven 多模块改造为 Modulith 命名空间、依赖基线（Spring Boot 4.x）、统一异常与错误码、CI 质量门；前端 RuoYi-Vue3 工程同步初始化 |
| P0 | 2 common | ruoyi-common → common | 迁移 ruoyi-common 工具集：AjaxResult/BaseEntity → Result/PageResult、核心工具（StringUtils/DateUtils/JsonUtils/树形/脱敏/雪花ID）、Redis 工具、安全工具、注解基类；改造为 e-aio 薄封装门面 |
| P1 | 3 platform | ruoyi-system（字典/参数/公告）+ ruoyi-quartz → platform | 字典/参数/公告迁移并扩展分级配置；Quartz → Spring Task + ShedLock（Quartz 可选保留） |
| P1 | 4 org | ruoyi-system（sys_user 用户/部门/岗位）→ org | 用户主档、部门/岗位迁移；组织树升级为无限级（集团-子公司-部门） |
| P1 | 5 security | ruoyi-framework（SecurityConfig/JWT/拦截器）+ ruoyi-system（角色/菜单/权限）→ security | 认证授权迁移；扩展母子公司多级组织权限（组织树/数据权限/SoD） |
| P1 | 6 audit | ruoyi-system（操作日志/登录日志）→ audit | 日志 AOP 迁移；升级 WORM 防篡改 + 财务审计双体系 |
| P1 | 7–8 | workflow / mdm | RuoYi 无直接对应，全新开发；复用 RuoYi 工具类与工程规范 |
| P1 | 10 portal | ruoyi-ui（RuoYi-Vue3 前端工程）→ portal + 前端工程 | 保留菜单/路由/权限指令/字典/水印组件；扩展配置化渲染与移动端 H5 |
| 贯穿 | — | ruoyi-generator → devtools | 保留代码生成器为开发工具链，服务 Vibe Coding（不入运行时） |

### 13.3 批次验收标准

| 批次 | 验收标准 |
|------|----------|
| P0 | 工程骨架 CI 绿灯、ArchUnit 验证通过、Flyway 迁移可运行；common 全部工具单测通过 |
| P1 | 母子公司权限 E2E（组织树/数据权限/SoD/跨公司审批）通过；操作审计 WORM + 哈希链校验验证；工作流+审批全链路跑通；MVP 种子企业试用反馈 |
| P2 | 业务闭环 E2E：合同→应收、出库→凭证、采购→入库→对账；财务审计（账实相符/四流合一）通过；2,000 并发性能测试达标 |
| P3 | OpenAPI 对外集成联调通过；移动端 H5 上线；多语言多币种切换验证；行业模块模板沉淀 ≥ 1 套 |

### 13.4 并行开发与契约先行

- **链内串行、链间并行**：P1 平台底座为链式（platform→org→security→audit→workflow→mdm），相邻模块契约先行后实现可重叠；P2 中 report/project/ai 与 finance 链并行；P3 三个平台支撑模块互不依赖可并行。
- **契约冻结**：模块公共 API 由架构师与模块负责人共同评审后冻结 V1，进入开发队列；契约变更走评审，避免联调返工。
- **节奏**：单模块 2–4 周；核心维护组 5–8 人 + 领域贡献者并行；每批次结束做架构评审（ArchUnit + 契约 + 质量门）。

### 13.5 里程碑映射

| 项目里程碑（可研 7.1） | 开发队列 | 交付 |
|--------------------------|----------|------|
| 阶段 0（M0–M2）技术预研 | P0 + P1 启动 | Modulith 骨架、配置化定制 PoC、AI PoC、立项评审 |
| 阶段 1（M2–M9）MVP | P0 + P1 完成（顺序 1–10） | 平台底座 + OA + 审批 + 权限 + 工作流 + 操作审计 MVP |
| 阶段 2（M9–M24）业务闭环 | P2 完成（顺序 11–21） | CRM/库存/财务（含财务审计）/HRM/SCM/报表，商机→合同→履约→回款闭环 |
| 阶段 3（M24–M36）平台化 | P3 完成（顺序 22–25） | 开放平台/移动端/多语言多币种，行业解决方案与社区生态 |

*本概要设计说明书为 V1.0 草案，基于可行性研究报告与 SRS 编制；详细设计阶段将逐模块细化并保持与本文档的一致性与可追溯性。*
