import { DATA_CONFLICT, FORBIDDEN_CODE, IDEMPOTENCY_KEY_MISSING } from '@/api/codes'
import { action, query } from '@/api/request'

/**
 * 定时任务 API（P1 册 5.2 的 `/platform/job` 8 个端点 + `/platform/jobRun` 3 个端点）。
 *
 * 三条口径（与 `dict.js` 同款）：
 * - 路径省略 context-path `/api`（请求层 baseURL 已带）；
 * - **只按 `Result.code` 分支**，不做中文 message 匹配（api-conventions）；
 * - 写动作每动作一个幂等键（5.6）；读动作不带。
 *
 * 权限点与 7.3 逐字一致（`platform:job:list/get/add/up/del/run`）：Pause/Resume 也用 `run`
 * ——7.3 的 job 段没有 pause/resume 点，而 5.2 给这两个端点的权限点正是 `platform:job:run`。
 */
const JOB_BASE = '/platform/job'
const JOB_RUN_BASE = '/platform/jobRun'

// ---- 判定用的码值（逐字对齐后端 PlatformErrorCode 20020–20025；前端只按码分支）----

/** 任务/运行记录不存在（`jobCode`/`runId` 无效）。 */
export const JOB_NOT_FOUND = 20020

/** 任务正在执行（不允许并发时重复触发、删除运行中的任务）。 */
export const JOB_RUNNING = 20021

/** Cron 表达式非法（Spring 6 段；Quartz 的 ?/L/W/# 不支持）。 */
export const JOB_CRON_INVALID = 20022

/** 处理点未注册（可执行点只能来自代码注册的 JobHandler Bean）。 */
export const JOB_HANDLER_NOT_REGISTERED = 20023

/** 任务已停用（停用后手动触发被拒）。 */
export const JOB_DISABLED = 20024

/** 执行超时（只出现在运行日志状态里；同步接口不返回它）。 */
export const JOB_TIMEOUT = 20025

/** 运行状态（后端 `JobRunStatus` + DDL 的 CHECK，P1 册 4.3.10）。 */
export const JOB_RUN_STATUSES = Object.freeze(['RUNNING', 'SUCCESS', 'FAILED', 'TIMEOUT', 'RETRYING', 'SKIPPED'])

/** 触发来源（后端 `JobTriggerType` + 字典 `platform_job_trigger_type` 的项值）。 */
export const JOB_TRIGGER_TYPES = Object.freeze(['CRON', 'MANUAL', 'RETRY'])

/** 续跑状态：这些状态不再变化，可以重试；RUNNING 不能重试（后端也拒 20021）。 */
export const JOB_RUN_RETRYABLE = Object.freeze(['FAILED', 'TIMEOUT', 'SUCCESS', 'SKIPPED'])

/** 权限点（P1 册 7.3，与后端 `@PreAuthorize` 逐字一致）。 */
export const JOB_PERMISSIONS = Object.freeze({
  list: 'platform:job:list',
  get: 'platform:job:get',
  add: 'platform:job:add',
  up: 'platform:job:up',
  del: 'platform:job:del',
  run: 'platform:job:run',
})

/** 分页边界（P1 册 5.3：pageNum ≥ 1、pageSize 1–200）。 */
const MIN_PAGE_SIZE = 1
const MAX_PAGE_SIZE = 200
const DEFAULT_PAGE_SIZE = 20

/** 字段上限（与 `JobSaveCmd` 的 @Size 一致）：前端先拦，省一次 400 往返。 */
export const JOB_CODE_MAX_LENGTH = 64
export const JOB_NAME_MAX_LENGTH = 128
export const HANDLER_CODE_MAX_LENGTH = 128
export const CRON_MAX_LENGTH = 64
export const REMARK_MAX_LENGTH = 255

/** 重投上限的边界（DDL `ck_job_retry`：0–10）。 */
export const RETRY_MAX_LIMIT = 10

/** 默认值（P1 册 7.2/5.3：超时 300s、重投 3 次、退避 30s）。 */
export const DEFAULT_TIMEOUT_SECONDS = 300
export const DEFAULT_RETRY_MAX = 3
export const DEFAULT_BACKOFF_SECONDS = 30

function optional(value) {
  const text = typeof value === 'string' ? value.trim() : value
  return text === undefined || text === null || text === '' ? undefined : text
}

function intOr(value, fallback) {
  const parsed = Math.trunc(Number(value))
  return Number.isFinite(parsed) ? parsed : fallback
}

/**
 * 分页入参整形：与 `dict.js` 的 `pageOf` **逐字同款**（`0` 属非法值 → 回落默认页大小 20，
 * 而不是被夹成 1 页 —— 两个页面的分页语义必须一致，否则同一份使用习惯在两个页面表现不同）。
 */
