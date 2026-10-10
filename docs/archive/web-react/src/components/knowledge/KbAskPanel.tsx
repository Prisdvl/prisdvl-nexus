import { useCallback, useEffect, useState, type ReactElement } from 'react'
import { Button } from '@/components/ui/button'
import { Spinner } from '@/components/ui/Spinner'
import { StateError } from '@/components/common/StateError'
import { useKbAsk, type QaTurn } from '@/hooks/useKbAsk'
import { listQaHistory, type QaRecord } from '@/api/rag'
import type { RagCitation } from '@/api/types/chat'

/**
 * 知识库问答页签（R11 补齐 —— 此前是 `Placeholder text="…R8 实现"`）。
 *
 * ## 这一屏要回答的三个问题（顺序就是用户的疑问顺序）
 *
 * 1. **它答什么？** —— 只能基于这个知识库的资料；检索为空时如实说"没检索到"，不硬答。
 * 2. **依据是什么？** —— 引用列表默认只显示**回答真正引用过**的条目，
 *    未被引用的命中通过开关可查（界面上不能替模型隐瞒）。
 * 3. **它是不是在装？** —— 检索信息（命中数/来源/是否重排降级）如实展示。
 *
 * ## 为什么不用「对话」那条链路
 *
 * 问答是**无状态**的一次提问；历史（最近 3 轮）由本组件的 `turns` 回传，
 * 服务端不维护会话。这样用户"只想问一句"时不必先建会话。
 */
