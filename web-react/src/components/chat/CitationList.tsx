import { useState, type ReactElement } from 'react'
import type { RagCitation } from '@/api/types/chat'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'

/**
 * 引用列表（回答右侧）。
 *
 * ## 显示什么（Phase B §15：L2 用户状态）
 * 序号 · 文档标题 · 标题路径 · 片段摘录。
 *
 * ## 刻意不显示（任务书 §73）
 * - **相似度分数**：那是 L4。用户不需要知道 `score=0.8234` 才能判断该不该信。
 * - **知识库 id / chunkId**：内部标识，点击跳转时才用。
 *
 * ## `referenced` 的用途
 *
 * 后端把**未被引用的**命中也返回了（"便于核对模型有没有漏引"）。
 * 本组件**默认只显示被引用的**，但给出"还有 N 条未引用"的开关——
 * 让用户能自己核对，而不是让前端替模型隐瞒。
 */
export interface CitationListProps {
  citations: RagCitation[]
  className?: string
}

export function CitationList({ citations, className }: CitationListProps): ReactElement {
  const [showAll, setShowAll] = useState(false)
  const referenced = citations.filter((c) => c.referenced)
  const shown = showAll ? citations : referenced
  const hidden = citations.length - referenced.length

  return (
    <aside
      className={cn(
        'hidden w-72 shrink-0 flex-col border-l border-[var(--nx-color-border)] xl:flex',
        className
      )}
      data-testid="chat-citations"
    >
      <div className="border-b border-[var(--nx-color-border)] p-3">
        <h2 className="m-0 text-[var(--nx-text-sm)] font-[var(--nx-weight-medium)]">
          引用（{shown.length}）
        </h2>
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto p-3">
        {shown.length === 0 ? (
          <p className="m-0 text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">
            这次回答没有引用知识库内容。
          </p>
        ) : null}

        {shown.map((c) => (
          <div
            key={`${c.index}-${c.chunkId}`}
            data-testid="chat-citation"
            data-index={c.index}
            className="mb-3 rounded-[var(--nx-radius-md)] border border-[var(--nx-color-border)] p-2.5"
          >
            <div className="mb-1 flex items-center gap-1.5">
              <Badge tone="primary" data-testid="chat-citation-index">
                [{c.index}]
              </Badge>
              <span className="min-w-0 flex-1 truncate text-[var(--nx-text-xs)] font-[var(--nx-weight-medium)]">
                {c.documentTitle ?? '未命名文档'}
              </span>
            </div>
            {c.headingPath ? (
              <p className="m-0 mb-1 text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">
                {c.headingPath}
              </p>
            ) : null}
            <p className="m-0 line-clamp-4 text-[var(--nx-text-xs)] leading-[1.6] text-[var(--nx-color-text-secondary)]">
              {c.snippet}
            </p>
          </div>
        ))}

        {/*
          核对开关。默认**不**显示未引用的命中——
          但要让用户能自己看，而不是让前端替模型隐瞒。
        */}
        {hidden > 0 ? (
          <button
            type="button"
            onClick={() => setShowAll((v) => !v)}
            data-testid="chat-citations-toggle"
            className="mt-1 w-full rounded-[var(--nx-radius-md)] px-2 py-1.5 text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)] hover:bg-[var(--nx-color-surface-muted)]"
          >
            {showAll ? '只看被引用的' : `还有 ${hidden} 条未引用的检索结果`}
          </button>
        ) : null}
      </div>
    </aside>
  )
}
