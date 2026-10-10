import { create } from 'zustand'
import { useCallback, type ReactElement } from 'react'
import { ConfirmDialog } from '@/components/common/ConfirmDialog'

/**
 * 命令式确认框。
 *
 * ## ⚠️ 为什么状态在**模块级单例**里，而不是 hook 的局部 state
 *
 * 第一版把 `pending` 放在 `useConfirm()` 的 `useState` 里。看着能用，实际上**完全失效**：
 *
 * ```tsx
 * const confirm = useConfirm()          // 页面：实例 A
 * // …
 * function ConfirmHost() { const confirm = useConfirm() }   // 宿主：实例 B
 * ```
 *
 * 两处 `useConfirm()` 是**两个互不相干的 hook 实例**。页面 `confirm()` 设置的是 A 的
 * `pending`，而 `ConfirmHost` 渲染的是 B 的 `pending`——**两者永不相连**，
 * 于是"点删除没反应"。（R6 的 E2E 抓到的就是这个。）
 *
 * 用模块级 Zustand store：任何地方调用 `useConfirm()` 拿到的都是**同一个** `confirm`。
 * 这也正是它该有的形态——确认框是**应用级单例**，像 `window.confirm` 一样。
 *
 * ## 为什么不直接用 `window.confirm`
 *
 * 三个理由：样式不可控、无法表达影响（"将删除 12 篇文档与 340 个切片"）、
 * 无法表达进行中（请求在飞时弹窗已消失，用户能再点一次，于是发两个删除请求）。
 *
 * ## 取消返回 `false`，不 reject
 *
 * Vue 侧用 `ElMessageBox.confirm()` 时**取消会 reject**，于是每处都要 try/catch
 * 包住才能避免未处理 promise 拒绝（Phase C 修过三处）。这里取消正常 resolve `false`。
 */
export interface ConfirmOptions {
  title: string
  /** 具体影响。`danger` 时必填——只问"确定吗"等于没确认。 */
  description?: string
  confirmText?: string
  cancelText?: string
  danger?: boolean
  /** 是否允许点遮罩关闭。表单类弹窗应设 false（误点会丢输入）。 */
  closeOnOverlayClick?: boolean
}

interface PendingConfirm extends ConfirmOptions {
  open: boolean
  loading: boolean
}

interface ConfirmState {
  pending: PendingConfirm | null
  /** 发起确认，返回用户的选择。 */
  confirm: (options: ConfirmOptions) => Promise<boolean>
  /** 内部：由宿主组件调用，落定结果并关闭。 */
  settle: (value: boolean) => void
}

export const useConfirmStore = create<ConfirmState>((set) => ({
  pending: null,

  confirm(options) {
    // 记住 resolver，等用户在弹窗里点了确定/取消再调用它。
    // 用模块级变量而不是 state：这个函数不是"状态"，放 state 会在 devtools 里变成噪音。
    let resolve: ((v: boolean) => void) | null = null
    const promise = new Promise<boolean>((r) => {
      resolve = r
    })
    resolverStack.push((v) => resolve?.(v))
    set({ pending: { ...options, open: true, loading: false } })
    return promise
  },

  settle(value) {
    // 先摘 resolver 再改 state：顺序反了会在 setState 触发的重渲染里
    // 又跑一遍 settle，导致"确认后弹窗又弹一次"
    const resolver = resolverStack.pop()
    set({ pending: null })
    resolver?.(value)
  }
}))

/**
 * 等待中的 resolver 栈。
 *
 * 用**栈**而不是单个变量：理论上可能同时有两次 confirm（虽然 UI 上不会），
 * 栈能保证 LIFO 地正确落定，不至于错配。
 */
const resolverStack: Array<(v: boolean) => void> = []

/**
 * 命令式确认函数。**任何组件调用都是同一个实例。**
 *
 * 用法：
 * ```tsx
 * const confirm = useConfirm()
 * if (!(await confirm({ title: '确认删除？', danger: true, description: '…' }))) return
 * await remove(id)
 * ```
 */
export function useConfirm(): ConfirmState['confirm'] {
  return useConfirmStore((s) => s.confirm)
}

/**
 * 全局确认框宿主。**在应用里挂一次**（`WorkspaceLayout` 已挂）。
 *
 * 单独导出成组件而不是让 `useConfirm` 自己渲染，是因为**hook 不能渲染 UI**——
 * 命令式 API 只能触发状态，渲染必须由组件完成。
 */
export function ConfirmHost(): ReactElement | null {
  const pending = useConfirmStore((s) => s.pending)
  const settle = useConfirmStore((s) => s.settle)
  const onClose = useCallback(() => settle(false), [settle])

  if (!pending?.open) return null
  return (
    <ConfirmDialog
      open={pending.open}
      title={pending.title}
      {...(pending.description !== undefined ? { description: pending.description } : {})}
      {...(pending.confirmText !== undefined ? { confirmText: pending.confirmText } : {})}
      {...(pending.cancelText !== undefined ? { cancelText: pending.cancelText } : {})}
      danger={pending.danger ?? false}
      loading={pending.loading}
      closeOnOverlayClick={pending.closeOnOverlayClick ?? true}
      onClose={onClose}
      onConfirm={() => settle(true)}
    />
  )
}
