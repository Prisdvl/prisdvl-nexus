import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  createAgent,
  deleteAgent,
  duplicateAgent,
  listAgents,
  setAgentStatus,
  testChat,
  updateAgent,
  type AgentPayload,
  type AgentResource,
  type AgentStatus,
  type AgentTestResult
} from '@/api/agents'
import { listTools, type ToolResource } from '@/api/tools'

/**
 * Agent 状态 —— 跨页面共享的那一份真相。
 *
 * ## 为什么它该是 Store（Phase B §14.2 的判据）
 *
 * 实测（Phase A §4）：**Agent 列表被 2 处各自拉取**（`AgentsView` 管理页、
 * `AgentView` 对话页的 Agent 选择器），**工具列表同样被 2 处各自拉取**
 * （管理页的配置弹窗、对话页）。四处各有一个 `ref([])`。
 *
 * 真实后果：新建/发布一个 Agent 之后切到对话页，它**还没出现**
 * （对话页要重新拉一次才看见），而用户的直觉是"我刚发布了"。
 *
 * ## 刻意**不**放进来的东西（Phase C §十）
 *
 * | 数据 | 归属 | 原因 |
 * | --- | --- | --- |
 * | 当前会话的消息 | View 局部 | 离开页面就该清掉；进 Store 会让"退出后消息还在"变成 bug 而非特性 |
 * | 会话列表 | View 局部 | 同上（它属于对话页，不属于 Agent 域） |
 * | 正在跑的试跑结果 | View 局部 | 一次性的操作回显 |
 * | 配置弹窗的表单草稿 | View 局部 | 弹窗关闭即销毁 |
 *
 * 判别式：**"换一个页面还该不该看见它"**。不该，就留在 View。
 */
export const useAgentStore = defineStore('agent', () => {
  /* ---------------- Agent 列表 ---------------- */

  const list = ref<AgentResource[]>([])
  const loading = ref(false)
  const error = ref<string>('')

  /** 与 knowledgeStore 同理：区分"没拉过"与"拉完了"，免得每次进页面都重拉。 */
  const loaded = ref(false)

  async function fetchList(force = false): Promise<void> {
    if (loading.value) return
    if (loaded.value && !force) return
    loading.value = true
    error.value = ''
    try {
      const res = await listAgents()
      list.value = res.list
      loaded.value = true
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Agent 加载失败'
    } finally {
      loading.value = false
    }
  }

  /* ---------------- 工具列表 ---------------- */

  const tools = ref<ToolResource[]>([])
  const toolsLoading = ref(false)
  const toolsLoaded = ref(false)

  async function fetchTools(force = false): Promise<void> {
    if (toolsLoading.value) return
    if (toolsLoaded.value && !force) return
    toolsLoading.value = true
    try {
      tools.value = await listTools()
      toolsLoaded.value = true
    } catch (e) {
      error.value = e instanceof Error ? e.message : '工具列表加载失败'
    } finally {
      toolsLoading.value = false
    }
  }

  /* ---------------- 派生 ---------------- */

  /**
   * 可被对话页选用的 Agent：**只有已发布的**。
   *
   * 草稿与已停用的都不该出现在选择器里——这是"按状态过滤"的核心断言
   * （`verify-agents.mjs` 的第 ⑥/⑩ 两条腿就在验它）。
   */
  const usable = computed(() => list.value.filter((a) => a.status === 'PUBLISHED'))

  /** 内置 Agent 永远不可删、不可停用（它是不选 Agent 时的兜底）。 */
  const builtin = computed(() => list.value.filter((a) => a.builtin))

  function byId(id: string): AgentResource | undefined {
    return list.value.find((a) => a.id === id)
  }

  /* ---------------- 动作 ---------------- */

  /**
   * 写完就**就地更新列表里那一行**。
   *
   * 刻意不 `fetchList()` 全量刷新：管理页改完配置立刻要看到回显，
   * 整表重拉会让页面闪一下，而且会把用户正在输入的表单内容冲掉。
   */
  function upsert(agent: AgentResource): void {
    const i = list.value.findIndex((a) => a.id === agent.id)
    if (i >= 0) list.value[i] = agent
    else list.value = [agent, ...list.value]
  }

  /** 新建时的载荷：code 与 name 必填，其余可选。与 api/agents.ts 的签名一致。 */
  type NewAgentPayload = Required<Pick<AgentPayload, 'code' | 'name'>> & AgentPayload

  async function create(payload: NewAgentPayload): Promise<AgentResource> {
    const created = await createAgent(payload)
    upsert(created)
    return created
  }

  async function update(id: string, payload: AgentPayload): Promise<AgentResource> {
    const updated = await updateAgent(id, payload)
    upsert(updated)
    return updated
  }

  async function remove(id: string): Promise<void> {
    await deleteAgent(id)
    list.value = list.value.filter((a) => a.id !== id)
  }

  async function setStatus(id: string, status: AgentStatus): Promise<AgentResource> {
    return update(id, { status })
  }

  async function duplicate(id: string): Promise<AgentResource> {
    const copy = await duplicateAgent(id)
    upsert(copy)
    return copy
  }

  /**
   * 试跑。
   *
   * **结果刻意不存进 store**：它是一次性回显（这次跑成了什么样），
   * 而且可能很大。存进全局会让它跨页面活着，正是要避免的那类冗余。
   */
  async function test(id: string, question: string): Promise<AgentTestResult> {
    return testChat(id, question)
  }

  /** 退出登录时清空：这些是上一个账号的数据。 */
  function reset(): void {
    list.value = []
    loaded.value = false
    error.value = ''
    tools.value = []
    toolsLoaded.value = false
  }

  return {
    list,
    loading,
    error,
    loaded,
    tools,
    toolsLoading,
    toolsLoaded,
    usable,
    builtin,
    fetchList,
    fetchTools,
    byId,
    create,
    update,
    remove,
    setStatus,
    duplicate,
    test,
    reset
  }
})
