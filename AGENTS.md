# AGENTS.md — e-aio 协作开发指南（AI 编码代理与人类开发者通用）

> 本文件是 AI 编码代理（Claude Code / Cursor / Codex / 豆包等）与人类贡献者进入 e-aio 仓库的**第一份必读文档**。
> 目标：让任何代理在**不读完全部文档**的情况下，也能按项目既定架构、约定与质量门正确产出代码。
> 规则冲突时优先级：本文件 > 03 概要设计 > 02 需求规格 > 01 可研；代码评审以本文件为准。

---

## 1. 项目一句话

**e-aio**（企业级一体化管理系统）：把 OA / CRM / 库存 / 财务 / HR / 项目 / 供应链 / 工作流 / 审批 / 权限 / 审计 / 报表 BI / AI / 门户消息等**企业通用能力大一统**到一套**全开源、自托管（Self-hosted）、非 SaaS** 的系统中；具体业务逻辑按企业需求**放在单独业务模块中，单向调用系统通用能力**定制开发。

**三个不可动摇的原则**：
1. **非 SaaS**：不收取订阅/授权/使用费，企业数据完全自主。
2. **全开源**：全部代码开源（e-aio 自身 Apache-2.0），**接受社区 PR**。
3. **模块化单体**：**不用微服务**，采用 Spring Modulith 模块化单体，纯 Vibe Coding 开发。

---

## 2. 仓库结构（必须遵守）

```
e-aio/
├── backend/
│   └── e-aio/                  # 后端工程根（父 POM 所在，Maven 多模块）
│       ├── pom.xml             # 父 POM（依赖管理 BOM + 模块清单）
│       ├── e-aio-app/          # 启动模块（Spring Boot 可执行 Jar）
│       ├── e-aio-common/       # common 技术底座（顺序 2）
│       └── e-aio-<module>/     # P1–P3 按开发队列新增（platform/org/security/oa/...）
├── frontend/                   # 前端独立工程（Vue3 + Vite + Element Plus）
│   ├── src/
│   │   ├── api/                # 按模块 API 封装（统一 POST + JSON）
│   │   ├── utils/request.js    # axios 统一封装（响应拦截 + 幂等键头）
│   │   └── views/              # 页面（P1 起按模块挂载）
│   └── vite.config.js          # 开发代理 → 后端；生产独立部署
├── docs/                       # 软件工程文档（见 §9）
├── .agents/skills/             # Vibe Coding 工具链（本地使用，不入库，.gitignore 排除）
├── .github/workflows/ci.yml    # CI 流水线
├── AGENTS.md                   # 本文件
└── README.md
```

**硬约束**：
- 后端所有 Maven 模块必须位于 `backend/e-aio/` 下，artifactId 前缀 `e-aio-`。
- 新增模块 = 复制 P0 骨架模板 + 父 POM 登记 + 注册独立 Schema，**禁止在 backend/ 根下平铺模块**。
- `frontend/` 为独立 npm 工程，不参与 Maven 构建；后端只对其暴露 API 契约。
- `docs/` 文档按"序号-项目名"命名，数据库设计**不单独成文档**，并入各详细设计分册。

---

## 3. 快速开始与常用命令

### 环境要求

| 依赖 | 版本 | 说明 |
|------|------|------|
| JDK | 21（LTS） | 后端运行时 |
| Maven | 3.9+ | 后端构建 |
| Node.js | 20+ LTS | 前端构建 |
| PostgreSQL | 16+ | 主库（每模块独立 Schema） |
| Redis | 7+ | 缓存/锁/限流/幂等键 |

### 命令

```bash
# 后端（所有命令在 backend/e-aio/ 下执行）
mvn -B compile                    # 编译
mvn -B test                      # 单元测试 + ArchUnit 架构测试
mvn -B verify -DskipITs          # 全量验证（Modulith verify）
mvn -pl e-aio-common -am clean compile   # 仅构建某模块
mvn spring-boot:run              # 本地启动（默认 8080）

# 前端（在 frontend/ 下执行）
npm install
npm run dev                      # 开发（Vite 代理 → 后端）
npm run build                    # 生产构建

# 本地测试数据库（Docker）
docker compose up -d postgres redis
```

