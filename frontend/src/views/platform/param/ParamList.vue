<template>
  <el-card shadow="never">
    <template #header>
      <div class="param__header">
        <span>参数管理</span>
        <el-button
          type="primary"
          :disabled="loading"
          @click="openAdd"
        >
          新增参数
        </el-button>
        <el-button
          :disabled="loading"
          @click="handleRefreshAll"
        >
          刷新全部缓存
        </el-button>
      </div>
    </template>

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
      <el-table-column
        label="操作"
        width="230"
        fixed="right"
      >
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            @click="openEdit(row)"
          >
            编辑
          </el-button>
          <el-tooltip
            :disabled="!deleteBlockReason(row)"
            :content="deleteBlockReason(row)"
            placement="top"
          >
            <span>
              <el-button
                link
                type="danger"
                :disabled="!!deleteBlockReason(row)"
                @click="handleDelete(rowEditable(row))"
              >
                删除
              </el-button>
            </span>
          </el-tooltip>
          <el-button
            link
            :disabled="!rowEditable(row).id"
            @click="handleRefreshKey(row)"
          >
            刷新缓存
          </el-button>
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

    <el-dialog
      v-model="editor.visible"
      :title="editor.title"
      width="560px"
      :close-on-click-modal="false"
    >
      <el-alert
        v-if="editor.encrypted"
        class="param__dialog-alert"
        type="warning"
        :closable="false"
        title="这是加密参数（value_type = SECRET）"
        description="后端只下发 ******，前端拿不到明文。留空 = 保持原值不变（P1-2 册 331 行）；要换值就填新值。"
      />
      <el-alert
        v-if="editor.hotReload === false"
        class="param__dialog-alert"
        type="info"
        :closable="false"
        title="该键不支持热更新：写入后需重启才生效"
      />

      <el-form
        ref="editorFormRef"
        :model="editor.form"
        :rules="RULES"
        label-width="96px"
        @submit.prevent
      >
        <el-form-item
          label="参数键"
          prop="paramKey"
        >
          <el-input
            v-model="editor.form.paramKey"
            :disabled="editor.mode === 'up'"
            maxlength="128"
            show-word-limit
            placeholder="如 platform.file.max-size"
          />
          <span
            v-if="editor.mode === 'up'"
            class="param__field-hint"
          >键是行身份，(键, 级别, 归属) 不可改；改名请删了重建。</span>
        </el-form-item>
        <el-form-item
          label="级别"
          prop="paramLevel"
        >
          <el-select
            v-model="editor.form.paramLevel"
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
            v-model="editor.form.ownerId"
            :min="0"
            :controls="false"
            :disabled="editor.form.paramLevel === 'SYSTEM'"
            class="param__owner"
          />
          <span class="param__field-hint">
            {{
              editor.form.paramLevel === 'SYSTEM'
                ? 'SYSTEM 级固定 ownerId=0（否则后端 20006）'
                : 'ORG 级填组织 ID、USER 级填用户 ID，必须大于 0'
            }}
          </span>
        </el-form-item>
        <el-form-item
          label="值类型"
          prop="valueType"
        >
          <el-select
            v-model="editor.form.valueType"
            class="param__type"
          >
            <el-option
              v-for="type in PARAM_VALUE_TYPES"
              :key="type"
              :label="type"
              :value="type"
            />
          </el-select>
        </el-form-item>
        <el-form-item
          label="值"
          prop="paramValue"
        >
          <el-input
            v-model="editor.form.paramValue"
            type="textarea"
            :rows="3"
            :placeholder="editor.encrypted ? '留空 = 保持原值不变' : ''"
          />
        </el-form-item>
        <el-form-item label="分组">
          <el-input
            v-model="editor.form.paramGroup"
            placeholder="可选"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button
          :disabled="saving"
          @click="editor.visible = false"
        >
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
  </el-card>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { FORBIDDEN_CODE } from '@/api/codes'
import { newIdempotencyScope } from '@/api/idempotency'
import {
  PARAM_KEY_MAX_LENGTH,
  PARAM_LEVELS,
  PARAM_VALUE_TYPES,
  add,
  del,
  describeWriteFailure,
  getAll,
  getPage,
  refresh,
  toParamRow,
  up,
} from '@/api/platform/param'

/** 覆盖优先级 USER > ORG > SYSTEM（P1 册 3.1.1）；DTO 未冻结 candidates 顺序，故前端显式排。 */
const LEVEL_RANK = { USER: 0, ORG: 1, SYSTEM: 2 }

