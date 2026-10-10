import { useCallback, useState, type ReactElement } from 'react'
import { StateEmpty } from '@/components/common/StateEmpty'
import { StateError } from '@/components/common/StateError'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/form'
import {
  chunkStateLabel,
  isChunkNotReady,
  isStateReady,
  stateTone,
  type ChunkView,
  type DocumentSummary,
  type SearchHit
} from '@/api/types/knowledge'
import { listChunks, recreateIndex, searchChunks } from '@/api/knowledge'
import { useAsyncState } from '@/hooks/useAsyncState'
import { useConfirm } from '@/hooks/useConfirm'
import { getErrorMessage } from '@/lib/errors'

/**
 * 知识库高级区（R10）。
 *
 * ## 这里解决的是"明明导入过了却搜不到"
 *
 * 导入成功只代表**文档与切片进了数据库**。要让检索命中，还差两步：
 * ```text
 * 解析 → 切片 → 向量化（写向量库）→ 建 ES 索引
 *                        ↑                ↑
 *                   vectorStatus      esStatus
 * ```
 * 任一步失败，检索就命中不了，但用户在「资料」页**看不到任何异常**——
 * 文档数、切片数都是正常的。
 *
 * 所以高级区做三件事：
 * 1. **按文档看切片**（含向量 / 索引状态）——定位是哪一步断了
 * 2. **试试能不能搜到**（可选 `vector` / `es` / `hybrid`）——判断是哪一路失灵
 * 3. **重建索引**——修不好时的兜底（危险操作，必须确认）
 *
 * ## 为什么放在「高级」里默认折叠（任务书 §12）
 *
 * 99% 的用户不需要看切片。但出问题时**必须能看到**——
 * 既不能藏起来（用户报障时无法自证），也不能默认摊开（淹没主要内容）。
 */
export interface AdvancedPanelProps {
  kbId: string
  documents: DocumentSummary[]
  /** 检索模式。`vector` 单独验证向量这一路，`es` 单独验证关键词。 */
  mode: 'hybrid' | 'vector' | 'es'
}