---

## 4. 技术栈（开源优先，已冻结）

| 层 | 选型 |
|----|------|
| 语言/框架 | Java 21 + **Spring Boot 4.1.x + Spring Modulith 2.1.x**（配套版本，见父 POM） |
| 工具库 | Lombok / Hutool / Bean Validation / MapStruct（common 门面封装） |
| Excel | **Apache Fesod**（https://fesod.apache.org/zh-cn/docs/ ，Apache-2.0），经 `ExcelKit` 门面 |
| 缓存/锁 | Redis（Redisson 底层），经 `RedisKit` 门面 |
| 定时任务 | Quartz / XXL-JOB 或 Spring Task + ShedLock（P1 定） |
| 持久层 | MyBatis-Plus（Boot 4 对应 starter，P1 落地时确认版本） |
| 数据库迁移 | Flyway 多 Schema |
| 工作流 | Flowable（Apache-2.0）二次封装 |
| 搜索/BI | OpenSearch/Elasticsearch；ClickHouse/StarRocks |
| 系统监测 | Actuator + Prometheus + Grafana |
| OCR | PaddleOCR / Tesseract |
| AI | Spring AI / LangChain4j / LiteLLM 网关 + RAG + pgvector |
| 权限 | Spring Security + 自研多级组织权限引擎 |
| API | RESTful 风格，**统一 POST + JSON**（含查询/删除/导出） |

**规则**：能用开源就用开源；业务模块**禁止直接散用第三方工具类**，一律走 common 门面（`ExcelKit` / `RedisKit` / `DateUtils` / `JsonUtils` …）。

---

## 5. 架构约束（ArchUnit 强制，违反即 CI 失败）

### 5.1 模块化单体（非微服务）

- 单一可执行 Jar（`e-aio-app`），模块间通过 Spring Modulith 边界交互。
- 每模块独立 Schema（`eaio_<module>`），跨模块数据**只能经应用层接口访问，禁止跨 Schema SQL**。
- 模块间通过 Spring 应用事件异步解耦（如"合同创建"→触发"应收生成"）。
- 模块可独立演进，未来必要时可平滑拆分（但**当前不拆**）。

### 5.2 命名空间与分包（硬约束）

模块根包：`com.eaio.<module>`。模块内部**五层分包**：

```
com.eaio.<module>
├── api            # 对外公共接口（Spring 服务接口 + DTO），其他模块仅可依赖此包
├── application    # 应用服务：用例编排、事务边界、事件发布
├── domain         # 领域模型：实体、值对象、领域服务、仓储接口
├── infrastructure # 基础设施：持久层实现、外部适配、缓存实现
└── events         # 领域事件定义
```

**强制规则**（ArchUnit 固化）：
- `domain` / `infrastructure` / `internal` 包**不得**被其他模块引用；
- 跨模块仅可依赖 `api` 包；
- **依赖单向**：业务模块 → 通用能力（workflow/security/audit/report/ai/...），**禁止反向依赖**；
- 禁止循环依赖。

### 5.3 主数据（MDM）

模块化单体内，主数据采用**共享访问、不分发订阅**：各模块直接通过 MDM 的 `api` 包接口读取主数据，不搞消息分发/订阅复制。变更通过 `MasterDataChangedEvent` 通知缓存失效。

### 5.4 权限（母子公司）

- 集团 - 子公司**无限级组织树**；子公司数据隔离（数据权限）、集团统一管控/子公司自治可配。
- RBAC + ABAC 混合；角色继承与覆盖；跨公司审批与审计；职责分离（SoD）。
- 上下文：`TenantCtx`（多组织上下文，P1 落地）承载当前用户所属组织。

### 5.5 审计（双维度）

- **系统操作审计**：全操作留痕、日志防篡改、全场景溯源（`@AuditLog` 切面，P1 接入）。
- **财务专项审计**：凭证/对账/报表可追溯，合规级输出。

