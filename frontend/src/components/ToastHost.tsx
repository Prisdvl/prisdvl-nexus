import { CheckCircle2, Info, ListChecks, TriangleAlert } from 'lucide-react'
import SwipeToast from '@/bits/SwipeToast/SwipeToast'
import { toast, useToasts, type ToastTone } from '@/lib/toast'
import type { ThemeMode } from '@/lib/types'

const ICON: Record<ToastTone, typeof Info> = {
  info: Info,
  success: CheckCircle2,
  error: TriangleAlert,
  task: ListChecks,
}

interface Props {
  theme: ThemeMode
}

/** 提示宿主：右下角堆叠，可侧滑关闭；删除类操作带「撤销」 */
export default function ToastHost({ theme }: Props) {
  const items = useToasts()
  const dark = theme === 'dark'

  const surface = dark ? '#1c1c20' : '#ffffff'
  const ink = dark ? '#f2f2f5' : '#16161a'
  const fuse = dark ? '#8ea2ff' : '#b4542a'

  return (
    <div
      className="toast-host"
      aria-live="polite"
      aria-relevant="additions"
      style={{ position: 'fixed', right: 18, bottom: 78, zIndex: 200, display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 10, pointerEvents: 'none' }}
    >
      {items.map((t) => {
        const Icon = ICON[t.tone]
        const tint = t.tone === 'error' ? (dark ? '#ff8f8f' : '#c02626') : t.tone === 'success' ? (dark ? '#7fd8a0' : '#1f7a4d') : ink
        return (
          <div key={t.id} style={{ pointerEvents: 'auto' }}>
            <SwipeToast
              inline
              title={<span style={{ color: tint }}>{t.title}</span>}
              description={t.description}
              icon={<span style={{ color: tint, display: 'inline-flex' }}><Icon size={17} /></span>}
              actionLabel={t.actionLabel}
              onAction={t.onAction}
              duration={t.duration ?? 4000}
              background={surface}
              color={ink}
              fuseColor={t.tone === 'error' ? '#e5484d' : fuse}
              width={340}
              radius={12}
              closeButton
              onClose={() => toast.dismiss(t.id)}
            />
          </div>
        )
      })}
    </div>
  )
}