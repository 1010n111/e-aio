import { beforeEach, describe, expect, it, vi } from 'vitest'
import axios from 'axios'

import { IDEMPOTENCY_KEY_HEADER } from '@/api/codes'
import { newIdempotencyScope } from '@/api/idempotency'
import { http } from '@/api/request'
import {
  PARAM_BUILTIN_READONLY,
  PARAM_DUPLICATED,
  PARAM_NOT_FOUND,
  PARAM_SCOPE_INVALID,
  PARAM_TYPE_MISMATCH,
} from '@/api/platform/paramCodes'
import {
  MASKED_PARAM_VALUE,
  add,
  del,
  describeWriteFailure,
  getAll,
  getPage,
  normalizeParamQuery,
  normalizeParamSaveCmd,
  refresh,
  toParamRow,
  up,
} from '@/api/platform/param'

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

describe('ParamSaveCmd 组装（P1 册 5.3）', () => {
  it('SYSTEM 级把 ownerId 直接置 0，不给用户填的机会（后端要求 SYSTEM 恒 0，否则 20006）', () => {
    expect(
      normalizeParamSaveCmd({ paramKey: ' a.b ', paramLevel: 'SYSTEM', ownerId: 7, valueType: 'INT' }),
    ).toMatchObject({ paramLevel: 'SYSTEM', ownerId: 0 })
  })

  it('ORG/USER 级保留用户填的 ownerId', () => {
    expect(normalizeParamSaveCmd({ paramLevel: 'ORG', ownerId: '12' })).toMatchObject({ ownerId: 12 })
    expect(normalizeParamSaveCmd({ paramLevel: 'USER', ownerId: 42 })).toMatchObject({ ownerId: 42 })
  })

  it('只下发契约字段：界面状态不泄漏进请求体', () => {
    const body = normalizeParamSaveCmd({
      paramKey: 'k',
      paramLevel: 'SYSTEM',
      valueType: 'STRING',
      id: 123,
      builtin: true,
      encrypted: true,
      hotReload: false,
    })

    expect(Object.keys(body).sort()).toEqual(['ownerId', 'paramKey', 'paramLevel', 'paramValue', 'valueType', 'version'])
  })

  it('Up 带 version，Add 不带（缺 version 后端 up 直接 10003）', () => {
    expect(normalizeParamSaveCmd({ paramKey: 'k', paramLevel: 'SYSTEM', valueType: 'INT', version: 3 })).toMatchObject({
      version: 3,
    })
    expect(normalizeParamSaveCmd({ paramKey: 'k', paramLevel: 'SYSTEM', valueType: 'INT' })).toMatchObject({
      version: null,
    })
  })

  it('paramValue 原样保留（去掉首尾空白会悄悄改掉用户写的值），null/undefined 归一为空串', () => {
    expect(normalizeParamSaveCmd({ paramValue: '  spaced  ' }).paramValue).toBe('  spaced  ')
    expect(normalizeParamSaveCmd({ paramValue: null }).paramValue).toBe('')
    expect(normalizeParamSaveCmd({}).paramValue).toBe('')
  })

  it('可选项留空即省略，不塞空串', () => {
    const body = normalizeParamSaveCmd({ paramGroup: '  ' })

    expect(body).not.toHaveProperty('paramGroup')
    expect(normalizeParamSaveCmd({ paramGroup: ' sms ' })).toMatchObject({ paramGroup: 'sms' })
  })

  it('不下发 remark：ParamDTO 没有该字段，读不回来的值不做"只能写不能读"的入口（P1-3 册 439 行第 17 条）', () => {
    const body = normalizeParamSaveCmd({ paramKey: 'k', paramLevel: 'SYSTEM', valueType: 'STRING', remark: ' 备注 ' })

    expect(body).not.toHaveProperty('remark')
  })
})

