<template>
  <div ref="chartEl" class="temp-chart"></div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import type { AnomalyView, SamplePoint } from '../api'
import { parseUtc, parseWallClock } from '../utils/format'

const props = defineProps<{
  points: SamplePoint[]
  tempMin: number
  tempMax: number
  anomalies: AnomalyView[]
  tzMode: 'UTC' | 'LOCAL'
}>()

const chartEl = ref<HTMLElement>()
let chart: echarts.ECharts | null = null

function timeOf(p: SamplePoint): Date | null {
  return props.tzMode === 'LOCAL' ? parseWallClock(p.timeLocal) : parseUtc(p.timeUtc)
}

/** 异常只有 UTC 时间：LOCAL 模式下取出其 UTC 分量重建成“墙上时钟”日期，与采样点对齐 */
function anomalyTime(iso: string): number | undefined {
  const d = parseUtc(iso)
  if (!d) return undefined
  if (props.tzMode === 'UTC') return d.getTime()
  return new Date(
    d.getUTCFullYear(),
    d.getUTCMonth(),
    d.getUTCDate(),
    d.getUTCHours(),
    d.getUTCMinutes(),
    d.getUTCSeconds(),
    d.getUTCMilliseconds()
  ).getTime()
}

function render() {
  if (!chart) return
  if (props.points.length === 0) {
    chart.clear()
    chart.setOption({
      title: {
        text: '暂无采样数据',
        subtext: '该箱体在所选时间范围内没有温度采样',
        left: 'center',
        top: 'center',
        textStyle: { color: '#909399', fontSize: 14, fontWeight: 'normal' },
        subtextStyle: { color: '#b1b3b8', fontSize: 12 }
      }
    })
    return
  }

  const seriesData = props.points
    .map((p) => {
      const t = timeOf(p)
      return t ? ([t, p.temperature, p] as [Date, number, SamplePoint]) : null
    })
    .filter(Boolean) as [Date, number, SamplePoint][]

  // 越限点（状态色叠加散点，颜色不是唯一编码：tooltip 同时标注越限）
  const outOfRange = seriesData
    .filter(([, v]) => v < props.tempMin || v > props.tempMax)
    .map(([t, v, p]) => [t, v, p] as [Date, number, SamplePoint])

  // 异常区段：超温=红，离线=琥珀，间隔=蓝灰，哈希=紫
  const bandColor: Record<string, string> = {
    TEMP_EXCURSION: 'rgba(227,73,72,0.12)',
    OFFLINE: 'rgba(237,161,0,0.14)',
    INTERVAL: 'rgba(42,120,214,0.08)',
    HASH_BROKEN: 'rgba(74,58,167,0.15)'
  }
  const markAreas = props.anomalies
    .filter((a) => ['TEMP_EXCURSION', 'OFFLINE', 'INTERVAL', 'HASH_BROKEN'].includes(a.type))
    .map((a) => [
      {
        xAxis: anomalyTime(a.startTimeUtc),
        itemStyle: { color: bandColor[a.type] },
        label: {
          show: a.type === 'TEMP_EXCURSION' || a.type === 'OFFLINE',
          formatter: a.type === 'TEMP_EXCURSION' ? '超温' : '离线',
          color: a.type === 'TEMP_EXCURSION' ? '#c23a39' : '#a87400',
          fontSize: 10,
          position: 'insideTop'
        }
      },
      { xAxis: anomalyTime(a.endTimeUtc) }
    ])

  const allTimes = seriesData.map((d) => d[0].getTime())
  const padMs = Math.max(60_000, Math.round((Math.max(...allTimes) - Math.min(...allTimes)) * 0.01))

  chart.setOption({
    animationDuration: 200,
    grid: { left: 56, right: 24, top: 28, bottom: 48 },
    tooltip: {
      trigger: 'axis',
      axisPointer: {
        type: 'cross',
        lineStyle: { color: '#9aa5b1' },
        label: { backgroundColor: '#52514e' }
      },
      formatter: (params: unknown) => {
        const arr = params as Array<{ data: [Date, number, SamplePoint] }>
        const p = arr[0]?.data?.[2]
        if (!p) return ''
        const raw = props.tzMode === 'LOCAL' ? p.timeLocal : p.timeUtc
        const when = (raw || '').replace('T', ' ').replace(/\.\d+$/, '') + (props.tzMode === 'UTC' ? ' UTC' : '')
        const out = p.inRange ? '' : '<span style="color:#e34948;font-weight:600"> · 越限</span>'
        const src = p.source === 'BACKFILL' ? '<span style="color:#a87400">（补传）</span>' : ''
        return `<div style="font-size:12px;line-height:1.7">
          ${when}<br/>
          <b>${Number(p.temperature).toFixed(2)} ℃</b>${out}${src}<br/>
          序号 ${p.seq}${p.gapSeconds != null ? ` · 距上点 ${p.gapSeconds}s` : ''}
        </div>`
      }
    },
    legend: {
      data: ['温度', '越限点'],
      top: 0,
      right: 8,
      itemWidth: 14,
      itemHeight: 8,
      textStyle: { color: '#52514e', fontSize: 12 }
    },
    xAxis: {
      type: 'time',
      min: Math.min(...allTimes) - padMs,
      max: Math.max(...allTimes) + padMs,
      axisLine: { lineStyle: { color: '#c8ccd4' } },
      axisLabel: { color: '#6b6d72', fontSize: 11, hideOverlap: true },
      splitLine: { show: false }
    },
    yAxis: {
      type: 'value',
      name: '℃',
      nameTextStyle: { color: '#8a8a86', fontSize: 11 },
      scale: true,
      axisLabel: { color: '#6b6d72', fontSize: 11 },
      splitLine: { lineStyle: { color: '#eef0f3' } }
    },
    series: [
      {
        name: '温度',
        type: 'line',
        showSymbol: false,
        symbolSize: 7,
        lineStyle: { width: 2, color: '#2a78d6' },
        itemStyle: { color: '#2a78d6' },
        emphasis: { focus: 'series' },
        data: seriesData,
        markLine: {
          silent: true,
          symbol: 'none',
          data: [
            {
              yAxis: props.tempMax,
              lineStyle: { color: '#e34948', type: 'dashed', width: 1.5 },
              label: { formatter: `上限 ${props.tempMax}℃`, color: '#c23a39', fontSize: 10, position: 'insideEndTop' }
            },
            {
              yAxis: props.tempMin,
              lineStyle: { color: '#2a78d6', type: 'dashed', width: 1.5 },
              label: { formatter: `下限 ${props.tempMin}℃`, color: '#1c5cab', fontSize: 10, position: 'insideEndBottom' }
            }
          ]
        },
        markArea: { silent: true, data: markAreas }
      },
      {
        name: '越限点',
        type: 'scatter',
        symbolSize: 7,
        itemStyle: { color: '#e34948', borderColor: '#fff', borderWidth: 1 },
        data: outOfRange,
        z: 3
      }
    ]
  })
}

function resize() {
  chart?.resize()
}

onMounted(() => {
  chart = echarts.init(chartEl.value!)
  render()
  window.addEventListener('resize', resize)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  chart?.dispose()
})
watch(() => [props.points, props.anomalies, props.tzMode, props.tempMin, props.tempMax], render, { deep: true })
</script>

<style scoped>
.temp-chart {
  width: 100%;
  height: 380px;
}
</style>
