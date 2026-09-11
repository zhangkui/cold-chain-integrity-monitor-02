import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import AssessmentPanel from './AssessmentPanel.vue'
import type { AssessmentView } from '../api'

const latestAssessment = vi.fn()
const assessmentVersions = vi.fn()
const assessmentVersion = vi.fn()
const generateAssessment = vi.fn()

vi.mock('../api', () => ({
  api: {
    latestAssessment: (...a: unknown[]) => latestAssessment(...a),
    assessmentVersions: (...a: unknown[]) => assessmentVersions(...a),
    assessmentVersion: (...a: unknown[]) => assessmentVersion(...a),
    generateAssessment: (...a: unknown[]) => generateAssessment(...a)
  }
}))

// ElMessage 在 happy-dom 下直接走 DOM，不做额外 mock
function viewFixture(over: Partial<AssessmentView> = {}): AssessmentView {
  return {
    id: 101,
    boxId: 1,
    boxCode: 'BOX-1',
    batchNo: 'BATCH-1',
    version: 1,
    conclusion: 'PASS',
    conclusionLabel: '合格',
    chainStatus: 'INTACT',
    chainChecked: 12,
    summary: '评估合格：哈希链完整，有效冷链占比 100.0%',
    primaryRisks: [],
    metrics: [
      { key: 'rule', label: '温控规则', value: '[2, 8]', unit: '℃', status: 'OK', detail: '' },
      { key: 'chain', label: '哈希链', value: '完整', unit: null, status: 'OK', detail: '校验 12 环' },
      { key: 'validColdChain', label: '有效冷链时长', value: '1小时40分', unit: null, status: 'OK', detail: '占比 100.0%' }
    ],
    ruleSnapshot: {
      tempMin: 2,
      tempMax: 8,
      excursionSeconds: 300,
      offlineSeconds: 900,
      intervalMinSeconds: 240,
      intervalMaxSeconds: 360,
      failValidRatio: 0.8,
      warnValidRatio: 0.95,
      nodeDelayToleranceSeconds: 3600,
      ruleVersion: 'assessment-v1'
    },
    dataBoundary: {
      evaluatedAtUtc: '2026-09-10T00:00:00Z',
      samplesFromUtc: '2026-09-09T00:00:00Z',
      samplesToUtc: '2026-09-09T01:40:00Z',
      shipmentCoverageRatio: 1,
      sampleCount: 12
    },
    ruleVersion: 'assessment-v1',
    generatedBy: 'auditor-li',
    generatedAt: '2026-09-10T00:00:00Z',
    ...over
  }
}

