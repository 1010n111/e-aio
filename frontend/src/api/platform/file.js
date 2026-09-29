import { DATA_CONFLICT, FORBIDDEN_CODE, IDEMPOTENCY_KEY_MISSING } from '@/api/codes'
import { action, download as requestDownload, query, upload as requestUpload } from '@/api/request'

/**
 * 文件中心 API（P1 册 5.2 的 `/platform/file` 端点；动作地址省略 context-path `/api`，请求层 baseURL 已带）。
 *
 * 三条口径（与 `dict.js` / `job.js` 同款）：
 * - **只按 `Result.code` 分支**，不做中文 message 匹配（api-conventions）；
 * - 写动作每动作一个幂等键（5.6），但 `Upload` 是例外：服务端自幂等（5.2"幂等"列 = 否，`upload.js` 默认不带键）；
 * - 物理存储路径**在任何地方都不出现**（5.3 的 `FileDTO` 刻意不含 `storagePath`）：页面拿到的只有元数据
 *   与带时效的下载链接，别在前端拼路径。
 */
const FILE_BASE = '/platform/file'

// ---- 判定用的码值（逐字对齐后端 `PlatformErrorCode` 20010–20017；前端只按码分支）----
//
// 通用段（10001/10003/10403）从 `@/api/codes` 取，文件段在本文件声明（P1 册 7.1 的分段口径）。

/** 上传内容为空（0 字节）。 */
export const FILE_EMPTY = 20010

/** 超过单文件上限（参数 `platform.file.max-size`，默认 50MB）。 */
export const FILE_TOO_LARGE = 20011
export const FILE_CHUNK_INVALID = 20013

/** 扩展名不在白名单（参数 `platform.file.allowed-ext`）；`Content-Type` 只作记录、不作判据。 */
export const FILE_TYPE_NOT_ALLOWED = 20012

/** 文件不存在（`fileId` 无效或已逻辑删除）。 */
export const FILE_NOT_FOUND = 20014

/** 存储读写失败（磁盘/IO/S3 异常；**不自动回落另一种存储**）。 */
export const FILE_STORAGE_ERROR = 20015

/** 下载链接签名无效或已过期（本地盘链接是 HMAC 时效 token）。 */
export const FILE_SIGNATURE_INVALID = 20016

/** 无权访问该文件（上传者本人 / 同组织 / 管理员权限点，5.2 的 Download 判定）。 */
export const FILE_ACCESS_DENIED = 20017
export const FILE_SESSION_NOT_FOUND = 20018

/** 来源取值（后端 `FileSource` + DDL `ck_file_source`，P1 册 4.3.5）。 */
export const FILE_SOURCES = Object.freeze(['UPLOAD', 'CHUNK', 'EXPORT', 'IMPORT_ERROR', 'TEMPLATE'])

/** 存储类型（后端 `FileStorage.type()` + DDL `ck_file_storage_type`）：`S3` 本票未实现，值先留位。 */
export const FILE_STORAGE_TYPES = Object.freeze(['LOCAL', 'S3'])

/** 权限点（P1 册 7.3，与后端 `@PreAuthorize` 逐字一致）：路由 `meta.permission` 与后端注解共用同一批字面量。 */
export const FILE_PERMISSIONS = Object.freeze({
  list: 'platform:file:list',
  get: 'platform:file:get',
  upload: 'platform:file:upload',
  download: 'platform:file:download',
  del: 'platform:file:del',
  bind: 'platform:file:bind',
})

/** 分页边界（P1 册 5.3：`pageNum` ≥ 1，`pageSize` 1–200）。 */
const MIN_PAGE_SIZE = 1
const MAX_PAGE_SIZE = 200
const DEFAULT_PAGE_SIZE = 20

/**
 * 上传预检的两条边界，取 P1 册 7.2 的参数**默认值**（`platform.file.max-size` = 52428800、
 * `platform.file.allowed-ext` = 下面这串）。
 *
 * 前端预检只为省一次往返与一句人话提示，**不作判据**：判据在后端（参数一改，这里不会跟着变），
 * 所以预检放过的文件后端仍可能回 20011/20012，反之这里拦下的也不代表后端一定拒。
 */
// Reference defaults only; the server's hot-reloadable parameters remain authoritative.
export const FILE_MAX_SIZE_BYTES = 52_428_800

