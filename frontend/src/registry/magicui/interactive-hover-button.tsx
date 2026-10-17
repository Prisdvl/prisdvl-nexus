import type { ButtonHTMLAttributes, ReactNode } from 'react'
import { ArrowRight } from 'lucide-react'
import { cn } from '@/lib/utils'
import './interactive-hover-button.css'

export interface InteractiveHoverButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  children?: ReactNode
}

/**
 * Magic UI InteractiveHoverButton
 * @see https://magicui.design/docs/components/interactive-hover-button
 *
 * 本仓库无 Tailwind，样式落在同目录 CSS，语义对齐官方：圆点放大 + 文案滑出 + 箭头层滑入。
 */
export function InteractiveHoverButton({
  children = 'Button',
  className,
  type = 'button',
  ...props
}: InteractiveHoverButtonProps) {
  return (
    <button type={type} className={cn('ihb', className)} {...props}>
      <span className="ihb__idle">
        <span className="ihb__dot" aria-hidden />
        <span className="ihb__label">{children}</span>
      </span>
      <span className="ihb__hover" aria-hidden>
        <span>{children}</span>
        <ArrowRight size={17} strokeWidth={2.2} />
      </span>
    </button>
  )
}
