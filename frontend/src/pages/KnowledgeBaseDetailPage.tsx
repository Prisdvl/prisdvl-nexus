import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useLocation } from 'react-router'
import { ArrowLeft, FileSpreadsheet, FileText, RefreshCw, Search, Trash2, Upload } from 'lucide-react'
import ThoughtLine from '@/bits/ThoughtLine/ThoughtLine'
import JellyRadio from '@/bits/JellyRadio/JellyRadio'
import RubberSegment from '@/bits/RubberSegment/RubberSegment'
import StatusMark from '@/bits/StatusMark/StatusMark'
import Modal from '@/components/Modal'
import { StateEmpty } from '@/components/Skeleton'
import DocPreview, { mockDocContent } from '@/components/DocPreview'
import Markdown from '@/components/Markdown'
import { toast } from '@/lib/toast'
import { download } from '@/lib/download'
import { DOCS, QA_HISTORY, type DocItem } from '@/data'
import { api, USE_MOCK, errMessage, type ChunkView, type DocumentLinks, type KnowledgeBase, type RelatedDoc, type SearchHit } from '@/lib/api'
import { fetchDocs } from '@/lib/backendData'
import type { ThemeMode } from '@/lib/types'

type Tab = 'docs' | 'qa' | 'adv' | 'links'
const TABS = [
  { label: '文档', href: '#docs' },
  { label: '问答', href: '#qa' },
  { label: '高级检索', href: '#adv' },
  { label: '关联', href: '#links' },
]

const CHUNKS = [
  { id: 1, head: 'JVM 内存模型', text: '程序计数器、虚拟机栈、本地方法栈、堆、方法区；栈管运行、堆管存储……', tags: ['JVM', '内存'] },
  { id: 2, head: 'B+ 树索引结构', text: '非叶子节点只存键，叶子节点串成链表，配合页大小减少磁盘 IO……', tags: ['MySQL', '索引'] },
  { id: 3, head: 'synchronized 原理', text: '对象头 Mark Word、锁升级（偏向→轻量级→重量级）与 Monitor……', tags: ['并发'] },
]

const RELATED = [
  { id: 1, title: 'MySQL 调优清单.MD', why: '与「B+ 树索引」强相关（0.93）', status: 'resolved' },
  { id: 2, title: 'Redis 缓存与 MySQL 一致性', why: '外部链接，待关联', status: 'external' },
  { id: 3, title: 'JVM 参数速查表.MD', why: '候选关联，待确认', status: 'pending' },
  { id: 4, title: '分布式锁实现对比', why: '外部链接，待关联', status: 'external' },
]

interface Props {
  kbId: string
  theme: ThemeMode
  onBack: () => void
  /** 由引用跳转带入：需要高亮定位的文档名 */
  highlightDoc?: string | null
  /** 点引用后（带 ?doc=）重新定位到该文档 */
  onOpenDoc?: (docName: string) => void
  /** 高亮结束后清掉 URL 上的 ?doc=（避免地址栏留痕、刷新又亮） */
  onClearHighlight?: () => void
}

