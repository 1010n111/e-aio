# API 与契约规约（冻结 V1，评审红线）

## 请求

- **全部接口 POST + JSON**（含查询、删除、导出）。不按 REST 资源语义建 URL，不用 GET/PUT/DELETE。
- 路径 `/api/<module>/<resource>/<action>`，动作体现在接口名：`Get`（单条）/ `GetPage`（分页）/ `Add`（新增）/ `Up`（更新）/ `Del`（逻辑删除）+ 业务动作词（`Submit` / `Approve` / `Export` …）。
  例：`POST /api/crm/customer/GetPage`、`POST /api/crm/customer/Add`。
- 分页参数入 JSON body：`pageNum` / `pageSize` / 排序字段。
- 文件上传走 `multipart/form-data`（`FileApi/Upload`），下载走二进制流（`FileApi/Download`），统由 **platform 模块的 FileApi** 提供。
- 鉴权：`Authorization: Bearer <JWT>`。

## 协议端点例外清单（[ADR-0003](../adr/0003-protocol-endpoint-exceptions.md)）

以上"全部 POST + JSON"与"恒 200"是**常规模式**的规则。下表是**协议模式**的**封闭例外清单**——方法、Content-Type、状态码由协议规定，**不套 `Result`**；不在表内的路径一律按常规模式处理，新增例外须走 Issue 评审并更新本表。

| 端点 | 形态 | 依据 |
|---|---|---|
| `/oauth2/authorize` | GET + 302/400 | RFC 6749 §4.1 |
| `/oauth2/token` | POST + `x-www-form-urlencoded` + 200/400/401 | RFC 6749 §4.1.3/§5.2 |
| `/oauth2/revoke`、`/oauth2/introspect` | POST + form + 标准响应 | RFC 7009、RFC 7662 |
| `/.well-known/openid-configuration`、`/oauth2/jwks` | GET + JSON | OIDC Discovery、RFC 8414 |
| `/userinfo` | GET/POST + Bearer + JSON（非 `Result`） | OIDC Core §5.3 |
| `/saml2/metadata`、`/saml2/sso`、`/saml2/acs`、`/saml2/slo` | GET/POST + XML + 302 | SAML 2.0 Web SSO |
| `/api/platform/file/download`、`/api/platform/file/chunk` | 二进制流 + `Content-Length`/`Range` | HTTP 语义（断点续传） |

约定：`TraceIdFilter` 覆盖全部路径；`IdempotencyFilter` 只作用于常规模式写接口；`ApiResponseAdvice` / `GlobalExceptionHandler` 不介入例外路径；前端请求层对例外路径走独立封装（不套 `Result` 解包与 `10401` 跳登录）。

## 响应

- **HTTP 状态码恒为 200**，业务结果由 body 的 `code` 表达；认证/鉴权失败同样返回 200 + `10401` / `10403`。
- 返回体 `Result<T>`：`code` / `message` / `data` / `traceId`；分页 `Result<PageResult<T>>`。
- 错误码 `ErrorCode` 枚举：`0` 成功；`10000–19999` 通用段（10000 参数校验失败、10001 缺少参数、10002 数据不存在、10003 数据冲突、10401 未认证、10403 无权限、10500 系统错误、10501 重复提交、10502 幂等校验不可用）；`20000+` 业务段每模块预留 1000 号。**登记表**：P0 模块见 04-DD P0 册附录 6.1，P1 模块（platform 20000–20999 / iam 21000–21999 / audit 22000–22999）见 04-DD P1 批次总册 6.1；**同段内的具体号以该模块单模块分册的号表为唯一号源**。
- 异常：业务抛 `BusinessException(ErrorCode)`；系统异常走 `SystemException`；**不向客户端泄漏堆栈**。
- `traceId`：请求头 `X-Trace-Id` 透传/生成 → 写 MDC → `Result.traceId` 自动回填。

## 幂等键（写接口强制）

- 写接口（`Add` / `Up` / `Del` 及业务动作）前端携带 `Idempotency-Key` 请求头；后端 `IdempotencyFilter` 用 Redis SETNX 占位（键 `eaio:{env}:idem:{sha256(URL+key)}`，TTL 24h），重复请求返回 `10501 重复提交`。
- 占位状态：`PROCESSING`（并发重入防护）→ 成功置 `DONE`（TTL 24h）；业务失败/系统异常**立即释放占位**，允许用户修正后重试（细节见 P0 册 3.2.5）。不缓存、不回放历史结果。
  - **`PROCESSING` 与 `DONE` 命中均返回 `10501`**（执行中重放同属重复提交，客户端不等待、不轮询该接口）；长任务（导出/批量导入）**不得以幂等键承担结果交付**，走任务 ID + 轮询。
  - **fail-closed（P0 起强制）**：Redis 不可用导致无法判定重复时，`IdempotencyFilter` 拒绝执行并返回 `10502`（幂等校验不可用）+ ERROR 日志（含 traceId），**不静默放行**——放行会在 Redis 抖动期间真实重复写入。代价：写接口短时不可用，读接口不受影响。
- 查询接口不启用；幂等键缺失时放行并记 WARN。

> 前端响应拦截据此判定跳登录，见 [frontend-conventions.md](frontend-conventions.md)。
