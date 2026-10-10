/**
 * 项目（Projects）store
 *
 * 对齐 ChatGPT 的信息架构：会话可以归属到"项目"下，侧栏有独立的「项目」栏目。
 * 与 `lib/sessions` 一样是模块级 store + `useSyncExternalStore`，并持久化到 localStorage
 * （原型里没有后端，否则刷新就丢；真实实现会换成接口）。
 */
import { useSyncExternalStore } from 'react'

export interface Project {
  id: number
  name: string
  /** 建项目的时间描述（原型只用展示） */
  createdAt: string
}

const KEY = 'nx-proto-projects'
let idSeq = Date.now()

const load = (): Project[] => {
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return []
    const list = JSON.parse(raw) as Project[]
    return Array.isArray(list) ? list : []
  } catch {
    return []
  }
}

type Listener = () => void
const listeners = new Set<Listener>()
/* 快照必须引用稳定，否则 useSyncExternalStore 会无限重渲染 */
let projects: Project[] = load()
let cached = { projects, activeProjectId: null as number | null }
let activeProjectId: number | null = null

const persist = () => {
  try { localStorage.setItem(KEY, JSON.stringify(projects)) } catch { /* 忽略隐私模式等写入失败 */ }
}
const emit = () => {
  cached = { projects, activeProjectId }
  persist()
  listeners.forEach((l) => l())
}
const subscribe = (l: Listener) => {
  listeners.add(l)
  return () => listeners.delete(l)
}

export const projectStore = {
  snapshot: () => cached,
  list: () => projects,
  activeId: () => activeProjectId,

  add: (name: string): number => {
    const id = (idSeq += 1)
    projects = [...projects, { id, name: name.trim() || '未命名项目', createdAt: '刚刚' }]
    activeProjectId = id
    emit()
    return id
  },

  rename: (id: number, name: string) => {
    projects = projects.map((p) => (p.id === id ? { ...p, name: name.trim() || p.name } : p))
    emit()
  },

  remove: (id: number) => {
    projects = projects.filter((p) => p.id !== id)
    if (activeProjectId === id) activeProjectId = null
    emit()
  },

  /** 选中某个项目（侧栏据此过滤会话）；传 null 表示"全部会话" */
  setActive: (id: number | null) => {
    activeProjectId = id
    emit()
  },
}

export function useProjects() {
  const snap = useSyncExternalStore(subscribe, projectStore.snapshot, projectStore.snapshot)
  return { ...projectStore, projects: snap.projects, activeProjectId: snap.activeProjectId }
}
