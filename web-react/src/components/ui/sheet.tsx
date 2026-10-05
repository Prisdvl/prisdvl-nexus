import type { CSSProperties, ReactElement, ReactNode } from 'react'
import { cn } from '@/lib/utils'
import { Overlay } from './Overlay'

/**
 * Sheet —— 侧边抽屉。
 *
 * 承载「与主内容并存」的编辑：Agent 配置、资料设置、账号资料（任务书 §18）。
 * 与 Dialog 的选择判据：
 *
 * | 场景 | 用哪个 | 理由 |
 * | --- | --- | --- |
 * | 表单字段多、需要参照上下文 | Sheet | 抽屉只盖住一侧，主区仍可见 |
 * | 需要用户明确拍板 | Dialog | 居中会自然吸引注意，且不给"先看看旁边"的余地 |
 *
 * 表单类一律 `closeOnOverlayClick={false}`：误点遮罩会丢用户刚填的东西。
 */

export type SheetSide = 'right' | 'left' | 'bottom'

export interface SheetProps {
  open: boolean
  onClose: () => void
  title: ReactNode
  children: ReactNode
  footer?: ReactNode
  side?: SheetSide
  closeOnOverlayClick?: boolean
  label?: string
  /** 左右侧抽屉的宽度。底部抽屉忽略（用 max-height）。 */
  width?: string
}

const SIDE_CLASS: Record<SheetSide, string> = {
  right: 'inset-y-0 right-0 h-full',
  left: 'inset-y-0 left-0 h-full',
  bottom: 'inset-x-0 bottom-0'
}

const PANEL_SHAPE: Record<SheetSide, string> = {
  right: 'border-l',
  left: 'border-r',
  bottom: 'mt-auto max-h-[85vh] border-t'
}

export function Sheet({
  open,
  onClose,
  title,
  children,
  footer,
  side = 'right',
  closeOnOverlayClick = false,
  label,
  width = '32rem'
}: SheetProps): ReactElement | null {
  // 宽度用内联样式而不是拼接 Tailwind 任意值类名——拼接出的类名
  // Tailwind 扫描器看不到，生产构建会裁掉，表现为"宽度不生效"且不报错。
  const panelStyle: CSSProperties =
    side === 'bottom' ? {} : { width, maxWidth: '100vw' }

  return (
    <Overlay
      open={open}
      onClose={onClose}
      closeOnOverlayClick={closeOnOverlayClick}
      label={label}
      title={title}
      className={cn(side === 'bottom' ? 'flex items-end' : '')}
      panelClassName={cn(
        'flex flex-col bg-[var(--nx-color-surface)] shadow-[var(--nx-shadow-lg)]',
        SIDE_CLASS[side],
        PANEL_SHAPE[side],
        'border-[var(--nx-color-border)]'
      )}
      panelStyle={panelStyle}
    >
      <div className="min-h-0 flex-1 overflow-y-auto px-4 py-3">{children}</div>
      {footer ? (
        <div className="flex items-center justify-end gap-2 border-t border-[var(--nx-color-border)] bg-[var(--nx-color-surface-muted)] px-4 py-3">
          {footer}
        </div>
      ) : null}
    </Overlay>
  )
}
