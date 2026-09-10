<template>
  <div class="page-container">
    <div class="table-card upload-card">
      <div class="upload-title">CSV 批量导入温度采样</div>
      <el-upload
        drag
        :auto-upload="false"
        :on-change="onPick"
        :show-file-list="false"
        accept=".csv,text/csv"
      >
        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
        <div class="el-upload__text">拖拽 CSV 到此处，或 <em>点击选择文件</em></div>
        <template #tip>
          <div class="upload-tip">
            表头：deviceCode,seq,sampleTime,timezone,temperature,source
            （示例见仓库 samples/temperature-sample.csv）。逐行幂等：重复提交标记为“幂等重复”，不会覆盖已确认异常。
          </div>
        </template>
      </el-upload>
      <div v-if="pickedName" class="picked-row">
        <el-icon><Document /></el-icon>
        <span>{{ pickedName }}</span>
        <el-button type="primary" size="small" :loading="uploading" @click="submit">开始导入</el-button>
      </div>
    </div>

    <div class="table-card" style="margin-top: 12px">
      <div class="table-toolbar">
        <span class="table-title">最近导入批次</span>
        <el-button :icon="Refresh" size="small" @click="loadBatches">刷新</el-button>
      </div>
      <el-table :data="batches" size="small" stripe @row-click="openBatch" empty-text="暂无导入批次" style="cursor: pointer">
        <el-table-column prop="batchNo" label="批次号" width="220" class-name="mono-cell" />
        <el-table-column prop="fileName" label="文件" min-width="180" show-overflow-tooltip />
        <el-table-column label="结果" width="260">
          <template #default="{ row }">
            <el-tag type="success" size="small" effect="plain">成功 {{ row.successRows }}</el-tag>
            <el-tag type="info" size="small" effect="plain" style="margin: 0 4px">重复 {{ row.duplicateRows }}</el-tag>
            <el-tag type="danger" size="small" effect="plain">失败 {{ row.failedRows }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="operator" label="操作人" width="100" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'FAILED' ? 'danger' : row.status === 'FINISHED' ? 'success' : 'warning'" size="small">
              {{ row.status === 'FAILED' ? '失败' : row.status === 'FINISHED' ? '完成' : '进行中' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="时间(UTC)" width="150">
          <template #default="{ row }">{{ fmtUtc(row.createdAt) }}</template>
        </el-table-column>
      </el-table>
    </div>

    <el-drawer v-model="detailVisible" title="导入逐行结果" size="62%">
      <template v-if="detail">
        <el-descriptions :column="3" border size="small" class="batch-desc">
          <el-descriptions-item label="批次号">{{ detail.batchNo }}</el-descriptions-item>
          <el-descriptions-item label="文件">{{ detail.fileName }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ detail.status }}</el-descriptions-item>
          <el-descriptions-item label="总行数">{{ detail.totalRows }}</el-descriptions-item>
          <el-descriptions-item label="成功/重复/失败">
            {{ detail.successRows }} / {{ detail.duplicateRows }} / {{ detail.failedRows }}
          </el-descriptions-item>
          <el-descriptions-item label="操作人">{{ detail.operator }}</el-descriptions-item>
          <el-descriptions-item label="说明" :span="3">{{ detail.message }}</el-descriptions-item>
        </el-descriptions>

        <el-radio-group v-model="rowFilter" size="small" style="margin: 12px 0">
          <el-radio-button value="">全部 ({{ detail.rows?.length || 0 }})</el-radio-button>
          <el-radio-button value="SUCCESS">成功 ({{ detail.successRows }})</el-radio-button>
          <el-radio-button value="DUPLICATE">重复 ({{ detail.duplicateRows }})</el-radio-button>
          <el-radio-button value="FAILED">失败 ({{ detail.failedRows }})</el-radio-button>
        </el-radio-group>

        <el-table :data="filteredRows" size="small" max-height="440" empty-text="该分类下没有记录">
          <el-table-column prop="rowNo" label="行号" width="70" />
          <el-table-column label="结果" width="100">
            <template #default="{ row }">
              <el-tag :type="rowStatusType(row.status)" size="small">{{ rowStatusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="rawLine" label="原始内容" min-width="280" show-overflow-tooltip class-name="mono-cell" />
          <el-table-column prop="errorMsg" label="说明 / 错误" min-width="200" show-overflow-tooltip />
        </el-table>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Document, Refresh } from '@element-plus/icons-vue'
import type { UploadFile } from 'element-plus'
import { api, type ImportResult } from '../api'
import { fmtUtc } from '../utils/format'

const uploading = ref(false)
const pickedFile = ref<File | null>(null)
const pickedName = ref('')
const batches = ref<ImportResult[]>([])

function onPick(file: UploadFile) {
  pickedFile.value = file.raw ?? null
  pickedName.value = file.name
}

async function submit() {
  if (!pickedFile.value) return
  uploading.value = true
  try {
    const form = new FormData()
    form.append('file', pickedFile.value)
    const result = await api.importSamples(form)
    ElMessage.success(result.message || '导入完成')
    pickedFile.value = null
    pickedName.value = ''
    await loadBatches()
    openBatch(result)
  } finally {
    uploading.value = false
  }
}

async function loadBatches() {
  batches.value = await api.recentImports()
}

const detailVisible = ref(false)
const detail = ref<ImportResult | null>(null)
const rowFilter = ref('')

async function openBatch(row: ImportResult) {
  detail.value = await api.importResult(row.batchNo)
  rowFilter.value = ''
  detailVisible.value = true
}
const filteredRows = computed(() =>
  rowFilter.value ? detail.value?.rows?.filter((r) => r.status === rowFilter.value) || [] : detail.value?.rows || []
)
function rowStatusType(s: string) {
  return { SUCCESS: 'success', DUPLICATE: 'info', FAILED: 'danger' }[s] || 'info'
}
function rowStatusLabel(s: string) {
  return { SUCCESS: '成功', DUPLICATE: '幂等重复', FAILED: '失败' }[s] || s
}

onMounted(loadBatches)
</script>

<style scoped>
.upload-card { padding: 20px; }
.upload-title { font-weight: 600; margin-bottom: 12px; }
.upload-tip { color: #909399; font-size: 12px; margin-top: 6px; }
.picked-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 14px;
  color: #52514e;
}
.picked-row .el-button { margin-left: auto; }
.table-toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.table-title { font-weight: 600; }
.batch-desc { margin-bottom: 4px; }
:deep(.mono-cell) { font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 12px; }
</style>
