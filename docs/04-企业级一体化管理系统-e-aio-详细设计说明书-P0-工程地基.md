---
title: 企业级一体化管理系统详细设计说明书 · 第 P0 册（工程地基）
type: 详细设计说明书（DD）· 分册
phase: 详细设计（P0）
version: V1.1
status: 进行中
date: 2026-09-19
tags:
  - e-aio
  - DD
  - P0
  - 工程地基
aliases:
  - e-aio DD P0
  - 详细设计说明书 P0
related:
  - "[[01-企业级一体化管理系统-e-aio-可行性研究报告]]"
  - "[[02-企业级一体化管理系统-e-aio-软件需求规格说明书]]"
  - "[[03-企业级一体化管理系统-e-aio-概要设计说明书]]"
  - "[[04-企业级一体化管理系统-e-aio-详细设计说明书]]"
---

# 企业级一体化管理系统（e-aio）详细设计说明书（DD）

> **项目名称**：企业级一体化管理系统（e-aio）
> **项目定位**：通用能力大一统 + 业务逻辑按企业定制的企业级开源系统
> **编制依据**：GB/T 8567-2006《计算机软件文档编制规范》、GB/T 9385-2008《计算机软件需求规格说明规范》
> **上游文档**：[[01-企业级一体化管理系统-e-aio-可行性研究报告|01-可行性研究报告]]、[[02-企业级一体化管理系统-e-aio-软件需求规格说明书|02-软件需求规格说明书（SRS）]]、[[03-企业级一体化管理系统-e-aio-概要设计说明书|03-概要设计说明书（HLD）]]
> **版本**：V1.2（P0 阶段，评审修订：契约/幂等/依赖基线/质量门）
> **日期**：2026-09-19
> **分册归属**：本文档为详细设计说明书 **第 P0 册 · 工程地基**（[[04-企业级一体化管理系统-e-aio-详细设计说明书|总册索引]]），覆盖 HLD 13.2 顺序 1 工程骨架 + 顺序 2 common；P1–P3 各分册将在对应开发批次启动前编写。

---

## 核心结论（执行摘要）

本详细设计说明书完成 **P0 阶段（工程地基）** 的类级设计：**工程骨架（顺序 1）** 与 **common 技术底座（顺序 2）**，作为全部后续模块的开发地基。

| 设计主题 | 关键决策 |
|------|----------|
| 工程形态 | Maven 多模块 + Spring Boot 4.1.x + Spring Modulith 2.1.x；单一可执行 Jar |
| 命名空间 | 模块根包 `com.eaio.<module>`，分包 `api/application/domain/infrastructure/events` |
| 契约核心 | `Result<T>` / `PageResult<T>` / `ErrorCode` / 全局异常处理在 common 冻结 V1 |
| 数据库 | 每模块独立 Schema + **每模块独立 Flyway 实例**；P0 建 `eaio_platform` 骨架 Schema，无业务表 |
| 质量门 | ArchUnit 规则集 + `ApplicationModules.verify()` + Lint（NFR-OSS-03）+ CI 流水线（GitHub Actions） |
| 工具底座 | Hutool 门面 + Lombok + Bean Validation + MapStruct + ExcelKit（Apache Fesod）+ RedisKit |
| 前端 | RuoYi-Vue3 蓝本（Vue3 + Vite + Element Plus），统一 POST + JSON 请求封装 |
| P0 验收 | 与 HLD 13.3 对齐：工程骨架 CI 绿灯、ArchUnit 通过、Flyway 可运行；common 工具单测通过 |

**P0 里程碑**：M0–M1（约 2 个月，含技术预研收尾与立项评审交付）。

---

## 1. 引言

### 1.1 编写目的

本文档对 e-aio **P0 阶段**（工程骨架、common 技术底座）进行详细设计，将 HLD 中确定的模块边界、命名规范、交互机制落实为**类级结构、接口签名、配置项与测试设计**，作为 P0 编码、测试与验收的直接依据。后续阶段（P1–P3）文档将按同一规范延续。

### 1.2 文档范围

| 开发队列（HLD 13.2） | 本文档覆盖 | 详细设计章节 |
|----------------------|-----------|--------------|
| P0 · 顺序 1 工程骨架 | ✅ | 第 3 章 |
| P0 · 顺序 2 common | ✅ | 第 4 章 |
| P1–P3（顺序 3–24） | ❌ 后续补充 | 待 P1 启动前编写 |

### 1.3 术语与约定

| 术语 | 说明 |
|------|------|
| 应用壳（app shell） | `e-aio-app`：非业务模块，承载启动类与 Web 装配，是全应用唯一装配方与唯一 Modulith 应用（3.1.3）；业务代码不得放入 |
| 错误码分段 | `0` 成功；`10000–19999` 通用段；`20000+` 业务段，每模块预留 1000 号（3.2.3） |
| 幂等键（Idempotency-Key） | 写接口防重请求头；重复提交（含执行中重放）返回 `10501`，不重复执行、不回放结果（3.2.5） |
| 门面（Facade） | 对开源库的薄封装，统一 e-aio 内部调用入口 |
| 质量门（Quality Gate） | CI 中必须通过的检查项（编译/测试/架构/安全） |
| 契约（Contract） | 模块对外 API 签名 + DTO + 事件，冻结后变更走评审 |
| traceId | 请求级链路标识，贯穿日志与审计 |
| Schema | PostgreSQL 数据库模式，每模块独立 |
| Flyway | 数据库迁移工具（Apache-2.0） |
| ArchUnit | 架构单元测试框架（Apache-2.0） |

### 1.4 参考资料

1. [[01-企业级一体化管理系统-e-aio-可行性研究报告|01-可行性研究报告]]
2. [[02-企业级一体化管理系统-e-aio-软件需求规格说明书|02-软件需求规格说明书（SRS）]]
3. [[03-企业级一体化管理系统-e-aio-概要设计说明书|03-概要设计说明书（HLD）]]，重点：2.2.2 模块边界、2.4 交互机制、2.6 RuoYi 蓝本、5.1 common、13.2 P0 队列、13.3 P0 验收
4. Spring Boot 4.1.x 官方文档
5. Spring Modulith 2.1.x 官方文档
6. Apache Fesod 官方文档（https://fesod.apache.org/zh-cn/docs/）
7. RuoYi-Vue 官方文档（MIT，前后端分离版）

---

## 2. P0 总体设计

### 2.1 P0 目标与交付物

P0 目标是搭建**可运行、可验证、可扩展**的工程地基，交付物如下：

| 交付物 | 说明 | 对应 HLD |
|--------|------|----------|
| 工程骨架 | Maven 多模块工程 + Modulith 命名空间 + 统一异常/错误码 + Flyway + CI | 13.2 顺序 1 |
| common 工具库 | ExcelKit / RedisKit / 通用工具门面 / 统一返回体 | 13.2 顺序 2 |
| 前端骨架 | RuoYi-Vue3 蓝本初始化，统一请求封装 | 2.6.1 ruoyi-ui 映射 |
| 架构测试 | ArchUnit 规则集 + Modulith verify | 10.1 |
| CI 流水线 | GitHub Actions 编译/Lint/测试/架构/集成/安全/构建（后端 + 前端独立 Job） | 10.3 |
| 本地编排 | `docker-compose.yml` + `.env.example`（PostgreSQL/Redis 一键起本地依赖） | 9.2 |
| 合规与配套 | 自身 `LICENSE`（Apache-2.0）、`NOTICE`（RuoYi MIT 版权声明与蓝本 tag/commit）、`CONTRIBUTING.md`、`CODE_OF_CONDUCT.md`、PR 模板（`Dockerfile` 属 P1，见 3.8 阶段 7） | 2.6.3 / 10.3 |

### 2.2 P0 工程结构总览（前后端分离）

**前后端分离架构**：`backend/`（后端工程）与 `frontend/`（前端工程）分离——后端只暴露 RESTful API（统一 POST + JSON），前端独立构建（Vite）、独立部署（Nginx/静态托管）；开发期经 Vite 代理联调。与 RuoYi-Vue（前后端分离版）蓝本结构一致。

```
e-aio/                                # 主仓库（backend + frontend + docs）
├── backend/                          # 后端工程根目录
│   └── e-aio/                        # 后端工程（Spring Modulith 模块化单体，Maven 多模块，父 POM 所在）
│       ├── pom.xml                   # 父 POM（依赖管理 BOM + 模块清单）
│       ├── e-aio-app/                # 启动模块（Spring Boot 可执行 Jar；应用壳，非业务模块）
│       │   ├── src/main/java/com/eaio/EaioApplication.java
│       │   ├── src/main/java/com/eaio/app/       # Web 装配：全局异常/traceId/幂等过滤器/响应回填（3.3–3.4）
│       │   ├── src/main/resources/db/migration/platform/V1__baseline.sql
│       │   └── src/test/java/com/eaio/arch/      # ArchUnit + ApplicationModules.verify()
│       ├── e-aio-common/             # common 技术底座（顺序 2）
│       │   ├── src/main/java/com/eaio/common/
│       │   └── src/test/java/com/eaio/common/    # common 自身约束测试
│       └── …                         # P1–P3 按队列顺序新增 e-aio-<module>（P0 不预建目录）
├── frontend/                         # 前端独立工程（RuoYi-Vue3 蓝本，前后端分离）
│   ├── src/…                         # 见 3.1.4
│   ├── vite.config.js                # 开发代理 → 后端 API；生产独立部署
│   └── package.json                  # 独立依赖与构建脚本（与后端 Maven 无关）
├── docs/                             # 工程文档
├── .github/workflows/ci.yml          # CI 流水线（后端/前端独立 Job，见 3.8）
├── .github/PULL_REQUEST_TEMPLATE.md  # PR 模板（3.8 配套）
├── docker-compose.yml                # 本地 PostgreSQL/Redis 编排（见 docs/agents/build-and-test.md）
├── .env.example                      # 本地环境变量样例（不含真实密钥）；本地 `cp .env.example .env`，`.env` 已在 .gitignore 中
├── Dockerfile                        # 应用镜像（多阶段构建）——**属 P1**（随 3.8 阶段 7 镜像构建一并引入）
├── LICENSE                           # e-aio 自身 Apache-2.0
├── NOTICE                            # 第三方组件许可与 RuoYi MIT 版权声明、蓝本 tag/commit
├── CONTRIBUTING.md / CODE_OF_CONDUCT.md
├── .gitignore
└── README.md
```

