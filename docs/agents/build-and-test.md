# 构建、测试与质量门

## 环境要求

JDK **21**（固定版本）、Maven 3.9+、Node.js 20+ LTS、PostgreSQL **17**（每模块独立 Schema，镜像 `pgvector/pgvector:pg17`）、Redis **7**（缓存/锁/限流/幂等键）。Docker 仅本地 `docker compose` 与 Testcontainers 集成测试需要；**无 Docker 时 `mvn -B verify -DskipITs` 仍可全绿**，集成测试由 CI 承担（`disabledWithoutDocker`，见 04-DD P0 册 3.8 阶段 5）。

## 后端命令（在 `backend/e-aio/` 下执行）

| 目的 | 命令 |
|---|---|
| 编译 | `mvn -B compile` |
| Lint（后端 checkstyle） | `mvn -B checkstyle:check`（规则集 `backend/e-aio/config/checkstyle/checkstyle.xml`；绑定 `validate` 阶段，因此 `compile`/`test`/`verify` 都会先跑） |
| 依赖漏洞扫描（阶段 6 的 CVE 部分） | `mvn -B org.owasp:dependency-check-maven:check`（**刻意不绑生命周期**，见「三项工程门禁」；首次要同步 NVD 数据库，设置环境变量 `NVD_API_KEY` 会快一个量级，不设也能跑但受匿名限流） |
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
| Lint | 后端 `mvn -B checkstyle:check`（规则集与门禁级别 P1 M1 已冻结：`backend/e-aio/config/checkstyle/checkstyle.xml`，违规即失败，测试源码同样在范围内；豁免项与理由写在该文件头部）；前端 `npm run lint`（ESLint） | ✅ |
| 单元测试 | `mvn -B test`；**可执行核心**（`Result`/`PageResult`/`ErrorCode`/异常/`JsonUtils`/`SensitiveUtils`/`DateUtils`/`IdGenerator`，名单见 P0 册 4.8）100%，其余不设阈值；"整体 ≥ 80%"自 P1 有业务代码起生效 | ✅ |
| 架构测试 | `ArchitectureTest`（Modulith `verify()` + 命名接口 + 纯净性 + 错误码分段；含"作用面非空/违规判红"断言） | ✅ |
| 集成测试 | Testcontainers（PostgreSQL 17 + pgvector、Redis 7）跑 `@SpringBootTest`、Flyway 空库迁移、真实 Redis 幂等重放、`/actuator/health`（Failsafe `*IT`） | ✅ |
| 安全扫描 | License 扫描（`license-maven-plugin` 白名单，`verify` 阶段）+ **OWASP Dependency-Check**（`failBuildOnCVSS=7`，CI 阶段 6 显式跑，见「三项工程门禁」） | ✅ |
| 前端 | `npm test`（请求层 13 用例）+ `npm run build`（CI 独立 Job） | ✅ |

## 三项工程门禁：启用状态与失败语义（P1，issue #13）

| 门禁 | 启用状态 | 落点（配置） | 失败语义（什么情况算红） |
|---|---|---|---|
| 依赖漏洞扫描 | **已启用**（CI 阶段 6；P0 只做许可证，CVE 部分 P1 起启用） | `backend/e-aio/pom.xml`（`org.owasp:dependency-check-maven`，版本锁 `12.1.9`，**不绑定任何生命周期阶段**）+ 抑制文件 `backend/e-aio/config/dependency-check/suppressions.xml`（当前**空**）；CI 由 `license` 作业显式执行 `mvn -B org.owasp:dependency-check-maven:check`，`NVD_API_KEY` 取自 secret（可选） | `failBuildOnCVSS=7`：任一依赖命中 **CVSS ≥ 7** 且**未**在该抑制文件里评审接纳 → 构建失败。抑制文件为空 = **未评审即阻断**（写进抑制条目要先有评审结论：理由 + 到期时间 `until` + Issue/PR 号）。报告 `**/target/dependency-check-report.{html,json}` 随作业产物上传。版本说明：实测锁 `12.1.9`——`13.0.0` 在**未配置 `NVD_API_KEY`** 时匿名同步直接失败（`Invalid API Key, length of 0 too short to provided a masked partial key`），本地与"secret 未配置的 CI"都会踩到。**覆盖范围**：插件默认 `skipTestScope=true`，只扫随产物发布的 compile/runtime/provided 依赖（test 依赖不进镜像、不进 jar，故不纳入门禁）。**冷缓存代价**：无 `NVD_API_KEY` 时匿名限流实测约 15 分钟/2 万条，全量 39.5 万条要数小时——CI 靠 `actions/cache` 缓存 NVD 数据做增量，**首次务必配好 `NVD_API_KEY` secret**，否则该作业有超时风险 |
| 镜像构建 | **已启用**（P1；本地 `docker build`，发布流程里是 `release.yml` 的阶段 7） | 仓库根 `Dockerfile`：多阶段（builder 用 Maven 打可执行 jar、`~/.m2` 走 BuildKit cache mount；runtime 用 JRE 21、**非 root** 用户 `eaio`、`HEALTHCHECK` → `/api/actuator/health`） | 镜像构建失败，或构建出的容器**起不来/健康检查不过**（`docker inspect --format '{{.State.Health.Status}}'` 不是 `healthy`）即失败；发布流程里镜像推不上去就不进入发布作业 |
| tag 发布 | **已启用**（P1；`release.yml` 的阶段 8，只做"产物可下载"，不含部署/回滚） | `.github/workflows/release.yml`：`on.push.tags: ['v*']`；顺序 `gate`（`mvn -B verify`，与 CI 同口径）→ 推 `ghcr.io/<repo>:<tag>` → GitHub Release 附 `e-aio-app-*.jar` | 任一环失败即**不发布**：门禁不过就没有产物（`if-no-files-found: error`），`needs` 让后续作业不执行；权限最小化（`contents: write` + `packages: write`） |

