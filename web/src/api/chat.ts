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

/**
 * 一条引用（随消息落库，刷新后仍在）。
 *
 * 与 `StreamRetrievalHit` 是同一件事的两种载体：那个是检索**当下**推给界面的事件，
 * 只活在这一次连接里；这个是**回答结束时**写进 `chat_message.metadata` 的留档。
 * 前者回答"这次搜到了什么"，后者回答"这条回答是照着什么说的"。
 *
 * 字段与服务端 `RagAnswer.RagCitation` 一一对应（编号是**列表里的位置**，
 * 单库时与正文里的 `[n]` 严格对齐）。
 */
export interface ChatCitation {
  index: number
  /**
   * 这一片出自哪个知识库。多库检索下**没有它就无法回答"资料是哪来的"**——
   * 而会话可能同时绑着"产品手册"和"内部 FAQ"，答案的可靠性取决于出处。
   */
  knowledgeBaseId: string | null
  chunkId: string | null
  documentId: string | null
  documentTitle: string | null
  headingPath: string | null
  snippet: string | null
  score: number
  /** 正文里是否真的出现了 `[index]`；未引用也返回，便于核对模型有没有漏引。 */
  referenced: boolean
}

export interface ChatMessage {
  id: string
  role: 'USER' | 'ASSISTANT' | 'SYSTEM' | 'TOOL'
  /**
   * 产出这条回答的 Agent（5.4）。**只有助手消息有值，用户消息恒为 null**。
   *
   * 与 `Conversation.agentId` 不是一回事：那个是**活指针**（null = 跟随当前内置默认），
   * 这个是**历史事实**（这条回答当时是哪个 Agent 跑的，存的是解析后的具体 id）。
   * 会话换绑 Agent 后，旧回答仍指回原来那个——这才是"这条是谁答的"该有的答案。
   */
  agentId: string | null
  content: string | null
  model: string | null
  status: string
  errorMsg: string | null
  createdAt: string
  toolCalls: MessageToolCall[]
  /**
   * 这条回答引用的资料片段（无检索的对话为空数组）。
   *
   * 落库是必须的：检索事件只在流里发一次，刷新页面就没了——于是"这条回答照着什么说的"
   * 在历史回放里无从查证。它也是**未引用**标记的来源（模型漏引时看得出来）。
   */
  citations: ChatCitation[]
}

/**
 * 新建会话。
 *
 * `kbIds` 只在 RAG 模式下有意义（服务端会拒绝给 PLAIN/AGENT 绑库）：
 * RAG 会话必须绑定知识库，否则提问时会被明确拒绝——不做"悄悄退化成不检索"。
 * 可以绑多个（Phase 5.5，上限 10 个），检索时逐库召回后跨库归并。
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
  /**
   * 这一片出自哪个知识库（Phase 5.5）。
   *
   * 会绑多个库之后，"检索到 5 个片段"不再能回答最要紧的那个问题——**资料是从哪来的**。
   * 会话可能是"产品手册 + 内部 FAQ"两库，用户要靠这个字段才能判断答案的出处。
   */
  knowledgeBaseId: string | null
  documentTitle: string | null
  headingPath: string | null
  score: number
  /** bm25 / vector / hybrid */
  source: string
}

/**
 * 本次没能参与检索的知识库（Phase 5.5.1）。
 *
 * 多库是**并行**检索、逐库失败隔离的：一个库超时或不可用不会让整次检索失败，
 * 但也不能就这样消失——只显示"检索到 5 个片段"的话，绑了 3 个库的用户无从知道
 * 有一个库根本没被搜过，而答案可能就差在那一库里。
 *
 * 名字由服务端一并给出（不是前端拿 id 去查表）：查不到时前端只能显示"知识库 1002"，
 * 对用户没有意义；而 id 也保留，用于和命中片段、库列表对齐。
 */
export interface StreamRetrievalFailure {
  knowledgeBaseId: string
  knowledgeBaseName: string
  reason: string
}

/** retrieval 事件：检索完成、生成之前到达，供界面显示"检索到 N 个片段"。 */
export interface StreamRetrieval {
  /** 本次检索覆盖的知识库（会话绑了几个就是几个）；<b>含检索失败的那些</b>。 */
  knowledgeBaseIds: string[]
  count: number
  hits: StreamRetrievalHit[]
  contextTokens: number
  truncated: number
  reranked: boolean
  empty: boolean
  /**
   * 本次检索失败、因此<b>没有贡献任何片段</b>的知识库；正常时为空数组（不是缺省）。
   *
   * 与 `count` 一起读才有意义：`count = 5, failedKnowledgeBases = [内部 FAQ]` 说的是
   * "这 5 段只来自其余库"，而不是"内部 FAQ 里没有相关内容"。
   */
  failedKnowledgeBases: StreamRetrievalFailure[]
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
  /**
   * 本轮引用（没有检索或没有命中时**不带这个字段**）。
   *
   * 跟着 `done` 走而不是单开一个事件：`referenced` 要在完整正文里比对编号才算得出来，
   * 而回答结束时前端必然已经收到全部正文。这样实时视图与刷新后的历史视图
   * 渲染的是同一份数据，不会出现"刷新一下引用才出来"。
   */
  citations?: ChatCitation[]
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