/** 与 `platform.file.allowed-ext` 的默认值逐字一致（小写、不含点）。 */
export const FILE_ALLOWED_EXTENSIONS = Object.freeze([
  'jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp',
  'pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx',
  'txt', 'csv', 'zip', '7z',
])

/** 分片大小与参数种子默认值一致；服务端仍会校验真实参数和每片摘要。 */
export const FILE_CHUNK_SIZE_BYTES = 5 * 1024 * 1024

function optional(value) {
  const text = typeof value === 'string' ? value.trim() : value
  return text === undefined || text === null || text === '' ? undefined : text
}

/** 正整数 ID（雪花 ID 从 JSON 来是 number，从表单来是字符串）：非法一律当"没填"。 */
function positiveInt(value) {
  const parsed = Number(optional(value))
  return Number.isInteger(parsed) && parsed > 0 ? parsed : undefined
}

/** 分页入参整形：与 `dict.js` / `job.js` 的 `pageOf` 逐字同款（`0` 属非法值 → 回落默认 20）。 */
function pageOf(input = {}) {
  return {
    pageNum: Math.max(1, Math.trunc(Number(input.pageNum) || 1)),
    pageSize: Math.min(MAX_PAGE_SIZE, Math.max(MIN_PAGE_SIZE, Math.trunc(Number(input.pageSize) || DEFAULT_PAGE_SIZE))),
  }
}

function sourceOf(value) {
  return FILE_SOURCES.includes(value) ? value : undefined
}

/**
 * 列表筛选 → `FileQuery`（P1 册 5.3；未填的条件省略，空串会让模糊条件退化成全表匹配）。
 *
 * `bizType` + `bizId` **必须成对**下发（后端口径同款）：只给 `bizType` 会退化成"该业务类型下任何对象
 * 的文件"，看着像筛过了其实没有——那是最难发现的一类错误，所以这里直接丢掉半个条件。
 */
export function normalizeFileQuery(input = {}) {
  const body = { ...pageOf(input) }
  const originalName = optional(input.originalName)
  if (originalName !== undefined) {
    body.originalName = originalName
  }
  const bizType = optional(input.bizType)
  const bizId = positiveInt(input.bizId)
  if (bizType !== undefined && bizId !== undefined) {
    body.bizType = bizType
    body.bizId = bizId
  }
  const uploaderId = positiveInt(input.uploaderId)
  if (uploaderId !== undefined) {
    body.uploaderId = uploaderId
  }
  const uploaderOrgId = positiveInt(input.uploaderOrgId)
  if (uploaderOrgId !== undefined) {
    body.uploaderOrgId = uploaderOrgId
  }
  const source = sourceOf(input.source)
  if (source !== undefined) {
    body.source = source
  }
  // createdFrom/createdTo 是 `Instant`（5.3 的 FileQuery）：调用方给的就是 ISO-8601 瞬时串，原样下发。
  const createdFrom = optional(input.createdFrom)
  if (createdFrom !== undefined) {
    body.createdFrom = createdFrom
  }
  const createdTo = optional(input.createdTo)
  if (createdTo !== undefined) {
    body.createdTo = createdTo
  }
  return body
}

/**
 * 绑定表单 → `FileBindCmd`（P1 册 5.2 的 `Bind`）。
 *
 * 校验失败**抛错**而不是拼一个必然被拒的请求：`bizType` 空白、`bizId` 非正整数、`fileIds` 空，
 * 后端 `@NotBlank/@Positive/@NotEmpty` 一个都不会放过，前端先拦省一次往返并把话说清楚。
 * 重复项在此去重（服务端也会去重，这里只是别让请求里出现同一 ID 两遍）。
 */
export function normalizeBindCmd({ bizType, bizId, fileIds } = {}) {
  const type = optional(bizType)
  if (type === undefined) {
    throw new Error('绑定对象的业务类型必填（bizType），例如 crm.customer')
  }
  const id = positiveInt(bizId)
  if (id === undefined) {
    throw new Error('绑定对象的业务 ID 必须是正整数（bizId）')
  }
  const ids = [...new Set((Array.isArray(fileIds) ? fileIds : []).map(positiveInt).filter((id) => id !== undefined))]
  if (ids.length === 0) {
    throw new Error('至少需要一个有效的文件 ID（fileIds）')
  }
  return { bizType: type, bizId: id, fileIds: ids }
}

