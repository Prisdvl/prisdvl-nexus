/**
 * Agent 自有流式端点（`POST /api/agent/runs/stream`）的客户端。
 *
 * 为什么不复用 `lib/sse.ts`：那套处理器绑的是会话流的帧名（delta / retrieval /
 * tool_call / approval_required / usage），这里的帧只有 meta / status / step / done
 * 四种，而且没有文本增量。硬塞进同一个接口会让两套契约互相污染，所以单独一份，
 * 帧解析方式保持一致（按空行切帧，`event:` 与 `data:` 各占一行）。
 */
import { API_BASE, tokenStore } from '@/lib/api'

export interface AgentRunFrame {
  /** 帧名：meta / status / step / done，或 SSE 默认的 message */
  event: string
  /** data: 行解析出的对象；解析失败为 null */
  data: Record<string, unknown> | null
}

export interface AgentRunHandlers {
  onFrame?: (frame: AgentRunFrame) => void
  /** 非 2xx 或没有响应体时的错误信息（后端业务错误会带 message 字段） */
  onError?: (message: string) => void
}

export async function streamAgentRun(
  body: { agentId: string; question: string; conversationId?: string },
  h: AgentRunHandlers,
  signal?: AbortSignal,
): Promise<void> {
  const token = tokenStore.get()
  const res = await fetch(API_BASE + '/agent/runs/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(token ? { Authorization: 'Bearer ' + token } : {}),
    },
    body: JSON.stringify(body),
    signal,
  })
  if (!res.ok || !res.body) {
    let msg = 'HTTP ' + res.status
    try {
      const j = await res.json()
      if (j && j.message) msg = String(j.message)
    } catch {
      /* 非 JSON 错误体，用状态码兜底 */
    }
    h.onError?.(msg)
    return
  }
  const reader = res.body.getReader()
  const dec = new TextDecoder()
  let buf = ''
  const emit = (frame: string) => {
    let event = 'message'
    const dataLines: string[] = []
    for (const line of frame.split('\n')) {
      if (line.startsWith('event:')) event = line.slice(6).trim()
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
    }
    if (dataLines.length === 0) {
      h.onFrame?.({ event, data: null })
      return
    }
    let data: Record<string, unknown> | null = null
    try {
      data = JSON.parse(dataLines.join('\n')) as Record<string, unknown>
    } catch {
      data = null
    }
    h.onFrame?.({ event, data })
  }
  for (;;) {
    const r = await reader.read()
    if (r.done) break
    buf += dec.decode(r.value, { stream: true })
    let idx = buf.indexOf('\n\n')
    while (idx >= 0) {
      emit(buf.slice(0, idx))
      buf = buf.slice(idx + 2)
      idx = buf.indexOf('\n\n')
    }
  }
  if (buf.trim()) emit(buf)
}