function mountPanel(latest: unknown) {
  return mount(AssessmentPanel, {
    props: { boxId: 1, latest },
    global: { plugins: [ElementPlus] }
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  assessmentVersions.mockResolvedValue([])
})

describe('AssessmentPanel 评估面板', () => {
  it('从未评估：展示生成入口而非结论', () => {
    const w = mountPanel(null)
    expect(w.text()).toContain('生成评估')
    expect(w.text()).toContain('尚未评估')
  })

  it('激活时拉取最新评估：展示结论、生成时间、操作人与组成指标', async () => {
    const v = viewFixture()
    latestAssessment.mockResolvedValue(v)
    assessmentVersions.mockResolvedValue([{ id: 101, version: 1, conclusion: 'PASS' }])
    const w = mountPanel({ id: 101, version: 1, conclusion: 'PASS' })
    ;(w.vm as { activate: () => Promise<void> }).activate()
    await flushPromises()

    expect(latestAssessment).toHaveBeenCalledWith(1)
    expect(w.text()).toContain('合格')
    expect(w.text()).toContain('auditor-li')
    expect(w.text()).toContain('有效冷链时长')
    expect(w.text()).toContain('assessment-v1')
  })

  it('不可评估（422 已落库）：用错误携带的数据渲染，并回传 generated', async () => {
    const v = viewFixture({
      conclusion: 'UNASSESSABLE',
      conclusionLabel: '不可评估',
      chainStatus: 'UNVERIFIED',
      chainChecked: 0,
      summary: '不可评估：箱体无任何温度采样，评估数据不足',
      primaryRisks: [
        { code: 'NO_SAMPLES', severity: 'CRITICAL', message: '箱体无任何温度采样', blocker: true, refType: 'SAMPLE' }
      ],
      metrics: [{ key: 'coverage', label: '采样覆盖', value: '0 点', unit: null, status: 'BAD', detail: '无采样数据' }]
    })
    generateAssessment.mockRejectedValueOnce({ errorCode: 'DATA_INSUFFICIENT', data: v })
    latestAssessment.mockResolvedValue(v)
    const w = mountPanel(null)
    await w.find('button').trigger('click')
    await flushPromises()

    expect(w.text()).toContain('不可评估')
    expect(w.text()).toContain('箱体无任何温度采样')
    const emitted = w.emitted('generated')
    expect(emitted).toBeTruthy()
    expect((emitted![0][0] as AssessmentView).conclusion).toBe('UNASSESSABLE')
  })

  it('点击异常类风险 => 携带 anomalyId 跳转异常复核证据', async () => {
    const v = viewFixture({
      conclusion: 'FAIL',
      conclusionLabel: '不合格',
      primaryRisks: [
        {
          code: 'TEMP_EXCURSION',
          severity: 'CRITICAL',
          message: '连续超温（已确认）',
          blocker: true,
          refType: 'ANOMALY',
          anomalyId: 7
        }
      ]
    })
    latestAssessment.mockResolvedValue(v)
    const w = mountPanel({ id: 101, version: 1, conclusion: 'FAIL' })
    ;(w.vm as { activate: () => Promise<void> }).activate()
    await flushPromises()

    const row = w.find('.risk-row.clickable')
    expect(row.exists()).toBe(true)
    await row.trigger('click')
    expect(w.emitted('jumpAnomaly')).toEqual([[7]])
  })

  it('时间线类风险 => 触发 jumpTimeline', async () => {
    const v = viewFixture({
      conclusion: 'PASS',
      primaryRisks: [
        { code: 'DOOR_OPEN_UNMATCHED', severity: 'WARN', message: '开箱未关门', blocker: false, refType: 'TIMELINE' }
      ]
    })
    latestAssessment.mockResolvedValue(v)
    const w = mountPanel({ id: 101, version: 1, conclusion: 'PASS' })
    ;(w.vm as { activate: () => Promise<void> }).activate()
    await flushPromises()
    await w.find('.risk-row.clickable').trigger('click')
    expect(w.emitted('jumpTimeline')).toBeTruthy()
  })

  it('哈希链断裂场景：展示 BROKEN 与失配信息', async () => {
    const v = viewFixture({
      conclusion: 'NEEDS_REVIEW',
      conclusionLabel: '需复核',
      chainStatus: 'BROKEN',
      summary: '需人工复核：哈希链校验失败',
      primaryRisks: [
        { code: 'HASH_BROKEN', severity: 'CRITICAL', message: '断裂序号 seq=7', blocker: false, refType: 'SAMPLE' }
      ],
      metrics: [
        { key: 'chain', label: '哈希链', value: '断裂 6 处', unit: null, status: 'BAD', detail: '校验 12 环，首个失配 seq=7' }
      ]
    })
    latestAssessment.mockResolvedValue(v)
    const w = mountPanel({ id: 101, version: 1, conclusion: 'NEEDS_REVIEW' })
    ;(w.vm as { activate: () => Promise<void> }).activate()
    await flushPromises()
    expect(w.text()).toContain('需复核')
    expect(w.text()).toContain('seq=7')
  })

  it('切换历史版本调用版本接口', async () => {
    const v2 = viewFixture({ version: 2 })
    latestAssessment.mockResolvedValue(viewFixture())
    assessmentVersion.mockResolvedValue(v2)
    assessmentVersions.mockResolvedValue([
      { id: 102, version: 2, conclusion: 'PASS' },
      { id: 101, version: 1, conclusion: 'NEEDS_REVIEW' }
    ])
    const w = mountPanel({ id: 102, version: 2, conclusion: 'PASS' })
    ;(w.vm as { activate: () => Promise<void> }).activate()
    await flushPromises()
    await (w.vm as { onVersionChange: (v: number) => Promise<void> }).onVersionChange(2)
    await flushPromises()
    expect(assessmentVersion).toHaveBeenCalledWith(1, 2)
  })
})
