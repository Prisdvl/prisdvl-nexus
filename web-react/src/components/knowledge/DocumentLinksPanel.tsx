import { useCallback, useEffect, useState, type ReactElement } from 'react'
import { StateError } from '@/components/common/StateError'
import { getDocumentLinks, getRelatedDocuments } from '@/api/links'
import type { DocumentLinks, DocumentLinkView, RelatedDocument } from '@/api/links'
import type { DocumentSummary } from '@/api/types/knowledge'

/**
 * 文档关联与相关推荐（R11 补齐 —— 后端早已提供，前端此前未接）。
 *
 * ## 这一屏回答的问题
 *
 * 「这篇笔记和别的笔记是什么关系？」——知识库的价值一半在单篇内容，
 * 另一半在**笔记之间的连接**。没有这一屏，用户只能靠搜索一条条找。
 *
 * ## 三个刻意选择
 *
 * 1. **待创建的引用要显出来**（`dangling`）：指向尚不存在笔记的链接
 *    是知识库最有用的待办清单——"我提过这个概念但还没写"。所以它们**不隐藏**，
 *    而是弱化显示并标注「待创建」。
 * 2. **相关推荐要给出理由**：只说"相关"没有说服力，用户在意的是一句
 *    「互相链接 · 共同标签: java」——他能据此判断推荐是否合理。
 * 3. **不显示分数**：排序分（score）是后端实现细节，属于 L4 技术信息；
 *    界面上露出来只会让人问"40 分算高吗"。理由比分数有用。
 */
export function DocumentLinksPanel({
  kbId,
  documents
}: {
  kbId: string
  documents: DocumentSummary[]
}): ReactElement {
  const [docId, setDocId] = useState('')
  const [links, setLinks] = useState<DocumentLinks | null>(null)
  const [related, setRelated] = useState<RelatedDocument[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const effectiveDocId = docId || documents[0]?.id || ''

  const load = useCallback(
    async (signal?: AbortSignal) => {
      if (!effectiveDocId) {
        setLinks(null)
        setRelated([])
        return
      }
      setLoading(true)
      setError(null)
      try {
        // 两个请求并行：它们互不依赖，串行只会让面板慢一倍
        const [l, r] = await Promise.all([
          getDocumentLinks(kbId, effectiveDocId, signal),
          getRelatedDocuments(kbId, effectiveDocId, 5, signal)
        ])
        if (signal?.aborted) return
        setLinks(l)
        setRelated(r)
      } catch (e) {
        if (signal?.aborted) return
        setError(e instanceof Error ? e.message : String(e))
      } finally {
        if (!signal?.aborted) setLoading(false)
      }
    },
    [kbId, effectiveDocId]
  )

  useEffect(() => {
    const ctrl = new AbortController()
    void load(ctrl.signal)
    return () => ctrl.abort()
  }, [load])

  if (documents.length === 0) {
    return (
      <p className="m-0 text-[var(--nx-text-sm)] text-[var(--nx-color-text-muted)]">
        还没有文档，无法分析关联。
      </p>
    )
  }

  return (
    <div className="flex flex-col gap-3" data-testid="kb-links-panel">
      <div className="flex items-center gap-2">
        <span className="text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">查看文档</span>
        <select
          value={effectiveDocId}
          onChange={(e) => setDocId(e.target.value)}
          data-testid="kb-links-doc-select"
          className="rounded-[var(--nx-radius-md)] border border-[var(--nx-color-border)] px-2 py-1 text-[var(--nx-text-xs)]"
        >
          {documents.map((d) => (
            <option key={d.id} value={d.id}>
              {d.sourcePath || d.fileName}
            </option>
          ))}
        </select>
      </div>

      {loading ? (
        <p className="m-0 text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">加载中…</p>
      ) : null}

      {error ? (
        <StateError title="关联加载失败" description={error} onRetry={() => void load()} />
      ) : null}

      {links ? (
        <>
          <section className="flex flex-col gap-1.5">
            <span className="text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">
              出链（本文档指向）· {links.outlinks.length}
              {links.dangling > 0 ? ` · 其中 ${links.dangling} 条待创建` : ''}
            </span>
            {links.outlinks.length === 0 ? (
              <span className="text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">
                没有指向别处的链接。
              </span>
            ) : null}
            {links.outlinks.map((l) => (
              <LinkRow key={l.linkId} link={l} testId="kb-link-out" />
            ))}
          </section>

          <section className="flex flex-col gap-1.5">
            <span className="text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">
              反向链接（谁引用了本文档）· {links.backlinks.length}
            </span>
            {links.backlinks.length === 0 ? (
              <span className="text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">
                还没有别的文档引用它。
              </span>
            ) : null}
            {links.backlinks.map((l) => (
              <LinkRow key={l.linkId} link={l} testId="kb-link-back" />
            ))}
          </section>
        </>
      ) : null}

      {related.length > 0 ? (
        <section className="flex flex-col gap-1.5">
          <span className="text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">
            相关推荐 · {related.length}
          </span>
          {related.map((r) => (
            <div
              key={r.documentId}
              data-testid="kb-related-item"
              data-linked={r.linked}
              className="flex flex-wrap items-center gap-2 rounded-[var(--nx-radius-md)] border border-[var(--nx-color-border)] px-3 py-2 text-[var(--nx-text-xs)]"
            >
              <span className="font-[var(--nx-weight-medium)]">{r.title ?? r.documentId}</span>
              {/* 理由比分数有用：用户据此判断推荐是否合理 */}
              <span className="text-[var(--nx-color-text-muted)]">{r.reasons.join(' · ')}</span>
            </div>
          ))}
        </section>
      ) : null}
    </div>
  )
}

/**
 * 一条关联边。
 *
 * 三种状态各有不同呈现，**不能混为一谈**：
 * ```text
 * 已解析（内部）  → 正常显示目标标题
 * 未解析          → 显示原始 target + "待创建"（这是待办，不是错误）
 * 外链            → 显示为外链，并说明不参与内部关联
 * ```
 */
function LinkRow({ link, testId }: { link: DocumentLinkView; testId: string }): ReactElement {
  const unresolved = !link.external && !link.resolvedDocumentId
  return (
    <div
      data-testid={testId}
      data-unresolved={unresolved}
      data-external={link.external}
      className={
        'flex flex-wrap items-center gap-2 rounded-[var(--nx-radius-md)] border px-3 py-2 text-[var(--nx-text-xs)] ' +
        (unresolved ? 'border-dashed border-[var(--nx-color-border)] opacity-75' : 'border-[var(--nx-color-border)]')
      }
    >
      <span className="font-[var(--nx-weight-medium)]">
        {link.resolvedTitle ?? link.display ?? link.target}
      </span>
      {unresolved ? (
        <span className="text-[var(--nx-color-text-muted)]" data-testid="kb-link-dangling">
          待创建（目标笔记还不存在）
        </span>
      ) : null}
      {link.external ? (
        <span className="text-[var(--nx-color-text-muted)]" data-testid="kb-link-external">
          外链（不参与内部关联）
        </span>
      ) : null}
    </div>
  )
}
