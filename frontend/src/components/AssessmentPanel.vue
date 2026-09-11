<template>
  <div class="assessment-panel">
    <div class="panel-toolbar">
      <el-button type="primary" size="small" :loading="generating" @click="generate">
        {{ latest ? '重新评估（生成新版本）' : '生成评估' }}
      </el-button>
      <el-select
        v-if="versions.length"
        :model-value="view?.version"
        size="small"
        style="width: 190px; margin-left: 8px"
        @change="onVersionChange"
      >
        <el-option
          v-for="v in versions"
          :key="v.id"
          :label="`v${v.version} · ${v.conclusionLabel} · ${fmtUtc(v.generatedAt)}`"
          :value="v.version"
        />
      </el-select>
      <span class="hint">历史版本仅追加，永不覆盖</span>
    </div>

    <el-empty v-if="!view && !generating" description="该箱体尚未评估，点击右上角生成" />

    <template v-else-if="view">
      <!-- 结论横幅 -->
      <el-alert
        :title="`${view.conclusionLabel}（${view.version ? 'v' + view.version + ' · ' : ''}规则 ${view.ruleVersion}）`"
        :type="bannerType"
        :closable="false"
        show-icon
        style="margin: 10px 16px"
      >
        <div>{{ view.summary }}</div>
        <div class="banner-meta">
          生成时间 {{ fmtUtc(view.generatedAt) }} UTC · 操作人 {{ view.generatedBy }}
          · 哈希链 <el-tag :type="chainMeta.type" size="small" effect="plain">{{ chainMeta.label }}</el-tag>
          <span v-if="view.chainChecked">（校验 {{ view.chainChecked }} 环）</span>
        </div>
      </el-alert>

      <!-- 主要风险，点击跳转证据 -->
      <div v-if="view.primaryRisks?.length" class="section">
        <div class="section-title">主要风险（{{ view.primaryRisks.length }}）</div>
        <div
          v-for="(r, i) in view.primaryRisks"
          :key="i"
          class="risk-row"
          :class="{ clickable: r.refType === 'ANOMALY' || r.refType === 'TIMELINE' }"
          @click="jumpRisk(r)"
        >
          <el-tag :type="riskTagType(r.severity)" size="small" effect="dark" style="min-width: 52px; text-align: center">
            {{ r.severity === 'CRITICAL' ? '严重' : r.severity === 'WARN' ? '警告' : '提示' }}
          </el-tag>
          <span class="risk-msg">{{ r.message }}</span>
          <el-tag v-if="r.refType === 'ANOMALY' && r.anomalyId" size="small" type="primary" effect="plain">
            异常 #{{ r.anomalyId }} →
          </el-tag>
          <el-tag v-else-if="r.refType === 'TIMELINE'" size="small" type="warning" effect="plain">
            时间线证据 →
          </el-tag>
          <el-tag v-else-if="r.refType === 'SAMPLE'" size="small" type="info" effect="plain">
            采样曲线 →
          </el-tag>
        </div>
      </div>

      <!-- 组成指标 -->
      <div class="section">
        <div class="section-title">组成指标</div>
        <el-table :data="view.metrics" size="small" border>
          <el-table-column prop="label" label="维度" width="130" />
          <el-table-column label="结果" min-width="200">
            <template #default="{ row }">
              <b>{{ row.value }}</b>
              <span v-if="row.unit" class="sub-text"> {{ row.unit }}</span>
              <div class="sub-text">{{ row.detail }}</div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-tag :type="(METRIC_STATUS_META[row.status]?.type) || 'info'" size="small">
                {{ METRIC_STATUS_META[row.status]?.label || row.status }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!-- 规则快照 + 数据时间边界 -->
      <el-row :gutter="12" class="section">
        <el-col :span="12">
          <div class="section-title">使用的温控规则</div>
          <div class="snapshot-box">
            <div>规则区间：<b>[{{ view.ruleSnapshot.tempMin }}, {{ view.ruleSnapshot.tempMax }}] ℃</b></div>
            <div>连续超温阈值：{{ view.ruleSnapshot.excursionSeconds }}s</div>
            <div>离线阈值：{{ view.ruleSnapshot.offlineSeconds }}s</div>
            <div>允许采样间隔：[{{ view.ruleSnapshot.intervalMinSeconds }}, {{ view.ruleSnapshot.intervalMaxSeconds }}]s</div>
            <div>有效冷链占比阈值：不合格 &lt; {{ pct(view.ruleSnapshot.failValidRatio) }}，关注 &lt; {{ pct(view.ruleSnapshot.warnValidRatio) }}</div>
          </div>
        </el-col>
        <el-col :span="12">
          <div class="section-title">数据时间边界（UTC）</div>
          <div class="snapshot-box">
            <div>评估时刻：{{ fmtUtc(view.dataBoundary.evaluatedAtUtc) }}</div>
            <div>采样窗口：{{ fmtUtc(view.dataBoundary.samplesFromUtc) }} ~ {{ fmtUtc(view.dataBoundary.samplesToUtc) }}（{{ view.dataBoundary.sampleCount }} 点）</div>
            <div>入库窗口：{{ fmtUtc(view.dataBoundary.receivedFromUtc) }} ~ {{ fmtUtc(view.dataBoundary.receivedToUtc) }}</div>
            <div>转运窗口：{{ fmtUtc(view.dataBoundary.shipmentStartUtc) }} ~ {{ fmtUtc(view.dataBoundary.shipmentEndUtc) }}</div>
            <div v-if="view.dataBoundary.shipmentCoverageRatio != null">
              转运期采样占比：{{ pct(view.dataBoundary.shipmentCoverageRatio) }}
            </div>
          </div>
        </el-col>
      </el-row>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  api,
  type AssessmentBadge,
  type AssessmentRisk,
  type AssessmentSummary,
  type AssessmentView
} from '../api'
import { ASSESSMENT_META, CHAIN_STATUS_META, METRIC_STATUS_META, fmtUtc } from '../utils/format'