> **命名说明**：Maven artifactId 前缀 `e-aio-`，业务模块根包统一 `com.eaio.<module>`；`e-aio-app` 为**应用壳**（非业务模块）——启动类置于根包 `com.eaio`，Web 装配类置于 `com.eaio.app.*`（3.1.3）。**P0 只创建 `backend/e-aio/e-aio-app` 与 `backend/e-aio/e-aio-common`**（父 POM `<modules>` 仅此两项）；业务模块目录**不预建**，P1 启动时按队列顺序新增并同步父 POM。
>
> **前端工程边界**：`frontend/` 为独立 npm 工程（不参与 Maven 构建），独立版本管理；如需独立仓库协作，可将 `frontend/` 整体拆分为 `e-aio-web` 仓库（与 RuoYi-Vue3 独立前端仓库一致），后端仅依赖其 API 契约。

### 2.3 P0 与后续阶段的关系

- **契约先行**：common 对外 API（`Result<T>`、`ErrorCode`、工具门面、ExcelKit、RedisKit）在 P0 冻结 V1，P1 起所有模块基于此开发；API 变更走评审（HLD 13.4）。
- **骨架复用**：工程骨架的包结构、异常处理、ArchUnit 规则、CI 流水线被所有后续模块直接复用，P0 阶段将"模板化"——新增模块 = 复制骨架模板 + 注册 Schema。
- **无业务表**：P0 不建任何业务表；Flyway 在 P0 建立**迁移骨架与 `eaio_platform` 骨架 Schema**（供 P1 顺序 3 platform 直接继承），其余模块 Schema 由 P1 起在各自迁移脚本中声明创建（3.6）。
- **数据库设计归属**：数据库设计不单独成文档，随各详细设计分册以"数据结构设计"章节承载；P0 无业务表，本册仅落地 Flyway 迁移骨架（3.6），P1 起各分册按模块给出表结构 DDL、索引与迁移脚本。

---

## 3. 工程骨架详细设计（顺序 1）

### 3.1 工程初始化

#### 3.1.1 父 POM 与模块清单

父 `pom.xml` 职责：依赖版本管理（`<dependencyManagement>` + BOM）、插件版本、全局属性、模块清单。

| 配置项 | 值 |
|--------|-----|
| `spring-boot.version` | `4.1.x`（补丁浮动；当前解析值 **4.1.1**，升级走 PR） |
| `spring-modulith.version` | `2.1.x`（当前解析值 **2.1.1**，↔ Boot 4.1.x） |
| `java.version` | 21（LTS） |
| `project.build.sourceEncoding` | UTF-8 |
| 打包 | Spring Boot Maven Plugin（`e-aio-app` 为 repackage 主模块） |
| 蓝本基线 | ✅ 已核实 2026-09-19：RuoYi-Vue master = **3.9.2，Spring Boot 4.1.0 / Java 17**（与本文档 Boot 4.1.1 / Java 21 同线，可作为蓝本；Java 版本以本工程 21 为准）；RuoYi-Vue3 = vue 3.5.x / vite 6.x / element-plus 2.13.x，**MIT 许可** |
| 模块清单 | `e-aio-app`、`e-aio-common`（P1 起追加） |

#### 3.1.2 依赖版本基线（P0 引入的最小集）

| 依赖 | 用途 | 许可证 |
|------|------|--------|
| `spring-boot-starter-webmvc` | Web 容器（Boot 4 起 `spring-boot-starter-web` 已废弃，须用 `-webmvc`） | Apache-2.0 |
| `spring-boot-starter-validation` | Bean Validation 标准 | Apache-2.0 |
| `spring-boot-starter-aspectj` | 切面（审计/日志预留；Boot 4 无 `-aop` starter） | Apache-2.0 |
| spring-boot-starter-actuator | 健康检查 `/actuator/health`（3.10 步骤 3、5.3）；指标接 Prometheus 属 P1 | Apache-2.0 |
| logstash-logback-encoder | 结构化 JSON 日志（3.4）；锁 9.0（Jackson 3 线，配 Boot 4） | Apache-2.0 |
| spring-modulith-starter-core | 模块化单体 | Apache-2.0 |
| spring-modulith-starter-test | Modulith 测试 | Apache-2.0 |
| lombok | 样板代码生成 | MIT |
| `cn.hutool:hutool-all` | 工具库底座（✅ 已核实 2026-09-19：坐标 `cn.hutool`（**不存在 `com.hutool`**），5.8.x 线；6.x 线坐标 `org.dromara.hutool`，待稳定后评估） | **Mulan PSL v2**（非 MPL-2.0，宽松许可，OK） |
| mapstruct + mapstruct-processor | DTO 映射 | Apache-2.0 |
| `spring-boot-starter-flyway`（Boot 4 新增）+ `flyway-database-postgresql` | 数据库迁移（每模块独立实例，3.6）；`spring.flyway.*` 属性名不变 | Apache-2.0 |
| postgresql | JDBC 驱动 | PostgreSQL |
| spring-boot-starter-data-redis | Redis 基础（**P0 仅声明**：`RedisKit` 接口冻结，实现与 Redisson 底层随 P1 引入，见 4.4） | Apache-2.0 |
| archunit-junit5 | 架构测试（✅ 已核实 2026-09-19：**Apache-2.0**，本次核实纠正了此前误标，见文末修订记录） | Apache-2.0 |
| junit5（EPL-2.0） / assertj（Apache-2.0） / testcontainers（MIT） | 测试（✅ 已核实 2026-09-19：testcontainers 2.0.x，Boot 4.1.1 管理 2.0.5；MIT） | 见左 |
| `org.apache.fesod:fesod-sheet`（Apache Fesod Incubating，2.0.x-incubating，JDK8–JDK25） | Excel 读写（经 `ExcelKit` 门面，4.3） | Apache-2.0 |

> **原生编译（GraalVM）**：Spring Boot 4.x 官方支持 AOT + native-image 将应用编译为原生可执行文件（秒级启动、低内存）；e-aio **默认 JVM 运行**，原生构建作为可选 Profile 提供。涉及反射/动态代理的组件（MyBatis-Plus、Flowable、Redisson、AOP 等）需补充 native 配置（reflect-config / proxy-config），P1 起随模块验证；Spring Modulith 官方支持原生运行。**版本附注**：Boot 4.1.1 官方文档标称 Native Build Tools **1.1.8**，Maven Central 最新为 **1.1.14（UPL-1.0）**——二者兼容性未经验证，原生构建整条链路推迟到 P1 验证，P0 不引入该插件。

> **版本策略**：统一由父 POM 管理，锁定已知兼容组合；升级经 PR 评审并跑全量 CI。表内未标注范围者均为 compile 范围，`archunit-junit5` 与测试件为 test 范围；`spring-modulith-starter-test` 仅 test 范围。**MyBatis-Plus P0 不引入**（P0 无表可映射，避免无用的自动配置与启动风险），P1 顺序 3 再落地 Boot 4 对应坐标 `com.baomidou:mybatis-plus-spring-boot4-starter`（≥3.5.16，届时锁定版本）；Redisson（分布式锁/限流/队列）与 springdoc（OpenAPI 标注）**P0 同样不引入**：`RedisKit` P0 只冻结接口签名（4.4）、平台无自定义接口无需文档，二者均随 P1 首次使用引入，引入时同步 [`docs/agents/tech-stack.md`](agents/tech-stack.md)。Spring Security 与 JWT 同理，由 P1 iam 承接（3.1.2 未列 = P0 无认证实现，`10401`/`10403` 仅为契约）。
>
> **版本锁定（✅ 已核实 2026-09-19，Maven Central metadata / POM 与官方文档）**：Java 21 · Spring Boot **4.1.1** · Spring Modulith **2.1.1**（2.1.x ↔ Boot 4.1.x）· archunit **1.5.0** · `cn.hutool:hutool-all` **5.8.47**（Mulan PSL v2）· `org.apache.fesod:fesod-sheet` **2.0.2-incubating**（仍处孵化：旧 EasyExcel 代码 IP 清理未完成）· mybatis-plus-spring-boot4-starter **3.5.17**（P1）· redisson-spring-boot-starter **4.7.0**（P1，内置 `redisson-spring-data-41` 对应 Boot 4.1；自动配置类为 `RedissonAutoConfigurationV4`，**旧版本按 `V2` 排除的写法在 Boot 4 静默失效**）· springdoc **3.1.1**（P1）· logstash-logback-encoder **9.0** · testcontainers **2.0.5** · native-maven-plugin **1.1.14（UPL-1.0，非 GPL-2.0+CE）**，Boot 4.1.1 官方文档标称 Native Build Tools **1.1.8**（1.1.14 与之兼容性未验证 → 原生构建 P1 验证）。
>
> **锁定粒度**：Spring Boot / Spring Modulith **补丁级浮动**（父 POM 写 `4.1.x` / `2.1.x`，由 BOM 解析；本文档记录当前值 4.1.1 / 2.1.1，随升级同步），其余第三方依赖**精确锁定**并在升级时走 PR 评审。许可证与版本核实清单见 3.1.2 表内 ✅ 标注，核对日期 2026-09-19。

