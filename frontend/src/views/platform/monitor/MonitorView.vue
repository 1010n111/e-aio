<template>
  <div class="monitor-page">
    <div class="monitor__toolbar">
      <span>监测与告警</span>
      <el-button
        type="primary"
        :loading="loading"
        @click="load"
      >
        刷新
      </el-button>
    </div>

    <el-alert
      v-if="metricError"
      type="warning"
      :closable="false"
      :title="metricError"
      class="monitor__alert"
    />

    <el-card shadow="never">
      <template #header>健康状态</template>
      <el-table
        :data="healthRows"
        border
        size="small"
      >
        <el-table-column
          prop="name"
          label="组件"
          width="180"
        />
        <el-table-column
          prop="status"
          label="状态"
          width="120"
        />
      </el-table>
    </el-card>

    <el-card shadow="never">
      <template #header>指标快照</template>
      <el-descriptions
        v-if="snapshot"
        :column="3"
        border
      >
        <el-descriptions-item label="堆内存">{{ bytes(snapshot.jvm?.heapUsedBytes) }}</el-descriptions-item>
        <el-descriptions-item label="线程">{{ snapshot.threads?.active ?? 0 }}</el-descriptions-item>
        <el-descriptions-item label="Redis">{{ snapshot.redis?.status ?? 'UNKNOWN' }}</el-descriptions-item>
        <el-descriptions-item label="24小时任务失败">{{ snapshot.jobs?.failures24h ?? 0 }}</el-descriptions-item>
        <el-descriptions-item label="死信数">{{ snapshot.events?.deadLetters ?? 0 }}</el-descriptions-item>
        <el-descriptions-item label="未解决告警">{{ snapshot.alerts?.open ?? 0 }}</el-descriptions-item>
      </el-descriptions>
      <el-empty
        v-else
        description="暂无指标"
      />
    </el-card>

    <el-card shadow="never">
      <template #header>告警</template>
      <el-table
        :data="alerts"
        border
        row-key="id"
        empty-text="暂无告警"
      >
        <el-table-column
          prop="title"
          label="规则"
          min-width="180"
        />
        <el-table-column
          prop="severity"
          label="级别"
          width="100"
        />
        <el-table-column
          prop="status"
          label="状态"
          width="100"
        />
        <el-table-column
          prop="detail"
          label="详情"
          min-width="260"
          show-overflow-tooltip
        />
        <el-table-column
          label="操作"
          width="180"
        >
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'OPEN'"
              link
              type="primary"
              @click="ackAlert(row)"
            >
              确认
            </el-button>
            <el-button
              v-if="row.status !== 'RESOLVED'"
              link
              type="success"
              @click="resolveAlert(row)"
            >
              解决
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never">
      <template #header>事件死信</template>
      <el-table
        :data="deadLetters"
        border
        row-key="eventId"
        empty-text="暂无死信"
      >
        <el-table-column
          prop="eventId"
          label="事件 ID"
          min-width="220"
        />
        <el-table-column
          prop="eventType"
          label="事件类型"
          min-width="220"
        />
        <el-table-column
          prop="lastError"
          label="最后错误"
          min-width="260"
          show-overflow-tooltip
        />
        <el-table-column
          label="操作"
          width="100"
        >
          <template #default="{ row }">
            <el-button
              link
              type="warning"
              @click="replayDead(row)"
            >
              重放
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { deadLetters as loadDeadLetters, replay } from '@/api/platform/event'
import { ack, alertPage, health, metrics, resolve } from '@/api/platform/monitor'

const loading = ref(false)
const snapshot = ref(null)
const healthState = ref({})
const alerts = ref([])
const deadLetters = ref([])
const metricError = ref('')

const healthRows = computed(() => Object.entries(healthState.value).map(([name, status]) => ({ name, status })))

function bytes(value) {
  if (!value) return '0 B'
  if (value < 1024) return `${value} B`
  if (value < 1024 ** 2) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / 1024 ** 2).toFixed(1)} MB`
}

async function load() {
  loading.value = true
  metricError.value = ''
  const results = await Promise.allSettled([metrics(), health(), alertPage(), loadDeadLetters()])
  const [metricResult, healthResult, alertResult, deadResult] = results
  if (metricResult.status === 'fulfilled') snapshot.value = metricResult.value.data
  else metricError.value = metricResult.reason?.message ?? '监测数据不可用'
  if (healthResult.status === 'fulfilled') healthState.value = healthResult.value.data ?? {}
  if (alertResult.status === 'fulfilled') alerts.value = alertResult.value.data?.records ?? []
  if (deadResult.status === 'fulfilled') deadLetters.value = deadResult.value.data?.records ?? []
  loading.value = false
}

async function ackAlert(row) {
  await ack(row.id)
  ElMessage.success('告警已确认')
  await load()
}

async function resolveAlert(row) {
  await resolve(row.id)
  ElMessage.success('告警已解决')
  await load()
}

async function replayDead(row) {
  await replay(row.eventId)
  ElMessage.success('死信已进入重试队列')
  await load()
}

onMounted(load)
</script>

<style scoped>
.monitor-page { display: grid; gap: 12px; }
.monitor__toolbar { display: flex; align-items: center; justify-content: space-between; font-size: 18px; font-weight: 600; }
.monitor__alert { margin-bottom: 0; }
</style>
