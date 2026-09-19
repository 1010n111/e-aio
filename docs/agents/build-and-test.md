# 构建、测试与质量门

## 环境要求

JDK **21**（固定版本）、Maven 3.9+、Node.js 20+ LTS、PostgreSQL **17**（每模块独立 Schema，镜像 `pgvector/pgvector:pg17`）、Redis **7**（缓存/锁/限流/幂等键）。Docker 仅本地 `docker compose` 与 Testcontainers 集成测试需要；**无 Docker 时 `mvn -B verify -DskipITs` 仍可全绿**，集成测试由 CI 承担（`disabledWithoutDocker`，见 04-DD P0 册 3.8 阶段 5）。

## 后端命令（在 `backend/e-aio/` 下执行）

| 目的 | 命令 |
|---|---|
| 编译 | `mvn -B compile` |
| 单测 + ArchUnit | `mvn -B test` |
| 架构/Modulith 验证（跳过集成测试，日常用） | `mvn -B verify -DskipITs` |
| **全量门禁**（含 Testcontainers 集成测试，提交前用） | `mvn -B verify` |
| 单模块 | `mvn -pl <module> -am clean compile` |

## 前端命令（在 `frontend/` 下执行）

`npm install` / `npm run dev`（Vite 代理 → 后端）/ `npm run lint`（ESLint）/ `npm run build`。

## 本地依赖

`docker compose up -d postgres redis`。仓库当前尚无 `docker-compose.yml`（P0 交付物），该命令在交付后生效。`postgres` 服务使用 `pgvector/pgvector:pg17`，初始化脚本执行 `CREATE EXTENSION IF NOT EXISTS vector`（报表/AI 向量检索在 P1 使用，P0 只保证环境零改动）。

## 质量门（PR 必须全绿）

| 门 | 内容 | 阻断 |
|---|---|---|
| 编译 | `mvn -B compile` | ✅ |
| Lint | 后端 `mvn -B checkstyle:check` + 前端 `npm run lint`（ESLint）（NFR-OSS-03） | ✅ |
| 单元测试 | `mvn -B test`；common 工具核心覆盖率 100%，整体 ≥ 80% | ✅ |
| 架构测试 | ArchUnit（模块边界 / 依赖单向 / 五层分包）+ `ApplicationModules.verify()` | ✅ |
| 集成测试 | Testcontainers（PostgreSQL + Redis）跑 `@SpringBootTest`、Flyway 空库迁移 | ✅ |
| 安全扫描 | License 扫描（CI）；**OWASP Dependency-Check 属 P1**（P0 依赖面小、无认证代码） | ✅（License） |
| 前端 | `npm run build`（CI 独立 Job） | ✅ |

**小步推进**：一次改动一个主题，改完立刻跑对应测试（`mvn -pl <模块> -am test` 或 `npm run build`）。

> CI 流水线定义在 `.github/workflows/ci.yml`（P0 交付物）。PR 门槛与提交规范见 [git-workflow.md](git-workflow.md)。