---

## 6. API 与编码规范（评审红线）

### 6.1 API 规约（HLD 2.6.4，已冻结）

- **全部接口使用 POST + JSON**（包括查询、删除、导出）；HTTP 状态码恒为 200，业务结果由 `code` 表达。
- **具体动作体现在接口名中**：`Get`（查询单条）/ `GetPage`（分页查询）/ `Add`（新增）/ `Up`（更新）/ `Del`（删除，逻辑删除）+ 业务动作词（如 `Submit`、`Approve`、`Export`）。
- 例：`POST /api/crm/customer/GetPage`、`POST /api/crm/customer/Add`。
- 文件上传走 `multipart/form-data`（`FileApi/Upload`），下载走二进制流（`FileApi/Download`），归 **platform 模块的 FileApi** 统一提供。
- 分页参数统一入 JSON body（`pageNum` / `pageSize` / 排序字段）。

### 6.2 契约（common，冻结 V1）

- 返回体：`Result<T>`（`code`/`message`/`data`/`traceId`）；分页：`Result<PageResult<T>>`。
- 错误码：`ErrorCode` 枚举。`0` 成功；通用段 `10000–19999`（10000 参数校验失败、10002 数据不存在、10401 未认证、10403 无权限、10500 系统错误、10501 重复提交）；业务段 `20000+` 按模块前缀分配（附录维护）。
- 异常：业务抛 `BusinessException(ErrorCode)`；系统异常走 `SystemException`，**不向客户端泄漏堆栈**。
- `traceId`：请求头 `X-Trace-Id` 透传/生成，写入 MDC，`Result.traceId` 自动回填。

### 6.3 幂等键（写接口强制）

- 写接口（Add/Up/Del 及业务动作）前端携带 `Idempotency-Key` 请求头；后端 `IdempotencyFilter` 用 Redis SETNX 占位（键 `eaio:{env}:idem:{sha256(URL+key)}`，TTL 24h），重复请求返回 `10501 重复提交`。
- 查询接口不启用；幂等键缺失时放行并记 WARN。

### 6.4 Java 编码规范

| 项 | 约定 |
|----|------|
| 依赖注入 | **构造器注入**（`@RequiredArgsConstructor`），禁止字段注入 |
| Lombok | `@Data`（简单 DTO）/ `@Builder`（不可变 DTO）/ `@Slf4j` |
| Bean Validation | Controller 入参 `@Validated`；自定义约束 `@EnumValid`/`@Mobile`/`@IdCard`/`@Money`（common.validation） |
| DTO 映射 | 跨模块契约转换一律 **MapStruct**（`componentModel="spring"`、`unmappedTargetPolicy=ERROR`）；低频用 `BeanUtils` |
| ID 主键 | `IdGenerator`（雪花，long）统一生成 |
| JSON | 统一 `JsonUtils`（Jackson），禁止业务模块自建 ObjectMapper |
| 敏感数据 | 存储原文，展示层经 `@Sensitive` 脱敏（手机/证件/银行卡/邮箱） |
| 日志 | 结构化 JSON 日志（ts/level/traceId/module/msg）；禁止打印敏感字段 |

### 6.5 前端规范

- 所有请求走 `src/utils/request.js` 统一封装（POST + JSON、响应拦截、401/403 跳登录）。
- API 文件按模块组织：`src/api/<module>/<resource>.js`，按动作词导出 `get/getPage/add/up/del`。
- 保留 RuoYi-Vue3 成熟能力（动态路由/权限指令 `v-hasPermi`/字典/水印），不重复造轮子。

---

## 7. 数据库约定

- 每模块独立 Schema（`eaio_<module>`），Flyway 脚本位置 `classpath:db/migration/<module>/`，命名 `V<版本>__<描述>.sql`。
- 表主键统一雪花 long；**逻辑删除**优先；审计字段（`create_by/create_time/update_by/update_time`）统一基类。
- **禁止跨 Schema 关联查询与写操作**；跨模块数据经 API 获取。
- 数据库设计随各详细设计分册（"数据结构设计"章节）维护，不单独成文档。

