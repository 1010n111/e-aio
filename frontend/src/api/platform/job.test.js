import { beforeEach, describe, expect, it, vi } from 'vitest'
import axios from 'axios'

import { DATA_CONFLICT, FORBIDDEN_CODE, IDEMPOTENCY_KEY_HEADER, IDEMPOTENCY_KEY_MISSING } from '@/api/codes'
import { newIdempotencyScope } from '@/api/idempotency'
import { http } from '@/api/request'
import router from '@/router'
import {
  CRON_MAX_LENGTH,
  DEFAULT_BACKOFF_SECONDS,
  DEFAULT_RETRY_MAX,
  DEFAULT_TIMEOUT_SECONDS,
  JOB_CRON_INVALID,
  JOB_DISABLED,
  JOB_HANDLER_NOT_REGISTERED,
  JOB_NOT_FOUND,
  JOB_PERMISSIONS,
  JOB_RUNNING,
  JOB_RUN_RETRYABLE,
  JOB_RUN_STATUSES,
  JOB_TIMEOUT,
  JOB_TRIGGER_TYPES,
  RETRY_MAX_LIMIT,
  RUN_STATUS_LABELS,
  describeWriteFailure,
  formatParams,
  job,
  jobRun,
  normalizeJobQuery,
  normalizeJobRunQuery,
  normalizeJobSaveCmd,
  parseParamText,
} from '@/api/platform/job'

/** 捕获请求并把成功返回体喂给 axios 全链路（不启 HTTP 服务），写法同 dict.test.js。 */
function stubServer(data) {
  const captured = []
  http.defaults.adapter = async (config) => {
    captured.push(config)
    return {
      data: { code: 0, message: '成功', data, traceId: 't-job' },
      status: 200,
      statusText: 'OK',
      headers: new axios.AxiosHeaders(),
      config,
    }
  }
  return captured
}

describe('任务查询条件整形（P1 册 5.3）', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('未填的条件不进请求体：空串/空白串/null 一律省略，enabled 只在 true/false 时下发', () => {
    expect(normalizeJobQuery({ jobCode: '  ', enabled: null })).toEqual({ pageNum: 1, pageSize: 20 })
    expect(normalizeJobQuery({ jobCode: ' platform. ', enabled: false })).toEqual({
      jobCode: 'platform.',
      enabled: false,
      pageNum: 1,
      pageSize: 20,
    })
  })

  it('运行日志查询：状态只在白名单内下发，非法状态被丢掉而不是透传给后端', () => {
    expect(normalizeJobRunQuery({ jobCode: 'a', status: 'RETRYING' })).toMatchObject({
      jobCode: 'a',
      status: 'RETRYING',
    })
    expect(normalizeJobRunQuery({ status: 'PAUSED' })).not.toHaveProperty('status')
  })

  it('分页被限制在 1–200（P1 册 5.3）', () => {
    expect(normalizeJobQuery({ pageNum: 0, pageSize: 9999 })).toMatchObject({ pageNum: 1, pageSize: 200 })
    expect(normalizeJobRunQuery({ pageNum: -3, pageSize: 0 })).toMatchObject({ pageNum: 1, pageSize: 20 })
  })
})

describe('参数文本 → params 对象（纯逻辑）', () => {
  it('空文本 = null（后端语义：用任务保存的参数）', () => {
    expect(parseParamText('')).toBeNull()
    expect(parseParamText('   ')).toBeNull()
    expect(parseParamText(undefined)).toBeNull()
  })

  it('值统一转成字符串（后端 params 是 Map<String,String>）', () => {
    expect(parseParamText('{"days":90,"force":true}')).toEqual({ days: '90', force: 'true' })
  })

  it('坏 JSON 与"不是对象"的 JSON 都抛错，不静默传空参数', () => {
    expect(() => parseParamText('{oops}')).toThrow(/合法 JSON/)
    expect(() => parseParamText('[1,2]')).toThrow(/JSON 对象/)
    expect(() => parseParamText('"text"')).toThrow(/JSON 对象/)
  })

  it('formatParams 与 parseParamText 往返一致（空参数给空文本）', () => {
    expect(formatParams(null)).toBe('')
    expect(formatParams({})).toBe('')
    expect(parseParamText(formatParams({ days: '90' }))).toEqual({ days: '90' })
  })
})

