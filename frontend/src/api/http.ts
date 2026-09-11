import axios, { type AxiosRequestConfig, type InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'

declare module 'axios' {
  export interface AxiosRequestConfig {
    /** 跳过统一信封解包（如 actuator 健康检查） */
    _skipEnvelope?: boolean
    /** 禁用自动错误弹窗 */
    _silent?: boolean
    /** 最大自动重试次数（仅网络错误/5xx，默认 3） */
    _retries?: number
  }
  export interface InternalAxiosRequestConfig {
    _skipEnvelope?: boolean
    _silent?: boolean
    _retries?: number
    _idemKey?: string
    _attempt?: number
  }
}

const http = axios.create({
  baseURL: '/',
  timeout: 30000
})

// 同一逻辑请求的幂等键：重试时复用，避免断网重发造成重复数据
function idempotencyKey(config: InternalAxiosRequestConfig) {
  if (!config._idemKey) {
    const rnd =
      typeof crypto !== 'undefined' && 'randomUUID' in crypto
        ? crypto.randomUUID()
        : `${Date.now()}-${Math.random().toString(16).slice(2)}`
    config._idemKey = `${config.method || 'get'}-${(config.url || '').replace(/\W/g, '')}-${rnd}`
  }
  return config._idemKey
}

http.interceptors.request.use((config) => {
  config.headers.set('X-Request-Id', idempotencyKey(config))
  // 写操作携带业务幂等键（服务端自身按 设备+时间+序号 兜底，双头双保险）
  if (['post', 'put', 'patch', 'delete'].includes((config.method || '').toLowerCase())) {
    config.headers.set('Idempotency-Key', idempotencyKey(config))
  }
  const operator = localStorage.getItem('operator')
  if (operator) config.headers.set('X-Operator', operator)
  return config
})

const RETRY_STATUS = new Set([502, 503, 504])
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

http.interceptors.response.use(
  async (response) => {
    const config = response.config
    if (config._skipEnvelope) return response.data

    const body = response.data
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) return body.data
      if (!config._silent) ElMessage.error(body.message || '请求失败')
      // 业务失败也可能携带数据（如 422 已持久化的“不可评估”结果）
      const err = new Error(body.message || '请求失败') as Error & {
        code?: number
        errorCode?: string
        data?: unknown
      }
      err.code = body.code
      err.errorCode = body.errorCode
      err.data = body.data
      return Promise.reject(err)
    }
    return body
  },
  async (error) => {
    const config = error.config as InternalAxiosRequestConfig | undefined
    // 网络中断（含断网）或网关错误：指数退避重试
    if (config) {
      config._attempt = (config._attempt ?? 0) + 1
      const max = config._retries ?? 3
      const retriable =
        !error.response ||
        RETRY_STATUS.has(error.response.status) ||
        error.code === 'ECONNABORTED'
      if (retriable && config._attempt <= max) {
        const backoff = Math.min(8000, 500 * 2 ** (config._attempt - 1))
        await sleep(backoff)
        return http(config)
      }
    }
    if (!config?._silent) {
      const msg = error.response?.data?.message || (navigator.onLine ? '请求失败，请稍后重试' : '网络已断开，恢复后将自动重试')
      ElMessage.error(msg)
    }
    // 把业务信封的 code/errorCode/data 挂到错误对象上，便于调用方区分 404/422/500
    const envelope = error.response?.data
    if (envelope && typeof envelope === 'object' && 'code' in envelope) {
      error.code = envelope.errorCode || String(envelope.code)
      error.bizCode = envelope.code
      error.errorCode = envelope.errorCode
      error.bizData = envelope.data
    }
    return Promise.reject(error)
  }
)

export function get<T = unknown>(url: string, config?: AxiosRequestConfig): Promise<T> {
  return http.get(url, config) as Promise<T>
}
export function post<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
  return http.post(url, data, config) as Promise<T>
}
export function upload<T = unknown>(
  url: string,
  formData: FormData,
  config?: AxiosRequestConfig
): Promise<T> {
  // 不要手动设置 Content-Type：浏览器会自动带上 multipart boundary
  // multipart 默认不自动重试，避免服务端实际已收到时重试产生重复附件版本
  return http.post(url, formData, { _retries: 0, ...config }) as Promise<T>
}

export default http
