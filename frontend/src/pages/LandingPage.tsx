import { Suspense, lazy, useEffect, useRef, useState, type ReactNode } from 'react'
import { AnimatePresence, LayoutGroup, motion, useReducedMotion } from 'motion/react'
import { ArrowDown, ArrowRight, ArrowUp, Bot, Code2, FileText, Plus, Search, Wrench } from 'lucide-react'
/* 两个 WebGL 装饰件按需加载：hero 背景与「关于」弹层里的水晶球都不是首屏必要内容，
 * 且都已由 webgl2Ok 门控（无 WebGL2 时本来就不挂载），所以延迟加载不新增降级路径；
 * 它们与 WavesBg 共用 ogl，静态导入会把整个 ogl 拉进入口包。 */
const PatternWaves = lazy(() => import('@/bits/PatternWaves/PatternWaves'))
import SkipLink from '@/components/SkipLink'
import TechText from '@/bits/TechText/TechText'
const CrystalizedBall = lazy(() => import('@/bits/CrystalizedBall/CrystalizedBall'))
import SlingButton, { type SlingSendDetail } from '@/bits/SlingButton/SlingButton'
import OptionWheel from '@/bits/OptionWheel/OptionWheel'
import CallChip, { type CallChipStatus } from '@/bits/CallChip/CallChip'
import LatticeLoader from '@/bits/LatticeLoader/LatticeLoader'
import Modal from '@/components/Modal'
import CardNav, { type CardNavItem } from '@/bits/CardNav/CardNav'
import { FloatingNav, type FloatingNavItem } from '@/bits/FloatingNav/FloatingNav'
import '@/bits/FloatingNav/FloatingNav.css'
import '@/styles/landing-cardnav.css'
import BrandMark from '@/components/BrandMark'
import LangToggle from '@/components/LangToggle'
import { InteractiveHoverButton } from '@/registry/magicui/interactive-hover-button'
import LogoLoop, { type LogoItem } from '@/bits/LogoLoop/LogoLoop'
import { AGENTS, type AgentItem } from '@/data'
import { landingCopy, type LandingCopy } from '@/lib/landingCopy'
import { useLocale } from '@/lib/locale'
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

const FLOW_DEMO_QUESTION = 'B+ 树为什么适合做数据库索引？'
const FLOW_DEMO_ANSWER = {
  tool: 'kb.search → doc.read',
  cite: '集合框架.md · MySQL 索引原理.pdf',
  text: '叶子节点串成有序链表，范围扫描与磁盘页对齐都更省；面试助手会先检索再引用原文。',
}
const FLOW_DEMO_CITES = ['集合框架.md', 'MySQL 索引原理.pdf'] as const

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

function AgentPageDemo({ t }: { t: LandingCopy }) {
  const [active, setActive] = useState<AgentItem>(AGENTS[0])

  return (
    <div className="lp-agent-demo" aria-label={t.agentDemo.title}>
      <div className="lp-agent-demo__chrome" aria-hidden="true">
        <span className="lp-mac-dot lp-mac-dot--close" />
        <span className="lp-mac-dot lp-mac-dot--min" />
        <span className="lp-mac-dot lp-mac-dot--max" />
        <em>app / agents</em>
      </div>
      <div className="lp-agent-demo__body">
        <header className="lp-agent-demo__head">
          <div>
            <p className="lp-agent-demo__eyebrow">{t.agentDemo.eyebrow}</p>
            <h3>{t.agentDemo.title}</h3>
            <p>{t.sections.agents.sub}</p>
          </div>
          <span className="lp-agent-demo__new" aria-hidden="true"><Plus size={14} /> {t.agentDemo.new}</span>
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
                  {agent.tools.slice(0, 2).map((tool) => <span key={tool}>{tool}</span>)}
                </div>
                <small>{t.agentDemo.bound} {agent.kb}</small>
              </button>
            )
          })}
        </div>
      </div>
    </div>
  )
}