describe('JobSaveCmd 组装（P1 册 5.3）', () => {
  it('只下发契约字段：界面状态不泄漏进请求体', () => {
    const body = normalizeJobSaveCmd({
      jobCode: ' job.x ',
      jobName: ' 名称 ',
      handlerCode: ' h ',
      cron: ' 0 0 3 * * * ',
      params: '{"days":"90"}',
      remark: ' 备注 ',
      id: 9,
      scheduled: true,
      running: true,
      version: 4,
    })
    expect(Object.keys(body).sort()).toEqual(
      [
        'allowConcurrent',
        'backoffSeconds',
        'cron',
        'enabled',
        'handlerCode',
        'jobCode',
        'jobName',
        'params',
        'remark',
        'retryMax',
        'timeoutSeconds',
        'version',
      ].sort(),
    )
    expect(body.jobCode).toBe('job.x')
    expect(body.cron).toBe('0 0 3 * * *')
    expect(body.params).toEqual({ days: '90' })
    expect(body.remark).toBe('备注')
    expect(body.version).toBe(4)
  })

  it('缺省值按 7.2/5.3：超时 300、重投 3、退避 30、不允许并发、启用', () => {
    const body = normalizeJobSaveCmd({ jobCode: 'j', jobName: 'n', handlerCode: 'h', cron: '0 0 3 * * *' })
    expect(body.timeoutSeconds).toBe(DEFAULT_TIMEOUT_SECONDS)
    expect(body.retryMax).toBe(DEFAULT_RETRY_MAX)
    expect(body.backoffSeconds).toBe(DEFAULT_BACKOFF_SECONDS)
    expect(body.allowConcurrent).toBe(false)
    expect(body.enabled).toBe(true)
    expect(body.version).toBeNull()
  })

  it('重投上限夹在 0–10（DDL 的 CHECK）而不是把非法值丢给数据库', () => {
    expect(normalizeJobSaveCmd({ retryMax: 99 }).retryMax).toBe(RETRY_MAX_LIMIT)
    expect(normalizeJobSaveCmd({ retryMax: -5 }).retryMax).toBe(0)
    expect(normalizeJobSaveCmd({ backoffSeconds: -1 }).backoffSeconds).toBe(0)
  })

  it('enabled=false 会被保留（停用语义不能被缺省值吞掉）', () => {
    expect(normalizeJobSaveCmd({ enabled: false }).enabled).toBe(false)
  })
})

