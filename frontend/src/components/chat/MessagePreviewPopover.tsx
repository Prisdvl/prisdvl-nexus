import { createPortal } from 'react-dom'
import { useLayoutEffect, useRef, useState, type RefObject } from 'react'
import { messageSummary } from '@/lib/summary'
import type { ChatMsg } from '@/data'

/** 预览浮层与视口边缘的最小留白 */
const VIEWPORT_PAD = 14
/** 浮层与横线之间的间距 */
const GAP = 12
const POPOVER_W = 300

interface Props {
  message: ChatMsg
  index: number
  /** 被 hover 的横线元素（定位基准） */
  anchor: HTMLElement | null
  onMouseEnter: () => void
  onMouseLeave: () => void
  onJump: () => void
}

/**
 * 消息预览浮层。
 *
 * 关键约束（踩过坑）：**必须 Portal 到 document.body + position: fixed**。
 * 对话页里 `.thread` 是 `overflow-y: auto`、`.chat-fixed`/`.shell-main` 是 `overflow: hidden`，
 * 把浮层放在轨道内部再指望 `overflow: visible` 一定会被裁掉（只露一小块）。
 * 挂到 body 后，再用 getBoundingClientRect() 自己算 fixed 坐标，就与任何祖先的
 * overflow / border-radius / transform 无关了。
 *
 * 定位：默认贴在横线**左侧**（轨道本身在内容右侧，往左才在视口内），
 * 左侧空间不够时翻到右侧；上下同样做边界收敛，始终留 VIEWPORT_PAD。
 */
export default function MessagePreviewPopover({ message, index, anchor, onMouseEnter, onMouseLeave, onJump }: Props) {
  const ref = useRef<HTMLDivElement>(null)
  const [pos, setPos] = useState<{ left: number; top: number; place: 'left' | 'right' } | null>(null)

  useLayoutEffect(() => {
    if (!anchor) return
    const compute = () => {
      const a = anchor.getBoundingClientRect()
      const el = ref.current
      const h = el?.offsetHeight ?? 120
      const w = el?.offsetWidth ?? POPOVER_W
      const vw = window.innerWidth
      const vh = window.innerHeight

      /* 优先左侧；放不下再翻到右侧 */
      const roomLeft = a.left - GAP - VIEWPORT_PAD
      const place: 'left' | 'right' = roomLeft >= w ? 'left' : 'right'
      let left = place === 'left' ? a.left - GAP - w : a.right + GAP
      left = Math.min(Math.max(left, VIEWPORT_PAD), vw - w - VIEWPORT_PAD)

      /* 垂直：与横线居中对齐，再收敛到视口内 */
      let top = a.top + a.height / 2 - h / 2
      top = Math.min(Math.max(top, VIEWPORT_PAD), vh - h - VIEWPORT_PAD)

      setPos({ left, top, place })
    }
    compute()
    /* 滚动/缩放时跟随（横线位置会变） */
    window.addEventListener('scroll', compute, true)
    window.addEventListener('resize', compute)
    return () => {
      window.removeEventListener('scroll', compute, true)
      window.removeEventListener('resize', compute)
    }
  }, [anchor, message.id])

  if (!anchor) return null

  return createPortal(
    <div
      ref={ref}
      className="msg-preview"
      data-place={pos?.place ?? 'left'}
      style={{ left: pos?.left ?? -9999, top: pos?.top ?? -9999, width: POPOVER_W }}
      role="tooltip"
      onMouseEnter={onMouseEnter}
      onMouseLeave={onMouseLeave}
      onClick={onJump}
    >
      {/* 轨道只展示"我的提问"，因此不再重复标注角色（去掉「我 / 助手」标签） */}
      <div className="msg-preview__head">
        <span className="msg-preview__label">提问</span>
        <span className="msg-preview__index">#{index}</span>
      </div>
      <p className="msg-preview__body">{messageSummary(message.text)}</p>
      <p className="msg-preview__hint">点击跳转</p>
    </div>,
    document.body
  )
}

/** 让 TS 满意：RefObject 类型在别处可能被引用 */
export type AnchorRef = RefObject<HTMLElement | null>
