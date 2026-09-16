# 企业级一体化管理系统（e-aio）详细设计说明书（DD）

> **项目名称**：企业级一体化管理系统（e-aio）
> **项目定位**：通用能力大一统 + 业务逻辑按企业定制的企业级开源系统
> **编制依据**：GB/T 8567-2006《计算机软件文档编制规范》、GB/T 9385-2008《计算机软件需求规格说明规范》
> **上游文档**：01-可行性研究报告、02-软件需求规格说明书（SRS）、03-概要设计说明书（HLD）
> **版本**：V1.0（P0 阶段）
> **日期**：2026-09-16
> **范围说明**：本文档按 HLD 第 13 章开发队列**分阶段编写**——当前版本覆盖 **P0 阶段（顺序 1 工程骨架 + 顺序 2 common）**；P1–P3 各模块的详细设计将在对应开发批次启动前补充，章节编号延续。

---

## 核心结论（执行摘要）

本详细设计说明书完成 **P0 阶段（工程地基）** 的类级设计：**工程骨架（顺序 1）** 与 **common 技术底座（顺序 2）**，作为全部后续模块的开发地基。

| 设计主题 | 关键决策 |
|------|----------|
| 工程形态 | Maven 多模块 + Spring Boot 4.1.x + Spring Modulith 2.1.x；单一可执行 Jar |
| 命名空间 | 模块根包 `com.eaio.<module>`，分包 `api/application/domain/infrastructure/events` |
| 契约核心 | `Result<T>` / `PageResult<T>` / `ErrorCode` / 全局异常处理在 common 冻结 V1 |
| 数据库 | Flyway 多 Schema 迁移骨架（每模块独立 Schema），P0 无业务表 |
| 质量门 | ArchUnit 规则集 + `ApplicationModules.verify()` + CI 流水线（GitHub Actions） |
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
| P1–P3（顺序 3–25） | ❌ 后续补充 | 待 P1 启动前编写 |

### 1.3 术语与约定

| 术语 | 说明 |
|------|------|
| 门面（Facade） | 对开源库的薄封装，统一 e-aio 内部调用入口 |
| 质量门（Quality Gate） | CI 中必须通过的检查项（编译/测试/架构/安全） |
| 契约（Contract） | 模块对外 API 签名 + DTO + 事件，冻结后变更走评审 |
| traceId | 请求级链路标识，贯穿日志与审计 |
| Schema | PostgreSQL 数据库模式，每模块独立 |
| Flyway | 数据库迁移工具（Apache-2.0） |
| ArchUnit | 架构单元测试框架（Apache-2.0） |

### 1.4 参考资料

1. 《企业级一体化管理系统可行性研究报告》（01）
2. 《企业级一体化管理系统软件需求规格说明书》（02）
3. 《企业级一体化管理系统概要设计说明书》（03），重点：2.2.2 模块边界、2.4 交互机制、2.6 RuoYi 蓝本、5.1 common、13.2 P0 队列、13.3 P0 验收
4. Spring Boot 4.1.x 官方文档
5. Spring Modulith 2.1.x 官方文档
6. Apache Fesod 官方文档（https://fesod.apache.org/zh-cn/docs/）
7. RuoYi-Vue 官方文档（Apache-2.0，前后端分离版）

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
| CI 流水线 | GitHub Actions 编译/测试/架构/安全/构建 | 10.3 |

### 2.2 P0 工程结构总览（后端）

```
e-aio/
├── pom.xml                      # 父 POM（依赖管理 BOM）
├── e-aio-app/                   # 启动模块（Spring Boot 可执行 Jar）
│   └── src/main/java/com/eaio/EaioApplication.java
├── e-aio-common/                # common 技术底座（顺序 2）
│   └── src/main/java/com/eaio/common/
├── e-aio-modules/               # 业务/能力模块（P1 起逐个新增）
│   ├── e-aio-platform/          # P1 顺序 3（预留）
│   └── ...
├── e-aio-web/                   # 前端工程（RuoYi-Vue3 蓝本，独立目录）
│   └── src/...
├── .github/workflows/ci.yml     # CI 流水线
└── docs/                        # 工程文档
```

> **命名说明**：Maven artifactId 前缀 `e-aio-`，模块根包统一 `com.eaio.<module>`。**P0 只创建 `e-aio-app` 与 `e-aio-common`**，业务模块目录在 P1 启动时按顺序新增，父 POM 模块清单同步维护。

### 2.3 P0 与后续阶段的关系