/** 来源标签配色；DEFAULT/YAML/DB 是契约枚举（P1 册 5.3），不是文案。 */
const SOURCE_TAG_TYPE = { DEFAULT: 'info', YAML: 'warning', DB: 'success' }

/**
 * 删除不可用的两类原因：文案只写一处，按钮 tooltip 与点击守卫共用，避免两处漂移。
 * 必须分开说——DEFAULT/YAML 来源根本没有数据库行（`!id`），把它说成"内置"是误导（评审点 2）。
 */
const DELETE_NO_ROW_HINT = '该参数没有可删除的行（当前值来自默认值或配置文件）'
const DELETE_BUILTIN_HINT = '平台内置参数不可删除（后端返回 20005）'

const form = reactive({ paramKey: '', paramLevel: null, ownerId: null, paramGroup: '' })
const page = reactive({ pageNum: 1, pageSize: 20, total: 0 })
const rows = ref([])
const loading = ref(false)
const allMode = ref(false)
const saving = ref(false)

const editorFormRef = ref(null)

/** 编辑态：`mode` 取 `add`/`up`，`mode` 决定带不带 `version`（乐观锁）。 */
const editor = reactive({
  visible: false,
  mode: 'add',
  title: '新增参数',
  encrypted: false,
  hotReload: true,
  row: null,
  form: emptyEditorForm(),
})

/** 同一次提交的幂等键（重试复用、内容变了换新；见 `@/api/idempotency`）——只活在内存里。 */
const submitKey = newIdempotencyScope()

const RULES = {
  paramKey: [
    { required: true, message: '参数键必填', trigger: 'blur' },
    { max: PARAM_KEY_MAX_LENGTH, message: `参数键不超过 ${PARAM_KEY_MAX_LENGTH} 字符`, trigger: 'blur' },
  ],
  paramLevel: [{ required: true, message: '级别必填', trigger: 'change' }],
  valueType: [{ required: true, message: '值类型必填', trigger: 'change' }],
  paramValue: [
    {
      validator: (_rule, value, callback) => {
        // 前端只拦明显写错的值类型，省一次注定 20002 的往返；权威校验在后端 ParamValueCodec。
        const text = String(value ?? '').trim()
        if (text === '' || editor.form.valueType === 'STRING' || editor.form.valueType === 'SECRET') {
          callback()
          return
        }
        const bad =
          (editor.form.valueType === 'INT' && !/^-?\d+$/.test(text)) ||
          (editor.form.valueType === 'BOOL' && !/^(true|false)$/i.test(text)) ||
          (editor.form.valueType === 'DECIMAL' && !/^-?\d+(\.\d+)?$/.test(text)) ||
          (editor.form.valueType === 'JSON' && !isJson(text))
        if (bad) {
          callback(new Error(`值不是合法的 ${editor.form.valueType}`))
        } else {
          callback()
        }
      },
      trigger: 'blur',
    },
  ],
}

function emptyEditorForm() {
  return {
    paramKey: '',
    paramLevel: 'SYSTEM',
    ownerId: 0,
    paramValue: '',
    valueType: 'STRING',
    paramGroup: '',
    // 不提供"备注"：`ParamDTO` 没有 remark（P1-3 册 5.3 / 册 439 行第 17 条），
    // 读不回来的字段做成"只能写不能读"的入口只会让用户看不到当前值。
  }
}

function isJson(text) {
  try {
    JSON.parse(text)
    return true
  } catch {
    return false
  }
}

/** 列表行 → 编辑器行（含 `id`/`version`/`builtin`；无 DB 行时为 null，走 Add）。 */
function rowEditable(row) {
  return toParamRow(row)
}

/** 删除按钮不可用的原因（空串 = 可删）：`!id` 与 `builtin` 是两回事，分别给文案。 */
function deleteBlockReason(row) {
  const target = rowEditable(row)
  if (!target.id) {
    return DELETE_NO_ROW_HINT
  }
  if (target.builtin) {
    return DELETE_BUILTIN_HINT
  }
  return ''
}

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

