<template>
  <el-card shadow="never">
    <template #header>Excel 任务</template>
    <el-input
      v-model="bizType"
      class="excel__biz-type"
      placeholder="已注册的业务类型"
      clearable
    />
    <el-upload
      :auto-upload="false"
      :show-file-list="false"
      accept=".xlsx,.xls"
      @change="handleFile"
    >
      <el-button
        type="primary"
        :disabled="!bizType.trim()"
      >
        选择导入文件
      </el-button>
    </el-upload>
    <el-table
      v-loading="loading"
      :data="rows"
      border
      row-key="taskId"
      class="excel__table"
    >
      <el-table-column
        prop="taskId"
        label="任务号"
        min-width="220"
      />
      <el-table-column
        prop="taskType"
        label="类型"
        width="90"
      />
      <el-table-column
        prop="bizType"
        label="业务类型"
        min-width="140"
      />
      <el-table-column
        prop="status"
        label="状态"
        width="110"
      />
      <el-table-column
        label="进度"
        width="180"
      >
        <template #default="{ row }">
          <el-progress :percentage="row.progressPercent ?? 0" />
        </template>
      </el-table-column>
      <el-table-column
        label="结果"
        width="260"
      >
        <template #default="{ row }">
          <el-button
            v-if="row.resultFileId"
            link
            type="primary"
            @click="downloadResult(row)"
          >
            下载结果
          </el-button>
          <el-button
            v-if="row.errorFileId"
            link
            type="warning"
            @click="downloadErrors(row)"
          >
            下载错误
          </el-button>
          <el-button
            v-if="row.failRows"
            link
            type="warning"
            @click="openErrors(row)"
          >
            错误明细
          </el-button>
          <el-button
            v-if="running(row.status)"
            link
            type="danger"
            @click="cancel(row)"
          >
            取消
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
  <el-drawer
    v-model="errorVisible"
    title="错误明细"
    size="560px"
  >
    <el-table
      v-loading="errorLoading"
      :data="errorRows"
      border
    >
      <el-table-column
        prop="rowNum"
        label="行号"
        width="80"
      />
      <el-table-column
        prop="columnName"
        label="列"
        width="120"
      />
      <el-table-column
        prop="errorMessage"
        label="原因"
        min-width="240"
      />
    </el-table>
    <el-pagination
      v-model:current-page="errorPage"
      layout="prev, pager, next, total"
      :page-size="20"
      :total="errorTotal"
      @current-change="loadErrors"
    />
  </el-drawer>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { excelTask } from '@/api/platform/excel'
import { upload } from '@/api/platform/file'

const rows = ref([])
const loading = ref(false)
const timers = new Map()
const errorVisible = ref(false)
const errorLoading = ref(false)
const errorRows = ref([])
const errorTaskId = ref('')
const errorPage = ref(1)
const errorTotal = ref(0)
const bizType = ref('')

function running(status) {
  return status === 'PENDING' || status === 'RUNNING'
}

async function handleFile(uploadEvent) {
  const file = uploadEvent?.raw
  if (!file) return
  if (!bizType.value.trim()) {
    ElMessage.warning('请先填写已注册的业务类型')
    return
  }
  try {
    const uploaded = await upload(file, { bizType: 'excel.source', bizId: 1 })
    const response = await excelTask.import({ fileId: uploaded.data?.id, bizType: bizType.value.trim() })
    const taskId = response.data?.taskId
    if (taskId) watchTask(taskId)
    ElMessage.success(`已受理：${taskId ?? '-'}`)
  } catch (error) {
    ElMessage.error(error?.message ?? 'Excel 导入失败')
  }
}

async function load() {
  loading.value = true
  try {
    const response = await excelTask.getPage()
    rows.value = response.data?.records ?? []
  } finally {
    loading.value = false
  }
}

async function loadErrors() {
  if (!errorTaskId.value) return
  errorLoading.value = true
  try {
    const response = await excelTask.errors(errorTaskId.value, errorPage.value, 20)
    errorRows.value = response.data?.records ?? []
    errorTotal.value = response.data?.total ?? 0
  } finally {
    errorLoading.value = false
  }
}

async function openErrors(row) {
  errorTaskId.value = row.taskId
  errorPage.value = 1
  errorVisible.value = true
  await loadErrors()
}

function watchTask(taskId) {
  if (timers.has(taskId)) return
  const tick = async () => {
    try {
      const response = await excelTask.get(taskId)
      const task = response.data
      const index = rows.value.findIndex((item) => item.taskId === taskId)
      if (index < 0) rows.value.unshift(task)
      else rows.value[index] = task
      if (!running(task.status)) {
        clearInterval(timers.get(taskId))
        timers.delete(taskId)
      }
    } catch {
      clearInterval(timers.get(taskId))
      timers.delete(taskId)
    }
  }
  timers.set(taskId, setInterval(tick, 2000))
  tick()
}

async function cancel(row) {
  await excelTask.cancel(row.taskId)
  row.status = 'CANCELLED'
}

function saveDownload(result) {
  const url = URL.createObjectURL(result.data)
  const link = document.createElement('a')
  link.href = url
  link.download = result.filename ?? 'excel-result.xlsx'
  link.click()
  URL.revokeObjectURL(url)
}

async function downloadResult(row) { saveDownload(await excelTask.downloadResult(row.taskId)) }
async function downloadErrors(row) { saveDownload(await excelTask.downloadErrors(row.taskId)) }

onMounted(load)
onBeforeUnmount(() => timers.forEach((timer) => clearInterval(timer)))
</script>

<style scoped>
.excel__table { margin-top: 16px; }
</style>
