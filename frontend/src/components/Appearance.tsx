import { useEffect, useRef, useState } from 'react'
import { Moon, Sun } from 'lucide-react'
import RubberSegment from '@/bits/RubberSegment/RubberSegment'
import { useTheme, type ThemeModeSetting } from '@/lib/theme'
import { runThemeTransition } from '@/lib/themeTransition'
import type { PaletteName } from '@/lib/types'

const MODES: { id: ThemeModeSetting; label: string }[] = [
  { id: 'light', label: '浅色' },
  { id: 'dark', label: '暗色' },
  { id: 'system', label: '跟随系统' },
]

/**
 * 强调色预设。前三套是"全配色"（亮色中性基座带极淡同色相），
 * 后四套是 AccentColor（只换强调色族）。色值用于设置面板里的预览圆点；
 * 真正生效的颜色在 tokens.css 里，两处色值保持同一观感即可。
 */
const ACCENTS: { id: PaletteName; label: string; light: string; dark: string }[] = [
  { id: 'prisdvl', label: 'Prisdvl', light: '#2457D6', dark: '#2457D6' },
  { id: 'ocean', label: '棱镜蓝', light: '#305478', dark: '#8aacc4' },
  { id: 'terracotta', label: '陶土橘', light: '#b4542a', dark: '#e8a077' },
  { id: 'turquoise', label: '松石青', light: '#1f7a8c', dark: '#6fd3de' },
  { id: 'lime', label: '青柠', light: '#5f9c1a', dark: '#b9e26a' },
  { id: 'indigo', label: '靛蓝', light: '#4f46e5', dark: '#9b93ff' },
  { id: 'violet', label: '紫罗兰', light: '#c026a8', dark: '#f489e0' },
  { id: 'rose', label: '玫红', light: '#e11d48', dark: '#fb7185' },
]

/**
 * 左下角扇区向右上展开。
 * 8 点 × 20px：圆心距约 ≥36px 才不粘；弧 ~118°、r≈128 时间隙约 14px。
 */
function orbitOffset(index: number, total: number, open: boolean, radius = 128) {
  const start = -10
  const end = 108
  const t = total <= 1 ? 0 : index / (total - 1)
  const deg = start + t * (end - start)
  const rad = (deg * Math.PI) / 180
  /* 展开：内→外错峰；收起：外→内回落，观感更丝滑 */
  const delay = open
    ? `${28 + index * 52}ms`
    : `${12 + (total - 1 - index) * 36}ms`
  return {
    tx: `${Math.cos(rad) * radius}px`,
    ty: `${-Math.sin(rad) * radius}px`,
    delay,
  }
}

/**
 * 外观控件：主题 + 强调色。
 * 设置页与登录前的角落面板共用这一份，避免两处各写一套导致不一致。
 */
export function AppearanceControls({ size = 'md' }: { size?: 'sm' | 'md' }) {
  const { mode, palette, resolved, setMode, setPalette } = useTheme()
  const modeLabel = MODES.find((m) => m.id === mode)?.label ?? '跟随系统'
  const modeRowRef = useRef<HTMLDivElement>(null)

  const shared = {
    size,
    radius: 10,
    inset: size === 'sm' ? 2 : 3,
    equalSlots: true,
    trackColor: 'var(--surface-muted)',
    thumbColor: 'var(--primary-container)',
    textColor: 'var(--text-2)',
    activeTextColor: 'var(--primary-text)',
  } as const

  const prisdvl = palette === 'prisdvl'

  return (
    <div className={`appearance${size === 'sm' ? ' appearance--sm' : ''}`}>
      <div className="appearance-row" ref={modeRowRef}>
        <span className="appearance-label">主题</span>
        {prisdvl ? (
          <p className="appearance-note">Prisdvl · 深色深钴蓝（独立模式）</p>
        ) : (
          <RubberSegment
            {...shared}
            items={MODES.map((m) => m.label)}
            value={modeLabel}
            animateExternal
            onChange={(label) => {
              const hit = MODES.find((m) => m.label === label)
              if (!hit || hit.id === mode) return
              runThemeTransition(modeRowRef.current, () => setMode(hit.id))
            }}
            aria-label="主题"
          />
        )}
      </div>
      <div className="appearance-row">
        <span className="appearance-label">配色</span>
        <div className="accent-swatches" role="radiogroup" aria-label="配色">
          {ACCENTS.map((a) => {
            const dot = a.id === 'prisdvl' ? a.light : resolved === 'dark' ? a.dark : a.light
            return (
              <button
                key={a.id}
                type="button"
                role="radio"
                aria-checked={palette === a.id}
                title={a.label}
                aria-label={a.label}
                className="accent-swatch"
                data-on={palette === a.id ? '' : undefined}
                style={{ background: dot }}
                onClick={(e) => {
                  if (a.id === palette) return
                  runThemeTransition(e.currentTarget, () => setPalette(a.id))
                }}
              />
            )
          })}
        </div>
      </div>
    </div>
  )
}

