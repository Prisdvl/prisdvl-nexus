import type { ReactElement, ReactNode } from 'react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

/**
 * StateError —— 「请求失败」。
 *
 * ## 它取代什么
 *
 * Vue 侧加载失败只有 `ElMessage.error('文档加载失败：…')`——**一个 3 秒消失的 toast**。
 * toast 消失后面板落到空态，于是**"加载失败"最终呈现给用户的是"这里没有数据"**。
 *
 * 本组件让失败**停在屏幕上**，并给一个能真正解决问题的动作（重试）。
 *
 * ## 与 toast 的分工
 *
 * | 场景 | 用什么 |
 * | --- | --- |
 * | 页面级 / 面板级加载失败 | **本组件**（常驻 + 重试） |
 * | 一次操作失败（保存、发布、删除） | `ElMessage.error` 类的 toast（一句即走） |
 *
 * 绝不能同时用两个——用户会看到同一件事被说两遍。
 * Vue 侧就有 5 处"拦截器弹一次 + catch 再弹一次"（Phase C 已消除）。
 */
export interface StateErrorProps {
  /** 一句失败。说明"什么失败了"，不是"出错了"。 */
  title?: string
  /** 真实原因。服务端返回什么就显示什么——用户判断要不要重试靠它。 */
  description?: ReactNode
  /** 重试按钮文案。 */
  retryLabel?: string
  /** 点击重试。**不传就没有重试按钮**。 */
  onRetry?: () => void
  /** 重试进行中（按钮转圈并禁用，防止重复点）。 */
  retrying?: boolean
  /**
   * 是否可重试。
   * 传 `false` 时不显示重试按钮——用于 404 / 403 这类"重试也没用"的失败。
   */
  retryable?: boolean
  variant?: 'page' | 'card' | 'inline'
}

const VARIANT_CLASS = {
  page: 'py-12 px-4 gap-2',
  card: 'py-8 px-4 gap-2',
  inline: 'py-4 px-2 gap-1'
} as const

export function StateError({
  title = '加载失败',
  description,
  retryLabel = '重试',
  onRetry,
  retrying = false,
  retryable = true,
  variant = 'card'
}: StateErrorProps): ReactElement {
  const showRetry = retryable && typeof onRetry === 'function'

  return (
    <div
      // role="alert"：读屏软件会**立刻**播报这��内容。
      // 错误状态不该安静地待在 DOM 里等用户去找——那和没提示一样。
      role="alert"
      data-testid="state-error"
      data-variant={variant}
      className={cn(
        'flex flex-col items-center justify-center text-center',
        VARIANT_CLASS[variant]
      )}
    >
      {/* 警告三角。用 stroke 而非 fill，和空态的文件夹图标视觉重量一致。 */}
      <svg
        aria-hidden="true"
        viewBox="0 0 20 20"
        className="mb-1 h-8 w-8 text-[var(--nx-color-danger)]"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.4"
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        <path d="M10 3.5 18 16.5H2L10 3.5Z" />
        <path d="M10 8v3.5" />
        <circle cx="10" cy="14" r="0.6" fill="currentColor" stroke="none" />
      </svg>

      <p className="m-0 font-[var(--nx-weight-medium)] text-[var(--nx-color-text)] text-[var(--nx-text-base)]">
        {title}
      </p>

      {description ? (
        <p className="mt-1 max-w-[52ch] text-[var(--nx-text-sm)] text-[var(--nx-color-text-secondary)] break-words">
          {description}
        </p>
      ) : null}

      {showRetry ? (
        <div className="mt-3">
          <Button
            variant="outline"
            size="sm"
            loading={retrying}
            onClick={onRetry}
            data-testid="state-error-retry"
          >
            {retryLabel}
          </Button>
        </div>
      ) : null}
    </div>
  )
}
