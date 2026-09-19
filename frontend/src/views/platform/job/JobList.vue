<template>
  <el-card shadow="never">
    <template #header>
      <div class="job__header">
        <span>定时任务</span>
        <span class="job__hint">
          可执行点只能来自代码里注册的处理点：数据库只决定开关、参数、超时与重试。改一行 handler 编码不会执行任何东西。
        </span>
      </div>
    </template>

    <el-form
      inline
      :model="query"
      @submit.prevent
    >
      <el-form-item label="任务编码">
        <el-input
          v-model="query.jobCode"
          placeholder="模糊匹配"
          clearable
        />
      </el-form-item>
      <el-form-item label="状态">
        <el-select
          v-model="query.enabled"
          placeholder="全部"
          clearable
          class="job__status"
        >
          <el-option
            label="启用"
            :value="true"
          />
          <el-option
            label="停用"
            :value="false"
          />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button
          type="primary"
          :loading="loading"
          @click="handleQuery"
        >
          查询
        </el-button>
        <el-button
          :disabled="loading"
          @click="handleReset"
        >
          重置
        </el-button>
        <el-button
          type="primary"
          plain
          :disabled="loading"
          @click="openAdd"
        >
          新增任务
        </el-button>
      </el-form-item>
    </el-form>

    <el-table
      v-loading="loading"
      :data="rows"
      border
      row-key="id"
      empty-text="没有匹配的任务"
    >
      <el-table-column
        prop="jobCode"
        label="任务编码"
        min-width="220"
        show-overflow-tooltip
      />
      <el-table-column
        prop="jobName"
        label="名称"
        min-width="140"
        show-overflow-tooltip
      />
      <el-table-column
        prop="handlerCode"
        label="处理点"
        min-width="200"
        show-overflow-tooltip
      />
      <el-table-column
        prop="cron"
        label="cron（6 段）"
        min-width="140"
      />
      <el-table-column
        label="启用"
        width="90"
      >
        <template #default="{ row }">
          <el-switch
            :model-value="row.enabled"
            :loading="toggling === row.jobCode"
            @change="(value) => handleToggle(row, value)"
          />
        </template>
      </el-table-column>
      <el-table-column
        label="调度/运行"
        width="150"
      >
        <template #default="{ row }">
          <el-tag
            size="small"
            :type="row.scheduled ? 'success' : 'info'"
          >
            {{ row.scheduled ? '已排期' : '未排期' }}
          </el-tag>
          <el-tag
            v-if="row.running"
            size="small"
            type="warning"
            class="job__running"
          >
            执行中
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column
        label="上次结果"
        width="150"
      >
        <template #default="{ row }">
          <el-tag
            v-if="row.lastStatus"
            size="small"
            :type="statusTagType(row.lastStatus)"
          >
            {{ statusLabel(row.lastStatus) }}
          </el-tag>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column
        label="上次执行"
        width="170"
      >
        <template #default="{ row }">{{ formatTime(row.lastRunTime) }}</template>
      </el-table-column>
      <el-table-column
        label="下次触发"
        width="170"
      >
        <template #default="{ row }">{{ formatTime(row.nextRunTime) }}</template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="330"
        fixed="right"
      >
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            :loading="triggering === row.jobCode"
            @click="openTrigger(row)"
          >
            触发
          </el-button>
          <el-button
            link
            type="primary"
            @click="openEdit(row)"
          >
            编辑
          </el-button>
          <el-button
            link
            :disabled="!row.enabled"
            @click="handleToggleSchedule(row)"
          >
            {{ row.scheduled ? '暂停' : '恢复' }}
          </el-button>
          <el-button
            link
            @click="openRuns(row)"
          >
            运行日志
          </el-button>
          <el-button
            link
            type="danger"
            :disabled="row.running"
            @click="handleDelete(row)"
          >
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="page.pageNum"
      v-model:page-size="page.pageSize"
      class="job__pager"
      :total="page.total"
      :page-sizes="[10, 20, 50, 100]"
      layout="total, sizes, prev, pager, next"
      @current-change="load"
      @size-change="load"
    />

    <!-- 新增/编辑 -->
    <el-dialog
      v-model="editor.visible"
      :title="editor.title"
      width="720px"
    >
      <el-form
        ref="editorRef"
        :model="editor.form"
        :rules="RULES"
        label-width="140px"
      >
        <el-form-item
          label="任务编码"
          prop="jobCode"
        >
          <el-input
            v-model="editor.form.jobCode"
            :disabled="editor.mode === 'edit'"
            placeholder="跨模块唯一，例如 crm.customer.sync"
            :maxlength="JOB_CODE_MAX_LENGTH"
          />
          <div class="job__tip">创建后不可修改（改编码 = 删了重建）；种子任务用 platform.* 前缀。</div>
        </el-form-item>
        <el-form-item
          label="任务名称"
          prop="jobName"
        >
          <el-input
            v-model="editor.form.jobName"
            :maxlength="JOB_NAME_MAX_LENGTH"
          />
        </el-form-item>
        <el-form-item
          label="处理点编码"
          prop="handlerCode"
        >
          <el-input
            v-model="editor.form.handlerCode"
            placeholder="必须命中代码里注册的 JobHandler，否则 20023"
            :maxlength="HANDLER_CODE_MAX_LENGTH"
          />
        </el-form-item>
        <el-form-item
          label="cron（6 段）"
          prop="cron"
        >
          <el-input
            v-model="editor.form.cron"
            placeholder="0 0 3 * * *"
            :maxlength="CRON_MAX_LENGTH"
          />
          <div class="job__tip">
            秒 分 时 日 月 周；不支持 Quartz 的 ? / L / W / #（非法报 20022）。例：每天 03:00 = 0 0 3 * * *，每 30 秒 = */30 * * * * *
          </div>
        </el-form-item>
        <el-form-item label="任务参数">
          <el-input
            v-model="editor.form.params"
            type="textarea"
            :rows="3"
            placeholder="JSON 对象，例如 {&quot;days&quot;:&quot;90&quot;}；留空 = 无参数"
          />
          <div class="job__tip">键名含 password/secret/token/key 会被拒绝（避免密钥进管理页与运维导出）。</div>
        </el-form-item>
        <el-form-item label="超时（秒）">
          <el-input-number
            v-model="editor.form.timeoutSeconds"
            :min="1"
            :max="86400"
          />
        </el-form-item>
        <el-form-item label="重投上限">
          <el-input-number
            v-model="editor.form.retryMax"
            :min="0"
            :max="RETRY_MAX_LIMIT"
          />
          <span class="job__tip">0 = 不重投；退避 = backoff × 2^(attempt-1)，封顶 30 分钟</span>
        </el-form-item>
        <el-form-item label="退避基数（秒）">
          <el-input-number
            v-model="editor.form.backoffSeconds"
            :min="0"
            :max="1800"
          />
        </el-form-item>
        <el-form-item label="允许并发">
          <el-switch v-model="editor.form.allowConcurrent" />
          <span class="job__tip">默认不允许：上一次还在跑时，cron 触发记「已跳过」、手动触发报 20021</span>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="editor.form.enabled" />
          <span class="job__tip">停用 = 既不按 cron 触发，也拒绝手动触发（20024）</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="editor.form.remark"
            :maxlength="REMARK_MAX_LENGTH"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editor.visible = false">
          取消
        </el-button>
        <el-button
          type="primary"
          :loading="saving"
          @click="handleSave"
        >
          保存
        </el-button>
      </template>
    </el-dialog>

    <!-- 手动触发 -->
    <el-dialog
      v-model="trigger.visible"
      title="手动触发"
      width="560px"
    >
      <el-form label-width="100px">
        <el-form-item label="任务">
          <span>{{ trigger.jobCode }}</span>
        </el-form-item>
        <el-form-item label="本次参数">
          <el-input
            v-model="trigger.params"
            type="textarea"
            :rows="3"
            placeholder="留空 = 用任务保存的参数，例如 {&quot;days&quot;:&quot;90&quot;}"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="trigger.visible = false">
          取消
        </el-button>
        <el-button
          type="primary"
          :loading="triggering === trigger.jobCode"
          @click="handleTrigger"
        >
          触发
        </el-button>
      </template>
    </el-dialog>

    <!-- 运行日志（抽屉里复用 JobRunList 组件） -->
    <el-drawer
      v-model="runs.visible"
      :title="`运行日志：${runs.jobCode}`"
      size="70%"
      @open="loadRuns"
    >
      <JobRunList
        ref="runListRef"
        :job-code="runs.jobCode"
      />
    </el-drawer>
  </el-card>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { newIdempotencyScope } from '@/api/idempotency'