---

## 8. 质量门与测试（PR 必须全绿）

| 门 | 内容 | 失败即阻断 |
|----|------|-----------|
| 编译 | `mvn -B compile` | ✅ |
| 单元测试 | `mvn -B test`（common 工具核心覆盖率 100%，整体 ≥ 80%） | ✅ |
| 架构测试 | ArchUnit（模块边界/依赖单向/五层分包）+ `ApplicationModules.verify()` | ✅ |
| 集成测试 | Testcontainers（PostgreSQL + Redis）跑 `@SpringBootTest`、Flyway 空库迁移 | ✅ |
| 安全扫描 | OWASP Dependency-Check + License 扫描（CI） | ✅ |
| 前端 | `npm run build`（CI 独立 Job） | ✅ |

- **PR 评审门槛**：CI 全绿 + 至少 1 名维护者评审。
- 提交信息：语义化简洁中文或英文，如 `docs: ...` / `feat(oa): ...` / `fix(common): ...`。

---

## 9. 文档地图（docs/，按软件开发流程阶段编号）

| 文档 | 内容 | 状态 |
|------|------|------|
| `01-…-可行性研究报告.md` | 市场/技术/经济/组织/法律/进度/风险，GB/T 8567 | 已定稿 |
| `02-…-软件需求规格说明书.md` | SRS：22 组功能需求（FR-SEC/AUD/WF/APR/MDM/OA/CRM/...）+ 26 条非功能 | 已定稿 |
| `03-…-概要设计说明书.md` | HLD：23 模块、模块边界、API 规约、开发队列 13.2（P0–P3，25 项顺序） | 已定稿 |
| `04-…-详细设计说明书.md` | DD 总册索引（分册体系 + 统一约定 + 验收对照） | 已定稿 |
| `04-…-详细设计说明书-P0-工程地基.md` | **当前开发批次**：工程骨架（顺序 1）+ common 底座（顺序 2），含 3.10 RuoYi 改造 12 步 | 进行中 |
| `04-…-详细设计说明书-P1-*.md` | P1 平台底座分册 | 待 P1 启动前编写 |

**开发队列摘要（HLD 13.2）**：P0 工程骨架 + common → P1 平台底座（platform/org/security/audit/workflow/approval/mdm/...）→ P2 业务域（oa/crm/inventory/finance/hr/project/scm/...）→ P3 开放定制与 AI 增强。写代码前先确认目标模块属于哪个批次，不要跨批次提前实现。

---

## 10. 给 AI 代理的工作流（Vibe Coding）

1. **先读本文件**，再读对应批次分册（当前为 P0 分册）与相关章节（HLD 2.2 模块边界 / 2.6 API / 5.1 common / 13.2 队列）。
2. **小步提交**：一次改动一个主题，改完立即跑对应测试（`mvn -pl <模块> -am test` / `npm run build`）。
3. **不改架构约束**：模块边界、依赖方向、API 规约、错误码分配是冻结的；确需变更 → 提 Issue 走评审。
4. **模板化新增模块**：复制 P0 骨架模块 → 改包名 `com.eaio.<module>` → 父 POM 登记 → 注册 Schema → 写 api 契约 → 实现 → ArchUnit 通过。
5. **本地工具链**：`.agents/skills/` 下有 Vibe Coding 辅助技能（本地使用，不入库、不进 PR）。
6. 产出后自查：是否违反 §5 架构约束？是否走 common 门面？API 是否 POST+JSON+动作词？错误码是否用枚举？测试是否覆盖关键路径？

---

## 11. 开源协作

- 仓库：`github.com/1010n111/e-aio`，主分支 `main`。
- **许可证**：e-aio 自身 Apache-2.0；RuoYi-Vue / RuoYi-Vue3 蓝本为 **MIT**（保留其版权声明与 LICENSE）。
- 接受 PR；Issue 驱动；语义化版本发布。
- 提交前自查：无敏感信息（密钥/口令/内网地址）、无版权残留（勿从闭源/其他开源工程粘贴未授权代码）。