#### 3.1.3 命名空间与包结构规范

模块根包：`com.eaio.<module>`（如 `com.eaio.common`、`com.eaio.platform`）。`e-aio-app` 为**应用壳**而非业务模块，**全应用只有一个启动类**：启动类 `EaioApplication` 置于根包 `com.eaio`，不使用 `@ComponentScan` 限定——Spring Boot 从 `com.eaio` 向下扫描，P1 起新增业务模块只要落在 `com.eaio.<module>` 包内即自动纳入，无需改动启动类。应用壳各包职责与 Web 装配类归属（P0 目录）：

| 包 | 内容 |
|----|------|
| `com.eaio` | 启动类 `EaioApplication`（唯一） |
| `com.eaio.app.web` | Web 装配：`GlobalExceptionHandler` / `TraceIdFilter` / `IdempotencyFilter` / 响应回填 Advice |
| `com.eaio.app.config` | 基础设施装配：`ModuleFlywayConfig` |
| `com.eaio.app.*`（其余） | 允许承载基础设施装配类；**业务代码不得放入 `com.eaio.app`** |
| `com.eaio.arch`（`src/test`） | ArchUnit + `ApplicationModules.verify()`（3.7；应用壳是唯一 Modulith 验证方） |

P0 不拆 `e-aio-app`：装配面仅 5 个类，拆模块只增加构建层数而不增加边界；Web 装配同样不下放 common（common 不依赖 Web 层，3.4）。

模块内部统一五层分包：

```
com.eaio.<module>
├── api          # 对外公共接口（Spring 服务接口 + DTO），其他模块仅可依赖此包
├── application  # 应用服务：用例编排、事务边界、事件发布
├── domain       # 领域模型：实体、值对象、领域服务、仓储接口
├── infrastructure # 基础设施：持久层实现（Mapper）、外部适配、缓存实现
└── events       # 领域事件定义（Event + 载荷 DTO）
```

**强制约束**（ArchUnit 固化）：
- `internal` / `domain` / `infrastructure` 包不得被其他模块引用；
- 跨模块仅可依赖 `api` 包；
- `application` 层调用 `domain` 与 `infrastructure`（依赖倒置：domain 定义接口，infrastructure 实现）。

**Modulith 登记形制（第四轮裁决）**：模块登记放**模块侧**——每个模块根包 `package-info.java` 写 `@ApplicationModule(allowedDependencies = …)`（新增模块自动纳入，无需回改 `e-aio-app`）；`ApplicationModules.verify()` 仍只有一条、跑在 `e-aio-app`。另加 ArchUnit 断言"**每个模块根包的 `package-info.java` 均带 `@ApplicationModule`**"防遗漏——只集中登记在 app 会让新增模块漏登记且未必立刻报错。

**Modulith 可见性落点（P0 必须实现）**：Spring Modulith 默认只暴露模块**根包**类型，故每个模块的 `api` 子包必须显式声明为命名接口，否则 P1 首个业务模块接入时 `ApplicationModules.verify()` 必然失败：

```java
// backend/e-aio/e-aio-common/src/main/java/com/eaio/common/api/package-info.java
@org.springframework.modulith.NamedInterface("api")
package com.eaio.common.api;
```

- 每个模块的 `api` 包均需同款 `package-info.java`；`e-aio-common` 在 P0 一并落位：它虽无横向调用方，但仍是"其他模块只见 common.api"的规范性样板，且由 3.7 的 ArchUnit 规则断言其存在（机制必须有牙齿，P1 新增模块不得遗漏）；
- 模块级依赖用模块根包 `package-info.java` 上的 `@ApplicationModule(allowedDependencies = {...})` 显式声明，固化"业务模块 → 通用能力、禁止反向、禁止循环"；
- 业务类型不得放在模块根包（根包默认全可见，等于无边界）。

#### 3.1.4 目录结构（前端 RuoYi-Vue3 初始化）

前端基于 RuoYi-Vue3 蓝本初始化（Vue3 + Vite + Element Plus），保留其成熟能力（路由/权限指令/字典/水印），改造点为统一请求封装（见 3.9）。

```
frontend/
├── src/
│   ├── api/          # 按模块的 API 封装（统一 POST + JSON）
│   ├── assets/       # 静态资源
│   ├── components/   # 通用组件
│   ├── directives/   # 权限指令（v-hasPermi 等，来自 RuoYi）
│   ├── layout/       # 布局（门户骨架）
│   ├── router/       # 路由（动态路由 + 权限）
│   ├── store/        # Pinia 状态（用户/权限/字典）
│   ├── utils/        # request.js（axios 封装）、auth.js
│   └── views/        # 页面（P1 起按模块挂载）
└── vite.config.js
```

### 3.2 统一返回体与错误码（契约核心，位于 common，工程骨架引用）

> `Result<T>`、`PageResult<T>`、`ErrorCode`、异常类型归属 **common 模块**（`com.eaio.common.api`），工程骨架的全局异常处理引用之；此处一并定义，避免重复。

#### 3.2.1 Result<T>

```java
package com.eaio.common.api;

/** 统一响应体（契约 V1）。 */
public class Result<T> {
    /** 业务错误码，0 表示成功。 */
    private int code;
    /** 提示消息。 */
    private String message;
    /** 业务数据。 */
    private T data;
    /** 链路追踪 ID。 */
    private String traceId;
    // getter/setter（Lombok @Data）省略

    public static <T> Result<T> ok(T data) { ... }
    public static <T> Result<T> ok(T data, String message) { ... }
    public static <T> Result<T> fail(int code, String message) { ... }
    public static <T> Result<T> fail(ErrorCode errorCode) { ... }
}
```

约定：HTTP 状态码恒为 200（业务结果由 `code` 表达）；`traceId` 由 MDC 自动填充。

#### 3.2.2 PageResult<T>

```java
public class PageResult<T> {
    private long total;        // 总记录数
    private long pageNum;      // 当前页
    private long pageSize;     // 页大小
    private List<T> records;   // 当前页数据
    // 静态工厂 from(long pageNum, long pageSize, long total, List<T> records) 等省略
}
```

> **纯净性约束**：`PageResult<T>` 不依赖任何持久层类型（不引用 MyBatis-Plus `IPage` 等），由各模块持久层在 `api` 层适配转换，保证 common 纯工具库属性（4.1）。

分页查询统一返回 `Result<PageResult<T>>`；分页参数统一入 JSON body（`pageNum`/`pageSize`/排序字段）。

#### 3.2.3 ErrorCode 与错误码表（P0 初稿）

```java
package com.eaio.common.api;

public enum ErrorCode {
    SUCCESS(0, "成功"),
    // 通用
    PARAM_INVALID(10000, "参数校验失败"),
    PARAM_MISSING(10001, "缺少必要参数"),
    DATA_NOT_FOUND(10002, "数据不存在"),
    DATA_CONFLICT(10003, "数据冲突（版本过期/重复）"),
    UNAUTHORIZED(10401, "未认证或令牌失效"),
    FORBIDDEN(10403, "无权限执行该操作"),
    SYSTEM_ERROR(10500, "系统内部错误"),
    IDEMPOTENT_REPLAY(10501, "重复提交"),
    IDEMPOTENCY_UNAVAILABLE(10502, "幂等校验不可用（Redis 不可用，请稍后重试）"),
    // 通用段预留 10503–19999：空号不回收
    // 模块前缀：业务模块段从 20000 起，每模块预留 1000 号（20000 platform / 21000 iam / 22000 audit …）
    // 10000–19999 之外的空号不回收；模块内自增，跨模块不可能撞号，附录 6.1 仅作索引
    // 模块侧登记（非 common）：各业务模块根包 package-info.java 声明 @ApplicationModule
    // ErrorCode 是**枚举**（0 成功 + 10000–10502 通用段）；业务模块各自定义枚举实现 BusinessErrorCode 接口，
    // 由 ArchUnit 断言其数值落在附录 6.1 登记的 1000 号段内（第五轮裁决）
    ;
    private final int code;
    private final String message;
    // 构造与 getter 省略
}
```

错误码规范（与 HLD 7.1 一致）：`模块前缀 + 递增`；P0 定义通用段（10000–19999），业务模块段（20000+）在 P1 起按模块分配并在附录 6.1 维护。

#### 3.2.4 异常类型

```java
package com.eaio.common.exception;

/** 业务异常：携带错误码 + 用户可读消息。 */
public class BusinessException extends RuntimeException {
    private final int code;
    public BusinessException(ErrorCode errorCode) { ... }
    public BusinessException(int code, String message) { ... }
}

/** 系统异常：内部错误，不向用户暴露细节。 */
public class SystemException extends RuntimeException { ... }

/** 幂等拦截异常：重复请求（携带相同幂等键）时抛出。 */
public class IdempotentReplayException extends RuntimeException {
    public IdempotentReplayException() { super("请求已提交，请勿重复操作"); }
}
```

