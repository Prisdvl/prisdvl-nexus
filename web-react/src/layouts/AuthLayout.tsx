import type { ReactElement } from 'react'
import { Outlet } from 'react-router'
import { cn } from '@/lib/utils'

/**
 * AuthLayout —— 未登录页的外壳。
 *
 * ## R2 只做"空壳"（任务书 §18）
 *
 * 真正的登录/注册/redirect 在 **R5**。这里只负责：
 * 居中一个内容区、给页面一个稳定的高度参照。
 *
 * ## 为什么登录页不套 WorkspaceLayout
 *
 * 两者的导航与账号区完全不同：登录时用户还没有账号，渲染侧栏底部那个
 * "demo"入口是**误导**。Phase B §4 也把 Login 列为"不应该出现技术信息"的独立页面。
 *
 * ## 为什么不用 `100vw` 居中
 *
 * 同 WorkspaceLayout 的理由（任务书 §15）：100vw 含滚动条宽度，
 * 会造成轻微的横向溢出。这里用 `mx-auto` + `max-w-*`。
 */
export function AuthLayout(): ReactElement {
  return (
    <div
      data-testid="auth-layout"
      className="flex min-h-full w-full items-center justify-center bg-[var(--nx-color-background)] px-4 py-8"
    >
      <div className={cn('w-full', 'max-w-[26rem]')}>
        <Outlet />
      </div>
    </div>
  )
}