- **契约先行**：common 对外 API（`Result<T>`、`ErrorCode`、工具门面、ExcelKit、RedisKit）在 P0 冻结 V1，P1 起所有模块基于此开发；API 变更走评审（HLD 13.4）。
- **骨架复用**：工程骨架的包结构、异常处理、ArchUnit 规则、CI 流水线被所有后续模块直接复用，P0 阶段将"模板化"——新增模块 = 复制骨架模板 + 注册 Schema。
- **无业务表**：P0 不建业务表；Flyway 仅在 `eaio_platform`（预留）之外维护**迁移元数据与 schema 创建脚本**，供 P1 各模块继承。

---

## 3. 工程骨架详细设计（顺序 1）

### 3.1 工程初始化

#### 3.1.1 父 POM 与模块清单

父 `pom.xml` 职责：依赖版本管理（`<dependencyManagement>` + BOM）、插件版本、全局属性、模块清单。

| 配置项 | 值 |
|--------|-----|
| `spring-boot.version` | 4.1.x（最新稳定版） |
| `spring-modulith.version` | 2.1.x（与 Boot 4.x 配套） |
| `java.version` | 21（LTS） |
| `project.build.sourceEncoding` | UTF-8 |
| 打包 | Spring Boot Maven Plugin（`e-aio-app` 为 repackage 主模块） |
| 模块清单 | `e-aio-app`、`e-aio-common`（P1 起追加） |

#### 3.1.2 依赖版本基线（P0 引入的最小集）

| 依赖 | 用途 | 许可证 |
|------|------|--------|
| spring-boot-starter-web | Web 容器 | Apache-2.0 |
| spring-boot-starter-validation | Bean Validation 标准 | Apache-2.0 |
| spring-boot-starter-aop | 切面（审计/日志预留） | Apache-2.0 |
| spring-modulith-starter-core | 模块化单体 | Apache-2.0 |
| spring-modulith-starter-test | Modulith 测试 | Apache-2.0 |
| lombok | 样板代码生成 | MIT |
| hutool-all | 工具库底座 | MPL-2.0 |
| mapstruct + mapstruct-processor | DTO 映射 | Apache-2.0 |
| mybatis-plus-spring-boot3-starter | 持久层（P1 起生效，P0 仅引入） | Apache-2.0 |
| flyway-core + flyway-database-postgresql | 数据库迁移 | Apache-2.0 |
| postgresql | JDBC 驱动 | PostgreSQL |
| spring-boot-starter-data-redis | Redis | Apache-2.0 |
| archunit-junit5 | 架构测试 | Apache-2.0 |
| junit5 / assertj / testcontainers | 测试 | Apache-2.0 |
| springdoc-openapi-starter-webmvc-ui | OpenAPI 3.x 文档 | Apache-2.0 |
| fesod-core（Apache Fesod） | Excel 读写 | Apache-2.0 |

> **版本策略**：统一由父 POM 管理，锁定已知兼容组合；升级经 PR 评审并跑全量 CI。

#### 3.1.3 命名空间与包结构规范

模块根包：`com.eaio.<module>`（如 `com.eaio.common`、`com.eaio.platform`）。

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

#### 3.1.4 目录结构（前端 RuoYi-Vue3 初始化）

前端基于 RuoYi-Vue3 蓝本初始化（Vue3 + Vite + Element Plus），保留其成熟能力（路由/权限指令/字典/水印），改造点为统一请求封装（见 3.9）。

```
e-aio-web/
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
    // 静态工厂 from(IPage<T>) / from(total, records) 省略
}
```

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
    // 模块前缀：业务模块从 20000 起按前缀分配（CRM-1001 风格映射为数值码）
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
```

### 3.3 全局异常处理

`GlobalExceptionHandler` 位于 `e-aio-app`（`com.eaio.app.web`），`@RestControllerAdvice` 实现：

| 异常类型 | 处理结果 | 日志 |
|----------|----------|------|
| `BusinessException` | `Result.fail(code, message)` | WARN（含 traceId） |
| `MethodArgumentNotValidException` / `ConstraintViolationException` | `Result.fail(PARAM_INVALID, 首个错误消息)` | DEBUG |
| `IdempotentReplayException` | `Result.fail(IDEMPOTENT_REPLAY)` | INFO |
| `AccessDeniedException` | `Result.fail(FORBIDDEN)` | WARN |
| 其他 `Exception` | `Result.fail(SYSTEM_ERROR)`（不泄漏堆栈） | ERROR（堆栈入库） |

要点：
- **不向客户端泄漏堆栈与敏感信息**（HLD 7.1）；
- 统一记录 `traceId`，异常日志与审计联动（P1 audit 接入后替换切面占位）；
- 审计写入失败在强审计场景阻断（P1 实现，P0 预留扩展点）。

### 3.4 日志与链路基础

- **结构化日志**：logback 配置输出 JSON（`{"ts","level","traceId","module","msg",...}`），生产环境 JSON、本地控制台 pattern。
- **traceId 过滤器**：`TraceIdFilter`（OncePerRequestFilter）——从请求头 `X-Trace-Id` 读取或生成 UUID，写入 MDC；`Result.traceId` 自动回填。
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
    enabled: true
    locations: classpath:db/migration
    # 多 Schema：按模块目录组织，各模块在启动时声明自己的 schema
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: 6379
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}
server:
  port: 8080
eaio:
  security:
    jwt:
      secret: ${JWT_SECRET:}
      expire-minutes: 120
  trace:
    header: X-Trace-Id
```