/** 字节数 → 人话（列表里比原始字节可读）；`null`/空串/负数/非数回落 `-`，不做 0 的伪装。 */
export function formatSize(bytes) {
  if (bytes === null || bytes === undefined || bytes === '') {
    return '-'
  }
  const value = Number(bytes)
  if (!Number.isFinite(value) || value < 0) {
    return '-'
  }
  if (value < 1024) {
    return `${Math.trunc(value)} B`
  }
  const units = ['KB', 'MB', 'GB']
  let scaled = value / 1024
  let unit = units[0]
  for (let i = 0; i < units.length; i += 1) {
    scaled = value / 1024 ** (i + 1)
    unit = units[i]
    if (scaled < 1024) {
      break
    }
  }
  return `${scaled.toFixed(1)} ${unit}`
}

/** 上传单文件（幂等键：否，服务端自幂等）：`bizType`/`bizId` 给全就顺带绑定，省一次 Bind 往返。 */
export function upload(file, { bizType, bizId } = {}, options = {}) {
  const form = new window.FormData()
  form.append('file', file)
  const type = optional(bizType)
  if (type !== undefined) {
    form.append('bizType', type)
  }
  const id = positiveInt(bizId)
  if (id !== undefined) {
    form.append('bizId', String(id))
  }
  return requestUpload(`${FILE_BASE}/Upload`, form, options)
}

async function sha256Hex(blob) {
  const digest = await window.crypto.subtle.digest('SHA-256', await blob.arrayBuffer())
  return [...new Uint8Array(digest)].map((value) => value.toString(16).padStart(2, '0')).join('')
}

/**
 * 大文件分片上传：uploadId 与已成功分片索引保存到 localStorage，网络中断后复用会话续传。
 * 会话过期时服务端回 20018，清掉本地游标并让调用方重新选择文件。
 */
export async function uploadLarge(file, { bizType, bizId, chunkSize = FILE_CHUNK_SIZE_BYTES, onProgress } = {}) {
  const total = Math.ceil(file.size / chunkSize)
  const resumeKey = 'eaio:file-upload:' + file.name + ':' + file.size + ':' + file.lastModified
  const readState = () => {
    try {
      return JSON.parse(window.localStorage.getItem(resumeKey) ?? '{}')
    } catch {
      return {}
    }
  }
  const writeState = (state) => window.localStorage.setItem(resumeKey, JSON.stringify(state))
  let state = readState()
  if (!state.uploadId || state.total !== total) {
    state = { uploadId: crypto.randomUUID(), total, done: [] }
  }
  const done = new Set(state.done ?? [])
  try {
    for (let index = 0; index < total; index += 1) {
      if (done.has(index)) {
        onProgress?.((done.size / total) * 100)
        continue
      }
      const part = file.slice(index * chunkSize, Math.min(file.size, (index + 1) * chunkSize))
      const form = new window.FormData()
      form.append('chunk', part, file.name)
      form.append('uploadId', state.uploadId)
      form.append('chunkIndex', String(index))
      form.append('chunkTotal', String(total))
      form.append('chunkSize', String(chunkSize))
      form.append('chunkSha256', await sha256Hex(part))
      form.append('fileName', file.name)
      form.append('expectedSize', String(file.size))
      await requestUpload(FILE_BASE + '/UploadChunk', form)
      done.add(index)
      writeState({ uploadId: state.uploadId, total, done: [...done] })
      onProgress?.((done.size / total) * 100)
    }
    const body = { uploadId: state.uploadId }
    const type = optional(bizType)
    const id = positiveInt(bizId)
    if (type !== undefined && id !== undefined) {
      body.bizType = type
      body.bizId = id
    }
    const result = await action(FILE_BASE + '/MergeChunks', body, { idempotencyKey: null })
    window.localStorage.removeItem(resumeKey)
    return result
  } catch (error) {
    if (error?.code === FILE_SESSION_NOT_FOUND) {
      window.localStorage.removeItem(resumeKey)
    }
    throw error
  }
}

/** 文件元数据（5.2 `/platform/file/GetMeta`）：不存在 20014、无权 20017。 */
export function getMeta(fileId) {
  return query(`${FILE_BASE}/GetMeta`, { fileId })
}

/**
 * 带时效的下载链接（5.2 `/platform/file/GetUrl`）：返回 `{fileId, url, expireAt, storageType}`。
 * `url` 是同源路径，可直接放进 `<a href>` / `window.open`；签名无效或过期即 20016。
 */
