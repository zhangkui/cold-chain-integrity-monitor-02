<template>
  <el-container class="app-shell">
    <el-aside width="220px" class="app-aside">
      <div class="logo">
        <el-icon><ColdDrink /></el-icon>
        <span>冷链完整性监测</span>
      </div>
      <el-menu :default-active="route.path" router class="app-menu">
        <el-menu-item index="/boxes">
          <el-icon><Box /></el-icon><span>箱体列表</span>
        </el-menu-item>
        <el-menu-item index="/anomalies">
          <el-icon><Warning /></el-icon><span>异常复核</span>
        </el-menu-item>
        <el-menu-item index="/imports">
          <el-icon><Upload /></el-icon><span>批量导入</span>
        </el-menu-item>
        <el-menu-item index="/audit">
          <el-icon><Document /></el-icon><span>审计日志</span>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="app-header">
        <div class="online-dot" :class="online ? 'on' : 'off'"></div>
        <span class="online-text">{{ online ? '后端连接正常' : '网络中断，正在重试…' }}</span>
        <div class="header-right">
          <el-input
            v-model="operatorStore.operator"
            size="small"
            style="width: 160px"
            placeholder="操作人（审计用）"
            :prefix-icon="User"
          />
        </div>
      </el-header>
      <el-main>
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { User } from '@element-plus/icons-vue'
import { useOperatorStore } from './stores/operator'
import { useNetworkStore } from './stores/network'

const route = useRoute()
const operatorStore = useOperatorStore()
const networkStore = useNetworkStore()
const online = ref(navigator.onLine)
const onOnline = () => {
  online.value = true
  networkStore.setBackendOnline(true)
}
const onOffline = () => {
  online.value = false
  networkStore.setBackendOnline(false)
}
onMounted(() => {
  window.addEventListener('online', onOnline)
  window.addEventListener('offline', onOffline)
  networkStore.pollHealth()
})
onUnmounted(() => {
  window.removeEventListener('online', onOnline)
  window.removeEventListener('offline', onOffline)
  networkStore.stopPoll()
})
</script>

<style scoped>
.app-shell { height: 100vh; }
.app-aside {
  background: #001529;
  color: #fff;
  display: flex;
  flex-direction: column;
}
.logo {
  height: 56px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 18px;
  font-weight: 600;
  font-size: 15px;
  color: #fff;
  white-space: nowrap;
}
.app-menu {
  border-right: none;
  background: transparent;
  flex: 1;
}
:deep(.app-menu .el-menu-item) { color: #b7c0cd; }
:deep(.app-menu .el-menu-item.is-active) {
  background: var(--el-color-primary);
  color: #fff;
}
:deep(.app-menu .el-menu-item:hover) { background: rgba(255, 255, 255, 0.08); }
.app-header {
  background: #fff;
  display: flex;
  align-items: center;
  gap: 8px;
  border-bottom: 1px solid #ebeef5;
}
.header-right { margin-left: auto; }
.online-dot { width: 8px; height: 8px; border-radius: 50%; }
.online-dot.on { background: var(--status-good); }
.online-dot.off { background: var(--status-critical); }
.online-text { color: #606266; font-size: 13px; }
</style>
