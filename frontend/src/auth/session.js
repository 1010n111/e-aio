import { reactive } from 'vue'

/** 登录页路径（与路由一致）。 */
export const LOGIN_PATH = '/login'

/**
 * 登录态占位（P0）：只保留"是否已登录"这一个事实与跳转出口。
 *
 * 真实登录、令牌刷新、用户信息加载属 P1（iam）；这里刻意不存令牌——
 * P0 后端无认证实现，前端存一个假令牌只会让 P1 误以为认证已就绪。
 *
 * 这里也**不存权限点**：M1–M2 不做前端按钮级控制（P1-3 册 3.10.2 / 册 440 行第 18 条），
 * 按钮显隐等 iam 交付后由 `v-hasPermi` 指令统一接管（P0 册 3.10 步骤 11、P1-4 册 3.12）。
 * 越权请求后端 `@PreAuthorize` 仍会拒（10403），前端只按 `Result.code` 提示。
 */
export const authState = reactive({
  loggedIn: false,
})

const STORAGE_KEY = 'eaio.loggedIn'

export function isLoggedIn() {
  try {
    return authState.loggedIn || globalThis.localStorage?.getItem(STORAGE_KEY) === '1'
  } catch {
    return authState.loggedIn
  }
}

export function markLoggedIn() {
  authState.loggedIn = true
  try {
    globalThis.localStorage?.setItem(STORAGE_KEY, '1')
  } catch {
    // 隐私模式下 localStorage 不可用：内存态仍然有效
  }
}

export function clearLogin() {
  authState.loggedIn = false
  try {
    globalThis.localStorage?.removeItem(STORAGE_KEY)
  } catch {
    // 同上
  }
}

/**
 * 登录失效时跳登录页。用 location 跳转而不是 router：请求层不依赖路由实例，
 * 这样它在任何入口（含单测）都能工作，也不会形成 router → request → router 的循环依赖。
 */
export function redirectToLogin() {
  if (typeof window === 'undefined') {
    return
  }
  if (window.location.pathname !== LOGIN_PATH) {
    window.location.assign(LOGIN_PATH)
  }
}
