'use client'

/**
 * ParticleTitle —— 标题化成一团「光雾里的尘埃」再聚回清晰文字。
 * ------------------------------------------------------------
 * 与原组件的三处差异（都是为落到本仓库而做）：
 *
 * 1. **无 Tailwind**：原子类改写为 `particle-title.css` 里的语义类，
 *    容器高度等尺寸交给上下文 CSS（对话页把它压在输入框上方）。
 * 2. **跟随主题 / 配色**：原组件内置 LIGHT / DARK 两套写死的调色板，
 *    这里改为从设计令牌取色（`--text` / `--bg` / `--primary*`），
 *    并沿用 `lib/palette.ts` 的「1×1 canvas 归一化」办法，
 *    因为 canvas 既不解析 CSS 变量也不支持 color-mix。
 *    标题的字号 / 字重 / 字距也直接读 `<h2>` 的 computed style，
 *    不另立一套字体常量 —— 否则画布文字与页面 h2 会慢慢漂移。
 * 3. **只循环一次**（`repeats` 默认 1）：进入新对话先给出清晰标题，
 *    短暂停留后散开一次、再聚拢一次，随后**停在清晰文字上不再动**。
 *    刷新或重新打开新对话会重新挂载组件，动画随之再放一次。
 *
 * 其余行为保留：离屏 / 标签页隐藏时暂停，`prefers-reduced-motion` 时静默降级为纯文字。
 */

import { useEffect, useMemo, useRef } from 'react'
import { useReducedMotion } from 'motion/react'
import { useTheme } from '@/lib/theme'
import type { ThemeMode } from '@/lib/types'
import './particle-title.css'

/* ------------------------------------------------------------------ */
/* 工具函数                                                            */
/* ------------------------------------------------------------------ */

type RGB = [number, number, number]
type Box = { x: number; y: number; w: number; h: number }
type Field = { cx: number; cy: number; rx: number; ry: number }
type Mode = 'smoke' | 'vortex' | 'sand' | 'burst' | 'sweep' | 'absorb'
type BlobSpec = { rgb: RGB; a: number; fx: number; fy: number; fw: number; fh: number }
type Palette = { ink: RGB; page: RGB; blobs: BlobSpec[] }
type Cell = {
  rx: number; ry: number; rw: number; rh: number; hx: number; hy: number; nx: number; ny: number
  r1: number; r2: number; r3: number; size: number; fx: number; fy: number; fc: RGB
  lt: number; bend: number; pa: number
}
type Phase = 'text' | 'dissolving' | 'field' | 'gathering'

const MODES: Mode[] = ['smoke', 'vortex', 'sand', 'burst', 'sweep', 'absorb']
const TIMING = { field: 1200, travel: 3600 }

/** 光雾色块：位置固定，颜色与不透明度按明暗各取一套 */
const BLOB_GEOM = [
  { fx: 0.04, fy: 0.08, fw: 0.62, fh: 0.78 },
  { fx: 0.44, fy: 0.22, fw: 0.54, fh: 0.7 },
  { fx: 0.3, fy: 0.44, fw: 0.44, fh: 0.56 },
]
const BLOB_SPECS: Record<ThemeMode, Array<{ token: string; a: number }>> = {
  /* 亮色：底色浅，光雾要淡、要亮，靠"提亮"制造柔光 */
  light: [
    { token: '--primary', a: 0.2 },
    { token: '--primary-container', a: 0.85 },
    { token: '--primary-strong', a: 0.16 },
  ],
  /* 暗色：底色本就深，光雾用高饱和强调色 + 一档亮色高光 */
  dark: [
    { token: '--primary', a: 0.45 },
    { token: '--primary-text', a: 0.24 },
    { token: '--primary-strong', a: 0.34 },
  ],
}

function hexToRgb(hex: string): RGB {
  const m = /^#?([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(hex.trim())
  if (!m) return [128, 128, 128]
  return [parseInt(m[1], 16), parseInt(m[2], 16), parseInt(m[3], 16)]
}

/**
 * 把任意 CSS 颜色串归一化成具体 hex。
 * canvas 不认 `var(--x)` / `color-mix()`，必须先落到实数（同 lib/palette.ts 的做法）。
 */
