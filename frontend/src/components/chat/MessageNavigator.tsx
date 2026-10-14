import { useCallback, useEffect, useMemo, useRef, useState, type RefObject } from 'react'
import MessageRail, { type RailItem } from './MessageRail'
import MessagePreviewPopover, { type PanelPhase } from './MessagePreviewPopover'
import type { ChatMsg } from '@/data'
import '@/styles/msgnav.css'

/** 离开轨道/浮层后的宽限期：够鼠标从轨道移到浮层上 */
const HIDE_DELAY = 160
/** 程序化滚动期间忽略观察器，避免"点了跳转又被观察器抢回 active" */
const PROGRAMMATIC_MS = 600
/** 视口顶部多少比例算"正在读"命中带（与 IntersectionObserver rootMargin 对齐） */
const READ_BAND = 0.24

interface Props {
  messages: ChatMsg[]
  /** 滚动容器（`.thread`），用于观察与跳转 */
  containerRef: RefObject<HTMLElement | null>
}

/** 提问序号锚点（1-based）。不用 data-mid：雪花 id 撞车时 querySelector 只会命中第一条。 */
function qidxSelector(index: number): string {
  return `[data-qidx="${index}"]`
}

/**
 * 按滚动位置推算当前提问序号（1-based）。
 * 语义是标准 scroll-spy：越过阅读线的最后一条。
 */
function resolveActiveIndex(root: HTMLElement, count: number): number | null {
  if (count < 1) return null
  const atBottom = root.scrollHeight - root.scrollTop - root.clientHeight <= 8
  if (atBottom) return count

  const rootRect = root.getBoundingClientRect()
  const bandBottom = rootRect.top + root.clientHeight * READ_BAND
  let current = 1
  for (let i = 1; i <= count; i++) {
    const node = root.querySelector<HTMLElement>(qidxSelector(i))
    if (!node) continue
    if (node.getBoundingClientRect().top <= bandBottom) current = i
    else break
  }
  return current
}

/**
 * 提问导航：右侧横线轨道 + 靠近整条轨道时显示的固定预览面板。
 *
 * 显隐要点：
 * - 用 pointerInside 跟踪指针是否仍在轨道/浮层上，避免"关面板后鼠标还在轨道上、
 *   mouseenter 不再触发 → 再也打不开"；
 * - 轨道节点用 state 而不是只读 ref.current，保证浮层挂载时一定有锚点。
 */
