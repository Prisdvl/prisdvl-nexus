import { useCallback, useEffect, useRef, useState } from 'react'
import { stopStream } from '@/api/chat'
import type { RagCitation } from '@/api/types/chat'
import {
  isChatDone,
  isChatError,
  type ChatStreamDone,
  type ChatStreamError,
  type ChatStreamMeta,
  type StreamRetrieval,
  type StreamToolCall
} from '@/api/types/sse'
import { postSse } from '@/lib/sse'
import { getErrorMessage, isApiError } from '@/lib/errors'

/**
 * 消费一次流式回答。
 *
 * ## 它做什么
 *
 * 把 `lib/sse.ts` 解析出的帧**分派成 UI 状态**。解析规则是 R4b 定好的
 * （且有 27 项桩帧验证），本 hook 只关心"帧 → 状态"的映射。
 *
 * ## 关键设计：delta 是**累积**的，不是追加
 *
 * 后端每个 `delta` 帧带的是**当前累积文本**，不是增量片段（实测确认）。
 * 所以每收到一个 delta 就**整体替换** `streamingText`。
 * 若按"追加"处理，界面会变成"你你你好你好…"——这是 R8 最容易踩的坑。
 *
 * ## 停止生成需要 `requestId`
 *
 * 它只在 `meta` 帧里给。所以 meta 到达后必须**立刻**存住，
 * 否则用户点"停止"时没有 id 可用。
 */
export interface UseChatStreamResult {
  /** 正在流式生成的文本（累积）。空串表示当前没有生成。 */
  streamingText: string
  /** 本次生成的 assistant 消息 id（来自 meta）。 */
  assistantMessageId: string | null
  /** 检索命中（来自 retrieval 帧）。**RAG 模式下这是"引用了哪些资料"的依据。 */
  retrieval: StreamRetrieval | null
  citations: RagCitation[]
  toolCalls: StreamToolCall[]
  /** 是否正在生成（含"已提交但 meta 还没来"）。 */
  streaming: boolean
  /** 生成结束后的统计（来自 done 帧）。 */
  done: ChatStreamDone | null
  error: string | null
  /** 启动一次流式回答。 */
  start: (conversationId: string, content: string) => Promise<void>
  /** 停止生成（走后端，让它正常收尾而不是粗暴断连）。 */
  stop: () => Promise<void>
  /** 清空本次生成的所有状态。切换会话时调用。 */
  reset: () => void
}

export function useChatStream(): UseChatStreamResult {
  const [streamingText, setStreamingText] = useState('')
  const [assistantMessageId, setAssistantMessageId] = useState<string | null>(null)
  const [requestId, setRequestId] = useState<string | null>(null)
  const [retrieval, setRetrieval] = useState<StreamRetrieval | null>(null)
  const [citations, setCitations] = useState<RagCitation[]>([])
  const [toolCalls, setToolCalls] = useState<StreamToolCall[]>([])
  const [streaming, setStreaming] = useState(false)
  const [done, setDone] = useState<ChatStreamDone | null>(null)
  const [error, setError] = useState<string | null>(null)

  const seq = useRef(0)
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
    seq.current++
    ctrl.current?.abort()
    ctrl.current = null
    setStreamingText('')
    setAssistantMessageId(null)
    setRequestId(null)
    setRetrieval(null)
    setCitations([])
    setToolCalls([])
    setStreaming(false)
    setDone(null)
    setError(null)
  }, [])

  const start = useCallback(async (conversationId: string, content: string) => {
    const mySeq = ++seq.current
    ctrl.current?.abort()
    const c = new AbortController()
    ctrl.current = c

    // 每次提问都是全新的生成，上一轮的痕迹全部清掉
    setStreamingText('')
    setAssistantMessageId(null)
    setRequestId(null)
    setRetrieval(null)
    setCitations([])
    setToolCalls([])
    setDone(null)
    setError(null)
    setStreaming(true)

    try {
      await postSse(
        '/chat/stream',
        { conversationId, content },
        {
          signal: c.signal,
          onFrame: (frame) => {
            // 过期帧直接丢弃：切换会话后旧流的响应不该污染新会话
            if (mySeq !== seq.current || !alive.current) return

            switch (frame.event) {
              case 'meta': {
                const m = frame.data as ChatStreamMeta
                setRequestId(m.requestId)
                setAssistantMessageId(m.assistantMessageId ?? null)
                break
              }
              case 'retrieval': {
                setRetrieval(frame.data as StreamRetrieval)
                break
              }
              case 'delta': {
                // **整体替换**，不是追加 —— 后端给的是累积文本
                const d = frame.data as { text: string }
                setStreamingText(d.text ?? '')
                break
              }
              case 'tool_call': {
                const t = frame.data as StreamToolCall
                setToolCalls((prev) => [...prev, t])
                break
              }
              case 'tool_result': {
                // 结果不进 UI 列表（那是 R9 的事），这里只忽略
                break
              }
              case 'usage': {
                // 用量在 done 之后从消息记录读，这里不单独存
                break
              }
              case 'done': {
                const d = frame.data as ChatStreamDone
                if (isChatDone(d)) {
                  setDone(d)
                  // done 里带完整引用，优先用它（比 retrieval 更权威：
                  // retrieval 是"搜到什么"，citations 是"回答实际引用了什么"）
                  if (d.citations?.length) setCitations(d.citations)
                }
                setStreaming(false)
                break
              }
              case 'error': {
                const e = frame.data as ChatStreamError
                setError(isChatError(e) ? e.message : getErrorMessage(e))
                setStreaming(false)
                break
              }
              default:
                // 未知事件：忽略。后端加新事件时前端不该崩。
                break
            }
          }
        }
      )
    } catch (e) {
      if (mySeq !== seq.current || !alive.current) return
      if (isApiError(e) && e.isAborted) {
        // 用户主动停止：不是错误
        setStreaming(false)
        return
      }
      setError(getErrorMessage(e))
      setStreaming(false)
    } finally {
      if (mySeq === seq.current) ctrl.current = null
    }
  }, [])

  const stop = useCallback(async () => {
    const id = requestId
    // 停不掉的两���：还没拿到 requestId（meta 没来）/ 已经在停
    if (!id || !streaming) return
    try {
      // 走**后端**的 stop 而不是只 abort 本地连接：
      // 后端才知道要把已生成的部分落库，否则这次回答就白跑了。
      await stopStream(id)
    } catch {
      // 停止失败不打扰用户——它已经停了或即将停
    }
    ctrl.current?.abort()
    setStreaming(false)
  }, [requestId, streaming])

  return {
    streamingText,
    assistantMessageId,
    retrieval,
    citations,
    toolCalls,
    streaming,
    done,
    error,
    start,
    stop,
    reset
  }
}
