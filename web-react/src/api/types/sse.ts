/**
 * SSE 事件类型 —— **全项目唯一来源**（R3 建立的约定，R4b 落实）。
 *
 * 权威来源（改动前请先读它们，本文件只是记录）：
 * ```text
 * nexus-app/.../chat/stream/SseEventNames.java    事件名常量表
 * nexus-app/.../chat/stream/RetrievalEvent.java   两条链路**共用**的检索事件
 * nexus-app/.../chat/service/impl/ChatStreamServiceImpl.java
 * nexus-app/.../knowledge/service/impl/RagStreamServiceImpl.java
 * ```
 *
 * ## 两条链路**不是**同一个形状（这是本文件存在的全部理由）
 *
 * 同一个事件名，两条链路的字段**不同**：
 *
 * | 事件 | Chat（`/api/chat/stream`） | RAG（`/api/knowledge-bases/{id}/ask/stream`） |
 * | --- | --- | --- |
 * | `meta` | `requestId` `conversationId` `userMessageId` `assistantMessageId` `modelRole` `mode` | `requestId` `citations`（预览） |
 * | `retrieval` | **同一个 `RetrievalEvent`** | **同一个 `RetrievalEvent`** |
 * | `delta` | `text` | `text` |
 * | `usage` | `promptTokens` `completionTokens` `totalTokens` | 不发 |
 * | `done` | `finishReason` **`stopped`** `citations` `resultSummary` `firstTokenMs` `latencyMs` `tool` | `finishReason` **`stop`** `citations` `recordId` `empty` |
 * | `error` | `code` `message` `retryable` | **`errorCode`** `error` `retryable` |
 * | `tool_call` / `tool_result` | 发 | 不发 |
 *
 * ## ⚠️ 所有 id 字段是 **string**（R7 实测踩过）
 *
 * 后端 `Long` 被 Jackson 序列化成 JSON 字符串。19 位雪花 id 超出 JS 安全整数上限，
 * `Number()` 会抹掉末位——当时表现为「详情页 404，URL 看起来完全正常」。
 *
 * 三个最容易踩的坑：
 * 1. `done.stopped`（Chat）vs `done.stop`（RAG）——**同义不同名**；
 * 2. `error.code`/`error.message`（Chat）vs `error.errorCode`/`error.error`（RAG）；
 * 3. Vue 侧曾把 RAG 的 `meta` 帧错命名成 `StreamMeta`，
 *    于是"meta 里的 citations"和"done 里的 citations"共用一个类型——
 *    **而它们的字段根本不同**（meta 的引用没有 index/snippet/score，因为回答还没开始）。
 *
 * 所以下面是**分层单一来源**：公共字段一份，链路专有部分显式 `extends`。
 * 强行合并成一个形状会与后端不符并破坏功能。
 */

import type { RagCitation, RagCitationPreview } from './chat'

/* ==================================================================
 * 事件名（对应 SseEventNames.java）
 * ================================================================== */

export const SSE_EVENTS = {
  meta: 'meta',
  delta: 'delta',
  usage: 'usage',
  done: 'done',
  error: 'error',
  retrieval: 'retrieval',
  citation: 'citation',
  toolCall: 'tool_call',
  toolResult: 'tool_result',
  approvalRequired: 'approval_required'
} as const

export type SseEventName = (typeof SSE_EVENTS)[keyof typeof SSE_EVENTS]

/**
 * 后端已定义但**当前未使用**的事件名。
 *
 * 保留在类型里是为了将来后端启用时不必改前端；**但不写解析分支**——
 * 写了也永远不会命中，徒增"看起来支持了"的错觉。
 */
export type ReservedEventName = 'citation' | 'approval_required'

/* ==================================================================
 * 公共片段
 * ================================================================== */

/** 所有事件都带 requestId（除 delta 外都靠它把帧归属到一次请求）。 */
export interface StreamMetaCommon {
  requestId: string
}

/* ==================================================================
 * retrieval —— 两条链路**完全一致**（后端共用 RetrievalEvent.of）
 * ================================================================== */

/** 命中的知识库失败（部分失败场景）。字段来自 `RetrievalEvent.failedKnowledgeBases`。 */
export interface RetrievalFailure {
  knowledgeBaseId: string
  knowledgeBaseName: string | null
  /** 失败原因。**直接展示给用户**——它说的是"这个库没搜到"或"ES 连不上"。 */
  reason: string
}

