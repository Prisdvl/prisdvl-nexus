import { create } from 'zustand'
import {
  createAgent as createAgentApi,
  duplicateAgent as duplicateAgentApi,
  listAgents,
  listTools,
  removeAgent as removeAgentApi,
  updateAgent as updateAgentApi
} from '@/api/agent'
import type {
  Agent,
  AgentQuery,
  CreateAgentRequest,
  Tool,
  UpdateAgentRequest
} from '@/api/types/agent'
import { getErrorMessage, isApiError } from '@/lib/errors'

/**
 * Agent 状态。
 *
 * ## 为什么 Agent 值得一个全局 store，而"当前编辑中的表单"不值得
 *
 * 判据来自 Phase B §14.2：**跨页面共享且单一真相 → Store**。
 * Agent 清单在三处出现：Agents 列表页、Chat 顶部的 Agent 选择器、
 * 知识库详情页的"用哪个 Agent 提问"。三处各拉一次，就是三份可能不一致的副本。
 *
 * 而"新建 Agent 表单里正在输入的 description"——换页面就该丢弃，不该进 store。
 * 那种状态留在组件里。
 *
 * ## 明确不进来的东西
 * | 东西 | 归属 | 原因 |
 * | --- | --- | --- |
 * | `editingAgent` / 表单草稿 | R9 页面局部 | 弹窗关闭即销毁 |
 * | `testChatResult` | R9 页面局部 | 一次性回显，可能很大 |
 * | 侧栏折叠 | 组件 + localStorage | 与 Agent 无关 |
 */

interface AgentState {
  list: Agent[]
  tools: Tool[]
  loading: boolean
  error: string | null
  /** Agent 清单是否拉过。区分"没拉过"与"拉完了但为空"。 */
  loaded: boolean
  toolsLoaded: boolean
}

interface AgentActions {
  fetchList: (options?: {
    force?: boolean
    query?: AgentQuery
    signal?: AbortSignal
  }) => Promise<void>
  fetchTools: (options?: { force?: boolean; signal?: AbortSignal }) => Promise<void>
  create: (payload: CreateAgentRequest) => Promise<Agent | null>
  update: (id: string, payload: UpdateAgentRequest) => Promise<Agent | null>
  remove: (id: string) => Promise<boolean>
  duplicate: (id: string) => Promise<Agent | null>
  /** 退出登录 / 换账号时清空。**必须**调，否则会看到别人的 Agent。 */
  reset: () => void
}

export const useAgentStore = create<AgentState & AgentActions>()((set, get) => ({
  list: [],
  tools: [],
  loading: false,
  error: null,
  loaded: false,
  toolsLoaded: false,

  async fetchList({ force = false, query, signal } = {}) {
    const { loading, loaded } = get()
    if (loading) return
    if (loaded && !force) return

    set({ loading: true, error: null })
    try {
      const res = await listAgents(query ?? {}, signal)
      set({ list: res.list, loaded: true, loading: false })
    } catch (e) {
      if (isApiError(e) && e.isAborted) {
        set({ loading: false })
        return
      }
      set({ error: getErrorMessage(e), loading: false })
    }
  },

  async fetchTools({ force = false, signal } = {}) {
    const { toolsLoaded } = get()
    if (toolsLoaded && !force) return
    try {
      const tools = await listTools(signal)
      set({ tools, toolsLoaded: true })
    } catch (e) {
      if (isApiError(e) && e.isAborted) return
      // 工具清单拿不到不阻断页面：Agent 列表仍可用，只是编辑器里没有可勾选项。
      // 这与 `initialize()` 里"知识库不可用仍算登录"的判断是同一个取舍。
      set({ error: getErrorMessage(e) })
    }
  },

  async create(payload) {
    try {
      const created = await createAgentApi(payload)
      // 直接并入列表而不是重新拉一次：省一个往返，且用户立刻能在列表里看到自己刚建的。
      set({ list: [created, ...get().list] })
      return created
    } catch (e) {
      set({ error: getErrorMessage(e) })
      return null
    }
  },

  async update(id, payload) {
    try {
      const updated = await updateAgentApi(id, payload)
      set({ list: get().list.map((a) => (a.id === id ? updated : a)) })
      return updated
    } catch (e) {
      set({ error: getErrorMessage(e) })
      return null
    }
  },

  async remove(id) {
    try {
      await removeAgentApi(id)
      set({ list: get().list.filter((a) => a.id !== id) })
      return true
    } catch (e) {
      set({ error: getErrorMessage(e) })
      return false
    }
  },

  async duplicate(id) {
    try {
      const copy = await duplicateAgentApi(id)
      set({ list: [copy, ...get().list] })
      return copy
    } catch (e) {
      set({ error: getErrorMessage(e) })
      return null
    }
  },

  reset() {
    set({
      list: [],
      tools: [],
      loaded: false,
      toolsLoaded: false,
      error: null,
      loading: false
    })
  }
}))
