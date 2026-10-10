import type { ReactElement } from 'react'
import { cn } from '@/lib/utils'

/**
 * Spinner —— 加载指示器。
 *
 * 刻意用 `border` 画圈而不是 SVG 旋转图标：体积小、无额外资源、
 * 且可以直接用 `currentColor` 跟随所在文字的颜色（按钮里是反白、面板里是灰）。
 */
export interface SpinnerProps {
  /** 直径（px）。默认 16，与 sm 按钮的文字同高。 */
  size?: number
  className?: string
}

export function Spinner({ size = 16, className }: SpinnerProps): ReactElement {
  return (
    <span
      role="status"
      aria-label="加载中"
      className={cn('inline-block shrink-0 animate-spin rounded-full', className)}
      style={{
        width: size,
        height: size,
        // 2px 的边框、1.5px 的浅色"缺口"：缺口转一圈就是加载感
        border: '2px solid currentColor',
        borderTopColor: 'transparent'
      }}
    />
  )
}
