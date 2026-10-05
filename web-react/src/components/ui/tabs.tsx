import { useId, useState, type ReactElement, type ReactNode } from 'react'
import { cn } from '@/lib/utils'

/**
 * Tabs —— 页签原语。
 *
 * R1 **不确定业务页签的最终形态**（任务书 §19：R1 只提供 UI 原语，
 * 不替 Knowledge Detail 决定 tab）。所以这里的 API 是通用的：
 * 由调用方传 `tabs` 数组，组件只管渲染与键盘。
 *
 * ## ARIA 用的是 `role="tablist"`，不是"看起来像 tab 的按钮组"
 *
 * 读屏软件靠 `role` + `aria-selected` 判断"这是页签"，靠 `aria-controls`
 * 关联到面板。缺了这些，它会把这组按钮读成"三个可以按的按钮"，
 * 用户完全不知道当前在第几个、怎么切。
 *
 * 键盘遵循 WAI-ARIA 页签模式：←/→ 在页签间移动，Home/End 跳首尾。
 */

export interface TabItem {
  value: string
  label: ReactNode
  /** 右侧的内容（如数量徽标）。 */
  badge?: ReactNode
  disabled?: boolean
}

export interface TabsProps {
  items: TabItem[]
  value: string
  onChange: (value: string) => void
  /** 页签栏的对齐方式。 */
  align?: 'start' | 'stretch'
  /** 传给页签栏的类名（用于挂 testid）。 */
  className?: string
  /** 传给面板容器的类名。 */
  panelClassName?: string
  panel: (activeValue: string) => ReactNode
}

export function Tabs({
  items,
  value,
  onChange,
  align = 'start',
  className,
  panelClassName,
  panel
}: TabsProps): ReactElement {
  const baseId = useId()
  const enabled = items.filter((t) => !t.disabled)

  /** ←/→/Home/End 在页签间移动；只有启用项可落焦。 */
  const onKeyDown = (e: React.KeyboardEvent<HTMLDivElement>) => {
    const keys = ['ArrowLeft', 'ArrowRight', 'Home', 'End']
    if (!keys.includes(e.key)) return
    e.preventDefault()
    const dir = e.key === 'ArrowRight' ? 1 : e.key === 'ArrowLeft' ? -1 : 0
    const cur = enabled.findIndex((t) => t.value === value)
    if (cur < 0 || enabled.length === 0) return
    const next =
      e.key === 'Home'
        ? enabled[0]
        : e.key === 'End'
          ? enabled[enabled.length - 1]
          : enabled[(cur + dir + enabled.length) % enabled.length]
    if (next) onChange(next.value)
  }

  return (
    <div className="flex flex-col">
      <div
        role="tablist"
        aria-orientation="horizontal"
        onKeyDown={onKeyDown}
        className={cn(
          'flex gap-1 border-b border-[var(--nx-color-border)]',
          align === 'stretch' ? 'w-full' : '',
          className
        )}
      >
        {items.map((item) => {
          const active = item.value === value
          return (
            <button
              key={item.value}
              role="tab"
              id={`${baseId}-tab-${item.value}`}
              aria-selected={active}
              aria-controls={`${baseId}-panel-${item.value}`}
              // 不可用的页签给 aria-disabled 而不是 disabled：
              // disabled 的按钮不可聚焦，键盘用户就永远到不了它，也就不知道它存在
              aria-disabled={item.disabled || undefined}
              tabIndex={active ? 0 : -1}
              onClick={() => !item.disabled && onChange(item.value)}
              className={cn(
                'relative -mb-px inline-flex items-center gap-2 border-b-2 px-3 py-2',
                'text-[var(--nx-text-sm)] whitespace-nowrap',
                'transition-colors duration-[var(--nx-dur-fast)]',
                active
                  ? 'border-[var(--nx-color-primary)] text-[var(--nx-color-text)] font-[var(--nx-weight-medium)]'
                  : 'border-transparent text-[var(--nx-color-text-secondary)] hover:text-[var(--nx-color-text)]',
                item.disabled && 'cursor-not-allowed opacity-[0.55]'
              )}
            >
              {item.label}
              {item.badge}
            </button>
          )
        })}
      </div>

      <div
        role="tabpanel"
        id={`${baseId}-panel-${value}`}
        aria-labelledby={`${baseId}-tab-${value}`}
        // tabIndex=0：面板内如果没有可聚焦元素，键盘用户无法滚动它
        tabIndex={0}
        className={cn('flex-1 py-4 focus-visible:outline-none', panelClassName)}
      >
        {panel(value)}
      </div>
    </div>
  )
}

/** 便捷 Hook：把"当前页签值"收成组件内部状态。适合不需要外部控制页签的场合。 */
export function useTabs(initial: string): [string, (v: string) => void] {
  const [value, setValue] = useState(initial)
  return [value, setValue]
}
