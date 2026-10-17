import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { AnimatePresence, motion, useReducedMotion } from 'motion/react'
import Loc from './Loc'
import { appCopy } from '@/lib/appCopy'
import { useLocale } from '@/lib/locale'

export interface KbGraphNode {
  id: string
  label: string
  /** center=当前文档；back=反链；out=出链；related=推荐/同库 */
  kind: 'center' | 'back' | 'out' | 'related'
  docId?: string
}

export interface KbGraphEdge {
  from: string
  to: string
  kind: 'back' | 'out' | 'related'
}

interface Props {
  centerLabel: string
  nodes: KbGraphNode[]
  edges: KbGraphEdge[]
  /** 点击可跳转的节点；传入该回调后节点才可点击（无 docId 的节点不可点） */
  onSelect?: (node: KbGraphNode) => void
}

/**
 * 知识库局部关系图。
 *
 * 设计取舍（对齐 ChatGPT / Linear 的克制风格）：
 * - **只读布局 + 统一中性色**：颜色只用来区分关系类型（反链/出链/推荐），
 *   不给每个节点分配随机色，避免"彩虹图"；
 * - **选中态走描边 + 一圈光晕**，不用大面积填充，强弱分明但不喧宾夺主；
 * - **缩放/平移自己实现**（transform + rAF 节流），不引第三方图库：
 *   这里的交互只有"缩放 + 拖拽"，引 force-graph 会带来几百 KB 且行为不可控；
 * - **节点数上限**：超过 MAX_NODES 只画前 N 个并在面板里说明，
 *   避免大图谱把 SVG 撑到卡顿。
 */

const VIEW_W = 560
const VIEW_H = 400
const CX = VIEW_W / 2
const CY = VIEW_H / 2 - 6
const MAX_NODES = 40
const MIN_SCALE = 0.5
const MAX_SCALE = 2.4

function truncate(s: string, n: number) {
  return s.length > n ? s.slice(0, n - 1) + '…' : s
}

