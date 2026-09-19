// e-aio 错误码（与后端 common 的 ErrorCode 对齐，见 docs/04 P0 册 3.2.3 与附录 6.1）
// 只登记前端需要判定的通用段；业务段（20000+）由各模块按段分配，前端不硬编码。
export const SUCCESS_CODE = 0

/** 未认证或令牌失效：按 ADR-0001，登录失效只看响应体 code，不读 HTTP 401/403。 */
export const UNAUTHORIZED_CODE = 10401

/** 无权限执行该操作（后端 `ErrorCode.FORBIDDEN`）。 */
export const FORBIDDEN_CODE = 10403

/**
 * 缺幂等键（后端 `ErrorCode.PARAM_MISSING` 10001；入站链路的 `IdempotencyFilter` 对写动作缺键复用该码，
 * P1-3 册 5.6 收紧口径）。
 */
export const IDEMPOTENCY_KEY_MISSING = 10001

/** 数据冲突（版本过期/重复，后端 `ErrorCode.DATA_CONFLICT`）：`version` 已被他人改过。 */
export const DATA_CONFLICT = 10003

/** 幂等键请求头。 */
export const IDEMPOTENCY_KEY_HEADER = 'X-Idempotency-Key'
