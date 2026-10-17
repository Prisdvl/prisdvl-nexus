import { useEffect, useRef, type ReactNode } from 'react'
import { X } from 'lucide-react'
import { appCopy } from '@/lib/appCopy'
import { useLocale } from '@/lib/locale'

interface ModalProps {
  open: boolean
  title: ReactNode
  onClose: () => void
  children: ReactNode
  /** 底部操作区（可选） */
  footer?: ReactNode
  /** 宽度 */
  width?: number
  /** 关闭前拦截（例如有未保存内容时二次确认） */
  shouldBlockClose?: () => boolean
}

const FOCUSABLE = 'a[href],button:not([disabled]),input:not([disabled]),select,textarea,[tabindex]:not([tabindex="-1"])'

/**
 * 统一弹窗外壳。集中处理三件以前每个弹窗各写一遍、且经常漏掉的事：
 * 1. **关闭路径统一**：Esc、点遮罩、右上角关闭按钮，且都能被 shouldBlockClose 拦截；
 * 2. **焦点陷阱**：Tab / Shift+Tab 在弹窗内循环，不会跑到背后的页面；
 * 3. **焦点归还**：关闭后把焦点还给打开它的那个元素（键盘用户不会"丢位置"）。
 */
export default function Modal({ open, title, onClose, children, footer, width = 560, shouldBlockClose }: ModalProps) {
  const closeAria = appCopy(useLocale().locale).common.close
  const panelRef = useRef<HTMLDivElement>(null)
  const lastActive = useRef<HTMLElement | null>(null)

  const requestClose = () => {
    if (shouldBlockClose?.()) return
    onClose()
  }

  useEffect(() => {
    if (!open) return
    lastActive.current = document.activeElement as HTMLElement | null

    // 初始焦点：优先 autofocus（如表单输入），否则第一个可聚焦元素，否则面板本身
    const panel = panelRef.current
    const preferred = panel?.querySelector<HTMLElement>('[autofocus]')
    const first = preferred ?? panel?.querySelector<HTMLElement>(FOCUSABLE)
    ;(first ?? panel)?.focus({ preventScroll: true })

    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation()
        requestClose()
        return
      }
      if (e.key !== 'Tab' || !panel) return
      const nodes = Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE)).filter((el) => el.offsetParent !== null)
      if (nodes.length === 0) return
      const firstNode = nodes[0]
      const lastNode = nodes[nodes.length - 1]
      if (e.shiftKey && document.activeElement === firstNode) {
        e.preventDefault()
        lastNode.focus()
      } else if (!e.shiftKey && document.activeElement === lastNode) {
        e.preventDefault()
        firstNode.focus()
      }
    }

    document.addEventListener('keydown', onKey, true)
    const prevOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.removeEventListener('keydown', onKey, true)
      document.body.style.overflow = prevOverflow
      lastActive.current?.focus?.({ preventScroll: true })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  if (!open) return null

  return (
    <div className="overlay" onMouseDown={(e) => { if (e.target === e.currentTarget) requestClose() }}>
      <div
        ref={panelRef}
        className="dialog"
        role="dialog"
        aria-modal="true"
        aria-label={typeof title === 'string' ? title : undefined}
        tabIndex={-1}
        style={{ width: `min(${width}px, 100%)` }}
      >
        <div className="dhead">
          <h3>{title}</h3>
          <button className="close" type="button" aria-label={closeAria} onClick={requestClose}>
            <X size={17} />
          </button>
        </div>
        <div className="dbody">{children}</div>
        {footer && <div className="dfoot">{footer}</div>}
      </div>
    </div>
  )
}