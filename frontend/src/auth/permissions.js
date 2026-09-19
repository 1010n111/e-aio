import { reactive } from 'vue'

/**
 * 权限点（P1 册 7.3）：形状 `platform:<resource>:<action>`，与后端 `@PreAuthorize` 逐字一致。
 *
 * **这不是安全边界**：按钮隐藏只省一次注定被拒的往返，真正的判定在后端
 * （P1 册 5.6 第 5 条 + 7.1 备注：iam 交付前权限点是"契约载体"）。
 *
 * **iam 未交付**（本仓库现状）：`permissions` 为空 = 权限未知，此时一律放行，
 * 与 P1 册 3.10.2 / 路由注释的口径一致——不硬编码假权限数据，也不假装权限已经生效。
 * iam 下发权限码后调 `setPermissions(codes)`，空集合之外的判定就立刻生效（无权限 → 隐藏）。
 */
export const authState = reactive({ loggedIn: false, permissions: [] })

let delivered = false

/** iam 下发权限码的唯一入口；传空数组表示"后端确认该用户没有任何权限点"。 */
export function setPermissions(codes) {
  delivered = true
  authState.permissions = Array.isArray(codes) ? codes.map(String) : []
}

/** 退出登录/权限未知时回到"未下发"状态。 */
export function clearPermissions() {
  delivered = false
  authState.permissions = []
}

/** 该权限点是否已下发。 */
export function hasPermission(permission) {
  if (!delivered) {
    return true
  }
  return authState.permissions.includes(permission)
}
