import { useCallback, useEffect, useMemo, useRef, useState, type RefObject } from 'react'
import MessageRail, { type RailItem } from './MessageRail'
import MessagePreviewPopover from './MessagePreviewPopover'
import type { ChatMsg } from '@/data'
import '@/styles/msgnav.css'

/** hover 离开后的宽限期：够鼠标从横线移到浮层上（否则会闪） */
const HIDE_DELAY = 120
/** 程序化滚动期间忽略观察器，避免"点了跳转又被观察器抢回 active" */
const PROGRAMMATIC_MS = 600

interface Props {
  messages: ChatMsg[]
  /** 滚动容器（`.thread`），用于观察与跳转 */
  containerRef: RefObject<HTMLElement | null>
}

/**
 * 提问导航：右侧横线轨道 + Hover 预览。
 *
 * - 横线**只对应我的提问**（助手回答不占横线）；
 * - 当前提问用 **IntersectionObserver** 判定（不在 scroll 里逐帧遍历消息）；
 * - 点击横线平滑跳转，并用"程序化滚动"时间窗抑制观察器抖动；
 * - 预览浮层全局只有一个实例（Portal 到 body），只渲染当前 hover 的那条。
 */
export default function MessageNavigator({ messages, containerRef }: Props) {
  /* 只取我的提问作为跳转锚点 */
  const questions = useMemo(() => messages.filter((m) => m.role === 'user'), [messages])
  const items = useMemo<RailItem[]>(
    () => questions.map((m, i) => ({ id: m.id, index: i + 1 })),
    [questions]
  )

  const [activeId, setActiveId] = useState<string | null>(null)
  const [hovered, setHovered] = useState<{ item: RailItem; el: HTMLElement | null } | null>(null)
  const hideTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const programmaticUntil = useRef(0)

  /* ---------- 当前阅读位置（IntersectionObserver，只观察提问节点） ---------- */
  useEffect(() => {
    const root = containerRef.current
    if (!root || items.length === 0) return

    const visible = new Set<string>()
    const order = items.map((i) => i.id)

    const observer = new IntersectionObserver(
      (entries) => {
        if (performance.now() < programmaticUntil.current) return
        for (const e of entries) {
          const id = (e.target as HTMLElement).dataset.mid
          /*
           * ⚠️ 这里**不能**再 `Number(dataset.mid)`。
           * 消息 id 现在是字符串（后端雪花 ID / `m_<ts>` / `seed_*` / `tmp_*`），
           * 而 DOM attribute 一律是字符串：`Number("2108423642637959170")`
           * 会得到 2.108423642639e+18 —— 末位精度丢失，
           * 之后所有 `visible.has(id)` 判定失配，
           * 表现是"滚动时当前提问高亮不更新"，而且**不报错**，最难查。
           * 所以空值判断用 `!id`，不是 `Number.isFinite`。
           */
          if (!id) continue
          if (e.isIntersecting) visible.add(id)
          else visible.delete(id)
        }
        /* 滚到底：正在读的就是最后一个提问（命中带在视口上部，最后一条可能进不来） */
        const atBottom = root.scrollHeight - root.scrollTop - root.clientHeight <= 8
        if (atBottom) { setActiveId(order[order.length - 1]); return }
        if (visible.size === 0) return
        /* 取"最靠前的那条可见提问"作为当前项，避免多条同时 active 造成抖动 */
        const first = order.find((id) => visible.has(id))
        if (first !== undefined) setActiveId(first)
      },
      {
        root,
        /* 顶部细带命中：谁跨过视口上方 24% 的位置，就算"当前正在读" */
        rootMargin: '0px 0px -76% 0px',
        threshold: 0,
      }
    )

    for (const item of items) {
      const node = root.querySelector<HTMLElement>(`[data-mid="${item.id}"]`)
      if (node) observer.observe(node)
    }
    return () => observer.disconnect()
  }, [items, containerRef])

  /* 会话切换 / 提问增长时，兜底把 active 置为最后一个提问 */
  useEffect(() => {
    if (items.length === 0) { setActiveId(null); return }
    setActiveId((prev) => (prev !== null && items.some((i) => i.id === prev) ? prev : items[items.length - 1].id))
  }, [items])

  /* ---------- hover / 宽限期 ---------- */
  const show = useCallback((item: RailItem, el: HTMLElement | null) => {
    clearTimeout(hideTimer.current)
    if (!el) { hideTimer.current = setTimeout(() => setHovered(null), HIDE_DELAY); return }
    setHovered({ item, el })
  }, [])

  const keep = useCallback(() => clearTimeout(hideTimer.current), [])
  const scheduleHide = useCallback(() => {
    clearTimeout(hideTimer.current)
    hideTimer.current = setTimeout(() => setHovered(null), HIDE_DELAY)
  }, [])
  useEffect(() => () => clearTimeout(hideTimer.current), [])

  /* ---------- 跳转 ---------- */
  const jump = useCallback((id: string) => {
    const root = containerRef.current
    const node = root?.querySelector<HTMLElement>(`[data-mid="${id}"]`)
    if (!root || !node) return
    programmaticUntil.current = performance.now() + PROGRAMMATIC_MS
    setActiveId(id)                       // 立刻高亮，不等滚动结束
    setHovered(null)
    root.scrollTo({ top: Math.max(0, node.offsetTop - 16), behavior: 'smooth' })
  }, [containerRef])

  if (items.length < 2) return null

  const hoveredMsg = hovered ? messages.find((m) => m.id === hovered.item.id) ?? null : null

  return (
    <>
      <MessageRail items={items} activeId={activeId} onHover={show} onJump={jump} />
      {hoveredMsg && hovered && (
        <MessagePreviewPopover
          message={hoveredMsg}
          index={hovered.item.index}
          anchor={hovered.el}
          onMouseEnter={keep}
          onMouseLeave={scheduleHide}
          onJump={() => jump(hoveredMsg.id)}
        />
      )}
    </>
  )
}