function concreteColor(css: string): string {
  const cv = document.createElement('canvas')
  cv.width = 1
  cv.height = 1
  const ctx = cv.getContext('2d', { willReadFrequently: true })
  if (!ctx) return '#888888'
  ctx.clearRect(0, 0, 1, 1)
  ctx.fillStyle = 'rgba(0,0,0,0)'
  ctx.fillStyle = css
  ctx.fillRect(0, 0, 1, 1)
  const [r, g, b] = ctx.getImageData(0, 0, 1, 1).data
  const h = (v: number) => v.toString(16).padStart(2, '0')
  return `#${h(r)}${h(g)}${h(b)}`
}

function readToken(cs: CSSStyleDeclaration, token: string, fallback: string): RGB {
  return hexToRgb(concreteColor(cs.getPropertyValue(token).trim() || fallback))
}

function createRng(seed: number): () => number {
  let a = seed >>> 0
  return () => {
    a = (a + 0x6d2b79f5) >>> 0
    let t = a
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

const clamp = (t: number) => (t < 0 ? 0 : t > 1 ? 1 : t)
const mix = (a: number, b: number, t: number) => a + (b - a) * t
const smooth = (t: number) => {
  const c = clamp(t)
  return c * c * (3 - 2 * c)
}
const ease = (t: number) => {
  const c = clamp(t)
  return c < 0.5 ? 4 * c * c * c : 1 - Math.pow(-2 * c + 2, 3) / 2
}

/** 洗牌袋：保证六种散开方式轮完一轮之前不重复，相邻两次也不同 */
function createModeBag(seed: number): () => Mode {
  const r = createRng(seed)
  let bag: Mode[] = []
  let last: Mode | null = null
  return () => {
    if (!bag.length) {
      bag = MODES.slice()
      for (let i = bag.length - 1; i > 0; i--) {
        const j = Math.floor(r() * (i + 1))
        ;[bag[i], bag[j]] = [bag[j], bag[i]]
      }
      if (bag[bag.length - 1] === last) [bag[0], bag[bag.length - 1]] = [bag[bag.length - 1], bag[0]]
    }
    last = bag.pop() as Mode
    return last
  }
}

function blobStrength(b: BlobSpec, A: Box, x: number, y: number) {
  const bw = b.fw * A.w
  const bh = b.fh * A.h
  const cx = A.x + b.fx * A.w + bw / 2
  const cy = A.y + b.fy * A.h + bh / 2
  return Math.max(0, 1 - Math.hypot((x - cx) / (bw / 2), (y - cy) / (bh / 2))) * b.a
}

/** 边缘遮罩：中心实、外圈渐隐，光雾不会在容器边缘"切一刀" */
function rimMask(A: Box, x: number, y: number) {
  const r = Math.hypot((x - (A.x + A.w / 2)) / (A.w / 2), (y - (A.y + A.h / 2)) / (A.h / 2))
  return r < 0.55 ? 1 : r >= 1 ? 0 : 1 - (r - 0.55) / 0.45
}

function glowAt(pal: Palette, A: Box, x: number, y: number): { c: RGB; s: number } {
  let [r, g, b] = pal.page
  let vis = 0
  const m = rimMask(A, x, y)
  for (const bl of pal.blobs) {
    const s = blobStrength(bl, A, x, y) * m
    r = mix(r, bl.rgb[0], s)
    g = mix(g, bl.rgb[1], s)
    b = mix(b, bl.rgb[2], s)
    vis = Math.max(vis, s)
  }
  return { c: [r, g, b], s: vis }
}

/** 采样一个「落在光雾里」的落点：多试几个，取可见度最高的 */
function sampleFieldPoint(pal: Palette, rand: () => number, f: Field, A: Box) {
  let best: { x: number; y: number; c: RGB; s: number } | null = null
  for (let k = 0; k < 4; k++) {
    const a = rand() * 6.2832
    const q = Math.pow(rand(), 0.6)
    const x = f.cx + Math.cos(a) * f.rx * q
    const y = f.cy + Math.sin(a) * f.ry * q
    const g = glowAt(pal, A, x, y)
    if (!best || g.s > best.s) best = { x, y, c: g.c, s: g.s }
  }
  return best as { x: number; y: number; c: RGB; s: number }
}

/* ------------------------------------------------------------------ */
/* 画布引擎                                                            */
/* ------------------------------------------------------------------ */

/**
 * 每种散开方式给出「延迟 + 弧度」。
 * `scale` 把弧度按容器高度等比缩小 —— 原组件的常量是按 360px 高调的，
 * 压到 180px 的对话页里若照搬，尘粒会直接飞出画布被裁掉。
 */
function plan(mode: Mode, p: Cell, W: number, H: number, scale: number) {
  switch (mode) {
    case 'smoke':
      return { delay: (1 - clamp(p.ny)) * 0.35 + p.r3 * 0.25, bend: (p.r1 - 0.5) * 150 * scale }
    case 'vortex':
      return {
        delay: (Math.hypot(p.hx - W / 2, p.hy - H / 2) / Math.max(1, W)) * 0.9 + p.r3 * 0.15,
        bend: (p.r2 > 0.5 ? 1 : -1) * (90 + p.r1 * 140) * scale,
      }
    case 'sand':
      return { delay: p.nx * 0.6 + p.r3 * 0.18, bend: (p.r1 - 0.5) * 60 * scale }
    case 'burst':
      return { delay: p.r3 * 0.1, bend: (p.r2 - 0.5) * 26 * scale }
    case 'sweep':
      return { delay: (1 - p.nx) * 0.6 + p.r3 * 0.18, bend: -(60 + p.r1 * 90) * scale }
    default:
      return { delay: p.r1 * 0.55, bend: (p.r2 - 0.5) * 100 * scale }
  }
}

/** 走一条带弧度的路径（弧高由 bend 控制），返回当前点与归一化进度 */
function bowAt(a: [number, number], b: [number, number], bend: number, t: number) {
  const e = ease(t)
  const dx = b[0] - a[0]
  const dy = b[1] - a[1]
  const L = Math.hypot(dx, dy) || 1
  const bow = Math.sin(e * Math.PI) * bend
  return [
    mix(a[0], b[0], e) + (-dy / L) * bow,
    mix(a[1], b[1], e) + (dx / L) * bow,
    e,
  ] as const
}

const rgbStr = (a: RGB, b: RGB, t: number) => {
  const c = (i: number) => Math.max(0, Math.min(255, Math.round(mix(a[i], b[i], t))))
  return `rgb(${c(0)},${c(1)},${c(2)})`
}

type EngineOpts = {
  /** 停留几毫秒后开始散开（进入新对话时给用户先读一眼标题的时间） */
  holdMs: number
  /** 循环次数：1 = 只散开/聚拢一次，随后静止在清晰文字上 */
  repeats: number
  /** 真实 <h2>：画布从中读取字体与颜色，保证两边不漂移 */
  titleEl: HTMLElement
}
type Engine = { setActive: (on: boolean) => void; resize: () => void; destroy: () => void }

/** 文字排版参数取自 <h2> 的计算样式：主题里改字号/字重会自动跟着变 */
type FontSpec = { family: string; size: number; weight: string; spacing: number }

function readFont(el: HTMLElement): FontSpec {
  const cs = getComputedStyle(el)
  const size = parseFloat(cs.fontSize) || 28
  const spacing = cs.letterSpacing === 'normal' ? 0 : parseFloat(cs.letterSpacing) || 0
  return { family: cs.fontFamily || 'sans-serif', size, weight: cs.fontWeight || '600', spacing }
}

function createEngine(cv: HTMLCanvasElement, text: string, pal: Palette, opts: EngineOpts): Engine | null {
  const ctx = cv.getContext('2d')
  if (!ctx) return null
  const textCv = document.createElement('canvas')
  const nextMode = createModeBag((Date.now() & 0xffff) ^ 0x5a5a)
  let W = 0
  let H = 0
  let dpr = 1
  let scale = 1
  let field: Field = { cx: 0, cy: 0, rx: 0, ry: 0 }
  let box: Box = { x: 0, y: 0, w: 0, h: 0 }
  let parts: Cell[] = []
  let phase: Phase = 'text'
  let mode: Mode = nextMode()
  let phaseStart = 0
  let raf = 0
  let timer = 0
  /** 已经跑完的「散开 → 聚拢」轮数：达到 repeats 后就停在清晰文字上 */
  let cycles = 0
  let active = true
  let destroyed = false

  function reroll() {
    const r = createRng((Math.random() * 1e9) | 0)
    for (const p of parts) {
      const q = sampleFieldPoint(pal, r, field, box)
      p.fx = q.x
      p.fy = q.y
      p.fc = q.c
    }
  }

  /* ---------- 栅格化：把标题切成有限个方块，每个方块是一粒"尘" ---------- */
  function build() {
    W = cv.clientWidth
    H = cv.clientHeight
    dpr = Math.min(2, window.devicePixelRatio || 1)
    cv.width = Math.max(1, Math.round(W * dpr))
    cv.height = Math.max(1, Math.round(H * dpr))
    textCv.width = cv.width
    textCv.height = cv.height
    ctx!.setTransform(1, 0, 0, 1, 0, 0)

    const f = readFont(opts.titleEl)
    /* 窄容器里把字号收一点，避免长句被画布裁掉（只在必要时生效） */
    let fs = f.size
    const o = textCv.getContext('2d') as CanvasRenderingContext2D
    const setFont = (size: number) => {
      o.font = `${f.weight} ${size}px ${f.family}`
      if ('letterSpacing' in o) {
        ;(o as CanvasRenderingContext2D & { letterSpacing: string }).letterSpacing = `${f.spacing}px`
      }
    }
    setFont(fs)
    for (let i = 0; i < 3 && o.measureText(text).width > W - 24 && fs > 14; i++) {
      fs = fs * Math.max(0.6, (W - 24) / o.measureText(text).width)
      setFont(fs)
    }

    const ty = H / 2
    box = { x: 0, y: 0, w: W, h: H }
    /* 光雾椭圆：横向铺开、纵向克制，让标题上下都留出呼吸位 */
    field = { cx: W / 2, cy: H / 2, rx: Math.min(560, W * 0.4), ry: Math.min(260, H * 0.3) }
    scale = Math.min(1, H / 320)
    o.setTransform(dpr, 0, 0, dpr, 0, 0)
    o.clearRect(0, 0, W, H)
    o.textAlign = 'center'
    o.textBaseline = 'middle'
    const m = o.measureText(text)
    const tx = W / 2 - (m.actualBoundingBoxRight - m.actualBoundingBoxLeft) / 2
    o.fillStyle = `rgba(${pal.ink.join(',')},.95)`
    o.fillText(text, tx, ty)

    const tw = m.width
    const X0 = Math.max(0, Math.floor((tx - tw / 2 - 6) * dpr))
    const X1 = Math.min(cv.width, Math.ceil((tx + tw / 2 + 6) * dpr))
    const Y0 = Math.max(0, Math.floor((ty - fs) * dpr))
    const Y1 = Math.min(cv.height, Math.ceil((ty + fs) * dpr))
    const d = o.getImageData(0, 0, cv.width, cv.height).data

    /* 自适应格距：先细后粗，直到格子数落在性能预算内 */
    let cs = Math.max(2, Math.round(3 * dpr))
    let pts: number[][]
    for (;;) {
      pts = []
      for (let y = Y0; y < Y1; y += cs) {
        for (let x = X0; x < X1; x += cs) {
          const w = Math.min(cs, cv.width - x)
          const h = Math.min(cs, cv.height - y)
          let hit = false
          for (let yy = y; yy < y + h && !hit; yy++) {
            for (let xx = x; xx < x + w; xx++) {
              if (d[(yy * cv.width + xx) * 4 + 3] > 0) {
                hit = true
                break
              }
            }
          }
          if (hit) pts.push([x, y, w, h])
        }
      }
      if (pts.length <= 2400) break
      cs++
    }

    let minX = 1e9
    let maxX = -1e9
    for (const p of pts) {
      p[4] = (p[0] + p[2] / 2) / dpr
      p[5] = (p[1] + p[3] / 2) / dpr
      if (p[4] < minX) minX = p[4]
      if (p[4] > maxX) maxX = p[4]
    }

    /* 固定种子：同一次挂载里粒子粗细分布稳定，不随 resize 抖动 */
    const r = createRng(7)
    parts = pts.map((p) => {
      const u = r()
      const size = u < 0.62 ? 0.9 + r() * 0.8 : u < 0.9 ? 1.8 + r() * 1.4 : 3.4 + r() * 3.2
      return {
        rx: p[0], ry: p[1], rw: p[2], rh: p[3], hx: p[4], hy: p[5],
        nx: (p[4] - minX) / Math.max(1, maxX - minX),
        ny: (p[5] - (ty - fs / 2)) / fs,
        r1: r(), r2: r(), r3: r(), size, fx: 0, fy: 0, fc: [0, 0, 0] as RGB, lt: 0, bend: 0, pa: 0,
      }
    })
    ctx!.setTransform(dpr, 0, 0, dpr, 0, 0)
    reroll()
  }

  function drawRealText() {
    const c = ctx as CanvasRenderingContext2D
    c.setTransform(1, 0, 0, 1, 0, 0)
    c.clearRect(0, 0, cv.width, cv.height)
    c.globalAlpha = 1
    c.drawImage(textCv, 0, 0)
    c.setTransform(dpr, 0, 0, dpr, 0, 0)
  }

  function frame(now: number) {
    raf = 0
    const c = ctx as CanvasRenderingContext2D
    const prog = clamp((now - phaseStart) / TIMING.travel)
    const out = phase === 'dissolving'
    c.setTransform(1, 0, 0, 1, 0, 0)
    c.clearRect(0, 0, cv.width, cv.height)

    /* 第一层：文字碎片本身（散开时原地淡出，聚拢时原地淡入） */
    for (const p of parts) {
      const pl = plan(mode, p, W, H, scale)
      const lt = clamp((prog - pl.delay * 0.55) / 0.45)
      p.lt = lt
      p.bend = pl.bend
      p.pa = out ? 1 - smooth(lt / 0.18) : smooth((lt - 0.82) / 0.18)
      if (p.pa <= 0.002) continue
      c.globalAlpha = p.pa
      c.drawImage(textCv, p.rx, p.ry, p.rw, p.rh, p.rx, p.ry, p.rw, p.rh)
    }

    /* 第二层：沿弧线飞行的尘粒（颜色从"墨水"过渡到光雾色） */
    c.setTransform(dpr, 0, 0, dpr, 0, 0)
    for (const p of parts) {
      const lt = p.lt
      if (lt <= 0 || lt >= 1) continue
      const a: [number, number] = out ? [p.hx, p.hy] : [p.fx, p.fy]
      const b: [number, number] = out ? [p.fx, p.fy] : [p.hx, p.hy]
      const q = bowAt(a, b, out ? p.bend : -p.bend, lt)
      const e = out ? q[2] : 1 - q[2]
      let alpha = 0.1 + 0.9 * (1 - smooth((e - 0.45) / 0.55))
      alpha *= out ? 1 - smooth((lt - 0.85) / 0.15) : smooth(lt / 0.15) * (1 - p.pa)
      if (alpha <= 0.004) continue
      c.globalAlpha = Math.min(1, alpha)
      c.fillStyle = rgbStr(pal.ink, p.fc, smooth(e / 0.6))
      c.beginPath()
      c.arc(q[0], q[1], (p.size * mix(1, 1.7, smooth(e))) / 2, 0, 6.2832)
      c.fill()
    }
    c.globalAlpha = 1
    if (prog < 1) raf = requestAnimationFrame(frame)
    else advance()
  }

  function stopWork() {
    clearTimeout(timer)
    timer = 0
    cancelAnimationFrame(raf)
    raf = 0
  }

  function enter(next: Phase) {
    phase = next
    phaseStart = performance.now()
    stopWork()
    if (!active || destroyed) return
    if (next === 'text') {
      drawRealText()
      /* 循环次数用尽：停在清晰文字上，不再排下一次 */
      if (cycles >= opts.repeats) return
      timer = window.setTimeout(advance, opts.holdMs)
    } else if (next === 'field') {
      ctx!.setTransform(1, 0, 0, 1, 0, 0)
      ctx!.clearRect(0, 0, cv.width, cv.height)
      ctx!.setTransform(dpr, 0, 0, dpr, 0, 0)
      timer = window.setTimeout(advance, TIMING.field)
    } else {
      if (next === 'dissolving') {
        mode = nextMode()
        reroll()
      }
      frame(performance.now())
    }
  }

  function advance() {
    if (phase === 'text') {
      /* 只有真正从"停留"走进"散开"才记一轮 —— 写进 enter 会让中途 resize 白扣一轮 */
      cycles++
      enter('dissolving')
      return
    }
    enter(phase === 'dissolving' ? 'field' : phase === 'field' ? 'gathering' : 'text')
  }

  build()
  enter('text')

  return {
    setActive(on) {
      if (on === active || destroyed) return
      active = on
      if (on) enter(phase)
      else stopWork()
    },
    resize() {
      if (destroyed) return
      stopWork()
      build()
      if (active) enter(phase)
      else if (phase === 'text') drawRealText()
    },
    destroy() {
      destroyed = true
      active = false
      stopWork()
    },
  }
}

/* ------------------------------------------------------------------ */
/* React 组件                                                          */
/* ------------------------------------------------------------------ */

/** 光雾色块的底色：canvas 拿具体色值，DOM 用 rgba 叠在页面上 */
const cssColor = (c: RGB, a: number) => `rgba(${c[0]},${c[1]},${c[2]},${a})`

export interface ParticleTitleProps {
  /** 标题文字。真实 <h2> 留在 DOM 里，读屏软件不受影响。 */
  text: string
  className?: string
  /** 停留几毫秒后开始散开，默认 2400 */
  holdMs?: number
  /** 循环次数，默认 1（放一次就停） */
  repeats?: number
}

/**
 * 标题先以清晰文字出现，短暂停留后散成光雾里的尘埃，再聚拢成型；
 * 之后**保持静止**，不会再循环。刷新页面或进入新的空对话会重新播放一次。
 */
export function ParticleTitle({ text, className = '', holdMs = 2400, repeats = 1 }: ParticleTitleProps) {
  const reduce = useReducedMotion()
  const { resolved, palette } = useTheme()
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const titleRef = useRef<HTMLHeadingElement>(null)

  /* 主题 / 配色一变就重算 —— canvas 只能吃具体色值，必须在这里解析令牌 */
  const pal = useMemo<Palette>(() => {
    const cs = getComputedStyle(document.documentElement)
    return {
      ink: readToken(cs, '--text', '#202020'),
      page: readToken(cs, '--bg', '#f4f5f7'),
      blobs: BLOB_SPECS[resolved].map((s, i) => ({
        ...BLOB_GEOM[i],
        rgb: readToken(cs, s.token, '#888888'),
        a: s.a,
      })),
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [resolved, palette])

  useEffect(() => {
    const cv = canvasRef.current
    const titleEl = titleRef.current
    if (reduce || !cv || !titleEl) return
    const engine = createEngine(cv, text, pal, { holdMs, repeats, titleEl })
    if (!engine) return

    let visible = true
    let size = `${cv.clientWidth}x${cv.clientHeight}`
    const sync = () => engine.setActive(visible && !document.hidden)

    /* 离屏（切到别的会话/滚动出去）与标签页隐藏时停掉，不做无用功 */
    const io = new IntersectionObserver((entries) => {
      visible = entries[entries.length - 1].isIntersecting
      sync()
    })
    io.observe(cv)

    let rz = 0
    const ro = new ResizeObserver(() => {
      const next = `${cv.clientWidth}x${cv.clientHeight}`
      if (next === size) return
      size = next
      clearTimeout(rz)
      rz = window.setTimeout(() => engine.resize(), 200)
    })
    ro.observe(cv)

    document.addEventListener('visibilitychange', sync)
    sync()
    return () => {
      document.removeEventListener('visibilitychange', sync)
      clearTimeout(rz)
      io.disconnect()
      ro.disconnect()
      engine.destroy()
    }
  }, [reduce, text, pal, holdMs, repeats])

  return (
    <div className={`pt${className ? ` ${className}` : ''}`}>
      <div className="pt__glow" aria-hidden="true">
        {pal.blobs.map((b, i) => (
          <div
            key={i}
            className={`pt__blob pt__blob--${i}`}
            style={{ background: `radial-gradient(closest-side, ${cssColor(b.rgb, b.a)}, ${cssColor(b.rgb, 0)} 100%)` }}
          />
        ))}
      </div>

      {!reduce && <canvas ref={canvasRef} aria-hidden="true" className="pt__canvas" />}

      {/* 动画期间这张 h2 只留给读屏软件；画布画的是同一句话 */}
      <h2 ref={titleRef} className={`pt__title${reduce ? '' : ' pt__title--ghost'}`}>
        {text}
      </h2>
    </div>
  )
}

export default ParticleTitle
