/**
 * 落地页 hero 的背景波浪（PatternWaves）参数与取色。
 *
 * 为什么单独抽一份文件：**「新对话」空态要用与落地页完全相同的背景**，
 * 而调用点有两个 —— `pages/LandingPage.tsx` 的 hero 与 `pages/ChatPage.tsx` 的空态。
 * 参数各写一份迟早会分叉：改了一处、另一处还是旧值，
 * 这种「看着差不多、其实不一样」的问题最难查，所以只留一份定义。
 *
 * 取色也在这一份里，原因同上：落地页暗色用的是**近白高光**
 * （#ffffff / #eafcff / #f3f1ff …），不是强调色 ——
 * 那是落地页那股冷冽质感的关键，换成强调色就是另一套观感了。
 * 注意它和 `components/WavesBg.tsx` 里的 `WAVE_COLOR`（登录页用强调色）
 * 是两套，别合并。
 */
import type { PatternWavesProps } from '@/bits/PatternWaves/PatternWaves'
import type { PaletteName } from '@/lib/types'

export const LANDING_WAVE_COLOR: Record<PaletteName, { light: string; dark: string }> = {
  prisdvl: { light: '#193FA8', dark: '#3987EC' },
  terracotta: { light: '#7a3a1f', dark: '#ffffff' },
  turquoise: { light: '#14586b', dark: '#eafcff' },
  lime: { light: '#3f6a0d', dark: '#f2ffd6' },
  indigo: { light: '#312e81', dark: '#f3f1ff' },
  ocean: { light: '#305478', dark: '#d8e6f0' },
  violet: { light: '#701a75', dark: '#fff1fc' },
  rose: { light: '#881337', dark: '#fff1f2' },
}

/** 暗色略高：深底上同样的不透明度几乎看不出波纹 */
export const LANDING_WAVE_OPACITY = { light: 0.16, dark: 0.22 } as const

/** 除 color / opacity 外的全部参数（这两项由调用方按主题给） */
export const LANDING_WAVE_PARAMS: Omit<PatternWavesProps, 'color' | 'opacity'> = {
  preset: 'ocean',
  backgroundColor: 'transparent',
  fade: 'edges',
  fadeSize: 0.95,
  spacing: 18,
  markSize: 0.62,
  depth: 0.4,
  light: 0,
  shine: 0.12,
  contrast: 0.85,
  speed: 0.18,
  scale: 1.2,
  direction: 30,
  interactive: true,
  cursorSize: 70,
  cursorStrength: 0.6,
  intro: true,
}
