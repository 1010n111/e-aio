import { DATA_CONFLICT, FORBIDDEN_CODE, IDEMPOTENCY_KEY_MISSING } from '@/api/codes'
import { action, query } from '@/api/request'

/**
 * 数据字典 API（P1 册 5.2 的 11 个端点；动作地址省略 context-path `/api`，请求层 baseURL 已带）。
 *
 * **与设计册 3.10.3 的差异**：那里拆成 `dictType.js` / `dictItem.js` 两个文件；本票要求单文件
 * `dict.js`，理由是两者共用同一个 `DictAppService`（2.4.3）与同一套错误码，拆开只会让提示文案与
 * 分页整形各写两份。两份导出命名空间（`dictType` / `dictItem`）保持 3.10.3 的方法名不变，
 * 差异登记在《实现注记（T5）》。
 */
const DICT_TYPE_BASE = '/platform/dictType'
const DICT_ITEM_BASE = '/platform/dictItem'

// ---- 判定用的码值（逐字对齐后端 ErrorCode / PlatformErrorCode；前端只按码分支，不做中文匹配）----
//
// 通用段（10001/10003/10403）从 `@/api/codes` 取，平台段（20003/20005/20007/20008）在本文件声明：
// 业务段码不得被通用段重定义，通用段码也不该在业务档案里各抄一份（P1 册 7.1）。

/** 字典类型不存在（含"编码已存在"，见 5.2 给 `dictType/Add` 的错误码）。 */
export const DICT_TYPE_NOT_FOUND = 20003

/** 内置只读：段内没有 dict 专用码，后端复用 20005（`PARAM_BUILTIN_READONLY`）。 */
export const DICT_BUILTIN_READONLY = 20005

/** 同类型下项值重复。 */
export const DICT_ITEM_DUPLICATED = 20007

/** 类型下仍有字典项。 */
export const DICT_TYPE_IN_USE = 20008

/** 状态取值（后端 `DictStatus` + DDL 的 CHECK，P1 册 4.3.3/4.3.4）。 */
export const DICT_STATUSES = Object.freeze(['ENABLED', 'DISABLED'])

/** 权限点（P1 册 7.3，与后端 `@PreAuthorize` 逐字一致）：路由 `meta.permission` 与后端注解共用同一批字面量。 */
export const DICT_PERMISSIONS = Object.freeze({
  list: 'platform:dict:list',
  get: 'platform:dict:get',
  add: 'platform:dict:add',
  up: 'platform:dict:up',
  del: 'platform:dict:del',
  refresh: 'platform:dict:refresh',
})

/** 分页边界（P1 册 5.3：`pageNum` ≥ 1，`pageSize` 1–200）。 */
const MIN_PAGE_SIZE = 1
const MAX_PAGE_SIZE = 200
const DEFAULT_PAGE_SIZE = 20

/** 字段上限（与 `DictTypeSaveCmd`/`DictItemSaveCmd` 的 `@Size` 一致）：前端先拦，省一次 400 往返。 */
export const TYPE_CODE_MAX_LENGTH = 64
export const ITEM_VALUE_MAX_LENGTH = 128
export const ITEM_LABEL_MAX_LENGTH = 128
export const EXT_JSON_MAX_LENGTH = 2000

function optional(value) {
  const text = typeof value === 'string' ? value.trim() : value
  return text === undefined || text === null || text === '' ? undefined : text
}

function pageOf(input = {}) {
  return {
    pageNum: Math.max(1, Math.trunc(Number(input.pageNum) || 1)),
    pageSize: Math.min(
      MAX_PAGE_SIZE,
      Math.max(MIN_PAGE_SIZE, Math.trunc(Number(input.pageSize) || DEFAULT_PAGE_SIZE)),
    ),
  }
}

function statusOf(value) {
  return DICT_STATUSES.includes(value) ? value : undefined
}

