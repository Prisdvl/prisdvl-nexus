import { useEffect, useMemo, useState, type CSSProperties } from 'react'
import {
  ArrowRight, BookOpen, Bot, Clock, Database, FileText, MessagesSquare, Plus, Search, Sparkles, Upload, Wand2, Zap,
} from 'lucide-react'
import Carousel from '@/bits/Carousel/Carousel'
import Counter from '@/bits/Counter/Counter'
import LatticeLoader from '@/bits/LatticeLoader/LatticeLoader'
import StatusMark from '@/bits/StatusMark/StatusMark'
import { SkeletonRows } from '@/components/Skeleton'
import { SESSIONS, ACTIVITY as SEED_ACTIVITY, HEAT as SEED_HEAT, TREND as SEED_TREND, type ActivityKind } from '@/data'
import { fetchRecentImports, relTime, useLoad } from '@/lib/backendData'
import type { PaletteName, ThemeMode } from '@/lib/types'
import { api, errMessage, USE_MOCK } from '@/lib/api'

interface Props {
  theme: ThemeMode
  palette: PaletteName
  goChat: () => void
  goKb: () => void
  onOpenConversation: (id: string) => void
}

const CAROUSEL_ITEMS = [
  { id: 1, title: '多知识库聚合', description: '向量 + BM25 + RRF 混合检索，一次提问横跨全部笔记。', icon: <BookOpen className="carousel-icon" /> },
  { id: 2, title: '流式对话', description: '边生成边输出，可随时停止；引用自动标注来源文档。', icon: <MessagesSquare className="carousel-icon" /> },
  { id: 3, title: '自定义 Agent', description: '绑定知识库与工具，让 Agent 带着你的上下文干活。', icon: <Wand2 className="carousel-icon" /> },
  { id: 4, title: '自有部署', description: '连接本地或自建的数据服务；模型角色由应用配置管理。', icon: <Sparkles className="carousel-icon" /> },
]

const KIND_ICON: Record<ActivityKind, typeof Upload> = {
  import: Upload,
  qa: Search,
  agent: Bot,
  chat: MessagesSquare,
}

