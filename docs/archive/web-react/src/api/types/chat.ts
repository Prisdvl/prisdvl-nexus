import type { PageResult } from './common'

/**
 * Chat 与会话类型 —— **逐字段抄自后端 record**，且**核实过 JSON 形态**。
 *
 * 权威来源：
 * ```text
 * nexus-app/.../chat/model/dto/ConversationResponse.java
 *   record ConversationResponse(Long id, String title, String mode, String modelRole,
 *                                Integer messageCount, LocalDateTime lastMessageAt,
 *                                Boolean pinned, List<Long> kbIds, Long agentId,
 *                                LocalDateTime createdAt, LocalDateTime updatedAt)
 * nexus-app/.../chat/model/dto/ChatMessageResponse.java
 *   record ChatMessageResponse(Long id, String role, Long agentId, String content,
 *                               String model, Integer promptTokens, Integer completionTokens,
 *                               Integer totalTokens, Integer latencyMs, Integer firstTokenMs,
 *                               String status, String errorMsg, LocalDateTime createdAt,
 *                               List<MessageToolCall> toolCalls, List<RagAnswer.RagCitation> citations)
 * nexus-app/.../chat/model/dto/MessageToolCall.java
 *   record MessageToolCall(String tool, String input, String resultSummary)
 * nexus-knowledge/.../rag/RagAnswer.java
 *   record RagCitation(int index, Long knowledgeBaseId, Long chunkId, Long documentId,
 *                      String documentTitle, String headingPath, String snippet,
 *                      double score, boolean referenced)
 * nexus-app/.../chat/model/dto/StreamChatRequest.java
 *   record StreamChatRequest(@NotNull Long conversationId,
 *                            @NotBlank @Size(max=8000) String content)
 * ```
 *
 * ## ⚠️ `Long` → JSON **string**（R7 踩过的坑，这里直接按对的写）
 *
 * `id` / `agentId` / `kbIds[]` / `citations[].documentId` 等都是 Java `Long`，
 * Jackson 序列化成字符串。**用 `string`**，绝不 `number`——
 * 19 位雪花 id 超出 JS 安全整数上限，`Number()` 会抹掉末位。
 *
 * `Integer`（`messageCount` / `promptTokens`…）与 `int`（`citations[].index`）、
 * `double`（`score`）才是 number。
 *
 * ## `pinned` 是包装 `Boolean` → `boolean | null`
 *
 * 不是 `boolean`。后端没设值时是 null，`Boolean` 上加 `!` 会把它变成 `false`，
 * 于是"未置顶"和"后端没返回"混为一谈。
 */

/** 引用（RAG 问答的来源）。字段**严格**按后端 `RagCitation`。 */
export interface RagCitation {
  /** 引用序号，从 1 开始（后端 `int`）。回答正文里的 `[1]` 就对应它。 */
  index: number
  knowledgeBaseId: string
  /** 命中的切片 id。后端有，R4b 我漏了。 */
  chunkId: string
  documentId: string
  documentTitle: string | null
  /** 标题路径（如「JVM / 垃圾回收 / G1」）。用于定位到小节。 */
  headingPath: string | null
  /** 片段原文。 */
  snippet: string
  /** 相似度。后端是 `double`。 */
  score: number
  /** 回答是否真的引用了它（未引用也返回，便于核对模型有没有漏引）。 */
  referenced: boolean
}

/**
 * RAG `meta` 帧里的引用**预览**。
 *
 * **字段比 `RagCitation` 少**：没有 index / chunkId / snippet / score / referenced，
 * 因为 meta 到达时回答**还没开始**，那些字段那时不存在。
 * R4b 曾把两者混用，直接渲染出 `undefined`。
 */
export interface RagCitationPreview {
  knowledgeBaseId: string
  documentId: string
  documentTitle: string | null
}

/** 消息上记录的工具调用（回答已生成后的历史记录）。 */
export interface MessageToolCall {
  tool: string
  /** 入参。**后端给的是字符串**（不是对象）——可能已序列化过，原样展示。 */
  input: string | null
  resultSummary: string | null
}

export interface Conversation {
  id: string
  /** 用户没起名时后端会按首条消息生成。 */
  title: string | null
  /** RAG / AGENT 等。 */
  mode: string | null
  modelRole: string | null
  messageCount: number | null
  lastMessageAt: string | null
  /** 包装 `Boolean` → 可能为 null。见文件头说明。 */
  pinned: boolean | null
  kbIds: string[]
  agentId: string | null
  createdAt: string | null
  updatedAt: string | null
}

/** 消息角色。USER / ASSISTANT / SYSTEM。后端是字符串，未枚举化。 */
export type MessageRole = 'USER' | 'ASSISTANT' | 'SYSTEM' | (string & {})

export interface ChatMessage {
  id: string
  role: MessageRole
  agentId: string | null
  /** 正文。ASSISTANT 消息在流式过程中是**累积**的完整文本。 */
  content: string | null
  model: string | null
  promptTokens: number | null
  completionTokens: number | null
  totalTokens: number | null
  latencyMs: number | null
  firstTokenMs: number | null
  /** 生成状态。ASSISTENT 未完成时可能是 GENERATING。 */
  status: string | null
  errorMsg: string | null
  createdAt: string | null
  toolCalls: MessageToolCall[]
  citations: RagCitation[]
}

export type ConversationListResponse = PageResult<Conversation>
export type MessageListResponse = PageResult<ChatMessage>

/** `POST /api/chat/conversations` 的请求体。全部可选（后端会按首条消息生成标题）。 */
export interface CreateConversationRequest {
  /** 最长 200（后端 `@Size` 约束）。 */
  title?: string
  mode?: string
  kbIds?: string[]
  agentId?: string
}

/** `POST /api/chat/stream` 的请求体。 */
export interface StreamChatRequest {
  conversationId: string
  /** 最长 8000（后端 `@Size(max = 8000)`）。超了后端会 400。 */
  content: string
}

/** `POST /api/chat/stream/{requestId}/stop` 的响应。 */
export interface StopStreamResponse {
  requestId: string
  /** 是否真的停掉了（已经自然结束时为 false）。 */
  cancelled: boolean
}
