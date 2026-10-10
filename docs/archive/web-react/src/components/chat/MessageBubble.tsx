import { type ReactElement } from 'react'
import type { ChatMessage } from '@/api/types/chat'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'

/**
 * 一条消息。
 *
 * ## 布局
 * ```text
 * 用户：靠右，无气泡底色
 * 助手：靠左，带气泡底色 + 引用角标
 * ```
 * 这是 Phase B §15 的 L1 层：**不需要解释"谁在说话"**——
 * 位置与气泡已经表达了，再加"我：""助手："是噪声。
 */
export interface MessageBubbleProps {
  message: ChatMessage
  /** 正在流式生成（显示脉冲点，且不显示"已停止"之类）。 */
  streaming?: boolean
}

const TIME_FMT = new Intl.DateTimeFormat('zh-CN', {
  hour: '2-digit',
  minute: '2-digit'
})

export function MessageBubble({ message, streaming }: MessageBubbleProps): ReactElement {
  const isUser = message.role === 'USER'
  const created = message.createdAt ? new Date(message.createdAt) : null
  const time =
    created && !Number.isNaN(created.getTime()) ? TIME_FMT.format(created) : null

  return (
    <div
      className={cn('mb-4 flex flex-col gap-1', isUser ? 'items-end' : 'items-start')}
      data-testid="chat-message"
      data-role={message.role}
    >
      <div
        className={cn(
          'max-w-[85%] rounded-[var(--nx-radius-lg)] px-4 py-2.5',
          'text-[var(--nx-text-sm)] leading-[1.7] whitespace-pre-wrap break-words',
          isUser
            ? 'bg-[var(--nx-color-primary-container)] text-[var(--nx-color-text)]'
            : 'bg-[var(--nx-color-surface-muted)] text-[var(--nx-color-text)]'
        )}
      >
        {message.content || (streaming ? '' : '（空）')}
        {/* 流式光标：正在生成时给一个脉冲点，用户知道"还在写" */}
        {streaming ? (
          <span
            aria-hidden="true"
            data-testid="chat-streaming-cursor"
            className="ml-0.5 inline-block h-3 w-[2px] animate-pulse bg-current align-middle"
          />
        ) : null}
      </div>

      {/* 元信息行：时间 · 引用数 · 停止标记 */}
      <div className="flex items-center gap-2 px-1">
        {time ? <span className="text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">{time}</span> : null}
        {message.citations.length > 0 ? (
          <Badge tone="neutral" data-testid="chat-message-citations">
            {message.citations.filter((c) => c.referenced).length || message.citations.length} 条引用
          </Badge>
        ) : null}
        {/* `stopped` 只在 Chat 链路的 done 帧里有；这里读消息上的 status 兜底 */}
        {message.status === 'STOPPED' ? (
          <Badge tone="warning" data-testid="chat-message-stopped">
            已停止
          </Badge>
        ) : null}
        {message.errorMsg ? (
          <span className="text-[var(--nx-text-xs)] text-[var(--nx-color-danger)]">
            {message.errorMsg}
          </span>
        ) : null}
      </div>
    </div>
  )
}
