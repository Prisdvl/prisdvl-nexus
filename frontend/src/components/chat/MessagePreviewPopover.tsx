import { createPortal } from 'react-dom'
import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { messageSummary } from '@/lib/summary'
import type { ChatMsg } from '@/data'

const VIEWPORT_PAD = 14
const GAP = 12
const POPOVER_W = 320
/** 隐藏动画兜底：动画被关掉或 animationend 未触发时仍要卸载 */
const CLOSE_FALLBACK_MS = 280

export type PanelPhase = 'open' | 'closing'

interface Props {
  messages: ChatMsg[]
  /** 当前阅读提问序号（1-based） */
  currentIndex: number
  /** 整条轨道锚点：面板位置固定贴轨道，不跟单根横线跑 */
  anchor: HTMLElement | null
  phase: PanelPhase
  onMouseEnter: () => void
  onMouseLeave: () => void
  onJump: (index: number) => void
  /** closing 动画结束后由父级卸掉节点 */
  onClosed: () => void
}

/** Portal 预览：靠近轨道时整块出现，带显示/隐藏动画。 */
export default function MessagePreviewPopover({
  messages,
  currentIndex,
  anchor,
  phase,
  onMouseEnter,
  onMouseLeave,
  onJump,
  onClosed,
}: Props) {
  const ref = useRef<HTMLDivElement>(null)
  const [pos, setPos] = useState<{ left: number; top: number; place: 'left' | 'right' } | null>(null)

  useEffect(() => {
    if (phase !== 'closing') return
    const t = window.setTimeout(onClosed, CLOSE_FALLBACK_MS)
    return () => window.clearTimeout(t)
  }, [phase, onClosed])

  useLayoutEffect(() => {
    if (!anchor) return
    let alive = true
    const compute = () => {
      if (!alive) return
      const a = anchor.getBoundingClientRect()
      const el = ref.current
      const h = Math.min(el?.offsetHeight ?? 160, window.innerHeight - VIEWPORT_PAD * 2)
      const w = Math.min(el?.offsetWidth ?? POPOVER_W, window.innerWidth - VIEWPORT_PAD * 2)
      const place: 'left' | 'right' = a.left - GAP - VIEWPORT_PAD >= w ? 'left' : 'right'
      const left = Math.min(
        Math.max(place === 'left' ? a.left - GAP - w : a.right + GAP, VIEWPORT_PAD),
        window.innerWidth - w - VIEWPORT_PAD,
      )
      /* 相对整条轨道垂直居中，移动横线时位置不变 */
      const top = Math.min(
        Math.max(a.top + a.height / 2 - h / 2, VIEWPORT_PAD),
        window.innerHeight - h - VIEWPORT_PAD,
      )
      setPos({ left, top, place })
    }
    compute()
    window.addEventListener('scroll', compute, true)
    window.addEventListener('resize', compute)
    const ro = new ResizeObserver(compute)
    ro.observe(anchor)
    if (ref.current) ro.observe(ref.current)
    return () => {
      alive = false
      window.removeEventListener('scroll', compute, true)
      window.removeEventListener('resize', compute)
      ro.disconnect()
    }
  }, [anchor, messages.length, currentIndex, phase])

  useLayoutEffect(() => {
    if (phase !== 'open') return
    const active = ref.current?.querySelector<HTMLElement>('.msg-preview__item[data-active]')
    active?.scrollIntoView({ block: 'nearest' })
  }, [currentIndex, messages.length, phase])

  if (!anchor) return null

  return createPortal(
    <div
      ref={ref}
      className="msg-preview"
      data-place={pos?.place ?? 'left'}
      data-phase={phase}
      style={{ left: pos?.left ?? -9999, top: pos?.top ?? -9999, width: POPOVER_W }}
      role="group"
      aria-label="本次对话的问题导航"
      onMouseEnter={onMouseEnter}
      onMouseLeave={onMouseLeave}
      onFocusCapture={onMouseEnter}
      onBlurCapture={(e) => {
        if (!ref.current?.contains(e.relatedTarget as Node | null)) onMouseLeave()
      }}
      onKeyDown={(e) => { if (e.key === 'Escape') onMouseLeave() }}
      onAnimationEnd={(e) => {
        if (e.target !== ref.current) return
        if (phase === 'closing') onClosed()
      }}
    >
      <div className="msg-preview__list">
        {messages.map((message, index) => {
          const qIndex = index + 1
          const isCurrent = qIndex === currentIndex
          return (
            <button
              key={`${message.id}-${qIndex}`}
              type="button"
              className="msg-preview__item"
              data-active={isCurrent ? '' : undefined}
              aria-current={isCurrent ? 'location' : undefined}
              aria-label={`跳转到第 ${qIndex} 个提问：${messageSummary(message.text)}`}
              title={message.text}
              onClick={() => onJump(qIndex)}
            >
              <span className="msg-preview__item-index">{qIndex}</span>
              <span className="msg-preview__item-text">{messageSummary(message.text)}</span>
            </button>
          )
        })}
      </div>
    </div>,
    document.body,
  )
}
