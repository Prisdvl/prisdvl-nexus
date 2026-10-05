/**
 * SSE 事件类型的**唯一真实来源**（Phase C §十二 G，Phase A 标记的 P1）。
 *
 * ## 为什么不是"合并成一个类型"
 *
 * 改造前 `api/chat.ts` 与 `api/knowledge.ts` 各自声明了 `StreamMeta` / `StreamDone` /
 * `StreamRetrieval`，同名**不同形**。但实测后端（`RagStreamServiceImpl` 与
 * `ChatStreamServiceImpl`）之后会发现：**它们不是复制粘贴出来的重复，而是两条链路
 * 真的发了不同的形状**：
 *
 * | 事件 | 会话流（`/api/chat/stream`） | 知识库问答流 | 结论 |
 * | --- | --- | --- | --- |
 * | `meta` | requestId + conversationId + userMessageId + assistantMessageId + modelRole + mode | requestId + citations + contextTokens + truncated + reranked + empty | 同名不同形（**后端事实**） |
 * | `retrieval` | `RetrievalEvent.of(...)` | `RetrievalEvent.of(...)` —— **同一个构造器** | 形状完全一致 |
 * | `done` | finishReason + stopped + citations | finishReason + error + errorCode + retryable + recordId + citations | 部分重叠 |
 *
 * 所以"合并成一个形状"会与后端不符并破坏功能；"保留两份"又回到了分叉。
 * 正解是**分层单一来源**：公共部分定义一次，链路专有部分显式 `extends`。
 *
 * ## 特别保住 `failedKnowledgeBases`
 *
 * 改造前 `api/knowledge.ts` 的 `StreamRetrieval` **漏了这个字段**，
 * 而后端 `RetrievalEvent` 两条链路都发它（`payload.put("failedKnowledgeBases", ...)`）。
 * 缺它的直接后果：知识库问答页在"某个库检索失败"时无法显示"这次少搜了谁"，
 * 界面会**假装全都搜过了**。这是本文件存在的首要理由。
 *
 * ## 与后端的对应关系
 *
 * 权威来源（改动前请先读它们，不要凭本文件的注释推断）：
 *
 * ```text
 * nexus-app/.../chat/stream/SseEventNames.java    事件名常量
 * nexus-app/.../chat/stream/RetrievalEvent.java  retrieval 帧的字段
 * nexus-app/.../chat/service/impl/ChatStreamServiceImpl.java
 * nexus-app/.../knowledge/service/impl/RagStreamServiceImpl.java
 * ```
 */

/* ============================================================
 * 事件名（与服务端 SseEventNames 一致）
 * ============================================================ */

export const SSE_EVENT = {
  META: 'meta',
  RETRIEVAL: 'retrieval',
  TOOL_CALL: 'tool_call',
  DELTA: 'delta',
  DONE: 'done',
  ERROR: 'error'
} as const

export type SseEventName = (typeof SSE_EVENT)[keyof typeof SSE_EVENT]

/* ============================================================
 * 引用（citation）
 *
 * 两条链路的引用**形状本来就不同**，不是重复定义：
 * 会话流多一个 `knowledgeBaseId`（多库时要知道是哪来的），
 * 问答流多一个 `referenced`（回答是否真的引了它，便于核对模型有没有漏引）。
 * 但 `snippet` 的可选性也不同——会话流允许为 null。
 * ============================================================ */

export interface ChatCitation {
  /** 资料块编号，与回答里的 [n] 对应 */
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
  /** 回答是否真的引用了它（未引用也返回，便于核对模型有没有漏引） */
  referenced: boolean
}

export interface RagCitation {
  index: number
  chunkId: string
  documentId: string
  documentTitle: string | null
  headingPath: string | null
  snippet: string
  score: number
  /** 回答是否真的引用了它（未引用也返回，便于核对模型有没有漏引） */
  referenced: boolean
}

/**
 * `meta` 帧里的引用**预览**（问答流）。
 *
 * 与 {@link RagCitation} 的差别：没有 index / snippet / score / referenced——
 * 因为 `meta` 到达时回答还没开始，编号和正文都不存在。
 * 这一点以前被 `StreamMeta` 顶替掉，害得"meta 里的 citations"和
 * "done 里的 citations"共用一个类型，而它们的字段根本不同。
 */
