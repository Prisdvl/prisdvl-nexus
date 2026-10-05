import { useEffect, useRef, type CSSProperties, type ReactElement, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { cn } from '@/lib/utils'

/**
 * 浮层的底层能力：Portal、ESC 关闭、焦点管理、body 滚动锁、遮罩点击。
 *
 * ## 为什么不用 `<dialog>` 原生元素
 *
 * 原生 `<dialog>` 的 `showModal()` 自带焦点陷阱与顶层渲染，理论上更省事。
 * 但它在 React 里的集成有坑：关闭时 `:close` 事件与 React 的 state 不同步
 * （点遮罩关闭后 DOM 变了而 React 不知道），需要额外 ref 同步；
 * 且 `::backdrop` 的样式在部分场景不可控。本文不打算为"少写 40 行"去踩这些坑。
 *
 * ## 焦点陷阱：为什么必须自己做
 *
 * 浮层打开时如果不做处理，Tab 会走到**背后的页面**上——键盘用户会"看不见自己在哪"。
 * 这里是本项目**唯一**的浮层底层，所以只实现一次：
 *   1. 打开时把焦点移进浮层；
 *   2. Tab 在浮层内循环；
 *   3. 关闭时把焦点还给触发元素。
 *
 * Vue 版在这件事上出过问题：资料弹窗点「修改密码」后两个弹窗**同时可见**
 * （关闭动画期间），导致 E2E 定位歧义（Phase 0 记录）。React 版用"关闭时立刻卸载"
 * 规避了它——没有动画期，就不存在"两个都在"。
 */
export interface OverlayProps {
  open: boolean
  onClose: () => void
  /** 遮罩点击是否关闭。表单类弹窗应为 false（误点会丢输入）。 */
  closeOnOverlayClick?: boolean
  children: ReactNode
  /** 传给浮层最外层（遮罩容器）的类名。 */
  className?: string
  /** 无障碍标签。 */
  label?: string
  /** 面板类名：宽度、圆角、阴影。 */
  panelClassName?: string
  /**
   * 面板的内联样式。
   *
   * 为什么宽度走这里而不是拼接 Tailwind 类名：拼接出来的类名
   * （`max-w-[${width}]`）**Tailwind 扫描器看不到完整字面量**，
   * 生产构建会把它裁掉，表现为"宽度 mysteriously 不生效"且不报错。
   */
  panelStyle?: CSSProperties
  hideHeader?: boolean
  title?: ReactNode
}

const FOCUSABLE =
  'a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),' +
  'textarea:not([disabled]),[tabindex]:not([tabindex="-1"])'

export function Overlay({
  open,
  onClose,
  closeOnOverlayClick = true,
  children,
  className,
  label,
  panelClassName,
  panelStyle,
  hideHeader = false,
  title
}: OverlayProps): ReactElement | null {
  const panelRef = useRef<HTMLDivElement>(null)
  const restoreFocusRef = useRef<HTMLElement | null>(null)

  // 打开时记住"从哪来"，关闭时还回去。
  // 不还的话键盘用户关闭弹窗后会回到 body，只能从头 Tab。
  useEffect(() => {
    if (!open) return
    restoreFocusRef.current = document.activeElement as HTMLElement | null
    // 等面板挂载后再移焦点；第一帧就移会失败（元素还不存在）
    const raf = requestAnimationFrame(() => {
      panelRef.current?.querySelector<HTMLElement>(FOCUSABLE)?.focus()
    })
    return () => {
      cancelAnimationFrame(raf)
      restoreFocusRef.current?.focus?.()
    }
  }, [open])

  // ESC 关闭 + Tab 循环
  useEffect(() => {
    if (!open) return

    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation()
        onClose()
        return
      }
      if (e.key !== 'Tab') return

      const nodes = panelRef.current?.querySelectorAll<HTMLElement>(FOCUSABLE)
      if (!nodes || nodes.length === 0) return
      const list = Array.from(nodes)
      const first = list[0]
      const last = list[list.length - 1]
      if (!first || !last) return

      // 焦点在浮层之外（例如用户点了遮罩）→ 直接拉回第一个
      if (!panelRef.current?.contains(document.activeElement)) {
        e.preventDefault()
        first.focus()
        return
      }
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault()
        last.focus()
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault()
        first.focus()
      }
    }

    document.addEventListener('keydown', onKeyDown, true)
    return () => document.removeEventListener('keydown', onKeyDown, true)
  }, [open, onClose])

  // 背景滚动锁。
  // 用 overflow:hidden 而不是 position:fixed ——后者会让背景在 iOS 上跳一下。
  useEffect(() => {
    if (!open) return
    const prev = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = prev
    }
  }, [open])

  if (!open) return null

  return createPortal(
    <div
      className={cn('fixed inset-0 z-[var(--nx-z-modal)]', className)}
      // 遮罩层本身不是交互元素，只在允许点击关闭时挂 handler，
      // 否则它会挡住下面的内容却"什么也不做"，让人以为页面卡住了。
      onMouseDown={closeOnOverlayClick ? (e) => e.target === e.currentTarget && onClose() : undefined}
    >
      <div className="absolute inset-0 bg-[oklch(0_0_0/0.4)]" />
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        {...(label ? { 'aria-label': label } : {})}
        style={panelStyle}
        className={cn(
          'relative flex flex-col overflow-hidden bg-[var(--nx-color-surface)] shadow-[var(--nx-shadow-lg)]',
          panelClassName
        )}
      >
        {!hideHeader ? (
          <div className="flex items-center justify-between gap-3 border-b border-[var(--nx-color-border)] px-4 py-3">
            <h2 className="text-[var(--nx-text-lg)] font-[var(--nx-weight-semibold)]">{title}</h2>
            <button
              type="button"
              onClick={onClose}
              aria-label="关闭"
              className="grid h-7 w-7 place-items-center rounded-[var(--nx-radius-sm)] text-[var(--nx-color-text-muted)] hover:bg-[var(--nx-color-surface-muted)]"
            >
              <svg viewBox="0 0 20 20" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.6">
                <path d="M5 5l10 10M15 5L5 15" strokeLinecap="round" />
              </svg>
            </button>
          </div>
        ) : null}
        {children}
      </div>
    </div>,
    document.body
  )
}
