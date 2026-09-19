import { describe, expect, it, vi, beforeEach } from 'vitest'
import axios from 'axios'

import {
  ApiError,
  action,
  authExpiry,
  buildUrl,
  http,
  query,
} from '@/api/request'
import { IDEMPOTENCY_KEY_HEADER, UNAUTHORIZED_CODE } from '@/api/codes'
import { newIdempotencyKey, withIdempotencyKey } from '@/api/idempotency'

/** 捕获请求并把指定返回体喂给 axios 全链路（不启 HTTP 服务）。 */
function stubServer(envelope, status = 200) {
  const captured = []
  http.defaults.adapter = async (config) => {
    captured.push(config)
    return {
      data: envelope,
      status,
      statusText: status === 200 ? 'OK' : 'Error',
      headers: new axios.AxiosHeaders(),
      config,
    }
  }
  return captured
}

function successEnvelope(data) {
  return { code: 0, message: '成功', data, traceId: 't-1' }
}

describe('请求层：统一 POST + JSON（ADR-0001）', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.clear()
  })

  it('地址拼接只产生单个斜杠', () => {
    expect(buildUrl('/api/', '/platform/dict/Get')).toBe('/api/platform/dict/Get')
    expect(buildUrl('/api', 'platform/dict/Get')).toBe('/api/platform/dict/Get')
    expect(buildUrl('', '/iam/auth/Login')).toBe('/iam/auth/Login')
  })

  it('无论调用哪个动作，HTTP 方法都是 POST', async () => {
    const captured = stubServer(successEnvelope({ ok: true }))

    await action('/platform/dict/Get', { id: 1 })

    expect(captured).toHaveLength(1)
    expect(captured[0].method).toBe('post')
  })

  it('写操作自动带幂等键，且键只在请求头、不进 body', async () => {
    const captured = stubServer(successEnvelope({ id: 9 }))

    await action('/platform/dict/Add', { code: 'X' })

    const config = captured[0]
    expect(config.headers[IDEMPOTENCY_KEY_HEADER]).toMatch(/^[0-9a-f-]{36}$/)
    // 请求体是 JSON 字符串（axios 序列化后的真实形态），且不含幂等键
    const body = JSON.parse(config.data)
    expect(body).toEqual({ code: 'X' })
    expect(config.data).not.toContain(IDEMPOTENCY_KEY_HEADER)
  })

  it('幂等键不落 localStorage（落盘会让下一次动作复用旧键）', async () => {
    stubServer(successEnvelope(null))

    await action('/platform/dict/Del', { id: 1 })

    expect(Object.keys(localStorage)).toHaveLength(0)
  })

  it('同一动作重试复用同一个键：调用方生成一次并两次传入', async () => {
    const captured = stubServer(successEnvelope(null))
    const key = newIdempotencyKey()

    const config = withIdempotencyKey({ headers: {} }, key)
    await action('/platform/dict/Add', { code: 'X' }, { idempotencyKey: config.headers[IDEMPOTENCY_KEY_HEADER] })
    await action('/platform/dict/Add', { code: 'X' }, { idempotencyKey: config.headers[IDEMPOTENCY_KEY_HEADER] })

    expect(captured[0].headers[IDEMPOTENCY_KEY_HEADER]).toBe(key)
    expect(captured[1].headers[IDEMPOTENCY_KEY_HEADER]).toBe(key)
  })

  it('缺少幂等键时拒绝执行，不偷偷补一个', () => {
    expect(() => withIdempotencyKey({ headers: {} }, undefined)).toThrow(/幂等键/)
  })

  it('只读查询不带幂等键', async () => {
    const captured = stubServer(successEnvelope([]))

    await query('/platform/dict/List', { pageNum: 1, pageSize: 20 })

    expect(captured[0].headers[IDEMPOTENCY_KEY_HEADER]).toBeUndefined()
    expect(JSON.parse(captured[0].data)).toEqual({ pageNum: 1, pageSize: 20 })
  })

  it('成功时解包 data，业务调用方不接触 Result 结构', async () => {
    stubServer(successEnvelope({ total: 3 }))

    const result = await action('/platform/dict/List', {})

    expect(result.data).toEqual({ total: 3 })
    expect(result.traceId).toBe('t-1')
  })
})

describe('请求层：失败与登录失效（只按响应体 code 判定）', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.clear()
  })

  it('非 0 业务码 reject，并带上 code 与 traceId', async () => {
    stubServer({ code: 10000, message: '参数校验失败', data: null, traceId: 't-2' })

    const failure = await action('/platform/dict/Add', {}).catch((error) => error)

    expect(failure).toBeInstanceOf(ApiError)
    expect(failure.code).toBe(10000)
    expect(failure.message).toBe('参数校验失败')
    expect(failure.traceId).toBe('t-2')
  })

  it('登录失效码触发跳登录，且只认响应体 code', async () => {
    const handle = vi.spyOn(authExpiry, 'handle').mockImplementation(() => {})
    stubServer({ code: UNAUTHORIZED_CODE, message: '未认证或令牌失效', data: null, traceId: 't-3' })

    const failure = await query('/platform/dict/List', {}).catch((error) => error)

    expect(failure.isAuthExpired()).toBe(true)
    expect(handle).toHaveBeenCalledTimes(1)
  })

  it('HTTP 401/403 不触发跳登录（服务端错误不应被当成会话失效）', async () => {
    const handle = vi.spyOn(authExpiry, 'handle').mockImplementation(() => {})
    stubServer(successEnvelope({ ok: true }), 401)

    const result = await action('/platform/dict/List', {})

    expect(result.data).toEqual({ ok: true })
    expect(handle).not.toHaveBeenCalled()

    stubServer(successEnvelope({ ok: true }), 403)
    await action('/platform/dict/List', {})
    expect(handle).not.toHaveBeenCalled()
  })

  it('响应体不是 Result 结构时明确失败，而不是把 undefined 当数据', async () => {
    stubServer('<html>gateway error</html>', 502)

    const failure = await action('/platform/dict/List', {}).catch((error) => error)

    expect(failure).toBeInstanceOf(ApiError)
    expect(failure.message).toContain('Result')
  })

  it('请求层源码不出现 HTTP 401/403 判定（防回归到状态码判定）', async () => {
    const { readFileSync } = await import('node:fs')
    const { fileURLToPath } = await import('node:url')
    const { dirname, join } = await import('node:path')
    // 注意：jsdom 会替换全局 URL，`new URL('./x', import.meta.url)` 在此不可用
    const source = readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'request.js'), 'utf8')

    expect(source).not.toMatch(/status\s*===?\s*40[13]/)
    expect(source).not.toMatch(/response\.status/)
  })
})
