import { useEffect, useState } from 'react'
import { ArrowRight, Bot, Code2, Database, Layers, Wrench, Zap } from 'lucide-react'
import PatternWaves from '@/bits/PatternWaves/PatternWaves'
import RubberSegment from '@/bits/RubberSegment/RubberSegment'
import SkipLink from '@/components/SkipLink'
import SpecularButton from '@/bits/SpecularButton/SpecularButton'
import CardNav, { type CardNavItem } from '@/bits/CardNav/CardNav'
import '@/styles/landing-cardnav.css'
import { useTokenColor } from '@/lib/palette'
import BrandMark from '@/components/BrandMark'
import type { PaletteName, ThemeMode } from '@/lib/types'


const WAVE_COLOR: Record<PaletteName, { light: string; dark: string }> = {
  terracotta: { light: '#7a3a1f', dark: '#ffffff' },
  turquoise: { light: '#14586b', dark: '#eafcff' },
  lime: { light: '#3f6a0d', dark: '#f2ffd6' },
}
const ACCENT_HEX: Record<PaletteName, { light: string; dark: string }> = {
  terracotta: { light: '#b4542a', dark: '#e8a077' },
  turquoise: { light: '#1f7a8c', dark: '#6fd3de' },
  lime: { light: '#5f9c1a', dark: '#b9e26a' },
}

/* 三个 Agent：入口页用来说明「多 Agent 切换」 */
const AGENT_PRESETS = [
  {
    name: '面试助手',
    model: 'deepseek-v4-flash',
    kb: '我的 Java 笔记 · 面经合集',
    tools: ['knowledge_search', 'calculator'],
    line: '结合你的笔记与面经追问，先给结论再补原理，答不上来会指出盲区。',
  },
  {
    name: '代码审查员',
    model: 'glm-5.3',
    kb: '我的 Java 笔记',
    tools: ['knowledge_search', 'code_runner'],
    line: '贴代码即审：并发安全、异常处理、命名与边界条件，逐条给修改建议。',
  },
  {
    name: '简历润色师',
    model: 'kimi-k2.6',
    kb: '软技能与项目',
    tools: ['knowledge_search'],
    line: '把项目经历改写成 STAR 句式，量化结果，对齐目标岗位的关键词。',
  },
]

interface Props {
  theme: ThemeMode
  palette: PaletteName
  onEnterAuth: () => void
}

