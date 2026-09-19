# API 与契约规约（冻结 V1，评审红线）

## 请求

- **全部接口 POST + JSON**（含查询、删除、导出）。不按 REST 资源语义建 URL，不用 GET/PUT/DELETE。
- 路径 `/api/<module>/<resource>/<action>`，动作体现在接口名：`Get`（单条）/ `GetPage`（分页）/ `Add`（新增）/ `Up`（更新）/ `Del`（逻辑删除）+ 业务动作词（`Submit` / `Approve` / `Export` …）。
  例：`POST /api/crm/customer/GetPage`、`POST /api/crm/customer/Add`。
- 分页参数入 JSON body：`pageNum` / `pageSize` / 排序字段。
- 文件上传走 `multipart/form-data`（`FileApi/Upload`），下载走二进制流（`FileApi/Download`），统由 **platform 模块的 FileApi** 提供。
- 鉴权：`Authorization: Bearer <JWT>`。

## 响应

- **HTTP 状态码恒为 200**，业务结果由 body 的 `code` 表达；认证/鉴权失败同样返回 200 + `10401` / `10403`。
- 返回体 `Result<T>`：`code` / `message` / `data` / `traceId`；分页 `Result<PageResult<T>>`。
- 错误码 `ErrorCode` 枚举：`0` 成功；`10000–19999` 通用段（10000 参数校验失败、10001 缺少参数、10002 数据不存在、10003 数据冲突、10401 未认证、10403 无权限、10500 系统错误、10501 重复提交）；`20000+` 业务段按模块前缀分配（附录维护）。
- 异常：业务抛 `BusinessException(ErrorCode)`；系统异常走 `SystemException`；**不向客户端泄漏堆栈**。
- `traceId`：请求头 `X-Trace-Id` 透传/生成 → 写 MDC → `Result.traceId` 自动回填。

## 幂等键（写接口强制）

- 写接口（`Add` / `Up` / `Del` 及业务动作）前端携带 `Idempotency-Key` 请求头；后端 `IdempotencyFilter` 用 Redis SETNX 占位（键 `eaio:{env}:idem:{sha256(URL+key)}`，TTL 24h），重复请求返回 `10501 重复提交`。
- 占位状态：`PROCESSING`（并发重入防护）→ 成功置 `DONE`（TTL 24h）；业务失败/系统异常**立即释放占位**，允许用户修正后重试（细节见 P0 册 3.2.5）。不缓存、不回放历史结果。
- 查询接口不启用；幂等键缺失时放行并记 WARN。

> 前端响应拦截据此判定跳登录，见 [frontend-conventions.md](frontend-conventions.md)。
