import { useEffect, useRef, useState, type ReactNode } from 'react'
import { motion, useReducedMotion } from 'motion/react'
import { ArrowDown, ArrowRight, ArrowUp, Bot, Code2, FileText, Plus, Search, Wrench } from 'lucide-react'
import PatternWaves from '@/bits/PatternWaves/PatternWaves'
import SkipLink from '@/components/SkipLink'
import TechText from '@/bits/TechText/TechText'
import CrystalizedBall from '@/bits/CrystalizedBall/CrystalizedBall'
import SlingButton, { type SlingSendDetail } from '@/bits/SlingButton/SlingButton'
import OptionWheel from '@/bits/OptionWheel/OptionWheel'
import CallChip, { type CallChipStatus } from '@/bits/CallChip/CallChip'
import Modal from '@/components/Modal'
import CardNav, { type CardNavItem } from '@/bits/CardNav/CardNav'
import { FloatingNav, type FloatingNavItem } from '@/bits/FloatingNav/FloatingNav'
import '@/bits/FloatingNav/FloatingNav.css'
import '@/styles/landing-cardnav.css'
import BrandMark from '@/components/BrandMark'
import LogoLoop, { type LogoItem } from '@/bits/LogoLoop/LogoLoop'
import { AGENTS, type AgentItem } from '@/data'
import type { PaletteName, ThemeMode } from '@/lib/types'
import { isWebGL2Available } from '@/lib/webgl'
import { PRISDVL_ASCII } from '@/assets/prisdvlAscii'
import {
  siDocker,
  siElasticsearch,
  siFlyway,
  siNginx,
  siOpenjdk,
  siPostgresql,
  siRabbitmq,
  siReact,
  siRedis,
  siSpring,
  siSpringboot,
  siTypescript,
  siVite,
  type SimpleIcon,
} from 'simple-icons'

function BrandMarkIcon({ icon }: { icon: SimpleIcon }) {
  return (
    <svg className="lp-logo-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <path d={icon.path} fill="currentColor" />
    </svg>
  )
}

function TechChip({ label, icon }: { label: string; icon?: SimpleIcon }) {
  return (
    <span className="lp-logo-chip">
      {icon ? <BrandMarkIcon icon={icon} /> : null}
      {label}
    </span>
  )
}

/** 技术栈滚动：有官方图标的带上 SVG；长名用技术简称 */
const TECH_LOGOS: LogoItem[] = (
  [
    { label: 'Java', icon: siOpenjdk, title: 'Java 21 / OpenJDK' },
    { label: 'Spring', icon: siSpringboot, title: 'Spring Boot' },
    { label: 'Spring AI', icon: siSpring, title: 'Spring AI' },
    { label: 'React', icon: siReact, title: 'React 19' },
    { label: 'TS', icon: siTypescript, title: 'TypeScript' },
    { label: 'Vite', icon: siVite, title: 'Vite' },
    { label: 'Postgres', icon: siPostgresql, title: 'PostgreSQL' },
    { label: 'pgvector', title: 'pgvector' },
    { label: 'ES', icon: siElasticsearch, title: 'Elasticsearch' },
    { label: 'RabbitMQ', icon: siRabbitmq, title: 'RabbitMQ' },
    { label: 'Redis', icon: siRedis, title: 'Redis' },
    { label: 'MyBatis', title: 'MyBatis-Plus' },
    { label: 'Flyway', icon: siFlyway, title: 'Flyway' },
    { label: 'Docker', icon: siDocker, title: 'Docker' },
    { label: 'Nginx', icon: siNginx, title: 'Nginx' },
  ] as const
).map((item) => ({
  node: <TechChip label={item.label} icon={'icon' in item ? item.icon : undefined} />,
  title: item.title,
  ariaLabel: item.title,
}))

/** 对齐 Agent 会话：模式 → 思考 → 工具链 → 上下文 → 回答 + 引用 */
const FLOW_STEPS = [
  { key: 'mode', label: '会话模式', hint: 'Agent · 面试助手' },
  { key: 'think', label: '正在生成回答', hint: 'ThoughtLine 开场' },
  { key: 'kb', label: '调用工具 · 知识库检索', hint: 'kb.search → running → done' },
  { key: 'doc', label: '调用工具 · 读取文档', hint: 'doc.read · 集合框架.md' },
  { key: 'ctx', label: '上下文汇总', hint: '命中 6 切片 · 引用 2 篇' },
  { key: 'answer', label: '生成回答', hint: '流式输出 · 出处可核对' },
] as const

const FLOW_DEMO_QUESTION = 'B+ 树为什么适合做数据库索引？'
const FLOW_DEMO_ANSWER = {
  tool: 'kb.search → doc.read',
  cite: '集合框架.md · MySQL 索引原理.pdf',
  text: '叶子节点串成有序链表，范围扫描与磁盘页对齐都更省；面试助手会先检索再引用原文。',
}
const FLOW_DEMO_CITES = ['集合框架.md', 'MySQL 索引原理.pdf'] as const

/** 首页角落 OptionWheel：项目相关关键词 */
const HERO_WHEEL_ITEMS = ['知识库', 'Agent', '混合检索', '原文引用', '工具轨迹', 'Nexus'] as const

const PRISD_BANNER = [
  ' ____  ____  ___ ____  ____',
  '|  _ \\|  _ \\|_ _/ ___||  _ \\',
  '| |_) | |_) || |\\___ \\| | | |',
  '|  __/|  _ < | | ___) | |_| |',
  '|_|   |_| \\_\\___|____/|____/',
].join('\n')

/** 终端彩蛋：ask 每次换一句；其它指令也有玩梗回复 */
const ASK_EGGS = [
  '切片里先搜「pause」，别一上来加堆。出处：JVM 调优笔记 § Young GC。',
  '向量召回碰到近义词了：把「频繁」换成「抖动」再试一次。',
  '工具轨迹：knowledge_search → 3 条命中 → 取最高分切片作答。',
  '这题更适合绑「课件 Agent」：它已经挂了你的 JVM 笔记库。',
  '引用未就绪？先等嵌入完成，状态条会从 indexing 变 ready。',
  '答案可以核对：点开回答旁的切片，会跳回原文段落。',
  '沙箱没空跑代码，但笔记里有一段 `-XX:NewRatio` 的对照表。',
  '换个问法：Young GC 频繁时，先看存活率还是先看停顿？',
  '你问的次数只留在本页会话里，刷新就散了。',
  'nexus shell 说：资料在，出处在，别信没有引用的自信。',
] as const

const EGG_REPLIES: Record<string, string[]> = {
  help: [
    'commands: ask <问题> · help · clear · whoami · banner · fortune · ping',
    `提示：必须以指令开头。例：ask ${FLOW_DEMO_QUESTION}`,
  ],
  whoami: [
    'prisdvl@nexus — guest shell（落地页演示，不是真后端）',
    'uid=demo gid=landing tty=lp-term',
  ],
  fortune: [
    '好的知识库，是会反问你的。',
    '没有出处的答案，只是口气很稳的猜测。',
    '工具调用留下轨迹，比一次「全对」更重要。',
    '切片短一点，引用准一点。',
  ],
  ping: ['pong — latency 0ms (local)', 'pong — still fake, still fast', 'pong — 42'],
}