import {
  CRON_MAX_LENGTH,
  DEFAULT_BACKOFF_SECONDS,
  DEFAULT_RETRY_MAX,
  DEFAULT_TIMEOUT_SECONDS,
  HANDLER_CODE_MAX_LENGTH,
  JOB_CODE_MAX_LENGTH,
  JOB_NAME_MAX_LENGTH,
  REMARK_MAX_LENGTH,
  RETRY_MAX_LIMIT,
  RUN_STATUS_LABELS,
  describeWriteFailure,
  formatParams,
  job,
  parseParamText,
} from '@/api/platform/job'
import JobRunList from '@/views/platform/job/JobRunList.vue'

/**
 * 任务管理页（P1 册 3.10.2 的 `/platform/job`）：启停、手动触发、暂停/恢复、编辑与查看运行日志。
 *
 * 三条口径：
 * - **按钮不做前端隐藏**（3.10.2 的临时态）：权限点由路由 `meta.permission` 声明、后端 `@PreAuthorize`
 *   兜底；`v-hasPermi` 由 iam 阶段提供；
 * - **错误只按 `Result.code` 分支**（api-conventions），文案不做判定；
 * - **写动作每个用户动作一个幂等键**：同一次提交重试复用、内容变了换新（`newIdempotencyScope`）。
 *
 * 「重试」不在这里：重试针对**一次运行**，入口在运行日志里（`JobRunList.vue`）。
 */
