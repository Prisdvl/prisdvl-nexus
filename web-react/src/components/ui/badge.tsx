import type { HTMLAttributes, ReactElement } from 'react'
import { cn } from '@/lib/utils'

/**
 * Badge —— 小色块。
 *
 * 只做**容器**（形状 + 语义色），不内置任何状态词。
 *
 * ## 为什么不内置"处理中 / 已完成 / 失败"这些映射
 *
 * Vue 侧有两份状态词表（`KnowledgeBaseListView` 与 `AgentsView` 各一份），
 * 同一个状态在两个页面可能显示不同，且没人说得清以哪个为准。
 * 迁移时把它固化成 `StatusBadge`（见 `components/common/StatusBadge.tsx`），
 * `Badge` 保持"不知道业务状态"——这样第二份词表没有机会出现。
 */
export type BadgeTone = 'neutral' | 'primary' | 'success' | 'warning' | 'danger' | 'info'

const TONE_CLASS: Record<BadgeTone, string> = {
  neutral: 'bg-[var(--nx-color-surface-muted)] text-[var(--nx-color-text-secondary)]',
  primary: 'bg-[var(--nx-color-primary-container)] text-[var(--nx-color-primary)]',
  success: 'bg-[var(--nx-color-success-container)] text-[var(--nx-color-success)]',
  warning: 'bg-[var(--nx-color-warning-container)] text-[var(--nx-color-warning)]',
  danger: 'bg-[var(--nx-color-danger-container)] text-[var(--nx-color-danger)]',
  info: 'bg-[var(--nx-color-info-container)] text-[var(--nx-color-info)]'
}

export interface BadgeProps extends HTMLAttributes<HTMLSpanElement> {
  tone?: BadgeTone
  /** 左侧圆点。用于"进行中"这类需要持续留意��状态。 */
  dot?: boolean
  /** 圆点呼吸。**只用于进行中**，终态不要加（会让人以为还在跑）。 */
  pulse?: boolean
}

export function Badge({
  tone = 'neutral',
  dot = false,
  pulse = false,
  className,
  children,
  ...rest
}: BadgeProps): ReactElement {
  return (
    <span
      className={cn(
        'inline-flex shrink-0 items-center gap-1.5 rounded-full px-2 py-0.5',
        'text-[var(--nx-text-xs)] leading-[1.4] whitespace-nowrap',
        TONE_CLASS[tone],
        className
      )}
      {...rest}
    >
      {dot ? (
        <span
          aria-hidden="true"
          className={cn('h-1.5 w-1.5 rounded-full bg-current', pulse && 'animate-pulse')}
        />
      ) : null}
      {children}
    </span>
  )
}