export function KbAskPanel({ kbId }: { kbId: string }): ReactElement {
  const ask = useKbAsk()
  const [question, setQuestion] = useState('')
  const [turns, setTurns] = useState<QaTurn[]>([])
  const [history, setHistory] = useState<QaRecord[]>([])
  const [loadingHistory, setLoadingHistory] = useState(false)

  const loadHistory = useCallback(async () => {
    setLoadingHistory(true)
    try {
      setHistory(await listQaHistory(kbId, 8))
    } catch {
      // 历史是增强项：加载失败不影响提问
      setHistory([])
    } finally {
      setLoadingHistory(false)
    }
  }, [kbId])

  useEffect(() => {
    void loadHistory()
  }, [loadHistory])

  const submit = async () => {
    const q = question.trim()
    if (!q || ask.streaming) return
    // 带上最近 2 轮：让"那它有什么缺点？"这类省略主语的追问能被理解
    const hist = turns.slice(-2).map((t) => ({ question: t.question, answer: t.answer }))
    await ask.ask(kbId, q, null, hist)
    setTurns((prev) => [...prev, { question: q, answer: ask.streamingText }])
    setQuestion('')
    void loadHistory()
  }

  const hasAnswer = ask.streamingText.length > 0

  return (
    <section className="flex flex-col gap-3" data-testid="kb-ask">
      <div className="flex gap-2">
        <input
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault()
              void submit()
            }
          }}
          placeholder="就这个知识库提问，例如：缓存击穿怎么解决？"
          data-testid="kb-ask-input"
          className="flex-1 rounded-[var(--nx-radius-md)] border border-[var(--nx-color-border)] px-3 py-2 text-[var(--nx-text-sm)]"
        />
        {ask.streaming ? (
          <Button variant="ghost" onClick={ask.stop} data-testid="kb-ask-stop">
            停止
          </Button>
        ) : (
          <Button
            variant="primary"
            onClick={() => void submit()}
            disabled={!question.trim()}
            data-testid="kb-ask-submit"
          >
            提问
          </Button>
        )}
      </div>

      {/* 检索事实：命中数 / 是否重排降级 / 上下文预算 —— 让"它有没有装"可核对 */}
      {ask.retrieval ? (
        <p className="m-0 flex flex-wrap items-center gap-x-3 text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">
          <span data-testid="kb-ask-retrieval">
            检索到 {ask.retrieval.count} 个片段
          </span>
          <span>上下文 {ask.contextTokens} token</span>
          <span data-testid="kb-ask-reranked">
            {ask.reranked ? '已重排' : '未重排（保持召回顺序）'}
          </span>
          {ask.retrieval.failedKnowledgeBases.length > 0 ? (
            <span data-testid="kb-ask-failed-kbs">
              {ask.retrieval.failedKnowledgeBases.length} 个知识库检索失败
            </span>
          ) : null}
        </p>
      ) : null}

      {ask.error ? (
        <StateError title="回答失败" description={ask.error} onRetry={() => void submit()} retryLabel="重试" />
      ) : null}

      {ask.streaming && !hasAnswer ? (
        <p className="m-0 flex items-center gap-2 text-[var(--nx-text-sm)] text-[var(--nx-color-text-muted)]">
          <Spinner size={14} />
          正在检索资料…
        </p>
      ) : null}

      {hasAnswer || ask.streaming ? (
        <div className="flex flex-col gap-3" data-testid="kb-ask-answer-wrap">
          <article
            data-testid="kb-ask-answer"
            className="whitespace-pre-wrap rounded-[var(--nx-radius-md)] border border-[var(--nx-color-border)] bg-[var(--nx-color-surface-muted)] p-3 text-[var(--nx-text-sm)] leading-relaxed"
          >
            {ask.streamingText}
            {ask.streaming ? <span className="nx-caret" aria-hidden="true" /> : null}
          </article>

          {ask.citations.length > 0 ? (
            <div data-testid="kb-ask-citations" className="flex flex-col gap-2">
              <span className="text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">
                参考资料
              </span>
              {ask.citations.map((c) => (
                <CitationRow key={`${c.index}-${c.chunkId}`} citation={c} />
              ))}
            </div>
          ) : null}
        </div>
      ) : (
        <p className="m-0 text-[var(--nx-text-sm)] text-[var(--nx-color-text-muted)]">
          问一个与这些资料相关的问题。回答只依据本知识库的内容，检索不到时会如实说明。
        </p>
      )}

      {/* 问答留档：回看"当时依据了哪些资料" */}
      <div className="flex flex-col gap-2 border-t border-[var(--nx-color-border)] pt-3">
        <span className="text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">
          问答历史{loadingHistory ? '（加载中）' : history.length ? `（${history.length} 条）` : ''}
        </span>
        {history.length === 0 && !loadingHistory ? (
          <span className="text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">
            还没有留档记录。
          </span>
        ) : null}
        {history.map((h, i) => (
          <details
            key={h.recordId ?? i}
            data-testid="kb-ask-history-item"
            className="rounded-[var(--nx-radius-md)] border border-[var(--nx-color-border)] px-3 py-2"
          >
            <summary className="cursor-pointer text-[var(--nx-text-sm)]">
              {h.answer.slice(0, 48)}
              {h.answer.length > 48 ? '…' : ''}
            </summary>
            <p className="m-0 mt-2 whitespace-pre-wrap text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">
              {h.answer}
            </p>
            <p className="m-0 mt-1 text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">
              {h.citations.length} 条引用 · {h.contextTokens} token · {h.costMillis}ms
              {h.reranked ? ' · 已重排' : ' · 未重排'}
            </p>
          </details>
        ))}
      </div>
    </section>
  )
}

/**
 * 单条引用。
 *
 * 未引用的条目**也显示**（弱化处理）——这是"可核对"的另一半：
 * 只显示被引用的，用户就无从判断"模型是不是漏引了明明检索到的资料"。
 */
function CitationRow({ citation }: { citation: RagCitation }): ReactElement {
  return (
    <div
      data-testid="kb-ask-citation"
      data-index={citation.index}
      data-referenced={citation.referenced}
      className={
        'flex items-baseline gap-2 rounded-[var(--nx-radius-md)] border px-3 py-2 text-[var(--nx-text-xs)] ' +
        (citation.referenced
          ? 'border-[var(--nx-color-border)]'
          : 'border-dashed border-[var(--nx-color-border)] opacity-70')
      }
    >
      <span className="font-mono text-[var(--nx-color-primary)]">[{citation.index}]</span>
      <span className="min-w-0 flex-1">
        <span className="block truncate">
          {citation.documentTitle ?? '未命名文档'}
          {citation.headingPath ? ` › ${citation.headingPath}` : ''}
        </span>
        {citation.snippet ? (
          <span className="mt-0.5 block text-[var(--nx-color-text-muted)]">
            {citation.snippet.slice(0, 80)}
          </span>
        ) : null}
      </span>
      {!citation.referenced ? <span className="shrink-0">未引用</span> : null}
    </div>
  )
}
