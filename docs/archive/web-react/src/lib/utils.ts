import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'

/**
 * shadcn/ui 约定的类名合并工具。
 *
 * `clsx` 处理条件类名，`twMerge` 处理**冲突**——后者是关键：
 * 组件内部写 `px-4`，调用方传 `px-8` 时若不合并，两者都在 class 里，
 * 胜出与否取决于 CSS 顺序而不是调用方意图。`twMerge` 让后者覆盖前者。
 *
 * 没有它，shadcn 组件的 variant 覆盖会时灵时不灵。
 */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs))
}
