# 构建、测试与质量门

## 环境要求

JDK **21**（固定版本）、Maven 3.9+、Node.js 20+ LTS、PostgreSQL **17**（每模块独立 Schema，镜像 `pgvector/pgvector:pg17`）、Redis **7**（缓存/锁/限流/幂等键）。Docker 仅本地 `docker compose` 与 Testcontainers 集成测试需要；**无 Docker 时 `mvn -B verify -DskipITs` 仍可全绿**，集成测试由 CI 承担（`disabledWithoutDocker`，见 04-DD P0 册 3.8 阶段 5）。

## 后端命令（在 `backend/e-aio/` 下执行）

| 目的 | 命令 |
|---|---|
| 编译 | `mvn -B compile` |
| 单测 + 架构测试（无容器也能全绿） | `mvn -B test` |
| 单测 + 架构测试 + 许可证扫描（跳过集成测试，日常用） | `mvn -B verify -DskipITs` |
| **全量门禁**（含 Testcontainers 集成测试，提交前用；需要 Docker） | `mvn -B verify` |
| 单模块 | `mvn -pl <module> -am clean compile` |

集成测试（`*IT`）走 Failsafe，与单测（`*Test`，Surefire）分开：`mvn -B test` 不会碰容器，`mvn -B verify` 才追加容器化集成测试。

## 前端命令（在 `frontend/` 下执行）

`npm install` / `npm run dev`（Vite 代理 → 后端）/ `npm run lint`（ESLint）/ `npm run build`。

## 本地依赖（本地编排）

```bash
cp .env.example .env                 # 样例值；.env 已被 .gitignore 忽略，别提交真实凭据
docker compose up -d postgres redis  # 与 CI 同镜像：pgvector/pgvector:pg17 + redis:7-alpine
docker compose ps                    # 两个服务都应是 healthy
```

`postgres` 的初始化脚本（`deploy/postgres/init/01-extensions.sql`）建 pgvector 扩展并给应用角色建 Schema 权限（报表/AI 向量检索在 P1 使用，P0 只保证环境零改动）；`redis` 不持久化，重启即清空。

本地起后端（连库 + 跑迁移）：

```bash
cd backend/e-aio && mvn -B spring-boot:run -Dspring-boot.run.profiles=local
curl http://localhost:8080/api/actuator/health   # {"code":0,...,"data":{"status":"UP"}}
```

说明：迁移由应用自己执行（每模块一个 Flyway 实例），因此首次启动会创建 `eaio_platform` 与其中的 `flyway_schema_history`。

## 无 Docker 时的降级路径

本机没装 Docker（或不想起 compose）时：

```bash
cd backend/e-aio
mvn -B verify -DskipITs      # 编译 + 单测 + 架构测试 + 许可证扫描，全绿
cd e-aio-app && java -jar target/e-aio-app-*.jar --eaio.flyway.enabled=false   # 无库启动，健康检查可达
```

**权威性声明**：这条路径**不能**替代 CI。迁移（Flyway 在真实 PostgreSQL 上执行）与集成测试（Testcontainers）的权威验证在 **CI 阶段 5**；`*IT` 用例在无 Docker 机器上由 `@Testcontainers(disabledWithoutDocker = true)` 跳过（实测：`Tests run: 4, Skipped: 4`），"本地全绿"不等于"CI 会绿"——提交含迁移脚本的改动前务必让 CI 跑完。

## 离线/受限网络下的本地验证（例外路径，不改变 CI 基线）

网络不可用或本地仓库只读时，可用已缓存 artifact 做**局部**验证，基线本身不变（父 POM 仍锁 4.1.x）：

- 命令加 `-o`（离线）与临时 `-s <settings>`（把仓库 id `public` 与 `central` 同时放进上下文，否则本地仓库中 `_remote.repositories` 记为 `public=` 的 artifact 会被判为"需重新下载"）；
- 本地缓存里通常只有 4.1.0 而没有最新补丁版，此时**临时**把父 POM 版本改成 4.1.0 验证，验证完必须改回——**不要提交该改动**；
- 该路径只能证明"代码与装配正确"，**不构成**对 CI 基线的验证：`actuator`、`modulith`、`logstash-logback-encoder`、`archunit-junit5`、`testcontainers` 等若不在缓存中，相关票在本机无法端到端验证，须在报告中如实标注（"本地未验证，权威在 CI"）。

## 质量门（PR 必须全绿）

| 门 | 内容 | 阻断 |
|---|---|---|
| 编译 | `mvn -B compile` | ✅ |
| Lint | 前端 `npm run lint`（ESLint）；**后端 checkstyle 规则集与门禁级别在 P1 冻结**（P0 未启用，不写"已启用"以免假绿灯） | ✅（前端） |
| 单元测试 | `mvn -B test`；**可执行核心**（`Result`/`PageResult`/`ErrorCode`/异常/`JsonUtils`/`SensitiveUtils`/`DateUtils`/`IdGenerator`，名单见 P0 册 4.8）100%，其余不设阈值；"整体 ≥ 80%"自 P1 有业务代码起生效 | ✅ |
| 架构测试 | `ArchitectureTest`（Modulith `verify()` + 命名接口 + 纯净性 + 错误码分段；含"作用面非空/违规判红"断言） | ✅ |
| 集成测试 | Testcontainers（PostgreSQL 17 + pgvector、Redis 7）跑 `@SpringBootTest`、Flyway 空库迁移、真实 Redis 幂等重放、`/actuator/health`（Failsafe `*IT`） | ✅ |
| 安全扫描 | License 扫描（`license-maven-plugin` 白名单，`verify` 阶段）；**OWASP Dependency-Check 属 P1**（P0 依赖面小、无认证代码） | ✅（License） |
| 前端 | `npm test`（请求层 13 用例）+ `npm run build`（CI 独立 Job） | ✅ |

**CI 与阶段映射**：`.github/workflows/ci.yml`（P0 交付物）——`backend`（阶段 1/3/4）、`integration`（阶段 5，`mvn verify`）、`license`（阶段 6）、`frontend`（阶段 1b/2b）；阶段 7 镜像构建与阶段 8 发布属 P1。运行环境固定 `ubuntu-latest` + Temurin JDK 21 + Node 24，集成测试直接用 runner 自带 Docker（不配置 service container）。PR 门槛与提交规范见 [git-workflow.md](git-workflow.md)。

**小步推进**：一次改动一个主题，改完立刻跑对应测试（`mvn -pl <模块> -am test` 或 `npm run build`）。