/** 写成功后按当前视图重取：不把用户切回另一种视图（allMode 决定走 GetPage 还是 GetAll）。 */
function reload() {
  return allMode.value ? loadGroupAll() : loadPage()
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

function openAdd() {
  editor.mode = 'add'
  editor.title = '新增参数'
  editor.row = null
  editor.encrypted = false
  editor.hotReload = true
  editor.form = emptyEditorForm()
  editor.visible = true
}

/** 编辑既有行：级别/归属决定 `Up` 定位到哪一行，`paramKey` 锁住（后端不支持改名）。 */
function openEdit(row) {
  const target = rowEditable(row)
  editor.mode = target.version === null ? 'add' : 'up'
  editor.title = editor.mode === 'up' ? `编辑参数：${target.paramKey}` : `新增参数：${target.paramKey}`
  editor.row = target
  editor.encrypted = target.encrypted
  editor.hotReload = row?.hotReload !== false
  editor.form = {
    paramKey: target.paramKey ?? '',
    paramLevel: target.paramLevel,
    ownerId: target.ownerId,
    paramValue: target.paramValue ?? '',
    valueType: target.valueType,
    paramGroup: target.paramGroup ?? '',
  }
  editor.visible = true
}

/**
 * 保存。失败时**保留对话框内容**供用户改后再提交：
 * 幂等键按"载荷签名"复用——内容没变（重试）沿用旧键，内容变了就是一次新动作、换新键。
 */
async function handleSave() {
  const valid = await editorFormRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  const payload = {
    ...editor.form,
    version: editor.mode === 'up' ? editor.row?.version : null,
  }
  const attempt = submitKey.next(payload)
  saving.value = true
  try {
    if (editor.mode === 'up') {
      await up(payload, { idempotencyKey: attempt.key })
    } else {
      await add(payload, { idempotencyKey: attempt.key })
    }
    // 成功后这次动作结束：下一次保存必须换新键（哪怕内容一模一样）
    submitKey.reset()
    editor.visible = false
    ElMessage.success(editor.hotReload ? '保存成功（已热更新）' : '保存成功（该键需重启生效）')
    await reload()
  } catch (error) {
    ElMessage.error(describeWriteFailure(error))
  } finally {
    saving.value = false
  }
}

/**
 * 删除：带 `{id, version}`（乐观锁），二次确认。按钮已按 `deleteBlockReason` 禁用，这里再拦一道：
 * 无行（DEFAULT/YAML）与内置（builtin）分开提示，别把"没有行"说成"内置"。
 */
async function handleDelete(target) {
  if (!target?.id) {
    ElMessage.warning(DELETE_NO_ROW_HINT)
    return
  }
  if (target.builtin) {
    ElMessage.warning(DELETE_BUILTIN_HINT)
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认删除参数「${target.paramKey}」（${target.paramLevel} / ownerId=${target.ownerId}）？删除后该键回落到 YAML/默认值。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  const attempt = submitKey.next({ id: target.id, version: target.version })
  try {
    await del(target.id, target.version, { idempotencyKey: attempt.key })
    submitKey.reset()
    ElMessage.success('删除成功')
    await reload()
  } catch (error) {
    ElMessage.error(describeWriteFailure(error))
  }
}

/** 行内刷新：只清该键的缓存（`Refresh {paramKey}`）。 */
async function handleRefreshKey(row) {
  const target = rowEditable(row)
  if (!target.id) {
    ElMessage.warning('该键没有数据库行，无需刷新（值来自 YAML 或默认值）')
    return
  }
  const attempt = submitKey.next({ paramKey: target.paramKey })
  try {
    await refresh(target.paramKey, { idempotencyKey: attempt.key })
    submitKey.reset()
    ElMessage.success(`已刷新 ${target.paramKey} 的缓存`)
    await reload()
  } catch (error) {
    ElMessage.error(describeWriteFailure(error))
  }
}

/** 页头刷新：全量失效（DBA 绕过接口改库后的兜底），不传 paramKey。 */
async function handleRefreshAll() {
  try {
    await ElMessageBox.confirm(
      '确认清空并重载**全部**参数缓存？其他实例最长 60s（L1 TTL）后才看到新值。',
      '刷新全部缓存',
      { type: 'warning', confirmButtonText: '刷新', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  const attempt = submitKey.next({})
  try {
    await refresh(undefined, { idempotencyKey: attempt.key })
    submitKey.reset()
    ElMessage.success('已刷新全部参数缓存')
    await reload()
  } catch (error) {
    ElMessage.error(describeWriteFailure(error))
  }
}

// 进页即查一次：空条件 = 看全部分页结果，不需要用户先点一次"查询"
loadPage()
</script>

<style scoped>
.param__header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.param__header > span {
  flex: 1;
}

.param__level,
.param__owner {
  width: 130px;
}

.param__type {
  width: 160px;
}

.param__mode {
  margin-bottom: 12px;
}

.param__dialog-alert {
  margin-bottom: 12px;
}

.param__field-hint {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
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
