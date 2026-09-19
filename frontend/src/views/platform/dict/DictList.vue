<template>
  <el-card shadow="never">
    <template #header>
      <div class="dict__header">
        <span>字典管理</span>
        <span class="dict__hint">
          左侧维护类型、右侧维护该类型的项；项的作用是「值 → 标签」的解析（列表页展示标签）。
        </span>
      </div>
    </template>

    <el-row :gutter="16">
      <el-col :span="10">
        <el-form
          inline
          :model="typeForm"
          @submit.prevent
        >
          <el-form-item label="类型编码">
            <el-input
              v-model="typeForm.typeCode"
              placeholder="模糊匹配"
              clearable
            />
          </el-form-item>
          <el-form-item label="名称">
            <el-input
              v-model="typeForm.typeName"
              placeholder="模糊匹配"
              clearable
            />
          </el-form-item>
          <el-form-item label="状态">
            <el-select
              v-model="typeForm.status"
              placeholder="全部"
              clearable
              class="dict__status"
            >
              <el-option
                v-for="status in DICT_STATUSES"
                :key="status"
                :label="statusLabel(status)"
                :value="status"
              />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button
              type="primary"
              :loading="typeLoading"
              @click="handleTypeQuery"
            >
              查询
            </el-button>
            <el-button
              :disabled="typeLoading"
              @click="handleTypeReset"
            >
              重置
            </el-button>
            <el-button
              type="primary"
              plain
              :disabled="typeLoading"
              @click="openTypeAdd"
            >
              新增类型
            </el-button>
          </el-form-item>
        </el-form>

        <el-table
          v-loading="typeLoading"
          :data="types"
          border
          highlight-current-row
          row-key="id"
          empty-text="没有匹配的字典类型"
          @current-change="selectType"
        >
          <el-table-column
            prop="typeCode"
            label="类型编码"
            min-width="180"
            show-overflow-tooltip
          />
          <el-table-column
            prop="typeName"
            label="名称"
            min-width="120"
            show-overflow-tooltip
          />
          <el-table-column
            label="状态"
            width="90"
          >
            <template #default="{ row }">
              <el-tag :type="row.status === 'ENABLED' ? 'success' : 'info'">{{ statusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column
            label="项数"
            width="70"
          >
            <template #default="{ row }">{{ row.itemCount ?? 0 }}</template>
          </el-table-column>
          <el-table-column
            label="内置"
            width="70"
          >
            <template #default="{ row }">{{ row.builtin ? '是' : '否' }}</template>
          </el-table-column>
          <el-table-column
            label="操作"
            width="190"
            fixed="right"
          >
            <template #default="{ row }">
              <el-button
                link
                type="primary"
                @click="openTypeEdit(row)"
              >
                编辑
              </el-button>
              <el-button
                link
                :disabled="row.builtin"
                @click="handleTypeDelete(row)"
              >
                删除
              </el-button>
              <el-button
                link
                @click="handleRefresh(row)"
              >
                刷新缓存
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-pagination
          v-model:current-page="typePage.pageNum"
          v-model:page-size="typePage.pageSize"
          class="dict__pager"
          :total="typePage.total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @change="loadTypes"
        />
      </el-col>

      <el-col :span="14">
        <el-alert
          v-if="!selectedType"
          type="info"
          :closable="false"
          title="先在左侧选中一个字典类型"
          description="项必须属于某个类型（(type_code, item_value) 是唯一键），选中后这里显示它的项。"
        />

        <template v-else>
          <el-alert
            class="dict__type-alert"
            :type="selectedType.builtin ? 'warning' : 'success'"
            :closable="false"
            :title="`当前类型：${selectedType.typeCode}（${selectedType.typeName}）`"
            :description="typeAlertDescription(selectedType)"
          />

          <el-form
            inline
            :model="itemForm"
            @submit.prevent
          >
            <el-form-item label="项值">
              <el-input
                v-model="itemForm.itemValue"
                placeholder="模糊匹配"
                clearable
              />
            </el-form-item>
            <el-form-item label="状态">
              <el-select
                v-model="itemForm.status"
                placeholder="全部"
                clearable
                class="dict__status"
              >
                <el-option
                  v-for="status in DICT_STATUSES"
                  :key="status"
                  :label="statusLabel(status)"
                  :value="status"
                />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button
                type="primary"
                :loading="itemLoading"
                @click="handleItemQuery"
              >
                查询
              </el-button>
              <el-button
                :disabled="itemLoading"
                @click="handleItemReset"
              >
                重置
              </el-button>
              <el-button
                type="primary"
                plain
                :disabled="itemLoading"
                @click="openItemAdd"
              >
                新增项
              </el-button>
            </el-form-item>
          </el-form>

          <el-table
            v-loading="itemLoading"
            :data="items"
            border
            row-key="id"
            empty-text="该类型下没有匹配的字典项"
          >
            <el-table-column
              prop="itemValue"
              label="项值"
              min-width="140"
              show-overflow-tooltip
            />
            <el-table-column
              prop="itemLabel"
              label="标签"
              min-width="140"
              show-overflow-tooltip
            />
            <el-table-column
              prop="sortNo"
              label="排序"
              width="80"
            />
            <el-table-column
              label="状态"
              width="90"
            >
              <template #default="{ row }">
                <el-tag :type="row.status === 'ENABLED' ? 'success' : 'info'">{{ statusLabel(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column
              label="默认"
              width="70"
            >
              <template #default="{ row }">{{ row.isDefault ? '是' : '否' }}</template>
            </el-table-column>
            <el-table-column
              label="操作"
              width="190"
              fixed="right"
            >
              <template #default="{ row }">
                <el-button
                  link
                  type="primary"
                  @click="openItemEdit(row)"
                >
                  编辑
                </el-button>
                <el-button
                  link
                  :disabled="selectedType.builtin"
                  @click="handleToggleStatus(row)"
                >
                  {{ row.status === 'ENABLED' ? '停用' : '启用' }}
                </el-button>
                <el-button
                  link
                  type="danger"
                  :disabled="selectedType.builtin"
                  @click="handleItemDelete(row)"
                >
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>

          <el-pagination
            v-model:current-page="itemPage.pageNum"
            v-model:page-size="itemPage.pageSize"
            class="dict__pager"
            :total="itemPage.total"
            :page-sizes="[10, 20, 50, 100]"
            layout="total, sizes, prev, pager, next"
            @change="loadItems"
          />
        </template>
      </el-col>
    </el-row>

    <el-dialog
      v-model="typeEditor.visible"
      :title="typeEditor.title"
      width="520px"
      :close-on-click-modal="false"
    >
      <el-form
        ref="typeFormRef"
        :model="typeEditor.form"
        :rules="TYPE_RULES"
        label-width="90px"
        @submit.prevent
      >
        <el-form-item
          label="类型编码"
          prop="typeCode"
        >
          <el-input
            v-model="typeEditor.form.typeCode"
            :disabled="typeEditor.mode === 'up'"
            :maxlength="TYPE_CODE_MAX_LENGTH"
            show-word-limit
            placeholder="如 crm_customer_level"
          />
          <span
            v-if="typeEditor.mode === 'up'"
            class="dict__field-hint"
          >编码是行身份，不可修改；要换编码请删了重建。</span>
        </el-form-item>
        <el-form-item
          label="名称"
          prop="typeName"
        >
          <el-input
            v-model="typeEditor.form.typeName"
            maxlength="128"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-select
            v-model="typeEditor.form.status"
            class="dict__status"
          >
            <el-option
              v-for="status in DICT_STATUSES"
              :key="status"
              :label="statusLabel(status)"
              :value="status"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="typeEditor.form.remark"
            maxlength="255"
            show-word-limit
            placeholder="可选"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button
          :disabled="saving"
          @click="typeEditor.visible = false"
        >
          取消
        </el-button>
        <el-button
          type="primary"
          :loading="saving"
          @click="handleTypeSave"
        >
          保存
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="itemEditor.visible"
      :title="itemEditor.title"
      width="560px"
      :close-on-click-modal="false"
    >
      <el-alert
        v-if="itemEditor.mode === 'up'"
        class="dict__dialog-alert"
        type="warning"
        :closable="false"
        title="停用而非删除：停用后列表页不再出现该项，但历史数据仍能解析出标签"
        description="逻辑删除的项连标签都查不到，历史单据只能显示原始值（P1 册 3.2.4）。"
      />
      <el-form
        ref="itemFormRef"
        :model="itemEditor.form"
        :rules="ITEM_RULES"
        label-width="90px"
        @submit.prevent
      >
        <el-form-item
          label="项值"
          prop="itemValue"
        >
          <el-input
            v-model="itemEditor.form.itemValue"
            :disabled="itemEditor.mode === 'up'"
            :maxlength="ITEM_VALUE_MAX_LENGTH"
            show-word-limit
            placeholder="如 GOLD"
          />
          <span
            v-if="itemEditor.mode === 'up'"
            class="dict__field-hint"
          >(类型编码, 项值) 是唯一键，不可修改。</span>
        </el-form-item>
        <el-form-item
          label="标签"
          prop="itemLabel"
        >
          <el-input
            v-model="itemEditor.form.itemLabel"
            :maxlength="ITEM_LABEL_MAX_LENGTH"
            show-word-limit
            placeholder="如 黄金客户"
          />
        </el-form-item>
        <el-form-item label="排序号">
          <el-input-number
            v-model="itemEditor.form.sortNo"
            :min="0"
            :controls="false"
            class="dict__sort"
          />
          <span class="dict__field-hint">升序；getItems 按它返回。</span>
        </el-form-item>
        <el-form-item label="状态">
          <el-select
            v-model="itemEditor.form.status"
            class="dict__status"
          >
            <el-option
              v-for="status in DICT_STATUSES"
              :key="status"
              :label="statusLabel(status)"
              :value="status"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="默认项">
          <el-switch v-model="itemEditor.form.isDefault" />
        </el-form-item>
        <el-form-item label="扩展 JSON">
          <el-input
            v-model="itemEditor.form.extJson"
            type="textarea"
            :rows="3"
            :maxlength="EXT_JSON_MAX_LENGTH"
            placeholder="可选，如 {&quot;color&quot;:&quot;#409eff&quot;}；无结构校验，前端自行容错"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button
          :disabled="saving"
          @click="itemEditor.visible = false"
        >
          取消
        </el-button>
        <el-button
          type="primary"
          :loading="saving"
          @click="handleItemSave"
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

import { newIdempotencyScope } from '@/api/idempotency'
import {
  DICT_STATUSES,
  EXT_JSON_MAX_LENGTH,
  ITEM_LABEL_MAX_LENGTH,
  ITEM_VALUE_MAX_LENGTH,
  TYPE_CODE_MAX_LENGTH,
  describeWriteFailure,
  dictItem,
  dictType,
} from '@/api/platform/dict'

/**
 * 字典管理页（P1 册 3.10.2 的 `/platform/dict`：左侧类型 + 右侧项）。
 *
 * 三条口径：
 * - **按钮不做前端隐藏**（3.10.2 的临时态）：权限点由路由 `meta.permission` 声明、后端 `@PreAuthorize`
 *   兜底；`v-hasPermi` 由 iam 阶段提供（7.5 遗留）；
 * - **错误只按 `Result.code` 分支**（api-conventions）：文案在中英环境下都不参与判定；
 * - **幂等键每个用户动作生成一次**：同一次提交重试复用、内容变了换新（见 `@/api/idempotency`）。
 */
const STATUS_LABELS = { ENABLED: '启用', DISABLED: '停用' }

const typeForm = reactive({ typeCode: '', typeName: '', status: null })
const typePage = reactive({ pageNum: 1, pageSize: 20, total: 0 })
const types = ref([])
const typeLoading = ref(false)

const itemForm = reactive({ itemValue: '', status: null })
const itemPage = reactive({ pageNum: 1, pageSize: 20, total: 0 })
const items = ref([])
const itemLoading = ref(false)

const selectedType = ref(null)
const saving = ref(false)

const typeFormRef = ref(null)
const itemFormRef = ref(null)

const typeEditor = reactive({ visible: false, mode: 'add', title: '新增类型', form: emptyTypeForm() })
const itemEditor = reactive({ visible: false, mode: 'add', title: '新增字典项', form: emptyItemForm() })

/** 同一次提交的幂等键（重试复用、内容变了换新）——只活在内存里。 */
const submitKey = newIdempotencyScope()

const TYPE_RULES = {
  typeCode: [
    { required: true, message: '类型编码必填', trigger: 'blur' },
    { max: TYPE_CODE_MAX_LENGTH, message: `类型编码不超过 ${TYPE_CODE_MAX_LENGTH} 字符`, trigger: 'blur' },
  ],
  typeName: [{ required: true, message: '名称必填', trigger: 'blur' }],
}

const ITEM_RULES = {
  itemValue: [
    { required: true, message: '项值必填', trigger: 'blur' },
    { max: ITEM_VALUE_MAX_LENGTH, message: `项值不超过 ${ITEM_VALUE_MAX_LENGTH} 字符`, trigger: 'blur' },
  ],
  itemLabel: [{ required: true, message: '标签必填', trigger: 'blur' }],
}

function emptyTypeForm() {
  return { typeCode: '', typeName: '', status: 'ENABLED', remark: '' }
}

function emptyItemForm() {
  return { itemValue: '', itemLabel: '', sortNo: 0, status: 'ENABLED', isDefault: false, extJson: '' }
}

function statusLabel(status) {
  return STATUS_LABELS[status] ?? status ?? '-'
}

/** 内置类型的说明文案：讲清"不可删"的原因与替代动作（3.2.4 要求管理页文案明确代价）。 */
function typeAlertDescription(type) {
  if (type.builtin) {
    return '平台内置类型及其字典项不可删除；需要下线某个值时请改用「停用」。'
  }
  return '删除该类型前必须先清空它的项（否则后端返回 20008）；引用方在类型不存在时会得到 20003。'
}

function describeFailure(error) {
  return `${error?.message ?? '查询失败'}（code=${error?.code ?? 'unknown'}，traceId=${error?.traceId ?? '-'}）`
}

async function loadTypes() {
  typeLoading.value = true
  try {
    const { data } = await dictType.getPage({ ...typeForm, pageNum: typePage.pageNum, pageSize: typePage.pageSize })
    types.value = data?.records ?? []
    typePage.total = data?.total ?? 0
    // 选中行可能在翻页/改条件后不在当前页了：清掉右侧，避免"看着 A 的项在改 B"
    if (selectedType.value && !types.value.some((row) => row.id === selectedType.value.id)) {
      selectedType.value = null
      items.value = []
      itemPage.total = 0
    }
  } catch (error) {
    types.value = []
    typePage.total = 0
    ElMessage.error(describeFailure(error))
  } finally {
    typeLoading.value = false
  }
}

async function loadItems() {
  if (!selectedType.value) {
    return
  }
  itemLoading.value = true
  try {
    const { data } = await dictItem.getPage({
      typeCode: selectedType.value.typeCode,
      ...itemForm,
      pageNum: itemPage.pageNum,
      pageSize: itemPage.pageSize,
    })
    items.value = data?.records ?? []
    itemPage.total = data?.total ?? 0
  } catch (error) {
    items.value = []
    itemPage.total = 0
    ElMessage.error(describeFailure(error))
  } finally {
    itemLoading.value = false
  }
}

/** 选中类型 → 右侧换成它的项（项列表总是"某类型下"的列表，5.3）。 */
function selectType(row) {
  if (!row || row.id === selectedType.value?.id) {
    return
  }
  selectedType.value = row
  itemForm.itemValue = ''
  itemForm.status = null
  itemPage.pageNum = 1
  loadItems()
}

function handleTypeQuery() {
  typePage.pageNum = 1
  loadTypes()
}

function handleTypeReset() {
  typeForm.typeCode = ''
  typeForm.typeName = ''
  typeForm.status = null
  typePage.pageSize = 20
  handleTypeQuery()
}

function handleItemQuery() {
  itemPage.pageNum = 1
  loadItems()
}

function handleItemReset() {
  itemForm.itemValue = ''
  itemForm.status = null
  itemPage.pageSize = 20
  handleItemQuery()
}

function openTypeAdd() {
  typeEditor.mode = 'add'
  typeEditor.title = '新增类型'
  typeEditor.form = emptyTypeForm()
  typeEditor.visible = true
}

function openTypeEdit(row) {
  typeEditor.mode = 'up'
  typeEditor.title = `编辑类型：${row.typeCode}`
  typeEditor.form = {
    typeCode: row.typeCode,
    typeName: row.typeName,
    status: row.status,
    remark: row.remark ?? '',
  }
  typeEditor.visible = true
}

function openItemAdd() {
  itemEditor.mode = 'add'
  itemEditor.title = `新增字典项：${selectedType.value.typeCode}`
  itemEditor.form = emptyItemForm()
  itemEditor.visible = true
}

function openItemEdit(row) {
  itemEditor.mode = 'up'
  itemEditor.title = `编辑字典项：${row.itemValue}`
  itemEditor.form = {
    itemValue: row.itemValue,
    itemLabel: row.itemLabel,
    sortNo: row.sortNo ?? 0,
    status: row.status,
    isDefault: row.isDefault === true,
    extJson: row.extJson ?? '',
    version: row.version,
  }
  itemEditor.visible = true
}

/** 保存类型。`Up` 带当前 version（乐观锁）；失败保留对话框内容供改后再提交。 */
async function handleTypeSave() {
  const valid = await typeFormRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  const payload = { ...typeEditor.form, version: typeEditor.mode === 'up' ? currentTypeVersion() : null }
  const attempt = submitKey.next(payload)
  saving.value = true
  try {
    if (typeEditor.mode === 'up') {
      await dictType.up(payload, { idempotencyKey: attempt.key })
    } else {
      await dictType.add(payload, { idempotencyKey: attempt.key })
    }
    submitKey.reset()
    typeEditor.visible = false
    ElMessage.success('保存成功')
    await loadTypes()
  } catch (error) {
    ElMessage.error(describeWriteFailure(error))
  } finally {
    saving.value = false
  }
}

function currentTypeVersion() {
  const row = types.value.find((item) => item.typeCode === typeEditor.form.typeCode)
  return row?.version ?? 0
}

/** 删除类型：二次确认里写明"仍有用项会被拒"，后端 20008 是权威判定。 */
async function handleTypeDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确认删除字典类型「${row.typeCode}」？该类型下仍有项时后端会拒绝（20008），需先清空项。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  const attempt = submitKey.next({ id: row.id, version: row.version })
  try {
    await dictType.del(row.id, row.version, { idempotencyKey: attempt.key })
    submitKey.reset()
    ElMessage.success('删除成功')
    if (selectedType.value?.id === row.id) {
      selectedType.value = null
      items.value = []
    }
    await loadTypes()
  } catch (error) {
    ElMessage.error(describeWriteFailure(error))
  }
}

/** 刷新该类型的缓存（改库后不经过接口的兜底）；只清本机 L1+L2，其他实例靠 L1 TTL 收敛。 */
async function handleRefresh(row) {
  const attempt = submitKey.next({ typeCode: row.typeCode })
  try {
    await dictType.refresh(row.typeCode, { idempotencyKey: attempt.key })
    submitKey.reset()
    ElMessage.success(`已刷新 ${row.typeCode} 的缓存`)
    await loadTypes()
  } catch (error) {
    ElMessage.error(describeWriteFailure(error))
  }
}

/** 保存项。 */
async function handleItemSave() {
  const valid = await itemFormRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  const payload = {
    typeCode: selectedType.value.typeCode,
    ...itemEditor.form,
    version: itemEditor.mode === 'up' ? itemEditor.form.version : null,
  }
  const attempt = submitKey.next(payload)
  saving.value = true
  try {
    if (itemEditor.mode === 'up') {
      await dictItem.up(payload, { idempotencyKey: attempt.key })
    } else {
      await dictItem.add(payload, { idempotencyKey: attempt.key })
    }
    submitKey.reset()
    itemEditor.visible = false
    ElMessage.success('保存成功')
    await loadItems()
    await loadTypes()
  } catch (error) {
    ElMessage.error(describeWriteFailure(error))
  } finally {
    saving.value = false
  }
}

/**
 * 停用/启用：走 `upItem` 改状态。
 *
 * 停用是设计册推荐的"下线"方式（3.2.4）：项不再出现在 `getItems`，但 `getLabel` 仍能解析它，
 * 历史数据照常显示标签。
 */
async function handleToggleStatus(row) {
  const next = row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  const payload = {
    typeCode: row.typeCode,
    itemValue: row.itemValue,
    itemLabel: row.itemLabel,
    sortNo: row.sortNo,
    status: next,
    isDefault: row.isDefault === true,
    extJson: row.extJson ?? null,
    version: row.version,
  }
  const attempt = submitKey.next(payload)
  try {
    await dictItem.up(payload, { idempotencyKey: attempt.key })
    submitKey.reset()
    ElMessage.success(next === 'ENABLED' ? '已启用' : '已停用（历史数据仍能解析标签）')
    await loadItems()
  } catch (error) {
    ElMessage.error(describeWriteFailure(error))
  }
}

/** 删除项：文案按 3.2.4 说明"删除 vs 停用"的代价差异。 */
async function handleItemDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确认删除字典项「${row.itemValue}」？删除后历史数据将显示原始值，建议改用停用。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  const attempt = submitKey.next({ id: row.id, version: row.version })
  try {
    await dictItem.del(row.id, row.version, { idempotencyKey: attempt.key })
    submitKey.reset()
    ElMessage.success('删除成功')
    await loadItems()
    await loadTypes()
  } catch (error) {
    ElMessage.error(describeWriteFailure(error))
  }
}

// 进页即查一次类型：空条件 = 看全部，不需要用户先点一次"查询"
loadTypes()
</script>

<style scoped>
.dict__header {
  display: flex;
  align-items: baseline;
  gap: 12px;
}

.dict__hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.dict__status {
  width: 130px;
}

.dict__sort {
  width: 130px;
}

.dict__type-alert {
  margin-bottom: 12px;
}

.dict__dialog-alert {
  margin-bottom: 12px;
}

.dict__field-hint {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.dict__pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