describe('列表行 → 编辑器行（定位 Up/Del 的目标行）', () => {
  const row = {
    paramKey: 'platform.file.max-size',
    effectiveValue: '10485760',
    source: 'DB',
    sourceLevel: 'ORG',
    hotReload: true,
    candidates: [
      {
        id: 9,
        paramKey: 'platform.file.max-size',
        paramLevel: 'ORG',
        ownerId: 3,
        paramValue: '10485760',
        valueType: 'INT',
        paramGroup: 'file',
        encrypted: false,
        builtin: false,
        version: 4,
      },
    ],
  }

  it('按 (paramLevel, ownerId) 找到行：Up 就是按这三元组定位的', () => {
    expect(toParamRow(row)).toMatchObject({
      paramLevel: 'ORG',
      ownerId: 3,
      version: 4,
      id: 9,
      valueType: 'INT',
      paramValue: '10485760',
    })
  })

  it('候选匹配只走 sourceLevel，并取同级别里 ownerId 最小者（退回按 row.ownerId 匹配就挂）', () => {
    // 回归点：`ParamWithSourceDTO` 没有 ownerId 字段（P1 册 5.3）。早先按 row.ownerId 匹配：
    // 一个候选都对不上（编辑悄悄退化成新增），或错认 SYSTEM 行（ownerId=0）为可写行。
    const rowWithoutOwnerId = {
      paramKey: 'platform.file.max-size',
      source: 'DB',
      sourceLevel: 'ORG',
      candidates: [
        { id: 1, paramLevel: 'SYSTEM', ownerId: 0, version: 1 },
        { id: 3, paramLevel: 'ORG', ownerId: 11, version: 2 },
        { id: 4, paramLevel: 'ORG', ownerId: 5, version: 9 },
      ],
    }

    expect(toParamRow(rowWithoutOwnerId)).toMatchObject({
      paramLevel: 'ORG',
      ownerId: 5,
      id: 4,
      version: 9,
    })
  })

  it('同级别多条候选时取 ownerId 最小的一条（页面靠展开候选看全部）', () => {
    const multiOrg = {
      paramKey: 'k',
      source: 'DB',
      sourceLevel: 'ORG',
      candidates: [
        { id: 7, paramLevel: 'ORG', ownerId: 8, version: 1 },
        { id: 6, paramLevel: 'ORG', ownerId: 2, version: 5 },
      ],
    }

    expect(toParamRow(multiOrg)).toMatchObject({ id: 6, ownerId: 2, version: 5 })
  })

  it('DEFAULT/YAML 来源没有数据库行：id/version 为 null（页面据此走 Add）', () => {
    expect(toParamRow({ paramKey: 'x', source: 'YAML', sourceLevel: null, candidates: [] })).toMatchObject({
      paramLevel: 'SYSTEM',
      ownerId: 0,
      id: null,
      version: null,
    })
  })

  it('加密行不回填掩码：值留空 = 保持不变，绝不把 ****** 当真实值提交', () => {
    const secret = {
      paramKey: 'smtp.password',
      source: 'DB',
      sourceLevel: 'SYSTEM',
      candidates: [
        {
          id: 5,
          paramKey: 'smtp.password',
          paramLevel: 'SYSTEM',
          ownerId: 0,
          paramValue: MASKED_PARAM_VALUE,
          valueType: 'SECRET',
          encrypted: true,
          version: 2,
        },
      ],
    }

    const target = toParamRow(secret)

    expect(target).toMatchObject({ encrypted: true, paramValue: '', valueType: 'SECRET', version: 2 })
    expect(normalizeParamSaveCmd(target).paramValue).toBe('')
  })

  it('已加密但后端没标 encrypted 时，靠掩码值本身也能判定', () => {
    const target = toParamRow({
      paramKey: 's',
      source: 'DB',
      sourceLevel: 'SYSTEM',
      candidates: [{ id: 5, paramLevel: 'SYSTEM', ownerId: 0, paramValue: MASKED_PARAM_VALUE, version: 2 }],
    })

    expect(target).toMatchObject({ encrypted: true, paramValue: '' })
  })

  it('内置标记透传给页面（删除按钮据此禁用）', () => {
    const target = toParamRow({
      paramKey: 'b',
      source: 'DB',
      sourceLevel: 'SYSTEM',
      candidates: [{ id: 1, paramLevel: 'SYSTEM', ownerId: 0, builtin: true, version: 1 }],
    })

    expect(target.builtin).toBe(true)
  })
})