const rows = ref([])
const loading = ref(false)
const saving = ref(false)
const toggling = ref('')
const triggering = ref('')

const query = reactive({ jobCode: '', enabled: null })
const page = reactive({ pageNum: 1, pageSize: 20, total: 0 })

const editorRef = ref(null)
const runListRef = ref(null)
const editor = reactive({ visible: false, mode: 'add', title: '新增任务', form: emptyForm() })
const trigger = reactive({ visible: false, jobCode: '', params: '' })
const runs = reactive({ visible: false, jobCode: '' })

/** 同一次提交的幂等键（重试复用、内容变了换新）——只活在内存里。 */
const submitKey = newIdempotencyScope()

const RULES = {
  jobCode: [
    { required: true, message: '任务编码必填', trigger: 'blur' },
    { max: JOB_CODE_MAX_LENGTH, message: `任务编码不超过 ${JOB_CODE_MAX_LENGTH} 字符`, trigger: 'blur' },
  ],
  jobName: [{ required: true, message: '任务名称必填', trigger: 'blur' }],
  handlerCode: [{ required: true, message: '处理点编码必填', trigger: 'blur' }],
  cron: [
    { required: true, message: 'cron 必填', trigger: 'blur' },
    { max: CRON_MAX_LENGTH, message: `cron 不超过 ${CRON_MAX_LENGTH} 字符`, trigger: 'blur' },
  ],
}

function emptyForm() {
  return {
    jobCode: '',
    jobName: '',
    handlerCode: '',
    cron: '0 0 3 * * *',
    params: '',
    enabled: true,
    timeoutSeconds: DEFAULT_TIMEOUT_SECONDS,
    retryMax: DEFAULT_RETRY_MAX,
    backoffSeconds: DEFAULT_BACKOFF_SECONDS,
    allowConcurrent: false,
    remark: '',
    version: null,
  }
}

function statusLabel(status) {
  return RUN_STATUS_LABELS[status] ?? status ?? '-'
}

function statusTagType(status) {
  if (status === 'SUCCESS') {
    return 'success'
  }
  if (status === 'RUNNING') {
    return 'warning'
  }
  if (status === 'RETRYING') {
    return 'info'
  }
  return 'danger'
}

