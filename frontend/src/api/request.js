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
    // 二进制下载的 body 不是 `Result` 信封（P1 册 3.10.4 的例外二）：原样交回 `download()`，
    // 由它按 `Content-Type` 分辨"文件"还是"装着错误的 JSON"。JSON 请求仍必须过下面的信封校验，
    // 否则"少解一层 code"会在业务侧静默变成 undefined。
    if (response.config?.responseType === 'blob') {
      return response
    }
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

/**
 * 上传（`multipart/form-data`）：P1 册 3.10.4 的例外一。
 *
 * 两处与 `action` 不同，都不是风格问题：
 * - `Content-Type: null`：实例默认头是 `application/json`，Axios 见 FormData 带着 JSON 头会把它
 *   **序列化成 JSON 字符串**发出去（文件内容变成 `{}`）；手写 `multipart/form-data` 又会丢掉 boundary，
 *   服务端解不出任何 part。置 null 才是"删掉这个头"，让浏览器自己带 `boundary`。
 * - 幂等键默认**不带**：上传服务端自幂等（5.2 的"幂等"列 = 否），只在调用方显式给了非空键时才带上。
 */
export async function upload(actionPath, formData, options = {}) {
  const config = {
    url: buildUrl('', actionPath),
    method: 'post',
    data: formData,
    headers: { 'Content-Type': null },
  }
  if (typeof options.idempotencyKey === 'string' && options.idempotencyKey !== '') {
    config.headers[IDEMPOTENCY_KEY_HEADER] = options.idempotencyKey
  }
  return http.request(config)
}

/**
 * 响应头 `Content-Disposition` → 文件名。
 *
 * 后端给的是 `attachment; filename*=UTF-8''<百分号转义>`（RFC 5987）；`filename*=UTF-8''中文.pdf`
 * 不能按 latin-1 读，得先 `decodeURIComponent`。老式 `filename="a b.pdf"` 作为回落：
 * 没有这个头（或缺文件名）时返回 `null`，由调用方决定退化成什么名字。
 */
export function parseContentDisposition(header) {
  const text = typeof header === 'string' ? header : ''
  if (text === '') {
    return null
  }
  const extended = /filename\*\s*=\s*UTF-8''([^;]+)/i.exec(text)
  if (extended) {
    const raw = extended[1].trim()
    try {
      return decodeURIComponent(raw)
    } catch {
      // 转义被截断/非法时退回原文：文件名难看总好过整次下载失败
      return raw
    }
  }
  const plain = /filename\s*=\s*"?([^";]*)"?/i.exec(text)
  const name = plain ? plain[1].trim() : ''
  return name === '' ? null : name
}

/** `Content-Type` 是否是 JSON `Result` 信封（后端 HTTP 恒 200，失败体也是 200 + JSON）。 */
export function isJsonContentType(contentType) {
  return String(contentType ?? '')
    .toLowerCase()
    .startsWith('application/json')
}

/** 下载失败体（200 + JSON `Result`）→ 与 JSON 响应拦截器同款的 `ApiError`。 */
async function failureFromBlob(blob) {
  let text = ''
  try {
    text = typeof blob?.text === 'function' ? await blob.text() : ''
  } catch {
    text = ''
  }
  let envelope
  try {
    envelope = JSON.parse(text)
  } catch {
    return new ApiError(-1, '下载失败：响应体既不是文件内容也不是 Result JSON（HTTP 恒 200，可能是网关错误页）')
  }
  if (!envelope || typeof envelope !== 'object' || typeof envelope.code !== 'number') {
    return new ApiError(-1, '响应体不是约定的 Result 结构（缺少 code）')
  }
  const error = new ApiError(envelope.code, envelope.message, envelope.traceId)
  if (error.isAuthExpired()) {
    authExpiry.handle()
  }
  return error
}

/**
 * 下载（`responseType: 'blob'`）：P1 册 3.10.4 的例外二。
 *
 * 成功 → `{ data: Blob, filename, contentType }`；失败时**HTTP 仍是 200**，只能按响应头
 * `Content-Type` 分辨"这是文件"还是"这是一段错误 JSON"——不分辨就会把错误 JSON 当文件存到用户磁盘上。
 */
export async function download(actionPath, params = {}) {
  const response = await http.request({
    url: buildUrl('', actionPath),
    method: 'post',
    data: params,
    responseType: 'blob',
    headers: {},
  })
  const contentType = response.headers?.['content-type']
  if (isJsonContentType(contentType)) {
    throw await failureFromBlob(response.data)
  }
  return {
    data: response.data,
    filename: parseContentDisposition(response.headers?.['content-disposition']),
    contentType: contentType ?? null,
  }
}
