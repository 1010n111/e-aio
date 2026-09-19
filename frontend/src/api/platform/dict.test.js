import { beforeEach, describe, expect, it, vi } from 'vitest'
import axios from 'axios'

import { DATA_CONFLICT, FORBIDDEN_CODE, IDEMPOTENCY_KEY_HEADER, IDEMPOTENCY_KEY_MISSING } from '@/api/codes'
import { newIdempotencyScope } from '@/api/idempotency'
import { http } from '@/api/request'
import router from '@/router'
import {
  DICT_BUILTIN_READONLY,
  DICT_ITEM_DUPLICATED,
  DICT_PERMISSIONS,
  DICT_STATUSES,
  DICT_TYPE_IN_USE,
  DICT_TYPE_NOT_FOUND,
  EXT_JSON_MAX_LENGTH,
  ITEM_LABEL_MAX_LENGTH,
  ITEM_VALUE_MAX_LENGTH,
  TYPE_CODE_MAX_LENGTH,
  describeWriteFailure,
  dictItem,
  dictType,
  normalizeItemQuery,
  normalizeItemSaveCmd,
  normalizeTypeQuery,
  normalizeTypeSaveCmd,
} from '@/api/platform/dict'

/** 捕获请求并把成功返回体喂给 axios 全链路（不启 HTTP 服务），写法同 param.test.js。 */
function stubServer(data) {
  const captured = []
  http.defaults.adapter = async (config) => {
    captured.push(config)
    return {
      data: { code: 0, message: '成功', data, traceId: 't-dict' },
      status: 200,
      statusText: 'OK',
      headers: new axios.AxiosHeaders(),
      config,
    }
  }
  return captured
}

describe('字典查询条件整形（P1 册 5.3）', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('未填的条件不进请求体：空串/空白串/null 一律省略', () => {
    expect(normalizeTypeQuery({ typeCode: '  ', typeName: '', status: null })).toEqual({ pageNum: 1, pageSize: 20 })
    expect(normalizeItemQuery({ itemValue: ' ', status: undefined })).toEqual({ pageNum: 1, pageSize: 20 })
  })

  it('已填条件按契约整形：模糊字段去空白、非法状态不下发', () => {
    expect(normalizeTypeQuery({ typeCode: ' platform_ ', typeName: ' 级别 ', status: 'ENABLED' })).toEqual({
      typeCode: 'platform_',
      typeName: '级别',
      status: 'ENABLED',
      pageNum: 1,
      pageSize: 20,
    })
    expect(normalizeTypeQuery({ status: 'PAUSED' })).not.toHaveProperty('status')
  })

  it('项查询的 typeCode 是精确匹配（项列表总是"某类型下"的列表）', () => {
    expect(normalizeItemQuery({ typeCode: 'platform_param_level', itemValue: 'SY' })).toEqual({
      typeCode: 'platform_param_level',
      itemValue: 'SY',
      pageNum: 1,
      pageSize: 20,
    })
  })

  it('分页字段被限制在 1–200（P1 册 5.3）', () => {
    expect(normalizeTypeQuery({ pageNum: 0, pageSize: 9999 })).toMatchObject({ pageNum: 1, pageSize: 200 })
    expect(normalizeItemQuery({ pageNum: -5, pageSize: 0 })).toMatchObject({ pageNum: 1, pageSize: 20 })
  })
})

