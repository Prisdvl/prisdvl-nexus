import { useEffect, useMemo, useState } from 'react'
import {
  ArrowRight, Bot, Clock, Database, FileText, MessagesSquare, Plus, RotateCcw, Search, Upload, Wand2, Zap,
} from 'lucide-react'
import Counter from '@/bits/Counter/Counter'
import LatticeLoader from '@/bits/LatticeLoader/LatticeLoader'
import StatusMark from '@/bits/StatusMark/StatusMark'
import { DraggableWidgetGrid, type WidgetItem } from '@/bits/DraggableWidgetGrid/DraggableWidgetGrid'
import Loc from '@/components/Loc'
import { SkeletonRows } from '@/components/Skeleton'
import { SESSIONS, ACTIVITY as SEED_ACTIVITY, HEAT as SEED_HEAT, TREND as SEED_TREND, type ActivityKind } from '@/data'
import { fetchRecentImports, relTime, useLoad } from '@/lib/backendData'
import { useLocale } from '@/lib/locale'
import { pagesCopy } from '@/lib/pagesCopy'
import { appCopy } from '@/lib/appCopy'
import type { PaletteName, ThemeMode } from '@/lib/types'
import { api, errMessage, USE_MOCK } from '@/lib/api'
import '@/styles/workbench-widgets.css'

interface Props {
  theme: ThemeMode
  palette: PaletteName
  goChat: () => void
  goKb: () => void
  goAgents: () => void
  goAutomations: () => void
  onOpenConversation: (id: string) => void
}

const KIND_ICON: Record<ActivityKind, typeof Upload> = {
  import: Upload,
  qa: Search,
  agent: Bot,
  chat: MessagesSquare,
}

/**
 * 磁贴的默认排布。
 *
 * 尺寸不是随便定的：四个 lg（2×2）+ 四个 wide（2×1）在 4 列时正好铺满 16 格，
 * 精确铺砖器可以一条缝都不留；窄屏成 2 列时仍然整除 = 8 行。
 * 面积一旦不再是列数的整数倍，就会退回行打包器（磁贴会被撑大补洞），
 * 视觉上会「有的块莫名变宽」——改尺寸时请自己先除一下。
 */
const DEFAULT_WIDGETS: WidgetItem[] = [
  { id: 'stats', size: 'wide' },
  { id: 'trend', size: 'lg' },
  /* 列表类磁贴给 2 行高：1 行只有约 150px 正文，5 条活动根本放不下，
     用户看到的就是「总览显示不全」——卡片里只露两行半，还得在卡片内再滚一次。
     文档处理只有 3 条 + 一个入口，1 行高刚好，不再撑成一大片空白 */
  { id: 'imports', size: 'wide' },
  { id: 'timeline', size: 'lg' },
  { id: 'heat', size: 'wide' },
  { id: 'recent', size: 'lg' },
]

/* 布局键跟着默认尺寸一起升版：只改 DEFAULT_WIDGETS 的话，凡是打开过工作台的人
   localStorage 里都有旧布局，新默认值永远不生效（这正是一堆用户看到「显示不全」的原因） */
const GRID_KEY = 'nx-workbench-widgets-v2'

type WbCopy = ReturnType<typeof pagesCopy>['workbench']

/** 磁贴的读屏名称：优先用同一个字典里已有的标题，避免另造一套文案。 */
function widgetLabel(id: string, w: WbCopy): string {
  switch (id) {
    case 'stats': return w.widgetStats
    case 'trend': return w.trendTitle
    case 'imports': return w.importsTitle
    case 'timeline': return w.timelineTitle
    case 'heat': return w.heatTitle
    default: return w.recentTitle
  }
}

/** 磁贴外壳：头部固定，主体吃掉剩余高度并按需内部滚动。 */
function Widget({
  icon, title, sub, children, bodyClass,
}: {
  icon: React.ReactNode
  title: React.ReactNode
  sub?: React.ReactNode
  children: React.ReactNode
  bodyClass?: string
}) {
  return (
    <div className="wbw-widget">
      <div className="wbw-head">
        <h2 className="wbw-title">{icon} {title}</h2>
        {sub && <span className="wbw-sub">{sub}</span>}
      </div>
      <div className={`wbw-body${bodyClass ? ` ${bodyClass}` : ''}`}>{children}</div>
    </div>
  )
}

