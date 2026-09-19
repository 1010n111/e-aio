import { query } from '@/api/request'

/** 动作地址：省略 context-path `/api`（请求层 baseURL 已带，见 `api/request.js`）。 */
const PARAM_BASE = '/platform/param'

/** 分级配置的三级（P1 册 3.1.1）；下拉选项与入参校验共用一处，避免两处漂移。 */
export const PARAM_LEVELS = Object.freeze(['SYSTEM', 'ORG', 'USER'])

/** 分页边界（P1 册 5.3：`pageNum` ≥ 1，`pageSize` 1–200）。 */
const MIN_PAGE_SIZE = 1
const MAX_PAGE_SIZE = 200
const DEFAULT_PAGE_SIZE = 20

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
 * 分页查询（P1 册 1926 行）：`ParamQuery` → `PageResult<ParamWithSourceDTO>`。
 * 分页返回的就是 `ParamWithSourceDTO`，列表直接展示生效值/来源，无需逐行再调 `Get`。
 */
export function getPage(paramQuery) {
  return query(`${PARAM_BASE}/GetPage`, normalizeParamQuery(paramQuery))
}

/** 按分组取全部（不分页）：`{paramGroup?}` → `List<ParamWithSourceDTO>`（P1 册 1928 行）。 */
export function getAll({ paramGroup } = {}) {
  const group = optional(paramGroup)
  return query(`${PARAM_BASE}/GetAll`, group === undefined ? {} : { paramGroup: group })
}

// 本票只读：`Get`（单条）/ `Add` / `Up` / `Del` / `Refresh` 与写页面同票落地——
// 这里不预置用不到的动作，免得留下没人调用、没人验证的空壳导出。
