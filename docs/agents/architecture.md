# 架构约束（ArchUnit 强制，违反即 CI 失败）

## 模块化单体

- 单一可执行 Jar（`e-aio-app`），模块间经 Spring Modulith 边界交互；**不用微服务，当前不拆分**。
- 每模块独立 Schema `eaio_<module>`；跨模块数据只经应用层接口，**禁止跨 Schema SQL**。
- 模块间用 Spring 应用事件异步解耦（如「合同创建」→ 触发「应收生成」）。

## 目录硬约束

- 后端 Maven 模块全部位于 `backend/e-aio/` 下，artifactId 前缀 `e-aio-`，父 POM 即 `backend/e-aio/pom.xml`。
- 新增模块 = 复制 P0 骨架模板 + 父 POM 登记 + 注册独立 Schema；**禁止在 `backend/` 根下平铺模块**。
- `frontend/` 是独立 npm 工程，不参与 Maven 构建；后端只对其暴露 API 契约。

## 五层分包

模块根包 `com.eaio.<module>`：

| 包 | 内容 | 可见性 |
|---|---|---|
| `api` | 对外公共接口（Spring 服务接口 + DTO） | **其他模块只能依赖这个包** |
| `application` | 用例编排、事务边界、事件发布 | 模块内 |
| `domain` | 实体、值对象、领域服务、仓储接口 | 模块内 |
| `infrastructure` | 持久层实现、外部适配、缓存实现 | 模块内 |
| `events` | 领域事件定义 | 模块内 |

ArchUnit 固化规则：`domain` / `infrastructure` / `internal` 不得被其他模块引用；跨模块只依赖 `api`；依赖单向（业务模块 → 通用能力 workflow/iam/audit/report/ai，禁止反向）；禁止循环依赖。

## 主数据（MDM）

共享访问、不分发订阅：各模块直接调 MDM 的 `api` 读主数据；变更由 `MasterDataChangedEvent` 通知缓存失效。

## 权限（母子公司）

集团-子公司**无限级组织树**；子公司数据隔离 + 集团统一管控/子公司自治可配。RBAC + ABAC 混合、角色继承与覆盖、跨公司审批与审计、职责分离（SoD）。当前组织上下文由 `TenantCtx` 承载（P1 落地）。

## 审计（双维度）

- **系统操作审计**：全操作留痕、日志防篡改、全场景溯源（`@AuditLog` 切面，P1 接入）。
- **财务专项审计**：凭证/对账/报表可追溯，合规级输出。

## 新增模块顺序

复制 P0 骨架 → 改包名 `com.eaio.<module>` → 父 POM 登记 → 注册 Schema → 写 `api` 契约 → 实现 → ArchUnit 通过。

> 接口与契约规则见 [api-conventions.md](api-conventions.md)；表结构见 [database.md](database.md)。