本地等价命令：

```bash
# 漏洞扫描（不绑生命周期，任何时候都可以单独跑）
cd backend/e-aio && mvn -B org.owasp:dependency-check-maven:check
# 镜像：构建 → 以"空应用"启动（无库、无 Redis）→ 看健康
docker build -t eaio-it .
docker run -d --name eaio-it -p 18080:8080 eaio-it
curl http://localhost:18080/api/actuator/health   # data.status = UP
docker inspect --format '{{.State.Health.Status}}' eaio-it   # healthy
docker rm -f eaio-it
```

镜像的两个既有约定（照抄给排障用）：健康端点在 **servlet context-path 之后**（`/api/actuator/health`），且被 `ApiResponseAdvice` 包了统一返回体，所以 `HEALTHCHECK` 只看 HTTP 状态码（UP=200 / DOWN=503）；基础配置排除了 `DataSourceAutoConfiguration`，**无库**即可启动，而 Redis 健康指示器在连不上 Redis 时会把整体健康压成 DOWN，镜像因此把默认运行形态定为"空应用"（`MANAGEMENT_HEALTH_REDIS_ENABLED=false`，见 `Dockerfile`），接入 Redis 的部署用 `-e MANAGEMENT_HEALTH_REDIS_ENABLED=true` 覆盖。

三项门禁的**落地验证记录（2026-09-19，本机）**：漏洞扫描在 NVD 数据缓存就绪后 `mvn -B -DautoUpdate=false org.owasp:dependency-check-maven:check` 全反应堆 17 秒 `BUILD SUCCESS`、四个模块各产出 `target/dependency-check-report.{html,json}`、当前依赖面 0 条命中；随后**临时**给 `e-aio-app` 加 `org.apache.struts:struts-core:1.3.10`（compile scope）复跑，得到 `[ERROR] One or more dependencies were identified with vulnerabilities that have a CVSS score greater than or equal to '7.0'`（`CVE-2012-0391(9.8)`、`CVE-2014-0114(7.5)`）→ `BUILD FAILURE`，撤销后 `mvn -B -DskipTests compile` 恢复 `BUILD SUCCESS`（证明门禁真有牙齿，且空抑制列表 = 未评审即阻断）。镜像：`docker build -t eaio-it .` 通过 → `docker run -d --name eaio-it -p 18080:8080 eaio-it` 约 9 秒后 `docker inspect --format '{{.State.Health.Status}}'` = `healthy`，宿主 `curl http://localhost:18080/api/actuator/health` = HTTP 200 且 `data.status=UP`，容器内 `id` = `uid=1001(eaio)`（非 root）。**发布流程（阶段 8）本机无法端到端验证**（需要真打 tag 与 GitHub 权限），只做了 YAML 结构自检（触发条件/权限/`needs` 链/产物路径/镜像 tag），**权威验证在 CI 首次打 tag**。

**CI 与阶段映射**：`.github/workflows/ci.yml`（P0 交付物）——`backend`（阶段 1–4，阶段 2 Lint 自 P1 M1 起启用：checkstyle 绑定 `validate`，随该作业的 `compile`/`test` 先执行）、`integration`（阶段 5，`mvn verify`）、`license`（阶段 6：许可证白名单 + OWASP 依赖漏洞扫描）、`frontend`（阶段 1b/2b）；**阶段 7 镜像构建与阶段 8 发布不在 `ci.yml`，在 `.github/workflows/release.yml`（tag `v*` 触发）**：门禁 `mvn -B verify` → 多阶段构建并推 `ghcr.io/<repo>:<tag>` → GitHub Release 附可执行 jar。运行环境固定 `ubuntu-latest` + Temurin JDK 21 + Node 24，集成测试直接用 runner 自带 Docker（不配置 service container）。PR 门槛与提交规范见 [git-workflow.md](git-workflow.md)。

**小步推进**：一次改动一个主题，改完立刻跑对应测试（`mvn -pl <模块> -am test` 或 `npm run build`）。