export default function WorkbenchPage({ theme, palette, goChat, goKb, onOpenConversation }: Props) {
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
        title: c.title ?? '未命名会话',
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
        title: a.type === 'IMPORT' ? `导入 ${a.label ?? ''}` : `会话「${a.label ?? '未命名'}」`,
        detail: a.type === 'IMPORT' ? '导入任务' : '对话',
        time: relTime(a.at),
      }))
  const stats: { label: string; value: number; sub: string; accent?: boolean }[] = [
    { label: '知识库', value: counts.kb, sub: `共 ${counts.docs} 篇文档`, accent: true },
    { label: '文档', value: counts.docs, sub: '已入库切片可检索' },
    { label: '会话', value: counts.conv, sub: '含历史对话' },
    { label: 'Agent', value: counts.agent, sub: '含默认助手' },
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
          <p className="eyebrow" style={{ margin: 0 }}>工作台</p>
          <h1 className="page-title">{currentUser?.nickname || currentUser?.username ? `下午好，${currentUser.nickname || currentUser.username}` : '工作台'}</h1>
          <p className="page-sub">沉淀知识，与 AI 对话，构建你的 Agent —— 今天也继续推进吧。</p>
        </div>
        <button className="btn btn-primary" type="button" onClick={goChat}>
          <MessagesSquare size={16} /> 开始对话
        </button>
      </div>

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
      {countsError && <p className="hint" role="status">统计暂时不可用：{countsError}</p>}

      <div className="wb-layout">
        {/* ---------- 主区 ---------- */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
          <section className="panel wb-card">
            <div className="wb-card-head">
              <h2 className="wb-card-title"><Zap size={16} /> 用量趋势</h2>
              <span className="wb-sub">近 14 天</span>
            </div>
            {statsLoading ? <SkeletonRows rows={2} /> : statsError ? <p className="form-error active" role="alert">{statsError}</p> : trendPoints.length === 0 ? <p className="hint">近 14 天暂无可展示的调用数据。</p> : (
              <>
                <div className="wb-trend" key={trendKey} onMouseLeave={() => setTrendHover(null)}>
                  <div className="wb-trend-y" aria-hidden="true">
                    <span>{max}</span>
                    <span>{mid}</span>
                    <span>0</span>
                  </div>
                  <div className="wb-trend-plot">
                    <svg viewBox="0 0 100 100" preserveAspectRatio="none" role="img" aria-label="近 14 天调用次数趋势">
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
                          aria-label={`${p.label}：${p.count} 次调用`}
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
                    次调用
                  </span>
                  <span className="wb-trend-metric">
                    日均
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
                    峰值
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

          <section className="panel wb-card">
            <div className="wb-card-head">
              <h2 className="wb-card-title"><Upload size={16} /> 最近文档处理</h2>
              {!USE_MOCK && <span className="wb-sub">{importsLoading ? '加载中…' : `${importRows?.length ?? 0} 条`}</span>}
            </div>
            {USE_MOCK ? (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
                <div className="task-row wb-data-in" style={{ '--wb-i': 0 } as CSSProperties}><StatusMark status="running" progress={0.62} size={18} /><span className="names"><span className="t">面经整理 v4.md</span><span className="d">解析中 · 62%（42 / 68 切片）</span></span><LatticeLoader status="working" label="" pattern="sweep" cellSize={4} fontSize={12} showTimer /></div>
                <div className="task-row wb-data-in" style={{ '--wb-i': 1 } as CSSProperties}><StatusMark status="done" size={18} label="并发编程要点.md" fontSize={13} strike={false} /><span className="names"><span className="d">已完成 · 61 切片</span></span></div>
                <p className="hint">离线演示模式：以下为示例数据，连接后端后显示真实处理状态</p>
              </div>
            ) : importsLoading ? (
              <SkeletonRows rows={3} />
            ) : importsError ? (
              <p className="form-error active" role="alert">{importsError}</p>
            ) : !importRows?.length ? (
              <p className="hint">还没有导入文档。新建知识库并上传文件后，处理状态会显示在这里。</p>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                {importRows.map((item, i) => (
                  <div className="task-row wb-data-in" key={item.id} style={{ '--wb-i': i } as CSSProperties}>
                    <StatusMark status={item.status} size={18} label={item.status === 'done' ? '已就绪' : item.status === 'failed' ? '失败' : '处理中'} fontSize={12} strike={false} />
                    <span className="names"><span className="t">{item.name}</span><span className="d">{item.detail} · {item.updated}</span></span>
                  </div>
                ))}
                <button className="btn btn-ghost btn-sm" type="button" onClick={goKb}>打开知识库</button>
              </div>
            )}
          </section>

          <section className="panel wb-card">
            <div className="wb-card-head">
              <h2 className="wb-card-title"><Clock size={16} /> 活动时间线</h2>
              <span className="wb-sub">最近 5 条</span>
            </div>
            <ul className="wb-timeline">
              {activity.length === 0 && !statsLoading && !statsError && (
                <p className="hint" style={{ padding: '4px 0' }}>最近没有活动，发起对话或导入文档后会显示在这里。</p>
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

          <section className="panel wb-card">
            <div className="wb-card-head">
              <h2 className="wb-card-title"><Sparkles size={16} /> 功能速览</h2>
            </div>
            <div className="lp-carousel">
              <Carousel items={CAROUSEL_ITEMS} baseWidth={300} autoplay autoplayDelay={3200} pauseOnHover loop />
            </div>
          </section>
        </div>

        {/* ---------- 右侧栏 ---------- */}
        <aside className="wb-rail">
          <section className="panel wb-card">
            <div className="wb-card-head">
              <h2 className="wb-card-title"><Database size={16} /> 活跃度</h2>
              <span className="wb-sub">近 30 天</span>
            </div>
            <div className="wb-heat" aria-label="近 12 周活跃度">
              {heat.map((v, i) => (
                <span key={i} data-lv={v} className="wb-heat-cell" style={{ '--wb-i': i } as CSSProperties} />
              ))}
            </div>
            <div className="wb-heat-foot wb-data-in" style={{ '--wb-i': 2 } as CSSProperties}>
              <span>共 {heat.length} 天 · 活跃 <b className="wb-heat-active">{activeDays}</b> 天</span>
              <span className="wb-heat-scale">
                少
                <i style={{ background: 'var(--surface-muted)' }} />
                <i style={{ background: 'color-mix(in oklab, var(--primary) 26%, var(--surface-muted))' }} />
                <i style={{ background: 'color-mix(in oklab, var(--primary) 50%, var(--surface-muted))' }} />
                <i style={{ background: 'var(--primary-strong)' }} />
                多
              </span>
            </div>
          </section>

          <section className="panel wb-card">
            <div className="wb-card-head">
              <h2 className="wb-card-title"><MessagesSquare size={16} /> 最近会话</h2>
              <button className="btn btn-ghost btn-sm" type="button" onClick={goChat}>
                全部 <ArrowRight size={13} />
              </button>
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
              {convLoading && <SkeletonRows rows={3} />}
              {convError && <p className="form-error active" role="alert">{convError}</p>}
              {!convLoading && !convError && recent.length === 0 && <p className="hint">还没有会话，开始对话后会显示在这里。</p>}
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
                <Plus size={13} /> 管理知识库
              </button>
              <button className="btn btn-ghost btn-sm" type="button" style={{ flex: 1 }} onClick={goKb}>
                <FileText size={13} /> 浏览文档
              </button>
            </div>
          </section>
        </aside>
      </div>
    </div>
  )
}