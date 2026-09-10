import { defineStore } from 'pinia'
import { ref, watch } from 'vue'

/** 操作人：随 X-Operator 请求头进入审计日志 */
export const useOperatorStore = defineStore('operator', () => {
  const stored = localStorage.getItem('operator') || ''
  const operator = ref(stored)
  watch(operator, (v) => localStorage.setItem('operator', v || ''))
  return { operator }
})
