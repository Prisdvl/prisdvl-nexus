import type { ReactElement, ReactNode } from 'react'
import { Overlay } from './Overlay'

/**
 * Dialog —— 居中模态。
 *
 * 承载「必须完成才能继续」的操作：删除、停用、整包同步、重建索引（任务书 §14）。
 * 表单类弹窗请把 `closeOnOverlayClick` 设为 false——误点遮罩会丢用户刚填的东西。
 */

export interface DialogProps {
  open: boolean
  onClose: () => void
  title: ReactNode
  children: ReactNode
  /** 底部操作区。放在这里是为了让"确认/取消"的顺序与样式统一。 */
  footer?: ReactNode
  label?: string
  closeOnOverlayClick?: boolean
  /** 面板宽度，默认 28rem。 */
  width?: string
}

export function Dialog({
  open,
  onClose,
  title,
  children,
  footer,
  label,
  closeOnOverlayClick = true,
  width = '28rem'
}: DialogProps): ReactElement | null {
  return (
    <Overlay
      open={open}
      onClose={onClose}
      closeOnOverlayClick={closeOnOverlayClick}
      label={label}
      title={title}
      className="grid place-items-center p-4"
      panelClassName="max-h-[calc(100vh-2rem)] w-full rounded-[var(--nx-radius-xl)]"
    >
      <div className="overflow-y-auto px-4 py-3" style={{ maxWidth: width }}>
        {children}
      </div>
      {footer ? (
        <div className="flex items-center justify-end gap-2 border-t border-[var(--nx-color-border)] bg-[var(--nx-color-surface-muted)] px-4 py-3">
          {footer}
        </div>
      ) : null}
    </Overlay>
  )
}
