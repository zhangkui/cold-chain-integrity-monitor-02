import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/boxes' },
    { path: '/boxes', name: 'boxes', component: () => import('../views/BoxesView.vue') },
    { path: '/anomalies', name: 'anomalies', component: () => import('../views/AnomaliesView.vue') },
    { path: '/imports', name: 'imports', component: () => import('../views/ImportsView.vue') },
    { path: '/audit', name: 'audit', component: () => import('../views/AuditView.vue') }
  ]
})

export default router
