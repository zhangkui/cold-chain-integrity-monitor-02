/** 后端 LocalDateTime 以 UTC 存储，序列化为不带时区的 ISO 串，这里补 Z 解析 */
export function parseUtc(s?: string): Date | null {
  if (!s) return null
  const normalized = s.endsWith('Z') || s.includes('+') ? s : s + 'Z'
  const d = new Date(normalized)
  return Number.isNaN(d.getTime()) ? null : d
}

/** LOCAL 时间是已按设备时区渲染好的“墙上时钟”，解析时不带时区转换 */
export function parseWallClock(s?: string): Date | null {
  if (!s) return null
  const d = new Date(s)
  return Number.isNaN(d.getTime()) ? null : d
}

const pad = (n: number) => String(n).padStart(2, '0')

export function fmtUtc(s?: string): string {
  const d = parseUtc(s)
  if (!d) return '—'
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())} ${pad(
    d.getUTCHours()
  )}:${pad(d.getUTCMinutes())}`
}

export function fmtDuration(seconds?: number | null): string {
  if (seconds === undefined || seconds === null) return '—'
  const s = Math.max(0, Math.round(seconds))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = s % 60
  if (h > 0) return `${h}小时${m}分`
  if (m > 0) return `${m}分${sec}秒`
  return `${sec}秒`
}

export const ANOMALY_META: Record<string, { label: string; type: 'danger' | 'warning' | 'info' | 'primary' }> = {
  TEMP_EXCURSION: { label: '连续超温', type: 'danger' },
  OFFLINE: { label: '传感器离线', type: 'warning' },
  INTERVAL: { label: '采样间隔异常', type: 'info' },
  HASH_BROKEN: { label: '哈希链断裂', type: 'danger' }
}

export const STATUS_META: Record<string, { label: string; type: 'warning' | 'success' | 'info' | 'danger' }> = {
  OPEN: { label: '待复核', type: 'warning' },
  CONFIRMED: { label: '已确认', type: 'danger' },
  REJECTED: { label: '已驳回', type: 'info' }
}
