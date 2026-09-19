import { beforeEach, describe, expect, it, vi } from 'vitest'
import axios from 'axios'

import { IDEMPOTENCY_KEY_HEADER } from '@/api/codes'
import { http } from '@/api/request'
import { getAll, getPage, normalizeParamQuery } from '@/api/platform/param'

/** 捕获请求并把成功返回体喂给 axios 全链路（不启 HTTP 服务），写法同 request.test.js。 */
function stubServer(data) {
  const captured = []
  http.defaults.adapter = async (config) => {
    captured.push(config)
    return {
      data: { code: 0, message: '成功', data, traceId: 't-param' },
      status: 200,
      statusText: 'OK',
      headers: new axios.AxiosHeaders(),
      config,
    }
  }
  return captured
}

describe('参数中心请求模块（只读）', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.clear()
  })

  it('未填的条件不进请求体：空串/空白串/null 一律省略', () => {
    expect(
      normalizeParamQuery({ paramKey: '  ', paramLevel: null, ownerId: null, paramGroup: '' }),
    ).toEqual({ pageNum: 1, pageSize: 20 })
  })

  it('已填条件按契约整形：paramKey 去空白、ownerId 转数字、非法 paramLevel 不下发', () => {
    expect(
      normalizeParamQuery({
        paramKey: ' sys.timeout ',
        paramLevel: 'ORG',
        ownerId: '7',
        paramGroup: 'sms',
        pageNum: 3,
        pageSize: 50,
      }),
    ).toEqual({
      paramKey: 'sys.timeout',
      paramLevel: 'ORG',
      ownerId: 7,
      paramGroup: 'sms',
      pageNum: 3,
      pageSize: 50,
    })

    expect(normalizeParamQuery({ paramLevel: 'TENANT' })).not.toHaveProperty('paramLevel')
  })

  it('分页字段被限制在 1–200（P1 册 5.3）', () => {
    expect(normalizeParamQuery({ pageNum: 0, pageSize: 9999 })).toMatchObject({ pageNum: 1, pageSize: 200 })
    expect(normalizeParamQuery({ pageNum: -5, pageSize: 0 })).toMatchObject({ pageNum: 1, pageSize: 20 })
  })

  it('ownerId = 0 是 SYSTEM 级的合法值，不能被当成未填丢掉', () => {
    expect(normalizeParamQuery({ ownerId: 0 })).toMatchObject({ ownerId: 0 })
  })

  it('GetPage：POST 到省略 /api 的动作路径，带分页，无幂等键', async () => {
    const captured = stubServer({ total: 1, pageNum: 1, pageSize: 20, records: [] })

    await getPage({ paramKey: 'a.b' })

    const config = captured[0]
    expect(config.method).toBe('post')
    // baseURL === '/api' 且动作路径不含 /api：自己再拼一次就会变成 /api/api/...
    expect(config.baseURL).toBe('/api')
    expect(config.url).toBe('/platform/param/GetPage')
    expect(config.headers[IDEMPOTENCY_KEY_HEADER]).toBeUndefined()
    expect(JSON.parse(config.data)).toEqual({ paramKey: 'a.b', pageNum: 1, pageSize: 20 })
  })

  it('GetAll：只传 paramGroup，走同一基础地址', async () => {
    const captured = stubServer([])

    await getAll({ paramGroup: ' notify ' })

    expect(captured[0].url).toBe('/platform/param/GetAll')
    expect(JSON.parse(captured[0].data)).toEqual({ paramGroup: 'notify' })
  })

  it('GetAll 未填分组时不塞空串（省略参数 ≠ 传空条件）', async () => {
    const captured = stubServer([])

    await getAll()

    expect(JSON.parse(captured[0].data)).toEqual({})
  })

  it('成功时解包 data：分页记录可直接喂给列表', async () => {
    stubServer({ total: 2, pageNum: 1, pageSize: 20, records: [{ paramKey: 'x' }] })

    const result = await getPage({})

    expect(result.data.records).toEqual([{ paramKey: 'x' }])
    expect(result.traceId).toBe('t-param')
  })
})