#### 3.2.5 幂等键拦截（后端）

写接口（Add/Up/Del 及业务动作）统一经幂等过滤器防重（与前端 3.9.2 携带的 `Idempotency-Key` 配合）：

| 要素 | 设计 |
|------|------|
| 归属 | `com.eaio.app.web.IdempotencyFilter`（OncePerRequestFilter，`e-aio-app` 装配；底层用 `RedisKit` 的 SETNX 能力） |
| 拦截点 | 按 URL + `Idempotency-Key` 请求头（仅写接口，见"范围"） |
| 存储 | Redis：键 `eaio:{env}:idem:{sha256(URL+key)}`；值为状态——`PROCESSING`（TTL 10min，防并发重入）/ `DONE`（TTL 24h，可配） |
| 流程 | 请求到达 → SETNX 置 `PROCESSING` → 已存在则返回 `Result.fail(IDEMPOTENT_REPLAY)`（10501）→ 业务执行 → **成功**改写为 `DONE`（TTL 24h）；**业务失败/系统异常**立即删除占位，允许用户修正后重试（避免一次失败锁死 24h） |
| 异常 | 幂等命中抛 `IdempotentReplayException`，由 3.3 全局异常处理统一返回 |
| 范围 | 写接口（Add/Up/Del 及业务动作）强制、查询接口不启用；幂等键缺失时按普通请求放行并记录 WARN |

> **重复提交的两种状态（必须明确）**：`10501` 对 **`PROCESSING`（首个请求仍在执行中）** 与 **`DONE`（已完成）** 两种情况**同样返回**——即"执行中重放"也算重复提交，客户端提示"请求已提交，请稍后刷新"，不等待、不回放。因此**长任务接口（导出/批量导入）不得以幂等键承担结果交付**：结果交付走任务 ID + 轮询（异步导入 API 随 FR-PLT-05 在 P1 冻结，见 4.3）。
>
> **幂等校验不可用时的行为（第五轮裁决，fail-closed）**：Redis 不可用（连接失败/超时）导致**无法判定是否重复**时，`IdempotencyFilter` **拒绝执行**并返回 `10502`（幂等校验不可用）+ ERROR 日志（含 traceId），**不静默放行**——放行会在 Redis 抖动期间真实产生重复写入，与"接口幂等"承诺相违。代价：Redis 故障时**写接口短时不可用，读接口不受影响**。P0 不提供 `fail-open` 开关（`eaio.idempotency.fail-open` 属 P1 评估项，默认恒为 closed）：单一行为比两套行为更可验证，本地无 Redis 时也不会误以为"幂等已生效"。
>
> **与 HLD 7.2 的对齐**：HLD 7.2 原表述"返回原结果"与本册/`docs/agents/api-conventions.md`（幂等键约定，优先级最高）的"返回 `10501` 重复提交"冲突。已统一为**返回 10501、不重复执行业务、不回放历史结果**，HLD 7.2 同步修订；接口幂等由"键 + 唯一约束"兜底，不做结果缓存回放。

### 3.3 全局异常处理

`GlobalExceptionHandler` 位于 `backend/e-aio/e-aio-app`（`com.eaio.app.web`），`@RestControllerAdvice` 实现：

| 异常类型 | 处理结果 | 日志 |
|----------|----------|------|
| `BusinessException` | `Result.fail(code, message)` | WARN（含 traceId） |
| `MethodArgumentNotValidException`（body 校验）/ `HandlerMethodValidationException`（方法参数校验，Boot 3.2+ 起的主路径）/ `BindException` / `ConstraintViolationException` | `Result.fail(PARAM_INVALID, 首个错误消息)` | DEBUG |
| `HttpMessageNotReadableException`（JSON 报文不可读）/ `HttpRequestMethodNotSupportedException` | `Result.fail(PARAM_INVALID, "请求格式不正确")` | DEBUG |
| `MissingServletRequestParameterException` / `MaxUploadSizeExceededException` | `Result.fail(PARAM_MISSING)`（上传超限用 `PARAM_INVALID`） | DEBUG |
| `IdempotentReplayException` | `Result.fail(IDEMPOTENT_REPLAY)` | INFO |
| `AccessDeniedException` | `Result.fail(FORBIDDEN)` | WARN |
| 其他 `Exception` | `Result.fail(SYSTEM_ERROR)`（不泄漏堆栈） | ERROR（堆栈入库） |

要点：
- **P0 无认证实现**：`spring-boot-starter-security` 与 JWT **P0 不引入**（3.1.2 版本策略），故 P0 不会抛出 `AccessDeniedException`——上表该行、`10401`/`10403` 均属**契约预留**（P1 iam 承接）；P0 不提供任何受保护接口，也不得出现"默认放行"的隐式行为；
- **不向客户端泄漏堆栈与敏感信息**（HLD 7.1）；
- **参数/报文类异常必须先于兜底分支显式处理**：否则 JSON 解析失败、缺参、方法不支持等会落到 10500 系统错误，误导前端与运维排查；P0 单测须覆盖"JSON 解析失败"与"参数校验失败"两条路径。
- 统一记录 `traceId`，异常日志与审计联动（P1 audit 接入后替换切面占位）；
- 审计写入失败在强审计场景阻断（P1 实现，P0 预留扩展点）。

### 3.4 日志与链路基础

- **结构化日志**：logback 配置输出 JSON（`{"ts","level","traceId","module","msg",...}`），生产环境 JSON、本地控制台 pattern。
- **traceId 过滤器**：`TraceIdFilter`（OncePerRequestFilter，`com.eaio.app.web`）——从请求头 `X-Trace-Id` 读取或生成 UUID，写入 MDC，响应头回写同名 header，请求结束清理 MDC。
- **`Result.traceId` 回填落点**：`GlobalResponseAdvice`（`ResponseBodyAdvice<Result<?>>`，`com.eaio.app.web`）在序列化前用 MDC 填充 `traceId`（为空才填）。`Result` 保持纯 POJO，自身不读 MDC（避免 common 依赖 Web 层）。
- **日志配置**：`e-aio-app/src/main/resources/logback-spring.xml`——本地控制台 pattern，`dev/test/prod` 输出 JSON（经 `logstash-logback-encoder`，3.1.2）。
- **日志级别规范**：业务操作 INFO、异常 WARN/ERROR、调试 DEBUG；禁止打印敏感字段（脱敏见 common `SensitiveUtils`）。

### 3.5 配置管理

#### 3.5.1 配置分层（与 HLD 9.3 一致）

| 层 | 来源 | 说明 |
|----|------|------|
| 默认配置 | 代码内 `application.yml` | 可运行默认值 |
| 环境配置 | `application-{env}.yml`（dev/test/prod） | 环境差异（DB/Redis 地址等） |
| 运行时参数 | 参数中心（DB 驱动） | P1 platform 落地，P0 预留表结构 |

#### 3.5.2 P0 最小配置清单（application.yml）

```yaml
spring:
  application:
    name: e-aio
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:5432/${DB_NAME:eaio}
    username: ${DB_USER:eaio}
    password: ${DB_PASSWORD:eaio}
  flyway:
    enabled: false          # 关闭 Spring Boot 默认单实例迁移：由 ModuleFlywayConfig 按模块实例执行（见 3.6）
    # 脚本目录 classpath:db/migration/<module>/，实例配置见 eaio.flyway.modules
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: 6379
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}
server:
  port: 8080
management:
  endpoints:
    web:
      exposure:
        include: health,info     # 健康检查 /actuator/health（3.10 步骤 3、5.3）
eaio:
  flyway:
    enabled: true                # 迁移总开关；生产恒 true，本地/集成测试可覆盖（3.6）
    modules:                     # 模块 → Schema 登记表；P1 起逐模块追加
      - module: platform
        schema: eaio_platform
  trace:
    header: X-Trace-Id
```

> **开关语义（必须遵守）**：`eaio.flyway.enabled` 默认 `true`（生产不得静默跳过迁移）；本地空应用演示在 `application-dev.yml` 设 `false`。**P1 iam 引入认证时在此追加 `eaio.security.jwt.*`**（P0 未声明，避免给出不存在的配置项）。
> **本地免依赖启动**：dev 未声明数据源时，数据源连接失败不应阻断启动——Flyway 由 `eaio.flyway.enabled=false` 跳过，P0 无需要 DB 的组件；需 DB 的集成测试用 Testcontainers（3.8 阶段 5、5.2）。
> **SQL 日志（第四轮裁决）**：**不引入 p6spy**——P0 无表、无 Mapper，用不上；后续需要时用 HikariCP 自带日志 + `logging.level`（MyBatis Mapper 日志级别）观测，不为日志引入新坐标。

敏感配置（密码/密钥）一律走环境变量或密钥管理，禁止明文入库。

### 3.6 Flyway 数据库迁移骨架（每模块独立实例）

单一 Flyway 实例 + 多 Schema 会让全部模块共享一个版本号序列与一张历史表，破坏"模块可独立演进"（HLD 13.1），故采用**每模块一个 Flyway 实例**：