describe('写动作请求：路径、载荷与幂等键', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.clear()
  })

  it('Add 走 ParamSaveCmd，带上幂等键（查询动作才不带）', async () => {
    const captured = stubServer({ id: 1, version: 0 })

    await add(
      { paramKey: 'a.b', paramLevel: 'SYSTEM', ownerId: 9, valueType: 'INT', paramValue: '1' },
      { idempotencyKey: 'k-add' },
    )

    expect(captured[0].url).toBe('/platform/param/Add')
    expect(captured[0].headers[IDEMPOTENCY_KEY_HEADER]).toBe('k-add')
    expect(JSON.parse(captured[0].data)).toMatchObject({
      paramKey: 'a.b',
      paramLevel: 'SYSTEM',
      ownerId: 0,
      valueType: 'INT',
      paramValue: '1',
      version: null,
    })
  })

  it('Add 下发前把参数键去空白：键是行身份，键上带空白就与后端 DTO 的键对不上', async () => {
    const captured = stubServer({ id: 1, version: 0 })

    await add({
      paramKey: '  platform.file.max-size  ',
      paramLevel: 'SYSTEM',
      valueType: 'INT',
      paramValue: '1',
    })

    expect(captured[0].url).toBe('/platform/param/Add')
    expect(JSON.parse(captured[0].data).paramKey).toBe('platform.file.max-size')
    expect(captured[0].headers[IDEMPOTENCY_KEY_HEADER]).toBeTruthy()
  })

  it('Up 带 version（乐观锁），路径为 Up', async () => {
    const captured = stubServer({})

    await up(
      { paramKey: 'a.b', paramLevel: 'USER', ownerId: 7, valueType: 'STRING', version: 2 },
      { idempotencyKey: 'k-up' },
    )

    expect(captured[0].url).toBe('/platform/param/Up')
    expect(JSON.parse(captured[0].data)).toMatchObject({ ownerId: 7, version: 2 })
  })

  it('未显式给键时请求层自动补一个（等价于"调用即一次新动作"）', async () => {
    const captured = stubServer({})

    await add({ paramKey: 'a', paramLevel: 'SYSTEM', valueType: 'STRING' })

    expect(captured[0].headers[IDEMPOTENCY_KEY_HEADER]).toMatch(/^[0-9a-f-]{36}$/)
  })

  it('Del 只发 {id, version}', async () => {
    const captured = stubServer(null)

    await del(88, 3, { idempotencyKey: 'k-del' })

    expect(captured[0].url).toBe('/platform/param/Del')
    expect(JSON.parse(captured[0].data)).toEqual({ id: 88, version: 3 })
  })

  it('Refresh 带 paramKey = 只清该键；省略 = 全量失效', async () => {
    const captured = stubServer(null)

    await refresh('a.b', { idempotencyKey: 'k-r1' })
    await refresh(undefined, { idempotencyKey: 'k-r2' })

    expect(captured[0].url).toBe('/platform/param/Refresh')
    expect(JSON.parse(captured[0].data)).toEqual({ paramKey: 'a.b' })
    expect(JSON.parse(captured[1].data)).toEqual({})
  })
})

