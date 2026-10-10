/**
 * 全局提示（原型级 store）
 *
 * 为什么不用内联提示：删除会话/文档这类操作需要「撤销」，
 * 而撤销入口必须**离开当前上下文也能点到**（列表行已经没了）。
 * 所以做成全局队列，由 ToastHost 统一渲染 SwipeToast（可侧滑关闭 + 引线倒计时）。
 */
import { useSyncExternalStore } from 'react'

export type ToastTone = 'info' | 'success' | 'error' | 'task'

export interface ToastItem {
  id: number
  tone: ToastTone
  title: string
  description?: string
  actionLabel?: string
  onAction?: () => void
  duration?: number
}

let seq = 0
let items: ToastItem[] = []
const listeners = new Set<() => void>()

const emit = () => listeners.forEach((l) => l())
const subscribe = (l: () => void) => {
  listeners.add(l)
  return () => listeners.delete(l)
}

function push(t: Omit<ToastItem, 'id'>): number {
  const id = ++seq
  items = [...items, { id, duration: t.tone === 'error' ? 6000 : 4000, ...t }]
  emit()
  return id
}

function dismiss(id: number) {
  items = items.filter((t) => t.id !== id)
  emit()
}

export const toast = {
  info: (title: string, description?: string) => push({ tone: 'info', title, description }),
  success: (title: string, description?: string) => push({ tone: 'success', title, description }),
  error: (title: string, description?: string) => push({ tone: 'error', title, description }),
  task: (title: string, description?: string) => push({ tone: 'task', title, description }),
  /** 删除类：带撤销 */
  undo: (title: string, description: string, onUndo: () => void) =>
    push({ tone: 'info', title, description, actionLabel: '撤销', onAction: onUndo, duration: 6000 }),
  dismiss,
}

export function useToasts(): ToastItem[] {
  return useSyncExternalStore(subscribe, () => items, () => items)
}