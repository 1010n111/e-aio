import { beforeEach, describe, expect, it, vi } from 'vitest'
import axios from 'axios'

import { DATA_CONFLICT, FORBIDDEN_CODE, IDEMPOTENCY_KEY_HEADER, IDEMPOTENCY_KEY_MISSING } from '@/api/codes'
import { authExpiry, download, http, parseContentDisposition, upload } from '@/api/request'
import {
  FILE_ACCESS_DENIED,
  FILE_ALLOWED_EXTENSIONS,
  FILE_EMPTY,
  FILE_MAX_SIZE_BYTES,
  FILE_NOT_FOUND,
  FILE_PERMISSIONS,
  FILE_SIGNATURE_INVALID,
  FILE_SOURCES,
  FILE_STORAGE_ERROR,
  FILE_STORAGE_TYPES,
  FILE_TOO_LARGE,
  FILE_TYPE_NOT_ALLOWED,
  describeWriteFailure,
  formatSize,
  normalizeBindCmd,
  normalizeFileQuery,
} from '@/api/platform/file'

/**
 * 捕获请求并把指定返回体喂给 axios 全链路（不启 HTTP 服务），写法同 `dict.test.js`。
 * `headers` 用来模拟下载的响应头（`Content-Type` / `Content-Disposition`）。
 */
function stubServer(data, headers = {}) {
  const captured = []
  http.defaults.adapter = async (config) => {
    captured.push(config)
    return {
      data,
      status: 200,
      statusText: 'OK',
      headers: new axios.AxiosHeaders(headers),
      config,
    }
  }
  return captured
}

/**
 * 错误体替身：**jsdom 的 `Blob` 没有 `text()`**（Node 的全局 `Blob` 才有），所以错误体用只实现 `text()`
 * 的对象走同一条分支——浏览器里那一步就是原生 `Blob.prototype.text()`，这里要验的是"按 Content-Type
 * 分辨 + 解出 code"，不是 Blob 的实现。
 */
function textBlob(text) {
  return { text: async () => text }
}

describe('文件列表筛选整形（P1 册 5.3 的 FileQuery）', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('未填的条件不进请求体：空串/空白串/null 一律省略', () => {
    expect(normalizeFileQuery({ originalName: '  ', bizType: '', bizId: null, source: null })).toEqual({
      pageNum: 1,
      pageSize: 20,
    })
  })

  it('已填条件按契约整形：文件名去空白、来源非法不下发', () => {
    expect(normalizeFileQuery({ originalName: ' 合同.pdf ', source: 'UPLOAD' })).toEqual({
      originalName: '合同.pdf',
      source: 'UPLOAD',
      pageNum: 1,
      pageSize: 20,
    })
    expect(normalizeFileQuery({ source: 'UNKNOWN' })).not.toHaveProperty('source')
  })

  it('bizType 与 bizId 必须成对：只给一半按"没给"处理', () => {
    expect(normalizeFileQuery({ bizType: 'crm.customer' })).not.toHaveProperty('bizType')
    expect(normalizeFileQuery({ bizId: 12 })).not.toHaveProperty('bizId')
    expect(normalizeFileQuery({ bizType: ' crm.customer ', bizId: '12' })).toMatchObject({
      bizType: 'crm.customer',
      bizId: 12,
    })
  })

  it('保留上传者与组织筛选字段', () => {
    expect(normalizeFileQuery({ uploaderId: '7', uploaderOrgId: 9 })).toMatchObject({
      uploaderId: 7,
      uploaderOrgId: 9,
    })
  })

  it('时间区间原样下发（后端是 Instant，格式化留给调用方）', () => {
    expect(
      normalizeFileQuery({ createdFrom: '2024-05-01T00:00:00.000Z', createdTo: '2024-05-31T23:59:59.999Z' }),
    ).toMatchObject({
      createdFrom: '2024-05-01T00:00:00.000Z',
      createdTo: '2024-05-31T23:59:59.999Z',
    })
  })

  it('分页字段被限制在 1–200（P1 册 5.3）', () => {
    expect(normalizeFileQuery({ pageNum: 0, pageSize: 9999 })).toMatchObject({ pageNum: 1, pageSize: 200 })
    expect(normalizeFileQuery({ pageNum: -5, pageSize: 0 })).toMatchObject({ pageNum: 1, pageSize: 20 })
  })
})

describe('FileBindCmd 组装（P1 册 5.2 的 Bind）', () => {
  it('去重、丢非法 ID、trim bizType', () => {
    expect(normalizeBindCmd({ bizType: ' crm.customer ', bizId: '12', fileIds: [3, 3, '5', 0, -1, null, 'x'] })).toEqual({
      bizType: 'crm.customer',
      bizId: 12,
      fileIds: [3, 5],
    })
  })

  it('非法输入直接抛错：页面不该发出一个必然被拒的请求', () => {
    expect(() => normalizeBindCmd({ bizType: '  ', bizId: 1, fileIds: [1] })).toThrow(/bizType/)
    expect(() => normalizeBindCmd({ bizType: 'a', bizId: 0, fileIds: [1] })).toThrow(/bizId/)
    expect(() => normalizeBindCmd({ bizType: 'a', bizId: 1, fileIds: [] })).toThrow(/fileIds/)
    // 全是非法 ID 等价于空列表（别靠 undefined 占位混过去）
    expect(() => normalizeBindCmd({ bizType: 'a', bizId: 1, fileIds: [0, 'x'] })).toThrow(/fileIds/)
  })
})