export default function KbLinkGraph({ centerLabel, nodes, edges, onSelect }: Props) {
  const reduce = useReducedMotion()
  const { locale } = useLocale()
  const t = appCopy(locale)

  const [hover, setHover] = useState<string | null>(null)
  const [picked, setPicked] = useState<string | null>(null)
  const [scale, setScale] = useState(1)
  const [pan, setPan] = useState({ x: 0, y: 0 })
  const dragRef = useRef<{ id: number; sx: number; sy: number; ox: number; oy: number; moved: boolean } | null>(null)

  /* 超过上限就截断，并告知用户（图谱本身不静默丢数据） */
  const truncated = nodes.length > MAX_NODES + 1

  const layout = useMemo(() => {
    const all = truncated ? nodes.slice(0, MAX_NODES + 1) : nodes
    const others = all.filter((n) => n.kind !== 'center')
    const center = all.find((n) => n.kind === 'center') ?? {
      id: 'center',
      label: centerLabel,
      kind: 'center' as const,
    }
    const n = Math.max(others.length, 1)
    /*
     * 圆周半径随节点数增长，但设上限 —— 否则 10+ 个节点会把标签甩到画布外，
     * 出现"文字被裁掉 / 连线穿字"。窄屏靠 preserveAspectRatio 缩放，不改半径。
     */
    const radius = Math.min(146, 74 + n * 7.5)
    const placed = others.map((node, i) => {
      const angle = -Math.PI / 2 + (i / n) * Math.PI * 2
      return { ...node, x: CX + Math.cos(angle) * radius, y: CY + Math.sin(angle) * radius }
    })
    const byId: Record<string, { id: string; label: string; kind: KbGraphNode['kind']; docId?: string; x: number; y: number }> = {}
    byId[center.id] = { ...center, x: CX, y: CY }
    placed.forEach((p) => { byId[p.id] = p })
    return { center: byId[center.id], others: placed, byId }
  }, [nodes, centerLabel, truncated])

  const soft = reduce
    ? { duration: 0 }
    : { type: 'spring' as const, stiffness: 260, damping: 26, mass: 0.65 }

  const lonely = layout.others.length === 0

  /** 当前选中（含 hover）的节点：hover 优先，其次点选 */
  const focusId = hover ?? picked
  const focusNode = focusId ? layout.byId[focusId] ?? null : null
  const activeId = picked

  /* ---- 缩放 / 平移 ---- */
  const zoomBy = useCallback((factor: number) => {
    setScale((s) => Math.min(MAX_SCALE, Math.max(MIN_SCALE, s * factor)))
  }, [])

  const resetView = useCallback(() => {
    setScale(1)
    setPan({ x: 0, y: 0 })
  }, [])

  const onWheel = useCallback((e: React.WheelEvent) => {
    // 只有按住 Ctrl/⌘ 时才缩放，避免与页面滚动冲突
    if (!e.ctrlKey && !e.metaKey) return
    e.preventDefault()
    zoomBy(e.deltaY < 0 ? 1.12 : 0.89)
  }, [zoomBy])

  const onPointerDown = (e: React.PointerEvent) => {
    /* 点到节点时不启动拖拽，否则点选会被当成拖动 */
    if ((e.target as Element).closest('[data-node]')) return
    dragRef.current = { id: e.pointerId, sx: e.clientX, sy: e.clientY, ox: pan.x, oy: pan.y, moved: false }
    ;(e.currentTarget as Element).setPointerCapture?.(e.pointerId)
  }

  const onPointerMove = (e: React.PointerEvent) => {
    const d = dragRef.current
    if (!d || d.id !== e.pointerId) return
    const dx = e.clientX - d.sx
    const dy = e.clientY - d.sy
    if (!d.moved && Math.hypot(dx, dy) < 3) return
    d.moved = true
    setPan({ x: d.ox + dx, y: d.oy + dy })
  }

  const endDrag = (e: React.PointerEvent) => {
    const d = dragRef.current
    if (d && d.id === e.pointerId) dragRef.current = null
  }

  /* 键盘：+/- 缩放，0 复位；图区本身可聚焦 */
  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === '+' || e.key === '=') { e.preventDefault(); zoomBy(1.12) }
    else if (e.key === '-' || e.key === '_') { e.preventDefault(); zoomBy(0.89) }
    else if (e.key === '0') { e.preventDefault(); resetView() }
  }

  /* 图谱数据换了（切文档）就把视图与选中态复位 */
  useEffect(() => {
    setScale(1)
    setPan({ x: 0, y: 0 })
    setPicked(null)
    setHover(null)
  }, [centerLabel])

  const neighborIds = useMemo(() => {
    if (!focusId) return new Set<string>()
    const set = new Set<string>()
    edges.forEach((e) => {
      if (e.from === focusId) set.add(e.to)
      if (e.to === focusId) set.add(e.from)
    })
    return set
  }, [edges, focusId])

  /* 缩放后坐标需要按比例补偿，否则会朝原点漂移 */
  const zoomAround = (next: number) => {
    const prev = scale
    const k = next / prev
    setPan((p) => ({ x: CX - (CX - p.x) * k, y: CY - (CY - p.y) * k }))
  }

  const applyZoom = (factor: number) => {
    const next = Math.min(MAX_SCALE, Math.max(MIN_SCALE, scale * factor))
    if (next === scale) return
    zoomAround(next)
    setScale(next)
  }

  const relatedCount = focusNode ? neighborIds.size : 0

  return (
    <div className="kb-graph">
      <div className="kb-graph__stage">
        <div
          className="kb-graph__viewport"
          role="application"
          tabIndex={0}
          aria-label={t.kb.graphAria(centerLabel)}
          onWheel={onWheel}
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={endDrag}
          onPointerCancel={endDrag}
          onKeyDown={onKeyDown}
          data-dragging={dragRef.current?.moved ? '' : undefined}
        >
          <motion.svg
            viewBox={`0 0 ${VIEW_W} ${VIEW_H}`}
            className="kb-graph__svg"
            width="100%"
            height="100%"
            preserveAspectRatio="xMidYMid meet"
            initial={false}
            animate={{ scale, x: pan.x, y: pan.y }}
            transition={reduce ? { duration: 0 } : { type: 'spring', stiffness: 300, damping: 30, mass: 0.7 }}
            style={{ transformOrigin: 'center' }}
          >
            {/*
              连线先画、节点后画：SVG 按文档序渲染，这样线天然压在节点下面，
              不会出现"线穿过圆点"这种脏边。
            */}
            <g className="kb-graph__edges">
              {edges.map((e, i) => {
                const a = layout.byId[e.from]
                const b = layout.byId[e.to]
                if (!a || !b) return null
                const inFocus = focusId !== null && (e.from === focusId || e.to === focusId)
                const dim = focusId !== null && !inFocus
                return (
                  <motion.line
                    key={`${e.from}-${e.to}-${i}`}
                    x1={a.x}
                    y1={a.y}
                    x2={b.x}
                    y2={b.y}
                    className={`kb-graph__edge kb-graph__edge--${e.kind}`}
                    data-dim={dim ? '' : undefined}
                    data-on={inFocus ? '' : undefined}
                    initial={reduce ? false : { pathLength: 0, opacity: 0 }}
                    animate={{ pathLength: 1, opacity: 1 }}
                    transition={{ ...soft, delay: reduce ? 0 : 0.04 * i }}
                    strokeLinecap="round"
                  />
                )
              })}
            </g>

            <motion.g
              className="kb-graph__node-group kb-graph__node-group--center"
              initial={reduce ? false : { scale: 0.7, opacity: 0 }}
              animate={{ scale: 1, opacity: 1 }}
              transition={soft}
              style={{ transformOrigin: `${layout.center.x}px ${layout.center.y}px` }}
              data-selected={activeId === layout.center.id ? '' : undefined}
            >
              <circle
                cx={layout.center.x}
                cy={layout.center.y}
                r={9}
                className="kb-graph__node kb-graph__node--center"
              />
              <text
                x={layout.center.x}
                y={layout.center.y + 24}
                textAnchor="middle"
                className="kb-graph__label kb-graph__label--center"
              >
                {truncate(layout.center.label, 20)}
              </text>
            </motion.g>

            <AnimatePresence>
              {layout.others.map((node, i) => {
                const clickable = Boolean(node.docId && onSelect)
                const isSel = activeId === node.id
                const dim = focusId !== null && focusId !== node.id && !neighborIds.has(node.id)
                return (
                  <motion.g
                    key={node.id}
                    data-node={clickable ? '' : undefined}
                    className="kb-graph__node-group"
                    data-kind={node.kind}
                    data-selected={isSel ? '' : undefined}
                    data-dim={dim ? '' : undefined}
                    initial={reduce ? false : { scale: 0.4, opacity: 0 }}
                    animate={{ scale: 1, opacity: 1 }}
                    exit={reduce ? undefined : { scale: 0.4, opacity: 0 }}
                    transition={{ ...soft, delay: reduce ? 0 : 0.05 + i * 0.035 }}
                    style={{
                      cursor: clickable ? 'pointer' : 'default',
                      transformOrigin: `${node.x}px ${node.y}px`,
                    }}
                    onMouseEnter={() => setHover(node.id)}
                    onMouseLeave={() => setHover((h) => (h === node.id ? null : h))}
                    onFocus={() => setHover(node.id)}
                    onBlur={() => setHover((h) => (h === node.id ? null : h))}
                    onClick={() => {
                      setPicked(node.id)
                      if (clickable) onSelect?.(node)
                    }}
                    onKeyDown={(ev) => {
                      if (!clickable) return
                      if (ev.key === 'Enter' || ev.key === ' ') {
                        ev.preventDefault()
                        setPicked(node.id)
                        onSelect?.(node)
                      }
                    }}
                    tabIndex={clickable ? 0 : -1}
                    role={clickable ? 'button' : undefined}
                    aria-pressed={clickable ? isSel : undefined}
                    aria-label={clickable ? t.kb.graphOpen(node.label) : node.label}
                  >
                    <circle
                      cx={node.x}
                      cy={node.y}
                      r={isSel ? 7.5 : 6}
                      className={`kb-graph__node kb-graph__node--${node.kind}`}
                    />
                    <text
                      x={node.x}
                      y={node.y + 21}
                      textAnchor="middle"
                      className="kb-graph__label"
                    >
                      {truncate(node.label, 13)}
                    </text>
                  </motion.g>
                )
              })}
            </AnimatePresence>
          </motion.svg>
        </div>

        {/* 缩放控件：常驻右下，克制的小圆钮 */}
        <div className="kb-graph__zoom" role="group" aria-label={t.kb.graphZoomAria}>
          <button type="button" onClick={() => applyZoom(1.15)} aria-label={t.kb.graphZoomIn} title={t.kb.graphZoomIn}>+</button>
          <span className="kb-graph__zoom-val" aria-live="polite">{Math.round(scale * 100)}%</span>
          <button type="button" onClick={() => applyZoom(0.87)} aria-label={t.kb.graphZoomOut} title={t.kb.graphZoomOut}>−</button>
          <button
            type="button"
            className="kb-graph__zoom-reset"
            onClick={resetView}
            disabled={scale === 1 && pan.x === 0 && pan.y === 0}
            aria-label={t.kb.graphZoomReset}
            title={t.kb.graphZoomReset}
          >
            <Loc>{t.kb.graphZoomReset}</Loc>
          </button>
        </div>

        {/* 选中节点详情：放在图谱自身的容器里，不塞进画布空白区 */}
        <AnimatePresence>
          {focusNode && (
            <motion.div
              className="kb-graph__detail"
              key={focusNode.id}
              initial={reduce ? false : { opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              exit={reduce ? undefined : { opacity: 0, y: 6 }}
              transition={soft}
            >
              <div className="kb-graph__detail-head">
                <span className={`kb-graph__kind kb-graph__kind--${focusNode.kind}`}>
                  <Loc>{t.kb.graphLegend[focusNode.kind === 'center' ? 'related' : focusNode.kind]}</Loc>
                </span>
                <span className="kb-graph__detail-title" title={focusNode.label}>{focusNode.label}</span>
              </div>
              <p className="kb-graph__detail-meta">
                {t.kb.graphNeighbors(relatedCount)}
              </p>
              {focusNode.docId && focusNode.kind !== 'center' ? (
                <button
                  type="button"
                  className="btn btn-ghost btn-sm"
                  onClick={() => onSelect?.(focusNode)}
                >
                  <Loc>{t.kb.graphOpenDetail}</Loc>
                </button>
              ) : (
                <span className="kb-graph__detail-hint"><Loc>{t.kb.graphOpenHint}</Loc></span>
              )}
            </motion.div>
          )}
        </AnimatePresence>
      </div>

      <div className="kb-graph__foot">
        {!lonely && (
          <div className="kb-graph__legend" aria-hidden>
            <span data-k="back"><Loc>{t.kb.graphLegend.back}</Loc></span>
            <span data-k="out"><Loc>{t.kb.graphLegend.out}</Loc></span>
            <span data-k="related"><Loc>{t.kb.graphLegend.related}</Loc></span>
          </div>
        )}
        <p className="kb-graph__foot-note">
          <Loc>{truncated ? t.kb.graphTruncated(MAX_NODES) : t.kb.graphPanHint}</Loc>
        </p>
        {lonely && <p className="kb-graph__hint"><Loc>{t.kb.graphLonelyHint}</Loc><code>[[wiki]]</code></p>}
      </div>
    </div>
  )
}