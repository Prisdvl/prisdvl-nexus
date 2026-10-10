import { api, pageQuery } from './client'
import type {
  Agent,
  AgentAnswer,
  AgentListResponse,
  AgentQuery,
  CreateAgentRequest,
  TestAgentRequest,
  Tool,
  UpdateAgentRequest
} from './types/agent'

/**
 * Agent 与工具 API。
 *
 * 端点逐个对应 `AgentResourceController`（`@RequestMapping("/api/agents")`）
 * 与 `ToolController`（`@RequestMapping("/api/tools")`）：
 *
 * ```text
 * GET    /api/agents                      → Result<PageResult<AgentResponse>>
 * POST   /api/agents                      → Result<AgentResponse>
 * GET    /api/agents/{agentId}            → Result<AgentResponse>
 * PUT    /api/agents/{agentId}            → Result<AgentResponse>
 * POST   /api/agents/{agentId}/duplicate  → Result<AgentResponse>
 * DELETE /api/agents/{agentId}            → Result<Void>
 * GET    /api/tools                       → Result<List<ToolResponse>>
 * ```
 *
 * **注意路径里的 `{agentId}` 传的是 `id` 而不是 `code`** —— 后端路径变量是 `Long agentId`。
 *
 * 本阶段（���4）只做 **list / detail / create / update / remove / duplicate / tools**。
 * `test-chat`（试跑）与状态切换属 R9，因为它们需要页面上下文。
 */

export function listAgents(query: AgentQuery = {}, signal?: AbortSignal) {
  const { pageNum, pageSize } = query
  return api.get<AgentListResponse>('/agents', {
    query: pageQuery(pageNum, pageSize),
    ...(signal ? { signal } : {})
  })
}

export function getAgent(id: string, signal?: AbortSignal) {
  return api.get<Agent>(`/agents/${id}`, { ...(signal ? { signal } : {}) })
}

export function createAgent(payload: CreateAgentRequest, signal?: AbortSignal) {
  return api.post<Agent>('/agents', payload, { ...(signal ? { signal } : {}) })
}

export function updateAgent(
  id: string,
  payload: UpdateAgentRequest,
  signal?: AbortSignal
) {
  return api.put<Agent>(`/agents/${id}`, payload, { ...(signal ? { signal } : {}) })
}

export function removeAgent(id: string, signal?: AbortSignal) {
  return api.delete<void>(`/agents/${id}`, { ...(signal ? { signal } : {}) })
}

export function duplicateAgent(id: string, signal?: AbortSignal) {
  return api.post<Agent>(`/agents/${id}/duplicate`, undefined, {
    ...(signal ? { signal } : {})
  })
}

/** 工具清单。Agent 编辑器要用它列可勾选项（R9）。 */
export function listTools(signal?: AbortSignal) {
  return api.get<Tool[]>('/tools', { ...(signal ? { signal } : {}) })
}


/**
 * 试跑一个 Agent（R9）。
 *
 * `POST /api/agents/{agentId}/test-chat` → `Result<AgentAnswer>`
 *
 * ## 关键：**不受发布状态限制**（后端注释原文）
 *
 * > 为什么必须允许 DRAFT：§十八 的流程是"用户可以先测试 Agent，再发布"，
 * > 若试跑也要求 PUBLISHED，草稿就永远没法被验证，
 * > 只能靠"发布后才发现提示词写错了"。
 *
 * 所以前端**不要**在 `status !== 'PUBLISHED'` 时禁用试跑按钮——
 * 那样会直接堵死"先试跑再发布"这个主流程。
 *
 * 但**归属仍然校验**（只能试跑自己的），宽松只针对状态，不针对隔离。
 */
export function testAgent(
  agentId: string,
  payload: TestAgentRequest,
  signal?: AbortSignal
) {
  return api.post<AgentAnswer>(`/agents/${agentId}/test-chat`, payload, {
    ...(signal ? { signal } : {})
  })
}