/** 类型分页条件 → `DictTypeQuery`（P1 册 5.3；未填的条件省略，`typeCode: ""` 会让模糊条件退化成全表匹配）。 */
export function normalizeTypeQuery(input = {}) {
  const body = { ...pageOf(input) }
  const typeCode = optional(input.typeCode)
  if (typeCode !== undefined) {
    body.typeCode = typeCode
  }
  const typeName = optional(input.typeName)
  if (typeName !== undefined) {
    body.typeName = typeName
  }
  const status = statusOf(input.status)
  if (status !== undefined) {
    body.status = status
  }
  return body
}

/** 项分页条件 → `DictItemQuery`；`typeCode` 是**精确**匹配（项列表总是"某类型下"的列表）。 */
export function normalizeItemQuery(input = {}) {
  const body = { ...pageOf(input) }
  const typeCode = optional(input.typeCode)
  if (typeCode !== undefined) {
    body.typeCode = typeCode
  }
  const itemValue = optional(input.itemValue)
  if (itemValue !== undefined) {
    body.itemValue = itemValue
  }
  const status = statusOf(input.status)
  if (status !== undefined) {
    body.status = status
  }
  return body
}

/**
 * 编辑器表单 → `DictTypeSaveCmd`（P1 册 5.3）。
 *
 * `typeCode` 是行身份、`Up` 时不可改（3.2.4），因此编辑既有类型时也照原样带上（后端按它定位行）；
 * `version` 由调用方按当前模式决定（`Add` 传 `null` 让后端自己定；`Up` 必填，否则 10003）。
 */
export function normalizeTypeSaveCmd(input = {}) {
  const body = {
    typeCode: typeof input.typeCode === 'string' ? input.typeCode.trim() : input.typeCode,
    typeName: typeof input.typeName === 'string' ? input.typeName.trim() : input.typeName,
    status: statusOf(input.status) ?? 'ENABLED',
  }
  const remark = typeof input.remark === 'string' ? input.remark.trim() : input.remark
  if (remark !== undefined && remark !== '') {
    body.remark = remark
  }
  const version = Number(optional(input.version))
  body.version = Number.isFinite(version) ? Math.trunc(version) : null
  return body
}

/**
 * 编辑器表单 → `DictItemSaveCmd`。
 *
 * `itemValue` 同为行身份、`Up` 时不可改；`extJson` 原样下发（JSONB 列无结构校验，3.2.4），
 * 空值下发 `null` 而不是空串——空串不是合法 JSON，落进 JSONB 列会直接失败。
 */
export function normalizeItemSaveCmd(input = {}) {
  const extJson = optional(input.extJson)
  const body = {
    typeCode: typeof input.typeCode === 'string' ? input.typeCode.trim() : input.typeCode,
    itemValue: typeof input.itemValue === 'string' ? input.itemValue.trim() : input.itemValue,
    itemLabel: typeof input.itemLabel === 'string' ? input.itemLabel.trim() : input.itemLabel,
    sortNo: Math.trunc(Number(input.sortNo) || 0),
    status: statusOf(input.status) ?? 'ENABLED',
    isDefault: input.isDefault === true,
    extJson: extJson === undefined ? null : extJson,
  }
  const version = Number(optional(input.version))
  body.version = Number.isFinite(version) ? Math.trunc(version) : null
  return body
}

/** 类型分页（5.2 `/platform/dictType/GetPage`）：每行带 `itemCount`。 */
export function getTypePage(typeQuery) {
  return query(`${DICT_TYPE_BASE}/GetPage`, normalizeTypeQuery(typeQuery))
}

/** 类型详情（`{id}`）：不存在抛 20003。 */
export function getType(id) {
  return query(`${DICT_TYPE_BASE}/Get`, { id })
}

/** 新增类型（幂等键：是）。 */
export function addType(cmd, options = {}) {
  return action(`${DICT_TYPE_BASE}/Add`, normalizeTypeSaveCmd(cmd), options)
}

/** 更新类型（幂等键：是；`version` 必填，过期即 10003）。 */
export function upType(cmd, options = {}) {
  return action(`${DICT_TYPE_BASE}/Up`, normalizeTypeSaveCmd(cmd), options)
}

