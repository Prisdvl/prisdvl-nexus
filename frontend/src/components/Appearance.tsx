import { useEffect, useRef, useState } from 'react'
import { Monitor, Moon, Paintbrush, Sun } from 'lucide-react'
import RubberSegment from '@/bits/RubberSegment/RubberSegment'
import { useTheme, type ThemeModeSetting } from '@/lib/theme'
import type { PaletteName } from '@/lib/types'

const MODES: { id: ThemeModeSetting; label: string; icon: typeof Sun }[] = [
  { id: 'light', label: '亮色', icon: Sun },
  { id: 'dark', label: '暗色', icon: Moon },
  { id: 'system', label: '跟随系统', icon: Monitor },
]

const PALETTES: { id: PaletteName; label: string }[] = [
  { id: 'terracotta', label: '陶土橘' },
  { id: 'turquoise', label: '松石青' },
  { id: 'lime', label: '青柠' },
]

/**
 * 外观控件：主题 + 配色。
 * 设置页与登录前的角落面板共用这一份，避免两处各写一套导致不一致。
 */
export function AppearanceControls({ size = 'md' }: { size?: 'sm' | 'md' }) {
  const { mode, palette, setMode, setPalette } = useTheme()
  const modeLabel = MODES.find((m) => m.id === mode)?.label ?? '跟随系统'
  const paletteLabel = PALETTES.find((p) => p.id === palette)?.label ?? '陶土橘'

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

  return (
    <div className="appearance">
      <div className="appearance-row">
        <span className="appearance-label">主题</span>
        <RubberSegment
          {...shared}
          items={MODES.map((m) => m.label)}
          value={modeLabel}
          animateExternal
          onChange={(label) => {
            const hit = MODES.find((m) => m.label === label)
            if (hit) setMode(hit.id)
          }}
          aria-label="主题"
        />
      </div>
      <div className="appearance-row">
        <span className="appearance-label">配色</span>
        <RubberSegment
          {...shared}
          items={PALETTES.map((p) => p.label)}
          value={paletteLabel}
          animateExternal
          onChange={(label) => {
            const hit = PALETTES.find((p) => p.label === label)
            if (hit) setPalette(hit.id)
          }}
          aria-label="配色"
        />
      </div>
    </div>
  )
}

/**
 * 登录前的角落聚合控件：一个图标按钮，点开才是主题与配色。
 * 取代此前常驻右下角的整条悬浮工具栏（那是开发者工具，不该出现在产品界面里）。
 */
export default function CornerControls() {
  const [open, setOpen] = useState(false)
  const { mode, resolved } = useTheme()
  const boxRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const onDoc = (e: MouseEvent) => {
      if (boxRef.current && !boxRef.current.contains(e.target as Node)) setOpen(false)
    }
    const onEsc = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false) }
    document.addEventListener('mousedown', onDoc)
    document.addEventListener('keydown', onEsc)
    return () => {
      document.removeEventListener('mousedown', onDoc)
      document.removeEventListener('keydown', onEsc)
    }
  }, [open])

  const Icon = mode === 'system' ? Monitor : resolved === 'dark' ? Moon : Sun

  return (
    <div className="corner-tools" ref={boxRef}>
      {open && (
        <div className="corner-panel" role="dialog" aria-label="外观设置">
          <p className="corner-title"><Paintbrush size={13} /> 外观</p>
          <AppearanceControls size="sm" />
        </div>
      )}
      <button
        className="corner-btn"
        type="button"
        aria-label="外观设置"
        aria-expanded={open}
        aria-haspopup="dialog"
        onClick={() => setOpen((v) => !v)}
      >
        <Icon size={17} />
      </button>
    </div>
  )
}