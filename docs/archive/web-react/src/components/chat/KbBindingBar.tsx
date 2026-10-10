import { useCallback, useEffect, useState, type ReactElement } from 'react'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { updateConversationKbs } from '@/api/chat'
import { listKnowledgeBases } from '@/api/knowledge'
import type { Conversation } from '@/api/types/chat'
import type { KnowledgeBase } from '@/api/types/knowledge'

/**
 * 会话的知识库绑定条。
 *
 * ## 为什么需要它
 *
 * 创建 RAG 会话时前端默认**绑定全部知识库**（少一步操作，是合理的默认值）。
 * 但默认值不等于正确的值：用户的库可能横跨"工作"和"私人"，
 * 一次提问检索到无关的库会让回答跑偏，而他**看不到也改不了**。
 * 这个条把"这次检索哪些库"变成可见、可改的东西。
 *
 * ## 只对 RAG 会话显示
 *
 * 别的模式（PLAIN/AGENT）没有"检索哪些库"这回事——
 * AGENT 模式下库是由 Agent 定义的，PLAIN 根本不检索。
 * 给它们显示一个空的绑定条只会让人以为"绑定坏了"。
 *
 * ## 为什么改绑后**不**清空历史消息
 *
 * 知识库是"数据来源"，换掉只影响后续轮次检索到什么；
 * 已经发生的回答就是当时的样子，抹掉它等于篡改历史（后端也是按这个语义设计的：
 * `UpdateConversationRequest` 刻意不允许改 mode，正是为了保持同一段历史的可比性）。
 */
export function KbBindingBar({
  conversation,
  onChanged
}: {
  conversation: Conversation | null
  onChanged: () => void
}): ReactElement | null {
  const [kbs, setKbs] = useState<KnowledgeBase[]>([])
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState<string[]>([])
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const isRag = conversation?.mode === 'RAG'

  useEffect(() => {
    if (!isRag) return
    let alive = true
    void listKnowledgeBases({ pageSize: 100 })
      .then((res) => {
        if (alive) setKbs(res.list)
      })
      .catch(() => {
        // 库列表拉不到不该让整页报错：绑定条只是一条辅助信息
        if (alive) setKbs([])
      })
    return () => {
      alive = false
    }
  }, [isRag])

  const startEdit = useCallback(() => {
    setDraft(conversation?.kbIds ?? [])
    setError(null)
    setEditing(true)
  }, [conversation])

  const toggle = (id: string) => {
    setDraft((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]))
  }

  const save = async () => {
    if (!conversation) return
    setSaving(true)
    setError(null)
    try {
      await updateConversationKbs(conversation.id, draft)
      setEditing(false)
      onChanged()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setSaving(false)
    }
  }

  if (!isRag || !conversation) return null

  const bound = conversation.kbIds ?? []
  const nameOf = (id: string) => kbs.find((k) => k.id === id)?.name ?? id

  return (
    <div
      className="flex flex-wrap items-center gap-2 border-b border-[var(--nx-color-border)] px-4 py-2"
      data-testid="chat-kb-bind-bar"
    >
      <span className="text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">检索范围</span>

      {!editing ? (
        <>
          {bound.length === 0 ? (
            <span className="text-[var(--nx-text-xs)] text-[var(--nx-color-danger)]" data-testid="chat-kb-none">
              未绑定知识库，无法提问
            </span>
          ) : (
            bound.map((id) => (
              <Badge key={id} tone="neutral" data-testid="chat-kb-bound">
                {nameOf(id)}
              </Badge>
            ))
          )}
          <Button size="sm" variant="ghost" onClick={startEdit} data-testid="chat-kb-edit">
            {bound.length === 0 ? '绑定知识库' : '修改'}
          </Button>
        </>
      ) : (
        <>
          <div className="flex flex-wrap items-center gap-1.5">
            {kbs.length === 0 ? (
              <span className="text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">
                还没有知识库
              </span>
            ) : null}
            {kbs.map((k) => {
              const on = draft.includes(k.id)
              return (
                <button
                  key={k.id}
                  type="button"
                  onClick={() => toggle(k.id)}
                  aria-pressed={on}
                  data-testid="chat-kb-option"
                  data-selected={on}
                  className={
                    'rounded-[var(--nx-radius-md)] border px-2 py-0.5 text-[var(--nx-text-xs)] transition-colors ' +
                    (on
                      ? 'border-[var(--nx-color-primary)] bg-[var(--nx-color-surface-muted)]'
                      : 'border-[var(--nx-color-border)]')
                  }
                >
                  {k.name}
                </button>
              )
            })}
          </div>
          <span className="text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">
            已选 {draft.length}（最多 10）
          </span>
          <Button
            size="sm"
            variant="primary"
            loading={saving}
            onClick={() => void save()}
            data-testid="chat-kb-save"
          >
            保存
          </Button>
          <Button size="sm" variant="ghost" onClick={() => setEditing(false)} data-testid="chat-kb-cancel">
            取消
          </Button>
        </>
      )}

      {error ? (
        <span className="text-[var(--nx-text-xs)] text-[var(--nx-color-danger)]" data-testid="chat-kb-error">
          {error}
        </span>
      ) : null}
    </div>
  )
}
