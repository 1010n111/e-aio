<template>
  <el-card shadow="never">
    <template #header>参数管理（只读）</template>

    <!--
      权限点：platform:param:list / :get（P1 册 7.3）。iam 未交付，故本页不自造
      v-hasPermi、也不硬编码假权限数据，按钮一律不隐藏，权限先由后端 @PreAuthorize 兜底；
      权限点由 iam 下发后接管（承接项见 P1 册 3.10.2 与 7.5 L1）。
    -->
    <el-form
      inline
      :model="form"
      @submit.prevent
    >
      <el-form-item label="参数键">
        <el-input
          v-model="form.paramKey"
          placeholder="模糊匹配"
          clearable
        />
      </el-form-item>
      <el-form-item label="级别">
        <el-select
          v-model="form.paramLevel"
          placeholder="全部"
          clearable
          class="param__level"
        >
          <el-option
            v-for="level in PARAM_LEVELS"
            :key="level"
            :label="level"
            :value="level"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="归属 ID">
        <el-input-number
          v-model="form.ownerId"
          :min="0"
          :controls="false"
          placeholder="可选"
          class="param__owner"
        />
      </el-form-item>
      <el-form-item label="分组">
        <el-input
          v-model="form.paramGroup"
          placeholder="可选"
          clearable
        />
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
          :disabled="loading || !form.paramGroup.trim()"
          @click="loadGroupAll"
        >
          按分组查看全部
        </el-button>
      </el-form-item>
    </el-form>

    <el-alert
      v-if="allMode"
      class="param__mode"
      type="info"
      :closable="false"
      title="当前为「按分组查看全部」视图（GetAll，结果不分页）"
      description="点「查询」回到分页查询（GetPage）视图。"
    />

    <el-table
      v-loading="loading"
      :data="rows"
      border
      row-key="paramKey"
      empty-text="没有匹配的参数"
    >
      <el-table-column
        type="expand"
        label="覆盖候选"
        width="110"
      >
        <template #default="{ row }">
          <div class="param__candidates">
            <p class="param__hint">
              生效顺序 USER &gt; ORG &gt; SYSTEM；<code>encrypted = true</code> 时后端恒下发
              <code>******</code>，前端原样展示、不解密不改写。
            </p>
            <el-table
              :data="orderCandidates(row.candidates)"
              size="small"
              border
              empty-text="无覆盖候选（值来自默认值或 YAML）"
            >
              <el-table-column
                prop="paramLevel"
                label="级别"
                width="100"
              />
              <el-table-column
                prop="ownerId"
                label="归属 ID"
                width="100"
              />
              <el-table-column
                prop="paramValue"
                label="值"
                min-width="180"
                show-overflow-tooltip
              />
              <el-table-column
                prop="valueType"
                label="值类型"
                width="100"
              />
              <el-table-column
                prop="paramGroup"
                label="分组"
                width="120"
              />
              <el-table-column
                label="加密"
                width="80"
              >
                <template #default="{ row: candidate }">{{ candidate.encrypted ? '是' : '否' }}</template>
              </el-table-column>
              <el-table-column
                label="内置"
                width="80"
              >
                <template #default="{ row: candidate }">{{ candidate.builtin ? '是' : '否' }}</template>
              </el-table-column>
              <el-table-column
                label="热更新"
                width="90"
              >
                <template #default="{ row: candidate }">{{ candidate.hotReload ? '是' : '否' }}</template>
              </el-table-column>
              <el-table-column
                prop="version"
                label="版本"
                width="80"
              />
              <el-table-column
                label="更新时间"
                min-width="170"
              >
                <template #default="{ row: candidate }">{{ formatInstant(candidate.updatedAt) }}</template>
              </el-table-column>
            </el-table>
          </div>
        </template>
      </el-table-column>
      <el-table-column
        prop="paramKey"
        label="参数键"
        min-width="220"
        show-overflow-tooltip
      />
      <el-table-column
        label="生效值"
        min-width="200"
      >
        <template #default="{ row }">{{ displayValue(row.effectiveValue) }}</template>
      </el-table-column>
      <el-table-column
        label="来源"
        width="110"
      >
        <template #default="{ row }">
          <el-tag :type="SOURCE_TAG_TYPE[row.source] ?? 'info'">{{ row.source ?? '-' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column
        label="来源级别"
        width="110"
      >
        <template #default="{ row }">{{ row.sourceLevel ?? '-' }}</template>
      </el-table-column>
      <el-table-column
        label="热更新"
        width="100"
      >
        <template #default="{ row }">
          <el-tag :type="row.hotReload ? 'success' : 'info'">{{ row.hotReload ? '是' : '否' }}</el-tag>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-if="!allMode"
      v-model:current-page="page.pageNum"
      v-model:page-size="page.pageSize"
      class="param__pager"
      :total="page.total"
      :page-sizes="[10, 20, 50, 100]"
      layout="total, sizes, prev, pager, next"
      @change="loadPage"
    />
  </el-card>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { FORBIDDEN_CODE } from '@/api/codes'
import { PARAM_LEVELS, getAll, getPage } from '@/api/platform/param'

/** 覆盖优先级 USER > ORG > SYSTEM（P1 册 3.1.1）；DTO 未冻结 candidates 顺序，故前端显式排。 */
const LEVEL_RANK = { USER: 0, ORG: 1, SYSTEM: 2 }

/** 来源标签配色；DEFAULT/YAML/DB 是契约枚举（P1 册 5.3），不是文案。 */
const SOURCE_TAG_TYPE = { DEFAULT: 'info', YAML: 'warning', DB: 'success' }

const form = reactive({ paramKey: '', paramLevel: null, ownerId: null, paramGroup: '' })
const page = reactive({ pageNum: 1, pageSize: 20, total: 0 })
const rows = ref([])
const loading = ref(false)
const allMode = ref(false)

function orderCandidates(candidates) {
  return [...(candidates ?? [])].sort(
    (left, right) => (LEVEL_RANK[left?.paramLevel] ?? 99) - (LEVEL_RANK[right?.paramLevel] ?? 99),
  )
}

function displayValue(value) {
  return value === null || value === undefined || value === '' ? '-' : value
}

function formatInstant(instant) {
  if (!instant) {
    return '-'
  }
  const at = new Date(instant)
  return Number.isNaN(at.getTime()) ? String(instant) : at.toLocaleString()
}

/** 失败只按 Result.code 分支：中文 message 仅用于展示，绝不做字符串匹配（api-conventions）。 */
function describeFailure(error) {
  if (error?.code === FORBIDDEN_CODE) {
    return '无 platform:param:list 权限（code=10403）'
  }
  return `${error?.message ?? '查询失败'}（code=${error?.code ?? 'unknown'}，traceId=${error?.traceId ?? '-'}）`
}

async function loadPage() {
  loading.value = true
  allMode.value = false
  try {
    const { data } = await getPage({ ...form, pageNum: page.pageNum, pageSize: page.pageSize })
    // 分页出参 PageResult：total/pageNum/pageSize/records（P1 册 5.3）
    rows.value = data?.records ?? []
    page.total = data?.total ?? 0
  } catch (error) {
    rows.value = []
    page.total = 0
    ElMessage.error(describeFailure(error))
  } finally {
    loading.value = false
  }
}

async function loadGroupAll() {
  loading.value = true
  allMode.value = true
  try {
    const { data } = await getAll({ paramGroup: form.paramGroup })
    rows.value = data ?? []
  } catch (error) {
    rows.value = []
    ElMessage.error(describeFailure(error))
  } finally {
    loading.value = false
  }
}

function handleQuery() {
  page.pageNum = 1
  loadPage()
}

function handleReset() {
  form.paramKey = ''
  form.paramLevel = null
  form.ownerId = null
  form.paramGroup = ''
  page.pageSize = 20
  handleQuery()
}

// 进页即查一次：空条件 = 看全部分页结果，不需要用户先点一次"查询"
loadPage()
</script>

<style scoped>
.param__level,
.param__owner {
  width: 130px;
}

.param__mode {
  margin-bottom: 12px;
}

.param__candidates {
  padding: 4px 12px 8px;
}

.param__hint {
  margin: 0 0 8px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.param__pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