describe('幂等键：同一次提交复用、内容变了换新', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.clear()
  })

  it('载荷相同（用户点重试）复用同一个键', () => {
    const scope = newIdempotencyScope()
    const payload = { paramKey: 'a', paramValue: '1' }

    const first = scope.next(payload)
    const retry = scope.next({ ...payload })

    expect(retry.key).toBe(first.key)
  })

  it('载荷变了（用户改了内容再提交）换新键', () => {
    const scope = newIdempotencyScope()

    const first = scope.next({ paramKey: 'a', paramValue: '1' })
    const changed = scope.next({ paramKey: 'a', paramValue: '2' })

    expect(changed.key).not.toBe(first.key)
  })

  it('成功后 reset：下一次动作必须换新键，否则会被后端当重放吃掉', () => {
    const scope = newIdempotencyScope()
    const payload = { id: 1, version: 1 }

    const first = scope.next(payload)
    scope.reset()

    expect(scope.next(payload).key).not.toBe(first.key)
  })

  it('键是 crypto.randomUUID 的产物，且不落 localStorage', () => {
    const scope = newIdempotencyScope()

    expect(scope.next({}).key).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/)
    expect(Object.keys(localStorage)).toHaveLength(0)
  })

  it('不同 scope 各自独立（两次动作不会撞到同一个键）', () => {
    const first = newIdempotencyScope().next({ id: 1 })
    const second = newIdempotencyScope().next({ id: 1 })

    expect(first.key).not.toBe(second.key)
  })

  it('同一动作重试复用同一个键、载荷变了换新键：键以请求头落到真实请求上', async () => {
    const captured = stubServer({ id: 1, version: 0 })
    const scope = newIdempotencyScope()
    const payload = { paramKey: 'a', paramValue: '1' }

    const attempt = scope.next(payload)
    const retry = scope.next({ ...payload })
    const changed = scope.next({ paramKey: 'a', paramValue: '2' })

    await add(payload, { idempotencyKey: retry.key })
    await add({ paramKey: 'a', paramValue: '2' }, { idempotencyKey: changed.key })

    expect(retry.key).toBe(attempt.key)
    expect(captured[0].headers[IDEMPOTENCY_KEY_HEADER]).toBe(attempt.key)
    expect(captured[1].headers[IDEMPOTENCY_KEY_HEADER]).not.toBe(attempt.key)
  })
})

describe('Result.code → 用户提示（唯一分支依据，不做中文匹配）', () => {
  it('缺幂等键 10001：这是前端问题，提示重试', () => {
    expect(describeWriteFailure({ code: 10001, message: '缺幂等键' })).toContain('10001')
    expect(describeWriteFailure({ code: 10001 })).toContain('重新提交')
  })

  it('乐观锁冲突 10003：提示刷新后用最新版本重试', () => {
    expect(describeWriteFailure({ code: 10003 })).toContain('10003')
    expect(describeWriteFailure({ code: 10003 })).toContain('刷新')
  })

  it('param 段业务码按码分支到各自文案', () => {
    expect(describeWriteFailure({ code: PARAM_NOT_FOUND })).toContain('20001')
    expect(describeWriteFailure({ code: PARAM_TYPE_MISMATCH })).toContain('20002')
    expect(describeWriteFailure({ code: PARAM_DUPLICATED })).toContain('20004')
    expect(describeWriteFailure({ code: PARAM_BUILTIN_READONLY })).toContain('20005')
    expect(describeWriteFailure({ code: PARAM_SCOPE_INVALID })).toContain('20006')
  })

  it('内置参数不可删的提示说明原因（不是"删除失败"这种无信息量文案）', () => {
    expect(describeWriteFailure({ code: PARAM_BUILTIN_READONLY })).toContain('内置')
  })

  it('无权限 10403 与未登记的码：前者给权限提示，后者回落后端 message + code + traceId', () => {
    expect(describeWriteFailure({ code: 10403 })).toContain('10403')
    expect(describeWriteFailure({ code: 99999, message: '后端说', traceId: 't-9' })).toBe(
      '后端说（code=99999，traceId=t-9）',
    )
    expect(describeWriteFailure(undefined)).toContain('code=unknown')
  })

  it('分支只看码：message 是中文还是英文都不影响判定', () => {
    expect(describeWriteFailure({ code: PARAM_BUILTIN_READONLY, message: 'whatever' })).toBe(
      describeWriteFailure({ code: PARAM_BUILTIN_READONLY, message: '系统内置参数不可删除' }),
    )
  })
})
