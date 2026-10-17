import { useEffect, useMemo, useRef, useState } from 'react'
import { Moon, Sun } from 'lucide-react'
import RubberSegment from '@/bits/RubberSegment/RubberSegment'
import { useTheme, type ThemeModeSetting } from '@/lib/theme'
import { runThemeTransition } from '@/lib/themeTransition'
import type { PaletteName } from '@/lib/types'
import { appCopy } from '@/lib/appCopy'
import { useLocale } from '@/lib/locale'
import Loc from './Loc'

/**
 * id 是稳定值，label 随语言取 —— 写成模块级常量会把语言焊死。
 * 组件内用 useMemo 按当前 locale 生成，避免切换语言后分段控件还显示旧文案。
 */
const MODE_IDS: ThemeModeSetting[] = ['light', 'dark', 'system']

const THEME_LABELS: Record<'zh' | 'en', Record<ThemeModeSetting, string>> = {
  zh: { light: '浅色', dark: '暗色', system: '跟随系统' },
  en: { light: 'Light', dark: 'Dark', system: 'Match system' },
}

const ACCENT_LABELS: Record<'zh' | 'en', Record<Exclude<PaletteName, 'prisdvl'>, string>> = {
  zh: { ocean: '棱镜蓝', terracotta: '陶土橘', turquoise: '松石青', lime: '青柠', indigo: '靛蓝', violet: '紫罗兰', rose: '玫红' },
  en: { ocean: 'Prism blue', terracotta: 'Terracotta', turquoise: 'Turquoise', lime: 'Lime', indigo: 'Indigo', violet: 'Violet', rose: 'Rose' },
}

/**
 * 强调色预设。前三套是"全配色"（亮色中性基座带极淡同色相），
 * 后四套是 AccentColor（只换强调色族）。色值用于设置面板里的预览圆点；
 * 真正生效的颜色在 tokens.css 里，两处色值保持同一观感即可。
 */
type AccentDef = { id: PaletteName; light: string; dark: string }
const ACCENT_DEFS: AccentDef[] = [
  { id: 'prisdvl', light: '#2457D6', dark: '#2457D6' },
  { id: 'ocean', light: '#305478', dark: '#8aacc4' },
  { id: 'terracotta', light: '#b4542a', dark: '#e8a077' },
  { id: 'turquoise', light: '#1f7a8c', dark: '#6fd3de' },
  { id: 'lime', light: '#5f9c1a', dark: '#b9e26a' },
  { id: 'indigo', light: '#4f46e5', dark: '#9b93ff' },
  { id: 'violet', light: '#c026a8', dark: '#f489e0' },
  { id: 'rose', light: '#e11d48', dark: '#fb7185' },
]

/**
 * 左下角扇区向右上展开。
 * 8 点 × 20px：圆心距需 ≥40px 才不粘；弧 ~126°、r≈142 时间隙约 18px。
 */