describe('formatSize（列表里比原始字节可读）', () => {
  it('B 段取整，KB/MB/GB 保留一位小数', () => {
    expect(formatSize(0)).toBe('0 B')
    expect(formatSize(1023)).toBe('1023 B')
    expect(formatSize(1024)).toBe('1.0 KB')
    expect(formatSize(1536)).toBe('1.5 KB')
    expect(formatSize(1048576)).toBe('1.0 MB')
    expect(formatSize(1073741824)).toBe('1.0 GB')
    // 再大也只到 GB（单位阶梯到 GB 为止），不凭空造 TB
    expect(formatSize(1024 ** 4)).toBe('1024.0 GB')
  })

  it('非法值回落 `-`，不伪装成 0 B', () => {
    expect(formatSize(null)).toBe('-')
    expect(formatSize(undefined)).toBe('-')
    expect(formatSize(-1)).toBe('-')
    expect(formatSize('abc')).toBe('-')
  })

  it('预检常量与 7.2 的参数默认值一致（仅作提示，判据在后端）', () => {
    expect(FILE_MAX_SIZE_BYTES).toBe(52_428_800)
    expect(FILE_ALLOWED_EXTENSIONS).toContain('pdf')
    expect(FILE_ALLOWED_EXTENSIONS).not.toContain('exe')
  })
})

describe('Content-Disposition 解析（下载文件名的唯一来源）', () => {
  it('RFC 5987 的 filename* 优先且按 UTF-8 解码', () => {
    expect(parseContentDisposition("attachment; filename*=UTF-8''%E4%B8%AD%E6%96%87.pdf")).toBe('中文.pdf')
  })

  it('回落老式 filename="..."（带空格）', () => {
    expect(parseContentDisposition('attachment; filename="a b.pdf"')).toBe('a b.pdf')
    expect(parseContentDisposition('attachment; filename=a.pdf')).toBe('a.pdf')
  })

  it('没有这个头（或没有文件名）时返回 null，由调用方决定退化名字', () => {
    expect(parseContentDisposition(undefined)).toBeNull()
    expect(parseContentDisposition('')).toBeNull()
    expect(parseContentDisposition('attachment')).toBeNull()
    expect(parseContentDisposition('attachment; filename=""')).toBeNull()
  })

  it('转义非法（%ZZ）时退回原文，不让整次下载失败', () => {
    expect(parseContentDisposition("attachment; filename*=UTF-8''bad%ZZ.pdf")).toBe('bad%ZZ.pdf')
  })
})

describe('Result.code → 用户提示（唯一分支依据，不做中文匹配）', () => {
  it('每个登记码都命中登记文案、不落兜底（兜底会拼 traceId 与后端 message）', () => {
    const registered = [
      IDEMPOTENCY_KEY_MISSING,
      DATA_CONFLICT,
      FORBIDDEN_CODE,
      FILE_EMPTY,
      FILE_TOO_LARGE,
      FILE_TYPE_NOT_ALLOWED,
      FILE_NOT_FOUND,
      FILE_STORAGE_ERROR,
      FILE_SIGNATURE_INVALID,
      FILE_ACCESS_DENIED,
    ]

    // 回归点：常量若 import 错名（undefined），多个键会塌成同一个 `[undefined]` 键，
    // 表现是"提示悄悄退化成兜底文案"，而 contains('20014') 这种断言照样绿。
    expect(new Set(registered).size).toBe(registered.length)
    for (const code of registered) {
      const hint = describeWriteFailure({ code, message: '后端 message', traceId: 't-x' })
      expect(hint, `码 ${code} 应命中登记文案`).not.toContain('traceId')
      expect(hint).not.toContain('后端 message')
    }
  })

  it('提示是可执行的：超限指参数、过期给重来、无权说清谁能下', () => {
    expect(describeWriteFailure({ code: FILE_TOO_LARGE })).toContain('platform.file.max-size')
    expect(describeWriteFailure({ code: FILE_TYPE_NOT_ALLOWED })).toContain('platform.file.allowed-ext')
    expect(describeWriteFailure({ code: FILE_SIGNATURE_INVALID })).toContain('20016')
    expect(describeWriteFailure({ code: FILE_ACCESS_DENIED })).toContain('20017')
    expect(describeWriteFailure({ code: DATA_CONFLICT })).toContain('刷新')
  })

  it('未登记的码回落后端 message + code + traceId', () => {
    expect(describeWriteFailure({ code: 99999, message: '后端说', traceId: 't-9' })).toBe(
      '后端说（code=99999，traceId=t-9）',
    )
    expect(describeWriteFailure(undefined)).toContain('code=unknown')
  })

  it('权限点、来源、存储类型与后端契约逐字一致（P1 册 7.3、4.3.5）', () => {
    expect(FILE_PERMISSIONS).toEqual({
      list: 'platform:file:list',
      get: 'platform:file:get',
      upload: 'platform:file:upload',
      download: 'platform:file:download',
      del: 'platform:file:del',
      bind: 'platform:file:bind',
    })
    expect(FILE_SOURCES).toEqual(['UPLOAD', 'CHUNK', 'EXPORT', 'IMPORT_ERROR', 'TEMPLATE'])
    expect(FILE_STORAGE_TYPES).toEqual(['LOCAL', 'S3'])
  })
})