describe('11 个端点的路径、载荷与幂等键（P1 册 5.2、5.6）', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('读动作走 POST 且不带幂等键；路径省略 /api（baseURL 已带）', async () => {
    const captured = stubServer({ total: 0, pageNum: 1, pageSize: 20, records: [] })

    await job.getPage({ jobCode: 'platform.' })
    await job.get('platform.job.log.clean')
    await jobRun.getPage({ jobCode: 'platform.job.log.clean' })
    await jobRun.get(7)

    expect(captured.map((config) => config.url)).toEqual([
      '/platform/job/GetPage',
      '/platform/job/Get',
      '/platform/jobRun/GetPage',
      '/platform/jobRun/Get',
    ])
    expect(captured.every((config) => config.method === 'post')).toBe(true)
    expect(captured.every((config) => config.baseURL === '/api')).toBe(true)
    expect(captured.every((config) => config.headers[IDEMPOTENCY_KEY_HEADER] === undefined)).toBe(true)
    expect(JSON.parse(captured[1].data)).toEqual({ jobCode: 'platform.job.log.clean' })
    expect(JSON.parse(captured[3].data)).toEqual({ runId: 7 })
  })

  it('写动作全部带幂等键，路径与载荷逐条对应 5.2', async () => {
    const captured = stubServer({})

    await job.add({ jobCode: 'j', jobName: 'n', handlerCode: 'h', cron: '0 0 3 * * *' }, { idempotencyKey: 'k1' })
    await job.up({ jobCode: 'j', jobName: 'n2', handlerCode: 'h', cron: '0 0 4 * * *', version: 1 }, { idempotencyKey: 'k2' })
    await job.del('j', 2, { idempotencyKey: 'k3' })
    await job.run('j', { days: '90' }, { idempotencyKey: 'k4' })
    await job.pause('j', { idempotencyKey: 'k5' })
    await job.resume('j', { idempotencyKey: 'k6' })
    await jobRun.retry(9, { idempotencyKey: 'k7' })

    expect(captured.map((config) => config.url)).toEqual([
      '/platform/job/Add',
      '/platform/job/Up',
      '/platform/job/Del',
      '/platform/job/Run',
      '/platform/job/Pause',
      '/platform/job/Resume',
      '/platform/jobRun/Retry',
    ])
    expect(captured.map((config) => config.headers[IDEMPOTENCY_KEY_HEADER])).toEqual([
      'k1', 'k2', 'k3', 'k4', 'k5', 'k6', 'k7',
    ])
    expect(JSON.parse(captured[2].data)).toEqual({ jobCode: 'j', version: 2 })
    expect(JSON.parse(captured[3].data)).toEqual({ jobCode: 'j', params: { days: '90' } })
    expect(JSON.parse(captured[4].data)).toEqual({ jobCode: 'j' })
    expect(JSON.parse(captured[6].data)).toEqual({ runId: 9 })
  })

  it('手动触发不带参数时不塞空 params（后端会回落到任务保存的参数）', async () => {
    const captured = stubServer({ runId: 1 })

    await job.run('j', null, { idempotencyKey: 'k8' })

    expect(JSON.parse(captured[0].data)).toEqual({ jobCode: 'j' })
  })

  it('未显式给键时请求层自动补一个（等价于"调用即一次新动作"）', async () => {
    const captured = stubServer({})

    await job.pause('j')

    expect(captured[0].headers[IDEMPOTENCY_KEY_HEADER]).toMatch(/^[0-9a-f-]{36}$/)
  })

  it('成功时解包 data：分页记录可直接喂给列表，触发返回 runId', async () => {
    stubServer({ total: 1, pageNum: 1, pageSize: 20, records: [{ jobCode: 'j' }] })

    const result = await job.getPage({})

    expect(result.data.records).toEqual([{ jobCode: 'j' }])
    expect(result.traceId).toBe('t-job')
  })
})

describe('幂等键：同一次提交复用、内容变了换新（P1 册 5.6）', () => {
  it('载荷相同（用户点重试）复用同一个键，内容变了换新键', () => {
    const scope = newIdempotencyScope()
    const first = scope.next({ jobCode: 'j', version: 1 })

    expect(scope.next({ jobCode: 'j', version: 1 }).key).toBe(first.key)

    const changed = scope.next({ jobCode: 'j', version: 2 })
    expect(changed.key).not.toBe(first.key)
    expect(scope.next({ jobCode: 'j', version: 2 }).key).toBe(changed.key)

    scope.reset()
    expect(scope.next({ jobCode: 'j', version: 2 }).key).not.toBe(changed.key)
  })
})