| 约定 | 说明 |
|------|------|
| 实例划分 | 每模块一个 `Flyway` Bean：`locations=classpath:db/migration/<module>`、`schemas=eaio_<module>`、在该 Schema 内建 `flyway_schema_history`；版本号在模块内独立递增 |
| 关闭默认自动配置 | `spring.flyway.enabled=false`（3.5.2）；由 `ModuleFlywayConfig`（`com.eaio.app.config`）按 `eaio.flyway.modules` 登记表逐模块执行 `migrate()`，避免所有模块脚本被灌进默认 Schema |
| 脚本位置 | 各模块自带 `src/main/resources/db/migration/<module>/`（[database.md](agents/database.md)）；单个可执行 Jar 内全部在 classpath，按实例 locations 隔离 |
| 命名规范 | `V<版本>__<描述>.sql`（模块内递增）；可重复迁移 `R__<描述>.sql`（checksum 变化时重跑，仅用于视图/函数/种子） |
| Schema 管理 | Flyway `create-schemas=true` 依据 `schemas` 自动创建 `eaio_<module>`；脚本内对象一律显式 schema 限定，**禁止跨 Schema DDL/查询**（[database.md](agents/database.md)） |
| P0 内容 | 仅登记 `platform → eaio_platform`；脚本 `e-aio-app/src/main/resources/db/migration/platform/V1__baseline.sql`（基线占位，**不含任何业务表 DDL**）。其余模块 Schema 由 P1 起各自迁移脚本创建；platform 后续脚本自 `V2__` 连续编号 |
| 种子数据 | P0 不注入业务种子（无表可写）；系统参数/字典/角色/流程模板等种子随 platform、iam 在 P1 各自迁移脚本注入 |

> **被否决的备选**：单实例 + `schemas=eaio_platform,eaio_iam,…` + `default-schema`——配置最少，但版本号全局共享、脚本需靠 `search_path` 切换，模块无法独立演进，与 HLD 13.1"可插拔演进"冲突。

### 3.7 ArchUnit 质量门

架构测试分两处固化规则：`ArchitectureTest`（`backend/e-aio/e-aio-app/src/test/java/com/eaio/arch`）承载**全部跨模块规则与 Modulith 校验**——应用壳（`e-aio-app`）是唯一依赖全部模块的装配方，因而是唯一能看到完整模块图的 Modulith 应用；`CommonArchitectureTest`（`backend/e-aio/e-aio-common/src/test/java/com/eaio/common`）只承载 common 自身约束（不依赖任何模块、不引用持久层类型、不依赖 Web 层）。**业务模块不得反向依赖 `e-aio-app`**。规则集：

| 规则 | 说明 |
|------|------|
| 模块边界 | `ApplicationModules.verify()`：禁止访问其他模块 `internal` 包 |
| 无循环依赖 | Modulith 自动校验 |
| 分层依赖 | 业务模块 → 通用能力，禁止反向（`com.eaio.*` 依赖白名单/黑名单断言） |
| 包访问 | `internal/domain/infrastructure` 不得被跨模块引用 |
| 公共 API | 跨模块仅可依赖 `api` 包类型（该包以 `@NamedInterface` 显式暴露，见 3.1.3） |
| 命名接口完备 | 断言**每个模块的 `api` 包均带 `@NamedInterface("api")`**（含 `e-aio-common`）——P1 新增模块遗漏即 CI 失败（3.1.3） |
| 模块登记完备 | 断言**每个模块根包 `package-info.java` 均带 `@ApplicationModule`**（3.1.3，模块侧登记防遗漏） |
| 错误码分段 | `com.eaio.common.api.ErrorCode` 单测：《 0 仅 `SUCCESS`、通用段落在 `10000–19999`、数值唯一 |
| 禁止跨 Schema SQL | P0 以代码评审 + 脚本人工核查约束（ArchUnit 不解析 SQL）；SQL 审计插件 P1 评估 |

CI 中 `mvn verify` 自动执行；任何架构违例即构建失败（NFR-OSS-03 PR 质量门）。

### 3.8 CI 流水线（GitHub Actions）

`.github/workflows/ci.yml` 流水线（运行环境见本节末）：

| 阶段 | 步骤 | 失败即阻断 |
|------|------|-----------|
| 1 编译 | `mvn -B compile` | ✅ |
| 2 Lint | 后端 `mvn -B checkstyle:check`（规则集随 P0 冻结）；前端 `npm run lint`（ESLint） | ✅ |
| 3 单元测试 | `mvn -B test`（含 ArchUnit） | ✅ |
| 4 架构测试 | `mvn -B verify -DskipITs`（Modulith verify） | ✅ |
| 5 集成测试 | Testcontainers 起 PostgreSQL 17（`pgvector/pgvector:pg17`）/ Redis 7 跑 `@SpringBootTest` + Flyway 空库迁移；测试类标 `@Testcontainers(disabledWithoutDocker = true)`——本机无 Docker 时跳过，迁移路径的**权威验证在 CI** | ✅ |
| 6 安全扫描 | License 扫描（NFR-OSS-04）；**OWASP Dependency-Check 属 P1**（P0 依赖面小、无认证代码，NVD Key 与缓存策略随 P1 一并落地） | ✅（License） |
| 7 镜像构建 | Docker 多阶段构建，推送 GHCR —— **属 P1**（P0 不交付 `Dockerfile`） | —（P1） |
| 8 发布 | 打 tag 时发布 Release —— **属 P1** | —（P1） |

配套：`CONTRIBUTING.md`、`CODE_OF_CONDUCT.md`、`LICENSE`（Apache-2.0）、`NOTICE`（含 RuoYi MIT 声明与蓝本 tag/commit）、PR 模板（NFR-OSS-03/05）；本地依赖编排 `docker-compose.yml` + `.env.example`。

**运行环境（第四轮裁决）**：`runs-on: ubuntu-latest` + Temurin **JDK 21**（`actions/setup-java`）+ Node **24**（`actions/setup-node`）；Runner 自带 Docker，Testcontainers 直接可用，**不再额外配置 service container**（避免与 Testcontainers 争夺容器生命周期）。后端 Job 顺序：`mvn -B verify`（含阶段 3–5）→ 前端独立 Job `npm ci && npm run lint && npm run build`。`docker-compose.yml` 形态：`postgres`（`pgvector/pgvector:pg17`，init 脚本 `CREATE EXTENSION IF NOT EXISTS vector`）+ `redis`（`redis:7-alpine`），named volume 持久化，`.env.example` 只放样例值（无真实密钥）——本地 `cp .env.example .env`（`.env` 入 `.gitignore`），compose 用 `environment: ${VAR:-default}` 读取。

### 3.9 前端工程骨架（RuoYi-Vue3 蓝本）

#### 3.9.1 初始化

采用 RuoYi-Vue3 的技术栈（Vue3 + Vite + Element Plus，MIT）**自行初始化工程**（不拷贝其源码，见 3.10 步骤 11），布局与页面写法参考其工程方法论。**P0 只交付最小壳**：请求层（3.9.2）+ 路由壳 + 布局 + 登录态占位（无后端认证，登录页仅作 UI 占位与 code 判定演示）；**动态菜单、权限指令 `v-hasPermi`、字典、水印留 P1**（均依赖 iam/platform 契约，P0 无接口可接）。

- 接口路径统一 `/api/<module>/<resource>/<Action>`（模块名即路径段，见 [api-conventions.md](agents/api-conventions.md)）；Vite 代理 `/api` → 后端。
- **认证失败处理唯一落点**：全局响应拦截器按 `body.code` 判定（`10401` 跳登录 / `10403` 提示无权限），只做这两个动作、不自动重试；`code !== 0` 一律 reject；**禁止按 HTTP 401/403 判断**（ADR-0001）。

#### 3.9.2 统一请求封装（统一 POST + JSON）

`src/utils/request.js`（axios 实例）按 HLD 2.6.4 约定实现：

```js
// 幂等键：由"用户动作"生成一次，重试复用同一键（Web Crypto，无第三方依赖）
export const newIdempotencyKey = () => crypto.randomUUID()

// 统一 POST + JSON：所有接口（含查询/删除/导出）走 POST
const request = (url, params = {}) => {
  const { __idempotencyKey, ...body } = params  // 幂等键从 body 剔除，仅作请求头
  return axios.post(url, body, {
    headers: {
      'Content-Type': 'application/json',
      ...(__idempotencyKey ? { 'Idempotency-Key': __idempotencyKey } : {}),
    },
  })
}

// 响应拦截：Result<T> 统一解包；code!==0 提示并 reject
// 认证失败按 body.code 判断（10401 未登录 / 10403 无权限）→ 跳登录或提示；
// 禁止按 HTTP 401/403 判断（ADR-0001：HTTP 恒 200）
```

> **幂等键生命周期（必须遵守）**：① 仅写接口生成，查询接口不生成；② 在"用户提交动作"发生时生成一次，同一动作的重试（网络重试、报错后重提）**必须复用同一键**，重新发起新业务动作才重新生成；③ 键只走请求头，不落 URL、不落本地存储。

#### 3.9.3 API 动作词封装

`src/api/<module>/<resource>.js` 按动作词规约生成：

```js
// src/api/crm/customer.js（示例，P1 实现）
export const customerApi = {
  get: (params) => request('/api/crm/customer/Get', params),       // 查询单条
  getPage: (params) => request('/api/crm/customer/GetPage', params), // 分页查询
  add: (params) => request('/api/crm/customer/Add', params),        // 新增
  up: (params) => request('/api/crm/customer/Up', params),          // 更新
  del: (params) => request('/api/crm/customer/Del', params),        // 删除（逻辑删除）
}
```

