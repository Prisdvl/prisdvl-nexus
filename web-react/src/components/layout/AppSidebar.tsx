import { useCallback, useEffect, useState, type ReactElement } from 'react'
import { Link, useLocation } from 'react-router'
import { Button } from '@/components/ui/button'
import { Tooltip } from '@/components/ui/tooltip'
import { cn } from '@/lib/utils'

/**
 * 一级导航。
 *
 * **与 Vue 版 `App.vue` 的 navItems 逐条对应**（任务书 §三：知识库 / 对话 / Agent，
 * 不新增首页 / Dashboard / Settings）。标签"Agent 管理"在 R2 已缩为"Agent"——
 * 导航空间有限，而完整名称由页面自己的 PageHeader 承担，信息并没有丢。
 *
 * testid 用 `workspace-nav-*` 而不是 Vue 侧的 `nav-*`（任务书 §25）。
 * **两套前端命名不同是有意的**：Phase R11 迁移 E2E 时会统一到一套，
 * 现在改名会让 132 个已稳定的 Vue 侧 testid 白改一次。
 */
export interface NavItem {
  to: string
  label: string
  /**
   * 哪些路径算"在这个导航项之下"。
   *
   * 为什么不用 NavLink 的 `end` 开关：`end` 只能做**精确匹配**，
   * 于是从 `/kb/123` 返回详情页时，`/`（end:true）不会高亮——用户会以为
   * 自己"离开了知识库"。Vue 版是用 `match: ['knowledge-bases','knowledge-base-detail']`
   * 显式列出两个路由名解决的，这里换成同样显式的路径前缀。
   */
  match: readonly string[]
  testId: string
  icon: ReactElement
}

/**
 * 判断某导航项在当前路径下是否激活。
 *
 * **必须精确到"路径段"**，否则 `/agents` 会同时点亮 `/agent`——
 * 因为 `'/agents'.startsWith('/agent')` 是 true。所以末尾还要跟一个 `/`。
 */
export function isNavActive(match: readonly string[], pathname: string): boolean {
  return match.some((p) => pathname === p || pathname.startsWith(`${p}/`))
}

const ICON = 'h-5 w-5'

/**
 * 图标内联而非引图标库。
 *
 * 三个导航项的图标是**固定契约**（Phase 0 定的），内联 SVG 让它们
 * 不依赖 lucide 的具体图形，也避免为三个图标引入整包依赖。
 * 其余图标（按钮、状态点）仍用 lucide。
 */
export const NAV_ITEMS: NavItem[] = [
  {
    to: '/',
    label: '知识库',
    // 详情页 `/kb/:id` 也算"在知识库之下"
    match: ['/', '/kb'],
    testId: 'workspace-nav-knowledge',
    icon: (
      <svg viewBox="0 0 24 24" className={ICON} fill="none" stroke="currentColor" strokeWidth="1.7">
        <path d="M3 7.5A1.5 1.5 0 0 1 4.5 6h4l2 2.5h9A1.5 1.5 0 0 1 21 10v8.5a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 18.5v-11Z" />
      </svg>
    )
  },
  {
    to: '/agent',
    label: '对话',
    match: ['/agent'],
    testId: 'workspace-nav-chat',
    icon: (
      <svg viewBox="0 0 24 24" className={ICON} fill="none" stroke="currentColor" strokeWidth="1.7">
        <path d="M4 5.5A1.5 1.5 0 0 1 5.5 4h13A1.5 1.5 0 0 1 20 5.5v9a1.5 1.5 0 0 1-1.5 1.5H9l-4 3v-3H5.5A1.5 1.5 0 0 1 4 14.5v-9Z" />
      </svg>
    )
  },
  {
    to: '/agents',
    label: 'Agent',
    match: ['/agents'],
    testId: 'workspace-nav-agent',
    icon: (
      <svg viewBox="0 0 24 24" className={ICON} fill="none" stroke="currentColor" strokeWidth="1.7">
        <path d="m12 3 1.9 4.6L18.5 9l-4.6 1.9L12 15.5l-1.9-4.6L5.5 9l4.6-1.4L12 3Z" />
        <path d="M18 15.5l.9 2.1 2.1.9-2.1.9-.9 2.1-.9-2.1-2.1-.9 2.1-.9.9-2.1Z" />
      </svg>
    )
  }
]

/**
 * 折叠状态的持久化。
 *
 * ## 为什么 key 叫 `nexus.sidebar.collapsed`
 *
 * 与 token 的 `nexus.token`、username 的 `nexus.username` 同一前缀——
 * 这些是"本应用在浏览器里留下的痕迹"，统一前缀便于将来清理与排查。
 *
 * ## 为什么读写都包 try/catch
 *
 * `localStorage` 在三种情况下会抛：Safari 无痕模式、禁用 cookie、
 * 存储配额满（`setItem` 时）。任务书 §28 要求「读取异常也必须 fallback = expanded」——
 * 读不到就当没折叠过，这是**安全的方向**（宁可多占一点宽度，也不要用户
 * 莫名其妙看到一个只有图标的侧栏）。
 */
const STORAGE_KEY = 'nexus.sidebar.collapsed'

export function readSidebarCollapsed(): boolean {
  try {
    return localStorage.getItem(STORAGE_KEY) === 'true'
  } catch {
    return false // 读不到 → 展开
  }
}

function writeSidebarCollapsed(collapsed: boolean): void {
  try {
    localStorage.setItem(STORAGE_KEY, String(collapsed))
  } catch {
    // 写不进去不是致命问题：本次会话内状态仍然正确，只是刷新后丢失
  }
}

