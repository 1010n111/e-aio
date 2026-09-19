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

## 无 Docker 时的降级路径

本机没装 Docker（或不想起 compose）时：`mvn -B verify -DskipITs`（跳过 Testcontainers 集成测试）即可完成编译/单测/架构测试；本地空应用启动把 `eaio.flyway.enabled` 设为 `false`（见 P0 册 3.5.2）——**迁移路径的权威验证在 CI**（阶段 5），因此提交含 Flyway 脚本的改动前，注意本地绿不等于 CI 绿。

## 离线/受限网络下的本地验证（例外路径，不改变 CI 基线）

网络不可用或本地仓库只读时，可用已缓存 artifact 做**局部**验证，基线本身不变（父 POM 仍锁 4.1.x）：

- 命令加 `-o`（离线）与临时 `-s <settings>`（把仓库 id `public` 与 `central` 同时放进上下文，否则本地仓库中 `_remote.repositories` 记为 `public=` 的 artifact 会被判为"需重新下载"）；
- 本地缓存里通常只有 4.1.0 而没有最新补丁版，此时**临时**把父 POM 版本改成 4.1.0 验证，验证完必须改回——**不要提交该改动**；
- 该路径只能证明"代码与装配正确"，**不构成**对 CI 基线的验证：`actuator`、`modulith`、`logstash-logback-encoder`、`archunit-junit5`、`testcontainers` 等若不在缓存中，相关票在本机无法端到端验证，须在报告中如实标注（"本地未验证，权威在 CI"）。

## 质量门（PR 必须全绿）

| 门 | 内容 | 阻断 |
|---|---|---|
| 编译 | `mvn -B compile` | ✅ |
| Lint | 后端 `mvn -B checkstyle:check` + 前端 `npm run lint`（ESLint）（NFR-OSS-03） | ✅ |
| 单元测试 | `mvn -B test`；**可执行核心**（`Result`/`PageResult`/`ErrorCode`/异常/`JsonUtils`/`SensitiveUtils`/`DateUtils` 等，名单见 P0 册 4.8）100%，其余不设阈值；"整体 ≥ 80%"自 P1 有业务代码起生效 | ✅ |
| 架构测试 | ArchUnit（模块边界 / 依赖单向 / 五层分包）+ `ApplicationModules.verify()` | ✅ |
| 集成测试 | Testcontainers（PostgreSQL + Redis）跑 `@SpringBootTest`、Flyway 空库迁移 | ✅ |
| 安全扫描 | License 扫描（CI）；**OWASP Dependency-Check 属 P1**（P0 依赖面小、无认证代码） | ✅（License） |
| 前端 | `npm run build`（CI 独立 Job） | ✅ |

**小步推进**：一次改动一个主题，改完立刻跑对应测试（`mvn -pl <模块> -am test` 或 `npm run build`）。

> CI 流水线定义在 `.github/workflows/ci.yml`（P0 交付物）。PR 门槛与提交规范见 [git-workflow.md](git-workflow.md)。
