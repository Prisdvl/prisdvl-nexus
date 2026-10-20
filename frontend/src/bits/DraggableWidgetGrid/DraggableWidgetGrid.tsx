/**
 * Draggable Widget Grid —— 可拖动重排的磁贴网格（工作台用）。
 *
 * 布局是「顺序 + 每个磁贴的尺寸」：精确铺砖器把这个顺序变成任意列数下的无空隙
 * 矩形，Motion 负责把每次变化动画出来。
 *
 * - 鼠标与触控笔：按下即拖；触摸屏要先长按，否则页面没法滚动。
 * - 键盘：聚焦某个磁贴，按住 Alt + 方向键移动。
 * - 样式全部走本项目的 CSS 变量（`--surface` / `--border` / `--text`），
 *   因此亮色与暗色主题都不需要额外分支。
 *
 * 与原稿的差异（移植时必须改的地方，见文件末尾注释）：
 * 1. 原稿用 Tailwind（`bg-card` / `ring-border` / `text-foreground`），本项目没有
 *    Tailwind，改为 CSS Module + 主题变量；
 * 2. 原稿顶部有 `'use client'`，那是 Next.js App Router 的 RSC 指令，本项目是
 *    Vite SPA，没有 server components，该指令只会变成一个无效的字符串字面量，
 *    所以没有保留；
 * 3. 增加 `persistKey`：把顺序与尺寸写进 localStorage，刷新后保持。
 */
import {
  memo,
  useCallback,
  useEffect,
  useId,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type KeyboardEvent as ReactKeyboardEvent,
  type PointerEvent as ReactPointerEvent,
  type ReactNode,
} from 'react'
import { MotionConfig, motion, useDragControls } from 'motion/react'
import styles from './DraggableWidgetGrid.module.css'

/* ------------------------------------------------------------------ *
 * Types
 * ------------------------------------------------------------------ */

/** 列 × 行：`sm` 1×1、`wide` 2×1、`tall` 1×2、`lg` 2×2。 */
export type WidgetSize = 'sm' | 'wide' | 'tall' | 'lg'

export interface WidgetItem {
  /** 稳定且唯一的 id。 */
  id: string
  size: WidgetSize
  /** 供辅助技术读取的名称。 */
  label?: string
}

export interface DraggableWidgetGridProps {
  /** 初始排布；挂载后由网格自己管理顺序。 */
  items: WidgetItem[]
  /** 拖放或键盘移动结束后回调新的排布。 */
  onChange?: (items: WidgetItem[]) => void
  /** 渲染磁贴内容。 */
  renderItem: (item: WidgetItem, size: WidgetSize) => ReactNode
  /** 是否允许拖动与键盘移动。 */
  editable?: boolean
  /** 列数上限。 */
  maxColumns?: number
  /** 目标格宽（px），列数由它推导。 */
  cellSize?: number
  /** 磁贴间距（px）。 */
  gap?: number
  /** 磁贴圆角（px）。 */
  radius?: number
  /** localStorage 键；传了就自动持久化顺序与尺寸。 */
  persistKey?: string
  /** 读屏用的操作提示（文案由调用方给，组件自己不读字典）。 */
  hint?: string
  className?: string
}

const SPANS: { [K in WidgetSize]: { col: number; row: number } } = {
  sm: { col: 1, row: 1 },
  wide: { col: 2, row: 1 },
  tall: { col: 1, row: 2 },
  lg: { col: 2, row: 2 },
}

const SIZE_LABELS: { [K in WidgetSize]: string } = {
  sm: 'Small',
  wide: 'Wide',
  tall: 'Tall',
  lg: 'Large',
}

const useIsoLayoutEffect =
  typeof window === 'undefined' ? useEffect : useLayoutEffect

function sizeOf(w: number, h: number): WidgetSize {
  if (w >= 2 && h >= 2) return 'lg'
  if (w >= 2) return 'wide'
  if (h >= 2) return 'tall'
  return 'sm'
}

/* ------------------------------------------------------------------ *
 * 布局
 * ------------------------------------------------------------------ */

interface Placement {
  id: string
  col: number
  row: number
  w: number
  h: number
}

interface Box {
  col: number
  row: number
  w: number
  h: number
}

const overlaps = (a: Box, b: Box) =>
  a.col < b.col + b.w &&
  b.col < a.col + a.w &&
  a.row < b.row + b.h &&
  b.row < a.row + a.h

