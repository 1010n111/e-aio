<template>
  <el-card shadow="never">
    <template #header>
      <div class="file__header">
        <span>文件中心</span>
      </div>
    </template>

    <el-form
      inline
      :model="query"
      @submit.prevent
    >
      <el-form-item label="文件名">
        <el-input
          v-model="query.originalName"
          placeholder="模糊匹配"
          clearable
        />
      </el-form-item>
      <el-form-item label="业务类型">
        <el-input
          v-model="query.bizType"
          placeholder="如 crm.customer"
          clearable
        />
      </el-form-item>
      <el-form-item label="业务 ID">
        <el-input
          v-model="query.bizId"
          placeholder="与业务类型成对"
          clearable
        />
      </el-form-item>
      <el-form-item label="上传时间">
        <el-date-picker
          v-model="query.dateRange"
          type="daterange"
          value-format="YYYY-MM-DD"
          start-placeholder="起"
          end-placeholder="止"
          class="file__range"
        />
      </el-form-item>
      <el-form-item label="来源">
        <el-select
          v-model="query.source"
          placeholder="全部"
          clearable
          class="file__source"
        >
          <el-option
            v-for="source in FILE_SOURCES"
            :key="source"
            :label="sourceLabel(source)"
            :value="source"
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

    <div class="file__toolbar">
      <el-upload
        :show-file-list="false"
        :http-request="handleUpload"
      >
        <el-button
          type="primary"
          plain
          :loading="uploading"
        >
          上传文件
        </el-button>
      </el-upload>
      <el-button
        :disabled="!selected"
        @click="handleCopyUrl()"
      >
        复制下载链接
      </el-button>
      <el-button
        :disabled="!selected"
        :loading="downloading"
        @click="handleDownload()"
      >
        下载
      </el-button>
      <el-button
        :disabled="!selected"
        @click="openBind()"
      >
        绑定业务对象
      </el-button>
      <el-button
        type="danger"
        plain
        :disabled="!selected"
        @click="handleDelete()"
      >
        删除
      </el-button>
    </div>

    <el-table
      v-loading="loading"
      :data="rows"
      border
      highlight-current-row
      row-key="id"
      empty-text="没有匹配的文件"
      @current-change="selectRow"
    >
      <el-table-column
        prop="originalName"
        label="文件名"
        min-width="240"
        show-overflow-tooltip
      />
      <el-table-column
        prop="extension"
        label="类型"
        width="80"
      />
      <el-table-column
        label="大小"
        width="100"
      >
        <template #default="{ row }">{{ formatSize(row.sizeBytes) }}</template>
      </el-table-column>
      <el-table-column
        label="来源"
        width="110"
      >
        <template #default="{ row }">{{ sourceLabel(row.source) }}</template>
      </el-table-column>
      <el-table-column
        label="存储"
        width="100"
      >
        <template #default="{ row }">
          <el-tag
            size="small"
            :type="row.storageType === 'S3' ? 'warning' : 'info'"
          >
            {{ storageLabel(row.storageType) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column
        prop="uploaderId"
        label="上传者"
        width="120"
      />
      <el-table-column
        label="上传时间"
        width="180"
      >
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="230"
        fixed="right"
      >
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            @click="handleDownload(row)"
          >
            下载
          </el-button>
          <el-button
            link
            @click="handleCopyUrl(row)"
          >
            链接
          </el-button>
          <el-button
            link
            @click="openBind(row)"
          >
            绑定
          </el-button>
          <el-button
            link
            type="danger"
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
      class="file__pager"
      :total="page.total"
      :page-sizes="[10, 20, 50, 100]"
      layout="total, sizes, prev, pager, next"
      @change="load"
    />

    <el-dialog
      v-model="bindEditor.visible"
      title="绑定业务对象"
      width="520px"
      :close-on-click-modal="false"
    >
      <el-form
        label-width="100px"
        @submit.prevent
      >
        <el-form-item label="文件">
          <span>{{ bindEditor.fileName }}</span>
        </el-form-item>
        <el-form-item label="业务类型">
          <el-input
            v-model="bindEditor.bizType"
            placeholder="如 crm.customer"
            maxlength="64"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="业务 ID">
          <el-input
            v-model="bindEditor.bizId"
            placeholder="业务对象的主键（正整数）"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button
          :disabled="binding"
          @click="bindEditor.visible = false"
        >
          取消
        </el-button>
        <el-button
          type="primary"
          :loading="binding"
          @click="handleBind"
        >
          绑定
        </el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { newIdempotencyScope } from '@/api/idempotency'
import {
  FILE_SOURCES,
  bind,
  del,
  describeWriteFailure,
  download,
  formatSize,
  getPage,
  getUrl,
  normalizeBindCmd,
  triggerBrowserDownload,
  upload,
} from '@/api/platform/file'

/**
 * 文件中心页（P1 册 3.10.2 的 `/platform/file`）：上传 / 列表 / 下载 / 删除 / 绑定，P1 册 3.10 的 3.10.2 项。
 *
 * 三条口径：
 * - **按钮不做前端隐藏**（3.10.2 的临时态）：权限点由路由 `meta.permission` 声明、后端 `@PreAuthorize`
 *   兜底；`v-hasPermi` 由 iam 阶段提供（7.5 遗留）。所以"下载/删除"对任何登录用户都点得动，
 *   点了会不会被拒由后端答（20017/10403）。
 * - **错误只按 `Result.code` 分支**（api-conventions），文案在中英环境下都不参与判定；
 * - **写动作每个用户动作一个幂等键**：同一次提交重试复用、内容变了换新（`newIdempotencyScope`）；
 *   上传不带键（服务端自幂等），`Del`/`Bind` 带（重复绑定不许报错）。
 */
const SOURCE_LABELS = {
  UPLOAD: '上传',
  CHUNK: '分片上传',
  EXPORT: '导出文件',
  IMPORT_ERROR: '导入错误文件',
  TEMPLATE: '模板',
}

const STORAGE_LABELS = { LOCAL: '本地盘', S3: '对象存储' }

const rows = ref([])
const loading = ref(false)
const uploading = ref(false)
const downloading = ref(false)
const binding = ref(false)
const selected = ref(null)

const query = reactive({ originalName: '', bizType: '', bizId: '', source: null, dateRange: [] })
const page = reactive({ pageNum: 1, pageSize: 20, total: 0 })

const bindEditor = reactive({ visible: false, fileId: null, fileName: '', bizType: '', bizId: '' })

/** 同一次提交的幂等键（重试复用、内容变了换新）——只活在内存里。 */
const submitKey = newIdempotencyScope()

function sourceLabel(source) {
  return SOURCE_LABELS[source] ?? source ?? '-'
}

function storageLabel(storageType) {
  return STORAGE_LABELS[storageType] ?? storageType ?? '-'
}

/** 时刻展示：后端给 ISO-8601（UTC）；这里只做可读化，不做时区换算。 */
function formatTime(value) {
  if (!value) {
    return '-'
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString()
}

async function load() {
  const [from, to] = query.dateRange ?? []
  loading.value = true
  try {
    const { data } = await getPage({
      originalName: query.originalName,
      bizType: query.bizType,
      bizId: query.bizId,
      source: query.source,
      // `createdFrom/createdTo` 后端是 `Instant`（5.3 的 FileQuery）：日期选择器只给本地日期串，
      // 这里补成本地日界的 ISO 瞬时；直接把 "2024-05-01" 丢过去会解析失败或落到错误的一天。
      createdFrom: from ? new Date(`${from}T00:00:00`).toISOString() : undefined,
      createdTo: to ? new Date(`${to}T23:59:59.999`).toISOString() : undefined,
      pageNum: page.pageNum,
      pageSize: page.pageSize,
    })
    rows.value = data?.records ?? []
    page.total = data?.total ?? 0
    // 选中行可能在翻页/改条件后不在当前页了：清掉，避免"看着 A 在下载 B"
    if (selected.value && !rows.value.some((row) => row.id === selected.value.id)) {
      selected.value = null
    }
  } catch (error) {
    rows.value = []
    page.total = 0
    ElMessage.error(describeWriteFailure(error))
  } finally {
    loading.value = false
  }
}

function handleQuery() {
  page.pageNum = 1
  return load()
}

function handleReset() {
  query.originalName = ''
  query.bizType = ''
  query.bizId = ''
  query.source = null
  query.dateRange = []
  page.pageSize = 20
  return handleQuery()
}

function selectRow(row) {
  selected.value = row ?? null
}

/** el-upload 的自定义上传：走统一请求层的 multipart 封装，后端仍会独立判大小与白名单。 */
async function handleUpload(options) {
  uploading.value = true
  try {
    const result = await upload(options.file)
    options.onSuccess?.(result, options)
    const { data } = result
    ElMessage.success(`已上传：${data?.originalName ?? options.file?.name ?? ''}`)
    await load()
  } catch (error) {
    options.onError?.(error)
    ElMessage.error(describeWriteFailure(error))
  } finally {
    uploading.value = false
  }
}

/** 复制下载链接：`GetUrl` 给的是同源路径（带时效签名），复制前补成绝对地址，方便粘到别处。 */
async function handleCopyUrl(row = selected.value) {
  if (!row) {
    return
  }
  try {
    const { data } = await getUrl(row.id)
    if (!data?.url) {
      throw new Error('后端未返回下载链接')
    }
    await copyText(absoluteUrl(data.url))
    ElMessage.success(`下载链接已复制（${formatTime(data.expireAt)} 前有效）`)
  } catch (error) {
    ElMessage.error(describeWriteFailure(error))
  }
}

/** 直接下载：失败时后端仍是 HTTP 200，请求层已按 `Content-Type` 分辨出错误体并抛错。 */
async function handleDownload(row = selected.value) {
  if (!row) {
    return
  }
  downloading.value = true
  try {
    const result = await download(row.id)
    // `Content-Disposition` 缺失时退回列表里的原始文件名，别让浏览器存成随机名
    triggerBrowserDownload({ data: result.data, filename: result.filename ?? row.originalName })
    ElMessage.success('已开始下载')
  } catch (error) {
    ElMessage.error(describeWriteFailure(error))
  } finally {
    downloading.value = false
  }
}

function openBind(row = selected.value) {
  if (!row) {
    return
  }
  bindEditor.fileId = row.id
  bindEditor.fileName = row.originalName
  bindEditor.bizType = ''
  bindEditor.bizId = ''
  bindEditor.visible = true
}

/** 绑定：先在本地整形校验，非法输入不发请求（后端 `@NotBlank/@Positive/@NotEmpty` 必拒）。 */
async function handleBind() {
  let cmd
  try {
    cmd = normalizeBindCmd({ bizType: bindEditor.bizType, bizId: bindEditor.bizId, fileIds: [bindEditor.fileId] })
  } catch (error) {
    ElMessage.error(error.message)
    return
  }
  binding.value = true
  try {
    await bind(cmd, { idempotencyKey: submitKey.next(cmd).key })
    submitKey.reset()
    bindEditor.visible = false
    ElMessage.success('已绑定（重复绑定不会报错，也不会多出一行）')
    // 列表可能正按 bizType/bizId 过滤：绑完刷新一次，让这行如实出现
    await load()
  } catch (error) {
    ElMessage.error(describeWriteFailure(error))
  } finally {
    binding.value = false
  }
}

/** 删除：带行的 `version`（乐观锁），过期即 10003——那说明别人先改过，刷新后重来。 */
async function handleDelete(row = selected.value) {
  if (!row) {
    return
  }
  const confirmed = await ElMessageBox.confirm(
    `确认删除文件「${row.originalName}」？（逻辑删除；若已被他人改过，后端会以 10003 拒绝，请刷新后重试）`,
    '删除确认',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
  ).catch(() => false)
  if (!confirmed) {
    return
  }
  const payload = { fileId: row.id, version: row.version }
  try {
    await del(row.id, row.version, { idempotencyKey: submitKey.next(payload).key })
    submitKey.reset()
    if (selected.value?.id === row.id) {
      selected.value = null
    }
    ElMessage.success('已删除')
    await load()
  } catch (error) {
    ElMessage.error(describeWriteFailure(error))
  }
}

/** 同源路径 → 绝对地址（签名链接给的是 `/api/...`，直接复制出去只有本机能用）。 */
function absoluteUrl(path) {
  const text = String(path ?? '')
  if (/^https?:\/\//i.test(text)) {
    return text
  }
  return `${window.location.origin}${text.startsWith('/') ? '' : '/'}${text}`
}

/** 复制文本：优先 Clipboard API；http 局域网部署（非安全上下文）没有它，退回落选 textarea。 */
async function copyText(text) {
  if (window.navigator.clipboard?.writeText) {
    await window.navigator.clipboard.writeText(text)
    return
  }
  const area = document.createElement('textarea')
  area.value = text
  area.style.position = 'fixed'
  area.style.opacity = '0'
  document.body.appendChild(area)
  area.select()
  document.execCommand('copy')
  document.body.removeChild(area)
}

// 进页即查一次：空条件 = 看全部，不需要用户先点一次"查询"
load()
</script>

<style scoped>
.file__header {
  display: flex;
  align-items: baseline;
  gap: 12px;
}

.file__toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.file__range {
  width: 240px;
}

.file__source {
  width: 130px;
}

.file__pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