export default function LandingPage({ theme, palette, onEnterAuth }: Props) {
  const [agentIdx, setAgentIdx] = useState(0)

  /* 导航：区块锚点 + 滚动高亮（scroll-spy）+ 顶部阅读进度 */
  const SECTIONS = [
    { id: 'what', label: '它能做什么' },
    { id: 'flow', label: '怎么工作' },
    { id: 'agents', label: 'Agent' },
    { id: 'stack', label: '技术栈' },
  ]
  const [active, setActive] = useState('what')
  /*
   * 主 CTA 用 SpecularButton（边缘高光跟随鼠标）。
   * 注意：lineColor / baseColor 会被 ogl 的 Color 解析，**必须传具体色值**，
   * 传 var(--x) 会解析失败；tint / textColor 走 CSS，本身可以留 token。
   */
  const ctaFill = useTokenColor('--btn-bg')
  const ctaText = useTokenColor('--btn-fg')
  const ctaLine = useTokenColor('--primary')
  const ctaBase = useTokenColor('--border-strong')

  useEffect(() => {
    const nodes = SECTIONS.map((s) => document.getElementById(s.id)).filter(Boolean) as HTMLElement[]
    if (nodes.length === 0) return
    /* 用「离视口顶部最近的区块」判定当前所在段，比纯 intersectionRatio 更符合直觉 */
    const obs = new IntersectionObserver(
      () => {
        const probe = window.innerHeight * 0.32
        let best: string | null = null
        let bestDist = Infinity
        for (const n of nodes) {
          const d = Math.abs(n.getBoundingClientRect().top - probe)
          if (d < bestDist) { bestDist = d; best = n.id }
        }
        if (best) setActive(best)
      },
      { rootMargin: '-10% 0px -55% 0px', threshold: [0, 0.15, 0.35, 0.6, 1] },
    )
    nodes.forEach((n) => obs.observe(n))
    return () => obs.disconnect()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const jumpTo = (id: string) => {
    setActive(id)
    document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }

  const waveColor = WAVE_COLOR[palette][theme]
  const accent = ACCENT_HEX[palette][theme]
  const agent = AGENT_PRESETS[agentIdx]

  const heroBg = (
    <div style={{ position: 'absolute', inset: 0, zIndex: 0, pointerEvents: 'none' }} aria-hidden="true">
      <PatternWaves
        preset="ocean"
        color={waveColor}
        backgroundColor="transparent"
        opacity={theme === 'dark' ? 0.5 : 0.4}
        fade="edges"
        fadeSize={0.95}
        spacing={18}
        markSize={0.62}
        depth={0.4}
        light={0}
        shine={0.55}
        contrast={0.85}
        speed={0.3}
        scale={1.2}
        direction={30}
        interactive
        cursorSize={70}
        cursorStrength={0.6}
        intro
      />
    </div>
  )

  const navPill = (
    <>
      <header className="lp-pill">
      <BrandMark />
      <nav className="lp-pill-nav" aria-label="页面导航">
        <RubberSegment
          items={SECTIONS.map((s) => s.label)}
          value={SECTIONS.find((s) => s.id === active)?.label ?? SECTIONS[0].label}
          animateExternal
          onChange={(label) => {
            const hit = SECTIONS.find((s) => s.label === label)
            if (hit) jumpTo(hit.id)
          }}
          size="md"
          radius={999}
          inset={3}
          equalSlots
          trackColor="transparent"
          thumbColor="var(--primary-container)"
          textColor="var(--text-2)"
          activeTextColor="var(--primary-text)"
          aria-label="页面导航"
        />
      </nav>
        <button className="btn btn-primary" type="button" onClick={onEnterAuth}>
          登录 <ArrowRight size={14} />
        </button>
      </header>
    </>
  )

  /*
   * 窄屏专用导航（≤767px）：胶囊导航在手机上放不下，
   * 换成 CardNav —— 汉堡展开三张卡片。卡片链接走 onClick 滚动，
   * 不用 <a href="#section">（hash 路由会把 #section 当成路由，之前踩过这个坑）。
   */
  const cardNavItems: CardNavItem[] = [
    {
      label: '产品',
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
        { label: '面试助手', ariaLabel: '查看面试助手', onClick: () => jumpTo('agents') },
        { label: 'Java 代码审查员', ariaLabel: '查看 Java 代码审查员', onClick: () => jumpTo('agents') },
        { label: '简历润色师', ariaLabel: '查看简历润色师', onClick: () => jumpTo('agents') },
      ],
    },
    {
      label: '账户',
      bgColor: 'var(--surface-muted)',
      textColor: 'var(--text)',
      links: [
        { label: '登录', ariaLabel: '去登录', onClick: onEnterAuth },
        { label: '注册', ariaLabel: '去注册', onClick: onEnterAuth },
        { label: '关于这个项目', ariaLabel: '关于这个项目', onClick: () => jumpTo('stack') },
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
      {/* 1 · 它能做什么 */}
      <section className="lp-section" id="what">
        <p className="lp-eyebrow">它能做什么</p>
        <h2 className="lp-h2">三件事：查你的资料、调工具、换 Agent</h2>
        <div className="lp-caps">
          <article>
            <span className="lp-cap-ic"><Database size={17} /></span>
            <h3>绑定知识库</h3>
            <p>上传 Markdown / PDF / DOCX，后台异步切片、向量化并建全文索引。提问时用向量 + BM25 + RRF 混合检索，回答附带引用切片，可回溯原文。</p>
          </article>
          <article>
            <span className="lp-cap-ic"><Wrench size={17} /></span>
            <h3>调用工具</h3>
            <p>Agent 可调用知识库检索、计算器、代码沙箱等工具。每次调用都会在回答里留下轨迹：调了什么、参数是什么、返回了什么。</p>
          </article>
          <article>
            <span className="lp-cap-ic"><Bot size={17} /></span>
            <h3>切换 Agent</h3>
            <p>同一个问题换一个 Agent，就是换一套知识库、工具与提示词。面试陪练、代码审查、简历润色，各自带着自己的上下文干活。</p>
          </article>
        </div>
      </section>

      {/* 2 · 怎么工作（示意） */}
      <section className="lp-section" id="flow">
        <p className="lp-eyebrow">怎么工作</p>
        <h2 className="lp-h2">一次提问，Agent 会走完这四步</h2>

        <ol className="lp-flow">
          {[
            ['理解问题', '拆解意图，判断是否需要查资料或算数'],
            ['检索知识库', '混合检索命中切片，带上引用信息'],
            ['调用工具', '按需调用计算器 / 代码沙箱，记录调用轨迹'],
            ['组织回答', '流式输出，附引用与可展开的调用过程'],
          ].map(([t, d], i) => (
            <li key={t}>
              <span className="lp-flow-num">0{i + 1}</span>
              <div>
                <h3>{t}</h3>
                <p>{d}</p>
              </div>
            </li>
          ))}
        </ol>

        {/* 抽象对话示意（非真实截图） */}
        <div className="lp-mock" aria-label="Agent 对话示意">
          <div className="lp-mock-bar"><span /><span /><span /></div>
          <div className="lp-mock-thread">
            <div className="lp-mock-user">Redis 持久化该选 RDB 还是 AOF？</div>
            <div className="lp-mock-trace">
              <span className="lp-mock-step done"><Zap size={12} /> 检索「Redis 源码阅读」 3 个切片</span>
              <span className="lp-mock-step done"><Zap size={12} /> 调用 calculator：1GB / 每秒写 5k 条</span>
              <span className="lp-mock-step"><Zap size={12} /> 组织回答…</span>
            </div>
            <div className="lp-mock-bot">
              两者取舍取决于你能接受多少数据丢失：<br />
              RDB 是周期性快照，恢复快、文件小，但两次快照之间的写入会丢；<br />
              AOF 记录每条写命令，最多丢 1 秒（everysec），代价是文件更大、恢复更慢。<br />
              生产常见做法是两者同时开启，用 AOF 保证可靠性、RDB 做冷备与快速恢复。
              <div className="lp-mock-cites">
                <span>Redis 源码阅读 · ## 持久化</span>
                <span>面经合集 · 缓存篇</span>
              </div>
            </div>
          </div>
          <p className="lp-mock-note">界面示意（非真实截图）· 展示检索命中、工具调用与引用</p>
        </div>
      </section>

      {/* 3 · 多 Agent 切换 */}
      <section className="lp-section" id="agents">
        <p className="lp-eyebrow">Agent</p>
        <h2 className="lp-h2">换 Agent，就是换一套知识与工具</h2>

        <div className="lp-agents">
          <div className="lp-agents-pick">
            <RubberSegment
              items={AGENT_PRESETS.map((a) => a.name)}
              defaultValue={AGENT_PRESETS[0].name}
              onChange={(v) => setAgentIdx(AGENT_PRESETS.findIndex((a) => a.name === v))}
              size="lg"
              trackColor="var(--surface-muted)"
              thumbColor="var(--primary-strong)"
              textColor="var(--text-2)"
              activeTextColor="var(--on-primary-strong)"
              aria-label="选择 Agent"
            />
            <p className="lp-agents-line">{agent.line}</p>
          </div>

          <dl className="lp-agents-spec">
            <div><dt>模型</dt><dd>{agent.model}</dd></div>
            <div><dt>绑定知识库</dt><dd>{agent.kb}</dd></div>
            <div>
              <dt>可用工具</dt>
              <dd className="lp-tool-row">
                {agent.tools.map((t) => <span className="chip" key={t}>{t}</span>)}
              </dd>
            </div>
          </dl>
        </div>
      </section>

      {/* 4 · 技术栈 */}
      <section className="lp-section" id="stack">
        <p className="lp-eyebrow">技术栈</p>
        <h2 className="lp-h2">全栈自建，每一层都能讲清楚</h2>
        <dl className="lp-stack">
          {[
            ['后端', 'Java 21 · Spring Boot 4.1 · Spring AI 2.0 · MyBatis-Plus · Flyway'],
            ['检索', 'PostgreSQL + pgvector · Elasticsearch 9 + IK · RRF 混合检索'],
            ['Agent', '工具注册与执行 · 知识库绑定 · 会话记忆 · 调用轨迹与指标'],
            ['异步与流式', 'RabbitMQ 导入流水线 · Redis · SseEmitter 流式输出'],
            ['前端', 'React 19 · Vite · TypeScript · Tailwind 4 · Zustand'],
            ['部署', 'Docker Compose 八服务 · Nginx 反代 · 健康检查'],
          ].map(([k, v]) => (
            <div className="lp-stack-row" key={k}>
              <dt>{k}</dt>
              <dd>{v}</dd>
            </div>
          ))}
        </dl>
      </section>

      {/* 5 · CTA */}
      <section className="lp-section lp-cta">
        <h2 className="lp-h2">直接进工作台看</h2>
        <p className="lp-sub">演示账号已备好：知识库、问答、对话与 Agent 都是可点的。</p>
        <div className="lp-cta-row">
          <SpecularButton
            className="cta-specular"
            size="lg"
            radius={12}
            tint={ctaFill}
            tintOpacity={1}
            textColor={ctaText}
            lineColor={ctaLine}
            baseColor={ctaBase}
            intensity={1}
            shineSize={12}
            shineFade={46}
            thickness={1.2}
            proximity={280}
            followMouse
            onClick={onEnterAuth}
          >
            进入登录页 <ArrowRight size={17} />
          </SpecularButton>
          <button className="btn btn-ghost btn-lg" type="button" onClick={() => jumpTo('flow')}>再看一遍流程</button>
        </div>
      </section>

      <footer className="lp-foot">
        <BrandMark />
        <p>个人知识库 · 混合检索 · 工具调用 · 自定义 Agent</p>
        <p className="lp-foot-dim">
          <Code2 size={12} style={{ verticalAlign: -2 }} /> 校招核心项目
        </p>
      </footer>
    </>
  )

  return (
    <div className="lp">
      <SkipLink />
      {navPill}
        {cardNav}
      <main id="main-content" tabIndex={-1}>
      <section className="lp-hero">
        {heroBg}
        <div className="lp-center">
          <span className="lp-badge"><Bot size={13} /> Java 21 · Spring AI 2.0 · Agent + RAG</span>
          <h1 className="lp-h1">会查资料、会调工具<br />的 AI Agent 平台</h1>
          <p className="lp-sub">
            导入文档即自动切片与向量化；Agent 绑定知识库与工具干活，<br />
            回答带引用、调用有轨迹，每一步都能解释。
          </p>
          <div className="lp-cta-row">
            <SpecularButton
              className="cta-specular"
              size="lg"
              radius={12}
              tint={ctaFill}
              tintOpacity={1}
              textColor={ctaText}
              lineColor={ctaLine}
              baseColor={ctaBase}
              intensity={1}
              shineSize={12}
              shineFade={46}
              thickness={1.2}
              proximity={280}
              followMouse
              onClick={onEnterAuth}
            >
              进入工作台 <ArrowRight size={17} />
            </SpecularButton>
            <button className="btn btn-ghost btn-lg" type="button" onClick={() => jumpTo('flow')}>看它怎么工作</button>
          </div>
          <div className="lp-hero-stats">
            <span><Wrench size={13} /> 3 类工具</span>
            <span><Database size={13} /> 向量 + BM25 混合检索</span>
            <span><Bot size={13} /> 多 Agent 可切换</span>
          </div>
        </div>
      </section>

      <div style={{ position: 'relative', zIndex: 1, background: 'var(--bg)' }}>{sections}</div>
      </main>
    </div>
  )
}