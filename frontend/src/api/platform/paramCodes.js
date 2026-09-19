// platform param 段的错误码（P1-3 册 7.1、5.2；码值与枚举名逐字对齐后端 PlatformErrorCode）。
//
// 这里**只**放 platform 自己段内的码：通用段的 10001/10003/10403 在 `@/api/codes`，
// 通用段码不得被业务段重定义（后端 `PlatformErrorCode` 类注释第 2 条 + P1 册 7.1 空号与预留），
// 所以本文件不再声明它们，需要时从 `@/api/codes` import（见 `@/api/platform/param`）。
//
// 按资源放档案里，页面就不必散落"裸数字 → 提示"的 if。
export const PARAM_NOT_FOUND = 20001
export const PARAM_TYPE_MISMATCH = 20002
export const PARAM_DUPLICATED = 20004
export const PARAM_BUILTIN_READONLY = 20005
export const PARAM_SCOPE_INVALID = 20006
