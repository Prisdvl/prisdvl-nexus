/**
 * SSE 客户端（POST + fetch 流）。
 *
 * 不用 EventSource：它只能 GET 且不能带 Authorization 头；本项目的流式对话是
 * POST /api/chat/stream + Bearer 令牌，所以这里手写帧解析。
 * 事件名是后端 SseEventNames 的契约：meta / delta / retrieval / tool_call /
 * tool_result / approval_required / usage / done / error（见 docs/api.md 5.2）。
 */
import { API_BASE, tokenStore } from '@/lib/api'

export interface SseHandlers {
  onMeta?: (d: Record<string, unknown>) => void
  onDelta?: (text: string) => void
  onRetrieval?: (d: Record<string, unknown>) => void
  onToolCall?: (d: Record<string, unknown>) => void
  onToolResult?: (d: Record<string, unknown>) => void
  /** 工具被权限闸门拦下、等待人工审批（payload: runId / approvalId / toolCode / arguments / reason） */
  onApprovalRequired?: (d: Record<string, unknown>) => void
  onUsage?: (d: Record<string, unknown>) => void
  onDone?: (d: Record<string, unknown>) => void
  onError?: (message: string) => void
}

export async function postSse(path: string, body: unknown, h: SseHandlers, signal?: AbortSignal): Promise<void> {
  const token = tokenStore.get()
  const res = await fetch(API_BASE + path, {
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
    try { const j = await res.json(); if (j && j.message) msg = String(j.message) } catch { /* 非 JSON 错误体 */ }
    h.onError?.(msg)
    return
  }
  const reader = res.body.getReader()
  const dec = new TextDecoder()
  let buf = ''
  for (;;) {
    const r = await reader.read()
    if (r.done) break
    buf += dec.decode(r.value, { stream: true })
    let idx = buf.indexOf('\n\n')
    while (idx >= 0) {
      dispatch(buf.slice(0, idx), h)
      buf = buf.slice(idx + 2)
      idx = buf.indexOf('\n\n')
    }
  }
  if (buf.trim()) dispatch(buf, h)
}

function dispatch(frame: string, h: SseHandlers): void {
  let event = 'message'
  const data: string[] = []
  for (const line of frame.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''))
  }
  if (!data.length) return
  let payload: Record<string, unknown>
  try { payload = JSON.parse(data.join('\n')) } catch { return }
  switch (event) {
    case 'meta': h.onMeta?.(payload); break
    case 'delta': h.onDelta?.(String(payload.text ?? '')); break
    case 'retrieval': h.onRetrieval?.(payload); break
    case 'tool_call': h.onToolCall?.(payload); break
    case 'tool_result': h.onToolResult?.(payload); break
    case 'approval_required': h.onApprovalRequired?.(payload); break
    case 'usage': h.onUsage?.(payload); break
    case 'done': h.onDone?.(payload); break
    case 'error': h.onError?.(String(payload.message ?? '生成失败')); break
    default: break
  }
}
