import axios from 'axios'

import { redirectToLogin, clearLogin } from '@/auth/session'
import { IDEMPOTENCY_KEY_HEADER, SUCCESS_CODE, UNAUTHORIZED_CODE } from '@/api/codes'
import { newIdempotencyKey } from '@/api/idempotency'

/** 后缀动作词（Get/Add/Up/Del/业务动作）用 `/` 直接接在资源后面。 */
export const ACTION_PATH_SEPARATOR = '/'

/** 拼接基础地址与动作路径，保证只有单个斜杠。 */
export function buildUrl(baseURL, actionPath) {
  const base = (baseURL ?? '').replace(/\/+$/, '')
  const action = String(actionPath ?? '').replace(/^\/+/, '')
  return `${base}${ACTION_PATH_SEPARATOR}${action}`
}

/** 非 0 业务码失败。 */
export class ApiError extends Error {
  constructor(code, message, traceId) {
    super(message || `请求失败（code=${code}）`)
    this.name = 'ApiError'
    this.code = code
    this.traceId = traceId
  }

  /** 登录失效：只看响应体 code，不看 HTTP 状态码（ADR-0001）。 */
  isAuthExpired() {
    return this.code === UNAUTHORIZED_CODE
  }
}

/** 登录失效时的处理出口，便于替换（单测注入探针 / 未来接入 iam 刷新逻辑）。 */
export const authExpiry = {
  handle: () => {
    clearLogin()
    redirectToLogin()
  },
}

export const http = axios.create({
  baseURL: '/api',
  timeout: 30_000,
  headers: { 'Content-Type': 'application/json' },
})

http.interceptors.request.use((config) => {
  // 契约：全局统一 POST + JSON（ADR-0001）。这里硬校验，防止有人顺手写 get()，
  // 那会让"统一 POST"在一次提交里悄悄失效。
  if ((config.method ?? 'post').toLowerCase() !== 'post') {
    return Promise.reject(new Error(`本工程只允许 POST 调用，收到：${config.method}`))
  }
  config.method = 'post'
  return config
})

http.interceptors.response.use(
  (response) => {
    const envelope = response.data
    if (!envelope || typeof envelope !== 'object' || typeof envelope.code !== 'number') {
      return Promise.reject(
        new ApiError(-1, '响应体不是约定的 Result 结构（缺少 code）', response.headers?.['x-trace-id']),
      )
    }
    if (envelope.code === SUCCESS_CODE) {
      // 成功：解包 data，业务调用方直接拿到数据
      return { data: envelope.data, message: envelope.message, traceId: envelope.traceId }
    }
    const error = new ApiError(envelope.code, envelope.message, envelope.traceId)
    if (error.isAuthExpired()) {
      authExpiry.handle()
    }
    return Promise.reject(error)
  },
  // 网络层错误（超时/断网）：后端 HTTP 恒 200，走到这里说明请求没到达或代理异常
  (error) => Promise.reject(new ApiError(-1, `网络请求失败：${error.message}`)),
)

/**
 * 统一动作调用。
 *
 * @param actionPath 动作地址，如 `/platform/dict/Get`、`/iam/auth/Login`
 * @param params 请求体（JSON）；分页参数也走 body
 * @param options.idempotencyKey 幂等键。**未提供时自动生成**（等价于"调用即一次新动作"）；
 *        需要"同一动作重试复用同一键"时，由调用方生成一次并显式传入。
 */
export async function action(actionPath, params = {}, options = {}) {
  const write = options.idempotencyKey !== null
  const config = {
    url: buildUrl('', actionPath),
    method: 'post',
    data: params,
    headers: {},
  }
  if (write) {
    config.headers[IDEMPOTENCY_KEY_HEADER] = options.idempotencyKey ?? newIdempotencyKey()
  }
  return http.request(config)
}

/** 只读查询：不生成幂等键。 */
export function query(actionPath, params = {}) {
  return action(actionPath, params, { idempotencyKey: null })
}