const contains = (outer: Box, inner: Box) =>
  inner.col >= outer.col &&
  inner.row >= outer.row &&
  inner.col + inner.w <= outer.col + outer.w &&
  inner.row + inner.h <= outer.row + outer.h

function spanOf(item: WidgetItem, columns: number) {
  return { w: Math.min(SPANS[item.size].col, columns), h: SPANS[item.size].row }
}

/**
 * 摆放所有磁贴。先试精确铺砖（无空隙、不拉伸）；失败退回行打包器，
 * 由它把磁贴撑大去补洞。
 */
function layout(items: WidgetItem[], columns: number): Placement[] {
  if (columns < 1 || items.length === 0) return []
  return tile(items, columns) ?? pack(items, columns)
}

const TILING_BUDGET = 20000

/**
 * 精确铺砖：阅读顺序里的第一个空格，放最早能填进去、且放完还能摆下其余磁贴的那个。
 */
function tile(items: WidgetItem[], columns: number): Placement[] | null {
  const spans = items.map((item) => spanOf(item, columns))
  const area = spans.reduce((n, s) => n + s.w * s.h, 0)
  const rows = Math.ceil(area / columns)
  const grid: boolean[] = new Array(rows * columns).fill(false)
  const used: boolean[] = new Array(items.length).fill(false)
  const out: Placement[] = []
  let budget = TILING_BUDGET

  const fits = (w: number, h: number, r: number, c: number) => {
    if (c + w > columns || r + h > rows) return false
    for (let y = r; y < r + h; y++)
      for (let x = c; x < c + w; x++) if (grid[y * columns + x]) return false
    return true
  }
  const mark = (w: number, h: number, r: number, c: number, v: boolean) => {
    for (let y = r; y < r + h; y++)
      for (let x = c; x < c + w; x++) grid[y * columns + x] = v
  }

  const place = (count: number): boolean => {
    if (count === items.length) return true
    if (--budget < 0) return false
    const i = grid.indexOf(false)
    if (i < 0) return false
    const r = Math.floor(i / columns)
    const c = i % columns
    // 同一形状的磁贴在这个格子里是可以互换的。
    const tried = new Set([] as string[])
    for (let k = 0; k < items.length; k++) {
      if (used[k]) continue
      const { w, h } = spans[k]
      const shape = `${w}x${h}`
      if (tried.has(shape) || !fits(w, h, r, c)) continue
      tried.add(shape)
      used[k] = true
      mark(w, h, r, c, true)
      out.push({ id: items[k].id, col: c, row: r, w, h })
      if (place(count + 1)) return true
      out.pop()
      mark(w, h, r, c, false)
      used[k] = false
    }
    return false
  }

  return place(0) ? out : null
}

/** 退路：按顺序填行，再把每行剩余的空格并给相邻磁贴。 */
function pack(items: WidgetItem[], columns: number): Placement[] {
  const out: Placement[] = []
  let row = 0
  let queue = items.map((item) => ({ id: item.id, ...spanOf(item, columns) }))

  while (queue.length > 0) {
    const height = Math.max(...queue.slice(0, columns).map((q) => q.h))
    const cells: boolean[] = new Array(height * columns).fill(false)
    const band: Placement[] = []
    const rest: typeof queue = []

    for (const q of queue) {
      let spot = -1
      for (let i = 0; i < cells.length && spot < 0; i++) {
        const r = Math.floor(i / columns)
        const c = i % columns
        if (c + q.w > columns || r + q.h > height) continue
        let free = true
        for (let y = r; y < r + q.h && free; y++)
          for (let x = c; x < c + q.w && free; x++)
            if (cells[y * columns + x]) free = false
        if (free) spot = i
      }
      if (spot < 0 || rest.length > 0) {
        rest.push(q)
        continue
      }
      const r = Math.floor(spot / columns)
      const c = spot % columns
      for (let y = r; y < r + q.h; y++)
        for (let x = c; x < c + q.w; x++) cells[y * columns + x] = true
      band.push({ id: q.id, col: c, row: r, w: q.w, h: q.h })
    }

    // 先把磁贴向右撑，再向下撑，把行里剩下的空格吃掉。
    for (let i = 0; i < cells.length; i++) {
      if (cells[i]) continue
      const r = Math.floor(i / columns)
      const c = i % columns
      const left = band.find(
        (p) => p.col + p.w === c && p.row <= r && p.row + p.h > r && p.h === 1,
      )
      const above = band.find((p) => p.row + p.h === r && p.col === c && p.w === 1)
      const grow = left ?? above
      if (!grow) continue
      if (grow === left) grow.w += 1
      else grow.h += 1
      cells[i] = true
    }

    out.push(...band.map((p) => ({ ...p, row: p.row + row })))
    row += height
    queue = rest
  }
  return out
}