type EggLine =
  | { kind: 'cmd'; text: string }
  | { kind: 'out'; text: string }
  | { kind: 'art'; text: string }
  | { kind: 'err'; text: string }
  | { kind: 'ok'; text: string }
  | { kind: 'dim'; text: string }

function pickEgg<T>(list: readonly T[], avoid?: T): T {
  if (list.length === 1) return list[0]
  let next = list[Math.floor(Math.random() * list.length)]
  if (avoid !== undefined && list.length > 1) {
    let guard = 0
    while (next === avoid && guard < 6) {
      next = list[Math.floor(Math.random() * list.length)]
      guard += 1
    }
  }
  return next
}

function runEggCommand(raw: string, lastAsk?: string): { lines: EggLine[]; clear?: boolean; lastAsk?: string } {
  const line = raw.trim()
  if (!line) return { lines: [] }
  const m = /^([a-zA-Z_]+)(?:\s+|$)([\s\S]*)$/.exec(line)
  if (!m) {
    return {
      lines: [{ kind: 'err', text: `unknown input — try: ask ${FLOW_DEMO_QUESTION}` }],
    }
  }
  const cmd = m[1].toLowerCase()
  const arg = m[2].trim().replace(/^["']|["']$/g, '')

  if (cmd === 'ask') {
    if (!arg) {
      return {
        lines: [
          { kind: 'err', text: 'usage: ask <问题>' },
          { kind: 'dim', text: `example: ask ${FLOW_DEMO_QUESTION}` },
        ],
      }
    }
    const reply = pickEgg(ASK_EGGS, lastAsk)
    const q = arg.slice(0, 64) + (arg.length > 64 ? '…' : '')
    return {
      lastAsk: reply,
      lines: [
        { kind: 'dim', text: `→ agent pipeline · sse stream` },
        { kind: 'dim', text: `  ✓ mode=AGENT agent=面试助手` },
        { kind: 'dim', text: `  ✓ thought: 正在生成回答` },
        { kind: 'dim', text: `  ✓ tool kb.search "${q}" · done` },
        { kind: 'dim', text: `  ✓ tool doc.read · 集合框架.md · done` },
        { kind: 'dim', text: `  ✓ context: 命中 6 切片 · 引用 2 篇` },
        { kind: 'ok', text: 'ok' },
        { kind: 'out', text: reply },
        { kind: 'dim', text: `// cites: ${FLOW_DEMO_ANSWER.cite}` },
        { kind: 'dim', text: '# local demo · not a live model' },
      ],
    }
  }
  if (cmd === 'clear') return { lines: [], clear: true }
  if (cmd === 'help') {
    return { lines: EGG_REPLIES.help.map((text) => ({ kind: 'out' as const, text })) }
  }
  if (cmd === 'banner') {
    return {
      lines: [
        { kind: 'out', text: PRISD_BANNER },
        { kind: 'art', text: PRISDVL_ASCII },
      ],
    }
  }
  if (cmd === 'whoami' || cmd === 'fortune' || cmd === 'ping') {
    return { lines: [{ kind: 'out', text: pickEgg(EGG_REPLIES[cmd]) }] }
  }
  return {
    lines: [
      { kind: 'err', text: `command not found: ${cmd}` },
      { kind: 'dim', text: 'type help — commands start with ask / help / clear …' },
    ],
  }
}

function AgentPageDemo() {
  const [active, setActive] = useState<AgentItem>(AGENTS[0])

  return (
    <div className="lp-agent-demo" aria-label="Agent 页面演示">
      <div className="lp-agent-demo__chrome" aria-hidden="true">
        <span className="lp-mac-dot lp-mac-dot--close" />
        <span className="lp-mac-dot lp-mac-dot--min" />
        <span className="lp-mac-dot lp-mac-dot--max" />
        <em>app / agents</em>
      </div>
      <div className="lp-agent-demo__body">
        <header className="lp-agent-demo__head">
          <div>
            <p className="lp-agent-demo__eyebrow">Agent</p>
            <h3>我的 Agent</h3>
            <p>把知识和工具打包成一个会干活的身份</p>
          </div>
          <span className="lp-agent-demo__new" aria-hidden="true"><Plus size={14} /> 新建 Agent</span>
        </header>

        <div className="lp-agent-demo__grid" role="list">
          {AGENTS.map((agent) => {
            const selected = agent.id === active.id
            return (
              <button
                key={agent.id}
                type="button"
                role="listitem"
                className={`lp-agent-demo__card${selected ? ' is-active' : ''}`}
                aria-pressed={selected}
                onClick={() => setActive(agent)}
              >
                <div className="lp-agent-demo__card-title">
                  <Bot size={14} />
                  <strong>{agent.name}</strong>
                </div>
                <p>{agent.desc}</p>
                <div className="lp-agent-demo__tags">
                  <span>{agent.model}</span>
                  {agent.tools.slice(0, 2).map((t) => <span key={t}>{t}</span>)}
                </div>
                <small>绑定 {agent.kb}</small>
              </button>
            )
          })}
        </div>
      </div>
    </div>
  )
}

/** 产品段：三条能力的轻量示意（连接段放在卡片间隙，避免被裁切） */
function WhatSchematic({ reduceMotion }: { reduceMotion: boolean | null }) {
  const items = [
    { icon: <FileText size={16} />, label: '资料入库', sub: '切片 · 索引' },
    { icon: <Search size={16} />, label: '混合检索', sub: '向量 · 全文' },
    { icon: <Wrench size={16} />, label: '工具轨迹', sub: '可回看' },
  ] as const

  return (
    <div className="lp-what-schematic" aria-hidden="true">
      {items.map((item, i) => (
        <div key={item.label} className="lp-what-schematic__unit">
          <motion.div
            className="lp-what-schematic__node"
            initial={reduceMotion ? false : { opacity: 0, y: 10 }}
            whileInView={reduceMotion ? undefined : { opacity: 1, y: 0 }}
            viewport={{ once: true, margin: '-20px' }}
            transition={{ duration: 0.45, delay: 0.08 * i, ease: [0.23, 1, 0.32, 1] }}
          >
            <span className="lp-what-schematic__icon">{item.icon}</span>
            <strong>{item.label}</strong>
            <em>{item.sub}</em>
          </motion.div>
          {i < items.length - 1 && (
            <span className={`lp-what-schematic__bridge${reduceMotion ? '' : ' is-animated'}`} />
          )}
        </div>
      ))}
    </div>
  )
}

type TermPhase = 'idle' | 'banner' | 'typing' | 'running' | 'done'

function TermPrompt({ children }: { children?: ReactNode }) {
  return (
    <>
      <span className="lp-term__user">prisdvl</span>
      <span className="lp-term__at">@</span>
      <span className="lp-term__host">nexus</span>
      <span className="lp-term__path">:~</span>
      <span className="lp-term__hash"> %</span>
      {children != null ? <>{' '}{children}</> : null}
    </>
  )
}

/** 提问段：仿终端开机演示 + 可输入彩蛋（ask 每次不同回复） */
function FlowAskDemo({
  reduceMotion,
  accent,
  theme,
  compact = false,
}: {
  reduceMotion: boolean | null
  accent: string
  theme: ThemeMode
  /** 首页旁栏：收紧高度、隐藏 ASCII 与步骤轨 */
  compact?: boolean
}) {
  const rootRef = useRef<HTMLDivElement>(null)
  const bodyRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const [started, setStarted] = useState(false)
  const [phase, setPhase] = useState<TermPhase>('idle')
  const [bannerShown, setBannerShown] = useState(false)
  const [typed, setTyped] = useState('')
  const [step, setStep] = useState(-1)
  const [done, setDone] = useState(false)
  const [eggLog, setEggLog] = useState<EggLine[]>([])
  const [draft, setDraft] = useState('ask ')
  const [busy, setBusy] = useState(false)
  const [kbStatus, setKbStatus] = useState<CallChipStatus>('idle')
  const [docStatus, setDocStatus] = useState<CallChipStatus>('idle')
  const [elapsedMs, setElapsedMs] = useState(0)
  const lastAskRef = useRef<string | undefined>(undefined)
  const timers = useRef<number[]>([])
  const runStartedAt = useRef(0)

  const askCmd = `ask --mode agent --agent 面试助手 "${FLOW_DEMO_QUESTION}"`

  /** 勿用默认 focus：浏览器会 scrollIntoView，浏览落地页时整页会突然跳到终端 */
  const focusInput = () => {
    inputRef.current?.focus({ preventScroll: true })
  }

  const clearTimers = () => {
    timers.current.forEach((id) => window.clearTimeout(id))
    timers.current = []
  }
  const later = (ms: number, fn: () => void) => {
    timers.current.push(window.setTimeout(fn, ms))
  }

  useEffect(() => () => clearTimers(), [])

  useEffect(() => {
    const el = rootRef.current
    if (!el || started) return
    const need = compact ? 0.2 : 0.4
    const obs = new IntersectionObserver(
      ([entry]) => {
        if (entry?.isIntersecting && (entry.intersectionRatio >= need || compact)) {
          setStarted(true)
          obs.disconnect()
        }
      },
      {
        threshold: compact ? [0, 0.15, 0.25, 0.5, 1] : [0, 0.25, 0.4, 0.65, 1],
        rootMargin: compact ? '0px' : '-10% 0px -16% 0px',
      },
    )
    obs.observe(el)
    return () => obs.disconnect()
  }, [started, compact])

  useEffect(() => {
    if (!started) return
    clearTimers()
    setPhase('banner')
    setBannerShown(false)
    setTyped('')
    setStep(-1)
    setDone(false)
    setEggLog([])
    setDraft('ask ')
    setBusy(false)
    setKbStatus('idle')
    setDocStatus('idle')
    setElapsedMs(0)
    runStartedAt.current = 0

    const boot = () => {
      setBannerShown(true)
      setPhase('typing')
      if (reduceMotion) {
        setTyped(askCmd)
        later(120, runPipeline)
        return
      }
      let i = 0
      const tick = () => {
        i += 1
        setTyped(askCmd.slice(0, i))
        if (i < askCmd.length) later(22 + (i % 7 === 0 ? 36 : 0), tick)
        else later(380, runPipeline)
      }
      later(320, tick)
    }

    const runPipeline = () => {
      setPhase('running')
      runStartedAt.current = performance.now()
      const gaps = reduceMotion
        ? [0, 0, 0, 0, 0, 0]
        : [220, 480, 620, 560, 480, 640]
      let acc = 0
      FLOW_STEPS.forEach((_, i) => {
        acc += gaps[i]
        later(acc, () => {
          setStep(i)
          if (i === 2) {
            setKbStatus('running')
            later(reduceMotion ? 0 : 420, () => setKbStatus('done'))
          }
          if (i === 3) {
            setDocStatus('running')
            later(reduceMotion ? 0 : 360, () => setDocStatus('done'))
          }
          if (i === FLOW_STEPS.length - 1) {
            later(reduceMotion ? 0 : 300, () => {
              setElapsedMs(Math.max(100, performance.now() - runStartedAt.current))
              setDone(true)
              setPhase('done')
              /* 不自动 focus，避免页面滚到终端 */
            })
          }
        })
      })
    }

    later(reduceMotion ? 0 : 200, boot)
    return clearTimers
  }, [started, reduceMotion, askCmd])

  useEffect(() => {
    if (phase !== 'running') return
    const id = window.setInterval(() => {
      if (!runStartedAt.current) return
      setElapsedMs(performance.now() - runStartedAt.current)
    }, 120)
    return () => window.clearInterval(id)
  }, [phase])

  useEffect(() => {
    const el = bodyRef.current
    if (!el) return
    /* 只滚终端内部；若浏览器连带滚了窗口，立刻掰回去 */
    const y = window.scrollY
    el.scrollTop = el.scrollHeight
    if (Math.abs(window.scrollY - y) > 1) window.scrollTo(0, y)
  }, [bannerShown, typed, step, done, phase, eggLog, busy, kbStatus, docStatus])

  const submitEgg = () => {
    if (phase !== 'done' || busy) return
    const raw = draft.trim()
    if (!raw) return

    const result = runEggCommand(raw, lastAskRef.current)
    if (result.clear) {
      setEggLog([])
      setDraft('ask ')
      return
    }
    if (result.lastAsk) lastAskRef.current = result.lastAsk

    const stamped: EggLine[] = [{ kind: 'cmd', text: raw }, ...result.lines]
    setBusy(true)
    setDraft('ask ')

    if (reduceMotion) {
      setEggLog((prev) => [...prev, ...stamped])
      setBusy(false)
      later(0, focusInput)
      return
    }

    /* 先回显命令，再短延迟吐出回复，像真在执行 */
    setEggLog((prev) => [...prev, { kind: 'cmd', text: raw }])
    later(180 + Math.random() * 220, () => {
      setEggLog((prev) => [...prev, ...result.lines])
      setBusy(false)
      focusInput()
    })
  }

  const renderEgg = (line: EggLine, key: string) => {
    if (line.kind === 'cmd') {
      return (
        <div key={key} className="lp-term__egg-row">
          <TermPrompt>
            <span className="lp-term__cmd">{line.text}</span>
          </TermPrompt>
        </div>
      )
    }
    if (line.kind === 'art') {
      return (
        <pre key={key} className="lp-term__hero-art lp-term__egg-art">
          {line.text}
        </pre>
      )
    }
    const cls =
      line.kind === 'err' ? 'lp-term__err'
        : line.kind === 'ok' ? 'lp-term__ok'
          : line.kind === 'dim' ? 'lp-term__dim'
            : 'lp-term__out'
    return (
      <div key={key} className={`lp-term__egg-row ${cls}`}>
        {line.kind === 'ok' ? (
          <>
            <span className="lp-term__ok">ok</span>
            <span className="lp-term__muted"> demo/</span>
            <span className="lp-term__accent">local</span>
          </>
        ) : (
          line.text
        )}
      </div>
    )
  }

  return (
    <div
      className={`lp-term${compact ? ' lp-term--compact' : ''}`}
      ref={rootRef}
      data-playing={started ? '' : undefined}
      data-egg={phase === 'done' ? '' : undefined}
      data-term-theme={theme}
      data-compact={compact ? '' : undefined}
      style={{ ['--term-accent' as string]: accent }}
      aria-label="仿终端提问演示"
    >
      <div className="lp-term__chrome" aria-hidden="true">
        <div className="lp-term__traffic">
          <span className="lp-mac-dot lp-mac-dot--close" />
          <span className="lp-mac-dot lp-mac-dot--min" />
          <span className="lp-mac-dot lp-mac-dot--max" />
        </div>
        <em className="lp-term__title">prisdvl — zsh — ask</em>
      </div>

      <div
        className="lp-term__screen"
        onClick={() => {
          if (phase === 'done' && !busy) focusInput()
        }}
      >
        <div className="lp-term__phosphor" aria-hidden />
        <div className="lp-term__scanlines" aria-hidden />
        <div className="lp-term__vignette" aria-hidden />
        <div className="lp-term__scroll" ref={bodyRef}>
          {!started && <span className="lp-term__muted">滚动到此处开始演示…</span>}
          {bannerShown && (
            <div className="lp-term__hero">
              <div className="lp-term__hero-copy">
                <pre className="lp-term__pre lp-term__banner">{PRISD_BANNER}</pre>
                <p className="lp-term__hero-lead">
                  Welcome to Prisdvl nexus shell.
                  <br />
                  Explore knowledge, tools, and ask — all from simple commands.
                </p>
                <p className="lp-term__muted">Try <span className="lp-term__cmd">ask</span> · <span className="lp-term__cmd">help</span> · <span className="lp-term__cmd">banner</span></p>
              </div>
              <pre className="lp-term__hero-art" aria-hidden="true">{PRISDVL_ASCII}</pre>
            </div>
          )}
          {(phase === 'typing' || phase === 'running' || phase === 'done') && (
            <div className="lp-term__egg-row">
              <TermPrompt>
                <span className="lp-term__cmd">{typed}</span>
                {phase === 'typing' && <span className="lp-term__caret" aria-hidden />}
              </TermPrompt>
            </div>
          )}
          {(phase === 'running' || phase === 'done') && (
            <pre className="lp-term__pre lp-term__agent">
              <span className={done ? 'lp-term__ok' : 'lp-term__pulse'}>{done ? '✓' : '●'}</span>
              <span className="lp-term__muted">
                {done
                  ? ` Agent · completed in ${(elapsedMs / 1000).toFixed(1)}s`
                  : ` Agent · ${(elapsedMs / 1000).toFixed(1)}s`}
              </span>
              {'\n'}
              {step >= 0 && (
                <span className="lp-term__line">
                  <span className="lp-term__dim">{'  ⎿  '}</span>
                  {`mode AGENT · 面试助手 · kb=我的 Java 笔记\n`}
                </span>
              )}
              {step >= 1 && (
                <span className={step === 1 && !done ? 'lp-term__line is-active' : 'lp-term__line'}>
                  <span className="lp-term__dim">{'  ⎿  '}</span>
                  {`thought · 正在生成回答\n`}
                </span>
              )}
              {step >= 2 && (
                <span className={step === 2 && !done ? 'lp-term__line is-active' : 'lp-term__line'}>
                  <span className="lp-term__dim">{'  ⎿  '}</span>
                  {`kb.search "B+ 树 索引 页"${kbStatus === 'done' || done ? ' · 422ms' : ' · running'}\n`}
                </span>
              )}
              {step >= 3 && (
                <span className={step === 3 && !done ? 'lp-term__line is-active' : 'lp-term__line'}>
                  <span className="lp-term__dim">{'  ⎿  '}</span>
                  {`doc.read 集合框架.md · 第 3 节${docStatus === 'done' || done ? ' · 358ms' : ' · running'}\n`}
                </span>
              )}
              {step >= 4 && (
                <span className={step === 4 && !done ? 'lp-term__line is-active' : 'lp-term__line'}>
                  <span className="lp-term__dim">{'  ⎿  '}</span>
                  {`context · 命中 6 · 引用 2 · 已联网\n`}
                </span>
              )}
              {step >= 5 && !done && (
                <span className="lp-term__line is-active">
                  <span className="lp-term__dim">{'  ⎿  '}</span>
                  {`stream · writing…\n`}
                </span>
              )}
            </pre>
          )}
          {done && (
            <pre className="lp-term__pre lp-term__answer">
              {'\n'}
              <span className="lp-term__out">{FLOW_DEMO_ANSWER.text}</span>
              {'\n'}
              <span className="lp-term__dim">{'\n  Cited\n'}</span>
              {FLOW_DEMO_CITES.map((c) => (
                <span key={c} className="lp-term__cite">
                  {`  · ${c}\n`}
                </span>
              ))}
              <span className="lp-term__dim">{'\n# ask / help to continue\n'}</span>
            </pre>
          )}
          {eggLog.map((line, i) => renderEgg(line, `egg-${i}-${line.kind}`))}
          {busy && (
            <div className="lp-term__egg-row lp-term__dim">● thinking…</div>
          )}
        </div>
        <div className="lp-term__dock">
          {phase === 'done' && !busy ? (
            <form
              className="lp-term__input-row"
              onSubmit={(e) => {
                e.preventDefault()
                submitEgg()
              }}
            >
              <TermPrompt />
              <input
                ref={inputRef}
                className="lp-term__input"
                value={draft}
                onChange={(e) => setDraft(e.target.value)}
                aria-label="终端输入，以 ask 等指令开头"
                spellCheck={false}
                autoComplete="off"
                placeholder='ask "你的问题"'
              />
            </form>
          ) : (
            <div className="lp-term__dock-idle" aria-hidden>
              <TermPrompt>
                <span className="lp-term__caret" />
              </TermPrompt>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

/** 落地页：工具轨迹示意（对齐 Chat 里 CallChip） */
function ToolTrailDemo({
  reduceMotion,
  accent,
}: {
  reduceMotion: boolean | null
  accent: string
}) {
  const rootRef = useRef<HTMLDivElement>(null)
  const [play, setPlay] = useState(false)
  const [kb, setKb] = useState<CallChipStatus>('idle')
  const [doc, setDoc] = useState<CallChipStatus>('idle')
  const [sandbox, setSandbox] = useState<CallChipStatus>('idle')

  useEffect(() => {
    const el = rootRef.current
    if (!el || play) return
    const obs = new IntersectionObserver(
      ([entry]) => {
        if (entry?.isIntersecting && entry.intersectionRatio >= 0.35) {
          setPlay(true)
          obs.disconnect()
        }
      },
      { threshold: [0.35] },
    )
    obs.observe(el)
    return () => obs.disconnect()
  }, [play])

  useEffect(() => {
    if (!play) return
    const timers: number[] = []
    const t = (ms: number, fn: () => void) => timers.push(window.setTimeout(fn, reduceMotion ? 0 : ms))
    setKb('running')
    t(700, () => setKb('done'))
    t(900, () => setDoc('running'))
    t(1500, () => setDoc('done'))
    t(1700, () => setSandbox('awaiting'))
    t(2600, () => setSandbox('running'))
    t(3400, () => setSandbox('done'))
    return () => timers.forEach((id) => window.clearTimeout(id))
  }, [play, reduceMotion])

  return (
    <div className="lp-trail" ref={rootRef} aria-label="工具调用轨迹演示">
      <div className="lp-trail__head">
        <p className="lp-trail__eyebrow">工具轨迹</p>
        <p className="lp-trail__title">回答里能展开每一次调用</p>
        <p className="lp-trail__sub">与工作台一致：检索 → 读文档 → 高风险沙箱需确认。</p>
      </div>
      <div className="lp-trail__chips">
        <CallChip
          icon="search"
          name="kb.search"
          argument="B+ 树 索引 页"
          status={kb === 'idle' ? (play ? 'running' : 'idle') : kb}
          expectedMs={800}
          color={accent}
          showTimer={!reduceMotion}
        />
        <CallChip
          icon="file"
          name="doc.read"
          argument="集合框架.md · 第 3 节"
          status={doc === 'idle' ? 'idle' : doc}
          expectedMs={700}
          color={accent}
          showTimer={!reduceMotion}
        />
        <CallChip
          icon="terminal"
          name="js.sandbox"
          argument="verify leaf-link walk"
          status={sandbox === 'idle' ? 'idle' : sandbox}
          expectedMs={900}
          color={accent}
          showTimer={!reduceMotion}
        />
      </div>
      <div className="lp-trail__meta" aria-hidden={!play}>
        <span>知识库 1</span>
        <span>命中 6 切片</span>
        <span>引用 2 篇</span>
        <span>已联网</span>
      </div>
    </div>
  )
}

/** 落地页：引用芯片条 */
function CitePreview() {
  return (
    <div className="lp-cites" aria-label="引用出处示意">
      <div className="lp-cites__head">
        <p className="lp-trail__eyebrow">出处</p>
        <p className="lp-trail__title">结论旁挂原文切片</p>
      </div>
      <div className="lp-cites__row">
        {FLOW_DEMO_CITES.map((c) => (
          <button key={c} type="button" className="lp-cites__chip" tabIndex={-1}>
            <FileText size={13} aria-hidden />
            {c}
          </button>
        ))}
        <span className="lp-cites__chip is-meta">已完成 · 引用 2 篇</span>
      </div>
    </div>
  )
}

/** 落地页：本轮上下文胶囊（对齐 Chat ContextChips） */
function ContextPreview() {
  return (
    <div className="lp-ctx" aria-label="本轮上下文示意">
      <div className="lp-cites__head">
        <p className="lp-trail__eyebrow">上下文</p>
        <p className="lp-trail__title">这一轮用了哪些料</p>
        <p className="lp-trail__sub">检索命中与引用篇数一眼可分：是没给到料，还是模型没答好。</p>
      </div>
      <div className="lp-ctx__row">
        <span className="lp-ctx__chip"><Search size={12} aria-hidden />知识库 1</span>
        <span className="lp-ctx__chip">命中 6 切片</span>
        <span className="lp-ctx__chip">引用 2 篇</span>
        <span className="lp-ctx__chip">历史 3 轮</span>
        <span className="lp-ctx__chip">已联网</span>
      </div>
    </div>
  )
}

/** 右下角弹射：朝上回顶，朝下到底；轻点跟图标方向 */
function BackToTopFab({ theme, accent }: { theme: ThemeMode; accent: string }) {
  const [faceDown, setFaceDown] = useState(true)
  const reduceMotion = useReducedMotion()

  useEffect(() => {
    const onScroll = () => {
      const y = window.scrollY
      const max = Math.max(0, document.documentElement.scrollHeight - window.innerHeight)
      /* 靠近顶部 → 朝下到底；离开顶部 → 朝上回顶 */
      setFaceDown(y < Math.min(120, max * 0.08) || (max > 0 && y / max < 0.12))
    }
    onScroll()
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => window.removeEventListener('scroll', onScroll)
  }, [])

  const dark = theme === 'dark'
  const goTop = () => {
    window.scrollTo({ top: 0, behavior: reduceMotion ? 'auto' : 'smooth' })
  }
  const goBottom = () => {
    const top = Math.max(0, document.documentElement.scrollHeight - window.innerHeight)
    window.scrollTo({ top, behavior: reduceMotion ? 'auto' : 'smooth' })
  }

  const onSend = (detail: SlingSendDetail) => {
    /* 拉动：向下拉弓 → 向上飞 → 回顶；向上拉弓 → 向下飞 → 到底 */
    if (detail.pulled) {
      if (detail.uy > 0.2) goTop()
      else if (detail.uy < -0.2) goBottom()
      else if (faceDown) goBottom()
      else goTop()
      return
    }
    if (faceDown) goBottom()
    else goTop()
  }

  return (
    <div className="lp-back-top is-visible" data-face={faceDown ? 'down' : 'up'}>
      <SlingButton
        className="lp-back-top__sling"
        onSend={onSend}
        padColor={dark ? '#f4f4f5' : '#202024'}
        iconColor={dark ? '#18181b' : '#fafafa'}
        accentColor={accent}
        wellColor="transparent"
        bandColor={accent}
        size={40}
        strokeWidth={1.75}
        armAt={28}
        maxPull={88}
        launchSpeed={2400}
        recoil={0.18}
        flight={72}
        particles={reduceMotion ? 0 : 8}
        spread={40}
        axis="vertical"
        tapSends
        ariaLabel={faceDown ? '前往底部' : '回到顶部'}
      >
        {faceDown ? <ArrowDown size={16} strokeWidth={2.2} /> : <ArrowUp size={16} strokeWidth={2.2} />}
      </SlingButton>
    </div>
  )
}

/* 滚动进入视口时的克制动效：淡入 + 轻微上浮；尊重系统减弱动效偏好 */
function Reveal({ children, delay = 0, className }: { children: ReactNode; delay?: number; className?: string }) {
  const reduce = useReducedMotion()
  return (
    <motion.div
      className={className}
      initial={reduce ? false : { opacity: 0, y: 16 }}
      whileInView={reduce ? undefined : { opacity: 1, y: 0 }}
      viewport={{ once: true, margin: '-40px' }}
      transition={{ duration: 0.55, ease: [0.23, 1, 0.32, 1], delay }}
    >
      {children}
    </motion.div>
  )
}


const WAVE_COLOR: Record<PaletteName, { light: string; dark: string }> = {
  prisdvl: { light: '#193FA8', dark: '#3987EC' },
  terracotta: { light: '#7a3a1f', dark: '#ffffff' },
  turquoise: { light: '#14586b', dark: '#eafcff' },
  lime: { light: '#3f6a0d', dark: '#f2ffd6' },
  indigo: { light: '#312e81', dark: '#f3f1ff' },
  ocean: { light: '#305478', dark: '#d8e6f0' },
  violet: { light: '#701a75', dark: '#fff1fc' },
  rose: { light: '#881337', dark: '#fff1f2' },
}
const ACCENT_HEX: Record<PaletteName, { light: string; dark: string }> = {
  prisdvl: { light: '#2457D6', dark: '#2457D6' },
  terracotta: { light: '#b4542a', dark: '#e8a077' },
  turquoise: { light: '#1f7a8c', dark: '#6fd3de' },
  lime: { light: '#5f9c1a', dark: '#b9e26a' },
  indigo: { light: '#4f46e5', dark: '#9b93ff' },
  ocean: { light: '#305478', dark: '#8aacc4' },
  violet: { light: '#c026a8', dark: '#f489e0' },
  rose: { light: '#e11d48', dark: '#fb7185' },
}

interface Props {
  theme: ThemeMode
  palette: PaletteName
  onEnterAuth: () => void
}

export default function LandingPage({ theme, palette, onEnterAuth }: Props) {
  const [aboutOpen, setAboutOpen] = useState(false)
  const reduceMotion = useReducedMotion()
  /* WebGL2 不可用（远程桌面/无 GPU/驱动黑名单）时所有 shader 背景都不挂载，
     退化为纯色底 + 静态头像；探测结果带缓存且立即释放探测 context */
  const [webgl2Ok] = useState(() => isWebGL2Available())

  /* 导航：首页 + 区块锚点；scroll-spy 驱动 RubberSegment */
  const SECTIONS = [
    { id: 'home', label: '首页' },
    { id: 'what', label: '产品' },
    { id: 'flow', label: '提问' },
    { id: 'trace', label: '轨迹' },
    { id: 'use', label: '场景' },
    { id: 'agents', label: 'Agent' },
    { id: 'stack', label: '实现' },
  ]
  const [active, setActive] = useState('home')

  useEffect(() => {
    const nodes = SECTIONS.map((s) => document.getElementById(s.id)).filter(Boolean) as HTMLElement[]
    if (nodes.length === 0) return
    const pick = () => {
      const homeEl = document.getElementById('home')
      /* 仍在首页段内（按文档高度，避免终端增高后误判回首页） */
      if (homeEl && window.scrollY < homeEl.offsetHeight - window.innerHeight * 0.38) {
        setActive('home')
        return
      }
      const probe = window.innerHeight * 0.32
      let best: string | null = null
      let bestDist = Infinity
      for (const n of nodes) {
        if (n.id === 'home') continue
        const d = Math.abs(n.getBoundingClientRect().top - probe)
        if (d < bestDist) { bestDist = d; best = n.id }
      }
      if (best) setActive(best)
    }
    const obs = new IntersectionObserver(pick, {
      rootMargin: '-10% 0px -55% 0px',
      threshold: [0, 0.15, 0.35, 0.6, 1],
    })
    nodes.forEach((n) => obs.observe(n))
    window.addEventListener('scroll', pick, { passive: true })
    pick()
    return () => {
      obs.disconnect()
      window.removeEventListener('scroll', pick)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const jumpTo = (id: string) => {
    setActive(id)
    if (id === 'home') {
      window.scrollTo({ top: 0, behavior: 'smooth' })
      return
    }
    document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }

  const waveColor = WAVE_COLOR[palette][theme]
  const accent = ACCENT_HEX[palette][theme]

  const heroBg = (
    <div style={{ position: 'absolute', inset: 0, zIndex: 0, pointerEvents: 'none' }} aria-hidden="true">
      {webgl2Ok && (
      <PatternWaves
        preset="ocean"
        color={waveColor}
        backgroundColor="transparent"
        opacity={theme === 'dark' ? 0.22 : 0.16}
        fade="edges"
        fadeSize={0.95}
        spacing={18}
        markSize={0.62}
        depth={0.4}
        light={0}
        shine={0.12}
        contrast={0.85}
        speed={0.18}
        scale={1.2}
        direction={30}
        interactive
        cursorSize={70}
        cursorStrength={0.6}
        intro
      />
      )}
    </div>
  )

  const navItems: FloatingNavItem[] = SECTIONS.map((s) => ({
    name: s.label,
    onClick: () => {
      if (s.id === active) return
      jumpTo(s.id)
    },
  }))

  const floatingNav = (
    <FloatingNav
      navItems={navItems}
      logo={<BrandMark />}
      activeName={SECTIONS.find((s) => s.id === active)?.label}
      action={
        <button className="btn btn-primary" type="button" onClick={onEnterAuth}>
          登录 <ArrowRight size={14} />
        </button>
      }
    />
  )

  /*
   * 窄屏专用导航（≤767px）：胶囊导航在手机上放不下，
   * 换成 CardNav —— 汉堡展开三张卡片。卡片链接走 onClick 滚动，
   * 不用 <a href="#section">（hash 路由会把 #section 当成路由，之前踩过这个坑）。
   */
  const cardNavItems: CardNavItem[] = [
    {
      label: '浏览',
      bgColor: 'var(--surface-muted)',
      textColor: 'var(--text)',
      links: SECTIONS.map((s) => ({
        label: s.label,
        ariaLabel: `跳到「${s.label}」`,
        onClick: () => jumpTo(s.id),
      })),
    },
    {
      label: 'Agent',
      bgColor: 'var(--surface-muted)',
      textColor: 'var(--text)',
      links: [
        { label: '我的 Agent', ariaLabel: '查看 Agent 页面演示', onClick: () => jumpTo('agents') },
        { label: '普通对话', ariaLabel: '查看普通对话说明', onClick: () => jumpTo('agents') },
        { label: '知识库问答', ariaLabel: '查看知识库问答说明', onClick: () => jumpTo('agents') },
      ],
    },
    {
      label: '账户',
      bgColor: 'var(--surface-muted)',
      textColor: 'var(--text)',
      links: [
        { label: '登录', ariaLabel: '去登录', onClick: onEnterAuth },
        { label: '注册', ariaLabel: '去注册', onClick: onEnterAuth },
        { label: '关于', ariaLabel: '关于 Prisdvl Nexus', onClick: () => setAboutOpen(true) },
      ],
    },
  ]

  const cardNav = (
    <div className="lp-cardnav">
      <CardNav
        logo={<BrandMark wordmark />}
        items={cardNavItems}
        baseColor="var(--card-bg)"
        menuColor="var(--text)"
        buttonBgColor="var(--btn-bg)"
        buttonTextColor="var(--btn-fg)"
        buttonLabel="登录"
        onButtonClick={onEnterAuth}
      />
    </div>
  )

  /* ---------------- 主体章节（三版共用） ---------------- */
  const sections = (
    <>
      {/* 1 · 产品 */}
      <section className="lp-section" id="what">
        <Reveal className="lp-head">
          <p className="lp-eyebrow">产品</p>
          <h2 className="lp-h2">把你的资料做成可提问的知识库</h2>
          <p className="lp-sub">
            上传文档后可检索、可引用原文；需要时 Agent 会调用工具，并把过程留在回答里。
          </p>
        </Reveal>
        <Reveal delay={0.06}>
          <WhatSchematic reduceMotion={reduceMotion} />
        </Reveal>
        <Reveal delay={0.12}>
          <div className="lp-cols lp-cols-3">
            <article>
              <h3>知识库</h3>
              <p>支持 Markdown / PDF / DOCX。后台完成切片与索引；提问时混合检索，结论旁附原文切片。</p>
            </article>
            <article>
              <h3>工具调用</h3>
              <p>可走知识库检索、计算器、代码沙箱等。每次调用的参数与返回可在回答中展开查看。</p>
            </article>
            <article>
              <h3>多 Agent</h3>
              <p>同一账号可配置多个 Agent，各自绑定知识库、工具与提示词，按任务分开使用。</p>
            </article>
          </div>
        </Reveal>
      </section>

      {/* 2 · 提问流程 */}
      <section className="lp-section" id="flow">
        <Reveal className="lp-head">
          <p className="lp-eyebrow">提问</p>
          <h2 className="lp-h2">一次 Agent 提问大致这样跑完</h2>
          <p className="lp-sub">
            进入本段后终端会按真实链路演示：选 Agent、检索、读文档、汇总上下文，再流式作答并挂上引用。
          </p>
        </Reveal>
        <Reveal delay={0.08}>
          <FlowAskDemo reduceMotion={reduceMotion} accent={accent} theme={theme} />
        </Reveal>
        <Reveal delay={0.14}>
          <ol className="lp-flow-rail" aria-label="提问步骤" style={{ marginTop: 22 }}>
            {FLOW_STEPS.map((s, i) => (
              <li key={s.key} className="lp-flow-rail__step">
                <span className="lp-flow-rail__idx">0{i + 1}</span>
                <div>
                  <strong>{s.label}</strong>
                  <p>{s.hint}</p>
                </div>
              </li>
            ))}
          </ol>
        </Reveal>
      </section>

      {/* 2.4 · 工具轨迹 / 引用 */}
      <section className="lp-section" id="trace">
        <Reveal className="lp-head">
          <p className="lp-eyebrow">轨迹</p>
          <h2 className="lp-h2">工具与出处都留在回答里</h2>
          <p className="lp-sub">
            和工作台同一套 CallChip / 引用芯片：能看见跑了什么、引了哪篇。
          </p>
        </Reveal>
        <Reveal delay={0.08}>
          <ToolTrailDemo reduceMotion={reduceMotion} accent={accent} />
        </Reveal>
        <Reveal delay={0.14}>
          <CitePreview />
        </Reveal>
        <Reveal delay={0.2}>
          <ContextPreview />
        </Reveal>
      </section>

      {/* 2.5 · 场景 */}
      <section className="lp-section" id="use">
        <Reveal className="lp-head">
          <p className="lp-eyebrow">场景</p>
          <h2 className="lp-h2">适合放进自己资料再追问的几类用法</h2>
        </Reveal>
        <Reveal delay={0.1}>
          <div className="lp-cols lp-cols-4">
            <article>
              <h3>课程与面试</h3>
              <p>课件、笔记建好索引后，按自己的材料追问。</p>
            </article>
            <article>
              <h3>代码与报错</h3>
              <p>接上文档和报错记录，边查边算，结论仍可核对出处。</p>
            </article>
            <article>
              <h3>读书笔记</h3>
              <p>多本书笔记入库后，可以跨书对照同一问题。</p>
            </article>
            <article>
              <h3>个人项目</h3>
              <p>把设计与需求绑给专属 Agent，带着上下文继续改。</p>
            </article>
          </div>
        </Reveal>
      </section>

      {/* 3 · Agent */}
      <section className="lp-section" id="agents">
        <Reveal className="lp-head">
          <p className="lp-eyebrow">Agent</p>
          <h2 className="lp-h2">不同任务可以配不同的 Agent</h2>
          <p className="lp-sub">
            下面是「我的 Agent」页示意：点选卡片查看绑定的知识库与工具。登录后使用真实列表。
          </p>
        </Reveal>
        <Reveal delay={0.08}>
          <AgentPageDemo />
        </Reveal>
        <Reveal delay={0.14}>
          <div className="lp-modes" aria-label="当前支持的会话模式" style={{ marginTop: 36 }}>
            <article><h3>普通对话</h3><p>直接使用对话模型；消息与会话由后端保存。</p></article>
            <article><h3>知识库问答</h3><p>选定知识库后再问；未命中资料时会明确说明。</p></article>
            <article><h3>Agent</h3><p>选用已发布的 Agent；自带工具与绑定知识库。</p></article>
          </div>
        </Reveal>
      </section>

      {/* 4 · 实现 */}
      <section className="lp-section" id="stack">
        <Reveal className="lp-head">
          <p className="lp-eyebrow">实现</p>
          <h2 className="lp-h2">后端、检索、前端与部署在同一仓库</h2>
          <p className="lp-sub">
            Spring AI 负责编排，React 做工作台。悬停可暂停浏览组件一览。
          </p>
        </Reveal>
        <Reveal delay={0.08}>
          <div className="lp-logo-loop">
            <LogoLoop
              logos={TECH_LOGOS}
              speed={100}
              direction="left"
              logoHeight={52}
              gap={24}
              hoverSpeed={0}
              scaleOnHover
              fadeOut
              ariaLabel="项目使用的主要技术"
            />
          </div>
        </Reveal>
        <Reveal delay={0.14}>
          <dl className="lp-stack" style={{ marginTop: 32 }}>
            {[
              ['后端', 'Java 21 · Spring Boot 4.1 · Spring AI 2.0 · MyBatis-Plus · Flyway'],
              ['检索', 'PostgreSQL + pgvector · Elasticsearch 9 + IK · RRF 混合检索'],
              ['Agent', '工具注册与执行 · 知识库绑定 · 会话记忆 · 调用轨迹与指标'],
              ['异步与流式', 'RabbitMQ 导入流水线 · Redis · SseEmitter 流式输出'],
              ['前端', 'React 19 · Vite · TypeScript · CSS Tokens'],
              ['部署', 'Docker Compose · Nginx 反代 · 健康检查'],
            ].map(([k, v]) => (
              <div className="lp-stack-row" key={k}>
                <dt>{k}</dt>
                <dd>{v}</dd>
              </div>
            ))}
          </dl>
        </Reveal>
      </section>

      {/* 4.5 · FAQ */}
      <section className="lp-section" id="faq">
        <Reveal className="lp-head">
          <p className="lp-eyebrow">问答</p>
          <h2 className="lp-h2">使用前常见问题</h2>
        </Reveal>
        <Reveal delay={0.1}>
          <div className="lp-faq">
          <details>
            <summary>支持上传哪些文件？</summary>
            <p>Markdown / PDF / DOCX。上传后异步完成切片、向量化与全文索引，完成后即可提问。</p>
          </details>
          <details>
            <summary>和直接用通用大模型聊天有什么区别？</summary>
            <p>会先检索你上传的资料再作答，并附原文切片；没有命中时会说明，而不是编造出处。</p>
          </details>
          <details>
            <summary>Agent 的工具调用安全吗？</summary>
            <p>计算器与代码沙箱在隔离环境执行；入参与返回会写入回答轨迹，可回看。</p>
          </details>
          <details>
            <summary>数据存在哪里？</summary>
            <p>知识库、会话与 Agent 按账号隔离；可用 Docker Compose 部署，Nginx 反代并带健康检查。</p>
          </details>
          </div>
        </Reveal>
      </section>

      {/* 5 · CTA */}
      <section className="lp-section lp-cta">
        <h2 className="lp-h2">登录后进入工作台</h2>
        <p className="lp-sub">知识库、对话与 Agent 均连接当前账号的真实服务。</p>
        <div className="lp-cta-row">
          <button className="btn btn-primary btn-lg" type="button" onClick={onEnterAuth}>
            登录并进入工作台 <ArrowRight size={17} />
          </button>
        </div>
      </section>

      <footer className="lp-foot">
        <BrandMark />
        <p>个人知识库 · 混合检索 · 自定义 Agent</p>
        <button className="lp-about-link" type="button" onClick={() => setAboutOpen(true)}>关于</button>
        <a
          className="lp-foot-github"
          href="https://github.com/Prisdvl/prisdvl-nexus"
          target="_blank"
          rel="noopener noreferrer"
          aria-label="GitHub 仓库"
          title="GitHub"
        >
          <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
            <path
              fill="currentColor"
              d="M12 2C6.475 2 2 6.588 2 12.253c0 4.537 2.865 8.37 6.839 9.723.5.094.683-.222.683-.494 0-.244-.009-.892-.014-1.75-2.782.62-3.37-1.37-3.37-1.37-.454-1.18-1.11-1.494-1.11-1.494-.907-.638.069-.625.069-.625 1.003.072 1.53 1.055 1.53 1.055.892 1.566 2.341 1.114 2.91.852.091-.663.35-1.114.636-1.37-2.22-.259-4.555-1.14-4.555-5.074 0-1.121.39-2.038 1.029-2.756-.103-.26-.447-1.303.098-2.716 0 0 .84-.276 2.75 1.052A9.34 9.34 0 0 1 12 6.844c.85.004 1.705.118 2.504.346 1.909-1.328 2.747-1.052 2.747-1.052.547 1.413.203 2.456.1 2.716.64.718 1.028 1.635 1.028 2.756 0 3.944-2.339 4.812-4.566 5.066.359.317.679.943.679 1.901 0 1.371-.012 2.477-.012 2.814 0 .274.18.593.688.492C19.138 20.62 22 16.788 22 12.253 22 6.588 17.523 2 12 2Z"
            />
          </svg>
        </a>
        <p className="lp-foot-dim">
          <Code2 size={12} style={{ verticalAlign: -2 }} /> Prisdvl Nexus
        </p>
      </footer>
      <Modal
        open={aboutOpen}
        title="关于 Prisdvl Nexus"
        onClose={() => setAboutOpen(false)}
        width={420}
        footer={<button className="btn btn-ghost" type="button" onClick={() => setAboutOpen(false)}>关闭</button>}
      >
        <div className="lp-about">
          <div className="lp-about-orb">
            {webgl2Ok && (
              <CrystalizedBall
                preset="plasma"
                color="#302b2e"
                /* 给的颜色是深墨色：按 light 主题（墨水描线）渲染，
                   容器也固定为浅色底，暗色模式下就是一枚带边框的展品 */
                theme="light"
                size={0.55}
                crackle={0.85}
                fill={0.5}
                interactive
                hoverStrength={0.7}
                paused={!!reduceMotion}
              />
            )}
            <img className="lp-about-avatar" src="/img/Prisdvl.png" alt="Prisdvl 头像" width={52} height={52} />
          </div>
          <div>
            <strong>Prisdvl Nexus</strong>
            <p>个人知识库与对话工作台。支持检索引用、工具调用与自定义 Agent。</p>
          </div>
          <a
            className="lp-about-github"
            href="https://github.com/Prisdvl/prisdvl-nexus"
            target="_blank"
            rel="noopener noreferrer"
            aria-label="在 GitHub 打开 prisdvl-nexus 仓库"
            title="GitHub · Prisdvl/prisdvl-nexus"
          >
            <svg className="lp-about-github__icon" viewBox="0 0 24 24" width="30" height="30" aria-hidden="true">
              <path
                fill="currentColor"
                d="M12 2C6.475 2 2 6.588 2 12.253c0 4.537 2.865 8.37 6.839 9.723.5.094.683-.222.683-.494 0-.244-.009-.892-.014-1.75-2.782.62-3.37-1.37-3.37-1.37-.454-1.18-1.11-1.494-1.11-1.494-.907-.638.069-.625.069-.625 1.003.072 1.53 1.055 1.53 1.055.892 1.566 2.341 1.114 2.91.852.091-.663.35-1.114.636-1.37-2.22-.259-4.555-1.14-4.555-5.074 0-1.121.39-2.038 1.029-2.756-.103-.26-.447-1.303.098-2.716 0 0 .84-.276 2.75 1.052A9.34 9.34 0 0 1 12 6.844c.85.004 1.705.118 2.504.346 1.909-1.328 2.747-1.052 2.747-1.052.547 1.413.203 2.456.1 2.716.64.718 1.028 1.635 1.028 2.756 0 3.944-2.339 4.812-4.566 5.066.359.317.679.943.679 1.901 0 1.371-.012 2.477-.012 2.814 0 .274.18.593.688.492C19.138 20.62 22 16.788 22 12.253 22 6.588 17.523 2 12 2Z"
              />
            </svg>
          </a>
        </div>
      </Modal>
    </>
  )

  return (
    <div className="lp">
      <SkipLink />
      {floatingNav}
        {cardNav}
      <main id="main-content" tabIndex={-1}>
      <section className="lp-hero" id="home">
        {heroBg}
        <div className="lp-option-wheel">
          <OptionWheel
            items={[...HERO_WHEEL_ITEMS]}
            defaultSelected={2}
            textColor={theme === 'dark' ? '#7a879e' : '#6b7280'}
            activeColor={theme === 'dark' ? '#E8EEF8' : '#101522'}
            side="left"
            fontSize={1.85}
            spacing={1.4}
            curve={1}
            tilt={6}
            blur={2}
            fade={0.25}
            smoothing={200}
            inset={28}
            loop={false}
            draggable
          />
        </div>
        <div className="lp-center">
          <h1 className="sr-only">Prisdvl Nexus</h1>
          <div className="lp-tech-title" aria-hidden="true">
            <TechText
              text="Prisdvl Nexus"
              fontWeight={600}
              fontSize={150}
              letterSpacing={-0.05}
              color={theme === 'dark' ? '#E8EEF8' : '#101522'}
              accentColor={accent}
              reveal="letter"
              lineStyle="dashed"
              dashLength={4}
              dashGap={2}
              strokeWidth={1.5}
              specks={15}
              selection={false}
              labels={false}
              draggable={false}
              sweep
              speed={1}
            />
          </div>
          <motion.p
            className="lp-badge"
            initial={reduceMotion ? false : { opacity: 0, y: 8 }}
            animate={reduceMotion ? undefined : { opacity: 1, y: 0 }}
            transition={{ duration: 0.45, ease: [0.23, 1, 0.32, 1], delay: 0.55 }}
          >
            个人知识库工作台
          </motion.p>
          <motion.p
            className="lp-sub"
            initial={reduceMotion ? false : { opacity: 0, y: 14 }}
            animate={reduceMotion ? undefined : { opacity: 1, y: 0 }}
            transition={{ duration: 0.55, ease: [0.23, 1, 0.32, 1], delay: 0.7 }}
          >
            上传文档建成知识库，提问可引用原文；需要时 Agent 可调用工具。登录后即可使用。
          </motion.p>
          <motion.div
            className="lp-cta-row"
            initial={reduceMotion ? false : { opacity: 0, y: 14 }}
            animate={reduceMotion ? undefined : { opacity: 1, y: 0 }}
            transition={{ duration: 0.55, ease: [0.23, 1, 0.32, 1], delay: 0.85 }}
          >
            <button className="btn btn-primary btn-lg" type="button" onClick={onEnterAuth}>
              开始 <ArrowRight size={17} />
            </button>
            <button className="btn btn-ghost btn-lg" type="button" onClick={() => jumpTo('flow')}>了解工作流程</button>
          </motion.div>
        </div>
      </section>

      <div style={{ position: 'relative', zIndex: 1, background: 'var(--bg)' }}>{sections}</div>
      </main>
      <BackToTopFab theme={theme} accent={accent} />
    </div>
  )
}