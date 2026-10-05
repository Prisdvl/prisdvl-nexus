import type { ReactElement, ReactNode } from 'react'
import { cn } from '@/lib/utils'

/**
 * PageHeader —— 页头。
 *
 * 任务书 §15 要求它统一 `title / description / actions / back` 四件事，
 * 供后续的 Knowledge / Agents / Knowledge Detail 共用。
 *
 * ## 为什么"返回"是它的参数而不是页面自己渲染
 *
 * 三个页面的返回行为并不完全一样：
 * - 知识库详情 → 回到列表（用路由 `navigate(-1)` 还是 `navigate('/')` 取决于来源）；
 * - Agent 配置（Sheet 内）→ 关掉 Sheet，不是换页；
 * - 导入后 → 留在原地。
 *
 * 所以这里只提供**位置**（左侧一个插槽），不规定**行为**。
 * 一旦组件里写死 `navigate(-1)`，其中两个场景就会用错方式返回——
 * 那正是 Vue 侧"每个页面自己写返回"留下的隐患。
 */
export interface PageHeaderProps {
  title: ReactNode
  /** 一句说明这个页面在做什么。**不是**给操作加的副标题。 */
  description?: ReactNode
  /** 右侧操作区（按钮组）。 */
  actions?: ReactNode
  /** 左侧插槽：返回按钮等。 */
  back?: ReactNode
  className?: string
  /** 传入 testid 由调用方给，不硬编码业务值。 */
  testId?: string
}

export function PageHeader({
  title,
  description,
  actions,
  back,
  className,
  testId
}: PageHeaderProps): ReactElement {
  return (
    <header
      data-testid={testId}
      className={cn(
        'flex flex-wrap items-start justify-between gap-3 border-b border-[var(--nx-color-border)] pb-4',
        className
      )}
    >
      <div className="flex min-w-0 items-start gap-3">
        {back ? <div className="shrink-0 pt-0.5">{back}</div> : null}
        <div className="min-w-0">
          {/*
            h1 而不是 div：这是页面主标题，读屏软件的"跳到标题"能用上它。
            页面里只应有一个 h1（这里就是它）。
          */}
          <h1 className="m-0 truncate text-[var(--nx-text-xl)] leading-[var(--nx-leading-tight)] font-[var(--nx-weight-semibold)]">
            {title}
          </h1>
          {description ? (
            <p className="mt-1 mb-0 text-[var(--nx-text-sm)] text-[var(--nx-color-text-secondary)]">
              {description}
            </p>
          ) : null}
        </div>
      </div>

      {actions ? <div className="flex shrink-0 items-center gap-2">{actions}</div> : null}
    </header>
  )
}
