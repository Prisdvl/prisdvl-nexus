// React Router v8 的核心 API 从 'react-router' 导入。
//
// 【实测结论，2026-10-04】NavLink / Link / Outlet **也在** 'react-router' 里。
// 网上常见的「DOM API 从 react-router/dom 导入」在 8.4.0 上不成立——
// 实测该入口只有 5 个导出（HydratedRouter / RouterProvider / 三个 unstable_*），
// 是 RSC/SSR 用的，不含这三个。
import { createHashRouter, RouterProvider, type RouteObject } from 'react-router'
import { RedirectIfAuthenticated, RequireAuth } from '@/app/RequireAuth'
import { AuthLayout } from '@/layouts/AuthLayout'
import { WorkspaceLayout } from '@/layouts/WorkspaceLayout'
import { AgentsPage } from '@/pages/AgentsPage'
import { ChatPage } from '@/pages/ChatPage'
import { KnowledgeBaseDetailPage } from '@/pages/KnowledgeBaseDetailPage'
import { KnowledgeBaseListPage } from '@/pages/KnowledgeBaseListPage'
import { LoginPage } from '@/pages/LoginPage'
import { NotFoundPage } from '@/pages/NotFoundPage'
import { ShowcasePage } from '@/dev/ShowcasePage'

/**
 * 路由表（R3）。
 *
 * ## 与 R2 的差别：多了鉴权层
 *
 * ```text
 * /login  → AuthLayout → RedirectIfAuthenticated → LoginPage
 * /       → RequireAuth → WorkspaceLayout → ┬ /
 *                                          ├ /kb/:id
 *                                          ├ /agent
 *                                          └ /agents
 * *       → NotFoundPage
 * ```
 *
 * `RequireAuth` 是**布局路由**（它自己渲染 `<Outlet />`），所以 4 个工作区页面
 * 被一起保护，不需要逐页包守卫——**逐页包一定会漏**（R6 加新页面时就可能漏）。
 *
 * ## 为什么仍是 hash 路由
 *
 * 见 R2 报告 §2。URL 语义与 R0/R1/R2 完全一致，R3 没动。
 */
const routes: RouteObject[] = [
  {
    path: '/login',
    element: <AuthLayout />,
    children: [
      {
        index: true,
        element: (
          <RedirectIfAuthenticated>
            <LoginPage />
          </RedirectIfAuthenticated>
        )
      }
    ]
  },
  {
    path: '/',
    element: <RequireAuth />,
    children: [
      {
        element: <WorkspaceLayout />,
        children: [
          { index: true, element: <KnowledgeBaseListPage /> },
          { path: 'kb/:id', element: <KnowledgeBaseDetailPage /> },
          { path: 'agent', element: <ChatPage /> },
          { path: 'agents', element: <AgentsPage /> }
        ]
      }
    ]
  }
]

/*
 * R1 组件样例页：**只在开发模式注册**。
 * 用 `import.meta.env.DEV` 而不是路由内条件渲染：前者让生产构建根本不引用该模块
 * （实测 dist 里 showcase 匹配数为 0），后者代码仍会被打进包。
 */
if (import.meta.env.DEV) {
  routes.push({ path: '/__showcase', element: <ShowcasePage /> })
}

routes.push({
  // Vue 版没有兜底路由，未匹配的 hash 只渲染空白（Phase A 审计记为 P2 缺口）。
  // 注意 404 放在 RequireAuth **之外**：未登录时访问乱路径应该看到 404 而不是登录页——
  // 那会让"地址打错了"被报成"你没登录"。
  path: '*',
  element: <NotFoundPage />
})

const router = createHashRouter(routes)

export function AppRouter() {
  return <RouterProvider router={router} />
}