/** 单个命中的切片。字段来自 `RetrievalEvent.hits`。 */
export interface RetrievalHit {
  knowledgeBaseId: string
  knowledgeBaseName: string | null
  documentId: string
  documentTitle: string | null
  /** 标题路径（如「JVM / 垃圾回收 / G1」）。**可空**——文档可能没有标题层级。 */
  headingPath: string | null
  chunkId: string
  score: number
  /** 片段原文。 */
  source: string
}

export interface StreamRetrieval {
  knowledgeBaseIds: string[]
  /** 命中数。`hits.length` 的冗余字段，后端显式给的。 */
  count: number
  hits: RetrievalHit[]
  contextTokens: number
  /** 上下文被截断（超预算）。UI 应提示"只用了前面一部分"。 */
  truncated: boolean
  reranked: boolean
  /** **检索为空**。与"0 命中"不同——它可能带着失败原因。 */
  empty: boolean
  /**
   * 部分失败的知识库。
   *
   * **缺它会直接导致一个假象**：Vue 侧改造前 `api/knowledge.ts` 漏了这个字段，
   * 于是知识库问答页在"某个库检索失败"时无法显示"这次少搜了谁"，
   * **界面会假装全都搜过了**（Phase C §6 修掉的真实缺陷）。
   */
  failedKnowledgeBases: RetrievalFailure[]
}

/* ==================================================================
 * meta —— 两条链路不同形
 * ================================================================== */

/** Chat 的 meta：先告诉前端"这条 assistant 消息的 id 是什么"。 */
export interface ChatStreamMeta extends StreamMetaCommon {
  /** **string**（后端 Long → JSON string，R7 精度坑）。 */
  conversationId: string
  userMessageId: string
  assistantMessageId: string
  /** 实际用的模型角色名。 */
  modelRole: string
  /** RAG / AGENT 等。 */
  mode: string
}

/**
 * RAG 的 meta 里的引用**预览**。
 *
 * **字段比 `RagCitation` 少**：没有 index / snippet / score / referenced——
 * 因为 meta 到达时回答**还没开始**，那些字段那时不存在。
 * Vue 侧曾把两者混用，导致渲染出 `undefined`。
 */


export interface RagStreamMeta extends StreamMetaCommon {
  citations: RagCitationPreview[]
}

/* ==================================================================
 * delta / usage
 * ================================================================== */

/** 增量文本。**每个 delta 都要推动一次 UI 更新**（任务书 §45）。 */
export interface StreamDelta {
  text: string
}

export interface StreamUsage {
  promptTokens: number
  completionTokens: number
  totalTokens: number
}

/* ==================================================================
 * done —— 两条链路的"是否被停止"字段名不同
 * ================================================================== */

/** Chat 的 done。用 `stopped`。 */
export interface ChatStreamDone extends StreamMetaCommon {
  finishReason: string
  /** 用户点了「停止生成」。**注意 RAG 那边叫 `stop`**。 */
  stopped: boolean
  citations: RagCitation[]
  /** 一句话摘要（后端生成）。 */
  resultSummary: string | null
  /** 首个 token 延迟（毫秒）。性能指标，**属于 L4 默认隐藏**。 */
  firstTokenMs: number | null
  latencyMs: number | null
  /** 用到的工具（若有）。 */
  tool: string | null
}

/** RAG 的 done。用 `stop`。 */
export interface RagStreamDone extends StreamMetaCommon {
  finishReason: string
  /** 注意：Chat 那边叫 `stopped`。 */
  stop: boolean
  citations: RagCitation[]
  /** 问答记录 id（用于"查看历史问答"）。**string**（后端 Long）。 */
  recordId: string | null
  /** 检索为空。 */
  empty: boolean
}

