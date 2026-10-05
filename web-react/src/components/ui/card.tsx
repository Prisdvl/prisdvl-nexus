import { forwardRef, type HTMLAttributes, type ReactElement } from 'react'
import { cn } from '@/lib/utils'

/**
 * Card —— 容器原语。
 *
 * ## 为什么只有这一个 Card
 *
 * 后续的 `KnowledgeBaseCard` / `AgentCard` / `DocumentItem` 都基于它（任务书 §16），
 * 所以它的 API 要**足够窄**——只管容器，不管内容。
 * 一旦在这里塞进 hover 效果或按钮，继承它的三个业务卡片就会各自带一份，
 * 最后三份代码都要改。
 *
 * 因此交互分两层：
 * - `interactive`：只加 `cursor-pointer` 与 hover 的边框/阴影变化（可点即可有反馈）；
 * - 具体行为（点击、选中态、危险态）由业务卡片自己组合。
 */

export type CardVariant = 'default' | 'interactive' | 'selected' | 'danger'

const VARIANT_CLASS: Record<CardVariant, string> = {
  default: 'border border-[var(--nx-color-border)] bg-[var(--nx-color-surface)]',
  interactive:
    'border border-[var(--nx-color-border)] bg-[var(--nx-color-surface)] cursor-pointer ' +
    'transition-[border-color,box-shadow] duration-[var(--nx-dur-fast)] ' +
    'hover:border-[var(--nx-color-border-strong)] hover:shadow-[var(--nx-shadow-md)]',
  selected:
    'border border-[var(--nx-color-primary)] bg-[var(--nx-color-primary-container)] ' +
    'ring-1 ring-[var(--nx-color-primary)]',
  danger:
    'border border-[var(--nx-color-danger)] bg-[var(--nx-color-danger-container)] ' +
    'text-[var(--nx-color-danger)]'
}

export interface CardProps extends HTMLAttributes<HTMLDivElement> {
  variant?: CardVariant
  /** 卡片内边距。`none` 供内部完全自定义的场景（如列表行）。 */
  padding?: 'none' | 'sm' | 'md'
}

const PADDING_CLASS = {
  none: '',
  sm: 'p-3',
  md: 'p-4'
} as const

export const Card = forwardRef<HTMLDivElement, CardProps>(function Card(
  { variant = 'default', padding = 'md', className, ...rest },
  ref
) {
  return (
    <div
      ref={ref}
      className={cn(
        'rounded-[var(--nx-radius-lg)]',
        VARIANT_CLASS[variant],
        PADDING_CLASS[padding],
        className
      )}
      {...rest}
    />
  )
})

/** 卡片标题区。**不预设字号**——标题层级由所在位置决定，不该由组件猜。 */
export function CardHeader({ className, ...rest }: HTMLAttributes<HTMLDivElement>): ReactElement {
  return <div className={cn('flex flex-col gap-1', className)} {...rest} />
}

export function CardTitle({ className, ...rest }: HTMLAttributes<HTMLHeadingElement>): ReactElement {
  return (
    <h3
      className={cn(
        'text-[var(--nx-text-lg)] leading-[var(--nx-leading-tight)] font-[var(--nx-weight-semibold)]',
        className
      )}
      {...rest}
    />
  )
}

export function CardDescription({
  className,
  ...rest
}: HTMLAttributes<HTMLParagraphElement>): ReactElement {
  return (
    <p
      className={cn('text-[var(--nx-text-sm)] text-[var(--nx-color-text-secondary)]', className)
      }
      {...rest}
    />
  )
}

export function CardContent({ className, ...rest }: HTMLAttributes<HTMLDivElement>): ReactElement {
  return <div className={cn('mt-3', className)} {...rest} />
}

export function CardFooter({ className, ...rest }: HTMLAttributes<HTMLDivElement>): ReactElement {
  return (
    <div
      className={cn(
        'mt-3 flex items-center gap-2 border-t border-[var(--nx-color-border)] pt-3',
        className
      )}
      {...rest}
    />
  )
}
