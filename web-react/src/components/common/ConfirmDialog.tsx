import { useState, type ReactElement, type ReactNode } from 'react'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'

/**
 * ConfirmDialog —— 危险操作的统一确认。
 *
 * ## 为什么不用 `window.confirm`
 *
 * 三个理由，都是实际遇到的：
 * 1. **样式不可控**：它由浏览器渲染，与应用风格割裂，深色模式下尤其突兀；
 * 2. **无法表达影响**：只能给两行文字，说不了"将删除 12 篇文档与 340 个切片"；
 * 3. **无法表达进行中**：删除请求在飞的时候，`confirm` 已经消失了，
 *    用户可以再点一次，于是发了两个删除请求。
 *
 * 任务书 §49 明确要求它支持 `danger` 与 `loading`。
 *
 * ## 危险操作必须把影响写进 description
 *
 * 这里的组件**强制** `danger` 时必须给 `description`——只问"确定吗"等于没确认。
 * 开发模式下会在开发模式警告。
 *
 * ## 与 useConfirm 的关系
 *
 * 本组件是**受控**用法（`open` + `onConfirm`），适合单处使用。
 * 多个互不相干的确认点（删除 KB / 删除文档 / 停用 Agent / 整包同步 / 重建索引）
 * 建议在应用层放**一个**实例 + 命令式调用，见 R4 的 `useConfirm`。
 */
export interface ConfirmDialogProps {
  open: boolean
  onClose: () => void
  onConfirm: () => void
  title: ReactNode
  /** 具体影响。`danger` 时**必填**。 */
  description?: ReactNode
  confirmText?: string
  cancelText?: string
  /** 危险操作：确认按钮变红。 */
  danger?: boolean
  /** 确认请求在途：按钮转圈并禁用，防止重复提交。 */
  loading?: boolean
  /**
   * 关闭时是否需要二次拦截。
   * 表单类弹窗（误点遮罩/ESC 会丢输入）传 false，依赖 `closeOnOverlayClick` 即可；
   * 这里控制的是**用户点了取消之后**要不要拦一下，通常不需要。
   */
  closeOnOverlayClick?: boolean
  /** 传给根节点的 testid 前缀（不硬编码业务 testid，由调用方给）。 */
  testId?: string
}

export function ConfirmDialog({
  open,
  onClose,
  onConfirm,
  title,
  description,
  confirmText = '确定',
  cancelText = '取消',
  danger = false,
  loading = false,
  closeOnOverlayClick = true,
  testId
}: ConfirmDialogProps): ReactElement {
  const [warned, setWarned] = useState(false)

  // 危险操作没有说明影响：开发模式提醒一次（用 state 去重，避免每次渲染都喊）
  if (danger && !description && !warned && open && import.meta.env.DEV) {
    setWarned(true)
    console.warn(
      '[ConfirmDialog] 危险操作没有写 description。' +
        '只问"确定吗"等于没确认——用户需要知道会失去什么。'
    )
  }

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={title}
      label={typeof title === 'string' ? title : undefined}
      // 危险确认框点遮罩就取消是对的（用户还有反悔余地），
      // 但**请求在飞时不能关**——否则用户会以为取消了，实际删除还在跑。
      closeOnOverlayClick={closeOnOverlayClick && !loading}
      footer={
        <>
          <Button variant="ghost" onClick={onClose} disabled={loading} data-testid="confirm-cancel">
            {cancelText}
          </Button>
          <Button
            variant={danger ? 'danger' : 'primary'}
            onClick={onConfirm}
            loading={loading}
            data-testid={testId ? `${testId}-submit` : 'confirm-submit'}
          >
            {confirmText}
          </Button>
        </>
      }
    >
      <div data-testid={testId ? `${testId}-body` : 'confirm-body'}>
        {description ? (
          <p className="m-0 text-[var(--nx-text-sm)] text-[var(--nx-color-text-secondary)] break-words">
            {description}
          </p>
        ) : null}
      </div>
    </Dialog>
  )
}