describe('DictTypeSaveCmd / DictItemSaveCmd 组装（P1 册 5.3）', () => {
  it('只下发契约字段：界面状态不泄漏进请求体', () => {
    const type = normalizeTypeSaveCmd({ typeCode: ' a_b ', typeName: ' 名称 ', status: 'ENABLED', itemCount: 3, id: 9 })
    expect(Object.keys(type).sort()).toEqual(['status', 'typeCode', 'typeName', 'version'])
    expect(type.typeCode).toBe('a_b')
    expect(type.typeName).toBe('名称')

    const item = normalizeItemSaveCmd({ typeCode: 'a_b', itemValue: ' V ', itemLabel: ' 标签 ', id: 9, version: 2 })
    expect(Object.keys(item).sort()).toEqual(
      ['extJson', 'isDefault', 'itemLabel', 'itemValue', 'sortNo', 'status', 'typeCode', 'version'].sort(),
    )
  })

  it('Up 带 version，Add 不带（缺 version 后端 up 直接 10003）', () => {
    expect(normalizeTypeSaveCmd({ typeCode: 't', typeName: 'n', version: 3 }).version).toBe(3)
    expect(normalizeTypeSaveCmd({ typeCode: 't', typeName: 'n' }).version).toBeNull()
    expect(normalizeItemSaveCmd({ itemValue: 'v', itemLabel: 'l', version: '4' }).version).toBe(4)
    expect(normalizeItemSaveCmd({ itemValue: 'v', itemLabel: 'l' }).version).toBeNull()
  })

  it('状态缺省按 ENABLED（与 DDL 默认值一致），非法值也回落 ENABLED 而不是下发给 CHECK 约束', () => {
    expect(normalizeTypeSaveCmd({}).status).toBe('ENABLED')
    expect(normalizeItemSaveCmd({ status: 'PAUSED' }).status).toBe('ENABLED')
    expect(normalizeItemSaveCmd({ status: 'DISABLED' }).status).toBe('DISABLED')
  })

  it('extJson 空值下发 null 而不是空串（空串不是合法 JSON，落 JSONB 列会直接失败）', () => {
    expect(normalizeItemSaveCmd({}).extJson).toBeNull()
    expect(normalizeItemSaveCmd({ extJson: '  ' }).extJson).toBeNull()
    expect(normalizeItemSaveCmd({ extJson: ' {"color":"red"} ' }).extJson).toBe('{"color":"red"}')
  })

  it('isDefault 是布尔，sortNo 是整数（后端列 NOT NULL DEFAULT）', () => {
    expect(normalizeItemSaveCmd({ isDefault: true, sortNo: '30' })).toMatchObject({ isDefault: true, sortNo: 30 })
    expect(normalizeItemSaveCmd({ isDefault: 'true' })).toMatchObject({ isDefault: false, sortNo: 0 })
  })

  it('可选备注留空即省略，不塞空串', () => {
    expect(normalizeTypeSaveCmd({ typeCode: 't', typeName: 'n', remark: '  ' })).not.toHaveProperty('remark')
    expect(normalizeTypeSaveCmd({ typeCode: 't', typeName: 'n', remark: ' 备注 ' }).remark).toBe('备注')
  })

  it('字段上限与契约一致（64/128/128/2000）', () => {
    expect([TYPE_CODE_MAX_LENGTH, ITEM_VALUE_MAX_LENGTH, ITEM_LABEL_MAX_LENGTH, EXT_JSON_MAX_LENGTH]).toEqual([
      64, 128, 128, 2000,
    ])
    expect(DICT_STATUSES).toEqual(['ENABLED', 'DISABLED'])
  })
})

describe('11 个端点的路径、载荷与幂等键（P1 册 5.2、5.6）', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('读动作走 POST 且不带幂等键；路径省略 /api（baseURL 已带）', async () => {
    const captured = stubServer({ total: 0, pageNum: 1, pageSize: 20, records: [] })

    await dictType.getPage({ typeCode: 'a' })
    await dictItem.getPage({ typeCode: 'a' })
    await dictItem.getItems('a')
    await dictType.get(7)

    expect(captured.map((config) => config.url)).toEqual([
      '/platform/dictType/GetPage',
      '/platform/dictItem/GetPage',
      '/platform/dictItem/GetItems',
      '/platform/dictType/Get',
    ])
    expect(captured.every((config) => config.method === 'post')).toBe(true)
    expect(captured.every((config) => config.baseURL === '/api')).toBe(true)
    expect(captured.every((config) => config.headers[IDEMPOTENCY_KEY_HEADER] === undefined)).toBe(true)
    expect(JSON.parse(captured[3].data)).toEqual({ id: 7 })
  })

  it('写动作全部带幂等键，路径与载荷逐条对应 5.2', async () => {
    const captured = stubServer({})

    await dictType.add({ typeCode: 't', typeName: 'n' }, { idempotencyKey: 'k1' })
    await dictType.up({ typeCode: 't', typeName: 'n2', version: 1 }, { idempotencyKey: 'k2' })
    await dictType.del(9, 2, { idempotencyKey: 'k3' })
    await dictType.refresh('t', { idempotencyKey: 'k4' })
    await dictItem.add({ typeCode: 't', itemValue: 'v', itemLabel: 'l' }, { idempotencyKey: 'k5' })
    await dictItem.up({ typeCode: 't', itemValue: 'v', itemLabel: 'l2', version: 1 }, { idempotencyKey: 'k6' })
    await dictItem.del(5, 1, { idempotencyKey: 'k7' })

    expect(captured.map((config) => config.url)).toEqual([
      '/platform/dictType/Add',
      '/platform/dictType/Up',
      '/platform/dictType/Del',
      '/platform/dictType/Refresh',
      '/platform/dictItem/Add',
      '/platform/dictItem/Up',
      '/platform/dictItem/Del',
    ])
    expect(captured.map((config) => config.headers[IDEMPOTENCY_KEY_HEADER])).toEqual([
      'k1', 'k2', 'k3', 'k4', 'k5', 'k6', 'k7',
    ])
    expect(JSON.parse(captured[2].data)).toEqual({ id: 9, version: 2 })
    expect(JSON.parse(captured[3].data)).toEqual({ typeCode: 't' })
    expect(JSON.parse(captured[6].data)).toEqual({ id: 5, version: 1 })
  })

  it('未显式给键时请求层自动补一个（等价于"调用即一次新动作"）', async () => {
    const captured = stubServer({})

    await dictItem.add({ typeCode: 't', itemValue: 'v', itemLabel: 'l' })

    expect(captured[0].headers[IDEMPOTENCY_KEY_HEADER]).toMatch(/^[0-9a-f-]{36}$/)
  })

  it('成功时解包 data：分页记录可直接喂给列表', async () => {
    stubServer({ total: 1, pageNum: 1, pageSize: 20, records: [{ typeCode: 't' }] })

    const result = await dictType.getPage({})

    expect(result.data.records).toEqual([{ typeCode: 't' }])
    expect(result.traceId).toBe('t-dict')
  })
})