export default function WorkbenchPage({ theme, palette, goChat, goKb, goAgents, goAutomations, onOpenConversation }: Props) {
  const { locale } = useLocale()
  const t = appCopy(locale)
  const w = pagesCopy(locale).workbench
  const { data: currentUser } = useLoad(() => api.users.me())
  const { data: importRows, loading: importsLoading, error: importsError } = useLoad(() => fetchRecentImports())
  /* 真实数据：四个计数来自后端列表接口；mock 模式保留原演示数字 */
  const [counts, setCounts] = useState({ kb: 0, docs: 0, conv: 0, agent: 0 })
  const [countsLoading, setCountsLoading] = useState(!USE_MOCK)
  const [countsError, setCountsError] = useState('')
  useEffect(() => {
    if (USE_MOCK) return
    let alive = true
    Promise.all([api.knowledge.list(1, 100), api.conversations.list(1, 100), api.agents.list(1, 100)])
      .then(([kb, conv, ag]) => {
        if (!alive) return
        setCounts({
          kb: kb.total,
          docs: kb.list.reduce((s, k) => s + (k.docCount ?? 0), 0),
          conv: conv.total,
          agent: ag.total,
        })
      })
      .catch((error) => { if (alive) setCountsError(errMessage(error)) })
      .finally(() => { if (alive) setCountsLoading(false) })
    return () => { alive = false }
  }, [])

  /* 最近会话：取真实会话列表前 3 条（mock 模式仍用种子 SESSIONS） */
  const { data: convPage, loading: convLoading, error: convError } = useLoad(() => api.conversations.list(1, 3))
  const recent = USE_MOCK
    ? SESSIONS.slice(0, 3)
    : (convPage?.list ?? []).map((c) => ({
        id: c.id,
        title: c.title ?? w.untitledConv,
        updated: relTime(c.lastMessageAt ?? c.updatedAt),
      }))
  /* 工作台聚合：趋势(14) / 热力(30) / 最近活动(8)，来自 GET /api/stats/summary */
  const { data: summary, loading: statsLoading, error: statsError } = useLoad(() => api.stats.summary())
  const [trendHover, setTrendHover] = useState<number | null>(null)
  const trendPoints = useMemo(() => {
    if (USE_MOCK) {
      const today = new Date()
      today.setHours(0, 0, 0, 0)
      return SEED_TREND.map((count, i) => {
        const d = new Date(today)
        d.setDate(today.getDate() - (SEED_TREND.length - 1 - i))
        const m = `${d.getMonth() + 1}`.padStart(2, '0')
        const day = `${d.getDate()}`.padStart(2, '0')
        return { day: `${d.getFullYear()}-${m}-${day}`, label: `${d.getMonth() + 1}/${d.getDate()}`, count }
      })
    }
    return (summary?.trend ?? []).map((p) => {
      const raw = p.day?.slice(0, 10) ?? ''
      const parts = raw.split('-')
      const label = parts.length === 3 ? `${Number(parts[1])}/${Number(parts[2])}` : raw
      return { day: raw, label, count: p.count }
    })
  }, [summary])
  /*
   * 活跃度的统计口径：消息条数 / Token 消耗。
   * 两套数据来自同一条 /api/stats/summary（heat / tokenHeat），切换只换数据源，不再请求。
   */
  const [heatMetric, setHeatMetric] = useState<'messages' | 'tokens'>('messages')
  const heatRaw = USE_MOCK
    ? (heatMetric === 'tokens' ? SEED_HEAT.map((value) => value * 260) : SEED_HEAT)
    : (heatMetric === 'tokens' ? (summary?.tokenHeat ?? []) : (summary?.heat ?? [])).map((point) => point.count)
  /* 色阶按窗口内的最大值归一化：Token 一天几千、消息一天几条，同一套绝对阈值套不上两套量纲 */
  const heatMax = Math.max(1, ...heatRaw)
  const heat = heatRaw.map((value) =>
    value <= 0 ? 0 : Math.min(4, Math.max(1, Math.ceil((value / heatMax) * 4))),
  )
  const activeDays = heatRaw.filter((value) => value > 0).length
  const heatSum = heatRaw.reduce((total, value) => total + value, 0)
  const heatSumText = heatSum >= 1000 ? `${(heatSum / 1000).toFixed(1)}k` : String(heatSum)
  const activity = USE_MOCK
    ? SEED_ACTIVITY
    : (summary?.activity ?? []).map((a) => ({
        kind: (a.type === 'IMPORT' ? 'import' : 'chat') as ActivityKind,
        title: a.type === 'IMPORT' ? w.activityImport(a.label ?? '') : w.activityChat(a.label ?? w.untitledConv),
        detail: a.type === 'IMPORT' ? w.activityImportDetail : w.activityChatDetail,
        time: relTime(a.at),
      }))
  const stats: { label: string; value: number; sub: string; accent?: boolean }[] = [
    { label: w.statKb, value: counts.kb, sub: w.statKbSub(counts.docs), accent: true },
    { label: w.statDocs, value: counts.docs, sub: w.statDocsSub },
    { label: w.statConv, value: counts.conv, sub: w.statConvSub },
    { label: w.statAgent, value: counts.agent, sub: w.statAgentSub },
  ]

  // 趋势折线（14 天）：平滑连续实线 + 轴标注
  const trendCounts = trendPoints.map((p) => p.count)
  const max = Math.max(1, ...trendCounts)
  const mid = Math.round(max / 2)
  const pts = trendPoints.map((p, i) => ({
    ...p,
    x: trendPoints.length <= 1 ? 50 : (i / (trendPoints.length - 1)) * 100,
    y: 100 - (p.count / max) * 78 - 12,
  }))
  const smoothLine = (points: { x: number; y: number }[]) => {
    if (points.length === 0) return ''
    if (points.length === 1) return `M ${points[0].x.toFixed(2)} ${points[0].y.toFixed(2)}`
    if (points.length === 2) {
      return `M ${points[0].x.toFixed(2)} ${points[0].y.toFixed(2)} L ${points[1].x.toFixed(2)} ${points[1].y.toFixed(2)}`
    }
    let d = `M ${points[0].x.toFixed(2)} ${points[0].y.toFixed(2)}`
    for (let i = 0; i < points.length - 1; i++) {
      const p0 = points[i === 0 ? 0 : i - 1]
      const p1 = points[i]
      const p2 = points[i + 1]
      const p3 = points[i + 2] ?? p2
      const cp1x = p1.x + (p2.x - p0.x) / 6
      const cp1y = p1.y + (p2.y - p0.y) / 6
      const cp2x = p2.x - (p3.x - p1.x) / 6
      const cp2y = p2.y - (p3.y - p1.y) / 6
      d += ` C ${cp1x.toFixed(2)} ${cp1y.toFixed(2)}, ${cp2x.toFixed(2)} ${cp2y.toFixed(2)}, ${p2.x.toFixed(2)} ${p2.y.toFixed(2)}`
    }
    return d
  }
  const line = smoothLine(pts)
  const area = pts.length
    ? `${line} L ${pts[pts.length - 1].x.toFixed(2)},100 L ${pts[0].x.toFixed(2)},100 Z`
    : ''
  const trendKey = trendPoints.map((p) => `${p.day}:${p.count}`).join(',')
  const totalCalls = trendCounts.reduce((total, count) => total + count, 0)
  const xLabels = pts.length
    ? [
        pts[0],
        pts[Math.floor((pts.length - 1) / 2)],
        pts[pts.length - 1],
      ].filter((p, i, arr) => arr.findIndex((x) => x.day === p.day) === i)
    : []
  const hover = trendHover !== null ? pts[trendHover] : null

  /* 重置布局：清掉存档，再换一个 key 让网格按默认排布重新挂载 */
  const [gridEpoch, setGridEpoch] = useState(0)
  const resetLayout = () => {
    try { localStorage.removeItem(GRID_KEY) } catch { /* 存不进就算了 */ }
    setGridEpoch((n) => n + 1)
  }

  const widgets = useMemo(
    () => DEFAULT_WIDGETS.map((item) => ({ ...item, label: widgetLabel(item.id, w) })),
    [w],
  )

  /* ---------- 各个磁贴的内容 ---------- */

  const statsBody = (
    <div className="wbw-stats" aria-busy={countsLoading}>
      {stats.map((s) => (
        <div key={s.label} className={`wbw-stat${s.accent ? ' wbw-stat--accent' : ''}`}>
          <span className="wbw-stat__label">{s.label}</span>
          <span className="wbw-stat__num">
            {/* 不传 places：按数值位数自动决定（0 → "0"，42 → "42"）。 */}
            {countsLoading ? '—' : <Counter value={s.value} fontSize={20} padding={3} gap={5} fontWeight={620} textColor="currentColor" gradientFrom="var(--surface-muted)" gradientHeight={7} />}
          </span>
          <span className="wbw-stat__sub">{s.sub}</span>
        </div>
      ))}
    </div>
  )

  const trendBody = statsLoading ? <SkeletonRows rows={2} /> : statsError ? <p className="form-error active" role="alert">{statsError}</p> : trendPoints.length === 0 ? <p className="wbw-hint">{w.trendEmpty}</p> : (
    <>
      <div className="wb-trend" key={trendKey} onMouseLeave={() => setTrendHover(null)}>
        <div className="wb-trend-y" aria-hidden="true">
          <span>{max}</span>
          <span>{mid}</span>
          <span>0</span>
        </div>
        <div className="wb-trend-plot">
          <svg viewBox="0 0 100 100" preserveAspectRatio="none" role="img" aria-label={w.trendAria}>
            <defs>
              <linearGradient id="wbArea" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor="var(--primary)" stopOpacity="0.2" />
                <stop offset="100%" stopColor="var(--primary)" stopOpacity="0" />
              </linearGradient>
              <clipPath id="wbReveal">
                <rect className="trend-reveal" x="0" y="0" width="100" height="100" />
              </clipPath>
            </defs>
            <line className="trend-grid" x1="0" y1="12" x2="100" y2="12" />
            <line className="trend-grid" x1="0" y1="51" x2="100" y2="51" />
            <line className="trend-grid" x1="0" y1="90" x2="100" y2="90" />
            <g clipPath="url(#wbReveal)">
              <path className="trend-area" d={area} fill="url(#wbArea)" />
              {pts.length > 1 && (
                <path
                  className="trend-line"
                  d={line}
                  fill="none"
                  stroke="var(--primary)"
                  strokeWidth="2.4"
                  vectorEffect="non-scaling-stroke"
                  strokeLinejoin="round"
                  strokeLinecap="round"
                />
              )}
            </g>
            {hover && (
              <line className="trend-cross" x1={hover.x} y1="8" x2={hover.x} y2="92" />
            )}
          </svg>
          {/* 命中层与点标：独立百分比定位，避免 stretch 把圆点拉扁 */}
          <div className="wb-trend-hits" role="presentation">
            {pts.map((p, i) => (
              <button
                key={p.day}
                type="button"
                className={`wb-trend-hit${trendHover === i ? ' is-active' : ''}`}
                style={{ left: `${p.x}%`, top: `${p.y}%` }}
                aria-label={w.trendHitAria(p.label, p.count)}
                onMouseEnter={() => setTrendHover(i)}
                onFocus={() => setTrendHover(i)}
              />
            ))}
          </div>
          {hover && (
            <div className="wb-trend-tip" style={{ left: `${hover.x}%`, top: `${hover.y}%` }} role="status">
              <strong>{hover.count}</strong>
              <span>{hover.label}</span>
            </div>
          )}
        </div>
        <div className="wb-trend-x" aria-hidden="true">
          {xLabels.map((p) => (
            <span key={p.day} style={{ left: `${p.x}%` }}>{p.label}</span>
          ))}
        </div>
      </div>
      <div className="wb-trend-legend">
        <span className="wb-trend-metric">
          <b>
            <Counter value={totalCalls} fontSize={13} padding={1} gap={1} horizontalPadding={0} fontWeight={600} textColor="var(--text)" gradientHeight={0} gradientFrom="transparent" />
          </b>
          <Loc>{w.metricCalls}</Loc>
        </span>
        <span className="wb-trend-metric">
          <Loc>{w.metricAvg}</Loc>
          <b>
            <Counter value={Math.round(totalCalls / Math.max(1, trendPoints.length))} fontSize={13} padding={1} gap={1} horizontalPadding={0} fontWeight={600} textColor="var(--text)" gradientHeight={0} gradientFrom="transparent" />
          </b>
        </span>
        <span className="wb-trend-metric">
          <Loc>{w.metricPeak}</Loc>
          <b>
            <Counter value={max} fontSize={13} padding={1} gap={1} horizontalPadding={0} fontWeight={600} textColor="var(--text)" gradientHeight={0} gradientFrom="transparent" />
          </b>
        </span>
      </div>
    </>
  )

  const importsBody = USE_MOCK ? (
    <div className="wbw-list">
      <div className="task-row"><StatusMark status="running" progress={0.62} size={18} /><span className="names"><span className="t">面经整理 v4.md</span><span className="d">{w.mockParsing(62, 42, 68)}</span></span><LatticeLoader status="working" label="" pattern="sweep" cellSize={4} fontSize={12} showTimer /></div>
      <div className="task-row"><StatusMark status="done" size={18} label="并发编程要点.md" fontSize={13} strike={false} /><span className="names"><span className="d">{w.mockDone(61)}</span></span></div>
      <p className="wbw-hint">{w.mockDemoHint}</p>
    </div>
  ) : importsLoading ? (
    <SkeletonRows rows={3} />
  ) : importsError ? (
    <p className="form-error active" role="alert">{importsError}</p>
  ) : !importRows?.length ? (
    /* 列表磁贴现在是 2 行高，空态只剩一行字会看着空；顺手把入口放到空态里 */
    <div className="wbw-empty">
      <p className="wbw-hint">{w.importsEmpty}</p>
      <button className="btn btn-ghost btn-sm" type="button" onClick={goKb}>{w.openKb}</button>
    </div>
  ) : (
    <div className="wbw-list">
      {importRows.slice(0, 3).map((item) => (
        <div className="task-row" key={item.id}>
          <StatusMark status={item.status} size={18} label={item.status === 'done' ? w.statusReady : item.status === 'failed' ? w.statusFailed : w.statusProcessing} fontSize={12} strike={false} />
          <span className="names"><span className="t">{item.name}</span><span className="d">{item.detail} · {item.updated}</span></span>
        </div>
      ))}
      <button className="btn btn-ghost btn-sm" type="button" onClick={goKb}>{w.openKb}</button>
    </div>
  )

  const timelineBody = activity.length === 0 ? (
    !statsLoading && !statsError ? <div className="wbw-empty"><p className="wbw-hint">{w.timelineEmpty}</p></div> : null
  ) : (
    <ul className="wb-timeline">
      {/* 右上角写的是「最近 5 条」，就真的只画 5 条：多出来的会在卡片内滚动 */}
      {activity.slice(0, 5).map((a, i) => {
        const Icon = KIND_ICON[a.kind]
        return (
          <li key={`${a.kind}-${a.time}-${i}`}>
            <span className="wb-tl-ic"><Icon size={13} /></span>
            <div>
              <div className="wb-tl-t">{a.title}</div>
              <div className="wb-tl-d">{a.detail}</div>
            </div>
            <span className="wb-tl-time">{a.time}</span>
          </li>
        )
      })}
    </ul>
  )

  const heatBody = (
    <>
      <div className="wb-heat" aria-label={heatMetric === 'tokens' ? w.heatMetricAria : w.heatAria}>
        {heat.map((v, i) => (
          <span key={i} data-lv={v} className="wb-heat-cell" />
        ))}
      </div>
      {heatMetric === 'tokens' && activeDays === 0 && (
        <p className="wbw-hint">{w.heatNoTokens}</p>
      )}
      <div className="wb-heat-foot">
        <span>
          {w.heatTotal(heat.length)} · <Loc>{w.heatActive(activeDays)}</Loc>
          {heatMetric === 'tokens' && <> · <Loc>{w.heatTokenSum(heatSumText)}</Loc></>}
        </span>
        <span className="wb-heat-scale">
          <Loc>{w.heatLess}</Loc>
          <i style={{ background: 'var(--surface-muted)' }} />
          <i style={{ background: 'color-mix(in oklab, var(--primary) 26%, var(--surface-muted))' }} />
          <i style={{ background: 'color-mix(in oklab, var(--primary) 50%, var(--surface-muted))' }} />
          <i style={{ background: 'var(--primary-strong)' }} />
          <Loc>{w.heatMore}</Loc>
        </span>
      </div>
    </>
  )

  /* 活跃度磁贴右上角的统计口径切换：按钮而不是 select —— 只有两项，一按即换 */
  const heatMetricSwitch = (
    <span className="wbw-seg" role="group" aria-label={w.heatMetricAria}>
      <button
        type="button"
        aria-pressed={heatMetric === 'messages'}
        onClick={() => setHeatMetric('messages')}
      >
        <Loc>{w.heatByMessages}</Loc>
      </button>
      <button
        type="button"
        aria-pressed={heatMetric === 'tokens'}
        onClick={() => setHeatMetric('tokens')}
      >
        <Loc>{w.heatByTokens}</Loc>
      </button>
    </span>
  )

  const recentBody = (
    <>
      {convLoading && <SkeletonRows rows={3} />}
      {convError && <p className="form-error active" role="alert">{convError}</p>}
      {!convLoading && !convError && recent.length === 0 && <p className="wbw-hint">{w.recentEmpty}</p>}
      {!convLoading && !convError && recent.map((s) => (
        <button
          className="list-row"
          key={s.id}
          type="button"
          style={{ width: '100%', cursor: 'pointer' }}
          onClick={() => onOpenConversation(s.id)}
        >
          <span className="ic" style={{ display: 'inline-flex', width: 32, height: 32, borderRadius: 9, alignItems: 'center', justifyContent: 'center', background: 'var(--primary-container)', color: 'var(--primary-text)', flex: 'none' }}>
            <MessagesSquare size={14} />
          </span>
          <div style={{ minWidth: 0, textAlign: 'left' }}>
            <div style={{ fontSize: 13, fontWeight: 500, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{s.title}</div>
            <div style={{ fontSize: 11.5, color: 'var(--text-3)' }}>{s.updated}</div>
          </div>
        </button>
      ))}
      <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
        <button className="btn btn-ghost btn-sm" type="button" style={{ flex: 1 }} onClick={goKb}>
          <Plus size={13} /> <Loc>{w.manageKb}</Loc>
        </button>
        <button className="btn btn-ghost btn-sm" type="button" style={{ flex: 1 }} onClick={goKb}>
          <FileText size={13} /> <Loc>{w.browseDocs}</Loc>
        </button>
      </div>
    </>
  )

  const renderWidget = (item: WidgetItem) => {
    switch (item.id) {
      case 'stats':
        return <Widget icon={<Wand2 size={15} />} title={<Loc>{w.widgetStats}</Loc>} sub={countsError ? w.countsUnavailable : undefined}>{statsBody}</Widget>
      case 'trend':
        return <Widget icon={<Zap size={15} />} title={<Loc>{w.trendTitle}</Loc>} sub={<Loc>{w.trendRange}</Loc>}>{trendBody}</Widget>
      case 'imports':
        return <Widget icon={<Upload size={15} />} title={<Loc>{w.importsTitle}</Loc>} sub={!USE_MOCK ? (importsLoading ? t.common.loading : w.importCount(importRows?.length ?? 0)) : undefined}>{importsBody}</Widget>
      case 'timeline':
        return <Widget icon={<Clock size={15} />} title={<Loc>{w.timelineTitle}</Loc>} sub={<Loc>{w.timelineRange}</Loc>}>{timelineBody}</Widget>
      case 'heat':
        return <Widget icon={<Database size={15} />} title={<Loc>{w.heatTitle}</Loc>} sub={heatMetricSwitch}>{heatBody}</Widget>
      default:
        return <Widget icon={<MessagesSquare size={15} />} title={<Loc>{w.recentTitle}</Loc>} sub={<button className="btn btn-ghost btn-sm" type="button" onClick={goChat}><Loc>{w.recentAll}</Loc> <ArrowRight size={13} /></button>}>{recentBody}</Widget>
    }
  }

  return (
    <div className="page" data-palette={palette} data-theme={theme}>
      <div className="page-head">
        <div>
          <p className="eyebrow" style={{ margin: 0 }}><Loc>{w.eyebrow}</Loc></p>
          <h1 className="page-title">{currentUser?.nickname || currentUser?.username ? w.greeting(currentUser.nickname || currentUser.username) : <Loc>{w.title}</Loc>}</h1>
          <p className="page-sub"><Loc>{w.sub}</Loc></p>
        </div>
        <div className="wbw-tools">
          <button className="btn btn-ghost btn-sm" type="button" onClick={resetLayout} title={w.resetLayoutTitle}>
            <RotateCcw size={14} /> <Loc>{w.resetLayout}</Loc>
          </button>
          <button className="btn btn-primary" type="button" onClick={goChat}>
            <MessagesSquare size={16} /> <Loc>{w.startChat}</Loc>
          </button>
        </div>
      </div>

      <section className="wb-quick-actions" aria-label={w.quickTitle}>
        <button className="wb-quick" type="button" onClick={goChat}>
          <span className="wb-quick__icon"><MessagesSquare size={16} aria-hidden="true" /></span>
          <span className="wb-quick__text"><b><Loc>{w.quickChat}</Loc></b><small><Loc>{w.quickChatDesc}</Loc></small></span>
          <ArrowRight size={14} className="wb-quick__arrow" aria-hidden="true" />
        </button>
        <button className="wb-quick" type="button" onClick={goKb}>
          <span className="wb-quick__icon"><Upload size={16} aria-hidden="true" /></span>
          <span className="wb-quick__text"><b><Loc>{w.quickKb}</Loc></b><small><Loc>{w.quickKbDesc}</Loc></small></span>
          <ArrowRight size={14} className="wb-quick__arrow" aria-hidden="true" />
        </button>
        <button className="wb-quick" type="button" onClick={goAgents}>
          <span className="wb-quick__icon"><Bot size={16} aria-hidden="true" /></span>
          <span className="wb-quick__text"><b><Loc>{w.quickAgent}</Loc></b><small><Loc>{w.quickAgentDesc}</Loc></small></span>
          <ArrowRight size={14} className="wb-quick__arrow" aria-hidden="true" />
        </button>
        <button className="wb-quick" type="button" onClick={goAutomations}>
          <span className="wb-quick__icon"><Zap size={16} aria-hidden="true" /></span>
          <span className="wb-quick__text"><b><Loc>{w.quickAutomation}</Loc></b><small><Loc>{w.quickAutomationDesc}</Loc></small></span>
          <ArrowRight size={14} className="wb-quick__arrow" aria-hidden="true" />
        </button>
      </section>

      {countsError && <p className="hint" role="status">{w.countsUnavailable} {countsError}</p>}

      <DraggableWidgetGrid
        key={gridEpoch}
        items={widgets}
        persistKey={GRID_KEY}
        onChange={() => { /* 顺序已由网格自己持久化；这里不需要额外动作 */ }}
        renderItem={renderWidget}
        hint={w.dragHint}
        announceMove={(item, index, total) => w.moveAnnounce(item.label ?? item.id, index, total)}
        className="wb-board"
        cellSize={300}
        gap={14}
        radius={16}
        maxColumns={4}
        /* 只给 4 列和 2 列：3 列时 2 宽的磁贴每行留一格空洞，页面反而更高 */
        columnChoices={[2, 4]}
      />
    </div>
  )
}
