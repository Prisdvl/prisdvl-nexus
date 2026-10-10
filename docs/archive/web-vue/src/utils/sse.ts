import { getToken } from '@/api/client'

/**
 * SSE over POST 的通用读取器。
 *
 * <p>为什么不用浏览器原生 EventSource：它只支持 GET，且无法携带 Authorization 头。
 * 用 fetch + ReadableStream 手工解析，JWT 就能走正常请求头，也不必把凭证塞进 URL
 * （那种做法会把 Token 写进访问日志）。
 *
 * <p>为什么单独抽出来：知识库问答与 Agent 对话是两条链路，但**SSE 帧的解析规则只有一套**
 * （`event:` / `data:` 两种行、帧可能被 TCP 分片切开、非 JSON 心跳要忽略）。
 * 复制两份的结果必然是"改了其中一处的 bug，另一处还留着"。
 */
export interface SseFrame {
  event: string
  data: unknown
}

export interface SseOptions {
  signal?: AbortSignal
  onFrame: (frame: SseFrame) => void
}

/**
 * 发起一次流式请求并逐帧回调。
 *
 * 后端保证的顺序由各接口自己定义（问答是 meta → delta… → done；
 * Agent 对话在 delta 之间还会插入 tool_call / tool_result）。
 */
export async function postSse(
  url: string,
  body: unknown,
  options: SseOptions
): Promise<void> {
  const response = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${getToken()}`
    },
    body: JSON.stringify(body),
    signal: options.signal
  })
  if (!response.ok || !response.body) {
    // 失败发生在开流之前时，后端返回的是统一 JSON（例如 4006 模型未注册、8001 会话不存在）。
    // 把里面的 message 带出来，否则用户只看得到"HTTP 400"这种无法排查的提示。
    let detail = ''
    try {
      const body = (await response.json()) as { message?: string }
      detail = body?.message ?? ''
    } catch {
      detail = ''
    }
    throw new Error(detail || `流式请求失败（HTTP ${response.status}）`)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let eventName = ''

  // 逐块读取：SSE 帧可能被 TCP 分片切开，因此要先缓冲、按换行切分、把最后一段留到下一轮
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split('\n')
    buffer = lines.pop() ?? ''
    for (const line of lines) {
      if (line.startsWith('event:')) {
        eventName = line.slice(6).trim()
      } else if (line.startsWith('data:')) {
        const raw = line.slice(5).trim()
        if (!raw) continue
        let payload: unknown
        try {
          payload = JSON.parse(raw)
        } catch {
          // 非 JSON 行（心跳等）直接忽略，不影响整条流
          continue
        }
        // 回调放在 try 之外：回调里抛出的业务错误（例如后端的 error 事件）
        // 必须原样传出去，不能被"忽略心跳"的兜底吞掉
        options.onFrame({ event: eventName, data: payload })
      }
    }
  }
}
