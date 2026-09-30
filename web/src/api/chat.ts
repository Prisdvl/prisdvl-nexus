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
  /** RAG 会话绑定的知识库 id（空数组 = 未绑定，不是 null）。其余模式恒为空 */
  kbIds: string[]
  /**
   * AGENT 会话选用的 Agent（Phase 4.1）。为 null = 用内置默认 Agent。
   *
   * 其余模式恒为 null：检索范围与提示词只有 AGENT 模式由 Agent 定义决定，
   * RAG 模式由 kbIds 决定（两处语义不同，别混用）。
   */
  agentId?: string | null
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

/**
 * 新建会话。
 *
 * `kbIds` 只在 RAG 模式下有意义（服务端会拒绝给 PLAIN/AGENT 绑库，也会拒绝绑多个）：
 * RAG 会话必须绑定知识库，否则提问时会被明确拒绝——不做"悄悄退化成不检索"。
 *
 * `agentId` 只在 AGENT 模式下有意义，且必须是**已发布**的 Agent
 * （草稿 5008 / 停用 5009 / 别人的 5006）；不传 = 内置默认 Agent。
 */
export function createConversation(
  mode: 'PLAIN' | 'RAG' | 'AGENT',
  options: { title?: string; kbIds?: string[]; agentId?: string | null } = {}
) {
  return client.post<never, Conversation>('/chat/conversations', {
    mode,
    title: options.title,
    kbIds: options.kbIds,
    agentId: options.agentId
  })
}

/** 换 Agent（AGENT 会话）。 */
export function bindAgent(id: string, agentId: string) {
  return client.put<never, Conversation>(`/chat/conversations/${id}`, { agentId })
}

/**
 * 清空会话选用的 Agent（回到内置默认）。
 *
 * 必须是独立信号而不是传 `agentId: null`：null 在这个局部更新接口里已经表示"不改"，
 * 一个值不能同时是"不改"和"清空"。
 */
export function resetConversationAgent(id: string) {
  return client.put<never, Conversation>(`/chat/conversations/${id}`, { resetAgent: true })
}

export function listConversations(pageSize = 50) {
  return client.get<never, { list: Conversation[]; total: number }>(
    `/chat/conversations?pageNum=1&pageSize=${pageSize}`
  )
}

export function renameConversation(id: string, title: string) {
  return client.put<never, Conversation>(`/chat/conversations/${id}`, { title })
}

/** 改绑知识库（RAG 会话）；传空数组 = 解绑。 */
export function bindKnowledgeBase(id: string, kbIds: string[]) {
  return client.put<never, Conversation>(`/chat/conversations/${id}`, { kbIds })
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

/** 一条检索命中（切片级：与回答里的 [n] 编号一一对应）。 */
export interface StreamRetrievalHit {
  chunkId: string
  documentId: string
  documentTitle: string | null
  headingPath: string | null
  score: number
  /** bm25 / vector / hybrid */
  source: string
}

/** retrieval 事件：检索完成、生成之前到达，供界面显示"检索到 N 个片段"。 */
export interface StreamRetrieval {
  knowledgeBaseId: string | null
  count: number
  hits: StreamRetrievalHit[]
  contextTokens: number
  truncated: number
  reranked: boolean
  empty: boolean
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
  onRetrieval?: (retrieval: StreamRetrieval) => void
  onDelta?: (text: string) => void
  onToolCall?: (call: { tool: string; input: string }) => void
  onToolResult?: (call: MessageToolCall) => void
  onUsage?: (usage: StreamUsage) => void
  onDone?: (done: StreamDone) => void
}

/**
 * 发起一轮对话（流式）。三种会话模式共用这一条链路，事件子集不同：
 *
 * - `PLAIN`：meta → delta… → usage → done
 * - `RAG`：meta → **retrieval**（检索到 N 个片段）→ delta… → usage → done
 * - `AGENT`：meta →（tool_call → tool_result）* → delta… → usage → done
 *
 * 检索/tool 事件都发生在生成之前，因此"正在检索…"能实时出现在回答之前 ——
 * 这正是用户能看到"模型在做什么"的地方，而不是对着空白等十几秒。
 */
export async function streamChat(
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
          case 'retrieval':
            handlers.onRetrieval?.(payload)
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
