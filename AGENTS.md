# AGENTS.md — e-aio 代理入口（人类贡献者同样适用）

**一句话**：e-aio 是全开源、自托管、**模块化单体**的企业级一体化管理系统（Java 21 + Spring Boot 4.1.x + Spring Modulith；Vue3 + Vite + Element Plus），把 OA / CRM / 库存 / 财务 / HR / 项目 / 供应链 / 工作流 / 审批 / 权限 / 审计 / 报表 BI / AI / 门户消息等通用能力大一统，业务逻辑放**独立业务模块**、单向调用通用能力。

**三原则**：非 SaaS（不收费）· 全开源（Apache-2.0，接受 PR）· 模块化单体（不用微服务）。

**优先级**：本文件与 `docs/agents/*` 为评审基准；冲突时 AGENTS 系列 > `03 概要设计` > `02 需求规格` > `01 可研`。

## 每个任务

1. **定批次**：目标模块属 P0/P1/P2/P3 哪一批（队列见 [docs-map.md](docs/agents/docs-map.md)）；不跨批次提前实现。
2. **读批次分册**：当前 P0 → `docs/04-企业级一体化管理系统-e-aio-详细设计说明书-P0-工程地基.md`。
3. **按索引查区域**，再写代码 + 测试。
4. **提交前过门禁**（[build-and-test.md](docs/agents/build-and-test.md)），并对照索引文件逐条自查冻结项。

## 命令

后端（**必须在 `backend/e-aio/` 下执行**，Maven 多模块）：

```bash
mvn -B compile                             # 编译
mvn -B test                                # 单测 + ArchUnit
mvn -pl e-aio-common -am clean compile     # 只构建某模块
mvn spring-boot:run                        # 本地启动，默认 8080
```

前端（在 `frontend/` 下执行，npm）：

```bash
npm install
npm run dev      # Vite 代理 → 后端
npm run build    # 生产构建
```

门禁矩阵、覆盖率、集成测试、环境要求见 [build-and-test.md](docs/agents/build-and-test.md)。

## 按分支索引

| 触发场景 | 读这个 |
|---|---|
| 建模块 / 分包 / 依赖方向 / 跨模块调用 | [architecture.md](docs/agents/architecture.md) |
| 写接口 / 返回体 / 错误码 / 幂等键 | [api-conventions.md](docs/agents/api-conventions.md) |
| 写 Java / DTO 映射 / 校验 / 日志 / common 门面 | [java-conventions.md](docs/agents/java-conventions.md) |
| 写前端页面 / 请求封装 | [frontend-conventions.md](docs/agents/frontend-conventions.md) |
| 建表 / Flyway / 字段约定 | [database.md](docs/agents/database.md) |
| 选型 / 引入依赖 | [tech-stack.md](docs/agents/tech-stack.md) |
| 跑测试 / CI / 提交 / PR / 许可证 | [git-workflow.md](docs/agents/git-workflow.md) |
| 找设计文档 / 确认队列 / 文档命名 | [docs-map.md](docs/agents/docs-map.md) |

**冻结项**：模块边界、依赖方向、API 规约、错误码分配。确需变更 → 先提 Issue 走评审。
