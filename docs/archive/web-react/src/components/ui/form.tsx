import {
  forwardRef,
  useId,
  type InputHTMLAttributes,
  type LabelHTMLAttributes,
  type ReactElement,
  type ReactNode,
  type SelectHTMLAttributes,
  type TextareaHTMLAttributes
} from 'react'
import { cn } from '@/lib/utils'

/**
 * 表单原语：Field / Input / Textarea / Select。
 *
 * ## 为什么要一个 Field 包裹层
 *
 * Vue 版的表单每处都自己写 label + 错误提示，于是**同一份「标签在上、错误在下、
 * 错误时 aria-describedby 指向错误」的结构被复制了十几次**——其中几处漏了
 * `aria-describedby`，读屏用户就听不到"这里填错了"。
 *
 * `Field` 把这套结构固化成组件：`useId` 生成的 id 把 label / 控件 / 描述 / 错误
 * 四者串起来，调用方**不可能漏掉**关联关系。
 *
 * 任务书 §17 要求 label / description / error / required / disabled / loading 统一，
 * 就在这一层落实。
 */

export interface FieldProps {
  label?: ReactNode
  /** 补充说明。挂 label 的 `aria-describedby`，读屏会读出来。 */
  description?: ReactNode
  /** 错误信息。**有值时控件进入 invalid 态**。 */
  error?: ReactNode
  required?: boolean
  /** 控件的 props 单独传进来，让 Field 能把 id / aria 关联注入。 */
  children: (props: {
    id: string
    'aria-invalid': boolean | undefined
    'aria-describedby': string | undefined
    'aria-required': true | undefined
  }) => ReactNode
}

export function Field({
  label,
  description,
  error,
  required,
  children
}: FieldProps): ReactElement {
  // useId 而不是自增序号：同一个表单渲染两次也不会撞 id（SPA 里 id 撞了，
  // label 的 for 就会指到别的控件上——这类 bug 极难从界面看出来）
  const id = useId()
  const descId = description ? `${id}-desc` : undefined
  const errId = error ? `${id}-err` : undefined
  const describedBy = [errId, descId].filter(Boolean).join(' ') || undefined

  return (
    <div className="flex flex-col gap-1.5">
      {label ? (
        <label
          htmlFor={id}
          className={cn(
            'text-[var(--nx-text-sm)] font-[var(--nx-weight-medium)]',
            required && 'after:ml-0.5 after:text-[var(--nx-color-danger)] after:content-["*"]'
          )}
        >
          {label}
        </label>
      ) : null}

      {children({
        id,
        'aria-invalid': error ? true : undefined,
        'aria-describedby': describedBy,
        'aria-required': required ? true : undefined
      })}

      {error ? (
        <p id={errId} role="alert" className="text-[var(--nx-text-sm)] text-[var(--nx-color-danger)]">
          {error}
        </p>
      ) : null}

      {description && !error ? (
        <p id={descId} className="text-[var(--nx-text-sm)] text-[var(--nx-color-text-muted)]">
          {description}
        </p>
      ) : null}
    </div>
  )
}

/** 输入框 / 文本域 / 下拉的共享外观。 */
const CONTROL_CLASS =
  'w-full rounded-[var(--nx-radius-md)] border bg-[var(--nx-color-surface)] px-3 ' +
  'text-[var(--nx-text-base)] text-[var(--nx-color-text)] ' +
  'placeholder:text-[var(--nx-color-text-muted)] ' +
  'transition-[border-color,box-shadow] duration-[var(--nx-dur-fast)] ' +
  'disabled:cursor-not-allowed disabled:opacity-[0.55]'

const CONTROL_STATE_CLASS = (invalid?: boolean): string =>
  invalid
    ? 'border-[var(--nx-color-danger)] focus:border-[var(--nx-color-danger)]'
    : 'border-[var(--nx-color-border-strong)] focus:border-[var(--nx-color-primary)]'

export type InputProps = InputHTMLAttributes<HTMLInputElement>

export const Input = forwardRef<HTMLInputElement, InputProps>(function Input(
  { className, ...rest },
  ref
) {
  return (
    <input
      ref={ref}
      className={cn(
        CONTROL_CLASS,
        CONTROL_STATE_CLASS(rest['aria-invalid'] === true || rest['aria-invalid'] === 'true'),
        'h-9',
        className
      )}
      {...rest}
    />
  )
})

export type TextareaProps = TextareaHTMLAttributes<HTMLTextAreaElement>

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaProps>(function Textarea(
  { className, rows = 4, ...rest },
  ref
) {
  return (
    <textarea
      ref={ref}
      rows={rows}
      className={cn(
        CONTROL_CLASS,
        CONTROL_STATE_CLASS(rest['aria-invalid'] === true || rest['aria-invalid'] === 'true'),
        // resize-y 而不是 resize：竖向可调、横向固定。
        // 横向可调会让长 prompt 撑破布局，是最常见的表单破版原因。
        'resize-y py-2 leading-[var(--nx-leading-normal)]',
        className
      )}
      {...rest}
    />
  )
})

/**
 * Select。
 *
 * **刻意用原生 `<select>`，不用自绘下拉。**理由：
 * 原生在移动端会调起系统选择器（体验远好于自绘），键盘与读屏完全可用，
 * 且零 JS。Phase B 要求的"知识库多选"需要多选控件，那是后续阶段的判断。
 */
export type SelectOption = { value: string; label: string; disabled?: boolean }

export interface SelectProps extends Omit<SelectHTMLAttributes<HTMLSelectElement>, 'children'> {
  options: SelectOption[]
  /** 右侧的下拉箭头。原生 select 的 appearance 关闭后需要自己画。 */
  showChevron?: boolean
}

export const Select = forwardRef<HTMLSelectElement, SelectProps>(function Select(
  { className, options, showChevron = true, ...rest },
  ref
) {
  return (
    <div className="relative">
      <select
        ref={ref}
        className={cn(
          CONTROL_CLASS,
          CONTROL_STATE_CLASS(rest['aria-invalid'] === true || rest['aria-invalid'] === 'true'),
          'h-9 appearance-none pr-9',
          className
        )}
        {...rest}
      >
        {options.map((o) => (
          <option key={o.value} value={o.value} disabled={o.disabled}>
            {o.label}
          </option>
        ))}
      </select>
      {showChevron ? (
        <svg
          aria-hidden="true"
          viewBox="0 0 20 20"
          className="pointer-events-none absolute top-1/2 right-3 h-4 w-4 -translate-y-1/2 text-[var(--nx-color-text-muted)]"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.6"
        >
          <path d="M6 8l4 4 4-4" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      ) : null}
    </div>
  )
})

/** 复用的 label 类型，供只需要 label 样式的场景。 */
export type { LabelHTMLAttributes }