export default function KnowledgeBaseDetailPage({ kbId, theme, onBack, highlightDoc = null, onOpenDoc, onClearHighlight }: Props) {
  /* 高亮是"一次性"的：进来脉冲一圈、2.5s 后自动取消（此前会一直亮） */
  const [hlActive, setHlActive] = useState(false)
  /*
   * location.key 每次导航都会变（即使跳到完全相同的 URL）。
   * 只用 highlightDoc 的变化做依据的话，"再次点同一个引用"不会重新高亮——
   * 因为 props 值没变、effect 不重跑（实测：第二次点引用毫无反应）。
   */
  const { key: navKey } = useLocation()
  const [tab, setTab] = useState<Tab>('docs')
  const [importOpen, setImportOpen] = useState(false)
  const [docs, setDocs] = useState<DocItem[]>(USE_MOCK ? DOCS : [])
  const [cites, setCites] = useState<{ documentName?: string; score?: number }[]>([])
  const [docsLoading, setDocsLoading] = useState(!USE_MOCK)
  const [docsError, setDocsError] = useState('')
  /* 文档列表本地过滤（只过滤已加载的真实文档，不做服务端全文检索——那是「高级检索」tab 的事） */
  const [docFilter, setDocFilter] = useState('')
  /** 本页本次访问提过的问题（真实记录；后端暂无历史接口，不展示虚构的历史条目） */
  const [asked, setAsked] = useState<string[]>(USE_MOCK ? QA_HISTORY.map((h) => h.q) : [])
  const [picked, setPicked] = useState<File | null>(null)
  const [searchQ, setSearchQ] = useState('')
  const [hits, setHits] = useState<SearchHit[]>([])
  const [searching, setSearching] = useState(false)
  const [chunks, setChunks] = useState<ChunkView[]>([])
  const [kbInfo, setKbInfo] = useState<KnowledgeBase | null>(null)
  const [linkData, setLinkData] = useState<DocumentLinks | null>(null)
  const [related, setRelated] = useState<RelatedDoc[]>([])
  const [indexing, setIndexing] = useState(false)

  const MODE_MAP: Record<string, string> = { '向量检索': 'vector', 'BM25': 'bm25', '混合检索': 'hybrid' }

  const uploadDocument = async () => {
    if (uploading) return
    if (!picked) { setUploadError('请选择一个文件。'); return }
    if (USE_MOCK) {
      setUploadError('离线测试模式不会上传或保存文件。请连接真实后端后重试。')
      return
    }
    setUploadError('')
    setUploading(true)
    try {
      const task = await api.documents.upload(kbId, picked)
      setImportOpen(false)
      setPicked(null)
      toast.task('导入任务已提交', `任务 ${task.id} 已进入解析队列`)
      setDocs(await fetchDocs(kbId))
    } catch (error) {
      setUploadError(errMessage(error))
    } finally {
      setUploading(false)
    }
  }

  /* 高级检索：直接打检索接口（不调用模型），用于验证"到底能不能搜到" */
  const runSearch = async () => {
    const q = searchQ.trim()
    if (!q || searching) return
    setSearching(true)
    try {
      setHits(await api.knowledge.search(kbId, q, MODE_MAP[usage.mode] ?? 'hybrid', usage.topK))
    } catch (e) {
      toast.error('检索失败', errMessage(e))
    } finally {
      setSearching(false)
    }
  }

  /* 切片索引状态：解释"为什么检索是空的"（PENDING = 还没建索引） */
  const loadFirstDocChunks = async () => {
    if (USE_MOCK) return
    try {
      const first = docs[0]
      if (!first) { setChunks([]); return }
      setChunks(await api.documents.chunks(kbId, first.id))
    } catch (e) {
      toast.error('切片加载失败', errMessage(e))
    }
  }

  const runIndex = async (rebuild: boolean) => {
    if (USE_MOCK) { toast.info('mock 模式不建索引'); return }
    setIndexing(true)
    try {
      if (rebuild) await api.knowledge.recreateIndex(kbId)
      else await api.knowledge.index(kbId)
      toast.success(rebuild ? '索引已重建' : '索引任务已提交')
      await loadFirstDocChunks()
    } catch (e) {
      toast.error('建索引失败', errMessage(e))
    } finally {
      setIndexing(false)
    }
  }
  const [uploading, setUploading] = useState(false)
  const [uploadError, setUploadError] = useState('')

  /* 按 kbId 拉知识库自身的名称/描述（此前页头是写死的演示名） */
  useEffect(() => {
    if (USE_MOCK) return
    let alive = true
    api.knowledge.get(kbId).then((k) => { if (alive) setKbInfo(k) }).catch(() => {})
    return () => { alive = false }
  }, [kbId])

  /* 进入「关联」tab 时按首篇文档拉出链/反链/相关推荐（后端按文档维度提供） */
  useEffect(() => {
    if (USE_MOCK || tab !== 'links' || !docs.length) return
    let alive = true
    const docId = docs[0].id
    Promise.all([api.documents.links(kbId, docId), api.documents.related(kbId, docId)])
      .then(([lk, rl]) => { if (alive) { setLinkData(lk); setRelated(rl) } })
      .catch(() => { /* 关联信息缺失不阻塞页面 */ })
    return () => { alive = false }
  }, [tab, docs])
  /* 进入「高级检索」tab 时加载切片索引状态 */
  useEffect(() => { if (tab === 'adv') void loadFirstDocChunks() }, [tab, docs])

  /* 真实数据：进入详情页按 kbId 拉文档列表（mock 模式继续用种子数据） */
  const loadDocs = (): void => {
    if (USE_MOCK) return
    let alive = true
    setDocsLoading(true)
    setDocsError('')
    fetchDocs(kbId)
      .then((rows) => { if (alive) setDocs(rows) })
      .catch((e) => { if (alive) setDocsError(errMessage(e)) })
      .finally(() => { if (alive) setDocsLoading(false) })
    docsLoadCancel.current = () => { alive = false }
  }
  const docsLoadCancel = useRef<(() => void) | null>(null)
  useEffect(() => {
    loadDocs()
    return () => docsLoadCancel.current?.()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [kbId])
  const [thinking, setThinking] = useState(false)
  const [qa, setQa] = useState('')
  const [answer, setAnswer] = useState<string | null>(null)
  const [usage, setUsage] = useState({ mode: '混合检索', topK: 6 })
  /* 预览抽屉：点文档行打开（.md 渲染正文，PDF/DOCX 给占位 + 下载） */
  const [preview, setPreview] = useState<DocItem | null>(null)
  const [previewContent, setPreviewContent] = useState<string | null>(null)
  const [previewChunks, setPreviewChunks] = useState<ChunkView[]>([])
  const [previewLoading, setPreviewLoading] = useState(false)
  const [previewError, setPreviewError] = useState<string | null>(null)
  const previewSeq = useRef(0)

  const openPreview = async (doc: DocItem) => {
    setPreview(doc)
    setPreviewContent(null); setPreviewChunks([]); setPreviewError(null)
    if (USE_MOCK) {
      // Fixtures are available only in the explicit offline test build.
      setPreviewContent(mockDocContent(doc))
      setPreviewChunks(CHUNKS.map((chunk, index) => ({
        id: `mock-${doc.id}-${index}`,
        chunkIndex: index,
        chunkType: 'TEXT',
        headingPath: chunk.head,
        content: chunk.text,
        tokenCount: 0,
        charCount: chunk.text.length,
        vectorStatus: 'READY',
        esStatus: 'READY',
      })))
      return
    }
    const seq = ++previewSeq.current
    setPreviewLoading(true)
    try {
      const [detail, chunkRows] = await Promise.all([
        api.documents.get(kbId, doc.id),
        api.documents.chunks(kbId, doc.id),
      ])
      if (seq !== previewSeq.current) return
      setPreviewContent(detail.content)
      setPreviewChunks(chunkRows)
    } catch (e) {
      if (seq === previewSeq.current) setPreviewError(errMessage(e))
    } finally {
      if (seq === previewSeq.current) setPreviewLoading(false)
    }
  }
  const C = theme === 'dark'
    ? { chip: '#26262b', active: '#f2f2f5', text: '#a3a3ad', onActive: '#141418' }
    : { chip: '#eef0f3', active: '#ffffff', text: '#6b7280', onActive: '#111111' }

  /* 文档列表仅做本地名称过滤（真实数据；搜索框不冒充全文检索） */
  const visibleDocs = (() => {
    const kw = docFilter.trim().toLowerCase()
    if (!kw) return docs
    return docs.filter((d) => d.name.toLowerCase().includes(kw))
  })()

  /* 引用跳转落点：切到文档 tab → 滚动定位 → 2.5s 后取消高亮并清掉 URL 参数 */
  useEffect(() => {
    if (!highlightDoc) { setHlActive(false); return }
    setTab('docs')
    setHlActive(true)
    const scroll = setTimeout(() => {
      document.querySelector('.doc-row[data-hl]')?.scrollIntoView({ block: 'center', behavior: 'smooth' })
    }, 220)
    const clear = setTimeout(() => {
      setHlActive(false)          // 先让淡出过渡跑起来
      onClearHighlight?.()        // 再清 URL（此时刷新也不会再高亮）
    }, 2500)
    return () => { clearTimeout(scroll); clearTimeout(clear) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [highlightDoc, navKey])

  const ask = async (e: FormEvent) => {
    e.preventDefault()
    const question = qa.trim()
    if (!question || thinking) return
    setThinking(true)
    setAnswer(null)
    setCites([])
    setAsked((list) => [question, ...list.filter((q) => q !== question)].slice(0, 20))
    if (!USE_MOCK) {
      try {
        const res = await api.rag.ask(kbId, qa.trim(), usage.topK)
        setAnswer(res.answer)
        setCites(res.citations ?? [])
      } catch (err) {
        setAnswer(`> ⚠️ 检索/生成失败：${errMessage(err)}`)
      } finally {
        setThinking(false)
      }
      return
    }
    setTimeout(() => {
      setThinking(false)
      setAnswer(`## 为什么 B+ 树更适合做索引

**核心原因**：把数据集中在叶子节点，内部节点只存键。

| 对比项 | B 树 | B+ 树 |
| --- | --- | --- |
| 内部节点存数据 | 是 | 否 |
| 单页可容纳键数 | 少 | 多（树更矮） |
| 范围查询 | 需中序回溯 | 叶子链表顺序扫描 |

### 检索路径

\`\`\`mermaid
flowchart TD
  R[根节点] --> A[内部节点: 只存键]
  A --> L1[叶子: 数据 + 互链]
  A --> L2[叶子: 数据 + 互链]
  L1 -.链表.-> L2
\`\`\`

### 与之匹配的页大小

\`\`\`text
一次磁盘 IO = 一页（16KB），局部性因此更好
\`\`\``)
    }, 2600)
  }

  return (
    <div className="page">
      <button
        type="button"
        /* 原来用 .list-row（列表行，带常驻灰底）→ 看起来像"永远被选中/高亮"；
           btn-ghost 只在悬停时才有底色，符合"鼠标在其上才高亮" */
        className="btn btn-ghost btn-sm"
        style={{ margin: '-8px 0 6px -6px' }}
        onClick={onBack}
      >
        <ArrowLeft size={15} /> <span style={{ fontSize: 13 }}>返回知识库</span>
      </button>

      <div className="page-head">
        <div>
          <p className="eyebrow" style={{ margin: 0 }}>知识库详情</p>
          <h1 className="page-title">{kbInfo?.name ?? (USE_MOCK ? '我的 Java 笔记' : '知识库')}</h1>
          <p className="page-sub">{(kbInfo?.description || (USE_MOCK ? '集合框架 / JVM / 并发 / 调优，校招复习主线' : '（无描述）')) + ' · ' + docs.length + ' 篇文档'}</p>
        </div>
        <div style={{ display: 'flex', gap: 10 }}>
          <button className="btn btn-ghost" type="button" disabled={indexing} onClick={() => void runIndex(true)}><RefreshCw size={15} /> 重建索引</button>
          <button className="btn btn-primary" type="button" onClick={() => setImportOpen(true)}>
            <Upload size={16} /> 导入文档
          </button>
        </div>
      </div>

      <div style={{ display: 'flex', justifyContent: 'flex-start', marginBottom: 22 }}>
        <RubberSegment
          items={TABS.map((t) => t.label)}
          value={{ docs: '文档', qa: '问答', adv: '高级检索', links: '关联' }[tab]}
          onChange={(v) => setTab(({ 文档: 'docs', 问答: 'qa', 高级检索: 'adv', 关联: 'links' } as Record<string, Tab>)[v] ?? 'docs')}
          size="md"
          inset={3}
          radius={12}
          trackColor="var(--surface-muted)"
          thumbColor="var(--primary-container)"
          textColor="var(--text-2)"
          activeTextColor="var(--primary-text)"
          aria-label="知识库分区"
        />
      </div>

      {tab === 'docs' && (
        <>
          <div style={{ display: 'flex', gap: 10, marginBottom: 14 }}>
            <div style={{ position: 'relative', flex: 1 }}>
              <Search size={15} style={{ position: 'absolute', left: 12, top: '50%', transform: 'translateY(-50%)', color: 'var(--text-3)' }} />
              <input
                className="input"
                placeholder="按名称筛选当前知识库的文档"
                style={{ paddingLeft: 36 }}
                aria-label="按名称筛选文档"
                value={docFilter}
                onChange={(e) => setDocFilter(e.target.value)}
              />
            </div>
            <span className="badge">{visibleDocs.length} / {docs.length} 篇</span>
          </div>

          <div className="panel" style={{ padding: 6 }}>
            {docsLoading && (
              <p className="hint" style={{ padding: 18 }} role="status">正在加载文档…</p>
            )}
            {!docsLoading && docsError && (
              <div style={{ padding: 18 }}>
                <p className="form-error active" role="alert" style={{ marginTop: 0 }}>文档列表加载失败：{docsError}</p>
                <button className="btn btn-ghost btn-sm" type="button" onClick={loadDocs}><RefreshCw size={13} /> 重试</button>
              </div>
            )}
            {!docsLoading && !docsError && docs.length === 0 && (
              <div style={{ padding: 12 }}>
                <StateEmpty
                  icon={<FileText size={20} />}
                  title="这个知识库还没有文档"
                  desc="导入 Markdown、TXT、PDF 或 DOCX，解析切片并建立索引后才能被检索与引用。"
                  action={<button className="btn btn-primary btn-sm" type="button" onClick={() => setImportOpen(true)}><Upload size={14} /> 导入文档</button>}
                />
              </div>
            )}
            {!docsLoading && !docsError && docs.length > 0 && visibleDocs.length === 0 && (
              <p className="hint" style={{ padding: 18 }}>没有名称包含「{docFilter.trim()}」的文档。</p>
            )}
            {visibleDocs.map((d) => (
              <div
                className="list-row doc-row"
                key={d.id}
                role="button"
                tabIndex={0}
                aria-label={`预览 ${d.name}`}
                onClick={() => void openPreview(d)}
                onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); void openPreview(d) } }}
                data-hl={hlActive && highlightDoc && (d.name === highlightDoc || highlightDoc.includes(d.name.replace(/\.[^.]+$/, ''))) ? '' : undefined}
              >
                <span className="ic">{d.type === 'PDF' ? <FileText size={15} /> : d.type === 'DOCX' ? <FileSpreadsheet size={15} /> : <FileText size={15} />}</span>
                <div style={{ minWidth: 0 }}>
                  <div className="nm">{d.name}</div>
                  <div className="mt">{d.type} · {d.size} · {d.updated}</div>
                </div>
                <span className="doc-status" title={{ done: '已索引', indexing: '解析中', failed: '解析失败' }[d.status]}>
                  {d.status === 'done' && <StatusMark status="done" size={15} label="已索引" fontSize={12} strike={false} />}
                  {d.status === 'indexing' && (
                    <StatusMark status="running" progress={0.4} size={15} label="解析中" fontSize={12} />
                  )}
                  {d.status === 'failed' && <StatusMark status="failed" size={15} label="失败" fontSize={12} strike={false} />}
                </span>
                <div className="doc-actions">
                  <button
                    className="btn btn-ghost btn-sm"
                    type="button"
                    onClick={(e) => {
                      e.stopPropagation()   // 否则点删除会顺带打开预览
                      if (USE_MOCK) {
                        const idx = docs.findIndex((x) => x.id === d.id)
                        setDocs((list) => list.filter((x) => x.id !== d.id))
                        toast.undo('已删除文档', d.name, () => {
                          setDocs((list) => {
                            const out = [...list]
                            out.splice(Math.min(idx, out.length), 0, d)
                            return out
                          })
                          toast.success('已恢复文档', d.name)
                        })
                        return
                      }
                      /* 真实删除：后端会级联清理切片/向量/索引，不可恢复 → 二次确认 */
                      if (!window.confirm(`确定删除文档「${d.name}」吗？切片与索引会一并清除，无法恢复。`)) return
                      api.documents.remove(kbId, d.id)
                        .then(() => {
                          setDocs((list) => list.filter((x) => x.id !== d.id))
                          toast.success('文档已删除', d.name)
                        })
                        .catch((e) => toast.error('删除失败', errMessage(e)))
                    }}
                  >
                    <Trash2 size={14} /> 删除
                  </button>
                </div>
              </div>
            ))}
          </div>


        </>
      )}

      {tab === 'qa' && (
        <>
          <form onSubmit={ask} style={{ display: 'flex', gap: 10, marginBottom: 16 }}>
            <div style={{ position: 'relative', flex: 1 }}>
              <Search size={15} style={{ position: 'absolute', left: 12, top: '50%', transform: 'translateY(-50%)', color: 'var(--text-3)' }} />
              <input aria-label="向这个知识库提问" className="input" value={qa} onChange={(e) => setQa(e.target.value)} placeholder="向这个知识库提问，例如：B+ 树为什么适合做索引？" style={{ paddingLeft: 36 }} />
            </div>
            <button className="btn btn-primary" type="submit" disabled={!qa.trim() || thinking}>提问</button>
          </form>

          {thinking && (
            <div className="panel panel-pad" style={{ marginBottom: 16 }}>
              <div className="thinking-head" style={{ marginBottom: 6 }}>
                <StatusMark status="running" size={17} color="var(--primary)" />
                正在检索并生成回答
              </div>
              <ThoughtLine
                working
                label=""
                /*
                 * 真实模式：/ask 是一次性请求，没有流式进度事件，
                 * 只能展示这一条与请求状态相符的步骤，不虚构切片数。
                 * mock 模式（离线 QA 构建）才展示演示步骤。
                 */
                steps={USE_MOCK ? ['读取会话上下文', '混合检索 12 个切片', '拼接引用于回答'] : ['正在检索知识库并生成回答']}
                showTimer
                fontSize={13}
              />
            </div>
          )}

          {answer && (
            <div className="panel panel-pad" style={{ marginBottom: 16 }}>
              <Markdown text={answer} theme={theme} />
              {/* 引用来源是回答的一部分，不再套一层 .panel —— 卡中卡会把层级搞反 */}
              {cites.length > 0 && (
                <div style={{ marginTop: 20 }}>
                  <h3 className="col-title" style={{ marginTop: 0 }}>引用来源（{cites.length}）</h3>
                  {cites.map((c, i) => (
                    <div key={i} className="list-row">
                      <span className="nm" style={{ fontSize: 13 }}>{c.documentName ?? '(未知文档)'}</span>
                      {typeof c.score === 'number' && <span className="badge">{(c.score * 100).toFixed(0)}%</span>}
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}

          <h2 className="col-title"><Search size={15} style={{ color: 'var(--primary)' }} /> 本次提问</h2>
          <div className="panel" style={{ padding: 6 }}>
            {asked.length === 0 && (
              <p className="hint" style={{ padding: 14 }}>还没有提过问。在上方输入问题，这里会记录本次访问的提问。</p>
            )}
            {asked.map((q, i) => (
              <div className="list-row" key={`${q}-${i}`}>
                <span style={{ fontSize: 13.5 }}>{q}</span>
              </div>
            ))}
          </div>
        </>
      )}

      {tab === 'adv' && (
        <>
          <div className="panel panel-pad" style={{ marginBottom: 16, maxWidth: 720 }}>
            <h2 className="col-title" style={{ marginTop: 0 }}>检索配置</h2>
            <div className="field">
              <label>检索模式</label>
              <JellyRadio
                items={['向量检索', 'BM25', '混合检索']}
                defaultValue="混合检索"
                size="md"
                chipColor={C.chip}
                activeColor={C.active}
                textColor={C.text}
                activeTextColor={C.onActive}
                onChange={(v) => setUsage({ ...usage, mode: v })}
              />
            </div>
            <p className="hint">检索模式与 topK 将应用于下方的即时切片查询；相似度阈值由服务端配置，当前不提供本页修改。</p>
            <div className="field">
              <label>topK：{usage.topK}（返回切片数）</label>
              <input type="range" min={1} max={20} value={usage.topK} onChange={(e) => setUsage({ ...usage, topK: Number(e.target.value) })} style={{ width: '100%' }} />
            </div>
          </div>

          <h2 className="col-title"><Search size={15} style={{ color: 'var(--primary)' }} /> 切片检索</h2>
          <form onSubmit={(e) => { e.preventDefault(); void runSearch() }} style={{ display: 'flex', gap: 10, marginBottom: 14 }}>
            <div style={{ position: 'relative', flex: 1 }}>
              <Search size={15} style={{ position: 'absolute', left: 12, top: '50%', transform: 'translateY(-50%)', color: 'var(--text-3)' }} />
              <input aria-label="在知识库内检索切片" className="input" value={searchQ} onChange={(e) => setSearchQ(e.target.value)} placeholder="输入关键词，直接检索切片（不调用模型）" style={{ paddingLeft: 36 }} />
            </div>
            <button className="btn btn-primary" type="submit" disabled={!searchQ.trim() || searching}>{searching ? '检索中…' : '检索'}</button>
          </form>

          {!USE_MOCK && (
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 14, flexWrap: 'wrap' }}>
              <button className="btn btn-ghost btn-sm" type="button" disabled={indexing} onClick={() => void runIndex(false)}>建立索引</button>
              <button className="btn btn-ghost btn-sm" type="button" disabled={indexing} onClick={() => void runIndex(true)}>重建索引</button>
              <span className="hint" style={{ margin: 0 }}>
                {chunks.length
                  ? `切片 ${chunks.length} 条 · 向量就绪 ${chunks.filter((c) => c.vectorStatus === 'READY').length} · 全文就绪 ${chunks.filter((c) => c.esStatus === 'READY').length}`
                  : '该库暂无切片'}
                {chunks.some((c) => c.vectorStatus === 'PENDING' || c.esStatus === 'PENDING') ? '（有切片未建索引 → 检索会返回空，点「建立索引」）' : ''}
              </span>
            </div>
          )}

          {hits.map((h, i) => (
            <div className="panel panel-pad" key={h.chunkId ?? h.id ?? i} style={{ marginBottom: 12 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
                <span style={{ fontSize: 13, fontWeight: 600 }}>{h.documentName ?? h.headingPath ?? '切片'}</span>
                {typeof h.score === 'number' && <span className="chip">{(h.score * 100).toFixed(0)}%</span>}
              </div>
              <p style={{ margin: 0, fontSize: 13, color: 'var(--text-2)' }}>{h.content}</p>
            </div>
          ))}
          {!searching && searchQ.trim() && hits.length === 0 && (
            <p className="hint">没有命中。若上方提示"有切片未建索引"，先点「建立索引」再检索。</p>
          )}
        </>
      )}

      {tab === 'links' && (
        <>
          {!USE_MOCK && (
            <div className="panel panel-pad" style={{ marginBottom: 14 }}>
              <p className="hint" style={{ margin: 0 }}>
                {docs.length
                  ? `文档「${docs[0].name}」：反向链接 ${linkData?.backlinks.length ?? 0} · 出链 ${linkData?.outlinks.length ?? 0} · 待创建 ${linkData?.dangling ?? 0} · 相关推荐 ${related.length}`
                  : '该知识库暂无文档，先导入再来看关联。'}
              </p>
            </div>
          )}
          <div className="panel" style={{ padding: 8 }}>
            {(USE_MOCK
              ? RELATED.map((r) => ({ title: r.title, why: r.why, tone: r.status === 'resolved' ? 'success' : 'accent', tag: r.status === 'resolved' ? '已关联' : '相关' }))
              : [
                  ...(linkData?.backlinks ?? []).map((x) => ({ title: x.resolvedTitle ?? x.display ?? x.target ?? '文档', why: '反向链接 · ' + (x.linkType ?? ''), tone: 'success', tag: '已关联' })),
                  ...(linkData?.outlinks ?? []).map((x) => ({ title: x.resolvedTitle ?? x.display ?? x.target ?? '文档', why: '出链 · ' + (x.linkType ?? ''), tone: 'success', tag: '已关联' })),
                  ...related.map((x) => ({ title: x.title ?? x.documentId ?? '文档', why: (x.reasons && x.reasons.length ? x.reasons.join(' · ') : '相关推荐') + (typeof x.score === 'number' ? ' · 相关度 ' + x.score : ''), tone: 'accent', tag: '相关' })),
                ]
            ).map((r, i) => (
              <div className="list-row" key={i}>
                <FileText size={15} style={{ color: 'var(--primary)' }} />
                <div style={{ minWidth: 0 }}>
                  <div style={{ fontSize: 13.5, fontWeight: 500 }}>{r.title}</div>
                  <div style={{ fontSize: 12, color: 'var(--text-3)' }}>{r.why}</div>
                </div>
                <span className="badge" data-tone={r.tone} style={{ marginLeft: 'auto' }}>{r.tag}</span>
              </div>
            ))}
            {!USE_MOCK && docs.length > 0 && !(linkData?.outlinks.length) && !(linkData?.backlinks.length) && !related.length && (
              <p className="hint" style={{ padding: 10 }}>暂无关联。关联来自文档间的双链（[[wiki 链接]]）与共同标签。</p>
            )}
          </div>
        </>
      )}

      <DocPreview
        doc={preview}
        theme={theme}
        content={previewContent}
        chunks={previewChunks}
        loading={previewLoading}
        error={previewError}
        onDownload={preview && previewContent && !USE_MOCK ? () => download(preview.name, previewContent, 'text/markdown;charset=utf-8') : undefined}
        onClose={() => setPreview(null)}
      />

      <Modal
        open={importOpen}
        title="导入文档"
        onClose={() => { if (!uploading) { setImportOpen(false); setUploadError('') } }}
        shouldBlockClose={() => uploading}
        width={520}
        footer={(
          <>
            <button className="btn btn-ghost" type="button" onClick={() => setImportOpen(false)} disabled={uploading}>取消</button>
            <button
              className="btn btn-primary"
              type="button"
              onClick={() => { void uploadDocument() }}
              disabled={uploading || !picked}
            >
              {uploading ? '正在上传…' : '上传并开始解析'}
            </button>
          </>
        )}
      >
        <div className="field">
          <label htmlFor="kb-import-file">选择一个文档</label>
          <input
            id="kb-import-file"
            className="input"
            type="file"
            accept=".md,.markdown,.txt,.pdf,.docx"
            disabled={uploading}
            onChange={(event) => { setPicked(event.target.files?.[0] ?? null); setUploadError('') }}
          />
          <p className="hint">支持 Markdown、TXT、PDF 与 DOCX。每次上传一个文件；最大体积以服务端配置为准。</p>
          {picked && <p className="hint" aria-live="polite">已选择：{picked.name}</p>}
          {uploadError && <p className="form-error active" role="alert">{uploadError}</p>}
        </div>
      </Modal>
    </div>
  )
}