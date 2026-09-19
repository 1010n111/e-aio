import { DATA_CONFLICT, FORBIDDEN_CODE, IDEMPOTENCY_KEY_MISSING } from '@/api/codes'
import { action, query } from '@/api/request'
import {
  PARAM_BUILTIN_READONLY,
  PARAM_DUPLICATED,
  PARAM_NOT_FOUND,
  PARAM_SCOPE_INVALID,
  PARAM_TYPE_MISMATCH,
} from '@/api/platform/paramCodes'

/** 动作地址：省略 context-path `/api`（请求层 baseURL 已带，见 `api/request.js`）。 */
const PARAM_BASE = '/platform/param'

/** 分级配置的三级（P1 册 3.1.1）；下拉选项与入参校验共用一处，避免两处漂移。 */
export const PARAM_LEVELS = Object.freeze(['SYSTEM', 'ORG', 'USER'])

/** 值类型（后端 `ParamValueType` 枚举名，P1 册 4.3.1）；`SECRET` 由后端加密落库。 */
export const PARAM_VALUE_TYPES = Object.freeze(['STRING', 'INT', 'BOOL', 'DECIMAL', 'JSON', 'SECRET'])

/**
 * `encrypted = true` 时后端恒下发这个掩码（P1 册 5.3），前端只做**展示与判定**，不解密不改写。
 * 判定用途只有一个：掩码不是真实值，绝不能让用户把它当成"当前值"再提交回去。
 */
export const MASKED_PARAM_VALUE = '******'

/** 分页边界（P1 册 5.3：`pageNum` ≥ 1，`pageSize` 1–200）。 */
const MIN_PAGE_SIZE = 1
const MAX_PAGE_SIZE = 200
const DEFAULT_PAGE_SIZE = 20

/** `paramKey` 上限（P1 册 5.3 `ParamSaveCmd`）；前端先拦，省一次 400 往返。 */
export const PARAM_KEY_MAX_LENGTH = 128

function optional(value) {
  const text = typeof value === 'string' ? value.trim() : value
  return text === undefined || text === null || text === '' ? undefined : text
}

/**
 * 把页面查询条件整形为 `ParamQuery` 请求体（P1 册 5.3、2036 行）。
 *
 * 未填的条件一律省略而不是下发空串：`paramKey: ""` 会让模糊条件退化成全表匹配，
 * `ownerId: null` 也不是合法的 long；`ownerId: 0` 是 SYSTEM 级的合法值，必须保留。
 */
export function normalizeParamQuery(input = {}) {
  const body = {}
  const paramKey = optional(input.paramKey)
  if (paramKey !== undefined) {
    body.paramKey = paramKey
  }
  if (PARAM_LEVELS.includes(input.paramLevel)) {
    body.paramLevel = input.paramLevel
  }
  const ownerId = Number(optional(input.ownerId))
  if (Number.isFinite(ownerId)) {
    body.ownerId = ownerId
  }
  const paramGroup = optional(input.paramGroup)
  if (paramGroup !== undefined) {
    body.paramGroup = paramGroup
  }
  body.pageNum = Math.max(1, Math.trunc(Number(input.pageNum) || 1))
  body.pageSize = Math.min(
    MAX_PAGE_SIZE,
    Math.max(MIN_PAGE_SIZE, Math.trunc(Number(input.pageSize) || DEFAULT_PAGE_SIZE)),
  )
  return body
}

/**
 * 把编辑器的表单整形为 `ParamSaveCmd`（P1 册 5.3）：只下发契约字段，未知键不带上。
 *
 * - `paramLevel = SYSTEM` → `ownerId` 直接置 0（后端 `ParamLevel.accepts` 要求 SYSTEM 恒 0，否则 20006），
 *   不给用户填的机会；
 * - `Up` 必须带 `version`（乐观锁，缺了后端 `up` 直接 10003）；`Add` 传 `null` 让后端自己定；
 * - `paramValue` 原样保留（不 trim）：空串是"保持不变"的既有语义（P1-2 册 331 行），且去掉首尾空白
 *   会悄悄改掉用户写的值；只有 `null/undefined` 归一为空串，避免下发 `null`；
 * - **不下发 `remark`**：P1-3 册 5.3 的 `ParamSaveCmd` 虽接受该字段，但 `ParamDTO` 没有它
 *   （册 439 行第 17 条）：读不回来的字段不做"只能写不能读"的入口，页面也不再提供输入框。
 */