/** 把 `items` 重排成布局的阅读顺序。 */
function canonical(items: WidgetItem[], columns: number): WidgetItem[] {
  const places = layout(items, columns)
  if (places.length !== items.length) return items
  const byId = new Map(items.map((item) => [item.id, item]))
  const sorted = [...places]
    .sort((a, b) => a.row - b.row || a.col - b.col)
    .map((p) => byId.get(p.id) as WidgetItem)
  if (sorted.every((item, i) => item === items[i])) return items
  // 只有阅读顺序仍产出同一套摆位时才采用它。
  const at = new Map(places.map((p) => [p.id, p]))
  const same = layout(sorted, columns).every((p) => {
    const q = at.get(p.id)
    return q && q.col === p.col && q.row === p.row && q.w === p.w && q.h === p.h
  })
  return same ? sorted : items
}

function moveTo(items: WidgetItem[], id: string, index: number) {
  const from = items.findIndex((item) => item.id === id)
  if (from < 0 || from === index || index < 0 || index >= items.length) return items
  const next = [...items]
  const [moved] = next.splice(from, 1)
  next.splice(index, 0, moved)
  return next
}

const sameOrder = (a: WidgetItem[], b: WidgetItem[]) =>
  a.length === b.length && a.every((item, i) => item.id === b[i].id)

/* ------------------------------------------------------------------ *
 * 落点选择
 * ------------------------------------------------------------------ */

interface Slot {
  left: number
  top: number
  right: number
  bottom: number
}

interface Candidate {
  order: WidgetItem[]
  slot: Slot
}

/** 拖动中的磁贴中心要进入格子多少比例才算占据它。 */
const ENTER = 0.18

/**
 * 挑一个「被拖磁贴的格位离它中心最近」的排布，返回 null 表示保持现状。
 *
 * 候选格位按 `ENTER` 收紧后再量距离，当前格位不收；于是每次被接受的移动都严格
 * 缩短这个距离，不可能在两种排布之间来回跳。
 */
function choose(
  home: Slot,
  candidates: Candidate[],
  cx: number,
  cy: number,
): WidgetItem[] | null {
  const distance = (s: Slot, inset: number) => {
    const ix = (s.right - s.left) * inset
    const iy = (s.bottom - s.top) * inset
    const dx = Math.max(s.left + ix - cx, 0, cx - (s.right - ix))
    const dy = Math.max(s.top + iy - cy, 0, cy - (s.bottom - iy))
    return Math.hypot(dx, dy)
  }
  const toCentre = (s: Slot) =>
    Math.hypot((s.left + s.right) / 2 - cx, (s.top + s.bottom) / 2 - cy)

  let best = distance(home, 0)
  if (best === 0) return null
  let pick: WidgetItem[] | null = null
  let bestCentre = Infinity
  for (const { order, slot } of candidates) {
    const d = distance(slot, ENTER)
    const c = toCentre(slot)
    // 平手时先入列者胜；整组互换排在前面。
    if (d < best || (d === best && pick && c < bestCentre)) {
      best = d
      bestCentre = c
      pick = order
    }
  }
  return pick
}

/**
 * 与 `items` 只差一步的所有排布：
 * - 整组互换：一块同形状、且被更小磁贴完全填满的区域，与拖动的磁贴互换位置，
 *   组内相对顺序不变；
 * - 顺序移动：被拖磁贴换到另一个下标。
 */
