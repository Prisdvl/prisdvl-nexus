import { useEffect, useState, type ReactElement } from 'react'
import { Outlet, useLocation } from 'react-router'
import { ConfirmHost } from '@/hooks/useConfirm'
import { AppHeader } from '@/components/layout/AppHeader'
import { AppSidebar } from '@/components/layout/AppSidebar'
import { UserMenu } from '@/components/layout/UserMenu'
import { Sheet } from '@/components/ui/sheet'

/**
 * WorkspaceLayout —— 工作区外壳。
 *
 * ## 它只负责五件事
 *
 * 结构、导航、Sidebar 状态、响应式、Outlet。
 * **不调用任何业务 API**（任务书 §5）——这个文件里出现的每一个 import
 * 都应该是布局或 UI 原语；出现 `api/` 就说明业务逻辑漏进来了。
 *
 * ## 为什么 Header 横跨顶部而不是嵌在主区
 *
 * 任务书 §5 的结构图是「Header 全宽在上，Sidebar 在下」。这不只是审美：
 * Header 横跨意味着**窄窗口下它始终可见**，而侧栏可以被 Sheet 收进浮层——
 * 1024px 下用户仍然看得见自己在哪、能切换页面。
 *
 * ## 横向溢出是怎么防住的（任务书 §15、§16）
 *
 * **不用 `width: 100vw`**。100vw 含滚动条宽度，在 flex 容器里会与 Sidebar 叠加出横向滚动条。
 * 正确姿势是三条一起上：
 *   - 外层 `flex` + `min-w-0`：允许子项收缩到内容以下；
 *   - 主区 `min-w-0`：**最关键的一条**——没有它，长 Markdown 段落与代码块会把容器撑宽；
 *   - 外层 `overflow-hidden`：兜住任何漏网的。
 */
export function WorkspaceLayout(): ReactElement {
  const { pathname } = useLocation()
  // 窄窗口（<1024）下侧栏改成 Sheet。桌面端保留展开/收起两态。
  const [isNarrow, setIsNarrow] = useState<boolean>(
    () => typeof window === 'undefined' ? false : window.innerWidth < 1024
  )
  const [mobileOpen, setMobileOpen] = useState(false)

  useEffect(() => {
    const onResize = () => setIsNarrow(window.innerWidth < 1024)
    window.addEventListener('resize', onResize)
    return () => window.removeEventListener('resize', onResize)
  }, [])

  // 路由一变就关掉移动端抽屉：否则点完"对话"抽屉还盖在页面上。
  useEffect(() => {
    setMobileOpen(false)
  }, [pathname])

  return (
    <div
      data-testid="workspace-shell"
      className="flex h-full min-w-0 flex-col overflow-hidden"
    >
      <AppHeader onOpenSidebar={isNarrow ? () => setMobileOpen(true) : undefined} />

      <div className="flex min-h-0 min-w-0 flex-1">
        {/* 桌面侧栏。窄屏时不渲染（Sheet 版本顶上），避免"看不见却仍占宽度"。 */}
        {isNarrow ? null : <AppSidebar className="shrink-0" footer={<UserMenu />} />}

        {/*
          主区。`min-w-0` 是防横向溢出的关键（任务书 §16）：
          缺它时，Chat 的长回答 / 代码块 / 长表格会把整个 flex 容器撑宽，
          表现为整页出现横向滚动条。
        */}
        <main data-testid="workspace-main" className="min-w-0 flex-1 overflow-y-auto">
          <Outlet />
        </main>
      </div>

      {/*
        全局确认框宿主（R4）。
        五类互不相干的确认点（删库 / 删文档 / 停 Agent / 整包同步 / 重建索引）
        共用这一个实例——每处自己放 `<ConfirmDialog>` 的话，
        "请求在飞时忘记传 loading"会让弹窗先消失、用户能再点一次，于是发两个删除请求。
      */}
      <ConfirmHost />

      {/* 移动端抽屉。复用 R1 的 Sheet，不自己再造浮层。 */}
      <Sheet
        open={mobileOpen}
        onClose={() => setMobileOpen(false)}
        title="导航"
        side="left"
        width="16rem"
        footer={<UserMenu />}
      >
        <AppSidebar onNavigate={() => setMobileOpen(false)} className="h-full border-r-0" />
      </Sheet>
    </div>
  )
}
