import { useEffect, useMemo, useState, type CSSProperties } from 'react'
import {
  ArrowRight, BookOpen, Bot, Clock, Database, FileText, MessagesSquare, Plus, Search, Upload, Wand2, Zap,
} from 'lucide-react'
import Counter from '@/bits/Counter/Counter'
import LatticeLoader from '@/bits/LatticeLoader/LatticeLoader'
import StatusMark from '@/bits/StatusMark/StatusMark'
import Loc from '@/components/Loc'
import { SkeletonRows } from '@/components/Skeleton'
import { SESSIONS, ACTIVITY as SEED_ACTIVITY, HEAT as SEED_HEAT, TREND as SEED_TREND, type ActivityKind } from '@/data'
import { fetchRecentImports, relTime, useLoad } from '@/lib/backendData'
import { useLocale } from '@/lib/locale'
import { pagesCopy } from '@/lib/pagesCopy'
import { appCopy } from '@/lib/appCopy'
import type { PaletteName, ThemeMode } from '@/lib/types'
import { api, errMessage, USE_MOCK } from '@/lib/api'

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
  const heat = USE_MOCK
    ? SEED_HEAT
    : (summary?.heat.map((point) => Math.min(4, point.count)) ?? [])
  const activeDays = heat.filter((value) => value > 0).length
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

  return (
    <div className="page" data-palette={palette} data-theme={theme}>
      <div className="page-head">
        <div>
          <p className="eyebrow" style={{ margin: 0 }}><Loc>{w.eyebrow}</Loc></p>
          <h1 className="page-title">{currentUser?.nickname || currentUser?.username ? w.greeting(currentUser.nickname || currentUser.username) : <Loc>{w.title}</Loc>}</h1>
          <p className="page-sub"><Loc>{w.sub}</Loc></p>
        </div>
        <button className="btn btn-primary" type="button" onClick={goChat}>
          <MessagesSquare size={16} /> <Loc>{w.startChat}</Loc>
        </button>
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

      <div className="stat-grid" aria-busy={countsLoading}>
        {stats.map((s, i) => (
          <div
            key={s.label}
            className={`stat-card wb-data-in${s.accent ? ' accent' : ''}`}
            style={{ '--wb-i': i } as CSSProperties}
          >
            <div className="label">{s.label}</div>
            <div className="num" style={s.accent ? { color: 'var(--primary-text)' } : undefined}>
              {/* 不传 places：按数值位数自动决定（0 → "0"，42 → "42"）。
                  之前固定两位会在全新账号上渲染成 "00"。 */}
              {countsLoading ? '—' : <Counter value={s.value} fontSize={34} padding={4} gap={6} fontWeight={600} textColor="currentColor" gradientFrom="var(--surface)" gradientHeight={10} />}
            </div>
            <div className="sub">{s.sub}</div>
          </div>
        ))}
      </div>
      {countsError && <p className="hint" role="status">{w.countsUnavailable} {countsError}</p>}

      <div className="wb-layout">
        {/* ---------- 主区 ---------- */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
          <section className="panel wb-card">
            <div className="wb-card-head">
              <h2 className="wb-card-title"><Zap size={16} /> <Loc>{w.trendTitle}</Loc></h2>
              <span className="wb-sub"><Loc>{w.trendRange}</Loc></span>
            </div>
            {statsLoading ? <SkeletonRows rows={2} /> : statsError ? <p className="form-error active" role="alert">{statsError}</p> : trendPoints.length === 0 ? <p className="hint">{w.trendEmpty}</p> : (
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
                        <line
                          className="trend-cross"
                          x1={hover.x}
                          y1="8"
                          x2={hover.x}
                          y2="92"
                        />
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
                      <div
                        className="wb-trend-tip"
                        style={{ left: `${hover.x}%`, top: `${hover.y}%` }}
                        role="status"
                      >
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
                      <Counter
                        value={totalCalls}
                        fontSize={13}
                        padding={1}
                        gap={1}
                        horizontalPadding={0}
                        fontWeight={600}
                        textColor="var(--text)"
                        gradientHeight={0}
                        gradientFrom="transparent"
                      />
                    </b>
                    <Loc>{w.metricCalls}</Loc>
                  </span>
                  <span className="wb-trend-metric">
                    <Loc>{w.metricAvg}</Loc>
                    <b>
                      <Counter
                        value={Math.round(totalCalls / Math.max(1, trendPoints.length))}
                        fontSize={13}
                        padding={1}
                        gap={1}
                        horizontalPadding={0}
                        fontWeight={600}
                        textColor="var(--text)"
                        gradientHeight={0}
                        gradientFrom="transparent"
                      />
                    </b>
                  </span>
                  <span className="wb-trend-metric">
                    <Loc>{w.metricPeak}</Loc>
                    <b>
                      <Counter
                        value={max}
                        fontSize={13}
                        padding={1}
                        gap={1}
                        horizontalPadding={0}
                        fontWeight={600}
                        textColor="var(--text)"
                        gradientHeight={0}
                        gradientFrom="transparent"
                      />
                    </b>
                  </span>
                </div>
              </>
            )}
          </section>

          <div className="wb-duo">
          <section className="panel wb-card">
            <div className="wb-card-head">
              <h2 className="wb-card-title"><Upload size={16} /> <Loc>{w.importsTitle}</Loc></h2>
              {!USE_MOCK && <span className="wb-sub">{importsLoading ? t.common.loading : w.importCount(importRows?.length ?? 0)}</span>}
            </div>
            {USE_MOCK ? (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
                <div className="task-row wb-data-in" style={{ '--wb-i': 0 } as CSSProperties}><StatusMark status="running" progress={0.62} size={18} /><span className="names"><span className="t">面经整理 v4.md</span><span className="d">{w.mockParsing(62, 42, 68)}</span></span><LatticeLoader status="working" label="" pattern="sweep" cellSize={4} fontSize={12} showTimer /></div>
                <div className="task-row wb-data-in" style={{ '--wb-i': 1 } as CSSProperties}><StatusMark status="done" size={18} label="并发编程要点.md" fontSize={13} strike={false} /><span className="names"><span className="d">{w.mockDone(61)}</span></span></div>
                <p className="hint">{w.mockDemoHint}</p>
              </div>
            ) : importsLoading ? (
              <SkeletonRows rows={3} />
            ) : importsError ? (
              <p className="form-error active" role="alert">{importsError}</p>
            ) : !importRows?.length ? (
              <p className="hint">{w.importsEmpty}</p>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                {importRows.map((item, i) => (
                  <div className="task-row wb-data-in" key={item.id} style={{ '--wb-i': i } as CSSProperties}>
                    <StatusMark status={item.status} size={18} label={item.status === 'done' ? w.statusReady : item.status === 'failed' ? w.statusFailed : w.statusProcessing} fontSize={12} strike={false} />
                    <span className="names"><span className="t">{item.name}</span><span className="d">{item.detail} · {item.updated}</span></span>
                  </div>
                ))}
                <button className="btn btn-ghost btn-sm" type="button" onClick={goKb}>{w.openKb}</button>
              </div>
            )}
          </section>

          <section className="panel wb-card">
            <div className="wb-card-head">
              <h2 className="wb-card-title"><Clock size={16} /> <Loc>{w.timelineTitle}</Loc></h2>
              <span className="wb-sub"><Loc>{w.timelineRange}</Loc></span>
            </div>
            <ul className="wb-timeline">
              {activity.length === 0 && !statsLoading && !statsError && (
                <p className="hint" style={{ padding: '4px 0' }}>{w.timelineEmpty}</p>
              )}
              {activity.map((a, i) => {
                const Icon = KIND_ICON[a.kind]
                return (
                  <li key={`${a.kind}-${a.time}-${i}`} className="wb-data-in" style={{ '--wb-i': i } as CSSProperties}>
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
          </section>
          </div>

        </div>

        {/* ---------- 右侧栏 ---------- */}
        <aside className="wb-rail">
          <section className="panel wb-card">
            <div className="wb-card-head">
              <h2 className="wb-card-title"><Database size={16} /> <Loc>{w.heatTitle}</Loc></h2>
              <span className="wb-sub"><Loc>{w.heatRange}</Loc></span>
            </div>
            <div className="wb-heat" aria-label={w.heatAria}>
              {heat.map((v, i) => (
                <span key={i} data-lv={v} className="wb-heat-cell" style={{ '--wb-i': i } as CSSProperties} />
              ))}
            </div>
            <div className="wb-heat-foot wb-data-in" style={{ '--wb-i': 2 } as CSSProperties}>
              <span>{w.heatTotal(heat.length)} · <Loc>{w.heatActive(activeDays)}</Loc></span>
              <span className="wb-heat-scale">
                <Loc>{w.heatLess}</Loc>
                <i style={{ background: 'var(--surface-muted)' }} />
                <i style={{ background: 'color-mix(in oklab, var(--primary) 26%, var(--surface-muted))' }} />
                <i style={{ background: 'color-mix(in oklab, var(--primary) 50%, var(--surface-muted))' }} />
                <i style={{ background: 'var(--primary-strong)' }} />
                <Loc>{w.heatMore}</Loc>
              </span>
            </div>
          </section>

          <section className="panel wb-card">
            <div className="wb-card-head">
              <h2 className="wb-card-title"><MessagesSquare size={16} /> <Loc>{w.recentTitle}</Loc></h2>
              <button className="btn btn-ghost btn-sm" type="button" onClick={goChat}>
                <Loc>{w.recentAll}</Loc> <ArrowRight size={13} />
              </button>
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
              {convLoading && <SkeletonRows rows={3} />}
              {convError && <p className="form-error active" role="alert">{convError}</p>}
              {!convLoading && !convError && recent.length === 0 && <p className="hint">{w.recentEmpty}</p>}
              {!convLoading && !convError && recent.map((s, i) => (
                <button
                  className="list-row wb-data-in"
                  key={s.id}
                  type="button"
                  style={{ width: '100%', cursor: 'pointer', '--wb-i': i } as CSSProperties}
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
            </div>
            <div style={{ display: 'flex', gap: 8, marginTop: 14 }}>
              <button className="btn btn-ghost btn-sm" type="button" style={{ flex: 1 }} onClick={goKb}>
                <Plus size={13} /> <Loc>{w.manageKb}</Loc>
              </button>
              <button className="btn btn-ghost btn-sm" type="button" style={{ flex: 1 }} onClick={goKb}>
                <FileText size={13} /> <Loc>{w.browseDocs}</Loc>
              </button>
            </div>
          </section>
        </aside>
      </div>
    </div>
  )
}
