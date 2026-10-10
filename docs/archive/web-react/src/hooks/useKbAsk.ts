import { useCallback, useEffect, useRef, useState } from 'react'
import { askKnowledgeBaseStream, type AskHistoryTurn } from '@/api/rag'
import { isApiError } from '@/lib/errors'
import type { RagCitation } from '@/api/types/chat'
import type { StreamRetrieval } from '@/api/types/sse'

/**
 * 知识库问答的流式状态（无会话版）。
 *
 * ## 与 useChatStream 的关系：共用解析器，不共用状态
 *
 * 两者的 SSE 解析走同一个 `lib/sse.ts`（分片/心跳/CRLF 都处理过了），
 * 但**状态形状不同**：会话流式要维护 messageId（消息落库的锚点）与工具调用痕迹，
 * 知识库问答只要"回答 + 引用"。
 * 硬合成一个 hook 会让两边都背上对方不需要的字段——
 * 而"某一边用不到的字段"正是后来出 bug 的地方（比如把 tool 事件当成 delta）。
 */

export interface QaTurn {
  question: string
  answer: string
}

export interface UseKbAskResult {
  streamingText: string
  retrieval: StreamRetrieval | null
  citations: RagCitation[]
  streaming: boolean
  error: string | null
  /** 本轮的检索上下文 token 数（来自 meta 帧） */
  contextTokens: number
  /** 重排是否生效（降级时前端应如实告知，而不是假装重排过了） */
  reranked: boolean
  ask: (kbId: string, question: string, topK: number | null, history: AskHistoryTurn[]) => Promise<void>
  /** 停止生成 */
  stop: () => void
  reset: () => void
}

export function useKbAsk(): UseKbAskResult {
  const [streamingText, setStreamingText] = useState('')
  const [retrieval, setRetrieval] = useState<StreamRetrieval | null>(null)
  const [citations, setCitations] = useState<RagCitation[]>([])
  const [streaming, setStreaming] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [contextTokens, setContextTokens] = useState(0)
  const [reranked, setReranked] = useState(false)

  const alive = useRef(true)
  const ctrl = useRef<AbortController | null>(null)

  useEffect(() => {
    alive.current = true
    return () => {
      alive.current = false
      ctrl.current?.abort()
    }
  }, [])

  const reset = useCallback(() => {
    ctrl.current?.abort()
    ctrl.current = null
    setStreamingText('')
    setRetrieval(null)
    setCitations([])
    setStreaming(false)
    setError(null)
    setContextTokens(0)
    setReranked(false)
  }, [])

  const stop = useCallback(() => {
    ctrl.current?.abort()
    ctrl.current = null
    setStreaming(false)
  }, [])

  const ask = useCallback(
    async (kbId: string, question: string, topK: number | null, history: AskHistoryTurn[]) => {
      ctrl.current?.abort()
      const c = new AbortController()
      ctrl.current = c

      // 每次提问都是全新一轮：上一轮的回答与引用必须清掉，
      // 否则用户会看到"新问题配旧引用"
      setStreamingText('')
      setRetrieval(null)
      setCitations([])
      setError(null)
      setContextTokens(0)
      setReranked(false)
      setStreaming(true)

      try {
        await askKnowledgeBaseStream(kbId, {
          question,
          ...(topK != null ? { topK } : {}),
          ...(history.length ? { history } : {}),
          signal: c.signal,
          onMeta: (meta) => {
            if (!alive.current) return
            setContextTokens(meta.contextTokens)
            setReranked(meta.reranked)
            // meta 带的引用只有位置信息（没有正文/是否被引用），
            // 真正权威的是 done 帧里的那份；这里先存下来让面板尽早有骨架。
            if (meta.citations?.length) setCitations(meta.citations)
          },
          onRetrieval: (r) => {
            if (alive.current) setRetrieval(r)
          },
          onDelta: (text) => {
            if (alive.current) setStreamingText((prev) => prev + text)
          },
          onDone: (done) => {
            if (!alive.current) return
            // done 里的引用是最终版（含 referenced），优先采用
            if (done.citations?.length) setCitations(done.citations)
            setStreaming(false)
          }
        })
      } catch (e) {
        if (!alive.current) return
        // 主动停止不是错误：用户在生成中点「停止」时不该看到红色报错
        if (isApiError(e) && e.isAborted) {
          setStreaming(false)
          return
        }
        setError(e instanceof Error ? e.message : String(e))
        setStreaming(false)
      }
    },
    []
  )

  return {
    streamingText,
    retrieval,
    citations,
    streaming,
    error,
    contextTokens,
    reranked,
    ask,
    stop,
    reset
  }
}
