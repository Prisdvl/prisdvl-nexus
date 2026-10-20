/**
 * 热力图共用的动效令牌。
 *
 * 数值刻意与 `src/styles/tokens.css` 的 `--dur-*` / `--ease-*` 保持一致：
 * 一处是 CSS 变量、一处是 motion 的 JS 参数，两边漂移就会出现「同一个界面
 * 两种节奏」——这是这类组件最常见的隐性破绽。
 */
import type { Transition } from 'motion/react'

/** 时长（秒）——与 --dur-fast / --dur-ui / --dur-panel 同值 */
export const DURATION = {
  fast: 0.13,
  ui: 0.2,
  panel: 0.28,
} as const

/** 曲线——与 --ease-out / --ease-inout 同值 */
export const EASE = {
  out: [0.23, 1, 0.32, 1],
  inOut: [0.77, 0, 0.175, 1],
} as const

/** 弹簧预设：snappy 给格子点击反馈，soft 给面板入场，cell 给色阶呼吸 */
export const SPRING = {
  snappy: { type: 'spring', stiffness: 480, damping: 32, mass: 0.7 },
  soft: { type: 'spring', stiffness: 260, damping: 30, mass: 0.9 },
  cell: { type: 'spring', stiffness: 520, damping: 36, mass: 0.6 },
} satisfies Record<string, Transition>

/** 单格 hover / 选中的统一过渡（鼠标进出的高频动作，用最短时长） */
export const cellTransition: Transition = { duration: DURATION.fast, ease: EASE.out }

/** 面板入场：一次性，不重复播放 */
export const revealTransition = (delay = 0): Transition => ({
  duration: DURATION.panel,
  ease: EASE.out,
  delay,
})

/** 一整块热力的入场位移（fade up），交给 whileInView 只播一次 */
export const revealVariants = {
  hidden: { opacity: 0, y: 8 },
  show: { opacity: 1, y: 0 },
} as const
