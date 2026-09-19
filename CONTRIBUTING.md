# 贡献指南（CONTRIBUTING）

感谢参与 e-aio。本项目全开源（Apache-2.0）、接受 PR，遵循"非 SaaS、全开源、模块化单体"三原则。

## 开始之前

1. **先读 [AGENTS.md](AGENTS.md)**：它是评审基准，含命令、门禁矩阵、分支索引与冻结项。
2. **确认批次**：目标功能属 P0/P1/P2/P3 哪一批（队列见 [docs/agents/docs-map.md](docs/agents/docs-map.md)）；**不跨批次提前实现**。
3. **查 Issue**：需求与缺陷记在 GitHub Issues；开工前确认工单已被打上 `ready-for-agent` 或 `ready-for-human`（见 [docs/agents/triage-labels.md](docs/agents/triage-labels.md)）。

## 开发流程

```bash
# 1. 分支：一个主题一个分支
git switch -c feat/<module>-<short-desc>

# 2. 改代码 + 测试（先写能失败的测试，再写实现）
cd backend/e-aio && mvn -B test          # 单测 + 架构测试（无 Docker 也能全绿）
cd frontend && npm test && npm run lint  # 前端

# 3. 提交门禁（提交前）
cd backend/e-aio && mvn -B verify        # 含 Testcontainers 集成测试（需要 Docker）
```

无 Docker 时用降级路径 `mvn -B verify -DskipITs`，但**迁移与集成测试的权威验证在 CI**（见 [docs/agents/build-and-test.md](docs/agents/build-and-test.md)）。

## 硬性约定

| 事项 | 约定 |
|---|---|
| 提交信息 | Conventional Commits，见 [docs/agents/git-workflow.md](docs/agents/git-workflow.md)；一个主题一次提交 |
| 模块边界 | 业务模块单向依赖通用能力层；跨模块只经 `api` 共享面（架构测试会拦） |
| 接口约定 | 统一 `POST` + JSON，HTTP 恒 200，业务结果看响应体 `code`（[ADR-0001](docs/adr/0001-unified-post-and-always-200-result-contract.md)） |
| 错误码 | 通用段 `10000–19999`，业务段每模块 `1000` 号（模块登记表见 P0 册附录 6.1）；**不回收空号** |
| 数据库 | 每模块独立 Schema 与独立 Flyway 实例（[ADR-0002](docs/adr/0002-per-module-schema-and-flyway-instance.md)）；迁移脚本只增不改 |
| 依赖 | 引入新依赖前先查 [docs/agents/tech-stack.md](docs/agents/tech-stack.md) 与许可证白名单；能复用/标准库解决的不新增依赖 |
| 冻结项 | 模块边界、依赖方向、API 规约、错误码分配：确需变更**先提 Issue 走评审** |
| 许可证 | 新增源码文件保持与仓库一致；引入 GPL/AGPL 类依赖会被 License 门禁拦下 |

## 代码风格

- 后端：Java 21，构造器注入，DTO 不进领域层；注释写"为什么"，不写"做了什么"。
- 前端：Vue 3 `<script setup>`，请求只经 `src/api/request.js`（禁止各页面自己拼 axios）。
- 不提交真实凭据：本地配置放 `.env`（已忽略），仓库只放 `.env.example` 样例值。

## PR 要求

- 使用 [PR 模板](.github/PULL_REQUEST_TEMPLATE.md)，逐条勾选门禁。
- 描述里写清：**改了什么、为什么这么改、怎么验证的**；有未验证项必须如实标注。
- CI 必须全绿才可合并；评审以 AGENTS 系列文档为基准。

## 行为准则

参与本项目即表示同意遵守[行为准则](CODE_OF_CONDUCT.md)。

## 许可证

提交贡献即表示你同意以 **Apache-2.0** 授权你的贡献（见 [LICENSE](LICENSE)）。