const props = defineProps<{ boxId: number; latest: AssessmentBadge | null | undefined }>()
const emit = defineEmits<{
  (e: 'generated', view: AssessmentView): void
  (e: 'jumpAnomaly', anomalyId: number): void
  (e: 'jumpTimeline'): void
  (e: 'jumpChart'): void
}>()

const generating = ref(false)
const view = ref<AssessmentView | null>(null)
const versions = ref<AssessmentSummary[]>([])

const bannerType = computed(() => {
  const t = view.value ? ASSESSMENT_META[view.value.conclusion]?.type : 'info'
  return t === 'success' ? 'success' : t === 'danger' ? 'error' : (t || 'info') as 'warning' | 'info'
})
const chainMeta = computed(() => CHAIN_STATUS_META[view.value?.chainStatus || 'UNVERIFIED'] || CHAIN_STATUS_META.UNVERIFIED)

function pct(v?: number | null) {
  if (v == null) return '—'
  return `${(v * 100).toFixed(1)}%`
}
function riskTagType(s: string) {
  return s === 'CRITICAL' ? 'danger' : s === 'WARN' ? 'warning' : 'info'
}

async function loadVersion(version?: number) {
  if (version == null) {
    view.value = await api.latestAssessment(props.boxId)
  } else {
    view.value = await api.assessmentVersion(props.boxId, version)
  }
  versions.value = await api.assessmentVersions(props.boxId)
}

/** 抽屉切到评估页时调用：已有历史则自动拉取最新，无历史则展示“生成评估”空态 */
async function activate() {
  if (view.value) return
  if (props.latest) {
    try {
      await loadVersion()
    } catch {
      view.value = null
    }
  }
}

async function generate() {
  generating.value = true
  try {
    let result: AssessmentView
    try {
      result = await api.generateAssessment(props.boxId)
    } catch (err) {
      // 422 数据不足：不可评估版本已持久化，后端把结果放在错误对象 data 上
      const e = err as { errorCode?: string; data?: AssessmentView }
      if (e?.errorCode === 'DATA_INSUFFICIENT' && e.data) {
        result = e.data
        ElMessage.warning('评估结论为「不可评估」，结果已留存为新版本')
      } else {
        throw err
      }
    }
    // 以服务端返回（或 422 携带的已落库结果）为准直接渲染，再同步历史版本列表
    view.value = result
    versions.value = await api.assessmentVersions(props.boxId)
    emit('generated', result)
  } catch (err) {
    // generateAssessment 为静默调用：422 已在内部处理；404/500 在此按错误信息提示
    const e = err as { errorCode?: string; message?: string }
    if (e?.errorCode !== 'DATA_INSUFFICIENT') {
      ElMessage.error(e?.message || '评估生成失败，请稍后重试')
    }
  } finally {
    generating.value = false
  }
}

async function onVersionChange(version: number) {
  await loadVersion(version)
}

// 抽屉复用（destroy-on-close 之外的兜底）：箱体切换时重置
watch(
  () => props.boxId,
  () => {
    view.value = null
    versions.value = []
  }
)

function jumpRisk(r: AssessmentRisk) {
  if (r.refType === 'ANOMALY' && r.anomalyId) emit('jumpAnomaly', r.anomalyId)
  else if (r.refType === 'TIMELINE') emit('jumpTimeline')
  else if (r.refType === 'SAMPLE') emit('jumpChart')
}

defineExpose({ refresh: () => loadVersion(), activate })
</script>

<style scoped>
.assessment-panel { padding-bottom: 16px; }
.panel-toolbar {
  display: flex;
  align-items: center;
  padding: 10px 16px 0;
}
.hint { margin-left: auto; color: #909399; font-size: 12px; }
.banner-meta {
  margin-top: 6px;
  font-size: 12px;
  opacity: 0.85;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.section { margin: 14px 16px 0; }
.section-title {
  font-weight: 600;
  font-size: 13px;
  color: #303133;
  margin-bottom: 8px;
}
.risk-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 4px;
  font-size: 13px;
  color: #41424d;
}
.risk-row.clickable { cursor: pointer; }
.risk-row.clickable:hover { background: #f0f5ff; }
.risk-msg { flex: 1; }
.sub-text { color: #909399; font-size: 12px; }
.snapshot-box {
  background: #f6f8fa;
  border-radius: 6px;
  padding: 10px 12px;
  font-size: 12.5px;
  color: #41424d;
  line-height: 1.9;
}
</style>