/** 时刻展示：后端给 ISO-8601（UTC）；这里只做可读化，不做时区换算（展示时区见参数 platform.time.display-zone）。 */
function formatTime(value) {
  if (!value) {
    return '-'
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString()
}

function describeFailure(error) {
  return `${error?.message ?? '操作失败'}（code=${error?.code ?? 'unknown'}，traceId=${error?.traceId ?? '-'}）`
}

async function load() {
  loading.value = true
  try {
    const { data } = await job.getPage({
      jobCode: query.jobCode,
      enabled: query.enabled,
      pageNum: page.pageNum,
      pageSize: page.pageSize,
    })
    rows.value = data?.records ?? []
    page.total = data?.total ?? 0
  } catch (error) {
    ElMessage.error(describeFailure(error))
  } finally {
    loading.value = false
  }
}

function handleQuery() {
  page.pageNum = 1
  return load()
}

function handleReset() {
  query.jobCode = ''
  query.enabled = null
  return handleQuery()
}

function openAdd() {
  editor.mode = 'add'
  editor.title = '新增任务'
  editor.form = emptyForm()
  editor.visible = true
}

function openEdit(row) {
  editor.mode = 'edit'
  editor.title = `编辑任务：${row.jobCode}`
  editor.form = {
    jobCode: row.jobCode,
    jobName: row.jobName,
    handlerCode: row.handlerCode,
    cron: row.cron,
    params: formatParams(row.params),
    enabled: row.enabled,
    timeoutSeconds: row.timeoutSeconds,
    retryMax: row.retryMax,
    backoffSeconds: row.backoffSeconds,
    allowConcurrent: row.allowConcurrent,
    remark: row.remark ?? '',
    version: row.version,
  }
  editor.visible = true
}

async function handleSave() {
  const valid = await editorRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  let payload
  try {
    payload = { ...editor.form, params: parseParamText(editor.form.params) }
  } catch (error) {
    ElMessage.error(error.message)
    return
  }
  saving.value = true
  try {
    const options = { idempotencyKey: submitKey.next(payload).key }
    if (editor.mode === 'add') {
      await job.add(payload, options)
      ElMessage.success('任务已新增')
    } else {
      await job.up(payload, options)
      ElMessage.success('任务已更新')
    }
    submitKey.reset()
    editor.visible = false
    await load()
  } catch (error) {
    ElMessage.error(describeWriteFailure(error))
  } finally {
    saving.value = false
  }
}

/** 启停 = 改 `enabled`（Up），不是暂停：停用后手动触发也会被拒（20024）。 */
async function handleToggle(row, value) {
  const payload = { ...row, enabled: value, params: row.params ?? null }
  toggling.value = row.jobCode
  try {
    await job.up(payload, { idempotencyKey: submitKey.next(payload).key })
    ElMessage.success(value ? '任务已启用' : '任务已停用（手动触发同时被拒）')
    await load()
  } catch (error) {
    ElMessage.error(describeWriteFailure(error))
  } finally {
    toggling.value = ''
  }
}

/** 暂停/恢复：只动 cron 调度，不动 `enabled`（3.4.6）。 */
async function handleToggleSchedule(row) {
  try {
    if (row.scheduled) {
      await job.pause(row.jobCode)
      ElMessage.success('已暂停 cron 调度（仍可手动触发）')
    } else {
      await job.resume(row.jobCode)
      ElMessage.success('已恢复 cron 调度')
    }
    await load()
  } catch (error) {
    ElMessage.error(describeWriteFailure(error))
  }
}

function openTrigger(row) {
  trigger.jobCode = row.jobCode
  trigger.params = ''
  trigger.visible = true
}

async function handleTrigger() {
  let params
  try {
    params = parseParamText(trigger.params)
  } catch (error) {
    ElMessage.error(error.message)
    return
  }
  triggering.value = trigger.jobCode
  try {
    const { data } = await job.run(trigger.jobCode, params)
    ElMessage.success(`已受理，运行 ID：${data?.runId ?? '-'}（执行是异步的，可在运行日志里看结果）`)
    trigger.visible = false
  } catch (error) {
    ElMessage.error(describeWriteFailure(error))
  } finally {
    triggering.value = ''
    await load()
  }
}

function openRuns(row) {
  runs.jobCode = row.jobCode
  runs.visible = true
}

function loadRuns() {
  return runListRef.value?.reload()
}

async function handleDelete(row) {
  const confirmed = await ElMessageBox.confirm(
    `删除任务「${row.jobCode}」？（逻辑删除；正在执行的任务不能删）`,
    '确认删除',
    { type: 'warning' },
  ).catch(() => false)
  if (!confirmed) {
    return
  }
  try {
    await job.del(row.jobCode, row.version)
    ElMessage.success('任务已删除')
    await load()
  } catch (error) {
    ElMessage.error(describeWriteFailure(error))
  }
}

load()
</script>

<style scoped>
.job__header {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.job__hint,
.job__tip {
  color: #909399;
  font-size: 12px;
}

.job__status {
  width: 120px;
}

.job__pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.job__running {
  margin-left: 4px;
}
</style>
