// platform param 段的错误码（P1-3 册 7.1、5.2；码值与枚举名逐字对齐后端 PlatformErrorCode）。
//
// 为什么不放进 `@/api/codes`：那里是**与业务无关的协议/通用段**（10000/10401/10403），
// 通用段码不得被 platform 重定义（P1 册 7.1 空号与预留）；20001–20006 是 param 资源自己的段，
// 按资源放档案里，页面就不必散落"裸数字 → 提示"的 if。
export const PARAM_NOT_FOUND = 20001
export const PARAM_TYPE_MISMATCH = 20002
export const PARAM_DUPLICATED = 20004
export const PARAM_BUILTIN_READONLY = 20005
export const PARAM_SCOPE_INVALID = 20006

/** 缺幂等键（5.6 收紧口径）：写动作没带键就是前端 bug，用户能做的只有重试。 */
export const IDEMPOTENCY_KEY_MISSING = 10001

/** 乐观锁冲突（通用码，platform 不新增平台码）：`version` 已被他人改过。 */
export const DATA_CONFLICT = 10003

/** 无权限（通用段）：按钮隐藏只是体验，后端仍会按 `Result.code` 拒绝，这里给出可执行提示。 */
export const FORBIDDEN = 10403
