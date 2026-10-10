import { useEffect, useState, type FormEvent } from 'react'
import {
  ArrowRight, BookOpen, Bot, Clock, Database, FileText, MessagesSquare, Play, Plus, Search, Sparkles, Upload, Wand2, Zap,
} from 'lucide-react'
import BorderGlow from '@/bits/BorderGlow/BorderGlow'
import Carousel from '@/bits/Carousel/Carousel'
import Counter from '@/bits/Counter/Counter'
import LatticeLoader from '@/bits/LatticeLoader/LatticeLoader'
import StatusMark from '@/bits/StatusMark/StatusMark'
import { SkeletonRows } from '@/components/Skeleton'
import { AGENTS, SESSIONS, ACTIVITY as SEED_ACTIVITY, HEAT as SEED_HEAT, TREND as SEED_TREND, type ActivityKind } from '@/data'
import { relTime, useLoad } from '@/lib/backendData'
import type { PaletteName, ThemeMode } from '@/lib/types'
import { api, USE_MOCK } from '@/lib/api'

interface Props {
  theme: ThemeMode
  palette: PaletteName
  goChat: () => void
  goKb: () => void
}

const CAROUSEL_ITEMS = [
  { id: 1, title: '多知识库聚合', description: '向量 + BM25 + RRF 混合检索，一次提问横跨全部笔记。', icon: <BookOpen className="carousel-icon" /> },
  { id: 2, title: '流式对话', description: '边生成边输出，可随时停止；引用自动标注来源文档。', icon: <MessagesSquare className="carousel-icon" /> },
  { id: 3, title: '自定义 Agent', description: '绑定知识库与工具，让 Agent 带着你的上下文干活。', icon: <Wand2 className="carousel-icon" /> },
  { id: 4, title: '本地优先', description: '数据落在自己的 MySQL / pgvector / ES，模型可自选。', icon: <Sparkles className="carousel-icon" /> },
]

const GLOW_BY_PALETTE: Record<PaletteName, { colors: string[]; hsl: string }> = {
  terracotta: { colors: ['#d97a52', '#e0a98a', '#8f4a2e'], hsl: '25 60% 55%' },
  turquoise: { colors: ['#2fb3c9', '#7fd8e3', '#1f7a8c'], hsl: '190 60% 52%' },
  lime: { colors: ['#8cc63f', '#c9e35e', '#4d7c0f'], hsl: '128 50% 52%' },
}

const KIND_ICON: Record<ActivityKind, typeof Upload> = {
  import: Upload,
  qa: Search,
  agent: Bot,
  chat: MessagesSquare,
}