export interface AppSidebarProps {
  /** 账号区（由 Layout 传入，Sidebar 本身不知道用户是谁）。 */
  footer?: ReactElement
  /** 移动端 Sheet 里的点击后回调（关掉抽屉）。 */
  onNavigate?: () => void
  className?: string
}

export function AppSidebar({ footer, onNavigate, className }: AppSidebarProps): ReactElement {
  // 只读折叠态。**不跟随窄屏自动折叠**——那会让"用户主动收起"与"窗口变窄"混为一谈，
  // 用户回到宽屏时发现自己的选择被系统改掉了。窄屏的处理在外层（换成 Sheet）。
  const { pathname } = useLocation()
  const [collapsed, setCollapsed] = useState(readSidebarCollapsed)

  useEffect(() => {
    writeSidebarCollapsed(collapsed)
  }, [collapsed])

  const toggle = useCallback(() => setCollapsed((v) => !v), [])

  return (
    <div
      data-testid="workspace-sidebar"
      data-collapsed={collapsed ? 'true' : 'false'}
      className={cn(
        'flex h-full flex-col border-r border-[var(--nx-color-border)] bg-[var(--nx-color-surface)]',
        'transition-[width] duration-[var(--nx-dur-base)] ease-[var(--nx-ease-out)]',
        collapsed ? 'w-16' : 'w-60',
        className
      )}
    >
      {/* ---------- 品牌 ---------- */}
      <div
        className={cn(
          'flex h-14 shrink-0 items-center border-b border-[var(--nx-color-border)]',
          collapsed ? 'justify-center px-2' : 'gap-2.5 px-4'
        )}
      >
        <span
          aria-hidden="true"
          className="grid h-7 w-7 shrink-0 place-items-center rounded-[var(--nx-radius-sm)] bg-[var(--nx-color-primary)] text-[var(--nx-color-on-primary)] text-sm font-[var(--nx-weight-bold)]"
        >
          N
        </span>
        {collapsed ? null : (
          <span className="min-w-0">
            <span className="block truncate text-[var(--nx-text-sm)] font-[var(--nx-weight-semibold)]">
              Prisdvl-Nexus
            </span>
            <span className="block truncate text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">
              知识库控制台
            </span>
          </span>
        )}
      </div>

      {/* ---------- 导航 ---------- */}
      <nav aria-label="主导航" className="flex min-h-0 flex-1 flex-col gap-1 overflow-y-auto p-2">
        {NAV_ITEMS.map((item) => {
          const active = isNavActive(item.match, pathname)
          return (
          // 用 Link 而不是 NavLink：激活判定完全由 isNavActive 负责。
          // NavLink 自带一套匹配规则（默认前缀匹配），与 isNavActive 是**两套真相**——
          // 实测踩过：/kb/12345 下样式对了（isNavActive 判定）但 aria-current 缺失
          //（NavLink 判定），E2E 就会漏判高亮。两套逻辑并存时，只有一套说了算。
          <Link
            key={item.to}
            to={item.to}
            data-testid={item.testId}
            onClick={onNavigate}
            aria-current={active ? 'page' : undefined}
            className={cn(
              'flex items-center rounded-[var(--nx-radius-md)] text-[var(--nx-text-sm)]',
              'transition-colors duration-[var(--nx-dur-fast)]',
              collapsed ? 'h-9 justify-center' : 'h-9 gap-2.5 px-2.5',
              active
                ? 'bg-[var(--nx-color-primary-container)] text-[var(--nx-color-primary)] font-[var(--nx-weight-medium)]'
                : 'text-[var(--nx-color-text-secondary)] hover:bg-[var(--nx-color-surface-muted)] hover:text-[var(--nx-color-text)]'
            )}
          >
            {/*
              收起后只剩图标 → **必须靠 Tooltip 告诉用户这是什么**（任务书 §7）。
              不给 Tooltip 的图标导航是典型的"用户不知道怎么回去"。
            */}
            {collapsed ? (
              <Tooltip content={item.label} side="right">
                <span className="grid place-items-center" tabIndex={-1} aria-hidden="true">
                  {item.icon}
                </span>
              </Tooltip>
            ) : (
              <span className="grid shrink-0 place-items-center" aria-hidden="true">
                {item.icon}
              </span>
            )}
            {collapsed ? <span className="sr-only">{item.label}</span> : <span>{item.label}</span>}
          </Link>
          )
        })}
      </nav>

      {/* ---------- 底部：折叠开关 + 账号区 ---------- */}
      <div className="shrink-0 border-t border-[var(--nx-color-border)] p-2">
        <Tooltip content={collapsed ? '展开侧栏' : '收起侧栏'} side="right">
          <Button
            variant="ghost"
            size="sm"
            onClick={toggle}
            // 任务书 §26：aria-label 必须说清"按下会发生什么"，而不是重复可见文字
            aria-label={collapsed ? '展开侧栏' : '收起侧栏'}
            aria-expanded={!collapsed}
            data-testid="workspace-sidebar-toggle"
            className={cn('w-full', collapsed ? 'px-0' : 'justify-start gap-2.5 px-2.5')}
          >
            <span className="grid shrink-0 place-items-center" aria-hidden="true">
              <svg
                viewBox="0 0 24 24"
                className={cn('h-4 w-4 transition-transform duration-[var(--nx-dur-base)]', collapsed && 'rotate-180')}
                fill="none"
                stroke="currentColor"
                strokeWidth="1.8"
              >
                <path d="M14 7l-5 5 5 5" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
            </span>
            {collapsed ? <span className="sr-only">展开侧栏</span> : <span>收起侧栏</span>}
          </Button>
        </Tooltip>

        {footer ? <div className="mt-2">{footer}</div> : null}
      </div>
    </div>
  )
}