function candidatesFor(
  items: WidgetItem[],
  id: string,
  columns: number,
  toSlot: (box: Box) => Slot,
): Candidate[] {
  const places = layout(items, columns)
  const me = places.find((p) => p.id === id)
  if (!me) return []
  const byId = new Map(items.map((item) => [item.id, item]))
  const rows = Math.max(...places.map((p) => p.row + p.h))
  const out: Candidate[] = []

  for (let row = 0; row + me.h <= rows; row++) {
    for (let col = 0; col + me.w <= columns; col++) {
      const area = { col, row, w: me.w, h: me.h }
      if (overlaps(area, me)) continue
      const group = places.filter((p) => overlaps(p, area))
      if (group.length < 2 || !group.every((p) => contains(area, p))) continue
      const moved = places.map((p) =>
        p.id === id
          ? { ...p, col, row }
          : group.includes(p)
            ? { ...p, col: p.col - col + me.col, row: p.row - row + me.row }
            : p,
      )
      moved.sort((a, b) => a.row - b.row || a.col - b.col)
      out.push({
        order: moved.map((p) => byId.get(p.id) as WidgetItem),
        slot: toSlot(area),
      })
    }
  }

  const from = items.findIndex((item) => item.id === id)
  for (let i = 0; i < items.length; i++) {
    if (i === from) continue
    const order = moveTo(items, id, i)
    const p = layout(order, columns).find((q) => q.id === id)
    if (p) out.push({ order, slot: toSlot(p) })
  }
  return out
}

/* ------------------------------------------------------------------ *
 * 动效
 * ------------------------------------------------------------------ */

const SPRING = { type: 'spring', visualDuration: 0.38, bounce: 0.16 } as const
const LIFT = { type: 'spring', visualDuration: 0.26, bounce: 0.32 } as const
const LIFT_SCALE = 1.06
/** 拖动中两次重排的最小间隔。 */
const SETTLE_MS = 40
/** 落下后高亮描边保留多久。 */
const LANDED_MS = 620
/** 触摸：抬起前要按住多久。 */
const LONG_PRESS_MS = 350
/** 触摸：按住期间允许多大位移，超过就算滚动。 */
const PRESS_SLOP = 8

/* ------------------------------------------------------------------ *
 * 磁贴
 * ------------------------------------------------------------------ */

interface Press {
  timer: number
  pointerId: number
  x: number
  y: number
}

type Phase = 'idle' | 'holding' | 'lifted'

interface WidgetHandlers {
  start: (id: string) => void
  drag: () => void
  end: (id: string) => void
  key: (e: ReactKeyboardEvent, id: string) => void
  /** 落下后紧跟的那次 click 是否要吞掉。 */
  swallow: () => boolean
  /** 指针在拖动后抬起时调用：忽略紧接着的一次 click。 */
  suppressClick: () => void
}

