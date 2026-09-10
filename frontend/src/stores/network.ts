import { defineStore } from 'pinia'
import { ref } from 'vue'
import api from '../api/http'

/** 后端连通性：定时健康探测，断网时页面头部给出提示 */
export const useNetworkStore = defineStore('network', () => {
  const backendOnline = ref(true)
  let timer: number | undefined

  async function check() {
    try {
      await api.get('/actuator/health', { _skipEnvelope: true, _silent: true, _retries: 0 })
      backendOnline.value = true
    } catch {
      backendOnline.value = false
    }
  }

  function pollHealth() {
    check()
    timer = window.setInterval(check, 10000)
  }

  function stopPoll() {
    if (timer) window.clearInterval(timer)
  }

  return { backendOnline, pollHealth, stopPoll, setBackendOnline: (v: boolean) => (backendOnline.value = v) }
})
