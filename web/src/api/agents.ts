import client from './client'
import type { PageResult } from './client'

/**
 * Agent 资源（Phase 4.1 后端 / 4.2 管理页）。
 *
 * 与对话的关系：`/api/chat/stream` 负责"跑"Agent，这里负责"配"Agent。
 * 会话只记住 `agentId`，真正用哪套提示词、哪个模型、能检索哪些知识库，
 * 都在执行时由服务端从 Agent 定义里读——所以**改了 Agent 的提示词，
 * 已存在的会话下一轮就生效**（这正是"配置化"的意义，界面上不必重建会话）。
 *
 * 所有 id 都是字符串：雪花 ID 超出 JS 安全整数范围（ADR-017）。
 */
export type AgentStatus = 'DRAFT' | 'PUBLISHED' | 'DISABLED'

/**
 * 回答策略：没有可引用的资料时，允不允许用模型自身的知识回答（4.4）。
 *
 * `KB_PREFERRED`（默认）——笔记优先；笔记里没有时可以用自身知识回答，但必须注明不是来自笔记。
 * `KB_ONLY`——只依据笔记，没找到就直说；这是 4.4 之前的旧行为，现在是显式选择。
 *
 * 两种情况下都**禁止**把模型自身的知识说成是用户笔记里的内容。
 */
export type AnswerPolicy = 'KB_PREFERRED' | 'KB_ONLY'

export interface AgentResource {
  id: string
  /** 稳定标识（创建后不可改，被 Tool / Workflow / 会话引用的是它，不是显示名） */
  code: string
  name: string
  description: string | null
  systemPrompt: string | null
  /** 逻辑模型角色；为空 = 服务端默认（chat.agent） */
  modelRole: string | null
  /** 没有可引用资料时怎么答；服务端保证一定返回合法值 */
  answerPolicy: AnswerPolicy
  /**
   * 采样温度（5.4）。`null` = **跟随服务端全局默认**，不是 0。
   *
   * 0 是一个真实的选择（"我要最确定的回答"），所以两者必须分开显示：
   * 把 null 显示成 0，用户就分不清"我配过"和"我没配过"。
   */
  temperature: number | null
  status: AgentStatus
  /** 内置默认 Agent：不可删除、不可停用，但可改名与改提示词 */
  builtin: boolean
  /** 绑定的知识库；**空数组 = 不限制**（检索全部知识库），与 RAG 会话的 kbIds 语义相反 */
  kbIds: string[]
  /**
   * 可用工具的 code 白名单（5.4）；**空数组 = 不限制**（装该用户全部启用中的工具）。
   *
   * 与 kbIds 同一套语义。注意它与直觉相反：**空 = 全都能用**，不是"一个都不能用"——
   * 所以界面上必须写明，否则用户看到空选择框会以为这个 Agent 没有工具。
   */
  toolCodes: string[]
  createdAt: string
  updatedAt: string
}

export interface AgentPayload {
  code?: string
  name?: string
  description?: string
  systemPrompt?: string
  modelRole?: string
  /** 不传 = 不改；新建时不传则由服务端落默认 KB_PREFERRED */
  answerPolicy?: AnswerPolicy
  /**
   * 采样温度（5.4，服务端校验 0–2）；**不传 = 不改 / 跟随全局默认**。
   *
   * 注意这里**没有**"改回跟随默认"的传法——`null` 在这个局部更新接口里已经表示"不改"，
   * 一个值不能同时是"不改"和"清空"。真要清空只能新建 Agent，界面上也照此说明。
   */
  temperature?: number
  status?: AgentStatus
  kbIds?: string[]
  /** 工具白名单（5.4）；`[]` = 回到"不限制"，不传 = 不改。 */
  toolCodes?: string[]
}

export function listAgents(pageSize = 50) {
  return client.get<never, PageResult<AgentResource>>(
    `/agents?pageNum=1&pageSize=${pageSize}`
  )
}

export function createAgent(payload: Required<Pick<AgentPayload, 'code' | 'name'>> & AgentPayload) {
  return client.post<never, AgentResource>('/agents', payload)
}

/** 局部更新：只传要改的字段。`kbIds: []` = 解绑（回到"不限制"）。 */
export function updateAgent(id: string, payload: AgentPayload) {
  return client.put<never, AgentResource>(`/agents/${id}`, payload)
}

/** 启停都走更新（`status`）：DRAFT 不能被会话选用，DISABLED 会被明确拒绝而不是悄悄降级。 */
export function setAgentStatus(id: string, status: AgentStatus) {
  return updateAgent(id, { status })
}

export function duplicateAgent(id: string) {
  return client.post<never, AgentResource>(`/agents/${id}/duplicate`)
}

export function deleteAgent(id: string) {
  return client.delete<never, void>(`/agents/${id}`)
}

/** 试跑结果：与 `/api/agent/ask` 同形（答案 + 工具痕迹 + 是否用了工具）。 */
export interface AgentTestResult {
  answer: string | null
  toolCalls: Array<{ tool: string; input: string; resultSummary: string }>
  /** false = 模型这轮没检索（可能是不需要，也可能是检索没跑通——界面上要能分辨） */
  usedTools: boolean
}

/**
 * 试跑一次（同步）。**不要求已发布**——草稿正是要被测试的对象，
 * 否则"先测试再发布"这条流程就没法走。
 *
 * 注意它走的是 `/api/agents/{id}/test-chat`（同步 JSON），不是对话链路：
 * 试跑不进会话、不落消息表，适合"改完提示词立刻看一眼效果"。
 */
export function testChat(id: string, question: string) {
  return client.post<never, AgentTestResult>(`/agents/${id}/test-chat`, { question })
}
