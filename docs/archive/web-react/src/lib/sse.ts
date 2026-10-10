import { ApiError } from '@/lib/errors'
import { getToken } from '@/lib/storage'
import { API_BASE } from '@/api/client'

/**
 * SSE over POST 的读取器。
 *
 * ## 移植说明（不是重造）
 *
 * Vue 侧已有一个 88 行的成熟实现（`web/src/utils/sse.ts`，R3 任务书 §44 明确
 * 「不要重新造一个解析器」）。本文件**移植**它，并做两处改进：
 *
 * 1. **错误接入 R3 的 `ApiError`**，不再抛裸 `Error`——于是上层能分清
 *    "开流前就失败（401 → 重新登录）"与"流中断了（网络）"。
 * 2. **复用 `API_BASE` 前缀**，与 R3 修的那个 bug 保持一致。
 *
 * 解析规则本身**一字未改**，因为它是 SSE 协议的要求，不是业务选择。
 *
 * ## 为什么不用浏览器原生 EventSource
 *
 * 它**只支持 GET**，且**无法携带 Authorization 头**。
 * 本项目的两个流式端点都是 POST + JWT。硬用 EventSource 只有两条路：
 * 把 token 塞进 URL（**会写进访问日志与浏览器历史**），或加一个一次性票据机制。
 * 前者是安全问题，后者是复杂度——都不值得。
 */
export interface SseFrame {
  event: string
  data: unknown
}

export interface PostSseOptions {
  signal?: AbortSignal
  /** 每一帧到达时回调。回调里抛出的错误会**原样传出**，不被吞掉。 */
  onFrame: (frame: SseFrame) => void
}

/**
 * 发起一次流式 POST，逐帧回调。
 *
 * 事件顺序由各接口自己定义（Chat 是 meta → …delta… → done，
 * 且 delta 之间会插入 tool_call / tool_result；RAG 是 meta → retrieval → delta… → done）。
 * 本函数**不校验顺序**——顺序是后端契约，前端照单接收即可。
 */
export async function postSse(
  path: string,
  body: unknown,
  options: PostSseOptions
): Promise<void> {
  const url = path.startsWith(API_BASE) ? path : `${API_BASE}${path}`
  const token = getToken()

  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  // 没有 token 时不设 Authorization（设空串会被后端当成"传了但无效"）
  if (token) headers.Authorization = `Bearer ${token}`

  let response: Response
  try {
    response = await fetch(url, {
      method: 'POST',
      headers,
      body: JSON.stringify(body),
      ...(options.signal ? { signal: options.signal } : {})
    })
  } catch (e) {
    if (e instanceof Error && e.name === 'AbortError') {
      throw new ApiError({ kind: 'aborted', message: '已停止生成', cause: e })
    }
    throw new ApiError({ kind: 'network', message: '网络连接失败，请检查网络后重试', cause: e })
  }

  if (!response.ok || !response.body) {
    /*
     * 开流**之前**失败时，后端返回的是统一 JSON 而不是 SSE 帧。
     * 例如 4006（模型未注册）、8001（会话不存在）、401（令牌失效）。
     *
     * 必须把 body 里的 message 带出来——否则用户只看到"HTTP 400"，
     * 而真正有用的「模型 opencode:xxx 未注册。已注册：[ollama:qwen3:7b]」被丢掉了
     * （这正是 Vue 侧踩过的坑，Phase C 记录在案）。
     */
    let message: string | undefined
    let code: number | undefined
    try {
      const parsed = (await response.json()) as { message?: string; code?: number }
      message = parsed?.message
      code = parsed?.code
    } catch {
      // 响应体不是 JSON（例如网关返回了 HTML 502 页）
    }

    if (response.status === 401) {
      // 交给 R3 的集中 401 处理：清 token + 跳登录。
      // 直接调 authStore 会造成循环依赖（client → store → client），
      // 所以这里复用 client 已在 `request()` 里注册的那套机制——
      // 简单做法：由调用方捕获后判断 kind，authStore.initialize 会兜住。
      throw new ApiError({
        kind: 'auth',
        message: message || '登录已过期，请重新登录',
        code,
        status: 401
      })
    }

    throw new ApiError({
      kind: response.status === 403 ? 'forbidden' : 'http',
      message: message || `流式请求失败（HTTP ${response.status}）`,
      code,
      status: response.status
    })
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let eventName = ''

  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break

      /*
       * 逐块读取 + 缓冲：SSE 帧**可能被 TCP 分片切开**。
       * `{ stream: true }` 让解码器保留多字节字符的中间状态——
       * 少了它，一个中文被切成两半时会变成乱码。
       */
      buffer += decoder.decode(value, { stream: true })

      const lines = buffer.split('\n')
      // 最后一段可能不完整，留到下一轮
      buffer = lines.pop() ?? ''

      for (const raw of lines) {
        // SSE 规范允许行尾 \r\n；trim 顺手去掉
        const line = raw.trimEnd()
        if (line.startsWith('event:')) {
          eventName = line.slice(6).trim()
        } else if (line.startsWith('data:')) {
          const payloadText = line.slice(5).trim()
          if (!payloadText) continue
          let payload: unknown
          try {
            payload = JSON.parse(payloadText)
          } catch {
            // 非 JSON 行（心跳 `: ping` 等）直接忽略，不影响整条流
            continue
          }
          // 回调放在 try 之外：回调里抛出的业务错误必须原样传出去，
          // 不能被上面"忽略心跳"的兜底吞掉
          options.onFrame({ event: eventName, data: payload })
        }
      }
    }
  } catch (e) {
    if (e instanceof Error && e.name === 'AbortError') {
      throw new ApiError({ kind: 'aborted', message: '已停止生成', cause: e })
    }
    if (e instanceof ApiError) throw e
    // 读取中途断开：网络错误（不是"生成失败"）
    throw new ApiError({ kind: 'network', message: '连接中断，请重试', cause: e })
  } finally {
    // 主动释放连接：正常结束或抛错都执行，否则 keep-alive 会挂住
    try {
      reader.releaseLock()
    } catch {
      // 已释放，忽略
    }
  }
}