/** 删除类型（幂等键：是）：仍有项 20008、内置只读 20005。 */
export function delType(id, version, options = {}) {
  return action(`${DICT_TYPE_BASE}/Del`, { id, version }, options)
}

/** 刷新某类型的缓存（幂等键：是）：类型不存在抛 20003。 */
export function refreshType(typeCode, options = {}) {
  return action(`${DICT_TYPE_BASE}/Refresh`, { typeCode }, options)
}

/** 启用项（5.2 `/platform/dictItem/GetItems`，不分页，按 sortNo）；类型不存在抛 20003。 */
export function getItems(typeCode) {
  return query(`${DICT_ITEM_BASE}/GetItems`, { typeCode })
}

/** 项分页（管理页，含停用项）。 */
export function getItemPage(itemQuery) {
  return query(`${DICT_ITEM_BASE}/GetPage`, normalizeItemQuery(itemQuery))
}

/** 新增项（幂等键：是）：类型不存在 20003、值重复 20007。 */
export function addItem(cmd, options = {}) {
  return action(`${DICT_ITEM_BASE}/Add`, normalizeItemSaveCmd(cmd), options)
}

/** 更新项（幂等键：是）：`version` 必填，过期即 10003。 */
export function upItem(cmd, options = {}) {
  return action(`${DICT_ITEM_BASE}/Up`, normalizeItemSaveCmd(cmd), options)
}

/** 删除项（幂等键：是）：内置类型下的项不可删（20005）。 */
export function delItem(id, version, options = {}) {
  return action(`${DICT_ITEM_BASE}/Del`, { id, version }, options)
}

/**
 * 两个资源的命名空间导出：设计册 3.10.3 的 `dictType.js` / `dictItem.js` 方法名在单文件下靠命名空间保留，
 * 既避免 11 个同名函数（`getPage`/`add`/`up`/`del` 各两份）在同一模块里互相遮蔽，也让调用点一眼看出在改什么。
 */
export const dictType = Object.freeze({
  getPage: getTypePage,
  get: getType,
  add: addType,
  up: upType,
  del: delType,
  refresh: refreshType,
})

export const dictItem = Object.freeze({
  getItems,
  getPage: getItemPage,
  add: addItem,
  up: upItem,
  del: delItem,
})

/**
 * `Result.code` → 用户可执行提示（唯一分支依据；中文 message 只做兜底展示，绝不做字符串匹配）。
 *
 * 幂等键与乐观锁冲突与动作无关，六种写动作共用一段文案；其余码天然唯一。
 */
const CODE_HINTS = Object.freeze({
  [IDEMPOTENCY_KEY_MISSING]: '请求缺少幂等键（code=10001），这是前端问题：请重新提交一次',
  [DATA_CONFLICT]: '该记录已被他人修改（code=10003）：请刷新后用最新版本重试',
  [DICT_TYPE_NOT_FOUND]: '字典类型不存在（code=20003）：可能已被他人删除，或该编码已存在，请刷新列表',
  [DICT_BUILTIN_READONLY]: '平台内置类型及其字典项不可删除（code=20005）：建议改用停用',
  [DICT_ITEM_DUPLICATED]: '同类型下已存在该字典项值（code=20007）',
  [DICT_TYPE_IN_USE]: '该类型下仍有字典项（code=20008）：请先删除或停用项，再删除类型',
  [FORBIDDEN_CODE]: '无权限（code=10403）：后端按权限点拒绝，请联系管理员分配 platform:dict:* 权限',
})

/** 写动作失败提示。未登记的码回落到"后端 message + code + traceId"，不猜语义。 */
export function describeWriteFailure(error) {
  const hint = CODE_HINTS[error?.code]
  if (hint) {
    return hint
  }
  return `${error?.message ?? '操作失败'}（code=${error?.code ?? 'unknown'}，traceId=${error?.traceId ?? '-'}）`
}
