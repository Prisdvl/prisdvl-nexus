import { useMemo, type ReactElement, type ReactNode } from 'react'
import { useLocation } from 'react-router'
import { cn } from '@/lib/utils'

/**
 * 路由 → 页面名。**这是"高亮与标题"的唯一依据**（任务书 §8、§9）。
 *
 * ## 为什么不靠"匹配中文文案"
 *
 * 任务书 §8 明确「不能依赖中文文案判断」。文案会变：R0 已把导航标签
 * 「Agent 管理」缩成「Agent」，若高亮逻辑是"当前标签等于『Agent 管理』"，
 * 这次改名就会让 Agent 页永远不高亮——而且**不报错**，只是悄悄不对。
 *
 * 判据改为**路径**：`/` 精确匹配，`/agent` 与 `/agents` 各自精确。
 * 注意 `/agents` 与 `/agent` 是**前缀关系**（`/agent` 是 `/agents` 的前半），
 * 所以这里一律用**完整路径相等**判断，不做 `startsWith`。
 */
const ROUTE_META: ReadonlyArray<{ path: string; title: string; parent?: string }> = [
  { path: '/', title: '知识库' },
  { path: '/agent', title: '对话' },
  { path: '/agents', title: 'Agent' }
]

/** 详情页要带 id，不能从路径推断出库名，所以单独处理。 */
const DETAIL_TITLE = '知识库详情'

export function useRouteTitle(): { title: string; parent?: string; isDetail: boolean } {
  const { pathname } = useLocation()
  return useMemo(() => {
    const exact = ROUTE_META.find((r) => r.path === pathname)
    if (exact) return { title: exact.title, isDetail: false }
    if (pathname.startsWith('/kb/')) {
      return { title: DETAIL_TITLE, parent: '知识库', isDetail: true }
    }
    return { title: '页面不存在', isDetail: false }
  }, [pathname])
}

export interface AppHeaderProps {
  /** 右侧操作区（页面自己传入，Header 不猜）。 */
  actions?: ReactNode
  /** 移动端展开侧栏的按钮。桌面端隐藏。 */
  onOpenSidebar?: () => void
  className?: string
}

export function AppHeader({ actions, onOpenSidebar, className }: AppHeaderProps): ReactElement {
  // 只取需要的字段：isDetail 目前没有分支用它，取出来会在 noUnusedLocals 下报错。
  // 面包屑是否出现由 `parent` 是否存在决定，不看 isDetail。
  const { title, parent } = useRouteTitle()

  return (
    <header
      data-testid="app-header"
      className={cn(
        'flex h-14 shrink-0 items-center justify-between gap-3',
        'border-b border-[var(--nx-color-border)] bg-[var(--nx-color-background)] px-4',
        className
      )}
    >
      <div className="flex min-w-0 items-center gap-2">
        {/*
          移动端侧栏按钮。桌面端用 `lg:hidden` 隐藏——不删，
          因为"窄窗口 = 可能没有侧栏"这件事在 R2 之后才成立。
        */}
        <button
          type="button"
          onClick={onOpenSidebar}
          aria-label="打开导航"
          data-testid="app-header-menu"
          className="grid h-8 w-8 shrink-0 place-items-center rounded-[var(--nx-radius-md)] text-[var(--nx-color-text-secondary)] hover:bg-[var(--nx-color-surface-muted)] lg:hidden"
        >
          <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="1.8">
            <path d="M4 7h16M4 12h16M4 17h16" strokeLinecap="round" />
          </svg>
        </button>

        {/*
          面包屑只在**有层级**时出现（任务书 §11：不要为了"看起来专业"强行加）。
          `/` 与 `/agent` 是一级页面 → 只有标题；
          `/kb/:id` 有父级 → 才有「知识库 / 详情」。
        */}
        {parent ? (
          <nav aria-label="面包屑" className="flex min-w-0 items-center gap-1.5">
            <span className="shrink-0 text-[var(--nx-text-sm)] text-[var(--nx-color-text-muted)]">
              {parent}
            </span>
            <span aria-hidden="true" className="text-[var(--nx-color-text-muted)]">
              /
            </span>
            <span
              aria-current="page"
              data-testid="app-header-title"
              className="truncate text-[var(--nx-text-base)] font-[var(--nx-weight-medium)]"
            >
              {title}
            </span>
          </nav>
        ) : (
          // 一级页面：它就是页面的主标题，用 h1。
          // 详情页走上面的面包屑分支（那里用 span，因为要显示层级），
          // 所以整页始终只有**一个** h1。
          <h1
            data-testid="app-header-title"
            className="truncate text-[var(--nx-text-base)] font-[var(--nx-weight-semibold)]"
          >
            {title}
          </h1>
        )}
      </div>

      {actions ? <div className="flex shrink-0 items-center gap-2">{actions}</div> : null}
    </header>
  )
}
