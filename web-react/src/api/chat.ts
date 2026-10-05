import { api, pageQuery } from './client'
import type {
  ChatMessage,
  Conversation,
  ConversationListResponse,
  CreateConversationRequest,
  MessageListResponse,
  StopStreamResponse
} from './types/chat'

/**
 * Chat API。
 *
 * 端点对应三个 Controller：
 * ```text
 * ChatConversationController  @RequestMapping("/api/chat/conversations")
 *   POST   /                          → Result<ConversationResponse>
 *   GET    /                          → Result<PageResult<ConversationResponse>>
 *   GET    /{conversationId}          → Result<ConversationResponse>
 *   PUT    /{conversationId}          → Result<ConversationResponse>
 *   DELETE /{conversationId}          → Result<Void>
 *
 * ChatMessageController      @RequestMapping("/api/chat/messages")     ← 不是 conversations
 *   GET    /{conversationId}          → Result<PageResult<ChatMessageResponse>>
 *
 * ChatStreamController       @RequestMapping("/api/chat")
 *   POST /stream                → text/event-stream（不走本文件，见 lib/sse.ts）
 *   POST /stream/{requestId}/stop → Result<StopStreamResponse>
 * ```
 *
 * **流式请求刻意不在这里**——它不走 `request()` 那套 JSON 解析，
 * 由 `lib/sse.ts` 的 `postSse` 处理。
 */

/* ---------------- 会话 ---------------- */

export function listConversations(
  params: { pageNum?: number; pageSize?: number } = {},
  signal?: AbortSignal
) {
  const { pageNum, pageSize } = params
  return api.get<ConversationListResponse>('/chat/conversations', {
    query: pageQuery(pageNum, pageSize),
    ...(signal ? { signal } : {})
  })
}

export function createConversation(
  payload: CreateConversationRequest = {},
  signal?: AbortSignal
) {
  return api.post<Conversation>('/chat/conversations', payload, {
    ...(signal ? { signal } : {})
  })
}

export function renameConversation(
  id: string,
  title: string,
  signal?: AbortSignal
) {
  return api.put<Conversation>(`/chat/conversations/${id}`, { title }, {
    ...(signal ? { signal } : {})
  })
}

export function deleteConversation(id: string, signal?: AbortSignal) {
  return api.delete<void>(`/chat/conversations/${id}`, {
    ...(signal ? { signal } : {})
  })
}

/* ---------------- 消息 ---------------- */

/**
 * 消息列表 → `GET /api/chat/messages/{conversationId}`。
 *
 * ## ⚠️ 路径是 `/chat/messages/{id}`，**不是** `/chat/conversations/{id}/messages`
 *
 * 我第一版写成了后者，**注释里还写得很确信**「ChatMessageController 也挂在
 * `/api/chat/conversations` 上」。实际后端（R8 勘察 `ChatMessageController:22`）：
 * ```java
 * @RequestMapping("/api/chat/messages")      // ← 这里
 * public class ChatMessageController {
 *     @GetMapping("/{conversationId}")
 *     public Result<PageResult<ChatMessageResponse>> list(...)
 * }
 * ```
 * 请求打过去得到 **405**（路径命中了 `ChatConversationController` 的某个方法，
 * 但方法不匹配），排查时很容易误以为是后端的问题。
 *
 * **这是 R7 的 `files` 字段名之后第二次"凭印象写路径/字段没核实"**。
 * 教训固化：**注释里凡是写"后端是 X"的，必须从源码复制，不能凭记忆或推演。**
 */
export function listMessages(
  conversationId: string,
  params: { pageNum?: number; pageSize?: number } = {},
  signal?: AbortSignal
) {
  const { pageNum, pageSize } = params
  return api.get<MessageListResponse>(`/chat/messages/${conversationId}`, {
    query: pageQuery(pageNum, pageSize),
    ...(signal ? { signal } : {})
  })
}

/* ---------------- 停止生成 ---------------- */

/**
 * 停止一次流式生成。
 *
 * `requestId` 来自 SSE 的 `meta` 帧。**没有它就没法停**——
 * 所以 `meta` 到达后必须立刻记下来（见 `useChatStream`）。
 */
export function stopStream(requestId: string, signal?: AbortSignal) {
  return api.post<StopStreamResponse>(
    `/chat/stream/${requestId}/stop`,
    undefined,
    { ...(signal ? { signal } : {}) }
  )
}

export type { ChatMessage, Conversation }