export function normalizeParamSaveCmd(input = {}) {
  const paramLevel = PARAM_LEVELS.includes(input.paramLevel) ? input.paramLevel : 'SYSTEM'
  const body = {
    paramKey: typeof input.paramKey === 'string' ? input.paramKey.trim() : input.paramKey,
    paramLevel,
    ownerId: paramLevel === 'SYSTEM' ? 0 : Math.trunc(Number(optional(input.ownerId)) || 0),
    paramValue: input.paramValue === undefined || input.paramValue === null ? '' : String(input.paramValue),
    valueType: input.valueType,
  }
  const paramGroup = optional(input.paramGroup)
  if (paramGroup !== undefined) {
    body.paramGroup = paramGroup
  }
  const version = Number(optional(input.version))
  body.version = Number.isFinite(version) ? Math.trunc(version) : null
  return body
}

/** 分页查询（P1 册 1939 行）：`ParamQuery` → `PageResult<ParamWithSourceDTO>`。 */
export function getPage(paramQuery) {
  return query(`${PARAM_BASE}/GetPage`, normalizeParamQuery(paramQuery))
}

/** 按分组取全部（不分页，P1 册 1941 行）：`{paramGroup?}` → `List<ParamWithSourceDTO>`。 */
export function getAll({ paramGroup } = {}) {
  const group = optional(paramGroup)
  return query(`${PARAM_BASE}/GetAll`, group === undefined ? {} : { paramGroup: group })
}

/** 单条生效值（P1 册 1940 行）：`{paramKey, paramLevel?, ownerId?}` → `ParamWithSourceDTO`。 */
export function get(paramKey, { paramLevel, ownerId } = {}) {
  const body = { paramKey }
  if (PARAM_LEVELS.includes(paramLevel)) {
    body.paramLevel = paramLevel
  }
  const owner = Number(optional(ownerId))
  if (Number.isFinite(owner)) {
    body.ownerId = owner
  }
  return query(`${PARAM_BASE}/Get`, body)
}

/**
 * 新增（P1 册 1942 行）：`ParamSaveCmd` → `ParamDTO`。幂等键要求：**是**。
 *
 * @param options.idempotencyKey 由调用方生成一次并在重试时复用（见 `@/api/idempotency`）
 */
export function add(cmd, options = {}) {
  return action(`${PARAM_BASE}/Add`, normalizeParamSaveCmd(cmd), options)
}

/** 更新（P1 册 1943 行）：`ParamSaveCmd` → `ParamDTO`。幂等键要求：**是**。 */
export function up(cmd, options = {}) {
  return action(`${PARAM_BASE}/Up`, normalizeParamSaveCmd(cmd), options)
}

/** 删除（P1 册 1944 行）：`{id, version}` → `Void`。幂等键要求：**是**。 */
export function del(id, version, options = {}) {
  return action(`${PARAM_BASE}/Del`, { id, version }, options)
}

/**
 * 刷新缓存（P1 册 1945 行）：`{paramKey?}` → `Void`。幂等键要求：**是**。
 * `paramKey` 省略 = 全量失效（DBA 绕过接口改库后的兜底）。
 */
export function refresh(paramKey, options = {}) {
  const key = optional(paramKey)
  return action(`${PARAM_BASE}/Refresh`, key === undefined ? {} : { paramKey: key }, options)
}

/**
 * `Result.code` → 用户可执行提示（唯一分支依据；中文 message 只做兜底展示，绝不做字符串匹配）。
 *
 * 幂等键与乐观锁冲突这两个码与动作无关，四种写动作共用一段文案；其余码天然唯一，不必按动作分叉。
 */