export default function WorkbenchPage({ theme, palette, goChat, goKb }: Props) {
  const glow = GLOW_BY_PALETTE[palette]
  const cardBg = theme === 'dark' ? '#1a1a1c' : '#ffffff'
  const [quickIn, setQuickIn] = useState('')
  const [quickRun, setQuickRun] = useState(false)
  const [quickOut, setQuickOut] = useState('')
  const [quickAgent, setQuickAgent] = useState(AGENTS[0].name)
  const [taskDone, setTaskDone] = useState(true)
  /* 原型：用 700ms 的"加载中"演示骨架屏（真实实现由请求状态驱动） */
  const [sessionsLoading, setSessionsLoading] = useState(true)
  useEffect(() => {
    const t = setTimeout(() => setSessionsLoading(false), 700)
    return () => clearTimeout(t)
  }, [])

  const runQuick = (e: FormEvent) => {
    e.preventDefault()
    if (!quickIn.trim() || quickRun) return
    setQuickRun(true)
    setQuickOut('')
    setTimeout(() => {
      setQuickRun(false)
      setQuickOut(`Agent「${quickAgent}」：已在「我的 Java 笔记」检索到 3 个切片并作答。\n（原型示意）`)
    }, 2000)
  }

  /* 真实数据：四个计数来自后端列表接口；mock 模式保留原演示数字 */
  const [counts, setCounts] = useState({ kb: 3, docs: 18, conv: 12, agent: 4 })
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
      .catch(() => { /* 工作台计数失败不阻塞页面 */ })
    return () => { alive = false }
  }, [])

  /* 最近会话：取真实会话列表前 3 条（mock 模式仍用种子 SESSIONS） */
  const { data: convPage, loading: convLoading } = useLoad(() => api.conversations.list(1, 3))
  const recent = USE_MOCK
    ? SESSIONS.slice(0, 3)
    : (convPage?.list ?? []).map((c) => ({
        id: c.id,
        title: c.title ?? '未命名会话',
        updated: relTime(c.lastMessageAt ?? c.updatedAt),
      }))
  /* 工作台聚合：趋势(14) / 热力(30) / 最近活动(8)，来自 GET /api/stats/summary */
  const { data: summary } = useLoad(() => api.stats.summary())
  const trend = USE_MOCK ? SEED_TREND : (summary?.trend.map((p) => p.count) ?? SEED_TREND)
  const heat = USE_MOCK
    ? SEED_HEAT
    : (() => {
        const tail = (summary?.heat ?? []).map((d) => Math.min(4, d.count))
        const pad = Math.max(0, 84 - tail.length)
        return [...Array<number>(pad).fill(0), ...tail].slice(-84)
      })()
  const activity = USE_MOCK
    ? SEED_ACTIVITY
    : (summary?.activity ?? []).map((a) => ({
        kind: (a.type === 'IMPORT' ? 'import' : 'chat') as ActivityKind,
        title: a.type === 'IMPORT' ? `导入 ${a.label ?? ''}` : `会话「${a.label ?? '未命名'}」`,
        detail: a.type === 'IMPORT' ? '导入任务' : '对话',
        time: relTime(a.at),
      }))
  const stats: { label: string; value: number; sub: string; accent?: boolean }[] = [
    { label: '知识库', value: counts.kb, sub: '共 ' + counts.docs + ' 篇文档', accent: true },
    { label: '文档', value: counts.docs, sub: '已入库切片可检索' },
    { label: '会话', value: counts.conv, sub: '含历史对话' },
    { label: 'Agent', value: counts.agent, sub: '含默认助手' },
  ]

  // 趋势折线（14 天）
  const max = Math.max(...trend)
  const pts = trend.map((v, i) => {
    const x = (i / (trend.length - 1)) * 100
    const y = 100 - (v / max) * 88 - 6
    return `${x.toFixed(2)},${y.toFixed(2)}`
  })
  const line = `M ${pts.join(' L ')}`
  const area = `${line} L 100,100 L 0,100 Z`

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <p className="eyebrow" style={{ margin: 0 }}>工作台</p>
          <h1 className="page-title">下午好，demo</h1>
          <p className="page-sub">沉淀知识，与 AI 对话，构建你的 Agent —— 今天也继续推进吧。</p>
        </div>
        <button className="btn btn-primary" type="button" onClick={goChat}>
          <MessagesSquare size={16} /> 开始对话
        </button>
      </div>

      {/* 统计四卡：统一 BorderGlow，错峰扫光 */}
      <div className="stat-grid">
        {stats.map((s, i) => (
          <BorderGlow
            key={s.label}
            colors={glow.colors}
            glowColor={glow.hsl}
            backgroundColor={cardBg}
            borderRadius={14}
            glowRadius={22}
            edgeSensitivity={45}
            glowIntensity={0.9}
            animated={i === 0}
            className="stat-card"
          >
            <div className="label">{s.label}</div>
            <div className="num" style={s.accent ? { color: 'var(--primary-text)' } : undefined}>
              <Counter value={s.value} places={s.value > 9 ? [10, 1] : [10, 1]} fontSize={34} padding={4} gap={6} fontWeight={600} textColor="currentColor" />
            </div>
            <div className="sub">{s.sub}</div>
          </BorderGlow>
        ))}
      </div>

      <div className="wb-layout">
        {/* ---------- 主区 ---------- */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
          <section className="panel wb-card">
            <div className="wb-card-head">
              <h2 className="wb-card-title"><Zap size={16} /> 用量趋势</h2>
              <span className="wb-sub">近 14 天</span>
            </div>
            <div className="wb-trend">
              <svg viewBox="0 0 100 100" preserveAspectRatio="none" role="img" aria-label="近 14 天 LLM 调用数趋势">
                <defs>
                  <linearGradient id="wbArea" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="var(--primary)" stopOpacity="0.28" />
                    <stop offset="100%" stopColor="var(--primary)" stopOpacity="0" />
                  </linearGradient>
                </defs>
                <path className="trend-area" d={area} fill="url(#wbArea)" />
                <path className="trend-line" d={line} fill="none" stroke="var(--primary)" strokeWidth="1.6" vectorEffect="non-scaling-stroke" strokeLinejoin="round" strokeLinecap="round" pathLength={1} />
              </svg>
            </div>
            <div className="wb-trend-legend">
              <span>
                <b><Counter value={trend.reduce((a, b) => a + b, 0)} places={[1000, 100, 10, 1]} fontSize={13} fontWeight={600} gap={1} padding={2} /></b> 次调用
              </span>
              <span>
                <b><Counter value={trend.reduce((a, b) => a + b, 0) * 2} places={[1000, 100, 10, 1]} fontSize={13} fontWeight={600} gap={1} padding={2} /></b> Tokens
              </span>
              <span>
                <b><Counter value={1.24} places={[1, '.', 0.1, 0.01]} fontSize={13} fontWeight={600} gap={1} padding={2} /></b>s 平均时延
              </span>
            </div>
          </section>

          <section className="panel wb-card">
            <div className="wb-card-head">
              <h2 className="wb-card-title"><Upload size={16} /> 导入任务</h2>
              <span className="wb-sub">2 进行中 · 1 排队</span>
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
              <div>
                <div className="task-row">
                  <StatusMark status="running" progress={0.62} size={18} />
                  <span className="names">
                    <span className="t">面经整理 v4.md</span>
                    <span className="d">解析中 · 62%（42 / 68 切片）</span>
                  </span>
                  <LatticeLoader status="working" label="" pattern="sweep" cellSize={4} fontSize={12} showTimer />
                </div>
                <div className="task-progress"><i style={{ width: '62%' }} /></div>
              </div>
              <div>
                <div className="task-row">
                  <StatusMark status="running" size={18} />
                  <span className="names">
                    <span className="t">MySQL 索引原理.pdf</span>
                    <span className="d">向量化中 · 不确定进度</span>
                  </span>
                </div>
              </div>
              <div className="task-row">
                <StatusMark status="pending" size={18} label="Java 集合寻址.md" fontSize={13} />
                <span className="names"><span className="d">排队中 · 等待解析器空闲</span></span>
              </div>
              <div className="task-row">
                <StatusMark status="done" size={18} label="并发编程要点.md" fontSize={13} strike={false} />
                <span className="names"><span className="d">已完成 · 61 切片 · 用时 8.4s</span></span>
              </div>
              <div style={{ display: 'flex', gap: 8 }}>
                <button
                  className="btn btn-ghost btn-sm"
                  type="button"
                  onClick={() => {
                    setTaskDone(false)
                    window.setTimeout(() => setTaskDone(true), 2600)
                  }}
                >
                  重跑一个任务（演示状态流转）
                </button>
                {taskDone && <StatusMark status="done" size={16} label="任务完成" fontSize={12.5} strike={false} />}
              </div>
            </div>
          </section>

          <section className="panel wb-card">
            <div className="wb-card-head">
              <h2 className="wb-card-title"><Clock size={16} /> 活动时间线</h2>
              <span className="wb-sub">最近 5 条</span>
            </div>
            <ul className="wb-timeline">
              {activity.map((a) => {
                const Icon = KIND_ICON[a.kind]
                return (
                  <li key={a.title}>
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
              <h2 className="wb-card-title"><Database size={16} /> 活力图</h2>
              <span className="wb-sub">近 12 周</span>
            </div>
            <div className="wb-heat" aria-label="近 12 周活跃度">
              {heat.map((v, i) => <span key={i} data-lv={v} />)}
            </div>
            <div className="wb-heat-foot">
              <span>共 84 天 · 活跃 61 天</span>
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
              <h2 className="wb-card-title"><Bot size={16} /> Agent 快捷测试</h2>
            </div>
            <div className="wb-quick">
              <div className="wb-quick-chips">
                {AGENTS.slice(0, 3).map((a) => (
                  <button
                    key={a.id}
                    type="button"
                    className="chip"
                    data-on={quickAgent === a.name ? '' : undefined}
                    onClick={() => setQuickAgent(a.name)}
                  >
                    {a.name}
                  </button>
                ))}
              </div>
              <form className="wb-quick-row" onSubmit={runQuick}>
                <input aria-label="给 Agent 的指令"
                  className="input"
                  value={quickIn}
                  onChange={(e) => setQuickIn(e.target.value)}
                  placeholder="给 Agent 一条指令…"
                />
                <button className="btn btn-primary" type="submit" aria-label="发送指令给 Agent" disabled={!quickIn.trim() || quickRun}>
                  <Play size={14} />
                </button>
              </form>
              {quickRun && <LatticeLoader status="working" label="执行中" pattern="orbit" cellSize={5} fontSize={13} showTimer />}
              {!quickRun && quickOut && <div className="wb-quick-out">{quickOut}</div>}
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
              {sessionsLoading && <SkeletonRows rows={3} />}
              {!(USE_MOCK ? sessionsLoading : convLoading) && recent.map((s) => (
                <button className="list-row" key={s.id} type="button" style={{ width: '100%', cursor: 'pointer' }} onClick={goChat}>
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
                <Plus size={13} /> 新建知识库
              </button>
              <button className="btn btn-ghost btn-sm" type="button" style={{ flex: 1 }} onClick={goKb}>
                <FileText size={13} /> 导入文档
              </button>
            </div>
          </section>
        </aside>
      </div>
    </div>
  )
}