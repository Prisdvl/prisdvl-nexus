import { forwardRef, type ButtonHTMLAttributes } from 'react'
import { cn } from '@/lib/utils'
import { Spinner } from '@/components/ui/Spinner'

/**
 * Button —— 全站唯一的按钮原语。
 *
 * ## 为什么 loading 时不换成 spinner 占位
 *
 * 常见做法是 `loading ? <Spinner/> : children`，那会让按钮宽度在切换瞬间变化，
 * **旁边的元素会跳一下**。任务书 §九 明确要求"loading 不能改变按钮尺寸导致页面跳动"。
 *
 * 这里用 `visibility: hidden` 保留 children 的占位宽度，spinner 绝对定位叠在上面。
 * 按钮尺寸恒定，布局不动。
 *
 * ## 为什么 variant 用对象而不是模板字符串
 *
 * 早期写法是 `'px-4 py-2 rounded-md ' + variantClass[variant]`。那样拼出来的类名
 * Tailwind 4 的扫描器**看不到完整字面量**，生产构建会把这些类裁掉（按钮全没样式）。
 * 对象字面量里的完整类名是能扫到的。变体表与类名一一对应，不做运行时拼接。
 */

export type ButtonVariant = 'primary' | 'secondary' | 'outline' | 'ghost' | 'danger'
export type ButtonSize = 'sm' | 'md' | 'lg'

const VARIANT_CLASS: Record<ButtonVariant, string> = {
  primary: 'bg-[var(--nx-color-primary)] text-[var(--nx-color-on-primary)] hover:bg-[var(--nx-color-primary-hover)]',
  secondary:
    'bg-[var(--nx-color-surface-muted)] text-[var(--nx-color-text)] hover:bg-[var(--nx-color-border)]',
  outline:
    'border border-[var(--nx-color-border-strong)] bg-[var(--nx-color-surface)] text-[var(--nx-color-text)] hover:bg-[var(--nx-color-surface-muted)]',
  ghost: 'text-[var(--nx-color-text-secondary)] hover:bg-[var(--nx-color-surface-muted)]',
  danger:
    'bg-[var(--nx-color-danger)] text-[var(--nx-color-on-primary)] hover:bg-[var(--nx-color-danger-hover)]'
}

const SIZE_CLASS: Record<ButtonSize, string> = {
  // 高度统一用固定值（h-8 / h-9 / h-10），不用 py-*：固定高度才能保证同一行按钮对齐。
  sm: 'h-8 px-3 text-[var(--nx-text-sm)] gap-1.5',
  md: 'h-9 px-4 text-[var(--nx-text-base)] gap-2',
  lg: 'h-10 px-5 text-[var(--nx-text-base)] gap-2'
}

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant
  size?: ButtonSize
  /** 处理中。**尺寸不变**（见上文），且自动禁用。 */
  loading?: boolean
  /**
   * 撑满容器宽度。
   *
   * 移动端（任务书 §54 要测 1024×768）上，表单里的提交按钮占满宽度比挤在右边好按。
   */
  fullWidth?: boolean
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  {
    variant = 'primary',
    size = 'md',
    loading = false,
    fullWidth = false,
    className,
    children,
    disabled,
    type = 'button',
    ...rest
  },
  ref
) {
  return (
    <button
      ref={ref}
      type={type}
      disabled={disabled === true || loading}
      aria-busy={loading || undefined}
      className={cn(
        'relative inline-flex shrink-0 items-center justify-center rounded-[var(--nx-radius-md)]',
        'font-[var(--nx-weight-medium)] whitespace-nowrap select-none',
        'transition-[background-color,color,border-color,opacity] duration-[var(--nx-dur-fast)]',
        // 键盘焦点环由 globals.css 的 :focus-visible 统一提供，这里不加 focus-visible:ring
        'disabled:pointer-events-none disabled:opacity-[0.55]',
        VARIANT_CLASS[variant],
        SIZE_CLASS[size],
        fullWidth && 'w-full',
        className
      )}
      {...rest}
    >
      {/*
        spinner 绝对定位叠在内容之上；children 用 visibility:hidden 继续占位。
        这样按钮宽度恒定，切换 loading 不引起任何布局位移。
      */}
      {loading ? (
        <span className="absolute inset-0 grid place-items-center">
          <Spinner />
        </span>
      ) : null}
      <span className={cn('inline-flex items-center gap-2', loading && 'invisible')}>
        {children}
      </span>
    </button>
  )
})