const CODE_HINTS = Object.freeze({
  [IDEMPOTENCY_KEY_MISSING]: '请求缺少幂等键（code=10001），这是前端问题：请重新提交一次',
  [DATA_CONFLICT]: '参数已被他人修改（code=10003）：请刷新列表后用最新版本重试',
  [PARAM_NOT_FOUND]: '参数不存在（code=20001）：可能已被他人删除，请刷新列表',
  [PARAM_TYPE_MISMATCH]: '参数值与所选类型不匹配（code=20002）：请检查值与值类型',
  [PARAM_DUPLICATED]: '该键在这一级别/归属下已存在（code=20004）',
  [PARAM_BUILTIN_READONLY]: '平台内置参数不可删除（code=20005）',
  [PARAM_SCOPE_INVALID]: '参数归属非法（code=20006）：SYSTEM 级必须 ownerId=0，ORG/USER 级必须大于 0',
  [FORBIDDEN_CODE]: '无权限（code=10403）：后端 @PreAuthorize 是权威，请联系管理员分配权限点',
})

/** 写动作失败提示。未登记的码回落到"后端 message + code + traceId"，不猜语义。 */
export function describeWriteFailure(error) {
  const hint = CODE_HINTS[error?.code]
  if (hint) {
    return hint
  }
  return `${error?.message ?? '操作失败'}（code=${error?.code ?? 'unknown'}，traceId=${error?.traceId ?? '-'}）`
}

/**
 * 把列表行摊平成编辑器要的"可写行"（`{id, version, builtin, encrypted}`，只读视图为 null）。
 *
 * 三种身份必须分清，否则会写错行：
 * - `paramLevel` 取 DTO 的 `sourceLevel`（行自己的级别）；DEFAULT/YAML 来源没有行 → 落 SYSTEM 级新行；
 * - **归属 ID 只能从候选行取**：`ParamWithSourceDTO` 没有 `ownerId` 字段（P1 册 5.3），
 *   拿 `row.ownerId` 去匹配会永远是 0，从而一个候选都对不上、`Up`/`Del` 悄悄退化成新增/删不掉；
 * - 同一级别下候选可能不止一条（列表按行分页，同键的 ORG 行可以有多条），取 `ownerId` 最小的那条
 *   作为可写行，页面靠展开候选看全部；无候选 → `id/version` 为 null，走 `Add`。
 */
export function toParamRow(row) {
  const level = PARAM_LEVELS.includes(row?.sourceLevel) ? row.sourceLevel : 'SYSTEM'
  const candidates = row?.candidates ?? []
  const candidate = candidates
    .filter((item) => item?.paramLevel === level && item.id)
    .sort((left, right) => Number(left.ownerId ?? 0) - Number(right.ownerId ?? 0))[0]
  if (!candidate) {
    return {
      paramKey: row?.paramKey,
      paramLevel: level,
      ownerId: 0,
      paramValue: '',
      valueType: 'STRING',
      paramGroup: null,
      version: null,
      id: null,
      builtin: false,
      encrypted: false,
    }
  }
  // 掩码值不是真实值：留空即"保持不变"，绝不把 `******` 当值提交回后端。
  const masked = candidate.encrypted === true || candidate.paramValue === MASKED_PARAM_VALUE
  const candidateLevel = PARAM_LEVELS.includes(candidate.paramLevel) ? candidate.paramLevel : level
  return {
    paramKey: candidate.paramKey ?? row?.paramKey,
    paramLevel: candidateLevel,
    ownerId: candidateLevel === 'SYSTEM' ? 0 : Math.trunc(Number(candidate.ownerId) || 0),
    paramValue: masked ? '' : (candidate.paramValue ?? ''),
    valueType: candidate.valueType ?? 'STRING',
    paramGroup: candidate.paramGroup ?? null,
    version: Math.trunc(Number(candidate.version) || 0),
    id: candidate.id ?? null,
    builtin: candidate.builtin === true,
    encrypted: masked,
  }
}