export function getUrl(fileId, expireSeconds) {
  const body = { fileId }
  const ttl = positiveInt(expireSeconds)
  if (ttl !== undefined) {
    body.expireSeconds = ttl
  }
  return query(`${FILE_BASE}/GetUrl`, body)
}

/** 下载二进制（5.2 `/platform/file/Download`，幂等键：否）：失败体仍是 200 + JSON，由请求层分辨。 */
export function download(fileId) {
  return requestDownload(`${FILE_BASE}/Download`, { fileId })
}

/** 绑定到业务对象（幂等键：是；唯一键 + `ON CONFLICT DO NOTHING`，重复绑定不报错）。 */
export function bind(cmd, options = {}) {
  return action(`${FILE_BASE}/Bind`, normalizeBindCmd(cmd), options)
}

/** 逻辑删除（幂等键：是）：`version` 必填，过期即 10003；不存在 20014、无权 20017。 */
export function del(fileId, version, options = {}) {
  return action(`${FILE_BASE}/Del`, { fileId, version }, options)
}

/** 分页查询（5.2 `/platform/file/GetPage`）：`PageResult<FileDTO>`。 */
export function getPage(fileQuery) {
  return query(`${FILE_BASE}/GetPage`, normalizeFileQuery(fileQuery))
}

/**
 * 把已下载的 blob 交给浏览器保存：object URL + 临时 `<a download>`。
 *
 * 用 object URL 而不是 `window.open(url)`：带 `download` 的 `<a>` 才会用 `Content-Disposition`
 * 里的文件名存盘，也不会被弹窗拦截器吃掉。用完立刻 `revokeObjectURL`，否则每次下载都漏一份内存。
 */
export function triggerBrowserDownload({ data: blob, filename } = {}) {
  const url = window.URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  if (filename) {
    link.download = filename
  }
  link.style.display = 'none'
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  window.URL.revokeObjectURL(url)
}

/**
 * `Result.code` → 用户可执行提示（唯一分支依据；中文 message 只做兜底展示，绝不做字符串匹配）。
 *
 * 幂等键/乐观锁/无权限与动作无关，写动作共用一段文案；其余码天然唯一。
 * 20011/20012 的文案点出**判据在后端参数**：前端改不了白名单，用户要知道该找谁改。
 */
const CODE_HINTS = Object.freeze({
  [IDEMPOTENCY_KEY_MISSING]: '请求缺少幂等键（code=10001），这是前端问题：请重新提交一次',
  [DATA_CONFLICT]: '该文件已被他人修改或删除（code=10003）：请刷新列表后用最新版本重试',
  [FILE_EMPTY]: '上传内容为空（code=20010）：请重新选择文件',
  [FILE_TOO_LARGE]: '文件超过大小上限（code=20011）：上限由参数 platform.file.max-size 控制（默认 50MB），请压缩后再传',
  [FILE_TYPE_NOT_ALLOWED]: '文件类型不在白名单（code=20012）：判据是扩展名，白名单由参数 platform.file.allowed-ext 控制（改 Content-Type 没用）',
  [FILE_NOT_FOUND]: '文件不存在（code=20014）：可能已被删除，请刷新列表',
  [FILE_STORAGE_ERROR]: '文件存储读写失败（code=20015）：磁盘或 S3 异常，请稍后重试或联系运维（不会自动换另一种存储）',
  [FILE_SIGNATURE_INVALID]: '下载链接签名无效或已过期（code=20016）：重新生成一次链接即可（本地盘链接带时效签名）',
  [FILE_ACCESS_DENIED]: '无权访问该文件（code=20017）：上传者本人、同组织或持有 platform:file:download 的管理员才能下载',
  [FILE_CHUNK_INVALID]: '分片校验失败（code=20013）：请重传提示的分片后再合并',
  [FILE_SESSION_NOT_FOUND]: '分片会话不存在或已过期（code=20018）：请重新选择文件上传',
  [FORBIDDEN_CODE]: '无权限（code=10403）：后端按权限点拒绝，请联系管理员分配 platform:file:* 权限',
})

/** 动作失败提示。未登记的码回落到"后端 message + code + traceId"，不猜语义。 */
export function describeWriteFailure(error) {
  const hint = CODE_HINTS[error?.code]
  if (hint) {
    return hint
  }
  return `${error?.message ?? '操作失败'}（code=${error?.code ?? 'unknown'}，traceId=${error?.traceId ?? '-'}）`
}
