import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { AppRouter } from '@/app/router'
import { useAuthInitializer } from '@/hooks/useAuth'
import '@/styles/globals.css'

/*
 * 入口。
 *
 * ## 为什么只有 Pinia，没有别的东西
 *
 * R4 之前不需要 Provider 之外的任何全局设施：Zustand 的 store 是模块级的，
 * 挂上 `useAuthInitializer` 就够了。
 * 加一个空的 QueryClient / ThemeProvider 只会让人以为"已经有数据缓存了"。
 */
const container = document.getElementById('root')
if (!container) {
  // 挂载点不存在属于部署错误（index.html 被改坏），直接报出来比白屏好
  throw new Error('找不到 #root 挂载点：index.html 是否被改坏？')
}

function Root() {
  // 认证初始化**必须在路由渲染之前**启动：
  // 否则 RequireAuth 第一次读到的是 'unknown'，虽然它会显示占位而不是误跳，
  // 但初始化能早一帧开始就少一帧等待。
  useAuthInitializer()
  return <AppRouter />
}

createRoot(container).render(
  <StrictMode>
    <Root />
  </StrictMode>
)
