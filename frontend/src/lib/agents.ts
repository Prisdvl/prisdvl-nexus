/**
 * Agent 列表缓存（决策 4：存 id，展示名从这里映射）
 *
 * ## 为什么存 id 而不是名字
 *
 * 后端 `V12__agent.sql` 对 `builtin` 的注释写明：
 * > `1 = 系统内置（默认助手）：不可删除、不可停用，**可改名改提示词**`
 *
 * 也就是说**名字是可变值**，把它当主键用会出现"改名后历史会话显示新旧混杂"。
 * 所以 `SessionItem.agentId` 存 id，`null` 表示"用内置默认 Agent"
 * ——这与后端 `ConversationResponse.agentId` 的语义**完全一致**（实测该字段可为 null）。
 *
 * ## 为什么不需要后端冗余 agentName
 *
 * `GET /api/agents` 已存在，且`AgentService.list:98` 在**读路径上**显式调了
 * `resolveDefault(userId)`，保证列表至少有一条（内置默认）且置顶
 * （`orderByDesc(AgentPO::getBuiltin)`）。所以前端自己拉一次即可，
 * 不需要后端在会话响应里冗余名字。
 *
 * ## 兼容性
 *
 * 旧的 `SessionItem.agent` 是自由文本（用户直接改名，不落库）。
 * 本模块的 `resolveAgentName` 会把无法匹配到 id 的旧值当**展示名**兜底，
 * 这样老localStorage 快照（虽然 STORE_VERSION 已升到 2 会被丢弃）与
 * mock 模式的种子会话都还能正常显示。
 */
import { useSyncExternalStore } from 'react'
import { api, USE_MOCK, type Agent } from '@/lib/api'
import { AGENTS } from '@/data'

/** `agentId: null` 对应的展示名 —— 与后端 `AgentPrompts.BUILTIN_NAME` 保持一致 */
export const DEFAULT_AGENT_NAME = '默认助手'

type Listener = () => void
const listeners = new Set<Listener>()

/** id → Agent 实体。key 是字符串雪花 id */
let cache = new Map<string, Agent>()
let loaded = false
let loading = false

const emit = () => listeners.forEach((l) => l())
const subscribe = (l: Listener) => {
  listeners.add(l)
  return () => listeners.delete(l)
}

/** 快照必须引用稳定，否则 useSyncExternalStore 会判定每次都变了 → 无限重渲染 */
let cached = { list: [] as Agent[], loaded, loading }
const refreshSnapshot = () => {
  cached = { list: [...cache.values()], loaded, loading }
}
const emitChange = () => {
  refreshSnapshot()
  emit()
}

/**
 * 拉取 Agent 列表（幂等：已加载过就返回，不重复打接口）。
 *
 * ⚠️ 失败**不抛异常**：Agent 选择器只是个辅助 UI，
 * 拉不到时回退到演示列表（mock）或空列表，不该让整页崩掉。
 */
export async function ensureAgents(): Promise<void> {
  if (loaded || loading) return
  if (USE_MOCK) {
    // mock 模式：把演示数据映射成后端形状，保持单一数据路径
    cache = new Map(
      AGENTS.map((a, i) => [
        `seed_agent_${i + 1}`,
        {
          id: `seed_agent_${i + 1}`,
          code: a.name,
          name: a.name,
          description: a.desc,
          builtin: a.name === DEFAULT_AGENT_NAME,
          status: 'PUBLISHED',
        },
      ]),
    )
    loaded = true
    emitChange()
    return
  }
  loading = true
  emitChange()
  try {
    const page = await api.agents.list(1, 100)
    cache = new Map(page.list.map((a) => [a.id, a]))
    loaded = true
  } catch {
    // 保持 loaded=false：下次进对话页还会再试一次
  } finally {
    loading = false
    emitChange()
  }
}

export const agentStore = {
  /** 全部 Agent（内置默认在列表第一位） */
  list: (): Agent[] => [...cache.values()],
  /** 展示名：`null` → 「默认助手」；未知 id → 回退成 id 本身而不是空白 */
  nameOf: (agentId: string | null): string => {
    if (agentId === null) return DEFAULT_AGENT_NAME
    return cache.get(agentId)?.name ?? agentId
  },
  loaded: () => loaded,
  /** 清空（登出时用：Agent 列表是**按用户**的，串号就是越权展示） */
  clear: () => {
    cache = new Map()
    loaded = false
    loading = false
    emitChange()
  },
}

export function useAgents() {
  const snap = useSyncExternalStore(subscribe, () => cached, () => cached)
  return { agents: snap.list, loaded: snap.loaded, loading: snap.loading, nameOf: agentStore.nameOf }
}