import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'
import { isLoggedIn } from '@/stores/auth'
import { ElMessage } from 'element-plus'

// 用 hash 路由：部署时不需要服务端 rewrite 规则（本地工具场景更省事）
const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true, title: '登录' }
  },
  {
    path: '/',
    name: 'knowledge-bases',
    component: () => import('@/views/KnowledgeBaseListView.vue'),
    meta: { title: '知识库' }
  },
  {
    path: '/kb/:id',
    name: 'knowledge-base-detail',
    component: () => import('@/views/KnowledgeBaseDetailView.vue'),
    meta: { title: '知识库详情' }
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.beforeEach((to) => {
  if (!to.meta.public && !isLoggedIn()) {
    ElMessage.info('请先登录')
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  return true
})

export default router
