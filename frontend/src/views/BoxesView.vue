<template>
  <div class="page-container">
    <div class="filter-bar">
      <el-form :inline="true" @submit.prevent>
        <el-form-item label="箱号">
          <el-input v-model="filters.boxCode" placeholder="如 BOX-DRY-01" clearable style="width: 170px" @keyup.enter="load" />
        </el-form-item>
        <el-form-item label="批次">
          <el-input v-model="filters.batchNo" placeholder="批次号" clearable style="width: 180px" @keyup.enter="load" />
        </el-form-item>
        <el-form-item label="温度区间">
          <el-input-number v-model="filters.tempFrom" :controls="false" placeholder="从 ℃" style="width: 100px" />
          <span style="margin: 0 4px">~</span>
          <el-input-number v-model="filters.tempTo" :controls="false" placeholder="到 ℃" style="width: 100px" />
        </el-form-item>
        <el-form-item label="转运节点">
          <el-input v-model="filters.nodeCode" placeholder="如 N-JN-02" clearable style="width: 150px" @keyup.enter="load" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="filters.status" placeholder="全部" clearable style="width: 130px">
            <el-option label="运输中" value="IN_TRANSIT" />
            <el-option label="已送达" value="DELIVERED" />
            <el-option label="异常" value="EXCEPTION" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="load">查询</el-button>
          <el-button :icon="RefreshLeft" @click="reset">重置</el-button>
        </el-form-item>
      </el-form>
    </div>

    <div class="table-card">
      <div class="table-toolbar">
        <span class="table-title">冷链箱（{{ boxes.length }}）</span>
        <el-button-group>
          <el-button :icon="Connection" @click="verifyAll">校验哈希链</el-button>
          <el-button :icon="Refresh" @click="recomputeAll">重算异常</el-button>
        </el-button-group>
      </div>
      <el-table v-loading="loading" :data="boxes" stripe style="width: 100%" empty-text="没有符合条件的冷链箱">
        <el-table-column prop="boxCode" label="箱号" width="140">
          <template #default="{ row }">
            <el-link type="primary" @click="openChart(row)">{{ row.boxCode }}</el-link>
          </template>
        </el-table-column>
        <el-table-column prop="batchNo" label="批次" width="170" />
        <el-table-column prop="specimenType" label="样本类型" width="110" />
        <el-table-column label="采集设备" width="170">
          <template #default="{ row }">
            <div>{{ row.deviceCode || '—' }}</div>
            <div class="sub-text">{{ row.timezone }}</div>
          </template>
        </el-table-column>
        <el-table-column label="规则区间(℃)" width="120">
          <template #default="{ row }">
            <span class="mono">[{{ row.tempMin }}, {{ row.tempMax }}]</span>
          </template>
        </el-table-column>
        <el-table-column label="样本/时长" width="100">
          <template #default="{ row }">
            <div>{{ row.sampleCount }} 点</div>
            <div class="sub-text">{{ fmtDuration(row.coveredSeconds) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="有效冷链时长" width="120">
          <template #default="{ row }">
            <b :class="validClass(row)">{{ fmtDuration(row.validColdChainSeconds) }}</b>
          </template>
        </el-table-column>
        <el-table-column label="超温/离线" width="130">
          <template #default="{ row }">
            <el-tag v-if="row.excursionSeconds" type="danger" size="small" effect="plain">
              超温 {{ fmtDuration(row.excursionSeconds) }}
            </el-tag>
            <el-tag v-if="row.offlineSeconds" type="warning" size="small" effect="plain" style="margin-left: 4px">
              离线 {{ fmtDuration(row.offlineSeconds) }}
            </el-tag>
            <span v-if="!row.excursionSeconds && !row.offlineSeconds" class="sub-text">无</span>
          </template>
        </el-table-column>
        <el-table-column label="异常" width="120">
          <template #default="{ row }">
            <el-badge :value="row.openAnomalyCount" :hidden="!row.openAnomalyCount" type="danger">
              <el-tag :type="row.totalAnomalyCount ? 'info' : 'success'" size="small" effect="plain">
                {{ row.totalAnomalyCount ? `${row.totalAnomalyCount} 条` : '正常' }}
              </el-tag>
            </el-badge>
          </template>
        </el-table-column>
        <el-table-column label="综合评估" width="210">
          <template #default="{ row }">
            <div v-if="row.latestAssessment" class="assess-cell" @click="openAssessment(row)">
              <el-tag :type="assessmentTagType(row.latestAssessment.conclusion)" size="small" effect="dark">
                {{ row.latestAssessment.conclusionLabel }}
              </el-tag>
              <span class="sub-text">v{{ row.latestAssessment.version }} · {{ fmtUtc(row.latestAssessment.generatedAt) }}</span>
              <div class="assess-risk" :title="row.latestAssessment.summary">
                {{ row.latestAssessment.topRisk?.message || row.latestAssessment.summary }}
              </div>
            </div>
            <el-button v-else link type="primary" size="small" @click="openAssessment(row)">生成评估</el-button>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="boxStatusType(row.status)" size="small">{{ boxStatusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openChart(row)">温度曲线</el-button>
            <el-button link type="primary" size="small" @click="openTimeline(row)">转运时间线</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 曲线 / 时间线抽屉 -->
    <el-drawer v-model="drawerVisible" :size="drawerSize" :title="drawerTitle" destroy-on-close>
      <el-tabs v-model="activeTab">
        <el-tab-pane label="温度曲线" name="chart">
          <div v-if="currentBox?.latestAssessment" class="assess-strip" @click="activeTab = 'assessment'">
            <el-tag :type="assessmentTagType(currentBox.latestAssessment.conclusion)" size="small" effect="dark">
              {{ currentBox.latestAssessment.conclusionLabel }}
            </el-tag>
            <span class="sub-text">最近评估 v{{ currentBox.latestAssessment.version }} · {{ fmtUtc(currentBox.latestAssessment.generatedAt) }} UTC</span>
            <span class="assess-strip-risk">{{ currentBox.latestAssessment.topRisk?.message || currentBox.latestAssessment.summary }}</span>
            <el-link type="primary" :underline="false" style="margin-left: auto">查看评估详情 →</el-link>
          </div>
          <div v-else-if="currentBox" class="assess-strip assess-strip-empty">
            <span class="sub-text">该箱体尚未生成综合评估</span>
            <el-button link type="primary" size="small" @click="activeTab = 'assessment'">立即生成 →</el-button>
          </div>
          <div class="chart-toolbar">
            <el-radio-group v-model="tzMode" size="small">
              <el-radio-button value="UTC">UTC</el-radio-button>
              <el-radio-button value="LOCAL">设备本地时间</el-radio-button>
            </el-radio-group>
            <el-date-picker
              v-model="dateRange"
              type="datetimerange"
              size="small"
              range-separator="至"
              start-placeholder="开始"
              end-placeholder="结束"
              value-format="YYYY-MM-DDTHH:mm:ss"
              style="margin-left: 12px; width: 380px"
            />
            <el-button size="small" style="margin-left: 8px" @click="dateRange = null">全部时段</el-button>
            <span class="chart-hint">共 {{ points.length }} 个采样点，跨日曲线可横向缩放浏览</span>
          </div>

          <div v-if="currentBox" class="chart-card" style="margin: 12px 16px">
            <TemperatureChart
              :points="points"
              :temp-min="Number(currentBox.tempMin)"
              :temp-max="Number(currentBox.tempMax)"
              :anomalies="drawerAnomalies"
              :tz-mode="tzMode"
            />
          </div>

          <div v-if="points.length" class="hash-strip">
            <el-icon><Lock /></el-icon>
            <span>
              哈希链：{{ points.length }} 环，
              末环 chainHash =
              <span class="mono hash-text">{{ points[points.length - 1].chainHash.slice(0, 24) }}…</span>
            </span>
          </div>
        </el-tab-pane>

        <el-tab-pane label="转运时间线" name="timeline">
          <div v-if="timeline" class="timeline-wrap">
            <div class="timeline-meta" v-if="timeline.shipmentNo">转运单：{{ timeline.shipmentNo }}</div>
            <el-empty v-if="!timeline.items.length" description="暂无转运节点、开箱事件与异常" />
            <el-timeline v-else>
              <el-timeline-item
                v-for="(item, idx) in timeline.items"
                :key="idx"
                :type="dotType(item)"
                :hollow="item.kind === 'NODE'"
                :timestamp="fmtUtc(item.timeUtc) + ' UTC'"
                placement="top"
              >
                <el-tag :type="kindTag(item.kind)" size="small" effect="plain" style="margin-right: 8px">
                  {{ kindLabel(item.kind) }}
                </el-tag>
                <b>{{ item.title }}</b>
                <div class="sub-text">{{ item.detail }}</div>
              </el-timeline-item>
            </el-timeline>
          </div>
        </el-tab-pane>

        <el-tab-pane label="综合评估" name="assessment">
          <AssessmentPanel
            v-if="currentBox"
            ref="assessmentPanelRef"
            :box-id="currentBox.id"
            :latest="currentBox.latestAssessment"
            @generated="onAssessmentGenerated"
            @jump-anomaly="goAnomaly"
            @jump-timeline="activeTab = 'timeline'"
            @jump-chart="activeTab = 'chart'"
          />
        </el-tab-pane>
      </el-tabs>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Connection,
  Lock,
  Refresh,
  RefreshLeft,
  Search
} from '@element-plus/icons-vue'
import {
  api,
  type AnomalyView,
  type AssessmentView,
  type BoxListItem,
  type SamplePoint,
  type TimelineResponse
} from '../api'
import TemperatureChart from '../components/TemperatureChart.vue'
import AssessmentPanel from '../components/AssessmentPanel.vue'
import { ANOMALY_META, ASSESSMENT_META, fmtDuration, fmtUtc } from '../utils/format'

const router = useRouter()

const loading = ref(false)
const boxes = ref<BoxListItem[]>([])
const filters = ref({ boxCode: '', batchNo: '', tempFrom: undefined as number | undefined, tempTo: undefined as number | undefined, nodeCode: '', status: '' })

async function load() {
  loading.value = true
  try {
    boxes.value = await api.listBoxes({
      boxCode: filters.value.boxCode || undefined,
      batchNo: filters.value.batchNo || undefined,
      tempFrom: filters.value.tempFrom,
      tempTo: filters.value.tempTo,
      nodeCode: filters.value.nodeCode || undefined,
      status: filters.value.status || undefined
    })
  } finally {
    loading.value = false
  }
}
function reset() {
  filters.value = { boxCode: '', batchNo: '', tempFrom: undefined, tempTo: undefined, nodeCode: '', status: '' }
  load()
}

async function verifyAll() {
  const res = (await api.verifyChain()) as { allIntact: boolean }
  if (res.allIntact) ElMessage.success('全部设备哈希链完整，未发现篡改')
  else {
    ElMessage.error('发现哈希链断裂！已生成 HASH_BROKEN 异常，请前往异常复核页查看')
    load()
  }
}
async function recomputeAll() {
  await ElMessageBox.confirm('全量重算将删除所有 OPEN 异常后重新识别，已确认/驳回的异常会保留。确认执行？', '重算异常', {
    type: 'warning'
  })
  const res = (await api.recompute()) as { newAnomalies: number }
  ElMessage.success(`重算完成，新增 OPEN 异常 ${res.newAnomalies} 条`)
  load()
}

// ---------------- 抽屉 ----------------
const drawerVisible = ref(false)
const activeTab = ref('chart')
const drawerSize = ref('72%')
const currentBox = ref<BoxListItem | null>(null)
const points = ref<SamplePoint[]>([])
const drawerAnomalies = ref<AnomalyView[]>([])
const timeline = ref<TimelineResponse | null>(null)
const tzMode = ref<'UTC' | 'LOCAL'>('UTC')
const dateRange = ref<[string, string] | null>(null)

const drawerTitle = computed(() =>
  currentBox.value ? `${currentBox.value.boxCode} · ${currentBox.value.batchNo}` : ''
)

function openChart(row: BoxListItem) {
  currentBox.value = row
  activeTab.value = 'chart'
  drawerVisible.value = true
}
function openTimeline(row: BoxListItem) {
  currentBox.value = row
  activeTab.value = 'timeline'
  drawerVisible.value = true
}
function openAssessment(row: BoxListItem) {
  currentBox.value = row
  activeTab.value = 'assessment'
  drawerVisible.value = true
}

const assessmentPanelRef = ref<InstanceType<typeof AssessmentPanel> | null>(null)
watch(activeTab, (tab) => {
  if (tab === 'assessment') {
    nextTick(() => assessmentPanelRef.value?.activate?.())
  }
})

function onAssessmentGenerated(view: AssessmentView) {
  ElMessage.success(`评估已生成（v${view.version} · ${view.conclusionLabel}），历史版本均已保留`)
  // 立即更新抽屉当前行徽标，并刷新列表持久化后的生成时间/风险
  if (currentBox.value && currentBox.value.id === view.boxId) {
    currentBox.value = {
      ...currentBox.value,
      latestAssessment: {
        id: view.id,
        version: view.version,
        conclusion: view.conclusion,
        conclusionLabel: view.conclusionLabel,
        chainStatus: view.chainStatus,
        summary: view.summary,
        generatedAt: view.generatedAt,
        topRisk: view.primaryRisks?.[0] ?? null
      }
    }
  }
  load()
}

function assessmentTagType(c: string) {
  return ASSESSMENT_META[c]?.type || 'info'
}

/** 从评估风险跳转到异常复核页（带箱号过滤） */
function goAnomaly(anomalyId?: number) {
  drawerVisible.value = false
  router.push({
    path: '/anomalies',
    query: { boxCode: currentBox.value?.boxCode, anomalyId: anomalyId ? String(anomalyId) : undefined }
  })
}

async function loadDetail() {
  if (!currentBox.value) return
  const id = currentBox.value.id
  const [p, a] = await Promise.all([
    api.boxSamples(id, {
      from: dateRange.value?.[0],
      to: dateRange.value?.[1],
      timezone: tzMode.value
    }),
    api.listAnomalies({ boxId: id })
  ])
  points.value = p
  drawerAnomalies.value = a
  timeline.value = await api.boxTimeline(id)
}

watch([drawerVisible, currentBox], (v) => {
  if (v[0]) {
    loadDetail()
    if (activeTab.value === 'assessment') {
      nextTick(() => assessmentPanelRef.value?.activate?.())
    }
  } else {
    points.value = []
    drawerAnomalies.value = []
    timeline.value = null
  }
})
watch([tzMode, dateRange], () => {
  if (drawerVisible.value && currentBox.value) {
    api
      .boxSamples(currentBox.value.id, {
        from: dateRange.value?.[0],
        to: dateRange.value?.[1],
        timezone: tzMode.value
      })
      .then((p) => (points.value = p))
  }
})

function validClass(row: BoxListItem) {
  if (!row.coveredSeconds) return 'sub-text'
  const ratio = row.validColdChainSeconds! / row.coveredSeconds
  return ratio >= 0.95 ? 'valid-good' : ratio >= 0.8 ? 'valid-warn' : 'valid-bad'
}
function boxStatusType(s: string) {
  return { IN_TRANSIT: 'primary', DELIVERED: 'success', EXCEPTION: 'danger' }[s] || 'info'
}
function boxStatusLabel(s: string) {
  return { IN_TRANSIT: '运输中', DELIVERED: '已送达', EXCEPTION: '异常' }[s] || s
}
function kindLabel(k: string) {
  return { NODE: '节点', EVENT: '事件', ANOMALY: '异常' }[k] || k
}
function kindTag(k: string) {
  return { NODE: 'primary', EVENT: 'warning', ANOMALY: 'danger' }[k] || 'info'
}
function dotType(item: TimelineResponse['items'][number]) {
  if (item.kind === 'NODE') return 'primary'
  if (item.kind === 'EVENT') return 'warning'
  const meta = item.type ? ANOMALY_META[item.type] : undefined
  if (item.status === 'CONFIRMED') return 'danger'
  return meta?.type === 'warning' ? 'warning' : meta?.type === 'info' ? 'info' : 'danger'
}

onMounted(load)
</script>

<style scoped>
.table-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.table-title { font-weight: 600; }
.sub-text { color: #909399; font-size: 12px; line-height: 1.5; }
.valid-good { color: #1baf7a; }
.valid-warn { color: #c98500; }
.valid-bad { color: #e34948; }
.chart-toolbar {
  display: flex;
  align-items: center;
  padding: 0 16px;
}
.chart-hint { margin-left: auto; color: #909399; font-size: 12px; }
.hash-strip {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 12px 16px;
  padding: 10px 14px;
  background: #f6f8fa;
  border-radius: 6px;
  color: #52514e;
  font-size: 12px;
}
.hash-text { color: #1c5cab; }
.timeline-wrap { padding: 8px 24px; }
.timeline-meta { color: #606266; margin-bottom: 16px; font-size: 13px; }
.assess-cell { cursor: pointer; line-height: 1.6; }
.assess-cell:hover .assess-risk { color: #1c5cab; }
.assess-risk {
  color: #909399;
  font-size: 12px;
  max-width: 190px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.assess-strip {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 10px 16px 0;
  padding: 8px 12px;
  background: #f6f8fa;
  border-radius: 6px;
  cursor: pointer;
  font-size: 12.5px;
}
.assess-strip-empty { cursor: default; }
.assess-strip-risk {
  color: #606266;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
