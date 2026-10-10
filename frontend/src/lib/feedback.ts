/**
 * 回答反馈 store（P0-3）
 *
 * 目的：让"改进"有数据来源。没有反馈入口，就永远只能靠感觉调提示词/检索。
 *
 * 数据设计原则（刻意保持字段少）：
 * 一条记录只回答一个四选一 —— **差评是检索差、提示词差、模型差，还是工具挂了**。
 * 再多字段就收不上来了。落到 localStorage（键 nx-proto-feedback），
 * 由「设置 → 数据导出」统一导出；接后端时把 append/list/clear 换成接口即可。
 */
import { useSyncExternalStore } from 'react'

export type FeedbackVote = 'up' | 'down'
/** 👎 时追问的原因（可不填） */
export type FeedbackReason = '事实错误' | '引用不对' | '没答到点' | '太啰嗦' | '格式差' | '其他'

export interface FeedbackEntry {
  /** 回答消息 id（同一消息只保留最新一次表态） */
  /* 字符串 id：与 SessionItem.id / ChatMsg.id 一致（后端雪花 ID不能转 Number）。
     ⚠️ 反馈会被导出成 JSON / CSV —— 字符串化后 CSV 里带引号，
        这是正确行为（数字 id 被Excel 当数字读会丢精度）。 */
  msgId: string
  sessionId: string
  vote: FeedbackVote
  /** 只有 👎 才会带原因 */
  reason?: FeedbackReason
  /** 归因到"哪套配置" */
  model?: string
  effort?: string
  /** 归因到"哪个 Agent" */
  agent: string
  /** 检索侧：绑了几个库、实际引用了几篇 —— 用来区分检索问题还是生成问题 */
  kbCount: number
  citedCount: number
  /** 工具侧：是不是工具失败导致的差评 */
  tools: { name: string; status: string; ms?: number }[]
  /** 体验侧：首个进展 / 总耗时 */
  firstProgressMs?: number
  totalMs?: number
  createdAt: string
}

const KEY = 'nx-proto-feedback'
type Listener = () => void
const listeners = new Set<Listener>()

const load = (): FeedbackEntry[] => {
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return []
    const list = JSON.parse(raw) as FeedbackEntry[]
    return Array.isArray(list) ? list : []
  } catch {
    return []
  }
}

let entries: FeedbackEntry[] = load()
/* 快照引用必须稳定，否则 useSyncExternalStore 会无限重渲染 */
let cached: FeedbackEntry[] = entries
const emit = () => {
  cached = entries
  try { localStorage.setItem(KEY, JSON.stringify(entries)) } catch { /* 配额满则仅内存 */ }
  listeners.forEach((l) => l())
}
const subscribe = (l: Listener) => {
  listeners.add(l)
  return () => listeners.delete(l)
}

export const feedbackStore = {
  snapshot: () => cached,
  list: () => entries,

  /** 记一条反馈；同一条回答重复表态会**覆盖**（用户改主意不应该算两条） */
  record: (entry: FeedbackEntry) => {
    entries = [...entries.filter((e) => e.msgId !== entry.msgId), entry]
    emit()
  },

  /** 撤销表态 */
  remove: (msgId: string) => {
    entries = entries.filter((e) => e.msgId !== msgId)
    emit()
  },

  clear: () => {
    entries = []
    try { localStorage.removeItem(KEY) } catch { /* 忽略 */ }
    emit()
  },

  /** 给「数据导出」用 */
  toJSON: () => JSON.stringify(entries, null, 2),
  toCSV: () => {
    const head = ['msgId', 'sessionId', 'vote', 'reason', 'model', 'effort', 'agent', 'kbCount', 'citedCount', 'tools', 'firstProgressMs', 'totalMs', 'createdAt']
    const rows = entries.map((e) => [
      e.msgId, e.sessionId, e.vote, e.reason ?? '', e.model ?? '', e.effort ?? '', e.agent,
      e.kbCount, e.citedCount, e.tools.map((t) => `${t.name}:${t.status}`).join('|'),
      e.firstProgressMs ?? '', e.totalMs ?? '', e.createdAt,
    ].map((v) => `"${String(v).replace(/"/g, '""')}"`).join(','))
    return [head.join(','), ...rows].join('\n')
  },
}

export function useFeedback() {
  const list = useSyncExternalStore(subscribe, feedbackStore.snapshot, feedbackStore.snapshot)
  return { entries: list, ...feedbackStore }
}
