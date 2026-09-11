<template>
  <div class="page-container">
    <div class="filter-bar">
      <el-form :inline="true" @submit.prevent>
        <el-form-item label="箱号">
          <el-input v-model="boxCodeInput" placeholder="按箱号查询后带入 boxId" clearable style="width: 180px" @keyup.enter="load" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="filters.type" placeholder="全部类型" clearable style="width: 150px">
            <el-option v-for="(meta, key) in ANOMALY_META" :key="key" :label="meta.label" :value="key" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="filters.status" placeholder="全部状态" clearable style="width: 140px">
            <el-option label="待复核" value="OPEN" />
            <el-option label="已确认" value="CONFIRMED" />
            <el-option label="已驳回" value="REJECTED" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="load">查询</el-button>
          <el-button :icon="RefreshLeft" @click="reset">重置</el-button>
        </el-form-item>
      </el-form>
    </div>

    <div class="table-card">
      <el-alert
        v-if="highlightId"
        :title="`已从评估证据定位到异常 #${highlightId}`"
        type="info"
        show-icon
        :closable="true"
        style="margin-bottom: 10px"
        @close="highlightId = null"
      />
      <el-table
        v-loading="loading"
        :data="anomalies"
        :row-class-name="rowClassName"
        stripe
        empty-text="暂无异常，冷链状态良好"
      >
        <el-table-column label="类型" width="130">
          <template #default="{ row }">
            <el-tag :type="ANOMALY_META[row.type]?.type || 'info'" effect="dark" size="small">
              {{ ANOMALY_META[row.type]?.label || row.type }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="boxCode" label="箱号" width="130" />
        <el-table-column prop="batchNo" label="批次" width="170" />
        <el-table-column prop="deviceCode" label="设备" width="110" />
        <el-table-column label="开始 (UTC)" width="150">
          <template #default="{ row }">{{ fmtUtc(row.startTimeUtc) }}</template>
        </el-table-column>
        <el-table-column label="持续" width="100">
          <template #default="{ row }">{{ fmtDuration(row.durationSeconds) }}</template>
        </el-table-column>
        <el-table-column prop="peakTemp" label="峰值℃" width="80">
          <template #default="{ row }">{{ row.peakTemp ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="description" label="描述" min-width="260" show-overflow-tooltip />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="STATUS_META[row.status]?.type" size="small">
              {{ STATUS_META[row.status]?.label || row.status }}
            </el-tag>
            <div v-if="row.confirmedBy" class="sub-text">{{ row.confirmedBy }}</div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button link type="success" size="small" :disabled="row.status === 'CONFIRMED'" @click="openReview(row, 'CONFIRM')">
              确认
            </el-button>
            <el-button link type="info" size="small" :disabled="row.status === 'REJECTED'" @click="openReview(row, 'REJECT')">
              驳回
            </el-button>
            <el-button link type="warning" size="small" :disabled="row.status === 'OPEN'" @click="openReview(row, 'REOPEN')">
              重开
            </el-button>
            <el-button link type="primary" size="small" @click="openEvidence(row)">证据版本</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 复核对话框 -->
    <el-dialog v-model="reviewVisible" :title="reviewTitle" width="480px">
      <el-form label-position="top">
        <el-form-item v-if="current" label="异常描述">
          <div class="review-desc">{{ current.description }}</div>
        </el-form-item>
        <el-form-item label="复核意见">
          <el-input v-model="reviewComment" type="textarea" :rows="3" placeholder="请填写复核依据（将记入审计日志与复核历史）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reviewVisible = false">取消</el-button>
        <el-button :type="reviewAction === 'CONFIRM' ? 'success' : reviewAction === 'REJECT' ? 'info' : 'warning'" @click="submitReview">
          提交
        </el-button>
      </template>
    </el-dialog>

    <!-- 证据版本对话框 -->
    <el-dialog v-model="evidenceVisible" title="证据附件版本" width="640px">
      <div v-if="current" class="evidence-head">
        <el-tag :type="ANOMALY_META[current.type]?.type" size="small">
          {{ ANOMALY_META[current.type]?.label }}
        </el-tag>
        <span>{{ current.boxCode }} · {{ fmtUtc(current.startTimeUtc) }} UTC</span>
      </div>

      <el-upload
        class="evidence-upload"
        :auto-upload="false"
        :on-change="onFileChange"
        :show-file-list="true"
        :limit="1"
      >
        <el-button :icon="Upload">选择证据文件（照片/PDF/记录）</el-button>
      </el-upload>
      <div class="evidence-note-row">
        <el-input v-model="evidenceNote" size="small" placeholder="版本说明（可选）" style="margin-right: 8px" />
        <el-button type="primary" size="small" :loading="uploading" @click="submitEvidence">上传为新版本</el-button>
      </div>
      <el-alert type="info" :closable="false" style="margin: 10px 0">
        附件按版本保存，旧版本永不覆盖；每个文件记录 SHA-256 指纹。
      </el-alert>

      <el-table :data="evidence" size="small" empty-text="暂无证据附件">
        <el-table-column prop="version" label="版本" width="70">
          <template #default="{ row }">v{{ row.version }}</template>
        </el-table-column>
        <el-table-column prop="fileName" label="文件名" min-width="160" show-overflow-tooltip />
        <el-table-column label="大小" width="90">
          <template #default="{ row }">{{ (row.sizeBytes / 1024).toFixed(1) }} KB</template>
        </el-table-column>
        <el-table-column label="SHA-256" min-width="180">
          <template #default="{ row }"><span class="mono sub-text">{{ row.fileHash.slice(0, 20) }}…</span></template>
        </el-table-column>
        <el-table-column prop="uploadedBy" label="上传人" width="90" />
        <el-table-column label="时间(UTC)" width="140">
          <template #default="{ row }">{{ fmtUtc(row.createdAt) }}</template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { RefreshLeft, Search, Upload } from '@element-plus/icons-vue'
import type { UploadFile } from 'element-plus'
import { api, type AnomalyView, type BoxListItem, type Evidence } from '../api'
import { ANOMALY_META, STATUS_META, fmtDuration, fmtUtc } from '../utils/format'

const route = useRoute()
const loading = ref(false)
const anomalies = ref<AnomalyView[]>([])
const boxCodeInput = ref((route.query.boxCode as string) || '')
const filters = ref({ type: '', status: '' })
const highlightId = ref<number | null>(route.query.anomalyId ? Number(route.query.anomalyId) : null)

function rowClassName({ row }: { row: AnomalyView }) {
  return highlightId.value === row.id ? 'anomaly-row-highlight' : ''
}

async function load() {
  loading.value = true
  try {
    let boxId: number | undefined
    if (boxCodeInput.value.trim()) {
      const boxes = await api.listBoxes({ boxCode: boxCodeInput.value.trim() })
      if (!boxes.length) {
        ElMessage.warning('未找到该箱号')
        anomalies.value = []
        return
      }
      boxId = (boxes[0] as BoxListItem).id
    }
    anomalies.value = await api.listAnomalies({
      boxId,
      type: filters.value.type || undefined,
      status: filters.value.status || undefined
    })
    if (highlightId.value && !anomalies.value.some((a) => a.id === highlightId.value)) {
      // 被定位的异常可能被状态筛选隐藏，给出提示但保留 boxCode 过滤
      ElMessage.info('未在当前筛选结果中找到该异常，可清空类型/状态筛选查看')
    }
  } finally {
    loading.value = false
  }
}
function reset() {
  boxCodeInput.value = ''
  filters.value = { type: '', status: '' }
  load()
}

// ---------------- 复核 ----------------
const reviewVisible = ref(false)
const current = ref<AnomalyView | null>(null)
const reviewAction = ref<'CONFIRM' | 'REJECT' | 'REOPEN'>('CONFIRM')
const reviewComment = ref('')
const reviewTitle = computed(() => ({
  CONFIRM: '确认异常',
  REJECT: '驳回异常',
  REOPEN: '重新打开异常'
}[reviewAction.value]))

function openReview(row: AnomalyView, action: 'CONFIRM' | 'REJECT' | 'REOPEN') {
  current.value = row
  reviewAction.value = action
  reviewComment.value = ''
  reviewVisible.value = true
}
async function submitReview() {
  if (!current.value) return
  await api.reviewAnomaly(current.value.id, { action: reviewAction.value, comment: reviewComment.value })
  ElMessage.success('复核已提交并写入审计日志')
  reviewVisible.value = false
  load()
}

// ---------------- 证据 ----------------
const evidenceVisible = ref(false)
const evidence = ref<Evidence[]>([])
const evidenceNote = ref('')
const uploading = ref(false)
let pickedFile: File | null = null

function onFileChange(file: UploadFile) {
  pickedFile = file.raw ?? null
}
async function openEvidence(row: AnomalyView) {
  current.value = row
  evidenceVisible.value = true
  evidenceNote.value = ''
  pickedFile = null
  evidence.value = await api.listEvidence(row.id)
}
async function submitEvidence() {
  if (!current.value || !pickedFile) {
    ElMessage.warning('请先选择文件')
    return
  }
  uploading.value = true
  try {
    const form = new FormData()
    form.append('file', pickedFile)
    if (evidenceNote.value) form.append('note', evidenceNote.value)
    const saved = await api.uploadEvidence(current.value.id, form)
    ElMessage.success(`已保存为 v${saved.version}`)
    evidenceNote.value = ''
    pickedFile = null
    evidence.value = await api.listEvidence(current.value.id)
  } finally {
    uploading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.sub-text { color: #909399; font-size: 12px; }
:deep(.anomaly-row-highlight) > td.el-table__cell {
  background-color: #fdf6ec !important;
}
.review-desc {
  background: #f6f8fa;
  border-radius: 6px;
  padding: 8px 12px;
  color: #52514e;
  font-size: 13px;
  width: 100%;
}
.evidence-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
  color: #52514e;
  font-size: 13px;
}
.evidence-upload { margin-bottom: 8px; }
.evidence-note-row { display: flex; align-items: center; }
</style>