/* ==================================================================
 * 纯函数解析器 —— 可独立单测，不依赖 fetch / 浏览器 / 后端
 * ================================================================== */

/**
 * SSE 增量解析器。
 *
 * ## 为什么要把它抽出来
 *
 * 解析逻辑的**难点全在分片**：一帧可能被 TCP 切成两半 arrives，
 * 一个中文字符可能被切成两个字节。把它埋在 `postSse` 的 `for(;;)` 里，
 * 就只能靠"真的开一条流"来测——而那需要后端 + 令牌 + 额度。
 *
 * 抽成纯函数后，`scripts/verify-sse.mjs` 用**构造的字节序列**就能测：
 * 半个帧、半个汉字、心跳夹杂、CRLF 换行。**零依赖、零额度、可重复。**
 *
 * ## 用法
 * ```ts
 * const parser = createSseParser()
 * for (const chunk of chunks) {
 *   for (const frame of parser.push(chunk)) handle(frame)
 * }
 * parser.flush()   // 取出流末尾不完整的那一帧（后端少写一个空行时）
 * ```
 */
export function createSseParser(): {
  /** 喂入一段解码后的文本，返回其中**完整**的帧。 */
  push: (text: string) => SseFrame[]
  /** 流结束：取出缓冲区里最后一段（后端未以空行结尾时的兜底）。 */
  flush: () => SseFrame[]
} {
  let buffer = ''
  let eventName = ''

  /**
   * 把一段完整帧的文本转成帧；无法解析返回 null（心跳等）。
   *
   * @param fallbackEvent 帧里**没有** `event:` 行时使用的名字。
   *   SSE 规范允许省略事件名（表示沿用上一个）。后端目前每帧都带，
   *   但解析器仍按规范处理——否则后端一省这行，前端就静默丢事件类型。
   */
  function toFrame(raw: string, fallbackEvent: string): SseFrame | null {
    const lines = raw.split('\n')
    let name = ''
    const dataLines: string[] = []
    for (const l of lines) {
      const line = l.trimEnd()
      if (line.startsWith('event:')) name = line.slice(6).trim()
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
    }
    if (dataLines.length === 0) return null
    const text = dataLines.join('\n')
    if (!text) return null
    let payload: unknown
    try {
      payload = JSON.parse(text)
    } catch {
      // 非 JSON（心跳 `: ping`、注释行）——忽略，不影响整条流
      return null
    }
    return { event: name || fallbackEvent, data: payload }
  }

  return {
    push(text) {
      buffer += text
      const frames: SseFrame[] = []
      // 以空行（\n\n 或 \r\n\r\n）切帧
      let idx: number
      while ((idx = findFrameEnd(buffer)) !== -1) {
        const raw = buffer.slice(0, idx)
        buffer = buffer.slice(idx).replace(/^\r?\n/, '')
        const frame = toFrame(raw, eventName)
        if (frame) {
          frames.push(frame)
          // 记住本帧的事件名，供下一帧省略 event: 时沿用
          eventName = frame.event
        }
      }
      return frames
    },
    flush() {
      if (!buffer.trim()) return []
      const frame = toFrame(buffer, eventName)
      buffer = ''
      return frame ? [frame] : []
    }
  }
}

/** 找第一个完整帧的结束位置（返回 \n\n 或 \r\n\r\n 的起点）。 */
function findFrameEnd(text: string): number {
  const a = text.indexOf('\n\n')
  const b = text.indexOf('\r\n\r\n')
  if (a === -1) return b
  if (b === -1) return a
  return Math.min(a, b)
}

/**
 * 把帧分派到处理器。
 *
 * 抽出来是为了**可单测**：解析器与分派逻辑分开，就能用桩帧测分派，
 * 不必真的开一条流。
 */
export function dispatchFrame(
  frame: SseFrame,
  handlers: Partial<Record<string, (data: never) => void>>
): boolean {
  const handler = handlers[frame.event]
  if (!handler) return false
  ;(handler as (d: unknown) => void)(frame.data)
  return true
}