例外：文件上传走 `multipart/form-data`（`FileApi/Upload`），下载走二进制流（`FileApi/Download`），由 platform 的 `FileApi` 专用封装（P1 落地）。

### 3.10 从 RuoYi-Vue 蓝本改造实施步骤（落地指南）

> 本节将 HLD 13.2.1"RuoYi 蓝本改造落点"落实为 **P0 可执行步骤**，指导从 `RuoYi-Vue`（后端）与 `RuoYi-Vue3`（前端）两个开源工程改造为 e-aio 工程骨架。改造原则：**保留成熟实现、按模块迁移、删除无关部分、叠加 e-aio 差异化能力**（HLD 2.6.3）。

#### 3.10.1 改造策略总览

| 分类 | RuoYi 内容 | e-aio 处置 |
|------|-----------|-----------|
| 保留（迁移） | ruoyi-common 工具集、ruoyi-framework 基础（Security/JWT/拦截器/全局异常）、RuoYi-Vue3 前端工程、字典/参数/日志基础实现 | 按 3.10.2 步骤迁移，包名/命名空间重写 |
| 改造 | AjaxResult/BaseEntity → `Result<T>`/`PageResult<T>`；操作日志 → 双审计（P1）；Quartz → Spring Task+ShedLock（P1）；API → 统一 POST + JSON | 见 3.2、3.5、3.6、3.9 |
| 删除/隔离 | ruoyi-generator（→ devtools 不入运行时）、ruoyi-admin 直连入口、RuoYi 前端直连后端配置、非 P0 的 system 业务表 | 移出 P0 父 POM / 前端移除，P1 按模块重建 |
| 合规 | RuoYi 版权声明与 LICENSE（MIT） | 保留原版权与 LICENSE 文件（HLD 2.6.3） |

#### 3.10.2 实施步骤（P0）

| 步骤 | 动作 | 涉及内容 | 输出 / 验收 |
|------|------|----------|-------------|
| 0 | 准备：clone `RuoYi-Vue` 与 `RuoYi-Vue3` 至临时目录；**锁定并记录 tag + commit hash**（跟随 master 漂移则基线不可复现）；确认 MIT LICENSE 保留 | 两个上游工程 | 基线记录（tag/commit 写入 `NOTICE` 与 PR 描述） |
| 1 | 建立 e-aio 父 POM 骨架：`groupId=com.eaio`、`artifactId=e-aio`、`<modules>` 仅含 `e-aio-app`、`e-aio-common`；引入 3.1.2 依赖基线 | 在 `backend/e-aio/` 下新建 `pom.xml` | `mvn -B compile` 通过 |
| 2 | 迁移 ruoyi-common → e-aio-common：**选择性迁移**（非全量拷贝）——取 `StringUtils`/`DateUtils`/`TreeUtils`/`Convert`/`ServletUtils`/雪花 ID/通用常量等，逐个核对许可证与 Boot 4 / Java 21 兼容后**改写为 e-aio 薄封装门面**（4.2）；`AjaxResult`→`Result<T>`、`BaseEntity`/分页 → `PageResult<T>` 按 3.2 契约重写。**不迁移** RuoYi 的安全工具与框架基座（`SecurityUtils` 随 iam、`SecurityConfig`/JWT 见步骤 3） | ruoyi-common | 第 4 章工具门面清单落位 |
| 3 | 认证**不在 P0 落地**：不迁移 `SecurityConfig`/JWT、不引入 Spring Security（3.1.2）；`WebConfig`/拦截器/全局异常 → 按 3.3 全局异常与 3.4 链路基础**重写**为 `com.eaio.app.web.*`（登录/认证入口由 P1 iam 实现） | ruoyi-framework（仅参考） | 空应用可启动、`/actuator/health` 可用 |
| 4 | ruoyi-system 拆分登记：用户/部门/岗位/角色/菜单/权限 → iam（P1）；字典/参数/公告 → platform（P1）；操作日志/登录日志 → audit（P1）。**P0 不搬入**，仅冻结契约 | ruoyi-system | 契约清单（6.3 待 P1 细化事项） |
| 5 | ruoyi-quartz 保留为 platform Scheduler 蓝本（P1 迁移 Spring Task + ShedLock）；**P0 移出父 POM** | ruoyi-quartz | 父 POM 无 quartz |
| 6 | ruoyi-generator → devtools：**P0 不搬入**，登记为 Vibe Coding 开发工具链（HLD 13.2.1） | ruoyi-generator | 运行时零残留 |
| 7 | 建立 Modulith 命名空间与 ArchUnit：按 3.1.3 分包（含各模块 `api` 包 `@NamedInterface`）与 3.7 规则集接入，`ApplicationModules.verify()` 纳入测试——**唯一落点 `e-aio-app`**（含 common 命名接口完备断言），common 只保留自身约束测试 | `e-aio-app`（arch 测试）、`e-aio-common`（自身约束测试） | 架构测试 0 违例 |
| 8 | API 风格改造契约：全局统一 POST + JSON + 动作词（Get/Add/Up/Del/业务动作）；幂等键头约定（3.2.5）；**springdoc 标注 P0 不引入**，P1 随 OpenAPI 3.x 文档一并接入（3.1.2） | 全后端 | 3.9 前端封装按此对接 |
| 9 | Flyway 迁移骨架接入：替换 RuoYi 的 SQL 脚本直连初始化方式（`sql/` 目录），改由 3.6 每模块独立 Flyway 实例管理（P0 仅 `platform → eaio_platform` 基线） | 数据库初始化 | 空库迁移通过 |
| 10 | CI 流水线：按 3.8 搭建 GitHub Actions（后端 Maven Job + 前端 npm Job 独立） | `.github/workflows/ci.yml` | PR 质量门全绿 |
| 11 | 前端 `frontend/`：**不拷贝 RuoYi-Vue3 源码**，按 3.9.2 自行搭建工程（改名 `e-aio-web`）——Vite Proxy、统一 request 封装（POST + JSON + 幂等键）、布局与页面模式**参考** RuoYi-Vue3；ESLint + `npm run build` 纳入 CI | `frontend/` | `npm run build` 与 `npm run lint` 通过 |
| 12 | 清理与合规收尾：父 POM 无 RuoYi 残留模块与未使用依赖；删除 RuoYi 私有常量/表注释残留；**`LICENSE`（Apache-2.0）与 `NOTICE`（RuoYi-Vue/Vue3 MIT 版权声明 + 蓝本 tag/commit）就位**；README、CONTRIBUTING、CODE_OF_CONDUCT、PR 模板、`docker-compose.yml`/`.env.example` 就位；**`Dockerfile` 与镜像推送属 P1**（3.8 阶段 7–8） | 全工程 | 对照 5.2 P0 验收清单 |

#### 3.10.3 包名重写与文件级操作示例

```bash
# 包名重写（示例）：ruoyi-common → backend/e-aio/e-aio-common
# 1) 物理目录迁移（在 backend/e-aio/ 下操作）
git mv ruoyi-common/src/main/java/com/ruoyi/common e-aio-common/src/main/java/com/eaio/common
# 2) 全量包名替换（IDE 或 sed 等价操作）
#    com.ruoyi.common → com.eaio.common
# 3) 关键类型改造
#    AjaxResult       → Result<T>（com.eaio.common.api）
#    BaseEntity       → 按用途拆为 PageResult<T> / BaseDO
#    Constants/UserConstants → RedisKeys 等按 e-aio 键规范重写
# 4) 构建验证（在 backend/e-aio/ 下执行）
mvn -pl e-aio-common -am clean compile
```

> **注意事项**：① 改造必须保留 RuoYi 版权声明与 LICENSE（MIT）；② 步骤 2/3 的迁移产物必须通过 3.7 ArchUnit（common 不依赖任何模块）；③ 步骤 4 的拆分登记冻结为 P1 契约，避免 P0 提前引入业务表；④ 前端清理后 RuoYi 页面（system 等）在 P1 portal 重建，P0 仅保留布局/路由/权限指令框架。

---

## 4. common 技术底座详细设计（顺序 2）

### 4.1 模块总体设计

**定位**：无状态通用工具库，被所有模块依赖，不依赖任何模块（HLD 5.1）。**不建表、无 Schema、不含业务逻辑**。

包结构：

```
com.eaio.common
├── api        # Result<T> / PageResult<T> / ErrorCode
├── exception  # BusinessException / SystemException
├── id         # IdGenerator（雪花）
├── json       # JsonUtils（Jackson 3 封装，不暴露 ObjectMapper 类型）
├── util       # 工具门面：DateUtils / StringUtils / CollectionUtils / BeanUtils / TreeUtils / SensitiveUtils / ConvertUtils
├── excel      # ExcelKit（Apache Fesod 封装）
├── redis      # RedisKit（缓存 + 轻量队列）、DistributedLock、RateLimiter、RedisKeys
└── validation # 自定义校验注解与约束
```

**P0 冻结清单（契约 V1）**：附录 6.2 列出全部对外类（方法签名见第 4 章各小节）；P1 起新增 API 向后兼容。

### 4.2 工具门面（Hutool 底座）

以 **Hutool** 为底座做薄封装，统一 e-aio 内部调用（禁止模块直接散用第三方工具，便于审计与替换）：

