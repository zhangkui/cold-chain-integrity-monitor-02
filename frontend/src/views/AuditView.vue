<template>
  <div class="page-container">
    <div class="filter-bar">
      <el-form :inline="true" @submit.prevent>
        <el-form-item label="实体类型">
          <el-input v-model="filters.entityType" placeholder="如 BOX_ASSESSMENT / ANOMALY / SAMPLE / EVIDENCE" clearable style="width: 300px" @keyup.enter="load" />
        </el-form-item>
        <el-form-item label="动作">
          <el-input v-model="filters.action" placeholder="如 REVIEW_CONFIRM" clearable style="width: 220px" @keyup.enter="load" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="load">查询</el-button>
          <el-button :icon="RefreshLeft" @click="reset">重置</el-button>
        </el-form-item>
      </el-form>
    </div>

    <div class="table-card">
      <el-alert type="warning" :closable="false" style="margin-bottom: 12px">
        审计日志仅追加：数据库触发器禁止任何 UPDATE / DELETE 操作。
      </el-alert>
      <el-table v-loading="loading" :data="logs" stripe size="small" empty-text="暂无审计记录">
        <el-table-column prop="id" label="#" width="80" />
        <el-table-column prop="createdAt" label="时间(UTC)" width="180">
          <template #default="{ row }">{{ fmtUtc(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column prop="entityType" label="实体" width="160" />
        <el-table-column prop="entityId" label="实体ID" width="90" />
        <el-table-column prop="action" label="动作" width="180">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.action }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="operator" label="操作人" width="110" />
        <el-table-column prop="requestId" label="请求ID" width="220">
          <template #default="{ row }"><span class="mono sub-text">{{ row.requestId || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="明细" min-width="260">
          <template #default="{ row }">
            <el-popover trigger="click" width="480" placement="left">
              <template #reference>
                <el-link type="primary" :underline="false">查看 JSON</el-link>
              </template>
              <pre class="json-pop">{{ row.detail }}</pre>
            </el-popover>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RefreshLeft, Search } from '@element-plus/icons-vue'
import { api } from '../api'
import { fmtUtc } from '../utils/format'

interface AuditLog {
  id: number
  entityType: string
  entityId: string
  action: string
  operator: string
  requestId?: string
  detail?: string
  createdAt: string
}

const loading = ref(false)
const logs = ref<AuditLog[]>([])
const filters = ref({ entityType: '', action: '' })

async function load() {
  loading.value = true
  try {
    logs.value = (await api.auditLogs({
      entityType: filters.value.entityType || undefined,
      action: filters.value.action || undefined
    })) as AuditLog[]
  } finally {
    loading.value = false
  }
}
function reset() {
  filters.value = { entityType: '', action: '' }
  load()
}
onMounted(load)
</script>

<style scoped>
.sub-text { color: #909399; font-size: 12px; }
.json-pop {
  white-space: pre-wrap;
  word-break: break-all;
  font-family: ui-monospace, Menlo, Consolas, monospace;
  font-size: 12px;
  margin: 0;
  max-height: 360px;
  overflow: auto;
}
</style>