export interface RagCitationPreview {
  chunkId: string
  documentId: string
  documentTitle: string | null
  headingPath: string | null
}

/* ============================================================
 * retrieval 帧 —— 两条链路共用（后端同一个 RetrievalEvent.of）
 * ============================================================ */

export interface StreamRetrievalFailure {
  knowledgeBaseId: string
  knowledgeBaseName: string | null
  /** 后端给的人话原因，例如"ES 连接被拒绝"。界面要如实展示，不加工。 */
  reason: string
}

export interface StreamRetrievalHit {
  chunkId: string
  documentId: string
  /** 这一片出自哪个知识库。问答流固定单库，值恒等于路径里的那个库。 */
  knowledgeBaseId: string | null
  documentTitle: string | null
  headingPath: string | null
  score: number
  /** `bm25` | `vector` | `rrf` 等，检索卡片据此显示"来自哪几路"。 */
  source: string
}

export interface StreamRetrieval {
  /** 本次检索覆盖的知识库。会话流可能多库，问答流恒为单元素。 */
  knowledgeBaseIds: string[]
  count: number
  hits: StreamRetrievalHit[]
  contextTokens: number
  /** 被 Token 预算截断的段数 */
  truncated: number
  reranked: boolean
  /** 没有可用结果（注意：与"检索失败"不同——失败看 failedKnowledgeBases） */
  empty: boolean
  /**
   * **本次没能成功检索的知识库**。
   *
   * 与 `count` 一起读才有意义：`count = 5, failedKnowledgeBases = [内部 FAQ]` 说的是
   * "这 5 段只来自其余库"，而不是"内部 FAQ 里没有相关内容"。
   *
   * 改造前 `api/knowledge.ts` 漏了这个字段——界面会假装全都搜过了。
   */
  failedKnowledgeBases: StreamRetrievalFailure[]
}

/* ============================================================
 * meta 帧 —— 公共只有 requestId，其余按链路分
 * ============================================================ */

export interface StreamMetaCommon {
  requestId: string
}

export interface ChatStreamMeta extends StreamMetaCommon {
  conversationId: string
  userMessageId: string
  assistantMessageId: string
  modelRole: string
  mode: string
}

export interface RagStreamMeta extends StreamMetaCommon {
  /** 引用预览，形状见 {@link RagCitationPreview}。 */
  citations: RagCitationPreview[]
  contextTokens: number
  truncated: number
  reranked: boolean
  empty: boolean
}

/* ============================================================
 * done 帧
 * ============================================================ */

export interface StreamDoneCommon {
  finishReason?: string
}

export interface ChatStreamDone extends StreamDoneCommon {
  /** 用户主动点了"停止生成"。前端据此区分"被中断"与"正常结束"。 */
  stopped?: boolean
  citations?: ChatCitation[]
}

export interface RagStreamDone extends StreamDoneCommon {
  error?: string
  errorCode?: number
  /** 服务端标记这个错误是否值得用户重试。界面据此决定要不要给"重试"动作。 */
  retryable?: boolean
  /** 留档记录 id：有值表示这次问答已落库，可在问答历史里回看。 */
  recordId?: string
  citations?: RagCitation[]
}

/* ============================================================
 * usage（仅会话流发出）
 *
 * 问答流的 `RagStreamServiceImpl` 没有任何 usage 发送点，
 * 所以 usage 不进 meta/done 帧，它由会话流单独的 usage 通道给。
 * 放在这里是因为它同样属于 SSE 协议的一部分。
 * ============================================================ */

export interface StreamUsage {
  promptTokens?: number
  completionTokens?: number
  totalTokens?: number
  latencyMs?: number
  firstTokenMs?: number
}

/** 工具调用帧（会话流独有，AGENT 模式）。 */
export interface StreamToolCall {
  tool: string
  input: string
  /** 生成结束后回填的摘要；进行中为 undefined。 */
  resultSummary?: string
}
