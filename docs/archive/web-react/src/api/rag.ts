import { postSse } from '@/lib/sse'
import { api } from './client'
import type { RagCitation } from './types/chat'
import type { RagStreamDone, StreamRetrieval } from './types/sse'

/**
 * 知识库问答（RAG）—— 与「对话」不同，这条链路**不需要会话**。
 *
 * ```text
 * POST /api/knowledge-bases/{kbId}/ask/stream   SSE：meta → retrieval → delta… → done
 * POST /api/knowledge-bases/{kbId}/ask          非流式（备用）
 * GET  /api/knowledge-bases/{kbId}/qa-history   问答留档
 * ```
 *
 * ## 为什么不用 chat 那条链路
 *
 * 「对话」是**有状态**的：会话承载历史、记忆、绑定的库与 Agent（`/api/chat/stream` 要 conversationId）。
 * 「知识库问答」是**无状态**的：一次提问一次回答，历史由调用方回传（`history` 字段，服务端只取最近 3 轮）。
 * 两者共用同一份检索与提示词（后端 `RagAnswerService`），但入口不同——
 * 硬把它们合成一个接口，等于给"只想问一次"的用户强加一个会话实体。
 */

export interface AskHistoryTurn {
  question: string
  /** 上一轮回答（服务端会截断到开头 300 字，避免上下文膨胀） */
  answer: string
}

export interface AskStreamOptions {
  question: string
  /** 省略 = 用知识库自身配置的 retrieval_top_k（不要在前端写死条数） */
  topK?: number
  history?: AskHistoryTurn[]
  signal?: AbortSignal
  onMeta?: (meta: { citations: RagCitation[]; contextTokens: number; reranked: boolean; empty: boolean }) => void
  onRetrieval?: (retrieval: StreamRetrieval) => void
  onDelta?: (text: string) => void
  onDone?: (done: RagStreamDone) => void
}

/**
 * 发起知识库流式问答。
 *
 * 事件顺序（后端契约）：`meta → retrieval → delta… → done`。
 * 引用在 `done` 里下发——因为 `referenced` 要在完整回答里比对编号才算得出来。
 */
export async function askKnowledgeBaseStream(
  kbId: string,
  options: AskStreamOptions
): Promise<void> {
  const body: Record<string, unknown> = { question: options.question }
  if (options.topK != null) body.topK = options.topK
  if (options.history?.length) body.history = options.history

  await postSse(`/knowledge-bases/${kbId}/ask/stream`, body, {
    ...(options.signal ? { signal: options.signal } : {}),
    onFrame: (frame) => {
      switch (frame.event) {
        case 'meta':
          options.onMeta?.(
            frame.data as { citations: RagCitation[]; contextTokens: number; reranked: boolean; empty: boolean }
          )
          break
        case 'retrieval':
          options.onRetrieval?.(frame.data as StreamRetrieval)
          break
        case 'delta': {
          const d = frame.data as { text?: string }
          if (d.text) options.onDelta?.(d.text)
          break
        }
        case 'done':
          options.onDone?.(frame.data as RagStreamDone)
          break
        default:
          // 未知事件一律忽略：后端将来加事件时前端不该崩（与 SSE 解析器的宽容策略一致）
          break
      }
    }
  })
}

/** 问答留档记录（用于"当时依据了哪些资料"）。 */
export interface QaRecord {
  recordId: string | null
  answer: string
  citations: RagCitation[]
  contextTokens: number
  truncated: number
  reranked: boolean
  costMillis: number
}

export function listQaHistory(kbId: string, limit = 10, signal?: AbortSignal) {
  return api.get<QaRecord[]>(`/knowledge-bases/${kbId}/qa-history`, {
    query: { limit },
    ...(signal ? { signal } : {})
  })
}
