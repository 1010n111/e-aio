<template>
  <el-card shadow="never">
    <template #header>
      <div class="template__header">
        <span>通知模板</span><el-button
          type="primary"
          @click="openAdd"
        >
          新增模板
        </el-button>
      </div>
    </template>
    <el-table
      v-loading="loading"
      :data="rows"
      row-key="id"
      border
    >
      <el-table-column
        prop="templateCode"
        label="编码"
        min-width="180"
      /><el-table-column
        prop="templateName"
        label="名称"
        min-width="160"
      /><el-table-column
        prop="channel"
        label="渠道"
        width="100"
      /><el-table-column
        prop="status"
        label="状态"
        width="100"
      /><el-table-column
        prop="contentTemplate"
        label="正文模板"
        min-width="260"
        show-overflow-tooltip
      /><el-table-column
        label="操作"
        width="100"
      >
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            @click="openEdit(row)"
          >
            编辑
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-dialog
      v-model="visible"
      :title="editing ? '编辑模板' : '新增模板'"
      width="650px"
    >
      <el-form
        :model="form"
        label-width="100px"
      >
        <el-form-item label="编码">
          <el-input
            v-model="form.templateCode"
            :disabled="editing"
          />
        </el-form-item><el-form-item label="名称"><el-input v-model="form.templateName" /></el-form-item><el-form-item label="渠道">
          <el-select v-model="form.channel">
            <el-option
              label="站内"
              value="SITE"
            /><el-option
              label="邮件"
              value="EMAIL"
            /><el-option
              label="短信"
              value="SMS"
            />
          </el-select>
        </el-form-item><el-form-item label="标题模板"><el-input v-model="form.titleTemplate" /></el-form-item><el-form-item label="正文模板">
          <el-input
            v-model="form.contentTemplate"
            type="textarea"
            :rows="8"
          />
        </el-form-item><el-form-item label="变量">
          <el-input
            v-model="form.variablesText"
            placeholder="多个变量用逗号分隔"
          />
        </el-form-item>
      </el-form><template #footer>
        <el-button @click="visible = false">取消</el-button><el-button
          type="primary"
          @click="saveRow"
        >
          保存
        </el-button>
      </template>
    </el-dialog>
  </el-card>
</template>
<script setup>
import { onMounted, reactive, ref } from 'vue'
import { getPage, save } from '@/api/platform/notifyTemplate'
const loading = ref(false); const rows = ref([]); const visible = ref(false); const editing = ref(false)
const form = reactive({ templateCode: '', templateName: '', channel: 'SITE', titleTemplate: '', contentTemplate: '', variablesText: '', version: null })
async function load() { loading.value = true; try { const result = await getPage(); rows.value = result.data?.records ?? [] } finally { loading.value = false } }
function openAdd() { Object.assign(form, { templateCode: '', templateName: '', channel: 'SITE', titleTemplate: '', contentTemplate: '', variablesText: '', version: null }); editing.value = false; visible.value = true }
function openEdit(row) { Object.assign(form, { ...row, variablesText: (row.variables ?? []).join(','), version: row.version }); editing.value = true; visible.value = true }
async function saveRow() { await save({ ...form, variables: form.variablesText.split(',').map(value => value.trim()).filter(Boolean) }); visible.value = false; await load() }
onMounted(load)
</script>
<style scoped>.template__header { display: flex; justify-content: space-between; align-items: center; }</style>