describe('幂等键：同一次提交复用、内容变了换新（P1 册 5.6）', () => {
  it('载荷相同（用户点重试）复用同一个键', () => {
    const scope = newIdempotencyScope()
    const payload = { id: 1, version: 1 }

    expect(scope.next({ ...payload }).key).toBe(scope.next(payload).key)
  })

  it('载荷变了（用户改了内容再提交）换新键；成功后 reset 也换新键', () => {
    const scope = newIdempotencyScope()
    const first = scope.next({ id: 1, version: 1 })

    expect(scope.next({ id: 1, version: 2 }).key).not.toBe(first.key)

    scope.reset()
    expect(scope.next({ id: 1, version: 1 }).key).not.toBe(first.key)
  })
})

describe('Result.code → 用户提示（唯一分支依据，不做中文匹配）', () => {
  it('dict 段业务码按码分支到各自文案', () => {
    expect(describeWriteFailure({ code: DICT_TYPE_NOT_FOUND })).toContain('20003')
    expect(describeWriteFailure({ code: DICT_ITEM_DUPLICATED })).toContain('20007')
    expect(describeWriteFailure({ code: DICT_TYPE_IN_USE })).toContain('20008')
    expect(describeWriteFailure({ code: DICT_BUILTIN_READONLY })).toContain('20005')
  })

  it('在用类型 20008 的提示可执行：先删或停用项', () => {
    expect(describeWriteFailure({ code: DICT_TYPE_IN_USE })).toContain('停用')
  })

  it('内置只读 20005 的提示给出替代动作（停用），不是"删除失败"这种无信息量文案', () => {
    expect(describeWriteFailure({ code: DICT_BUILTIN_READONLY })).toContain('内置')
  })

  it('幂等键/乐观锁/无权限与未登记的码：前者按码给文案，后者回落后端 message + code + traceId', () => {
    expect(describeWriteFailure({ code: 10001 })).toContain('10001')
    expect(describeWriteFailure({ code: 10003 })).toContain('刷新')
    expect(describeWriteFailure({ code: 10403 })).toContain('10403')
    expect(describeWriteFailure({ code: 99999, message: '后端说', traceId: 't-9' })).toBe(
      '后端说（code=99999，traceId=t-9）',
    )
    expect(describeWriteFailure(undefined)).toContain('code=unknown')
  })

  it('每个登记码都命中登记文案、不落兜底（兜底会拼 traceId 与后端 message）', () => {
    const registered = [
      IDEMPOTENCY_KEY_MISSING,
      DATA_CONFLICT,
      FORBIDDEN_CODE,
      DICT_TYPE_NOT_FOUND,
      DICT_BUILTIN_READONLY,
      DICT_ITEM_DUPLICATED,
      DICT_TYPE_IN_USE,
    ]

    // 回归点：常量若 import 错名（undefined），多个键会塌成同一个 `[undefined]` 键，
    // 表现是"提示悄悄退化成兜底文案"，而 contains('10403') 这种断言照样绿。
    expect(new Set(registered).size).toBe(registered.length)
    for (const code of registered) {
      const hint = describeWriteFailure({ code, message: '后端 message', traceId: 't-x' })
      expect(hint, `码 ${code} 应命中登记文案`).not.toContain('traceId')
      expect(hint).not.toContain('后端 message')
    }
  })

  it('分支只看码：message 是中文还是英文都不影响判定', () => {
    expect(describeWriteFailure({ code: DICT_TYPE_IN_USE, message: 'whatever' })).toBe(
      describeWriteFailure({ code: DICT_TYPE_IN_USE, message: '字典类型下仍有字典项' }),
    )
  })
})

describe('权限点与路由（P1 册 7.3、3.10.2）', () => {
  it('权限点与后端 @PreAuthorize 逐字一致', () => {
    expect(DICT_PERMISSIONS).toEqual({
      list: 'platform:dict:list',
      get: 'platform:dict:get',
      add: 'platform:dict:add',
      up: 'platform:dict:up',
      del: 'platform:dict:del',
      refresh: 'platform:dict:refresh',
    })
  })

  it('字典页路由已登记且声明 platform:dict:list（按钮级隐藏留待 iam 的 v-hasPermi）', () => {
    const route = router.getRoutes().find((item) => item.path === '/platform/dict')

    expect(route).toBeDefined()
    expect(route.meta.permission).toBe('platform:dict:list')
    expect(route.meta.title).toBe('字典管理')
    expect(typeof route.components.default).toBe('function')
  })
})