/**
 * 请求层的两处例外（P1 册 3.10.4、3.10.5 的测试点）。
 *
 * 不启 DOM/网络，只换 adapter：要验的是"上传没被序列化成 JSON"与"200 + JSON 的失败体不被当成文件"
 * 这两条——它们错了都不会抛异常，只会安静地传上去一个空 body / 存下一个错误 JSON 文件。
 */
describe('上传与下载的请求层契约（P1 册 3.10.4）', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('上传走 FormData：不设置 Content-Type（让浏览器带 boundary），也不带幂等键', async () => {
    const captured = stubServer({ code: 0, message: '成功', data: { id: 7 }, traceId: 't-file' })

    const result = await upload('/platform/file/Upload', new window.FormData())

    const config = captured[0]
    expect(config.url).toBe('/platform/file/Upload')
    expect(config.method).toBe('post')
    // 被 JSON 序列化过的 body 是字符串；真 FormData 会原样传下去（默认头是 application/json，必须显式删掉）
    expect(config.data).not.toBeTypeOf('string')
    expect(config.headers['Content-Type']).toBeFalsy()
    expect(config.headers[IDEMPOTENCY_KEY_HEADER]).toBeUndefined()
    expect(result.data).toEqual({ id: 7 })
  })

  it('上传只在显式给非空键时才带幂等键（服务端自幂等）', async () => {
    const captured = stubServer({ code: 0, message: '成功', data: null, traceId: 't-file' })

    await upload('/platform/file/Upload', new window.FormData(), { idempotencyKey: '' })
    await upload('/platform/file/Upload', new window.FormData(), { idempotencyKey: 'k-upload' })

    expect(captured[0].headers[IDEMPOTENCY_KEY_HEADER]).toBeUndefined()
    expect(captured[1].headers[IDEMPOTENCY_KEY_HEADER]).toBe('k-upload')
  })

  it('下载成功：返回 blob 与响应头里的文件名，不当成 Result 信封解包', async () => {
    const blob = new window.Blob(['hello'], { type: 'application/pdf' })
    stubServer(blob, {
      'content-type': 'application/pdf',
      'content-disposition': "attachment; filename*=UTF-8''%E4%B8%AD%E6%96%87.pdf",
    })

    const result = await download('/platform/file/Download', { fileId: 7 })

    expect(result.data).toBe(blob)
    expect(result.filename).toBe('中文.pdf')
    expect(result.contentType).toBe('application/pdf')
  })

  it('下载失败（HTTP 200 + JSON 错误体）：按 Content-Type 分辨并抛 ApiError', async () => {
    const envelope = { code: FILE_NOT_FOUND, message: '文件不存在', data: null, traceId: 't-404' }
    stubServer(textBlob(JSON.stringify(envelope)), { 'content-type': 'application/json;charset=UTF-8' })

    const failure = await download('/platform/file/Download', { fileId: 7 }).catch((error) => error)

    expect(failure.code).toBe(FILE_NOT_FOUND)
    expect(failure.message).toBe('文件不存在')
    expect(failure.traceId).toBe('t-404')
  })

  it('下载失败且登录失效：仍走一次跳登录（与 JSON 拦截器同款）', async () => {
    const handle = vi.spyOn(authExpiry, 'handle').mockImplementation(() => {})
    stubServer(textBlob(JSON.stringify({ code: 10401, message: '未认证', traceId: 't-1' })), {
      'content-type': 'application/json',
    })

    const failure = await download('/platform/file/Download', { fileId: 7 }).catch((error) => error)

    expect(failure.isAuthExpired()).toBe(true)
    expect(handle).toHaveBeenCalledTimes(1)
  })

  it('下载失败但响应体不是 JSON（网关错误页）：明确失败，不产出假文件', async () => {
    stubServer(textBlob('<html>gateway error</html>'), { 'content-type': 'application/json' })

    const failure = await download('/platform/file/Download', { fileId: 7 }).catch((error) => error)

    expect(failure.code).toBe(-1)
    expect(failure.message).toContain('Result')
  })
})