export default function MessageNavigator({ messages, containerRef }: Props) {
  const questions = useMemo(() => messages.filter((m) => m.role === 'user'), [messages])
  const items = useMemo<RailItem[]>(
    () => questions.map((m, i) => ({ id: m.id, index: i + 1 })),
    [questions]
  )
  const count = items.length

  const [activeIndex, setActiveIndex] = useState<number | null>(null)
  /** null = 不挂载；open/closing = 挂载并播动画 */
  const [panelPhase, setPanelPhase] = useState<PanelPhase | null>(null)
  /** 轨道 DOM（state，确保浮层渲染时 anchor 一定可用） */
  const [railNode, setRailNode] = useState<HTMLElement | null>(null)
  /** 指针是否仍在轨道或浮层上（含移向浮层的途中宽限期） */
  const pointerInside = useRef(false)
  const hideTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const programmaticUntil = useRef(0)
  const syncTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)

  const readingIndex = activeIndex ?? items[items.length - 1]?.index ?? 0

  const syncFromScroll = useCallback(() => {
    const root = containerRef.current
    if (!root || count < 1) return
    const next = resolveActiveIndex(root, count)
    if (next != null) setActiveIndex(next)
  }, [containerRef, count])

  useEffect(() => {
    const root = containerRef.current
    if (!root || count < 1) return

    const observer = new IntersectionObserver(
      (entries) => {
        if (performance.now() < programmaticUntil.current) return
        if (entries.some((e) => (e.target as HTMLElement).dataset.qidx)) {
          const next = resolveActiveIndex(root, count)
          if (next != null) setActiveIndex(next)
        }
      },
      {
        root,
        rootMargin: `0px 0px -${Math.round((1 - READ_BAND) * 100)}% 0px`,
        threshold: [0, 0.01, 1],
      }
    )

    for (let i = 1; i <= count; i++) {
      const node = root.querySelector<HTMLElement>(qidxSelector(i))
      if (node) observer.observe(node)
    }
    syncFromScroll()
    return () => observer.disconnect()
  }, [count, containerRef, syncFromScroll])

  useEffect(() => {
    if (count < 1) { setActiveIndex(null); return }
    setActiveIndex((prev) => (prev != null && prev >= 1 && prev <= count ? prev : count))
  }, [count])

  useEffect(() => () => {
    clearTimeout(hideTimer.current)
    clearTimeout(syncTimer.current)
  }, [])

  const openPanel = useCallback(() => {
    pointerInside.current = true
    clearTimeout(hideTimer.current)
    setPanelPhase('open')
  }, [])

  const scheduleHide = useCallback(() => {
    clearTimeout(hideTimer.current)
    hideTimer.current = setTimeout(() => {
      /* 宽限期内又回到轨道/浮层：不要关 */
      if (pointerInside.current) return
      /* 轨道仍被 :hover（关面板后鼠标没挪开、enter 不会重触发）→ 保持开 */
      if (railNode?.matches(':hover')) {
        pointerInside.current = true
        setPanelPhase('open')
        return
      }
      setPanelPhase((prev) => (prev === 'open' ? 'closing' : prev))
    }, HIDE_DELAY)
  }, [railNode])

  const keepPanel = useCallback(() => {
    pointerInside.current = true
    clearTimeout(hideTimer.current)
    setPanelPhase('open')
  }, [])

  const onRailLeave = useCallback(() => {
    pointerInside.current = false
    scheduleHide()
  }, [scheduleHide])

  const onPanelLeave = useCallback(() => {
    pointerInside.current = false
    scheduleHide()
  }, [scheduleHide])

  const finishClose = useCallback(() => {
    setPanelPhase((prev) => {
      if (prev !== 'closing') return prev
      return null
    })
    /*
     * 关完后若指针其实还在轨道上（跳转收起 / blur 误关 / 宽限期误判），
     * mouseenter 不会再来——这里主动重开，否则表现为"有时移上去不显示"。
     */
    requestAnimationFrame(() => {
      const stillOnRail = !!railNode?.matches(':hover')
      if (pointerInside.current || stillOnRail) {
        pointerInside.current = true
        setPanelPhase('open')
      }
    })
  }, [railNode])

  const jump = useCallback((index: number) => {
    const root = containerRef.current
    const node = root?.querySelector<HTMLElement>(qidxSelector(index))
    if (!root || !node) return
    programmaticUntil.current = performance.now() + PROGRAMMATIC_MS
    setActiveIndex(index)
    /*
     * 跳转后：若指针还在轨道/浮层上，保持面板（只更新高亮）；
     * 只有指针已经离开时才收起——否则关完后鼠标停在轨道上再也打不开。
     */
    clearTimeout(hideTimer.current)
    if (!pointerInside.current && !railNode?.matches(':hover')) {
      setPanelPhase((prev) => (prev ? 'closing' : null))
    } else {
      pointerInside.current = true
      setPanelPhase('open')
    }
    const top = node.getBoundingClientRect().top - root.getBoundingClientRect().top + root.scrollTop - 16
    root.scrollTo({ top: Math.max(0, top), behavior: 'smooth' })
    clearTimeout(syncTimer.current)
    syncTimer.current = setTimeout(() => {
      programmaticUntil.current = 0
      const el = root.querySelector<HTMLElement>(qidxSelector(index))
      const box = root.getBoundingClientRect()
      const r = el?.getBoundingClientRect()
      const stillVisible = !!r && r.bottom > box.top && r.top < box.bottom
      if (stillVisible) {
        setActiveIndex(index)
        return
      }
      const next = resolveActiveIndex(root, count)
      if (next != null) setActiveIndex(next)
    }, PROGRAMMATIC_MS + 50)
  }, [containerRef, count, railNode])

  if (items.length < 2) return null

  return (
    <>
      <MessageRail
        items={items}
        activeIndex={activeIndex}
        open={panelPhase != null}
        railRef={setRailNode}
        onPanelEnter={openPanel}
        onPanelLeave={onRailLeave}
        onJump={jump}
      />
      {panelPhase && railNode && (
        <MessagePreviewPopover
          messages={questions}
          currentIndex={readingIndex}
          anchor={railNode}
          phase={panelPhase}
          onMouseEnter={keepPanel}
          onMouseLeave={onPanelLeave}
          onJump={jump}
          onClosed={finishClose}
        />
      )}
    </>
  )
}