敏感配置（密码/密钥）一律走环境变量或密钥管理，禁止明文入库。

### 3.6 Flyway 数据库迁移骨架

| 约定 | 说明 |
|------|------|
| 脚本位置 | `classpath:db/migration`（按模块分包：`db/migration/eaio_platform/`、`db/migration/eaio_org/`…） |
| 命名规范 | `V<版本>__<描述>.sql`（如 `V1__init_schema.sql`）；重复执行用 `R__` |
| Schema 管理 | 每个模块在 `application-{module}.yml` 声明 `spring.flyway.schemas=eaio_<module>`；启动时自动创建 schema 并执行迁移 |
| P0 内容 | 仅创建预留 schema（`eaio_platform` 等由 P1 声明）与 Flyway 元表；**无业务表** |
| 种子数据 | P0 提供骨架种子（系统参数初值），业务种子（字典/角色/流程模板）在对应模块 P1 落地时由各自迁移脚本注入 |

### 3.7 ArchUnit 质量门

`ArchitectureTest`（`e-aio-app/src/test/java`）固化规则集：

| 规则 | 说明 |
|------|------|
| 模块边界 | `ApplicationModules.verify()`：禁止访问其他模块 `internal` 包 |
| 无循环依赖 | Modulith 自动校验 |
| 分层依赖 | 业务模块 → 通用能力，禁止反向（`com.eaio.*` 依赖白名单/黑名单断言） |
| 包访问 | `internal/domain/infrastructure` 不得被跨模块引用 |
| 公共 API | 跨模块仅可依赖 `api` 包类型 |
| 禁止跨 Schema SQL | 静态检查 + 代码评审（P0 先以规则约束，P1 引入 SQL 审计插件可选） |

CI 中 `mvn verify` 自动执行；任何架构违例即构建失败（NFR-OSS-03 PR 质量门）。

### 3.8 CI 流水线（GitHub Actions）

`.github/workflows/ci.yml` 流水线：

| 阶段 | 步骤 | 失败即阻断 |
|------|------|-----------|
| 1 编译 | `mvn -B compile` | ✅ |
| 2 单元测试 | `mvn -B test`（含 ArchUnit） | ✅ |
| 3 架构测试 | `mvn -B verify -DskipITs`（Modulith verify） | ✅ |
| 4 集成测试 | Testcontainers 起 PostgreSQL/Redis 跑 `@SpringBootTest` | ✅ |
| 5 安全扫描 | 依赖漏洞（OWASP Dependency-Check）+ License 扫描 | ✅ |
| 6 镜像构建 | Docker 多阶段构建，推送 GHCR | ⏸（PR 跳过，主分支执行） |
| 7 发布 | 打 tag 时发布 Release | ⏸ |

配套：`CONTRIBUTING.md`、`CODE_OF_CONDUCT.md`、PR 模板（NFR-OSS-03/05）。

### 3.9 前端工程骨架（RuoYi-Vue3 蓝本）

#### 3.9.1 初始化

基于 RuoYi-Vue3（Vue3 + Vite + Element Plus，MIT）初始化，保留：动态路由、权限指令（`v-hasPermi`）、字典、水印、登录态管理。

#### 3.9.2 统一请求封装（统一 POST + JSON）

`src/utils/request.js`（axios 实例）按 HLD 2.6.4 约定实现：