const Widget = memo(function Widget({
  item,
  col,
  row,
  w,
  h,
  columns,
  rows,
  editable,
  held,
  raised,
  landed,
  handlers,
  hintId,
  position,
  count,
  renderItem,
}: {
  item: WidgetItem
  col: number
  row: number
  w: number
  h: number
  columns: number
  rows: number
  editable: boolean
  held: boolean
  raised: boolean
  landed: boolean
  handlers: WidgetHandlers
  hintId: string
  /** 阅读顺序里的位置（从 1 开始），给辅助技术用。 */
  position: number
  count: number
  renderItem: (item: WidgetItem, size: WidgetSize) => ReactNode
}) {
  const controls = useDragControls()
  const node = useRef(null as HTMLDivElement | null)
  const press = useRef(null as Press | null)
  const lifted = useRef(false)
  const cleanup = useRef(null as (() => void) | null)
  const [phase, setPhase] = useState('idle' as Phase)

  const release = useCallback(() => {
    if (press.current) window.clearTimeout(press.current.timer)
    press.current = null
    lifted.current = false
    cleanup.current?.()
    cleanup.current = null
    setPhase('idle')
  }, [])

  useEffect(() => {
    const el = node.current
    if (!el) return
    // 抬起状态下，触摸移动用来拖磁贴而不是滚页面。
    const block = (e: TouchEvent) => {
      if (lifted.current) e.preventDefault()
    }
    el.addEventListener('touchmove', block, { passive: false })
    return () => {
      el.removeEventListener('touchmove', block)
      if (press.current) window.clearTimeout(press.current.timer)
      cleanup.current?.()
    }
  }, [])

  const onPointerDown = (e: ReactPointerEvent) => {
    if (!editable || e.button !== 0 || !e.isPrimary) return
    if (e.pointerType !== 'touch') {
      controls.start(e)
      return
    }
    if (press.current || lifted.current) return
    const origin = e.nativeEvent
    const pointerId = e.pointerId
    setPhase('holding')
    press.current = {
      pointerId,
      x: e.clientX,
      y: e.clientY,
      timer: window.setTimeout(() => {
        press.current = null
        lifted.current = true
        setPhase('lifted')
        navigator.vibrate?.(10)
        controls.start(origin)
        const done = (ev: PointerEvent) => {
          if (ev.pointerId !== pointerId) return
          handlers.suppressClick()
          release()
        }
        window.addEventListener('pointerup', done)
        window.addEventListener('pointercancel', done)
        cleanup.current = () => {
          window.removeEventListener('pointerup', done)
          window.removeEventListener('pointercancel', done)
        }
      }, LONG_PRESS_MS),
    }
  }

  const onPointerMove = (e: ReactPointerEvent) => {
    const p = press.current
    if (
      p &&
      e.pointerId === p.pointerId &&
      Math.hypot(e.clientX - p.x, e.clientY - p.y) > PRESS_SLOP
    )
      release()
  }

  const onPointerEnd = (e: ReactPointerEvent) => {
    if (press.current?.pointerId === e.pointerId) release()
  }

  const delay = (col / Math.max(columns, 1) + row / Math.max(rows, 1)) * 0.26

  return (
    <motion.div
      ref={node}
      role="listitem"
      data-slot="widget"
      data-widget-id={item.id}
      tabIndex={editable ? 0 : undefined}
      aria-label={item.label ?? `${SIZE_LABELS[item.size]} widget`}
      aria-describedby={editable && hintId ? hintId : undefined}
      aria-posinset={position}
      aria-setsize={count}
      layout="position"
      drag={editable}
      dragListener={false}
      dragControls={controls}
      dragSnapToOrigin
      dragMomentum={false}
      onDragStart={() => handlers.start(item.id)}
      onDrag={handlers.drag}
      onDragEnd={() => handlers.end(item.id)}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerEnd}
      onPointerCancel={onPointerEnd}
      onContextMenu={(e) => {
        if (phase !== 'idle') e.preventDefault()
      }}
      onKeyDown={(e) => handlers.key(e, item.id)}
      onClickCapture={(e) => {
        if (handlers.swallow() || (editable && (e.target as HTMLElement).closest('a'))) {
          e.preventDefault()
          e.stopPropagation()
        }
      }}
      animate={{
        scale: phase === 'holding' ? 0.97 : phase === 'lifted' ? LIFT_SCALE : 1,
      }}
      whileDrag={{ scale: LIFT_SCALE, transition: LIFT }}
      transition={SPRING}
      className={`${styles.slot}${editable ? ` ${styles.grab}` : ''}`}
      style={{
        gridColumn: `${col + 1} / span ${w}`,
        gridRow: `${row + 1} / span ${h}`,
        zIndex: held ? 20 : raised ? 10 : 0,
      }}
    >
      <motion.div
        initial={{ opacity: 0, y: 18, scale: 0.97 }}
        animate={{ opacity: 1, y: 0, scale: 1 }}
        transition={{ type: 'spring', visualDuration: 0.6, bounce: 0.12, delay }}
        className={`${styles.card}${landed ? ` ${styles.landed}` : ''}`}
      >
        {renderItem(item, sizeOf(w, h))}
      </motion.div>
    </motion.div>
  )
})

/* ------------------------------------------------------------------ *
 * 网格
 * ------------------------------------------------------------------ */