/**
 * 完整引用 —— **权威定义在 `api/types/chat.ts` 的 `RagCitation`**。
 *
 * 这里只做重导出，避免"两份定义"（R3 任务书 §17 禁止同一个 DTO 出现两套定义）。
 *
 * ## R8 修正了 R4b 的两处猜测
 *
 * 我 R4b 写的是 `knowledgeBaseId: number` / `knowledgeBaseName` / 无 `chunkId`，
 * 那是**凭 Phase C 的记录推的，没核实 DTO**。实际后端
 * （`RagAnswer.RagCitation`）是：
 * ```java
 * record RagCitation(int index, Long knowledgeBaseId, Long chunkId, Long documentId,
 *                    String documentTitle, String headingPath, String snippet,
 *                    double score, boolean referenced)
 * ```
 * 差异：**多了 `chunkId` 与 `headingPath`**，**没有 `knowledgeBaseName`**，
 * 且所有 Long 都是 **string**（R7 踩过的精度坑）。
 */
export type { RagCitation, RagCitationPreview }

/* ==================================================================
 * error —— 字段名也不同
 * ================================================================== */

export interface ChatStreamError extends StreamMetaCommon {
  code: number
  message: string
  /** 可重试。UI 据此决定给不给「重试」按钮。 */
  retryable: boolean
}

export interface RagStreamError extends StreamMetaCommon {
  errorCode: number
  error: string
  retryable: boolean
}

/* ==================================================================
 * 工具调用（仅 Chat 链路）
 * ================================================================== */

export interface StreamToolCall extends StreamMetaCommon {
  toolCode: string
  /** 展示给用户的中文名（"正在使用知识搜索"）。 */
  toolName: string
  input: unknown
}

export interface StreamToolResult extends StreamMetaCommon {
  toolCode: string
  result: unknown
}

/* ==================================================================
 * 联合类型：给"一个可能来自任意链路的事件"用
 * ================================================================== */

/**
 * 任意 SSE 帧的 data。
 *
 * 刻意**保持宽松**（未知事件落到 `unknown` 而不是报错）：
 * 后端将来加事件时，前端不应该崩——它只需要"不认识就忽略"。
 */
export type AnyStreamEvent =
  | { event: 'meta'; data: ChatStreamMeta | RagStreamMeta }
  | { event: 'retrieval'; data: StreamRetrieval }
  | { event: 'delta'; data: StreamDelta }
  | { event: 'usage'; data: StreamUsage }
  | { event: 'done'; data: ChatStreamDone | RagStreamDone }
  | { event: 'error'; data: ChatStreamError | RagStreamError }
  | { event: 'tool_call'; data: StreamToolCall }
  | { event: 'tool_result'; data: StreamToolResult }

/* ==================================================================
 * 类型守卫：让"哪条链路"成为可判定的
 * ================================================================== */

/**
 * `done` 是 Chat 还是 RAG —— 用判别字段，但要**容忍字段缺失**。
 *
 * ## 为什么这里从"严格判定"改成"宽容判定"（真机实测纠正）
 *
 * 原实现是 `'stopped' in data` / `'stop' in data`，注释还写着"这是判定链路的可靠依据"。
 * 实测抓到的 **正常完成** 的 done 帧只有两个字段：
 *
 * ```json
 * {"citations":[...4 条...],"finishReason":"stop"}
 * ```
 *
 * 因为后端只在**用户主动停止**时才补 `stopped`（停止分支），正常完成走 `donePayload(finishReason)`。
 * 于是两个守卫**同时为 false**，`useChatStream` 里 `if (isChatDone(d))` 整段被跳过，
 * `setCitations` 永不执行 —— 症状就是**流式回答的引用面板永远不出现**，
 * 而检索到的资料其实早就随 done 帧送到前端了。
 *
 * 教训：**判别字段若只在部分分支出现，它就不能当类型判别子。**
 * 取舍：宁可判错链路（两者字段高度重合、消费方都只读 citations），
 * 也不能因为判不出来就**丢掉数据**。
 */
export function isChatDone(data: ChatStreamDone | RagStreamDone): data is ChatStreamDone {
  if ('stopped' in data) return true
  if ('stop' in data) return false
  // 两个判别字段都没有 = 正常完成的 done。再用"哪边特有"的字段判一次；
  // 都没有就归为 Chat（RAG 特有的 recordId 缺失时，归 Chat 最安全）。
  return !('recordId' in data)
}

export function isRagDone(data: ChatStreamDone | RagStreamDone): data is RagStreamDone {
  return !isChatDone(data)
}

/** 错误事件同理。 */
export function isChatError(data: ChatStreamError | RagStreamError): data is ChatStreamError {
  return 'code' in data
}