export function AdvancedPanel({ kbId, documents, mode }: AdvancedPanelProps): ReactElement {
  const confirm = useConfirm()
  const [docId, setDocId] = useState<string>('')
  const [q, setQ] = useState('')
  const [hits, setHits] = useState<SearchHit[] | null>(null)
  const [searching, setSearching] = useState(false)
  const [searchError, setSearchError] = useState<string | null>(null)
  const [recreating, setRecreating] = useState(false)

  // 默认选第一篇——大多数排查场景就是"随便点一篇看看"
  const effectiveDocId = docId || documents[0]?.id || ''

  const chunks = useAsyncState(
    (signal) => listChunks(kbId, effectiveDocId, signal),
    [kbId, effectiveDocId],
    { immediate: effectiveDocId !== '' }
  )

  const onSearch = useCallback(async () => {
    const query = q.trim()
    if (!query) return
    setSearching(true)
    setSearchError(null)
    setHits(null)
    try {
      setHits(await searchChunks(kbId, { q: query, topK: 10, mode }))
    } catch (e) {
      setSearchError(getErrorMessage(e))
    } finally {
      setSearching(false)
    }
  }, [q, kbId, mode])

  const onRecreate = async () => {
    const ok = await confirm({
      title: '重建索引？',
      description:
        '会把该知识库**全部文档的切片**重新写入向量库与检索索引。' +
        '耗时较长，期间检索可能返回不全。此操作不可撤销。',
      confirmText: '重建',
      danger: true
    })
    if (!ok) return
    setRecreating(true)
    try {
      await recreateIndex(kbId)
      await chunks.retry()
    } finally {
      setRecreating(false)
    }
  }

  const notReady = (chunks.data ?? []).filter(isChunkNotReady).length

  return (
    <div className="flex flex-col gap-4" data-testid="kb-advanced">
      {/* ---- 索引状态 ---- */}
      <Card padding="md" data-testid="kb-advanced-index">
        <div className="flex items-center justify-between gap-3">
          <div>
            <h3 className="m-0 text-[var(--nx-text-sm)] font-[var(--nx-weight-medium)]">索引状态</h3>
            <p className="m-0 mt-1 text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">
              {notReady > 0 ? (
                <>有 {notReady} 个切片未完成向量化或索引——这些切片搜不到。</>
              ) : (
                '向量与索引均正常。'
              )}
            </p>
          </div>
          <Button
            variant="outline"
            size="sm"
            onClick={() => void onRecreate()}
            loading={recreating}
            data-testid="kb-recreate-index"
          >
            重建索引
          </Button>
        </div>
      </Card>

      {/* ---- 检索试验 ---- */}
      <Card padding="md" data-testid="kb-advanced-search">
        <h3 className="m-0 text-[var(--nx-text-sm)] font-[var(--nx-weight-medium)]">
          检索试验
        </h3>
        <p className="m-0 mt-1 mb-2 text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">
          直接调检索接口，看这个知识库到底能不能搜到。当前模式：
          <strong data-testid="kb-search-mode">{mode}</strong>
          {mode === 'vector' ? '（只走向量，可判断是不是关键词那一路失灵）' : ''}
          {mode === 'es' ? '（只走关键词，可判断是不是向量那一路失灵）' : ''}
        </p>
        <div className="flex gap-2">
          <Input
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="输入一个词试试"
            data-testid="kb-search-input"
          />
          <Button
            onClick={() => void onSearch()}
            loading={searching}
            disabled={!q.trim()}
            data-testid="kb-search-run"
          >
            搜索
          </Button>
        </div>

        {searchError ? (
          <p role="alert" className="mt-2 mb-0 text-[var(--nx-text-sm)] text-[var(--nx-color-danger)]">
            {searchError}
          </p>
        ) : null}

        {hits ? (
          hits.length === 0 ? (
            <p className="mt-3 mb-0 text-[var(--nx-text-sm)] text-[var(--nx-color-text-secondary)]" data-testid="kb-search-empty">
              没搜到任何内容。{notReady > 0 ? '先修好上面的索引状态再试。' : '资料里可能真的没有这个词。'}
            </p>
          ) : (
            <ul className="mt-3 mb-0 flex list-none flex-col gap-2 p-0" data-testid="kb-search-hits">
              {hits.map((h) => (
                <li key={h.chunkId} className="border-t border-[var(--nx-color-border)] pt-2" data-testid="kb-search-hit">
                  <div className="mb-1 flex items-center gap-1.5">
                    <span className="min-w-0 flex-1 truncate text-[var(--nx-text-xs)] font-[var(--nx-weight-medium)]">
                      {h.documentTitle ?? '未命名文档'}
                    </span>
                    {/* source / matchedBy 是排查的关键：命中来自哪一路 */}
                    <Badge tone="info" data-testid="kb-hit-source">
                      {h.source}
                    </Badge>
                    {/* 相似度属 L4，这里只显示"命中/未命中相关"—— */}
                    {/* 高级区已经是"给排查的人看"，所以保留分数 */}
                    <span className="text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">
                      {h.score.toFixed(3)}
                    </span>
                  </div>
                  <p className="m-0 line-clamp-3 text-[var(--nx-text-xs)] leading-[1.6] text-[var(--nx-color-text-secondary)]">
                    {h.content}
                  </p>
                </li>
              ))}
            </ul>
          )
        ) : null}
      </Card>

      {/* ---- 切片 ---- */}
      <Card padding="md" data-testid="kb-advanced-chunks">
        <div className="mb-2 flex items-center justify-between gap-2">
          <h3 className="m-0 text-[var(--nx-text-sm)] font-[var(--nx-weight-medium)]">切片</h3>
          {documents.length > 1 ? (
            <select
              value={effectiveDocId}
              onChange={(e) => setDocId(e.target.value)}
              className="nx-input max-w-56"
              data-testid="kb-chunk-doc-select"
            >
              {documents.map((d) => (
                <option key={d.id} value={d.id}>
                  {d.fileName}
                </option>
              ))}
            </select>
          ) : null}
        </div>

        {documents.length === 0 ? (
          <StateEmpty title="还没有文档" description="先在「资料」页导入内容。" />
        ) : null}

        {chunks.state === 'loading' ? <p className="m-0 text-[var(--nx-text-sm)]">加载中…</p> : null}

        {chunks.state === 'error' ? (
          <StateError
            title="切片加载失败"
            {...(chunks.error ? { description: chunks.error } : {})}
            onRetry={() => void chunks.retry()}
          />
        ) : null}

        {chunks.state === 'success' && (chunks.data ?? []).length === 0 ? (
          <p className="m-0 text-[var(--nx-text-sm)] text-[var(--nx-color-text-secondary)]">
            这篇文档还没有切片。
          </p>
        ) : null}

        <ul className="m-0 flex list-none flex-col gap-2 p-0" data-testid="kb-chunk-list">
          {(chunks.data ?? []).map((c: ChunkView) => (
            <li
              key={c.id}
              className="rounded-[var(--nx-radius-md)] border border-[var(--nx-color-border)] p-2.5"
              data-testid="kb-chunk"
            >
              <div className="mb-1 flex items-center gap-1.5">
                <span className="text-[var(--nx-text-xs)] font-[var(--nx-weight-medium)]">
                  #{c.chunkIndex ?? '?'}
                </span>
                {c.chunkType ? (
                  <Badge tone="neutral">{c.chunkType}</Badge>
                ) : null}
                {c.tokenCount !== null ? (
                  <span className="text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">
                    {c.tokenCount} token
                  </span>
                ) : null}
                {/*
                  状态徽标：**只在"未就绪"或"已就绪但值得说"时显示**。
                  已就绪（DONE/SUCCESS）不显示徽标——满屏"向量 正常 索引 正常"
                  是噪音，而异常才是要看的东西。
                */}
                {!isStateReady(c.vectorStatus) ? (
                  <Badge tone={stateTone(c.vectorStatus)} data-testid="kb-chunk-vector-status">
                    向量 {chunkStateLabel(c.vectorStatus)}
                  </Badge>
                ) : null}
                {!isStateReady(c.esStatus) ? (
                  <Badge tone={stateTone(c.esStatus)} data-testid="kb-chunk-es-status">
                    索引 {chunkStateLabel(c.esStatus)}
                  </Badge>
                ) : null}
              </div>
              {c.headingPath ? (
                <p className="m-0 mb-1 text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">
                  {c.headingPath}
                </p>
              ) : null}
              <p className="m-0 line-clamp-3 text-[var(--nx-text-xs)] leading-[1.6] text-[var(--nx-color-text-secondary)]">
                {c.content}
              </p>
            </li>
          ))}
        </ul>
      </Card>
    </div>
  )
}
