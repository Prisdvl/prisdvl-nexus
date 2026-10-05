import type { ReactElement } from 'react'
import { Skeleton, SkeletonRow } from '@/components/ui/skeleton'
import { Spinner } from '@/components/ui/Spinner'
import { cn } from '@/lib/utils'

/**
 * StateLoading —— 四种粒度的加载态。
 *
 * ## 为什么必须分粒度
 *
 * Vue 侧的教训很具体：详情页用整页 `v-loading`，而"删一篇文档"走的是
 * `busy = 'delete:' + doc.id`（按文档独立）——这一点 Phase A 确认是**全仓最好的范例**。
 * 但反过来说，面板级的等待在别处靠 `:loading` 零散处理，
 * 于是"哪些等待有反馈、哪些没有"完全看实现者心情。
 *
 * 有了这四种粒度，"等待"就变成**显式声明**而不是碰运气。
 */
export interface StateLoadingProps {
  /**
   * 粒度。
   *
   * | variant | 用在哪 | 形态 |
   * | --- | --- | --- |
   * | `page` | 整页首次加载 | 居中转圈 + 文案 |
   * | `section` | 某个面板 / 卡片加载 | 面板内居中转圈 |
   * | `list` | 列表加载 | **骨架屏**（转圈在长列表里看不出在加载什么） |
   * | `inline` | 按钮 / 一行文字旁 | 小转圈，无文案 |
   */
  variant?: 'page' | 'section' | 'list' | 'inline'
  /** `list` 时渲染几行。 */
  rows?: number
  /** 转圈旁的说明。`inline` 忽略（那一档放不下字）。 */
  label?: string
  className?: string
}

export function StateLoading({
  variant = 'section',
  rows = 3,
  label,
  className
}: StateLoadingProps): ReactElement {
  // 列表：骨架屏。aria-busy 让读屏知道"在加载"而不是"这里没内容"。
  if (variant === 'list') {
    const n = Math.max(1, Math.min(10, rows))
    return (
      <div
        aria-busy="true"
        data-testid="state-loading"
        data-variant="list"
        className={cn('flex flex-col gap-2', className)}
      >
        {Array.from({ length: n }, (_, i) => (
          <SkeletonRow key={i} />
        ))}
      </div>
    )
  }

  const box = variant === 'page' ? 'min-h-[40vh]' : 'min-h-[120px]'
  const isColumn = variant !== 'inline'

  return (
    <div
      aria-busy="true"
      data-testid="state-loading"
      data-variant={variant}
      className={cn(
        'flex items-center justify-center text-[var(--nx-color-text-muted)]',
        box,
        isColumn && 'flex-col gap-2',
        variant === 'inline' && 'inline-flex min-h-0',
        className
      )}
    >
      <Spinner size={variant === 'inline' ? 14 : 22} />
      {label && variant !== 'inline' ? (
        <span className="text-[var(--nx-text-sm)]">{label}</span>
      ) : null}
    </div>
  )
}

/** 若干行文字骨架，用于"标题 + 几行正文"的面板。 */
export function StateLoadingText({
  lines = 3,
  className
}: {
  lines?: number
  className?: string
}): ReactElement {
  return (
    <div
      aria-busy="true"
      data-testid="state-loading"
      data-variant="text"
      className={cn('flex flex-col gap-2 p-1', className)}
    >
      {Array.from({ length: Math.max(1, Math.min(10, lines)) }, (_, i) => (
        <Skeleton
          key={i}
          className="h-3.5"
          {...(i === lines - 1 ? { style: { width: '62%' } } : {})}
        />
      ))}
    </div>
  )
}
