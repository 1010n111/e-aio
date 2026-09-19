<template>
  <div class="run">
    <el-form
      inline
      :model="query"
      @submit.prevent
    >
      <el-form-item label="任务编码">
        <el-input
          v-model="query.jobCode"
          placeholder="精确匹配"
          clearable
          :disabled="Boolean(fixedJobCode)"
        />
      </el-form-item>
      <el-form-item label="状态">
        <el-select
          v-model="query.status"
          placeholder="全部"
          clearable
          class="run__status"
        >
          <el-option
            v-for="status in JOB_RUN_STATUSES"
            :key="status"
            :label="statusLabel(status)"
            :value="status"
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
      </el-form-item>
    </el-form>

    <el-table
      v-loading="loading"
      :data="rows"
      border
      row-key="id"
      empty-text="没有匹配的运行记录"
    >
      <el-table-column
        prop="id"
        label="运行 ID"
        width="200"
      />
      <el-table-column
        prop="jobCode"
        label="任务编码"
        min-width="200"
        show-overflow-tooltip
      />
      <el-table-column
        label="触发来源"
        width="110"
      >
        <template #default="{ row }">{{ triggerLabel(row.triggerType) }}</template>
      </el-table-column>
      <el-table-column
        label="状态"
        width="100"
      >
        <template #default="{ row }">
          <el-tag
            size="small"
            :type="statusTagType(row.status)"
          >
            {{ statusLabel(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column
        label="尝试"
        width="70"
      >
        <template #default="{ row }">第 {{ row.attempt }} 次</template>
      </el-table-column>
      <el-table-column
        label="开始"
        width="170"
      >
        <template #default="{ row }">{{ formatTime(row.startTime) }}</template>
      </el-table-column>
      <el-table-column
        label="耗时"
        width="100"
      >
        <template #default="{ row }">{{ row.durationMs == null ? '-' : `${row.durationMs} ms` }}</template>
      </el-table-column>
      <el-table-column
        label="下次重投"
        width="170"
      >
        <template #default="{ row }">{{ formatTime(row.nextRetryTime) }}</template>
      </el-table-column>
      <el-table-column
        prop="errorMessage"
        label="错误摘要"
        min-width="220"
        show-overflow-tooltip
      />
      <el-table-column
        label="操作"
        width="110"
        fixed="right"
      >
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            :loading="retrying === row.id"
            :disabled="row.status === 'RUNNING'"
            @click="handleRetry(row)"
          >
            重试
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="page.pageNum"
      v-model:page-size="page.pageSize"
      class="run__pager"
      :total="page.total"
      :page-sizes="[10, 20, 50, 100]"
      layout="total, sizes, prev, pager, next"
      @current-change="load"
      @size-change="load"
    />
  </div>
</template>

<script setup>
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import { newIdempotencyScope } from '@/api/idempotency'
import {
  JOB_RUN_STATUSES,
  RUN_STATUS_LABELS,
  TRIGGER_TYPE_LABELS,
  describeWriteFailure,
  jobRun,
} from '@/api/platform/job'

/**
 * 运行日志面板（P1 册 3.10.2 的 `views/platform/job/JobRunList.vue`）。
 *
 * 两种用法：任务页的抽屉里带 `jobCode` 看单个任务的日志；不带 `jobCode` 就是全局日志（谁失败得多一眼看出）。
 * 重试的入口在这里而不在任务页：**重试针对一次运行**（复用同一条 `job_run`，runId 不变），
 * 而"再跑一遍这个任务"用任务页的手动触发。
 */
const props = defineProps({
  jobCode: {
    type: String,
    default: '',
  },
})

const rows = ref([])
const loading = ref(false)
const retrying = ref(null)

const query = reactive({ jobCode: props.jobCode, status: null })
const page = reactive({ pageNum: 1, pageSize: 20, total: 0 })

/** 同一次提交的幂等键（重试复用、换了运行记录就换新）。 */
const submitKey = newIdempotencyScope()

const fixedJobCode = ref(props.jobCode)

watch(
  () => props.jobCode,
  (value) => {
    fixedJobCode.value = value
    query.jobCode = value
    handleQuery()
  },
)

function statusLabel(status) {
  return RUN_STATUS_LABELS[status] ?? status ?? '-'
}

function triggerLabel(triggerType) {
  return TRIGGER_TYPE_LABELS[triggerType] ?? triggerType ?? '-'
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

function formatTime(value) {
  if (!value) {
    return '-'
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString()
}

function describeFailure(error) {
  return `${error?.message ?? '查询失败'}（code=${error?.code ?? 'unknown'}，traceId=${error?.traceId ?? '-'}）`
}

async function load() {
  loading.value = true
  try {
    const { data } = await jobRun.getPage({
      jobCode: query.jobCode,
      status: query.status,
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
  query.jobCode = fixedJobCode.value
  query.status = null
  return handleQuery()
}

/**
 * 重试一次运行（复用同一条 `job_run`：`attempt + 1`，返回同一个 runId）。
 *
 * 正在执行的行后端会拒（20021），这里也先禁用按钮——两层都拦，避免"点了没反应"。
 */
async function handleRetry(row) {
  retrying.value = row.id
  try {
    const { data } = await jobRun.retry(row.id, { idempotencyKey: submitKey.next({ runId: row.id }).key })
    ElMessage.success(`已受理重试，运行 ID：${data?.runId ?? row.id}（执行是异步的，稍后刷新看结果）`)
    submitKey.reset()
    await load()
  } catch (error) {
    ElMessage.error(describeWriteFailure(error))
  } finally {
    retrying.value = null
  }
}

/** 给父组件（任务页抽屉）在打开时主动刷新用。 */
defineExpose({ reload: load })

load()
</script>

<style scoped>
.run__status {
  width: 130px;
}

.run__pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
