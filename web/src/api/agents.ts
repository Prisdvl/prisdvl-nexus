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

export interface AgentResource {
  id: string
  /** 稳定标识（创建后不可改，被 Tool / Workflow / 会话引用的是它，不是显示名） */
  code: string
  name: string
  description: string | null
  systemPrompt: string | null
  /** 逻辑模型角色；为空 = 服务端默认（chat.agent） */
  modelRole: string | null
  status: AgentStatus
  /** 内置默认 Agent：不可删除、不可停用，但可改名与改提示词 */
  builtin: boolean
  /** 绑定的知识库；**空数组 = 不限制**（检索全部知识库），与 RAG 会话的 kbIds 语义相反 */
  kbIds: string[]
  createdAt: string
  updatedAt: string
}

export interface AgentPayload {
  code?: string
  name?: string
  description?: string
  systemPrompt?: string
  modelRole?: string
  status?: AgentStatus
  kbIds?: string[]
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