/**
 * 角落主题控件：
 * - 点击主题键展开环绕配色环（不依赖悬停，可稳定点选）
 * - 点选配色后确认并收起，恢复原状
 * - 展开时再点中心：切换浅/深色（Prisdvl 锁定则收起）
 */
export default function CornerControls() {
  const { palette, resolved, setMode, setPalette } = useTheme()
  const [pickerOpen, setPickerOpen] = useState(false)
  const controlRef = useRef<HTMLDivElement>(null)
  const btnRef = useRef<HTMLButtonElement>(null)
  const dark = resolved === 'dark'
  const prisdvl = palette === 'prisdvl'

  useEffect(() => {
    if (!pickerOpen) return
    const onDoc = (e: MouseEvent) => {
      if (controlRef.current && !controlRef.current.contains(e.target as Node)) {
        setPickerOpen(false)
      }
    }
    const onEsc = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setPickerOpen(false)
    }
    document.addEventListener('mousedown', onDoc)
    document.addEventListener('keydown', onEsc)
    return () => {
      document.removeEventListener('mousedown', onDoc)
      document.removeEventListener('keydown', onEsc)
    }
  }, [pickerOpen])

  const onThemeBtnClick = () => {
    if (!pickerOpen) {
      setPickerOpen(true)
      return
    }
    if (prisdvl) {
      setPickerOpen(false)
      return
    }
    const next = dark ? 'light' : 'dark'
    runThemeTransition(btnRef.current, () => setMode(next))
  }

  return (
    <div
      ref={controlRef}
      className="theme-control"
      data-picker-open={pickerOpen ? '' : undefined}
    >
      <div className="theme-control__frame" aria-hidden />

      <div
        className="theme-control__swatches"
        role="radiogroup"
        aria-label="选择配色"
        aria-hidden={!pickerOpen}
      >
        {ACCENTS.map((a, i) => {
          const dot = a.id === 'prisdvl' ? a.light : dark ? a.dark : a.light
          const { tx, ty, delay } = orbitOffset(i, ACCENTS.length, pickerOpen)
          return (
            <button
              key={a.id}
              type="button"
              className="theme-control__swatch"
              tabIndex={pickerOpen ? 0 : -1}
              style={{
                ['--swatch-color' as string]: dot,
                ['--tx' as string]: tx,
                ['--ty' as string]: ty,
                ['--delay' as string]: delay,
              }}
              aria-pressed={palette === a.id}
              aria-label={a.label}
              title={a.label}
              onClick={(e) => {
                e.stopPropagation()
                if (a.id !== palette) {
                  runThemeTransition(e.currentTarget, () => setPalette(a.id))
                }
                setPickerOpen(false)
              }}
            />
          )
        })}
      </div>

      <button
        ref={btnRef}
        className="theme-control__btn"
        type="button"
        aria-expanded={pickerOpen}
        aria-haspopup="true"
        aria-label={
          pickerOpen
            ? prisdvl
              ? '收起配色'
              : dark
                ? '切换到浅色主题'
                : '切换到深色主题'
            : '展开配色'
        }
        title={
          pickerOpen
            ? prisdvl
              ? '点击收起'
              : '点击切换浅/深色 · Esc 收起'
            : '点击展开配色环'
        }
        onClick={onThemeBtnClick}
      >
        {dark ? (
          <Sun size={17} strokeWidth={1.8} aria-hidden />
        ) : (
          <Moon size={17} strokeWidth={1.8} aria-hidden />
        )}
      </button>
    </div>
  )
}
