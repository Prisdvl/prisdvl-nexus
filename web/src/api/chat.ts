import client from './client'
import { postSse } from '@/utils/sse'

/**
 * 会话与消息（Agent 对话用）。
 *
 * 这里所有的 id 都是**字符串**：雪花 ID 超出 JS 安全整数范围，
 * 后端已统一序列化为字符串（ADR-017），前端绝不能转成 Number 再传回去。
 */
export interface Conversation {
  id: string
  title: string | null
  mode: 'PLAIN' | 'RAG' | 'AGENT'
  modelRole: string | null
  messageCount: number
  lastMessageAt: string | null
  pinned: boolean
  createdAt: string
  updatedAt: string
}

/** 一次工具调用的痕迹（Agent 模式）：检索了什么、命中多少条。 */
export interface MessageToolCall {
  tool: string
  input: string
  resultSummary: string
}

export interface ChatMessage {
  id: string
  role: 'USER' | 'ASSISTANT' | 'SYSTEM' | 'TOOL'
  content: string | null
  model: string | null
  status: string
  errorMsg: string | null
  createdAt: string
  toolCalls: MessageToolCall[]
}

/** 新建会话；Agent 对话固定用 AGENT 模式（走带工具的链路）。 */
export function createConversation(mode: 'PLAIN' | 'RAG' | 'AGENT', title?: string) {
  return client.post<never, Conversation>('/chat/conversations', { mode, title })
}

export function listConversations(pageSize = 50) {
  return client.get<never, { list: Conversation[]; total: number }>(
    `/chat/conversations?pageNum=1&pageSize=${pageSize}`
  )
}

export function renameConversation(id: string, title: string) {
  return client.put<never, Conversation>(`/chat/conversations/${id}`, { title })
}

export function deleteConversation(id: string) {
  return client.delete<never, void>(`/chat/conversations/${id}`)
}

/** 历史消息（含工具痕迹），按时间正序。 */
export function listMessages(conversationId: string, pageSize = 100) {
  return client.get<never, { list: ChatMessage[]; total: number }>(
    `/chat/messages/${conversationId}?pageNum=1&pageSize=${pageSize}`
  )
}

export interface StreamMeta {
  requestId: string
  conversationId: string
  userMessageId: string
  assistantMessageId: string
  modelRole: string
  mode: string
}

export interface StreamDone {
  finishReason?: string
  stopped?: boolean
}

export interface StreamUsage {
  promptTokens?: number
  completionTokens?: number
  totalTokens?: number
  latencyMs?: number
  firstTokenMs?: number
}

export interface AgentStreamHandlers {
  onMeta?: (meta: StreamMeta) => void
  onDelta?: (text: string) => void
  onToolCall?: (call: { tool: string; input: string }) => void
  onToolResult?: (call: MessageToolCall) => void
  onUsage?: (usage: StreamUsage) => void
  onDone?: (done: StreamDone) => void
}

/**
 * 发起一轮 Agent 对话（流式）。
 *
 * 事件顺序：meta →（tool_call → tool_result）* → delta… → usage → done。
 * 工具调用发生在生成之前，因此"正在检索…"能实时出现在回答之前 —— 这正是
 * 用户能看到"模型在做什么"的地方，而不是对着空白等十几秒。
 */
export async function streamAgentChat(
  conversationId: string,
  content: string,
  handlers: AgentStreamHandlers,
  signal?: AbortSignal
): Promise<void> {
  await postSse(
    '/api/chat/stream',
    { conversationId, content },
    {
      signal,
      onFrame: ({ event, data }) => {
        const payload = data as never
        switch (event) {
          case 'meta':
            handlers.onMeta?.(payload)
            break
          case 'delta':
            handlers.onDelta?.((payload as { text: string }).text ?? '')
            break
          case 'tool_call':
            handlers.onToolCall?.(payload)
            break
          case 'tool_result':
            handlers.onToolResult?.(payload)
            break
          case 'usage':
            handlers.onUsage?.(payload)
            break
          case 'done':
            handlers.onDone?.(payload)
            break
          case 'error':
            throw new Error((payload as { message?: string }).message ?? '生成失败')
          default:
            // 未知事件（后续 Phase 新增）忽略即可，不影响已有分支
            break
        }
      }
    }
  )
}

/** 停止生成（幂等：流已结束时返回 cancelled=false，不算错误）。 */
export function stopStream(requestId: string) {
  return client.post<never, { requestId: string; cancelled: boolean }>(
    `/chat/stream/${requestId}/stop`
  )
}