```js
// 统一 POST + JSON：所有接口（含查询/删除/导出）走 POST
const request = (url, params = {}) =>
  axios.post(url, params, {
    headers: { 'Content-Type': 'application/json' },
    // 幂等键：写接口由调用方传入
    ...(params.__idempotencyKey ? { 'Idempotency-Key': params.__idempotencyKey } : {}),
  })

// 响应拦截：Result<T> 统一解包；code!==0 提示并 reject；HTTP 401/403 跳登录
```

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
├── json       # JsonUtils（Jackson 封装）
├── util       # 工具门面：DateUtils / StringUtils / CollectionUtils / BeanUtils / TreeUtils / SensitiveUtils / ConvertUtils
├── excel      # ExcelKit（Apache Fesod 封装）
├── redis      # RedisKit（缓存/锁/限流/队列）
├── validation # 自定义校验注解与约束
└── mapper     # MapStruct 映射基类约定（无代码，规范说明）
```

**P0 冻结清单（契约 V1）**：附录 6.2 列出全部对外类与方法签名；P1 起新增 API 向后兼容。

### 4.2 工具门面（Hutool 底座）

以 **Hutool** 为底座做薄封装，统一 e-aio 内部调用（禁止模块直接散用第三方工具，便于审计与替换）：

| 门面类 | 核心方法 | 说明 |
|--------|----------|------|
| `DateUtils` | `now()` / `format(d, pattern)` / `parse(str, pattern)` / `addDays` / `between` | 时间处理（UTC 存储、本地展示约定） |
| `StringUtils` | `isBlank` / `isNotBlank` / `truncate` / `mask` | 字符串工具 |
| `CollectionUtils` | `isEmpty` / `toMap` / `groupBy` | 集合工具 |
| `JsonUtils` | `toJson` / `fromJson` / `toMap` / `toList` | Jackson 封装，统一 ObjectMapper 配置（时区/空值策略） |
| `BeanUtils` | `copy` / `toMap`（MapStruct 用于性能敏感场景，反射拷贝仅限低频） | Bean 拷贝 |
| `TreeUtils` | `buildTree(list, rootId)` / `flatten(tree)` | 组织树/菜单树通用 |
| `SensitiveUtils` | `mask(mobile/email/idCard/bankNo)` + `@Sensitive` 注解 | 脱敏（JSON 序列化时自动应用） |
| `ConvertUtils` | `toLong` / `toInt` / `toBigDecimal` | 类型安全转换 |
| `IdGenerator` | `nextId()`（雪花） / `nextStr()` | 全局唯一 ID（BIGINT 主键） |

关键实现要点：

- **IdGenerator**：雪花算法（workerId 由配置注入，多实例分配）；时钟回拨保护（拒绝或等待）；返回 `long`，作为全部表主键。
- **JsonUtils**：统一 `ObjectMapper`（`JavaTimeModule`、`WRITE_DATES_AS_TIMESTAMPS=false`、空值策略），业务模块禁止自建 ObjectMapper。
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

    // 异步导入任务（配合 platform Scheduler，P1 落地调用方）
    public static <T> ImportResult<T> readAsync(InputStream in, Class<T> clazz, Consumer<ImportProgress> progress);
}

/** 导入结果：含成功行 + 错误行定位 */
public class ImportResult<T> {
    private int total;              // 总行数
    private int successCount;
    private List<T> data;           // 校验通过的数据
    private List<ExcelError> errors; // 行级错误（行号+列+消息）
}

/** 字段映射注解：@ExcelProperty(name="客户名称", index=0, required=true, validate=...) */
public @interface ExcelProperty { String name(); int index() default -1; boolean required() default false; }
```

要点：
- 字段映射用注解 `@ExcelProperty`（兼容 Fesod 注解体系），DTO 与导出模板解耦；
- 大数据导出走流式（`SXSSF` 类），内存峰值可控（NFR-PERF-04）；
- 导入错误逐行回显（行号 + 字段 + 原因），支撑前端批量修正；
- 业务模块不直接依赖 Fesod，仅依赖 `ExcelKit`（门面隔离，未来可换实现）。

### 4.4 RedisKit

**能力**：缓存读写、分布式锁、限流、轻量队列（HLD 5.1；支撑 FR-PLT-07、ShedLock 兼容）。

**对外 API（P0 冻结）**：

