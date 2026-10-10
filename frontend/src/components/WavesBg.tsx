import PatternWaves from '@/bits/PatternWaves/PatternWaves'
import Aurora from '@/bits/Aurora/Aurora'
import Threads from '@/bits/Threads/Threads'
import DotGrid from '@/bits/DotGrid/DotGrid'
import Ribbons from '@/bits/Ribbons/Ribbons'
import type { PaletteName, ThemeMode } from '@/lib/types'

/** 各配色 × 主题的取色 */
const WAVE_COLOR: Record<PaletteName, { light: string; dark: string }> = {
  terracotta: { light: '#8f4a2e', dark: '#e09872' },
  turquoise: { light: '#1f7a8c', dark: '#6fd3de' },
  lime: { light: '#4d7c0f', dark: '#b9e26a' },
}
const ACCENT_HEX: Record<PaletteName, { light: string; dark: string }> = {
  terracotta: { light: '#b4542a', dark: '#e8a077' },
  turquoise: { light: '#1f7a8c', dark: '#6fd3de' },
  lime: { light: '#5f9c1a', dark: '#b9e26a' },
}
/** Threads 需要 0–1 归一化 RGB */
const THREAD_RGB: Record<PaletteName, [number, number, number]> = {
  terracotta: [0.62, 0.32, 0.18],
  turquoise: [0.12, 0.48, 0.55],
  lime: [0.37, 0.61, 0.1],
}

export type BgVariant = 'waves' | 'aurora' | 'threads' | 'dotgrid' | 'ribbons'

interface WavesBgProps {
  theme: ThemeMode
  palette: PaletteName
  /** 登录/入口页主背景 vs 应用内衬底 */
  hero?: boolean
  /** 背景手法（业务页各用其一，避免同质化） */
  variant?: BgVariant
}

/** 全站背景层：五种 WebGL / Canvas 背景按页面分配 */
export default function WavesBg({ theme, palette, hero = false, variant = 'waves' }: WavesBgProps) {
  const dark = theme === 'dark'
  const ink = WAVE_COLOR[palette][theme]
  const accent = ACCENT_HEX[palette][theme]
  /* 应用内作"氛围底"：太亮会压住内容与文字对比度（实测暗色下 Aurora 曾铺满整屏） */
  const baseOpacity = hero ? (dark ? 0.9 : 0.8) : dark ? 0.34 : 0.42

  let layer: React.ReactNode = null
  if (variant === 'waves') {
    layer = (
      <PatternWaves
        preset={hero ? 'mesh' : 'mesh'}
        color={ink}
        backgroundColor="transparent"
        opacity={baseOpacity}
        fade="edges"
        fadeSize={hero ? 0.85 : 0.9}
        spacing={16}
        markSize={hero ? 0.85 : 0.7}
        depth={hero ? 0.85 : 0.6}
        light={0}
        shine={hero ? 1.1 : 0.8}
        contrast={hero ? 1.4 : 1.15}
        speed={hero ? 0.5 : 0.3}
        scale={hero ? 1.05 : 1.15}
        direction={25}
        interactive={hero}
        cursorSize={60}
        cursorStrength={0.5}
        intro={false}
      />
    )
  } else if (variant === 'aurora') {
    layer = <Aurora colorStops={[accent, dark ? '#ffffff' : '#f0c9b0', accent]} amplitude={0.9} blend={0.5} speed={0.5} lightMode={!dark} />
  } else if (variant === 'threads') {
    layer = <Threads color={THREAD_RGB[palette]} amplitude={1} distance={0.25} enableMouseInteraction={hero} />
  } else if (variant === 'dotgrid') {
    layer = (
      <DotGrid
        dotSize={4}
        gap={26}
        baseColor={dark ? '#33333a' : '#d6d8de'}
        activeColor={accent}
        proximity={120}
        shockRadius={200}
        shockStrength={3}
        resistance={800}
        returnDuration={1.6}
      />
    )
  } else {
    layer = (
      <Ribbons
        colors={[accent, dark ? '#ffffff' : '#33333a']}
        baseSpring={0.03}
        baseFriction={0.9}
        speedMultiplier={0.35}
        maxAge={420}
        pointCount={36}
        enableFade
      />
    )
  }

  return (
    <div
      data-bg={variant}
      data-bg-hero={hero ? '' : undefined}
      style={{
        position: 'fixed',
        inset: 0,
        zIndex: 0,
        pointerEvents: 'none',
        opacity: 1,
      }}
      aria-hidden="true"
    >
      {layer}
    </div>
  )
}