export function DraggableWidgetGrid({
  items: initialItems,
  onChange,
  renderItem,
  editable = true,
  maxColumns = 4,
  cellSize = 215,
  gap = 12,
  radius = 18,
  persistKey,
  hint,
  className = '',
}: DraggableWidgetGridProps) {
  const [items, setItems] = useState<WidgetItem[]>(() => {
    if (!persistKey) return initialItems
    try {
      const raw = localStorage.getItem(persistKey)
      if (!raw) return initialItems
      const saved = JSON.parse(raw) as WidgetItem[]
      // 只采用 id 与当前清单完全一致的存档，否则磁贴增删后会出现空位。
      const ids = new Set(initialItems.map((i) => i.id))
      const ok =
        Array.isArray(saved) &&
        saved.length === initialItems.length &&
        saved.every((s) => ids.has(s.id) && typeof s.size === 'string')
      return ok ? saved : initialItems
    } catch {
      return initialItems
    }
  })
  const grid = useRef(null as HTMLDivElement | null)
  const hintId = useId()
  const minColumns = Math.min(2, Math.max(1, maxColumns))

  const [metrics, setMetrics] = useState({ unit: 0, columns: 0 })
  useIsoLayoutEffect(() => {
    const el = grid.current
    if (!el) return
    const measure = () => {
      const width = el.getBoundingClientRect().width
      if (width < 1) return
      const columns = Math.max(
        minColumns,
        Math.min(maxColumns, Math.round(width / cellSize)),
      )
      const unit = (width - gap * (columns - 1)) / columns
      setMetrics((was) =>
        was.columns === columns && Math.abs(was.unit - unit) < 0.5
          ? was
          : { unit, columns },
      )
    }
    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(el)
    return () => observer.disconnect()
  }, [maxColumns, minColumns, cellSize, gap])

  const columns = metrics.columns || Math.max(minColumns, maxColumns)
  const placements = useMemo(() => layout(items, columns), [items, columns])
  const rows = placements.reduce((n, p) => Math.max(n, p.row + p.h), 0)

  // 给活过多次渲染的事件处理器用的最新值。
  const latest = useRef({ items, metrics, onChange })
  latest.current.metrics = metrics
  latest.current.onChange = onChange
  useIsoLayoutEffect(() => {
    latest.current.items = items
  }, [items])

  const commit = useCallback(
    (next: WidgetItem[]) => {
      latest.current.items = next
      setItems(next)
      if (persistKey) {
        try {
          localStorage.setItem(persistKey, JSON.stringify(next))
        } catch {
          /* 存不下就算了：布局退化成只在本页有效 */
        }
      }
    },
    [persistKey],
  )

  const toSlot = useCallback(
    (box: Box): Slot => {
      const el = grid.current
      const { unit } = latest.current.metrics
      const rect = el?.getBoundingClientRect()
      const colStep = unit + gap
      const rowStep = Math.round(unit) + gap
      const left = (rect?.left ?? 0) + box.col * colStep
      const top = (rect?.top ?? 0) + box.row * rowStep
      return {
        left,
        top,
        right: left + box.w * colStep - gap,
        bottom: top + box.h * rowStep - gap,
      }
    },
    [gap],
  )

  /* 拖动状态。只有抬起、重排、落下这三件事会触发 React 重渲染。 */
  const [held, setHeld] = useState(null as string | null)
  const [raised, setRaised] = useState(null as string | null)
  const [landed, setLanded] = useState(null as string | null)
  const dragging = useRef(null as string | null)
  const startOrder = useRef(null as WidgetItem[] | null)
  const frame = useRef(0)
  const lastMove = useRef(0)
  const swallowUntil = useRef(0)
  const ring = useRef(0)
  const refocus = useRef(null as string | null)

  const step = useCallback(
    (force = false) => {
      frame.current = 0
      const id = dragging.current
      const { items: current, metrics: m } = latest.current
      const el = id
        ? (grid.current?.querySelector(
            `[data-widget-id="${CSS.escape(id)}"]`,
          ) as HTMLElement | null)
        : null
      if (!id || !el || !m.columns) return
      const now = performance.now()
      if (!force && now - lastMove.current < SETTLE_MS) {
        // 下一帧再试，这样停住不动的磁贴也会落位。
        frame.current = requestAnimationFrame(() => step())
        return
      }
      const me = layout(current, m.columns).find((p) => p.id === id)
      if (!me) return
      const r = el.getBoundingClientRect()
      const order = choose(
        toSlot(me),
        candidatesFor(current, id, m.columns, toSlot),
        r.left + r.width / 2,
        r.top + r.height / 2,
      )
      if (!order) return
      lastMove.current = now
      commit(canonical(order, m.columns))
    },
    [toSlot, commit],
  )

  useEffect(
    () => () => {
      cancelAnimationFrame(frame.current)
      window.clearTimeout(ring.current)
    },
    [],
  )

  // 键盘移动后把焦点还给同一个磁贴（DOM 顺序变了焦点会丢）。
  useIsoLayoutEffect(() => {
    const id = refocus.current
    if (!id) return
    refocus.current = null
    const el = grid.current?.querySelector(
      `[data-widget-id="${CSS.escape(id)}"]`,
    ) as HTMLElement | null
    el?.focus()
  }, [items])

  const handlers: WidgetHandlers = useMemo(
    () => ({
      start: (id) => {
        dragging.current = id
        startOrder.current = latest.current.items
        lastMove.current = 0
        setHeld(id)
        setRaised(id)
        // 松开指针时触发的那次 click 不能落到磁贴内容上。
        window.addEventListener(
          'pointerup',
          () => {
            swallowUntil.current = performance.now() + 300
          },
          { once: true, capture: true },
        )
      },
      drag: () => {
        if (!frame.current) frame.current = requestAnimationFrame(() => step())
      },
      end: (id) => {
        cancelAnimationFrame(frame.current)
        step(true)
        frame.current = 0
        dragging.current = null
        setHeld(null)
        setLanded(id)
        window.clearTimeout(ring.current)
        ring.current = window.setTimeout(() => {
          setLanded(null)
          setRaised(null)
        }, LANDED_MS)
        const before = startOrder.current
        startOrder.current = null
        const after = latest.current.items
        if (before && !sameOrder(before, after)) latest.current.onChange?.(after)
      },
      key: (e, id) => {
        if (!editable || !e.altKey) return
        if ((e.target as HTMLElement).closest('input, textarea, select')) return
        const delta =
          e.key === 'ArrowRight' || e.key === 'ArrowDown'
            ? 1
            : e.key === 'ArrowLeft' || e.key === 'ArrowUp'
              ? -1
              : 0
        if (!delta) return
        e.preventDefault()
        const { items: current, metrics: m } = latest.current
        const cols = m.columns || maxColumns
        const from = current.findIndex((item) => item.id === id)
        // 一直找到布局真的发生变化为止。
        for (let to = from + delta; to >= 0 && to < current.length; to += delta) {
          const next = canonical(moveTo(current, id, to), cols)
          if (sameOrder(next, current)) continue
          refocus.current = id
          commit(next)
          latest.current.onChange?.(next)
          return
        }
      },
      swallow: () => performance.now() < swallowUntil.current,
      suppressClick: () => {
        swallowUntil.current = performance.now() + 300
      },
    }),
    [step, commit, editable, maxColumns],
  )

  const byId = useMemo(() => new Map(items.map((item) => [item.id, item])), [items])

  /*
   * DOM 顺序保持不变，只改网格落位。把元素在 DOM 里挪来挪去会让 Motion 的挂载
   * 动画重播（重排后磁贴会淡出再淡入）；视觉位置改由 aria-posinset 暴露。
   */
  const domOrder = useRef(items.map((item) => item.id))
  for (const item of items)
    if (!domOrder.current.includes(item.id)) domOrder.current.push(item.id)
  const placementById = new Map(placements.map((p) => [p.id, p]))
  const visualIndex = new Map(
    [...placements]
      .sort((a, b) => a.row - b.row || a.col - b.col)
      .map((p, i) => [p.id, i]),
  )

  return (
    <MotionConfig reducedMotion="user">
      <div
        className={`${styles.root} ${className}`.trim()}
        style={{ '--widget-radius': `${radius}px` } as CSSProperties}
      >
        {editable && hint && (
          <p id={hintId} className="sr-only">
            {hint}
          </p>
        )}
        <div
          ref={grid}
          role="list"
          data-slot="widget-grid"
          className={styles.grid}
          style={{
            gap,
            gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))`,
            // 方形格子：行高等于列宽。
            gridAutoRows: metrics.unit
              ? `${Math.round(metrics.unit)}px`
              : `minmax(${cellSize * 0.75}px, auto)`,
          }}
        >
          {domOrder.current.map((id) => {
            const item = byId.get(id)
            const p = placementById.get(id)
            if (!item || !p) return null
            return (
              <Widget
                key={id}
                position={(visualIndex.get(id) ?? 0) + 1}
                count={placements.length}
                item={item}
                col={p.col}
                row={p.row}
                w={p.w}
                h={p.h}
                columns={columns}
                rows={rows}
                editable={editable}
                held={held === p.id}
                raised={raised === p.id}
                landed={landed === p.id}
                handlers={handlers}
                hintId={hint ? hintId : ''}
                renderItem={renderItem}
              />
            )
          })}
        </div>
      </div>
    </MotionConfig>
  )
}

export default DraggableWidgetGrid