function orbitOffset(index: number, total: number, open: boolean, radius = 142) {
  const start = -14
  const end = 112
  const t = total <= 1 ? 0 : index / (total - 1)
  const deg = start + t * (end - start)
  const rad = (deg * Math.PI) / 180
  /* 展开：近端先出；收起：远端先回，错峰更清晰 */
  const delay = open
    ? `${18 + index * 46}ms`
    : `${10 + (total - 1 - index) * 32}ms`
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
  const { locale } = useLocale()
  const t = appCopy(locale)
  const { mode, palette, resolved, setMode, setPalette } = useTheme()
  const accentCopy = ACCENT_LABELS[locale]
  const ACCENTS = useMemo(
    () => ACCENT_DEFS.map((a) => ({ ...a, label: a.id === 'prisdvl' ? 'Prisdvl' : accentCopy[a.id] })),
    [accentCopy],
  )
  const MODES = useMemo(
    () => MODE_IDS.map((id) => ({ id, label: THEME_LABELS[locale][id] })),
    [locale],
  )
  const modeLabel = MODES.find((m) => m.id === mode)?.label ?? MODES[2].label
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
        <span className="appearance-label"><Loc>{t.appearance.themeLabel}</Loc></span>
        {prisdvl ? (
          <p className="appearance-note">{t.appearance.prisdvlNote}</p>
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
            aria-label={t.appearance.themeAria}
          />
        )}
      </div>
      <div className="appearance-row">
        <span className="appearance-label"><Loc>{t.appearance.accentLabel}</Loc></span>
        <div className="accent-swatches" role="radiogroup" aria-label={t.appearance.accentAria}>
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
  const { locale } = useLocale()
  const t = appCopy(locale)
  const { palette, resolved, setMode, setPalette } = useTheme()
  const ACCENTS = useMemo(
    () => ACCENT_DEFS.map((a) => ({ ...a, label: a.id === 'prisdvl' ? 'Prisdvl' : ACCENT_LABELS[locale][a.id] })),
    [locale],
  )
  const [pickerOpen, setPickerOpen] = useState(false)
  const [iconSpin, setIconSpin] = useState(0)
  const [pickedId, setPickedId] = useState<PaletteName | null>(null)
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

  useEffect(() => {
    if (!pickedId) return
    const t = window.setTimeout(() => setPickedId(null), 520)
    return () => window.clearTimeout(t)
  }, [pickedId])

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
    setIconSpin((n) => n + 1)
    runThemeTransition(btnRef.current, () => setMode(next))
  }

  return (
    <div
      ref={controlRef}
      className="theme-control"
      data-picker-open={pickerOpen ? '' : undefined}
      data-resolved={resolved}
    >
      {/* 轻 goo：只在展开瞬间略融，避免色点常年粘成一团 */}
      <svg className="theme-control__goo-defs" aria-hidden width="0" height="0">
        <defs>
          <filter id="theme-swatch-goo" x="-50%" y="-50%" width="200%" height="200%" colorInterpolationFilters="sRGB">
            <feGaussianBlur in="SourceGraphic" stdDeviation="1.4" result="blur" />
            <feColorMatrix
              in="blur"
              mode="matrix"
              values="1 0 0 0 0  0 1 0 0 0  0 0 1 0 0  0 0 0 16 -9"
              result="goo"
            />
            <feBlend in="SourceGraphic" in2="goo" />
          </filter>
        </defs>
      </svg>

      <div className="theme-control__frame" aria-hidden />
      <div className="theme-control__halo" aria-hidden />

      <div
        className="theme-control__swatches"
        role="radiogroup"
        aria-label={t.appearance.accentPickAria}
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
              data-picked={pickedId === a.id ? '' : undefined}
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
                setPickedId(a.id)
                if (a.id !== palette) {
                  runThemeTransition(e.currentTarget, () => setPalette(a.id))
                }
                /* 稍留选中反馈再收起，避免「点完立刻消失」 */
                window.setTimeout(() => setPickerOpen(false), 220)
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
              ? t.appearance.collapseAccent
              : dark
                ? t.appearance.toLight
                : t.appearance.toDark
            : t.appearance.expandAccent
        }
        title={
          pickerOpen
            ? prisdvl
              ? t.appearance.tapCollapse
              : t.appearance.tapToggleTheme
            : t.appearance.tapExpandAccent
        }
        onClick={onThemeBtnClick}
      >
        <span
          className="theme-control__icons"
          data-spin={iconSpin > 0 ? iconSpin : undefined}
          key={iconSpin > 0 ? `spin-${iconSpin}` : 'idle'}
        >
          <Sun
            className="theme-control__icon theme-control__icon--sun"
            size={17}
            strokeWidth={1.8}
            aria-hidden
          />
          <Moon
            className="theme-control__icon theme-control__icon--moon"
            size={17}
            strokeWidth={1.8}
            aria-hidden
          />
        </span>
      </button>
    </div>
  )
}
