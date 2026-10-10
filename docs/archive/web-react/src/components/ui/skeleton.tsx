import { cn } from '@/lib/utils'
import type { HTMLAttributes } from 'react'

/**
 * Skeleton —— 骨架屏。
 *
 * ## 什么时候用它、什么时候用 Spinner
 *
 * | 场景 | 用什么 | 理由 |
 * | --- | --- | --- |
 * | 列表/卡片首次加载 | **Skeleton** | 形状已知，先摆出结构，页面不跳 |
 * | 面板内等待一小段 | Spinner | 区域小，骨架反而显得啰嗦 |
 * | 整页首次加载 | 页面级 Skeleton | 不用全屏遮罩（遮罩挡住用户已看到的内容） |
 * | 点击后等结果 | Spinner 放在按钮里 | 见 `Button` 的 loading |
 *
 * Vue 侧的问题是"没等过程"（用 `isUploading` 一个布尔值管所有文件），
 * 骨架只是表现层，真正的逐文件状态属 R7。
 */
export function Skeleton({ className, ...rest }: HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      aria-hidden="true"
      className={cn('animate-pulse rounded-[var(--nx-radius-sm)] bg-[var(--nx-color-surface-muted)]', className)}
      {...rest}
    />
  )
}

/** 若干行文字骨架。最后一行短一些，更像真实文本而不是等宽色块。 */
export function SkeletonText({
  lines = 3,
  className
}: {
  lines?: number
  className?: string
}): React.ReactElement {
  const n = Math.max(1, Math.min(10, lines))
  return (
    <div className={cn('flex flex-col gap-2', className)}>
      {Array.from({ length: n }, (_, i) => (
        <Skeleton
          key={i}
          className="h-3.5"
          // 末行 62%：真实的段落最后一行通常不满行
          {...(i === n - 1 ? { style: { width: '62%' } } : {})}
        />
      ))}
    </div>
  )
}

/** 一行"标题 + 副文本 + 右侧状态"的骨架，对应列表卡片的首屏。 */
export function SkeletonRow({ className }: { className?: string }): React.ReactElement {
  return (
    <div
      className={cn(
        'flex items-center gap-3 rounded-[var(--nx-radius-lg)] border border-[var(--nx-color-border)] p-4',
        className
      )}
    >
      <Skeleton className="h-9 w-9 rounded-[var(--nx-radius-md)]" />
      <div className="flex min-w-0 flex-1 flex-col gap-2">
        <Skeleton className="h-3.5 w-1/3" />
        <Skeleton className="h-3 w-1/2" />
      </div>
      <Skeleton className="h-6 w-14 rounded-full" />
    </div>
  )
}
