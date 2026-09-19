# CONTEXT.md — e-aio 领域词汇表

> 本文件是**项目语言的唯一术语表**（glossary only）：只定义"用什么词、指什么"，不含实现细节、不写成规格。
> 上游冲突时的判定顺序见 [AGENTS.md](AGENTS.md)：AGENTS 系列 > `03 概要设计` > `02 需求规格` > `01 可研`；已落 ADR 的决策以 [docs/adr/](docs/adr/) 为准。
> 命名空间、目录结构等实现位置由 [docs/agents/](docs/agents/) 承载，本文件不重复。

## 项目定位

| 术语 | 定义 |
|---|---|
| e-aio | 全开源（Apache-2.0）、自托管、非 SaaS 的企业级一体化管理系统：通用能力大一统，业务逻辑按企业定制 |
| 模块化单体 | 单体部署形态下用模块边界组织代码的架构；**不用微服务，当前不拆分** |
| 模块（module） | 一个 Maven 模块 + 一个根包 `com.eaio.<module>` + 一个独立 Schema `eaio_<module>`，三者一一对应 |
| 应用壳（app shell） | `e-aio-app`：非业务模块，承载启动类与 Web 装配，是全应用唯一装配方与唯一 Modulith 应用；业务代码不得放入 |

## 分层

| 术语 | 定义 |
|---|---|
| 技术底座（common） | 无状态通用工具库；被所有模块依赖，**不依赖任何模块、不建表、无 Schema、不含业务逻辑** |
| 通用能力层 | platform、iam、audit、workflow、approval、mdm、report、ai；彼此独立或仅依赖平台底座，不依赖业务模块 |
| 业务模块层 | oa、crm、inventory、finance、fund、hr、project、scm、marketing、service；**单向依赖通用能力层，禁止反向** |
| 平台支撑层 | portal、integration、mobile、i18n；面向接入与集成，仅依赖通用能力层 |
| 五层分包 | 模块内部统一分包 `api / application / domain / infrastructure / events`；**跨模块只允许依赖 `api`** |
| 命名接口（named interface） | `api` 包以 `package-info.java` + `@NamedInterface("api")` 显式暴露，是跨模块可见性的唯一入口 |

## 契约

| 术语 | 定义 |
|---|---|
| 模块契约 | 模块对外公共 API：接口 + DTO + 事件；冻结后变更走评审 |
| 契约 V1 | common 对外 API（`Result<T>`/`PageResult<T>`/`ErrorCode`/异常/门面）自 P0 起冻结的版本；P1 起只允许向后兼容新增 |
| 统一返回体 | `Result<T>`：`code` / `message` / `data` / `traceId`；分页 `Result<PageResult<T>>` |
| HTTP 状态码恒 200 | 所有响应 HTTP 状态码为 200，业务结果由 body 的 `code` 表达；认证/鉴权失败同样 200 + `10401`/`10403`（见 [ADR-0001](docs/adr/0001-unified-post-and-always-200-result-contract.md)） |
| 错误码分段 | `0` 成功；`10000–19999` 通用段；`20000+` 业务段，**每模块预留 1000 号**（20000 platform、21000 iam、22000 audit，依此类推） |
| 幂等键 | 写接口请求头 `Idempotency-Key`；重复提交返回 `10501`，**不重复执行业务、不回放历史结果**（`PROCESSING` 执行中被重放同属重复提交） |
| 统一 POST + JSON | 全部接口（含查询/删除/导出）使用 POST + JSON body；动作词 `Get`/`GetPage`/`Add`/`Up`/`Del` + 业务动作词；**协议模式例外**（OAuth2/SAML2/文件流，封闭清单见 [ADR-0003](docs/adr/0003-protocol-endpoint-exceptions.md)）与文件上传/下载不套 `Result` |
| traceId | 请求级链路标识：请求头 `X-Trace-Id` 透传或生成 → MDC → `Result.traceId` 回填 |

## 平台底座（P1）

| 术语 | 定义 |
|---|---|
| TenantCtx（租户/组织上下文） | 请求级的"当前用户 + 当前组织 + 数据范围 + 权限码"只读快照；**归 iam**（`com.eaio.iam.api`），platform/audit 只经 `TenantCtxProvider` 读取，**不放 common**（见 [ADR-0005](docs/adr/0005-platform-zero-dependency-org-context.md)） |
| TenantCtxProvider | iam 暴露的只读接口：`current()` / `require()` / `currentOrgId()` / `runAs(...)`；唯一实现与 ThreadLocal 持有在 iam 内部 |
| 组织上下文端口（OrgContextPort） | platform 自有的最小端口（当前组织 id / 系统上下文标记），默认 Null 实现；由应用壳注入 iam 适配器，**platform 不依赖 iam** |
| 数据范围（data scope） | 角色的数据可见范围：`SELF` / `DEPT` / `DEPT_AND_SUB` / `ORG` / `ORG_AND_SUB` / `ALL` / `CUSTOM`；决定查询被追加的组织谓词 |
| 权限码（permission code） | `模块:资源:动作` 形式的授权标识（如 `iam:user:list`）；后端判定用，前端仅用于渲染（不作为安全边界） |
| 数据权限谓词 | 由 iam 拦截器追加到查询的组织过滤条件；三档：内联 `IN` / `EXISTS`（对 `eaio_iam.org_node_path` 的只读谓词，[ADR-0004](docs/adr/0004-cross-schema-readonly-predicate.md)）/ 规则停用（恒假 + 告警） |
| 审计链（audit chain） | 审计表内按 `chain_key` 串接的哈希链：`row_hash = SHA-256(规范化 JSON ‖ prev_hash)`；改一行即校验不通过 |
| WORM | 审计流水只追加：应用层无 UPDATE/DELETE + 数据库层 `REVOKE` + 触发器拒绝，保证"可举证" |
| 发件箱（outbox） | 模块内 `event_outbox` 表 + 投递重试/死信：业务事务与事件登记同事务，跨模块副作用最终一致 |

## 数据

| 术语 | 定义 |
|---|---|
| 每模块独立 Schema | 每模块数据落在自己的 `eaio_<module>` Schema；**禁止跨 Schema 关联查询与写操作** |
| 每模块独立 Flyway 实例 | 每模块一个 Flyway 实例：独立版本序列、独立 `flyway_schema_history`，模块可独立演进（见 [ADR-0002](docs/adr/0002-per-module-schema-and-flyway-instance.md)） |
| 逻辑删除 | 删除默认为逻辑删除，保留可追溯记录；物理删除需显式评审 |
| 单号 / 幂等键分工 | 业务单号与唯一约束是数据层幂等兜底；`Idempotency-Key` 是接口层防重，二者互补不互替 |

## 演进

| 术语 | 定义 |
|---|---|
| 批次（P0–P3） | 开发队列的四个批次（HLD 13.2）；**不跨批次提前实现** |
| RuoYi 蓝本 | RuoYi-Vue（后端）/ RuoYi-Vue3（前端），MIT 许可；仅作工程与功能**参考**（clean-room 重写，不拷贝源码，溯源见 `NOTICE`），不作为长期依赖 |
| devtools | 开发工具链（如代码生成器一类）；**不入运行时** |