/** 产品段：三条能力的轻量示意（连接段放在卡片间隙，避免被裁切） */
function WhatSchematic({ reduceMotion, t }: { reduceMotion: boolean | null; t: LandingCopy }) {
  const items = [
    { icon: <FileText size={16} />, label: t.schematic.ingest.t, sub: t.schematic.ingest.s },
    { icon: <Search size={16} />, label: t.schematic.search.t, sub: t.schematic.search.s },
    { icon: <Wrench size={16} />, label: t.schematic.trail.t, sub: t.schematic.trail.s },
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
  onProgress,
  scrollHint,
}: {
  reduceMotion: boolean | null
  accent: string
  theme: ThemeMode
  /** 首页旁栏：收紧高度、隐藏 ASCII 与步骤轨 */
  compact?: boolean
  /** 同步下方六步卡片：step 0..5，done 表示整链跑完 */
  onProgress?: (step: number, done: boolean) => void
  scrollHint: string
}) {
  const t = landingCopy(useLocale().locale)
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
    onProgress?.(step, done)
  }, [step, done, onProgress])

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
      const stepCount = 6
      Array.from({ length: stepCount }, (_, i) => {
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
          if (i === stepCount - 1) {
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
      aria-label={t.termAria}
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
          {!started && <span className="lp-term__muted">{scrollHint}</span>}
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
                aria-label={t.termInputAria}
                spellCheck={false}
                autoComplete="off"
                placeholder={t.termInputPh}
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

type TrailStep = 'idle' | 'running' | 'done'

const FLOW_SPRING = { type: 'spring' as const, stiffness: 280, damping: 30, mass: 0.7 }

function traceChips(t: LandingCopy) {
  return [
    { key: 'kb' as const, label: t.chips.kb, icon: <Search size={12} aria-hidden /> },
    { key: 'hits' as const, label: t.chips.hits },
    { key: 'cites' as const, label: t.chips.cites },
    { key: 'hist' as const, label: t.chips.hist },
    { key: 'net' as const, label: t.chips.net },
  ]
}

/** 六步轨：与终端 step 同步高亮，layoutId 让当前步的光斑在卡片间滑动 */
function FlowRail({
  activeStep,
  done,
  reduceMotion,
  steps,
}: {
  activeStep: number
  done: boolean
  reduceMotion: boolean | null
  steps: LandingCopy['flowSteps']
}) {
  const soft = reduceMotion ? { duration: 0 } : FLOW_SPRING
  const progress = activeStep < 0 ? 0 : done ? 1 : (activeStep + 1) / steps.length

  return (
    <LayoutGroup id="lp-flow-rail">
      <div className="lp-flow-rail-wrap">
        <ol className="lp-flow-rail" aria-label={steps.map((s) => s.label).join(' → ')}>
          {steps.map((s, i) => {
            const isActive = !done && activeStep === i
            const isDone = done || activeStep > i
            const dim = activeStep < 0
            return (
              <motion.li
                key={s.key}
                className="lp-flow-rail__step"
                data-active={isActive ? '' : undefined}
                data-done={isDone && !isActive ? '' : undefined}
                initial={reduceMotion ? false : { opacity: 0.4, y: 10 }}
                animate={{
                  opacity: dim ? 0.5 : isActive ? 1 : isDone ? 0.88 : 0.42,
                  y: 0,
                  scale: isActive ? 1.02 : 1,
                }}
                transition={{ ...soft, delay: reduceMotion ? 0 : Math.min(i * 0.04, 0.2) }}
              >
                {isActive && (
                  <motion.span
                    className="lp-flow-rail__glow"
                    layoutId="lp-flow-active-glow"
                    transition={soft}
                    aria-hidden
                  />
                )}
                <span className="lp-flow-rail__idx">0{i + 1}</span>
                <div>
                  <strong>{s.label}</strong>
                  <p>{s.hint}</p>
                </div>
              </motion.li>
            )
          })}
        </ol>
        <div className="lp-flow-rail__track" aria-hidden>
          <motion.div
            className="lp-flow-rail__fill"
            initial={false}
            animate={{ scaleX: progress }}
            transition={soft}
          />
        </div>
      </div>
    </LayoutGroup>
  )
}

/** 落地页：工具轨迹终端示意（LatticeLoader + 工具行，对齐工作台思考态） */
function ToolTrailDemo({
  reduceMotion,
  accent,
  theme,
  chipHome,
  onDone,
  t,
}: {
  reduceMotion: boolean | null
  accent: string
  theme: ThemeMode
  /** meta 芯片是否还住在本卡（迁到上下文卡后改为 false） */
  chipHome: boolean
  onDone?: () => void
  t: LandingCopy
}) {
  const chips = traceChips(t)
  const rootRef = useRef<HTMLDivElement>(null)
  const [play, setPlay] = useState(false)
  const [kb, setKb] = useState<TrailStep>('idle')
  const [doc, setDoc] = useState<TrailStep>('idle')
  const [sandbox, setSandbox] = useState<TrailStep>('idle')
  const allDone = kb === 'done' && doc === 'done' && sandbox === 'done'
  const soft = reduceMotion ? { duration: 0 } : FLOW_SPRING

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
    const later = (ms: number, fn: () => void) => timers.push(window.setTimeout(fn, reduceMotion ? 0 : ms))
    setKb('running')
    later(700, () => setKb('done'))
    later(900, () => setDoc('running'))
    later(1500, () => setDoc('done'))
    later(1700, () => setSandbox('running'))
    later(2500, () => setSandbox('done'))
    return () => timers.forEach((id) => window.clearTimeout(id))
  }, [play, reduceMotion])

  const onDoneRef = useRef(onDone)
  onDoneRef.current = onDone
  const doneFired = useRef(false)
  useEffect(() => {
    if (!allDone || doneFired.current) return
    doneFired.current = true
    onDoneRef.current?.()
  }, [allDone])

  const mark = (s: TrailStep) => (
    s === 'done'
      ? <span className="lp-term__ok">✓</span>
      : s === 'running'
        ? <span className="lp-term__pulse">●</span>
        : <span className="lp-term__dim">·</span>
  )

  return (
    <motion.div
      className="lp-trail"
      ref={rootRef}
      aria-label={t.trail.aria}
      layout={!reduceMotion}
      transition={soft}
    >
      <div className="lp-trail__head">
        <p className="lp-trail__eyebrow">{t.trail.eyebrow}</p>
        <p className="lp-trail__title">{t.trail.title}</p>
        <p className="lp-trail__sub">{t.trail.sub}</p>
      </div>

      <div
        className="lp-term lp-term--compact lp-trail__term"
        data-term-theme={theme}
        data-playing={play ? '' : undefined}
        style={{ ['--term-accent' as string]: accent }}
      >
        <div className="lp-term__chrome" aria-hidden="true">
          <div className="lp-term__traffic">
            <span className="lp-mac-dot lp-mac-dot--close" />
            <span className="lp-mac-dot lp-mac-dot--min" />
            <span className="lp-mac-dot lp-mac-dot--max" />
          </div>
          <em className="lp-term__title">prisdvl — tools — trail</em>
        </div>

        <div className="lp-term__screen">
          <div className="lp-term__phosphor" aria-hidden />
          <div className="lp-term__scanlines" aria-hidden />
          <div className="lp-term__vignette" aria-hidden />
          <div className="lp-term__scroll lp-trail__screen">
            {!play && <span className="lp-term__muted">{t.scrollHint}</span>}

            {play && (
              <div className="lp-trail__think">
                <LatticeLoader
                  status={allDone ? 'done' : 'working'}
                  label="Thinking"
                  doneLabel="Done in"
                  errorLabel="Failed after"
                  pattern="orbit"
                  grid={3}
                  shape="round"
                  color={accent}
                  doneColor="#22c55e"
                  errorColor="#ef4444"
                  cellSize={6}
                  gap={2}
                  fontSize={14}
                  step={90}
                  idleOpacity={0.15}
                  glow={false}
                  showTimer={!reduceMotion}
                />
              </div>
            )}

            {play && (
              <pre className="lp-term__pre lp-term__agent lp-trail__log">
                <span className="lp-term__line">
                  <span className="lp-term__dim">{'  ⎿  '}</span>
                  {`mode AGENT · 面试助手 · kb=我的 Java 笔记\n`}
                </span>
                {(kb !== 'idle') && (
                  <span className={kb === 'running' ? 'lp-term__line is-active' : 'lp-term__line'}>
                    <span className="lp-term__dim">{'  ⎿  '}</span>
                    {mark(kb)}
                    {` kb.search "B+ 树 索引 页"${kb === 'done' ? ' · 706ms' : ' · running'}\n`}
                  </span>
                )}
                {(doc !== 'idle') && (
                  <span className={doc === 'running' ? 'lp-term__line is-active' : 'lp-term__line'}>
                    <span className="lp-term__dim">{'  ⎿  '}</span>
                    {mark(doc)}
                    {` doc.read 集合框架.md · 第 3 节${doc === 'done' ? ' · 597ms' : ' · running'}\n`}
                  </span>
                )}
                {(sandbox !== 'idle') && (
                  <span className={sandbox === 'running' ? 'lp-term__line is-active' : 'lp-term__line'}>
                    <span className="lp-term__dim">{'  ⎿  '}</span>
                    {mark(sandbox)}
                    {` js.sandbox verify leaf-link walk${sandbox === 'done' ? ' · 799ms' : ' · running'}\n`}
                  </span>
                )}
                {allDone && (
                  <span className="lp-term__line">
                    <span className="lp-term__dim">{'  ⎿  '}</span>
                    <span className="lp-term__ok">✓</span>
                    {` answer ready · cites=2\n`}
                  </span>
                )}
              </pre>
            )}
          </div>
        </div>
      </div>

      <div className="lp-trail__meta" aria-hidden={!allDone || !chipHome}>
        <AnimatePresence>
          {allDone && chipHome && chips.filter((c) => c.key !== 'hist').map((c, i) => (
            <motion.span
              key={c.key}
              className="lp-trace-chip"
              layoutId={`lp-trace-chip-${c.key}`}
              initial={reduceMotion ? false : { opacity: 0, y: 8, scale: 0.92 }}
              animate={{ opacity: 1, y: 0, scale: 1 }}
              exit={reduceMotion ? undefined : { opacity: 0, scale: 0.9 }}
              transition={{ ...soft, delay: reduceMotion ? 0 : i * 0.05 }}
            >
              {c.label}
            </motion.span>
          ))}
        </AnimatePresence>
      </div>
    </motion.div>
  )
}

/** 落地页：引用芯片条 */
function CitePreview({ reduceMotion, visible, t }: { reduceMotion: boolean | null; visible: boolean; t: LandingCopy }) {
  const soft = reduceMotion ? { duration: 0 } : FLOW_SPRING
  if (!visible) return null
  return (
    <motion.div
      className="lp-cites"
      aria-label={t.cites.aria}
      initial={reduceMotion ? false : { opacity: 0, y: 18 }}
      animate={{ opacity: 1, y: 0 }}
      transition={soft}
      layout={!reduceMotion}
    >
      <div className="lp-cites__head">
        <p className="lp-trail__eyebrow">{t.cites.eyebrow}</p>
        <p className="lp-trail__title">{t.cites.title}</p>
      </div>
      <div className="lp-cites__row">
        {FLOW_DEMO_CITES.map((c, i) => (
          <motion.button
            key={c}
            type="button"
            className="lp-cites__chip"
            tabIndex={-1}
            initial={reduceMotion ? false : { opacity: 0, x: -10 }}
            animate={{ opacity: 1, x: 0 }}
            transition={{ ...soft, delay: reduceMotion ? 0 : 0.08 + i * 0.06 }}
          >
            <FileText size={13} aria-hidden />
            {c}
          </motion.button>
        ))}
        <motion.span
          className="lp-cites__chip is-meta"
          initial={reduceMotion ? false : { opacity: 0 }}
          animate={{ opacity: 1 }}
          transition={{ ...soft, delay: reduceMotion ? 0 : 0.22 }}
        >
          {t.cites.done}
        </motion.span>
      </div>
    </motion.div>
  )
}

/** 落地页：本轮上下文胶囊（对齐 Chat ContextChips）；承接轨迹 meta 的 layoutId 芯片 */
function ContextPreview({ reduceMotion, visible, t }: { reduceMotion: boolean | null; visible: boolean; t: LandingCopy }) {
  const soft = reduceMotion ? { duration: 0 } : FLOW_SPRING
  const chips = traceChips(t)
  if (!visible) return null
  return (
    <motion.div
      className="lp-ctx"
      aria-label={t.ctx.aria}
      initial={reduceMotion ? false : { opacity: 0, y: 18 }}
      animate={{ opacity: 1, y: 0 }}
      transition={soft}
      layout={!reduceMotion}
    >
      <div className="lp-cites__head">
        <p className="lp-trail__eyebrow">{t.ctx.eyebrow}</p>
        <p className="lp-trail__title">{t.ctx.title}</p>
        <p className="lp-trail__sub">{t.ctx.sub}</p>
      </div>
      <div className="lp-ctx__row">
        {chips.map((c, i) => (
          <motion.span
            key={c.key}
            className="lp-ctx__chip lp-trace-chip"
            layoutId={c.key === 'hist' ? undefined : `lp-trace-chip-${c.key}`}
            initial={c.key === 'hist' && !reduceMotion ? { opacity: 0, y: 8 } : false}
            animate={{ opacity: 1, y: 0 }}
            transition={{ ...soft, delay: reduceMotion ? 0 : (c.key === 'hist' ? 0.18 : i * 0.03) }}
          >
            {'icon' in c ? c.icon : null}
            {c.label}
          </motion.span>
        ))}
      </div>
    </motion.div>
  )
}

/** 轨迹三段：工具跑完 → 出处浮现 → 上下文承接同一批芯片 */
function TraceStory({
  reduceMotion,
  accent,
  theme,
  t,
}: {
  reduceMotion: boolean | null
  accent: string
  theme: ThemeMode
  t: LandingCopy
}) {
  const [trailDone, setTrailDone] = useState(false)
  const [showCites, setShowCites] = useState(false)
  const [showCtx, setShowCtx] = useState(false)

  useEffect(() => {
    if (!trailDone) return
    const a = window.setTimeout(() => setShowCites(true), reduceMotion ? 0 : 380)
    const b = window.setTimeout(() => setShowCtx(true), reduceMotion ? 0 : 980)
    return () => { window.clearTimeout(a); window.clearTimeout(b) }
  }, [trailDone, reduceMotion])

  return (
    <LayoutGroup id="lp-trace-story">
      <div className="lp-trace-story">
        <ToolTrailDemo
          reduceMotion={reduceMotion}
          accent={accent}
          theme={theme}
          chipHome={!showCtx}
          onDone={() => setTrailDone(true)}
          t={t}
        />
        <CitePreview reduceMotion={reduceMotion} visible={showCites} t={t} />
        <ContextPreview reduceMotion={reduceMotion} visible={showCtx} t={t} />
      </div>
    </LayoutGroup>
  )
}

/** 右下角弹射：朝上回顶，朝下到底；轻点跟图标方向 */
function BackToTopFab({ theme, accent }: { theme: ThemeMode; accent: string }) {
  const t = landingCopy(useLocale().locale)
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
        ariaLabel={faceDown ? t.toBottomAria : t.toTopAria}
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
  const { locale } = useLocale()
  const t = landingCopy(locale)
  /* 提问六步轨：跟终端演示同步 */
  const [flowStep, setFlowStep] = useState(-1)
  const [flowDone, setFlowDone] = useState(false)
  /* WebGL2 不可用（远程桌面/无 GPU/驱动黑名单）时所有 shader 背景都不挂载，
     退化为纯色底 + 静态头像；探测结果带缓存且立即释放探测 context */
  const [webgl2Ok] = useState(() => isWebGL2Available())

  /* 导航 4 栏：标签随语言整页切换，避免中英混杂 */
  const SECTIONS = [
    { id: 'home', label: t.nav.home },
    { id: 'what', label: t.nav.what },
    { id: 'flow', label: t.nav.flow },
    { id: 'agents', label: t.nav.agents },
    { id: 'stack', label: t.nav.stack },
  ] as const
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
      <Suspense fallback={null}>
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
      </Suspense>
      )}
    </div>
  )

  const navItems: FloatingNavItem[] = SECTIONS.filter((s) => s.id !== 'home').map((s) => ({
    name: s.label,
    onClick: () => {
      if (s.id === active) return
      jumpTo(s.id)
    },
  }))

  /* 语言入口与侧栏共用 LangToggle；落地页只保留小图标，文字占比尽量小 */
  const langBtn = <LangToggle variant="icon" className="lp-lang" size={16} />

  const floatingNav = (
    <FloatingNav
      navItems={navItems}
      logo={<BrandMark />}
      activeName={SECTIONS.find((s) => s.id === active)?.label}
      navAria={t.navAria}
      segmentAria={t.sectionNavAria}
      action={
        <div className="lp-nav-actions">
          {langBtn}
          <button className="btn btn-primary" type="button" onClick={onEnterAuth}>
            {t.login} <ArrowRight size={14} />
          </button>
        </div>
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
      label: t.cardNav.browse,
      bgColor: 'var(--surface-muted)',
      textColor: 'var(--text)',
      links: SECTIONS.map((s) => ({
        label: s.label,
        ariaLabel: s.label,
        onClick: () => jumpTo(s.id),
      })),
    },
    {
      label: t.cardNav.agent,
      bgColor: 'var(--surface-muted)',
      textColor: 'var(--text)',
      links: [
        { label: t.cardNav.myAgents, ariaLabel: t.cardNav.myAgents, onClick: () => jumpTo('agents') },
        { label: t.cardNav.plain, ariaLabel: t.cardNav.plain, onClick: () => jumpTo('agents') },
        { label: t.cardNav.rag, ariaLabel: t.cardNav.rag, onClick: () => jumpTo('agents') },
      ],
    },
    {
      label: t.cardNav.account,
      bgColor: 'var(--surface-muted)',
      textColor: 'var(--text)',
      links: [
        { label: t.login, ariaLabel: t.login, onClick: onEnterAuth },
        { label: t.register, ariaLabel: t.register, onClick: onEnterAuth },
        { label: t.about, ariaLabel: t.about, onClick: () => setAboutOpen(true) },
      ],
    },
  ]

  const cardNav = (
    <div className="lp-cardnav">
      <div className="lp-cardnav__lang">{langBtn}</div>
      <CardNav
        logo={<BrandMark wordmark />}
        items={cardNavItems}
        baseColor="var(--card-bg)"
        menuColor="var(--text)"
        buttonBgColor="var(--btn-bg)"
        buttonTextColor="var(--btn-fg)"
        buttonLabel={t.login}
        onButtonClick={onEnterAuth}
      />
    </div>
  )

  /* ---------------- 主体章节（三版共用） ---------------- */
  const sections = (
    <>
      {/* 1 · 产品（含场景） */}
      <section className="lp-section" id="what">
        <Reveal className="lp-head">
          <p className="lp-eyebrow">{t.sections.what.eyebrow}</p>
          <h2 className="lp-h2">{t.sections.what.title}</h2>
          <p className="lp-sub">{t.sections.what.sub}</p>
        </Reveal>
        <Reveal delay={0.06}>
          <WhatSchematic reduceMotion={reduceMotion} t={t} />
        </Reveal>
        <Reveal delay={0.12}>
          <div className="lp-cols lp-cols-3">
            <article>
              <h3>{t.whatCols.kb.t}</h3>
              <p>{t.whatCols.kb.p}</p>
            </article>
            <article>
              <h3>{t.whatCols.tools.t}</h3>
              <p>{t.whatCols.tools.p}</p>
            </article>
            <article>
              <h3>{t.whatCols.agents.t}</h3>
              <p>{t.whatCols.agents.p}</p>
            </article>
          </div>
        </Reveal>
        <Reveal delay={0.16}>
          <div className="lp-cols lp-cols-4" style={{ marginTop: 28 }} aria-label={t.useCasesAria}>
            <article>
              <h3>{t.useCases.interview.t}</h3>
              <p>{t.useCases.interview.p}</p>
            </article>
            <article>
              <h3>{t.useCases.code.t}</h3>
              <p>{t.useCases.code.p}</p>
            </article>
            <article>
              <h3>{t.useCases.notes.t}</h3>
              <p>{t.useCases.notes.p}</p>
            </article>
            <article>
              <h3>{t.useCases.project.t}</h3>
              <p>{t.useCases.project.p}</p>
            </article>
          </div>
        </Reveal>
      </section>

      {/* 2 · 提问（含轨迹） */}
      <section className="lp-section" id="flow">
        <Reveal className="lp-head">
          <p className="lp-eyebrow">{t.sections.flow.eyebrow}</p>
          <h2 className="lp-h2">{t.sections.flow.title}</h2>
          <p className="lp-sub">{t.sections.flow.sub}</p>
        </Reveal>
        <Reveal delay={0.08}>
          <FlowAskDemo
            reduceMotion={reduceMotion}
            accent={accent}
            theme={theme}
            scrollHint={t.scrollHint}
            onProgress={(step, done) => {
              setFlowStep(step)
              setFlowDone(done)
            }}
          />
        </Reveal>
        <Reveal delay={0.14}>
          <div style={{ marginTop: 22 }}>
            <FlowRail activeStep={flowStep} done={flowDone} reduceMotion={reduceMotion} steps={t.flowSteps} />
          </div>
        </Reveal>
        <Reveal delay={0.18}>
          <div style={{ marginTop: 36 }}>
            <p className="lp-trail__eyebrow" style={{ marginBottom: 8 }}>{t.trailLabel}</p>
            <TraceStory reduceMotion={reduceMotion} accent={accent} theme={theme} t={t} />
          </div>
        </Reveal>
      </section>

      {/* 3 · 智能体 */}
      <section className="lp-section" id="agents">
        <Reveal className="lp-head">
          <p className="lp-eyebrow">{t.sections.agents.eyebrow}</p>
          <h2 className="lp-h2">{t.sections.agents.title}</h2>
          <p className="lp-sub">{t.sections.agents.sub}</p>
        </Reveal>
        <Reveal delay={0.08}>
          <AgentPageDemo t={t} />
        </Reveal>
        <Reveal delay={0.14}>
          <div className="lp-modes" aria-label={t.modesAria} style={{ marginTop: 36 }}>
            <article><h3>{t.modes.plain.t}</h3><p>{t.modes.plain.p}</p></article>
            <article><h3>{t.modes.rag.t}</h3><p>{t.modes.rag.p}</p></article>
            <article><h3>{t.modes.agent.t}</h3><p>{t.modes.agent.p}</p></article>
          </div>
        </Reveal>
      </section>

      {/* 4 · 实现（含 FAQ） */}
      <section className="lp-section" id="stack">
        <Reveal className="lp-head">
          <p className="lp-eyebrow">{t.sections.stack.eyebrow}</p>
          <h2 className="lp-h2">{t.sections.stack.title}</h2>
          <p className="lp-sub">{t.sections.stack.sub}</p>
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
              ariaLabel={t.sections.stack.title}
            />
          </div>
        </Reveal>
        <Reveal delay={0.14}>
          <dl className="lp-stack" style={{ marginTop: 32 }}>
            {t.stackRows.map(([k, v]) => (
              <div className="lp-stack-row" key={k}>
                <dt>{k}</dt>
                <dd>{v}</dd>
              </div>
            ))}
          </dl>
        </Reveal>
        <Reveal delay={0.18}>
          <div style={{ marginTop: 40 }}>
            <p className="lp-trail__eyebrow" style={{ marginBottom: 12 }}>{t.faqTitle}</p>
            <div className="lp-faq">
              {t.faq.map((item) => (
                <details key={item.q}>
                  <summary>{item.q}</summary>
                  <p>{item.a}</p>
                </details>
              ))}
            </div>
          </div>
        </Reveal>
      </section>

      {/* 5 · CTA */}
      <section className="lp-section lp-cta">
        <h2 className="lp-h2">{t.cta.title}</h2>
        <p className="lp-sub">{t.cta.sub}</p>
        <div className="lp-cta-row">
          <button className="btn btn-primary btn-lg" type="button" onClick={onEnterAuth}>
            {t.cta.btn} <ArrowRight size={17} />
          </button>
        </div>
      </section>

      <footer className="lp-foot">
        <BrandMark />
        <p>{t.foot}</p>
        <button className="lp-about-link" type="button" onClick={() => setAboutOpen(true)}>{t.about}</button>
        <a
          className="lp-foot-github"
          href="https://github.com/Prisdvl/prisdvl-nexus"
          target="_blank"
          rel="noopener noreferrer"
          aria-label={t.githubRepoAria}
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
        title={`${t.about} Prisdvl Nexus`}
        onClose={() => setAboutOpen(false)}
        width={420}
        footer={<button className="btn btn-ghost" type="button" onClick={() => setAboutOpen(false)}>{locale === 'en' ? 'Close' : '关闭'}</button>}
      >
        <div className="lp-about">
          <div className="lp-about-orb">
            {webgl2Ok && (
              <Suspense fallback={null}>
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
              </Suspense>
            )}
            <img className="lp-about-avatar" src="/img/Prisdvl.png" alt={t.avatarAlt} width={52} height={52} />
          </div>
          <div>
            <strong>Prisdvl Nexus</strong>
            <p>{t.aboutBody}</p>
          </div>
          <a
            className="lp-about-github"
            href="https://github.com/Prisdvl/prisdvl-nexus"
            target="_blank"
            rel="noopener noreferrer"
            aria-label={t.githubOpenAria}
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
            items={[...t.heroWheel]}
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
            {t.heroBadge}
          </motion.p>
          <motion.p
            className="lp-sub"
            initial={reduceMotion ? false : { opacity: 0, y: 14 }}
            animate={reduceMotion ? undefined : { opacity: 1, y: 0 }}
            transition={{ duration: 0.55, ease: [0.23, 1, 0.32, 1], delay: 0.7 }}
          >
            {t.heroSub}
          </motion.p>
          <motion.div
            className="lp-cta-row"
            initial={reduceMotion ? false : { opacity: 0, y: 14 }}
            animate={reduceMotion ? undefined : { opacity: 1, y: 0 }}
            transition={{ duration: 0.55, ease: [0.23, 1, 0.32, 1], delay: 0.85 }}
          >
            <InteractiveHoverButton onClick={onEnterAuth}>
              {t.start}
            </InteractiveHoverButton>
            <button className="btn btn-ghost btn-lg" type="button" onClick={() => jumpTo('flow')}>{t.seeFlow}</button>
          </motion.div>
        </div>
      </section>

      <div style={{ position: 'relative', zIndex: 1, background: 'var(--bg)' }}>{sections}</div>
      </main>
      <BackToTopFab theme={theme} accent={accent} />
    </div>
  )
}
