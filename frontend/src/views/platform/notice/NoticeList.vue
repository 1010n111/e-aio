<template>
  <el-card shadow="never">
    <template #header>
      <div class="notice__header">
        <span>公告管理</span><el-button
          type="primary"
          @click="openAdd"
        >
          新增公告
        </el-button>
      </div>
    </template>
    <el-form
      inline
      :model="query"
      @submit.prevent
    >
      <el-form-item label="标题">
        <el-input
          v-model="query.title"
          clearable
        />
      </el-form-item>
      <el-form-item label="状态">
        <el-select
          v-model="query.publishStatus"
          clearable
        >
          <el-option
            label="草稿"
            value="DRAFT"
          /><el-option
            label="已发布"
            value="PUBLISHED"
          /><el-option
            label="已撤回"
            value="REVOKED"
          />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button
          type="primary"
          @click="load"
        >
          查询
        </el-button>
      </el-form-item>
    </el-form>
    <el-table
      v-loading="loading"
      :data="rows"
      row-key="id"
      border
    >
      <el-table-column
        prop="title"
        label="标题"
        min-width="220"
      />
      <el-table-column
        prop="scopeType"
        label="范围"
        width="90"
      />
      <el-table-column
        prop="publishStatus"
        label="状态"
        width="100"
      />
      <el-table-column
        prop="publishTime"
        label="发布时间"
        width="180"
      />
      <el-table-column
        label="置顶"
        width="80"
      >
        <template #default="{ row }">{{ row.topFlag ? '是' : '否' }}</template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="240"
      >
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            @click="openEdit(row)"
          >
            编辑
          </el-button><el-button
            v-if="row.publishStatus === 'PUBLISHED'"
            link
            @click="revokeRow(row)"
          >
            撤回
          </el-button><el-button
            v-else
            link
            type="success"
            @click="publishRow(row)"
          >
            发布
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-dialog
      v-model="dialogVisible"
      :title="editing ? '编辑公告' : '新增公告'"
      width="620px"
    >
      <el-form
        :model="form"
        label-width="90px"
      >
        <el-form-item label="标题">
          <el-input
            v-model="form.title"
            maxlength="200"
          />
        </el-form-item><el-form-item label="范围">
          <el-select v-model="form.scopeType">
            <el-option
              label="全部"
              value="ALL"
            /><el-option
              label="组织"
              value="ORG"
            /><el-option
              label="用户"
              value="USER"
            />
          </el-select>
        </el-form-item><el-form-item
          v-if="form.scopeType !== 'ALL'"
          label="目标ID"
        >
          <el-input
            v-model="form.targetText"
            placeholder="多个ID用逗号分隔"
          />
        </el-form-item><el-form-item label="发布时间">
          <el-date-picker
            v-model="form.publishTime"
            type="datetime"
          />
        </el-form-item><el-form-item label="置顶"><el-switch v-model="form.topFlag" /></el-form-item><el-form-item label="正文">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="8"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button><el-button
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
import { add, getPage, publish, revoke, up } from '@/api/platform/notice'

const loading = ref(false)
const rows = ref([])
const dialogVisible = ref(false)
const editing = ref(false)
const query = reactive({ title: '', publishStatus: '' })
const form = reactive({ id: null, title: '', content: '', scopeType: 'ALL', targetText: '', publishTime: null, topFlag: false })
async function load() { loading.value = true; try { const result = await getPage(query); rows.value = result.data?.records ?? [] } finally { loading.value = false } }
function openAdd() { Object.assign(form, { id: null, title: '', content: '', scopeType: 'ALL', targetText: '', publishTime: null, topFlag: false }); editing.value = false; dialogVisible.value = true }
function openEdit(row) { Object.assign(form, { ...row, targetText: '' }); editing.value = true; dialogVisible.value = true }
function payload() { const ids = form.targetText.split(',').map(value => Number(value.trim())).filter(value => value > 0); return { id: form.id, title: form.title, content: form.content, scopeType: form.scopeType, targetType: form.scopeType === 'ALL' ? null : form.scopeType, targetIds: ids, publishTime: form.publishTime, topFlag: form.topFlag } }
async function saveRow() { const body = payload(); if (editing.value) await up(body); else await add(body); dialogVisible.value = false; await load() }
async function publishRow(row) { await publish({ id: row.id }); await load() }
async function revokeRow(row) { await revoke(row.id); await load() }
onMounted(load)
</script>

<style scoped>
.notice__header { display: flex; justify-content: space-between; align-items: center; }
</style>
