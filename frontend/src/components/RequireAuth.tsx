/**
 * 受保护路由：未登录访问 /app/* → 重定向 /login，并用 state.from 记住来源。
 *
 * `replace` 而不是 `push`：登录页不该留在历史里，否则登录后点后退又回到登录页。
 */
import type { ReactElement } from 'react'
import { Navigate, Outlet, useLocation } from 'react-router'
import { useAuth } from '@/lib/auth'

export function RequireAuth(): ReactElement {
  const { isAuthed } = useAuth()
  const location = useLocation()

  if (!isAuthed) {
    const from = `${location.pathname}${location.search}`
    return <Navigate to="/login" replace state={{ from }} />
  }
  return <Outlet />
}

/** 已登录不该再看到登录页（否则登录后点后退会"看起来像登出失败"） */
export function RedirectIfAuthenticated({ children }: { children: ReactElement }): ReactElement {
  const { isAuthed } = useAuth()
  const location = useLocation()
  if (!isAuthed) return children
  const from = (location.state as { from?: string } | null)?.from
  return <Navigate to={from && !from.startsWith('/login') ? from : '/app/workbench'} replace />
}