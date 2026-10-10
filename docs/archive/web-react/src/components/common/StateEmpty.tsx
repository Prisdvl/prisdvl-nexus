import { useEffect, type ReactElement, type ReactNode } from 'react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

/**
 * StateEmpty —— 「成功但没有数据」。
 *
 * ## 与 StateError 的界线只有一条
 *
 * > **服务端有没有说成功。**
 *
 * 成功但数据为 0 → 用这个；请求失败 → 用 `StateError`。
 * 二者的文案**不允许互相越界**（"没有数据"不能用来表达"加载失败"）。
 *
 * 这条界线在 Vue 侧是被违反过的：文档加载失败只有一个 3 秒消失的 toast，
 * toast 一消失面板落到 `v-if="length === 0"` 的"没有匹配的文档"——
 * **"加载失败"最终呈现给用户的是"这里没有数据"**（Phase A 审计 §6.3）。
 *
 * ## 为什么"必须有下一步动作"是被强制的
 *
 * 光有一句"还没有知识库"，用户是卡住的——他不知道该点什么。
 * 开发模式下没给 action 会发出警告：**设计规范由组件 API 兜住**，不靠自觉。
 */
export interface StateEmptyProps {
  /** 一句状态。必须是"用户能懂的话"，不是"暂无数据"。 */
  title: string
  /** 补充说明：为什么空、接下来会发生什么。 */
  description?: ReactNode
  /** 下一步动作。**应该提供**。 */
  action?: ReactNode
  /**
   * 尺寸。
   *
   * - `page`：整页为空（新用户的知识库列表）
   * - `card`：卡片 / 面板内为空
   * - `inline`：行内一小块
   */
  variant?: 'page' | 'card' | 'inline'
  /**
   * 主动作按钮的 props（文案、onClick、testid）。
   * 传 `action` 字符串 + `actionProps` 是最常见的用法。
   */
  actionProps?: { label: string; onClick?: () => void; testId?: string; disabled?: boolean }
}

const ICON_PATHS: Record<string, string> = {
  folder: 'M3 7a2 2 0 0 1 2-2h3.5l1.5 2H17a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7Z',
  document: 'M6 3h7l5 5v13a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1Z',
  chat: 'M4 5a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H9l-4 3v-3H6a2 2 0 0 1-2-2V5Z'
}

const VARIANT_CLASS = {
  page: 'py-12 px-4 gap-2',
  card: 'py-8 px-4 gap-2',
  inline: 'py-4 px-2 gap-1'
} as const

export function StateEmpty({
  title,
  description,
  action,
  actionProps,
  variant = 'card'
}: StateEmptyProps): ReactElement {
  useEffect(() => {
    if (!action && !actionProps && import.meta.env.DEV) {
      // 开发期提醒，不在生产打扰用户
      console.warn(
        `[StateEmpty] "${title}" 没有提供下一步动作。` +
          '空态必须给用户一个可点的下一步，否则他不知道该点什么（Phase B §13.2）。'
      )
    }
  }, [title, action, actionProps])

  // 默认给一个文件夹图标（空态最常见的场景）。不要图标库：三个路径画出来
  // 体积远小于引入整个 icon 包，且空态图标不需要交互。
  const icon = ICON_PATHS.folder ?? ''

  return (
    <div
      data-testid="state-empty"
      data-variant={variant}
      className={cn(
        'flex flex-col items-center justify-center text-center',
        VARIANT_CLASS[variant]
      )}
    >
      <svg
        aria-hidden="true"
        viewBox="0 0 20 20"
        className="mb-1 h-8 w-8 text-[var(--nx-color-text-muted)]"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.4"
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        <path d={icon} />
      </svg>

      <p className="m-0 font-[var(--nx-weight-medium)] text-[var(--nx-color-text)] text-[var(--nx-text-base)]">
        {title}
      </p>

      {description ? (
        <p className="mt-1 max-w-[46ch] text-[var(--nx-text-sm)] text-[var(--nx-color-text-secondary)]">
          {description}
        </p>
      ) : null}

      {actionProps ? (
        <div className="mt-3">
          <Button
            onClick={actionProps.onClick}
            disabled={actionProps.disabled}
            data-testid={actionProps.testId}
          >
            {actionProps.label}
          </Button>
        </div>
      ) : action ? (
        <div className="mt-3">{action}</div>
      ) : null}
    </div>
  )
}