function pageOf(input = {}) {
  return {
    pageNum: Math.max(1, Math.trunc(Number(input.pageNum) || 1)),
    pageSize: Math.min(MAX_PAGE_SIZE, Math.max(MIN_PAGE_SIZE, Math.trunc(Number(input.pageSize) || DEFAULT_PAGE_SIZE))),
  }
}

function statusOf(value) {
  const text = optional(value)
  return text !== undefined && JOB_RUN_STATUSES.includes(text) ? text : undefined
}

/** 任务分页条件 → `JobQuery`（jobCode 模糊、enabled 精确）。 */
export function normalizeJobQuery(input = {}) {
  const body = { ...pageOf(input) }
  const jobCode = optional(input.jobCode)
  if (jobCode !== undefined) {
    body.jobCode = jobCode
  }
  if (input.enabled === true || input.enabled === false) {
    body.enabled = input.enabled
  }
  return body
}

/** 运行日志分页条件 → `JobRunQuery`（jobCode/status 精确，时间区间可选）。 */
export function normalizeJobRunQuery(input = {}) {
  const body = { ...pageOf(input) }
  const jobCode = optional(input.jobCode)
  if (jobCode !== undefined) {
    body.jobCode = jobCode
  }
  const status = statusOf(input.status)
  if (status !== undefined) {
    body.status = status
  }
  const startFrom = optional(input.startFrom)
  if (startFrom !== undefined) {
    body.startFrom = startFrom
  }
  const startTo = optional(input.startTo)
  if (startTo !== undefined) {
    body.startTo = startTo
  }
  return body
}

/**
 * 参数文本（界面上的 JSON 文本域）→ `params` 对象。
 *
 * 传空 = `null`（后端用它表示"用任务保存的参数"）；解析失败**抛错**而不是静默传空——
 * "少了一个参数"的任务跑起来不会报错，只会做出错误的结果。
 */
export function parseParamText(text) {
  const raw = optional(text)
  if (raw === undefined) {
    return null
  }
  let parsed
  try {
    parsed = JSON.parse(raw)
  } catch {
    throw new Error('任务参数必须是合法 JSON 对象，例如 {"days":"90"}')
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('任务参数必须是 JSON 对象（键 → 值），不能是数组或标量')
  }
  const params = {}
  for (const [key, value] of Object.entries(parsed)) {
    params[key] = typeof value === 'string' ? value : String(value)
  }
  return params
}

/** 参数对象 → 界面文本。 */
export function formatParams(params) {
  if (!params || Object.keys(params).length === 0) {
    return ''
  }
  return JSON.stringify(params, null, 2)
}

/**
 * 编辑器表单 → `JobSaveCmd`（P1 册 5.3）。
 *
 * `jobCode` 是行身份、`Up` 时不可改（3.4.6），编辑既有任务时也照原样带上（后端按它定位行）；
 * `version` 由调用方按模式决定（Add 传 null，Up 必填，否则 10003）。
 */
export function normalizeJobSaveCmd(input = {}) {
  const params = typeof input.params === 'string' ? parseParamText(input.params) : (input.params ?? null)
  const body = {
    jobCode: typeof input.jobCode === 'string' ? input.jobCode.trim() : input.jobCode,
    jobName: typeof input.jobName === 'string' ? input.jobName.trim() : input.jobName,
    handlerCode: typeof input.handlerCode === 'string' ? input.handlerCode.trim() : input.handlerCode,
    cron: typeof input.cron === 'string' ? input.cron.trim() : input.cron,
    params,
    enabled: input.enabled !== false,
    timeoutSeconds: intOr(input.timeoutSeconds, DEFAULT_TIMEOUT_SECONDS),
    retryMax: Math.min(RETRY_MAX_LIMIT, Math.max(0, intOr(input.retryMax, DEFAULT_RETRY_MAX))),
    backoffSeconds: Math.max(0, intOr(input.backoffSeconds, DEFAULT_BACKOFF_SECONDS)),
    allowConcurrent: input.allowConcurrent === true,
  }
  const remark = optional(input.remark)
  if (remark !== undefined) {
    body.remark = remark
  }
  const version = Number(optional(input.version))
  body.version = Number.isFinite(version) ? Math.trunc(version) : null
  return body
}

/** 任务分页（5.2 `/platform/job/GetPage`）：每行带 `scheduled`/`running`/`nextRunTime`。 */
export function getPage(jobQuery) {
  return query(`${JOB_BASE}/GetPage`, normalizeJobQuery(jobQuery))
}

/** 任务详情（`{jobCode}`）：不存在抛 20020。 */
export function get(jobCode) {
  return query(`${JOB_BASE}/Get`, { jobCode })
}

/** 新增任务（幂等：是）：cron 非法 20022、处理点未注册 20023、编码已存在 10003。 */
export function add(cmd, options = {}) {
  return action(`${JOB_BASE}/Add`, normalizeJobSaveCmd(cmd), options)
}

