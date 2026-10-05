import { useId, useState, type ReactElement, type ReactNode } from 'react'
import { cn } from '@/lib/utils'

/**
 * Tooltip —— 悬停/聚焦时的短提示。
 *
 * ## 明确不承担的职责（任务书 §20）
 *
 * **Tooltip 不能替代真正的 UI 文案。**典型误用：把表单字段的说明塞进 Tooltip，
 * 于是"标签右侧有个小问号"——用户不知道那是什么，触屏用户根本点不到，
 * 而且**它不会被读屏软件当作字段说明**。
 *
 * 正确做法：字段说明用 `Field` 的 `description`（它会挂 `aria-describedby`）。
 * Tooltip 只用于：
 *   - 被截断的标题（悬停看全文）
 *   - 侧栏收起后的图标（图标本身没有文字，需要补一个名字）
 *   - 纯装饰性图标按钮的 aria-label 提示
 *
 * ## 为什么用 CSS 而不用 JS 定位
 *
 * 跟随光标定位需要监听 mousemove 并计算位置，在 React 里是一份持续的重渲染。
 * 而 Tooltip 的定位需求只有"贴着触发元素"，用 `group-hover` 定位就够了。
 * 只有"跟随光标"这一种交互才值得上浮层库，而本项目不需要。
 */

export interface TooltipProps {
  /** 提示文字。**一句话**，不要塞段落。 */
  content: ReactNode
  children: ReactElement
  /** 提示出现的位置。 */
  side?: 'top' | 'bottom' | 'left' | 'right'
  /** 触发方式。图标按钮用 focus 更合适（键盘用户也会看到）。 */
  trigger?: 'hover' | 'focus'
  className?: string
}

const SIDE_CLASS: Record<NonNullable<TooltipProps['side']>, string> = {
  top: 'bottom-full left-1/2 -translate-x-1/2 mb-1.5',
  bottom: 'top-full left-1/2 -translate-x-1/2 mt-1.5',
  left: 'right-full top-1/2 -translate-y-1/2 mr-1.5',
  right: 'left-full top-1/2 -translate-y-1/2 ml-1.5'
}

export function Tooltip({
  content,
  children,
  side = 'top',
  trigger = 'hover',
  className
}: TooltipProps): ReactElement {
  const id = useId()
  const [open, setOpen] = useState(false)

  return (
    <span
      className={cn('relative inline-flex', className)}
      onMouseEnter={trigger === 'hover' ? () => setOpen(true) : undefined}
      onMouseLeave={trigger === 'hover' ? () => setOpen(false) : undefined}
      onFocus={trigger === 'focus' ? () => setOpen(true) : undefined}
      onBlur={trigger === 'focus' ? () => setOpen(false) : undefined}
    >
      {/*
        不用 `aria-describedby` 挂在 children 上——那要求 children 能接受 ref，
        而 children 是任意元素（可能是 button 也可能是一段文字）。
        改为在提示本体上放 role="tooltip"，并用 aria-describedby 指向它；
        Trigger 自己也能独立使用（比如纯装饰的图标）。
      */}
      <span aria-describedby={open ? id : undefined} className="inline-flex">
        {children}
      </span>

      {open ? (
        <span
          id={id}
          role="tooltip"
          className={cn(
            'pointer-events-none absolute z-[var(--nx-z-tooltip)]',
            'rounded-[var(--nx-radius-sm)] px-2 py-1',
            'bg-[var(--nx-color-text)] text-[var(--nx-color-text-inverse)]',
            'text-[var(--nx-text-xs)] whitespace-nowrap shadow-[var(--nx-shadow-md)]',
            SIDE_CLASS[side]
          )}
        >
          {content}
        </span>
      ) : null}
    </span>
  )
}