| 门面类 | 核心方法 | 说明 |
|--------|----------|------|
| `DateUtils` | `now()` / `format(d, pattern)` / `parse(str, pattern)` / `addDays` / `between` | 时间处理（UTC 存储、本地展示约定） |
| `StringUtils` | `isBlank` / `isNotBlank` / `truncate` / `mask` | 字符串工具 |
| `CollectionUtils` | `isEmpty` / `toMap` / `groupBy` | 集合工具 |
| `JsonUtils` | `toJson` / `fromJson` / `toMap` / `toList`（多态签名） | **Jackson 3**（Boot 4 管理）封装；**不暴露 `ObjectMapper` 类型**（3.1.2 版本策略） |
| `BeanUtils` | `copy` / `toMap`（MapStruct 用于性能敏感场景，反射拷贝仅限低频） | Bean 拷贝 |
| `TreeUtils` | `buildTree(list, rootId)` / `flatten(tree)` | 组织树/菜单树通用 |
| `SensitiveUtils` | `mask(mobile/email/idCard/bankNo)`；同包提供 `@Sensitive`（`SensitiveType` 枚举）与 Jackson `SensitiveSerializer` | 脱敏（JSON 序列化时自动应用） |
| `ConvertUtils` | `toLong` / `toInt` / `toBigDecimal` | 类型安全转换 |
| `IdGenerator` | `nextId()`（雪花） / `nextStr()` | 全局唯一 ID（BIGINT 主键） |

关键实现要点：

- **IdGenerator**：雪花算法（workerId 由配置注入，多实例分配）；时钟回拨保护（拒绝或等待）；返回 `long`，作为全部表主键。
- **JsonUtils**：基于 Boot 4 管理的 **Jackson 3**，统一时区（UTC）与空值策略；**对外只暴露 `toJson`/`fromJson`/`toMap`/`toList` 多态 API，签名中不出现 `ObjectMapper`/`JsonNode` 之外的 Jackson 类型**，业务模块禁止自建 ObjectMapper——Jackson 2→3 的 API 差异因此不会穿透到模块（4.2）。
- **SensitiveUtils**：`@Sensitive(type=MOBILE/ID_CARD/BANK/EMAIL/CUSTOM)` 注解 + Jackson 序列化器；存储为原文，仅展示层脱敏（符合 NFR-SEC-03）。

### 4.3 ExcelKit（Apache Fesod）

**选型依据**：Apache Fesod（原 FastExcel/EasyExcel 生态，Apache 孵化，Apache-2.0），流式读写、低内存（HLD 2.5 / 2.6.2；FR-PLT-05 要求 10 万+行）。

**对外 API（P0 冻结）**：

```java
package com.eaio.common.excel;

/** 读取：流式解析 + 行级回调 */
public class ExcelKit {
    // 读取到 List<DTO>（注解映射）
    public static <T> ImportResult<T> read(InputStream in, Class<T> clazz);
    public static <T> ImportResult<T> read(InputStream in, Class<T> clazz, ExcelReadOptions options);

    // 写入：List<DTO> 导出到 OutputStream（支持大数据流式）
    public static <T> void write(OutputStream out, Class<T> clazz, List<T> data);
    public static <T> void write(OutputStream out, Class<T> clazz, List<T> data, ExcelWriteOptions options);

    // 模板导出：预置表头/样式
    public static <T> void writeWithTemplate(OutputStream out, String templatePath, List<T> data);
}

/** 导入结果：含成功行 + 错误行定位 */
public class ImportResult<T> {
    private int total;              // 总行数
    private int successCount;
    private List<T> data;           // 校验通过的数据
    private List<ExcelError> errors; // 行级错误（行号+列+消息）
}

/** 字段映射：直接复用 Fesod 注解 org.apache.fesod.sheet.annotation.ExcelProperty
 *  （name/index/converter/required 等），e-aio 不自研同名单注解，避免同名歧义与迁移成本 */
```

要点：
- 字段映射直接复用 Fesod 的 `org.apache.fesod.sheet.annotation.ExcelProperty`（**不自研同名注解**），DTO 与导出模板解耦；
- 大数据导出走流式（Fesod 流式写出），内存峰值可控（NFR-PERF-04）；
- 导入错误逐行回显（行号 + 字段 + 原因），支撑前端批量修正；
- **异步导入不属 P0 冻结范围**：其进度/结果查询依赖 platform 的 `FileApi`（文件持久化）与 Scheduler（任务调度），P0 只冻结同步读写与模板导出；异步 API 随 FR-PLT-05 在 P1 冻结，届时以"任务 ID + 进度查询接口"补齐（调用方为业务模块，实现落在 platform）。
- 业务模块不直接依赖 Fesod，仅依赖 `ExcelKit`（门面隔离，未来可换实现）。

### 4.4 RedisKit

**能力**：缓存读写、分布式锁、限流、轻量队列（HLD 5.1；支撑缓存管理 FR-PLT-06、定时任务分布式锁防重（ShedLock 兼容））。

**对外 API（P0 冻结）**：

```java
package com.eaio.common.redis;

/** 缓存 + 轻量队列（唯一门面入口；命名避开 Spring Data `RedisCache` 同名冲突） */
public class RedisKit {
    public void set(String key, Object value);                    // 默认 TTL
    public void set(String key, Object value, Duration ttl);
    public <T> T get(String key, Class<T> clazz);
    public void delete(String key);
    public boolean expire(String key, Duration ttl);
    // 防穿透：空值缓存 setIfAbsent
    // 防击穿：互斥重建（配合 DistributedLock）

    // 轻量队列（Redis List/Stream：发布/消费/死信；事务后投递在 AFTER_COMMIT 监听器内调用）
    public void push(String queue, Object message);
    public <T> T poll(String queue, Class<T> clazz, Duration timeout);
}

/** 分布式锁 */
public class DistributedLock {
    // 基于 Redisson；等待/自动续期/可重入；返回 AutoCloseable 释放
    public AutoCloseable lock(String key, Duration waitTime, Duration leaseTime);
    public boolean tryLock(String key, Duration waitTime, Duration leaseTime);
}

/** 限流 */
public class RateLimiter {
    // 令牌桶（Redisson RRateLimiter）：key + 速率
    public boolean tryAcquire(String key, int permits);
}
```

**键规范**：`eaio:{env}:{biz}:{id}`，如 `eaio:prod:cache:crm:customer:1001`；统一在 `RedisKeys` 常量类登记，禁止散落字面量。

**一致性**：缓存失效走事件（`MasterDataChangedEvent` 等，P1 落地）；`RedisKit` 提供显式失效方法供事件监听器调用。

### 4.5 Lombok 接入规范

| 注解 | 使用场景 | 排除/注意 |
|------|----------|-----------|
| `@Data` | DTO / 简单实体 | 含继承的实体用 `@Getter/@Setter` + `@EqualsAndHashCode(callSuper=true)` |
| `@Builder` | 不可变 DTO、查询条件对象 | 与 `@AllArgsConstructor(access=PRIVATE)` 配合 |
| `@Slf4j` | 所有需要日志的类 | — |
| `@RequiredArgsConstructor` | 构造器注入（Spring Bean） | 替代 `@Autowired` 字段注入（强制） |

**强制**：Spring 依赖注入一律构造器注入（`@RequiredArgsConstructor`）；禁止字段注入。

### 4.6 Bean Validation 接入规范

- **标准注解**：`@NotNull` / `@NotBlank` / `@Size` / `@Min` / `@Max` / `@Pattern` / `@Valid` / `@Validated` 全模块统一使用；
- **自定义约束**：`@EnumValid`（枚举值校验）、`@Mobile`、`@IdCard`、`@Money` 在 `com.eaio.common.validation` 提供；
- **校验时机**：Controller 层入参校验（`@Validated`），Service 层入参再校验（防御式）；
- **错误映射**：校验失败统一映射 `PARAM_INVALID` + 首个字段消息（见 3.3）。

### 4.7 MapStruct 接入规范

- **用途**：DTO ⇆ 领域实体、模块间 DTO 转换（性能敏感、跨模块契约场景）；
- **约定**：Mapper 接口放 `com.eaio.<module>.api.mapper` 或领域层 `mapper` 包；命名 `XxxConverter`；
- **配置**：`componentModel = "spring"`（纳入 Spring 容器）、`nullValueCheckStrategy`、`unmappedTargetPolicy = ERROR`（未映射目标属性编译报错，防遗漏）；
- 低频拷贝（非性能敏感）允许 `BeanUtils` 反射，但跨模块 DTO 转换一律 MapStruct。

### 4.8 common 单元测试要求

| 被测对象 | 测试要点 | 覆盖率目标 |
|----------|----------|-----------|
| `IdGenerator` | 唯一性（并发 1000）、格式、时钟回拨 | 关键路径 100% |
| `JsonUtils` | 序列化/反序列化、日期格式、null 策略 | ≥ 90% |
| `SensitiveUtils` | 各类型脱敏规则、边界（空值/短串） | 100% |
| `TreeUtils` | 构建/展平、环与孤儿节点处理 | ≥ 90% |
| `ExcelKit` | 10 万行导出内存、导入错误定位、模板导出 | 集成测试覆盖 |
| `RedisKit` / `DistributedLock` / `RateLimiter` | 互斥重建、锁竞争、TTL、限流 | Testcontainers Redis |
| `Result`/`ErrorCode` | 静态工厂、traceId 填充 | 100% |

---

## 5. P0 测试与验收

### 5.1 测试设计概览