/** 更新任务（幂等：是）：`version` 必填，过期即 10003；改完立刻重建调度。 */
export function up(cmd, options = {}) {
  return action(`${JOB_BASE}/Up`, normalizeJobSaveCmd(cmd), options)
}

/** 删除任务（幂等：是）：正在执行 20021。 */
export function del(jobCode, version, options = {}) {
  return action(`${JOB_BASE}/Del`, { jobCode, version }, options)
}

/** 手动触发（幂等：是）：已停用 20024、正在执行且未允许并发 20021、处理点未注册 20023。 */
export function run(jobCode, params = null, options = {}) {
  const body = { jobCode }
  if (params && Object.keys(params).length > 0) {
    body.params = params
  }
  return action(`${JOB_BASE}/Run`, body, options)
}

/** 暂停 cron 调度（幂等：是）：保留 enabled 与手动触发能力。 */
export function pause(jobCode, options = {}) {
  return action(`${JOB_BASE}/Pause`, { jobCode }, options)
}

/** 恢复 cron 调度（幂等：是）：cron 非法 20022、处理点未注册 20023。 */
export function resume(jobCode, options = {}) {
  return action(`${JOB_BASE}/Resume`, { jobCode }, options)
}

/** 运行日志分页（5.2 `/platform/jobRun/GetPage`）。 */
export function getRunPage(runQuery) {
  return query(`${JOB_RUN_BASE}/GetPage`, normalizeJobRunQuery(runQuery))
}

/** 单条运行日志（`{runId}`）：不存在抛 20020。 */
export function getRun(runId) {
  return query(`${JOB_RUN_BASE}/Get`, { runId })
}

/** 重试一次运行（幂等：是）：返回同一个 `runId`；正在执行 20021、已停用 20024。 */
export function retryRun(runId, options = {}) {
  return action(`${JOB_RUN_BASE}/Retry`, { runId }, options)
}

/**
 * 命名空间导出：`job.*` 管任务、`jobRun.*` 管运行日志，调用点一眼看出在改什么。
 */
export const job = Object.freeze({
  getPage,
  get,
  add,
  up,
  del,
  run,
  pause,
  resume,
})

export const jobRun = Object.freeze({
  getPage: getRunPage,
  get: getRun,
  retry: retryRun,
})

/**
 * `Result.code` → 用户可执行提示（唯一分支依据）。未登记的码回落到"后端 message + code + traceId"。
 */
const CODE_HINTS = Object.freeze({
  [IDEMPOTENCY_KEY_MISSING]: '请求缺少幂等键（code=10001），这是前端问题：请重新提交一次',
  [DATA_CONFLICT]: '该记录已被他人修改或任务编码已存在（code=10003）：请刷新后用最新版本重试',
  [FORBIDDEN_CODE]: '无权限（code=10403）：后端按权限点拒绝，请联系管理员分配 platform:job:* 权限',
  [JOB_NOT_FOUND]: '任务或运行记录不存在（code=20020）：可能已被删除，请刷新列表',
  [JOB_RUNNING]: '任务正在执行（code=20021）：未允许并发的任务不能重复触发；可到运行日志看进度，或稍后重试',
  [JOB_CRON_INVALID]: 'Cron 表达式非法（code=20022）：必须是 6 段 Spring 表达式（秒 分 时 日 月 周），不支持 Quartz 的 ? / L / W / #',
  [JOB_HANDLER_NOT_REGISTERED]: '处理点未注册（code=20023）：可执行点只能来自代码里注册的 JobHandler，编码写错或该能力尚未发版',
  [JOB_DISABLED]: '任务已停用（code=20024）：停用后既不会按 cron 触发，也拒绝手动触发；只想临时停 cron 用「暂停」',
  [JOB_TIMEOUT]: '任务执行超时（code=20025）：已中断并进入重试判定；要放宽请调大 timeoutSeconds',
})

/** 写动作失败提示。未登记的码不猜语义。 */
export function describeWriteFailure(error) {
  const hint = CODE_HINTS[error?.code]
  if (hint) {
    return hint
  }
  return `${error?.message ?? '操作失败'}（code=${error?.code ?? 'unknown'}，traceId=${error?.traceId ?? '-'}）`
}

/** 运行状态的中文标签（只用于展示；判定一律看状态串本身）。 */
export const RUN_STATUS_LABELS = Object.freeze({
  RUNNING: '执行中',
  SUCCESS: '成功',
  FAILED: '失败',
  TIMEOUT: '超时',
  RETRYING: '待重投',
  SKIPPED: '已跳过',
})

/** 触发来源的中文标签。 */
export const TRIGGER_TYPE_LABELS = Object.freeze({
  CRON: '定时触发',
  MANUAL: '手动触发',
  RETRY: '失败重试',
})
