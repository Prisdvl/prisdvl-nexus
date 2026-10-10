import { useEffect, type ReactElement } from 'react'
import { Navigate, Outlet, useLocation } from 'react-router'
import { StateLoading } from '@/components/common/StateLoading'
import { useAuth } from '@/hooks/useAuth'

/**
 * 受保护路由（R3 任务书 §9）。
 *
 * ## 四种进入路径，只走两条
 *
 * ```text
 * status === 'unknown' | 'initializing'  → 显示"正在恢复登录状态"，**不跳**
 * status === 'guest' | 'failed'          → 跳 /login，并带上原路径
 * status === 'authed'                    → 放行，渲染 WorkspaceLayout
 * ```
 *
 * ## 关键：初始��期间**不跳**（这是整件事的重点）
 *
 * 应用启动时 store 的 status 是 `unknown`（还没读 token）。
 * 如果守卫写成 `if (!isLoggedIn) return <Navigate to="/login" />`，
 * 会在**每一次刷新**时先把用户闪到登录页，再被初始化结果"请回来"——
 * 用户看到的是登录页一闪而过，而且**如果初始化慢，登录表单会被填好又跳走**。
 *
 * Vue 侧没有这个问题只是因为它的 token 在内存里同步可用；
 * React 版要重新从 localStorage 取（同步，但发生在 store 创建之后），
 * 于是必须显式建模 `initializing`。
 */
export function RequireAuth(): ReactElement {
  const { status } = useAuth()
  const location = useLocation()

  // 初始化完成前渲染占位。**不是**全屏遮罩，是与页面同宽的内容区占位。
  if (status === 'unknown' || status === 'initializing') {
    return (
      <div className="flex min-h-full items-center justify-center p-8" data-testid="auth-initializing">
        <StateLoading variant="section" label="正在恢复登录状态" />
      </div>
    )
  }

  if (status === 'guest' || status === 'failed') {
    // `replace` 而不是 `push`：登录页不该在历史里留一条"从这里来"，
    // 否则用户登录后点浏览器"后退"会回到登录页。
    // `state.from` 记住来源，登录成功后可以跳回去。
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }

  return <Outlet />
}

/**
 * 已登录用户不该再看到登录页。
 *
 * 没有这一条的话：登录成功 → 跳 `/` → 用户手动点后退 → 又回到登录页，
 * 看起来像"登出失败"。
 */
export function RedirectIfAuthenticated({ children }: { children: ReactElement }): ReactElement {
  const { status } = useAuth()
  const location = useLocation()

  // 初始化期间**不要**跳：那时还不知道。渲染占位避免表单闪一下。
  useEffect(() => {
    // 这里刻意不做任何跳转，只靠下面的条件渲染
  }, [])

  if (status === 'unknown' || status === 'initializing') {
    return (
      <div className="flex min-h-full items-center justify-center p-8" data-testid="auth-initializing">
        <StateLoading variant="section" label="正在恢复登录状态" />
      </div>
    )
  }

  if (status === 'authed') {
    const from = (location.state as { from?: string } | null)?.from
    return <Navigate to={from && from !== '/login' ? from : '/'} replace />
  }

  return children
}