describe('Result.code → 用户提示（唯一分支依据，不做中文匹配）', () => {
  it('job 段业务码按码分支到各自文案', () => {
    expect(describeWriteFailure({ code: JOB_NOT_FOUND })).toContain('20020')
    expect(describeWriteFailure({ code: JOB_RUNNING })).toContain('20021')
    expect(describeWriteFailure({ code: JOB_CRON_INVALID })).toContain('20022')
    expect(describeWriteFailure({ code: JOB_HANDLER_NOT_REGISTERED })).toContain('20023')
    expect(describeWriteFailure({ code: JOB_DISABLED })).toContain('20024')
    expect(describeWriteFailure({ code: JOB_TIMEOUT })).toContain('20025')
  })

  it('提示是可执行的：cron 提示给出方言边界、未注册提示指出可执行点只能来自代码', () => {
    expect(describeWriteFailure({ code: JOB_CRON_INVALID })).toContain('6 段')
    expect(describeWriteFailure({ code: JOB_HANDLER_NOT_REGISTERED })).toContain('JobHandler')
    expect(describeWriteFailure({ code: JOB_DISABLED })).toContain('暂停')
  })

  it('幂等键/乐观锁/无权限与未登记的码：前者按码给文案，后者回落后端 message + code + traceId', () => {
    expect(describeWriteFailure({ code: IDEMPOTENCY_KEY_MISSING })).toContain('10001')
    expect(describeWriteFailure({ code: DATA_CONFLICT })).toContain('10003')
    expect(describeWriteFailure({ code: FORBIDDEN_CODE })).toContain('10403')
    expect(describeWriteFailure({ code: 99999, message: '后端说', traceId: 't-9' })).toBe(
      '后端说（code=99999，traceId=t-9）',
    )
    expect(describeWriteFailure(undefined)).toContain('code=unknown')
  })

  it('每个登记码都命中登记文案、不落兜底（常量 import 错名会让提示悄悄退化）', () => {
    const registered = [
      IDEMPOTENCY_KEY_MISSING,
      DATA_CONFLICT,
      FORBIDDEN_CODE,
      JOB_NOT_FOUND,
      JOB_RUNNING,
      JOB_CRON_INVALID,
      JOB_HANDLER_NOT_REGISTERED,
      JOB_DISABLED,
      JOB_TIMEOUT,
    ]

    expect(new Set(registered).size).toBe(registered.length)
    for (const code of registered) {
      const hint = describeWriteFailure({ code, message: '后端 message', traceId: 't-x' })
      expect(hint, `码 ${code} 应命中登记文案`).not.toContain('traceId')
      expect(hint).not.toContain('后端 message')
    }
  })
})

describe('权限点、状态集合与路由（P1 册 7.3、3.10.2）', () => {
  it('权限点与后端 @PreAuthorize 逐字一致（Pause/Resume 也用 platform:job:run）', () => {
    expect(JOB_PERMISSIONS).toEqual({
      list: 'platform:job:list',
      get: 'platform:job:get',
      add: 'platform:job:add',
      up: 'platform:job:up',
      del: 'platform:job:del',
      run: 'platform:job:run',
    })
  })

  it('状态与触发来源集合与后端枚举/DDL 的 CHECK 一致', () => {
    expect(JOB_RUN_STATUSES).toEqual(['RUNNING', 'SUCCESS', 'FAILED', 'TIMEOUT', 'RETRYING', 'SKIPPED'])
    expect(JOB_TRIGGER_TYPES).toEqual(['CRON', 'MANUAL', 'RETRY'])
    expect(JOB_RUN_RETRYABLE).not.toContain('RUNNING')
    expect(Object.keys(RUN_STATUS_LABELS).sort()).toEqual([...JOB_RUN_STATUSES].sort())
    expect(CRON_MAX_LENGTH).toBe(64)
  })

  it('任务页路由已登记且声明 platform:job:list（按钮级隐藏留待 iam 的 v-hasPermi）', () => {
    const route = router.getRoutes().find((item) => item.path === '/platform/job')

    expect(route).toBeDefined()
    expect(route.meta.permission).toBe('platform:job:list')
    expect(route.meta.title).toBe('定时任务')
    expect(typeof route.components.default).toBe('function')
  })
})