```java
package com.eaio.common.redis;

/** 缓存 */
public class RedisCache {
    public void set(String key, Object value);                    // 默认 TTL
    public void set(String key, Object value, Duration ttl);
    public <T> T get(String key, Class<T> clazz);
    public void delete(String key);
    public boolean expire(String key, Duration ttl);
    // 防穿透：空值缓存 setIfAbsent
    // 防击穿：互斥重建（配合 DistributedLock）
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

/** 轻量队列 */
public class QueueService {
    // 基于 Redis List/Stream：发布/消费/死信
    public void push(String queue, Object message);
    public <T> T poll(String queue, Class<T> clazz, Duration timeout);
    // 事务后投递（AFTER_COMMIT 事件监听器内调用）
}
```

**键规范**：`eaio:{env}:{biz}:{id}`，如 `eaio:prod:cache:crm:customer:1001`；统一在 `RedisKeys` 常量类登记，禁止散落字面量。

**一致性**：缓存失效走事件（`MasterDataChangedEvent` 等，P1 落地）；`RedisCache` 提供显式失效方法供事件监听器调用。

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
| `RedisCache`/`DistributedLock` | 互斥重建、锁竞争、TTL | Testcontainers Redis |
| `Result`/`ErrorCode` | 静态工厂、traceId 填充 | 100% |

---

## 5. P0 测试与验收

### 5.1 测试设计概览

| 层级 | 范围 | 工具 | 验收门槛 |
|------|------|------|----------|
| 单元测试 | common 工具、异常、返回体 | JUnit 5 + AssertJ | 覆盖率 ≥ 80%（工具核心 100%） |
| 架构测试 | Modulith 边界、依赖方向 | ArchUnit | 0 违例 |
| 集成测试 | 应用启动、Flyway 迁移、Redis 连接 | Testcontainers + @SpringBootTest | 全绿 |
| 契约测试 | `Result<T>`/`PageResult<T>` 序列化 | Spring Cloud Contract（可选） | 全绿 |

### 5.2 P0 验收清单（对照 HLD 13.3）

| HLD 13.3 P0 验收 | 落地验证 |
|------------------|----------|
| 工程骨架 CI 绿灯 | GitHub Actions 全阶段通过 |
| ArchUnit 验证通过 | `ApplicationModules.verify()` + 自定义规则 0 违例 |
| Flyway 迁移可运行 | Testcontainers PostgreSQL 空库迁移成功，多 Schema 创建正确 |
| common 全部工具单测通过 | 附录 4.8 测试清单全绿，覆盖率达标 |

### 5.3 里程碑交付（对齐可研 7.1 阶段 0）

| 交付 | 说明 |
|------|------|
| Modulith 骨架 | 可启动空应用 + 健康检查 `/health` |
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
| 10401 | 未认证或令牌失效 | security（P1） |
| 10403 | 无权限执行该操作 | security（P1） |
| 10500 | 系统内部错误 | 全部 |
| 10501 | 重复提交（幂等拦截） | 全部 |

### 6.2 common 对外 API 清单（P0 冻结 V1）

| 包 | 类 | 说明 |
|----|-----|------|
| `com.eaio.common.api` | `Result<T>`、`PageResult<T>`、`ErrorCode` | 统一返回体/分页/错误码 |
| `com.eaio.common.exception` | `BusinessException`、`SystemException` | 异常类型 |
| `com.eaio.common.id` | `IdGenerator` | 雪花 ID |
| `com.eaio.common.json` | `JsonUtils` | Jackson 统一封装 |
| `com.eaio.common.util` | `DateUtils/StringUtils/CollectionUtils/BeanUtils/TreeUtils/SensitiveUtils/ConvertUtils` | 工具门面 |
| `com.eaio.common.excel` | `ExcelKit`、`ImportResult`、`ExcelError`、`ExcelProperty` | Excel 导入导出 |
| `com.eaio.common.redis` | `RedisCache`、`DistributedLock`、`RateLimiter`、`QueueService`、`RedisKeys` | Redis 能力 |
| `com.eaio.common.validation` | `@EnumValid`、`@Mobile`、`@IdCard`、`@Money` | 自定义校验 |

### 6.3 待 P1 细化事项

1. `TenantCtx`（多组织上下文）实现与过滤器接入（security/org 详细设计）；
2. `@AuditLog` 切面与 audit 模块接入（替换 P0 日志占位）；
3. platform 参数中心/字典/FileApi/Scheduler 表结构与 API 契约；
4. 数据权限 SQL 改写方案（security 详细设计）；
5. 事件总线可靠性（重试/死信/幂等）具体实现；
6. 信创数据库适配层（方言抽象）方案。

---

*本详细设计说明书为 V1.0（P0 阶段）草案，基于 01–03 文档编制；P1 起按 HLD 13.2 开发队列逐批次补充，并与上游文档保持可追溯性。*
