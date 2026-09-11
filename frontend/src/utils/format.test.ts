import { describe, expect, it } from 'vitest'
import { ASSESSMENT_META, CHAIN_STATUS_META, METRIC_STATUS_META, fmtDuration } from './format'

describe('评估展示元数据', () => {
  it('四种结论均有中文标签与语义色', () => {
    expect(ASSESSMENT_META.PASS.label).toBe('合格')
    expect(ASSESSMENT_META.FAIL.label).toBe('不合格')
    expect(ASSESSMENT_META.NEEDS_REVIEW.label).toBe('需复核')
    expect(ASSESSMENT_META.UNASSESSABLE.label).toBe('不可评估')
    expect(ASSESSMENT_META.UNASSESSABLE.type).toBe('info')
    expect(ASSESSMENT_META.FAIL.type).toBe('danger')
  })

  it('链状态区分完整/断裂/未校验/无数据', () => {
    expect(CHAIN_STATUS_META.INTACT.type).toBe('success')
    expect(CHAIN_STATUS_META.BROKEN.type).toBe('danger')
    expect(CHAIN_STATUS_META.UNVERIFIED.type).toBe('warning')
    expect(CHAIN_STATUS_META.NO_DATA.type).toBe('info')
  })

  it('指标状态标签齐全', () => {
    for (const k of ['OK', 'WARN', 'BAD', 'INFO', 'NA']) {
      expect(METRIC_STATUS_META[k].label).toBeTruthy()
    }
  })

  it('时长格式化不会出现负数', () => {
    expect(fmtDuration(-5)).toBe('0秒')
    expect(fmtDuration(300)).toBe('5分0秒')
    expect(fmtDuration(3700)).toBe('1小时1分')
  })
})