| 层级 | 范围 | 工具 | 验收门槛 |
|------|------|------|----------|
| 单元测试 | common 工具、异常、返回体 | JUnit 5 + AssertJ | 覆盖率 ≥ 80%（工具核心 100%） |
| 架构测试 | Modulith 边界、依赖方向 | ArchUnit | 0 违例 |
| 集成测试 | 应用启动、Flyway 迁移、Redis 连接 | Testcontainers + @SpringBootTest | 全绿 |
| 契约测试 | `Result<T>`/`PageResult<T>` 序列化 + `traceId` 回填 | JUnit 5 + Jackson（P0）；Spring Cloud Contract 或等价方案 P1 评估（Boot 4 兼容性待验证） | 全绿 |

### 5.2 P0 验收清单（对照 HLD 13.3）

| HLD 13.3 P0 验收 | 落地验证 |
|------------------|----------|
| 工程骨架 CI 绿灯 | GitHub Actions 全阶段通过 |
| ArchUnit 验证通过 | `ApplicationModules.verify()` + 自定义规则 0 违例 |
| Flyway 迁移可运行 | Testcontainers PostgreSQL 空库迁移成功：`eaio_platform` 骨架 Schema 自动创建、`flyway_schema_history` 落位、`V1__baseline.sql` 版本 = 1（P0 无业务表；其余模块 Schema 由 P1 各自脚本创建） |
| common 全部工具单测通过 | 第 4.8 节测试清单全绿，覆盖率达标 |

### 5.3 里程碑交付（对齐可研 7.1 阶段 0）

> **归属说明**：可研阶段 0 覆盖 M0–M2（跨 P0 与 P1 启动），P0 只交付"Modulith 骨架 + 立项评审材料"；配置化定制 PoC 与 AI PoC 在阶段 0 内完成，P0 仅登记状态与预留接口，不计入 P0 验收（5.2）。

| 交付 | 说明 |
|------|------|
| Modulith 骨架 | 可启动空应用 + 健康检查（Actuator `/actuator/health`） |
| 配置化定制 PoC | 由前端配置化渲染引擎 PoC 承载（P1 深化） |
| AI PoC | AI 网关连通性验证（spring-ai 集成，P1 正式化） |
| 立项评审材料 | 01–04 文档 + P0 演示 |

---

## 6. 附录

### 6.1 P0 错误码表（初稿，契约 V1）

| 错误码 | 含义 | 使用方 |
|--------|------|--------|
| 0 | 成功 | 全部 |
| 10000 | 参数校验失败 | 全部 |
| 10001 | 缺少必要参数 | 全部 |
| 10002 | 数据不存在 | 全部 |
| 10003 | 数据冲突（版本过期/重复） | 全部 |
| 10401 | 未认证或令牌失效 | iam（P1） |
| 10403 | 无权限执行该操作 | iam（P1） |
| 10500 | 系统内部错误 | 全部 |
| 10501 | 重复提交（幂等拦截） | 全部 |
| 10502 | 幂等校验不可用（Redis 不可用，fail-closed） | 全部 |
| 10503–19999 | 通用段预留（空号不回收） | — |

**模块登记表（单一事实来源）**：P1 起新增模块**必须**按下表登记（模块 → 根包 → Schema → 错误码段 → 状态），不得在别处另建映射；错误码在预留段内自增，空号不回收，跨模块不可能撞号（3.2.3）：

| 模块 | 根包 | Schema | 错误码段（预留 1000 号） | 状态 |
|------|------|--------|--------------------------|------|
| platform | `com.eaio.platform` | `eaio_platform` | 20000–20999 | P0 仅基线 Schema（无业务码），P1 启用 |
| iam | `com.eaio.iam` | `eaio_iam` | 21000–21999 | P1 |
| audit | `com.eaio.audit` | `eaio_audit` | 22000–22999 | P1 |
| workflow / approval | `com.eaio.workflow` / `com.eaio.approval` | `eaio_workflow` / `eaio_approval` | 23000–23999 / 24000–24999 | P1–P2 按 HLD 13.2 队列登记 |
| 其余业务模块 | `com.eaio.<module>` | `eaio_<module>` | 顺延 1000 号段 | 随批次登记 |

### 6.2 common 对外 API 清单（P0 冻结 V1）

| 包 | 类 | 说明 |
|----|-----|------|
| `com.eaio.common.api` | `Result<T>`、`PageResult<T>`、`ErrorCode` | 统一返回体/分页/错误码 |
| `com.eaio.common.exception` | `BusinessException`、`SystemException`、`IdempotentReplayException` | 异常类型 |
| `com.eaio.common.id` | `IdGenerator` | 雪花 ID |
| `com.eaio.common.json` | `JsonUtils` | Jackson 统一封装 |
| `com.eaio.common.util` | `DateUtils/StringUtils/CollectionUtils/BeanUtils/TreeUtils/SensitiveUtils/ConvertUtils`；同包 `@Sensitive`（+`SensitiveType`/`SensitiveSerializer`） | 工具门面 + 脱敏注解 |
| `com.eaio.common.excel` | `ExcelKit`、`ImportResult`、`ExcelError`、`ExcelReadOptions`、`ExcelWriteOptions` | Excel 导入导出（字段映射复用 Fesod `@ExcelProperty`，不另建同名类） |
| `com.eaio.common.redis` | `RedisKit`、`DistributedLock`、`RateLimiter`、`RedisKeys` | Redis 能力 |
| `com.eaio.common.validation` | `@EnumValid`、`@Mobile`、`@IdCard`、`@Money` | 自定义校验 |

### 6.3 待 P1 细化事项

1. `TenantCtx`（多组织上下文）实现与过滤器接入（iam 详细设计）；
2. `@AuditLog` 切面与 audit 模块接入（替换 P0 日志占位）；
3. platform 参数中心/字典/FileApi/Scheduler 表结构与 API 契约；
4. 数据权限 SQL 改写方案（iam 详细设计）；
5. 事件总线可靠性（重试/死信/幂等）具体实现；
6. 信创数据库适配层（方言抽象）方案；
7. **ArchUnit「模块登记完备」断言**（每个模块根包 `package-info` 带 `@ApplicationModule`）与「业务错误码落在登记段内」断言——P0 只有 `e-aio-app` / `e-aio-common`（common 非 Modulith 模块），**无可断言对象**，故留到 P1 首个业务模块接入时补测（3.7 已声明该要求）；
8. `eaio.idempotency.fail-open` 开关评估（P0 恒 fail-closed，见 3.2.5）。

---

*本详细设计说明书为 V1.2（P0 阶段）评审修订版，基于 01–03 文档编制；P1 起按 HLD 13.2 开发队列逐批次补充，并与上游文档保持可追溯性。*

### 修订记录

| 版本 | 日期 | 主要修订 |
|------|------|----------|
| V1.1 | 2026-09-19 | P0 阶段审核修订初版 |
| V1.2 | 2026-09-19 | 评审修订（第一/二轮盘问裁决）：① 契约与幂等——`10501` 覆盖 `PROCESSING`/`DONE` 两态、长任务改任务 ID + 轮询（3.2.5）、P0 无认证实现的契约边界（3.3）；② 依赖基线——删除 5 项 P0 不引入依赖（security/jjwt/springdoc/redisson/native-maven-plugin），Boot 4 starter 改名（`-web`→`-webmvc`、`-aop`→`-aspectj`）、新增 `spring-boot-starter-flyway`，逐行核实坐标与许可证（Hutool 坐标/许可、ArchUnit 许可、native-maven-plugin 许可）、锁定版本（3.1.1/3.1.2）；③ 架构测试唯一落点 `e-aio-app`、common 仅自身约束、错误码每模块 1000 号分段并加单测（3.1.3/3.2.3/3.7）；④ Flyway 增加 `eaio.flyway.enabled` 总开关与基线约束（3.5.2/3.6）；⑤ 质量门——阶段 6 OWASP 推迟 P1、阶段 5 本机无 Docker 可跳过、阶段 7–8 标 P1（3.8/5.2）；⑥ 蓝本基线核实（RuoYi-Vue 3.9.2 = Boot 4.1.0 / Java 17，前端 MIT）并据此把 3.10 步骤 2/3/7/8/11/12 由"全量拷贝"改为"选择性迁移 + 重写"，P0 不落认证。新增根 [`CONTEXT.md`](../CONTEXT.md) 与 [`docs/adr/0001`](adr/0001-unified-post-and-always-200-result-contract.md)、[`0002`](adr/0002-per-module-schema-and-flyway-instance.md)；⑦ 第三轮：切面 starter 定 `-aspectj`、`JsonUtils` 锁 Jackson 3 多态门面（4.2）、本地镜像锁 PostgreSQL 17（`pgvector/pgvector:pg17`）+ Redis 7、版本锁定粒度定补丁浮动；⑧ 第四轮：Modulith 模块侧登记 + ArchUnit 断言（3.1.3/3.7）、附录 6.1 扩为模块登记表（错误码段/Schema 单一事实来源）、前端 P0 最小壳与认证失败唯一落点（3.9.1/3.9.2）、`/api/<module>/<resource>/<Action>` 路径段、CI 运行环境与 compose 形态（3.8）；⑨ 第五轮：幂等校验不可用 fail-closed（新增 `10502`，3.2.5/6.1）、`ErrorCode` 定为枚举 + `BusinessErrorCode` 接口 + 分段断言（3.2.3）、P0 架构测试只上可执行规则集（3.7/6.3）、`.env.example` + `.gitignore` 忽略 `.env`（3.8）。 |
