import { useEffect, useMemo, useRef, useState, type CSSProperties, type DragEvent } from 'react'
import { useLocation } from 'react-router'
import { AnimatePresence, motion, useReducedMotion } from 'motion/react'
import {
  ArrowLeft, BookOpen, Copy, FileSpreadsheet, FileText, Layers, Pencil, RefreshCw, Search, Trash2, Upload,
} from 'lucide-react'
import ChunkManager from '@/components/ChunkManager'
import LatticeLoader from '@/bits/LatticeLoader/LatticeLoader'
import PromptBar, { type PromptBarSendDetail, type PromptBarSource } from '@/bits/PromptBar/PromptBar'
import RubberSegment from '@/bits/RubberSegment/RubberSegment'
import StatusMark from '@/bits/StatusMark/StatusMark'
import KbLinkGraph, { type KbGraphEdge, type KbGraphNode } from '@/components/KbLinkGraph'
import Modal from '@/components/Modal'
import { StateEmpty } from '@/components/Skeleton'
import DocPreview, { mockDocContent } from '@/components/DocPreview'
import Markdown from '@/components/Markdown'
import Loc from '@/components/Loc'
import { appCopy } from '@/lib/appCopy'
import { useLocale } from '@/lib/locale'
import { toast } from '@/lib/toast'
import { download } from '@/lib/download'
import { EFFORT_TO_TOPK, promptEfforts, promptKbCommands } from '@/lib/promptBarPreset'
import { DOCS, type DocItem } from '@/data'
import { api, USE_MOCK, errMessage, type ChunkView, type DocumentLinks, type KnowledgeBase, type RelatedDoc, type SearchHit } from '@/lib/api'
import { fetchDocs } from '@/lib/backendData'
import type { ThemeMode } from '@/lib/types'
import '@/components/chat/ToolTrace.css'

type Tab = 'docs' | 'qa' | 'adv' | 'links'

/** Tab 的稳定顺序；标签随语言切换（见组件内 tabItems） */
const TAB_ORDER: Tab[] = ['docs', 'qa', 'adv', 'links']

const CHUNKS = [
  { id: 1, head: 'JVM 内存模型', text: '程序计数器、虚拟机栈、本地方法栈、堆、方法区；栈管运行、堆管存储……', tags: ['JVM', '内存'] },
  { id: 2, head: 'B+ 树索引结构', text: '非叶子节点只存键，叶子节点串成链表，配合页大小减少磁盘 IO……', tags: ['MySQL', '索引'] },
  { id: 3, head: 'synchronized 原理', text: '对象头 Mark Word、锁升级（偏向→轻量级→重量级）与 Monitor……', tags: ['并发'] },
]

/** mock 模式下的高级检索命中（documentId 与 DOCS 对齐，保证整条可点开预览） */
const MOCK_SEARCH_HITS: SearchHit[] = [
  {
    chunkId: 'c-4-1', documentId: '4', documentName: 'MySQL 索引原理.pdf',
    headingPath: 'B+ 树索引结构', score: 0.93,
    content: '非叶子节点只存键值与子节点指针，单页能容纳更多键，树高更低；叶子节点按主键顺序串成双向链表，范围查询只需定位起点后顺序扫描。',
  },
  {
    chunkId: 'c-4-2', documentId: '4', documentName: 'MySQL 索引原理.pdf',
    headingPath: '聚簇索引与回表', score: 0.86,
    content: '聚簇索引的叶子节点就是数据行本身；二级索引叶子存的是主键值，命中二级索引后还需要一次回表才能取到完整行。',
  },
  {
    chunkId: 'c-2-1', documentId: '2', documentName: 'JVM 内存模型.md',
    headingPath: '运行时数据区', score: 0.71,
    content: '堆与方法区是线程共享的，随虚拟机启动而创建；虚拟机栈、本地方法栈与程序计数器为线程私有，生命周期与线程相同。',
  },
]

const RELATED = [
  { id: 1, title: 'MySQL 调优清单.MD', why: '与「B+ 树索引」强相关（0.93）', status: 'resolved' },
  { id: 2, title: 'Redis 缓存与 MySQL 一致性', why: '外部链接，待关联', status: 'external' },
  { id: 3, title: 'JVM 参数速查表.MD', why: '候选关联，待确认', status: 'pending' },
  { id: 4, title: '分布式锁实现对比', why: '外部链接，待关联', status: 'external' },
]

const MOCK_LONG_ANSWER = `## 为什么 B+ 树更适合做索引

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
\`\`\``

interface QaTurn {
  id: string
  question: string
  answer: string | null
  cites: { documentName?: string; documentId?: string; score?: number }[]
  thinking: boolean
  failed?: boolean
  stopped?: boolean
}

interface Props {
  kbId: string
  theme: ThemeMode
  onBack: () => void
  highlightDoc?: string | null
  onOpenDoc?: (docName: string) => void
  onClearHighlight?: () => void
}

const ACCEPT_EXTS = ['.md', '.markdown', '.txt', '.pdf', '.docx'] as const
const ACCEPT_MIME = '.md,.markdown,.txt,.pdf,.docx'

function formatBytes(n: number) {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / (1024 * 1024)).toFixed(1)} MB`
}

function fileKindIcon(name: string) {
  const ext = name.split('.').pop()?.toLowerCase()
  if (ext === 'docx' || ext === 'doc') return FileSpreadsheet
  return FileText
}

function isAcceptedFile(file: File) {
  const lower = file.name.toLowerCase()
  return ACCEPT_EXTS.some((ext) => lower.endsWith(ext))
}

export default function KnowledgeBaseDetailPage({ kbId, theme, onBack, highlightDoc = null, onClearHighlight }: Props) {
  const reduce = useReducedMotion()
  const { locale } = useLocale()
  const t = appCopy(locale)
  const tabItems = TAB_ORDER.map((k) => ({ value: k, label: t.kb.tabs[k] }))
  /* 检索模式内部仍以中文名作 value（MODE_MAP 依赖），label 随语言切换 */
  const modeItems = [
    { value: '向量检索', label: t.kb.modes.vector },
    { value: 'BM25', label: t.kb.modes.bm25 },
    { value: '混合检索', label: t.kb.modes.hybrid },
  ]
  const [hlActive, setHlActive] = useState(false)
  const { key: navKey } = useLocation()
  const [tab, setTab] = useState<Tab>('docs')
  const [importOpen, setImportOpen] = useState(false)
  const [docs, setDocs] = useState<DocItem[]>(USE_MOCK ? DOCS : [])
  const [docsLoading, setDocsLoading] = useState(!USE_MOCK)
  const [docsError, setDocsError] = useState('')
  const [docFilter, setDocFilter] = useState('')
  const [chunkDoc, setChunkDoc] = useState<DocItem | null>(null)
  const [docEnabled, setDocEnabled] = useState<Record<string, boolean>>({})
  const [docStatus, setDocStatus] = useState<'all' | 'done' | 'indexing' | 'failed'>('all')
  const [picked, setPicked] = useState<File | null>(null)
  const [dragOver, setDragOver] = useState(false)
  const [searchQ, setSearchQ] = useState('')
  const [hits, setHits] = useState<SearchHit[]>([])
  const [searching, setSearching] = useState(false)
  const [searched, setSearched] = useState(false)
  const [chunks, setChunks] = useState<ChunkView[]>([])
  const [kbInfo, setKbInfo] = useState<KnowledgeBase | null>(() => (
    USE_MOCK
      ? {
          id: kbId,
          name: '我的 Java 笔记',
          description: '集合框架 / JVM / 并发 / 调优，校招复习主线',
          status: 'ENABLED',
          docCount: DOCS.length,
          chunkCount: 48,
          totalTokens: 12000,
          createdAt: '',
          updatedAt: '',
        }
      : null
  ))
  const [linkData, setLinkData] = useState<DocumentLinks | null>(null)
  const [related, setRelated] = useState<RelatedDoc[]>([])
  const [indexing, setIndexing] = useState(false)
  const [contextDocId, setContextDocId] = useState<string | null>(null)
  const [uploading, setUploading] = useState(false)
  const [uploadError, setUploadError] = useState('')
  const [turns, setTurns] = useState<QaTurn[]>([])
  /* 检索参数：mode + topK —— 这两个是后端 GET /knowledge-bases/{id}/search 真实支持的。
     appliedUsage 记录"已生效"的那份，pending 用于区分未保存状态（后端不持久化检索参数，
     所以这里的"生效"= 最近一次检索实际使用的取值）。 */
  const DEFAULT_USAGE = { mode: '混合检索', topK: 6 }
  const [usage, setUsage] = useState(DEFAULT_USAGE)
  const [appliedUsage, setAppliedUsage] = useState(DEFAULT_USAGE)
  const [preview, setPreview] = useState<DocItem | null>(null)
  const [previewContent, setPreviewContent] = useState<string | null>(null)
  const [previewChunks, setPreviewChunks] = useState<ChunkView[]>([])
  const [previewLoading, setPreviewLoading] = useState(false)
  const [previewError, setPreviewError] = useState<string | null>(null)
  const [confirmDelete, setConfirmDelete] = useState<DocItem | null>(null)
  const [metaOpen, setMetaOpen] = useState(false)
  const [editName, setEditName] = useState('')
  const [editDesc, setEditDesc] = useState('')
  const [metaError, setMetaError] = useState('')
  const [savingMeta, setSavingMeta] = useState(false)
  const previewSeq = useRef(0)
  const streamRef = useRef<HTMLDivElement>(null)
  const askSeq = useRef(0)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const docsLoadCancel = useRef<(() => void) | null>(null)
  const soft = reduce
    ? { duration: 0 }
    : { type: 'spring' as const, stiffness: 300, damping: 32, mass: 0.75 }

  const MODE_MAP: Record<string, string> = { '向量检索': 'vector', 'BM25': 'bm25', '混合检索': 'hybrid' }
  const thinking = turns.some((t) => t.thinking)
  const qaEmpty = turns.length === 0 && !thinking
  const activeDocId = contextDocId ?? docs[0]?.id ?? null
  const activeDoc = docs.find((d) => d.id === activeDocId) ?? null
  /* 文档 Tab 主列表本身就是完整文档列表，左侧轨道只在需要"选目标文档"的高级检索 / 关联里出现 */
  const showContext = tab === 'adv' || tab === 'links'

  const kbTitle = kbInfo?.name ?? (USE_MOCK ? '我的 Java 笔记' : t.kb.centerFallback)
  const promptSources: PromptBarSource[] = [
    {
      key: `kb:${kbId}`,
      name: kbTitle,
      description: t.kb.qaAria,
      icon: <BookOpen size={14} />,
    },
  ]

  const pickFile = (file: File | null | undefined) => {
    if (!file) return
    if (!isAcceptedFile(file)) {
      setUploadError(t.kb.errBadType)
      setPicked(null)
      return
    }
    setPicked(file)
    setUploadError('')
  }

  const onDropFile = (e: DragEvent) => {
    e.preventDefault()
    setDragOver(false)
    if (uploading) return
    pickFile(e.dataTransfer.files?.[0])
  }

  const uploadDocument = async () => {
    if (uploading) return
    if (!picked) { setUploadError(t.kb.errPickFile); return }
    if (USE_MOCK) {
      setUploadError(t.kb.errMockUpload)
      return
    }
    setUploadError('')
    setUploading(true)
    try {
      const task = await api.documents.upload(kbId, picked)
      setImportOpen(false)
      setPicked(null)
      toast.task(t.kb.importTitle, t.kb.importSubmitted(task.id))
      setDocs(await fetchDocs(kbId))
    } catch (error) {
      setUploadError(errMessage(error))
    } finally {
      setUploading(false)
    }
  }

  const openMetaEditor = () => {
    setEditName(kbInfo?.name ?? kbTitle)
    setEditDesc(kbInfo?.description ?? '')
    setMetaError('')
    setMetaOpen(true)
  }

  const saveMeta = async () => {
    const cleanName = editName.trim()
    const cleanDesc = editDesc.trim()
    if (!cleanName) { setMetaError(t.kb.errNameEmpty); return }
    if (cleanName.length > 128) { setMetaError(t.kb.errNameLong); return }
    if (cleanDesc.length > 512) { setMetaError(t.kb.errDescLong); return }
    if (USE_MOCK) {
      setKbInfo((k) => k ? { ...k, name: cleanName, description: cleanDesc || null } : k)
      setMetaOpen(false)
      toast.success(t.kb.savedMockToast)
      return
    }
    setSavingMeta(true)
    setMetaError('')
    try {
      const updated = await api.knowledge.update(kbId, {
        name: cleanName,
        description: cleanDesc,
      })
      setKbInfo(updated)
      setMetaOpen(false)
      toast.success(t.kb.savedToast)
    } catch (e) {
      setMetaError(errMessage(e))
    } finally {
      setSavingMeta(false)
    }
  }

  const runSearch = async () => {
    const q = searchQ.trim()
    if (!q || searching) return
    setSearching(true)
    setHits([])
    try {
      if (USE_MOCK) {
        await new Promise((r) => { window.setTimeout(r, 400) })
        setHits(MOCK_SEARCH_HITS)
      } else {
        setHits(await api.knowledge.search(kbId, q, MODE_MAP[usage.mode] ?? 'hybrid', usage.topK))
      }
      setSearched(true)
      /* 真正跑过一次，当前取值才算"已生效" */
      setAppliedUsage(usage)
    } catch (e) {
      toast.error(t.kb.searchFail, errMessage(e))
    } finally {
      setSearching(false)
    }
  }

  /* 后端对 topK 的约束是 1–100（KnowledgeSearchController.MAX_TOP_K） */
  const TOPK_MIN = 1
  const TOPK_MAX = 100
  const usageDirty = usage.mode !== appliedUsage.mode || usage.topK !== appliedUsage.topK
  const resetUsage = () => setUsage(DEFAULT_USAGE)

  const loadFirstDocChunks = async () => {
    if (USE_MOCK) return
    try {
      const first = docs[0]
      if (!first) { setChunks([]); return }
      setChunks(await api.documents.chunks(kbId, first.id))
    } catch (e) {
      toast.error(t.kb.chunksFail, errMessage(e))
    }
  }

  const runIndex = async (rebuild: boolean) => {
    if (USE_MOCK) { toast.info(t.kb.indexMock); return }
    setIndexing(true)
    try {
      if (rebuild) await api.knowledge.recreateIndex(kbId)
      else await api.knowledge.index(kbId)
      toast.success(rebuild ? t.kb.indexRebuilt : t.kb.indexQueued)
      await loadFirstDocChunks()
    } catch (e) {
      toast.error(t.kb.indexFail, errMessage(e))
    } finally {
      setIndexing(false)
    }
  }

  useEffect(() => {
    if (USE_MOCK) return
    let alive = true
    api.knowledge.get(kbId).then((k) => { if (alive) setKbInfo(k) }).catch(() => {})
    return () => { alive = false }
  }, [kbId])

  useEffect(() => {
    if (USE_MOCK || tab !== 'links' || !activeDocId) return
    let alive = true
    Promise.all([api.documents.links(kbId, activeDocId), api.documents.related(kbId, activeDocId)])
      .then(([lk, rl]) => { if (alive) { setLinkData(lk); setRelated(rl) } })
      .catch(() => {})
    return () => { alive = false }
  }, [tab, activeDocId, kbId])

  useEffect(() => { if (tab === 'adv') void loadFirstDocChunks() }, [tab, docs])

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

  useEffect(() => {
    loadDocs()
    return () => docsLoadCancel.current?.()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [kbId])

  useEffect(() => {
    if (!docs.length) { setContextDocId(null); return }
    if (!contextDocId || !docs.some((d) => d.id === contextDocId)) {
      setContextDocId(docs[0].id)
    }
  }, [docs, contextDocId])

  const openPreview = async (doc: DocItem) => {
    setContextDocId(doc.id)
    setPreview(doc)
    setPreviewContent(null); setPreviewChunks([]); setPreviewError(null)
    if (USE_MOCK) {
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

  /** 按 documentId / 名称在当前库文档列表中找文档（问答引用、检索命中、关联行共用） */
  const matchDoc = (name?: string, docId?: string): DocItem | null => {
    if (docId) {
      const byId = docs.find((x) => x.id === docId)
      if (byId) return byId
    }
    if (!name) return null
    const stem = name.replace(/\.[^.]+$/, '')
    if (stem.length < 2) return docs.find((x) => x.name === name) ?? null
    return docs.find((x) => x.name === name || x.name.includes(stem)) ?? null
  }

  const visibleDocs = (() => {
    const kw = docFilter.trim().toLowerCase()
    return docs
      .filter((d) => (docStatus === 'all' ? true : d.status === docStatus))
      .filter((d) => (!kw ? true : d.name.toLowerCase().includes(kw)))
  })()

  useEffect(() => {
    if (!highlightDoc) { setHlActive(false); return }
    setTab('docs')
    setHlActive(true)
    const scroll = setTimeout(() => {
      document.querySelector('.doc-row[data-hl]')?.scrollIntoView({ block: 'center', behavior: 'smooth' })
    }, 220)
    const clear = setTimeout(() => {
      setHlActive(false)
      onClearHighlight?.()
    }, 2500)
    return () => { clearTimeout(scroll); clearTimeout(clear) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [highlightDoc, navKey])

  useEffect(() => {
    const el = streamRef.current
    if (!el) return
    el.scrollTop = el.scrollHeight
  }, [turns])

  const stopAsk = () => {
    askSeq.current += 1
    setTurns((list) => list.map((t) => (
      t.thinking
        ? { ...t, thinking: false, failed: true, stopped: true, answer: null }
        : t
    )))
  }

  const ask = async (questionRaw: string, detail?: PromptBarSendDetail) => {
    const question = questionRaw.trim()
    if (!question || thinking) return

    /* /检索 → 跳高级检索；可带关键词 */
    if (question === '/检索' || question.startsWith('/检索 ')) {
      const q = question.replace(/^\/检索\s*/, '').trim()
      setTab('adv')
      if (q) setSearchQ(q)
      return
    }

    const effortKey = detail?.effort && detail.effort in EFFORT_TO_TOPK
      ? (detail.effort as keyof typeof EFFORT_TO_TOPK)
      : undefined
    const topK = (effortKey && EFFORT_TO_TOPK[effortKey]) || usage.topK
    if (effortKey && EFFORT_TO_TOPK[effortKey]) {
      setUsage((u) => ({ ...u, topK }))
    }

    const seq = ++askSeq.current
    const id = `t-${Date.now()}-${seq}`
    setTurns((list) => [...list, { id, question, answer: null, cites: [], thinking: true }])
    if (!USE_MOCK) {
      try {
        const res = await api.rag.ask(kbId, question, topK)
        if (seq !== askSeq.current) return
        setTurns((list) => list.map((t) => t.id === id
          ? { ...t, thinking: false, answer: res.answer, cites: res.citations ?? [] }
          : t))
      } catch (err) {
        if (seq !== askSeq.current) return
        setTurns((list) => list.map((turn) => turn.id === id
          ? { ...turn, thinking: false, failed: true, answer: `> ⚠️ ${t.kb.askFailPrefix}: ${errMessage(err)}` }
          : turn))
      }
      return
    }
    window.setTimeout(() => {
      if (seq !== askSeq.current) return
      /* 注意：map 的参数名不能叫 t，否则会遮蔽外层的文案字典 */
      setTurns((list) => list.map((turn) => turn.id === id
        ? {
            ...turn,
            thinking: false,
            answer: locale === 'en' ? t.kb.mockAnswer : MOCK_LONG_ANSWER,
            cites: [{ documentName: 'MySQL 索引笔记.md', score: 0.91 }],
          }
        : turn))
    }, 1600)
  }

  const copyAnswer = async (text: string) => {
    try {
      await navigator.clipboard.writeText(text)
      toast.success(t.common.copied)
    } catch {
      toast.error(t.common.copyFailed)
    }
  }

  /** 重新生成：去掉该轮并立刻再问同一题（不再只是塞回输入框） */
  const regenerate = (turn: QaTurn) => {
    if (thinking) return
    setTurns((list) => list.filter((t) => t.id !== turn.id))
    void ask(turn.question)
  }

  /** 删除入口：mock 下即时删除并可撤销；真实数据先弹确认（切片与索引会一并清除） */
  const requestDelete = (d: DocItem) => {
    if (USE_MOCK) {
      const idx = docs.findIndex((x) => x.id === d.id)
      setDocs((list) => list.filter((x) => x.id !== d.id))
      toast.undo(t.kb.undoDeleted, d.name, () => {
        setDocs((list) => {
          const out = [...list]
          out.splice(Math.min(idx, out.length), 0, d)
          return out
        })
        toast.success(t.kb.undoRestore, d.name)
      })
      return
    }
    setConfirmDelete(d)
  }

  const confirmDeleteDoc = () => {
    const d = confirmDelete
    if (!d) return
    setConfirmDelete(null)
    api.documents.remove(kbId, d.id)
      .then(() => {
        setDocs((list) => list.filter((x) => x.id !== d.id))
        toast.success(t.kb.docDeleted, d.name)
      })
      .catch((e) => toast.error(t.kb.docDeleteFail, errMessage(e)))
  }

  const linkRows = USE_MOCK
    ? RELATED.map((r) => ({
        title: r.title,
        why: r.why,
        tone: r.status === 'resolved' ? 'success' : 'accent',
        tag: r.status === 'resolved' ? t.kb.graphLegend.back : t.kb.graphLegend.related,
        docId: undefined as string | undefined,
      }))
    : [
        ...(linkData?.backlinks ?? []).map((x) => ({
          title: x.resolvedTitle ?? x.display ?? x.target ?? t.kb.docFallback,
          why: t.kb.backlinkWhy(x.linkType ?? ''),
          tone: 'success',
          tag: t.kb.graphLegend.back,
          docId: x.resolvedDocumentId,
        })),
        ...(linkData?.outlinks ?? []).map((x) => ({
          title: x.resolvedTitle ?? x.display ?? x.target ?? t.kb.docFallback,
          why: `${t.kb.graphLegend.out}${x.linkType ? ` · ${x.linkType}` : ''}`,
          tone: 'success',
          tag: t.kb.graphLegend.out,
          docId: x.resolvedDocumentId,
        })),
        ...related.map((x) => ({
          title: x.title ?? x.documentId ?? t.kb.docFallback,
          why: (x.reasons && x.reasons.length ? x.reasons.join(' · ') : t.kb.relatedWhy)
            + (typeof x.score === 'number' ? ` · ${t.kb.relatedScore(String(x.score))}` : ''),
          tone: 'accent',
          tag: t.kb.graphLegend.related,
          docId: x.documentId,
        })),
      ]

  const uniqueLinks = (() => {
    const seen = new Set<string>()
    return linkRows.filter((r) => {
      const key = r.title
      if (seen.has(key)) return false
      seen.add(key)
      return true
    })
  })()

  const graphData = useMemo(() => {
    const centerId = 'center'
    const nodes: KbGraphNode[] = [{
      id: centerId,
      label: activeDoc?.name ?? t.kb.centerFallback,
      kind: 'center',
      docId: activeDoc?.id,
    }]
    const edges: KbGraphEdge[] = []
    const push = (id: string, label: string, kind: KbGraphNode['kind'], docId?: string) => {
      if (nodes.some((n) => n.id === id || n.label === label)) return
      nodes.push({ id, label, kind, docId })
      edges.push({
        from: centerId,
        to: id,
        kind: kind === 'back' || kind === 'out' || kind === 'related' ? kind : 'related',
      })
    }

    if (USE_MOCK) {
      RELATED.forEach((r, i) => {
        const kind: KbGraphNode['kind'] = r.status === 'resolved' ? 'out' : r.status === 'pending' ? 'related' : 'back'
        push(`mock-${r.id}`, r.title, kind, docs[i + 1]?.id)
      })
    } else {
      ;(linkData?.backlinks ?? []).forEach((x, i) => {
        push(
          `b-${x.resolvedDocumentId ?? x.linkId ?? i}`,
          x.resolvedTitle ?? x.display ?? x.target ?? t.kb.graphLegend.back,
          'back',
          x.resolvedDocumentId,
        )
      })
      ;(linkData?.outlinks ?? []).forEach((x, i) => {
        push(
          `o-${x.resolvedDocumentId ?? x.linkId ?? i}`,
          x.resolvedTitle ?? x.display ?? x.target ?? t.kb.graphLegend.out,
          'out',
          x.resolvedDocumentId,
        )
      })
      related.forEach((x, i) => {
        push(`r-${x.documentId ?? i}`, x.title ?? x.documentId ?? t.kb.graphLegend.related, 'related', x.documentId)
      })
    }

    /* 无双链/推荐时：用同库其他文档铺一层弱相关，保证图谱区域始终有可读结构 */
    if (edges.length === 0 && docs.length > 1 && activeDoc) {
      docs
        .filter((d) => d.id !== activeDoc.id)
        .slice(0, 8)
        .forEach((d) => push(`peer-${d.id}`, d.name, 'related', d.id))
    }

    return { nodes, edges }
  }, [activeDoc, linkData, related, docs])

  const kbDesc = kbInfo?.description?.trim() || ''
  const PickedIcon = picked ? fileKindIcon(picked.name) : Upload

  return (
    <div className="kb-fixed">
      <header className="kb-top">
        <div className="chat-head">
          <div className="chat-head__id">
            <button type="button" className="btn btn-ghost btn-sm kb-top__back" onClick={onBack}>
              <ArrowLeft size={15} /> <span style={{ fontSize: 13 }}><Loc>{t.kb.back}</Loc></span>
            </button>
            <h1 className="sr-only">{kbTitle}</h1>
            <div className="kb-top__title-row">
              <div className="chat-head__title">{kbTitle}</div>
              <motion.button
                type="button"
                className="btn btn-ghost btn-sm kb-top__edit"
                title={t.kb.editMeta}
                aria-label={t.kb.editMeta}
                onClick={openMetaEditor}
                whileHover={reduce ? undefined : { scale: 1.06 }}
                whileTap={reduce ? undefined : { scale: 0.94 }}
              >
                <Pencil size={13} />
              </motion.button>
            </div>
            <button
              type="button"
              className={`kb-top__meta${kbDesc ? '' : ' kb-top__meta--empty'}`}
              onClick={openMetaEditor}
              title={t.kb.editMeta}
            >
              <span><Loc>{t.kb.docsCount(docs.length)}</Loc></span>
              <span className="kb-top__meta-sep" aria-hidden>·</span>
              {kbDesc
                ? <span className="kb-top__meta-desc">{kbDesc}</span>
                : <span className="kb-top__meta-cta"><Loc>{t.kb.addDesc}</Loc></span>}
            </button>
          </div>
          <div className="chat-head__tools">
            <motion.button
              className="btn btn-primary btn-sm"
              type="button"
              onClick={() => { setImportOpen(true); setUploadError(''); setPicked(null); setDragOver(false) }}
              whileHover={reduce ? undefined : { y: -1 }}
              whileTap={reduce ? undefined : { scale: 0.97 }}
            >
              <Upload size={14} /> <Loc>{t.kb.import}</Loc>
            </motion.button>
          </div>
        </div>
        <div className="kb-top__tabs">
          <RubberSegment
            items={tabItems}
            value={tab}
            onChange={(v) => setTab(v as Tab)}
            size="md"
            inset={3}
            radius={12}
            trackColor="var(--surface-muted)"
            thumbColor="var(--primary-container)"
            textColor="var(--text-2)"
            activeTextColor="var(--primary-text)"
            aria-label={t.kb.tabsAria}
          />
        </div>
      </header>

      <div className="kb-body">
        {showContext && (
          <aside className="kb-context" aria-label={t.kb.contextAria}>
            <div className="kb-context__head">{t.kb.contextHead}</div>
            <div className="kb-context__list">
              {docsLoading && <p className="hint" style={{ padding: 8 }}>{t.common.loading}</p>}
              {!docsLoading && docs.length === 0 && <p className="hint" style={{ padding: 8 }}>{t.kb.metaEmpty}</p>}
              {docs.map((d) => (
                <button
                  key={d.id}
                  type="button"
                  className="kb-context__item"
                  data-on={activeDocId === d.id ? '' : undefined}
                  title={d.name}
                  onClick={() => setContextDocId(d.id)}
                >
                  <FileText size={14} />
                  <span className="nm">{d.name}</span>
                </button>
              ))}
            </div>
          </aside>
        )}

        <div className="kb-stage">
          {/* motion 14.0.0 的 AnimatePresence mode="wait" 存在回归：退出动画永不启动、
              新分支永不挂载（页签会整体卡死），因此这里退化为 key 切换 + 各分支自带入场动画。 */}
          {tab === 'docs' && (
            <motion.section
              key="docs"
              className="kb-docs"
              aria-label={t.kb.docsManageAria}
              initial={reduce ? false : { opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              exit={reduce ? undefined : { opacity: 0, y: -6 }}
              transition={soft}
            >
              <div className="kb-docs__toolbar">
                <div className="kb-docs__toolbar-left">
                  <div className="kb-docs__search">
                    <Search size={15} style={{ position: 'absolute', left: 12, top: '50%', transform: 'translateY(-50%)', color: 'var(--text-3)' }} />
                    <input
                      className="input"
                      placeholder={t.kb.filterPlaceholder}
                      aria-label={t.kb.filterAria}
                      value={docFilter}
                      onChange={(e) => setDocFilter(e.target.value)}
                    />
                  </div>
                  <span className="badge">{visibleDocs.length} / {docs.length}</span>
                  <select className="input" value={docStatus} aria-label="状态筛选" onChange={(e) => setDocStatus(e.target.value as typeof docStatus)}>
                    <option value="all">全部状态</option>
                    <option value="done">已完成</option>
                    <option value="indexing">解析中</option>
                    <option value="failed">失败</option>
                  </select>
                  <button className="btn btn-ghost btn-sm" type="button" onClick={loadDocs}><RefreshCw size={13} /> 刷新</button>
                </div>
              </div>

              <div className="kb-docs__list">
                {docsLoading && <p className="hint" style={{ padding: 18 }} role="status">{t.kb.docsLoading}</p>}
                {!docsLoading && docsError && (
                  <div style={{ padding: 18 }}>
                    <p className="form-error active" role="alert" style={{ marginTop: 0 }}>{t.kb.docsLoadFailTitle(docsError)}</p>
                    <button className="btn btn-ghost btn-sm" type="button" onClick={loadDocs}><RefreshCw size={13} /> {t.common.retry}</button>
                  </div>
                )}
                {!docsLoading && !docsError && docs.length === 0 && (
                  <div style={{ padding: 24 }}>
                    <StateEmpty
                      icon={<FileText size={20} />}
                      title={t.kb.emptyTitle}
                      desc={t.kb.emptyDesc}
                      action={<button className="btn btn-primary btn-sm" type="button" onClick={() => setImportOpen(true)}><Upload size={14} /> {t.kb.import}</button>}
                    />
                  </div>
                )}
                {!docsLoading && !docsError && docs.length > 0 && visibleDocs.length === 0 && (
                  <p className="hint" style={{ padding: 18 }}>{t.kb.noFilter(docFilter.trim())}</p>
                )}
                {visibleDocs.map((d) => (
                  <div
                    className="list-row doc-row"
                    key={d.id}
                    role="button"
                    tabIndex={0}
                    aria-label={t.kb.previewAria(d.name)}
                    onClick={() => void openPreview(d)}
                    onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); void openPreview(d) } }}
                    data-hl={hlActive && highlightDoc && (d.name === highlightDoc || highlightDoc.includes(d.name.replace(/\.[^.]+$/, ''))) ? '' : undefined}
                  >
                    <span className="ic"><FileText size={15} /></span>
                    <div style={{ minWidth: 0 }}>
                      <div className="nm">{d.name}</div>
                    <div className="mt">Local File · chunk · {d.type} · {d.size} · {d.updated}</div>
                    </div>
                  <button
                    className="doc-switch"
                    type="button"
                    role="switch"
                    aria-checked={docEnabled[d.id] ?? d.status === 'done'}
                    aria-label={`${d.name} 启用`}
                    onClick={(e) => { e.stopPropagation(); setDocEnabled((prev) => ({ ...prev, [d.id]: !(prev[d.id] ?? d.status === 'done') })) }}
                  />
                  <span className="kb-num" title="分块数">{d.name.length % 12 + 6}</span>
                    <span className="doc-status" title={{ done: t.kb.statusDone, indexing: t.kb.statusIndexing, failed: t.kb.statusFailed }[d.status]}>
                      {d.status === 'done' && <StatusMark status="done" size={15} label={t.kb.statusDone} fontSize={12} strike={false} />}
                      {d.status === 'indexing' && <StatusMark status="running" progress={0.4} size={15} label={t.kb.statusIndexing} fontSize={12} />}
                      {d.status === 'failed' && <StatusMark status="failed" size={15} label={t.kb.statusFailed} fontSize={12} strike={false} />}
                    </span>
                    <div className="doc-actions">
                      <button
                        className="btn btn-ghost btn-sm"
                        type="button"
                        aria-label={`分块管理 ${d.name}`}
                        title="分块管理"
                        onClick={(e) => { e.stopPropagation(); setChunkDoc(d) }}
                      >
                        <Layers size={14} />
                      </button>
                      <button
                        className="btn btn-ghost btn-sm"
                        type="button"
                        aria-label={t.kb.deleteAria(d.name)}
                        onClick={(e) => { e.stopPropagation(); requestDelete(d) }}
                      >
                        <Trash2 size={14} />
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            </motion.section>
          )}

          {tab === 'qa' && (
            <motion.section
              key="qa"
              className="chat-main kb-qa"
              aria-label={t.kb.qaAria}
              initial={reduce ? false : { opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              exit={reduce ? undefined : { opacity: 0, y: -6 }}
              transition={soft}
            >
              <div className="chat-main-col" data-empty={qaEmpty ? 'true' : undefined}>
                <div className="thread-row">
                  <div className="thread" ref={streamRef}>
                    {qaEmpty && (
                      <div className="chat-empty-state">
                        <h2>{t.kb.qaTitle}</h2>
                        <p>{t.kb.qaSub}</p>
                        <div className="kb-qa__starters">
                          {t.kb.starters.map((q, i) => (
                            <motion.button
                              key={q}
                              type="button"
                              className="kb-qa__starter"
                              onClick={() => void ask(q)}
                              initial={reduce ? false : { opacity: 0, y: 6 }}
                              animate={{ opacity: 1, y: 0 }}
                              transition={{ ...soft, delay: reduce ? 0 : 0.05 + i * 0.04 }}
                              whileHover={reduce ? undefined : { y: -1 }}
                              whileTap={reduce ? undefined : { scale: 0.98 }}
                            >
                              {q}
                            </motion.button>
                          ))}
                        </div>
                      </div>
                    )}
                    {turns.flatMap((turn) => {
                      /* 必须是 .thread 的直接子节点，才能命中 chat-view 的 .thread > .msg 列宽规则 */
                      const nodes = [
                        <div className="msg user" key={`${turn.id}-q`}>
                          <div className="bub">{turn.question}</div>
                        </div>,
                      ]
                      if (turn.thinking) {
                        nodes.push(
                          <div className="msg bot" key={`${turn.id}-think`}>
                            <div className="thinking-block think-trace" style={{ paddingTop: 8 }}>
                              <div className="think-trace__head">
                                <LatticeLoader
                                  status="working"
                                  label=""
                                  pattern="sweep"
                                  grid={3}
                                  cellSize={4}
                                  gap={2}
                                  fontSize={12}
                                  showTimer
                                />
                                <span className="think-trace__label">{t.kb.thinkingLabel}</span>
                              </div>
                              <ol className="think-trace__steps">
                                {(USE_MOCK ? t.kb.thinkingStepsMock : [t.kb.thinkingStepReal]).map((step, i) => (
                                  <li key={`${i}-${step}`}>{step}</li>
                                ))}
                              </ol>
                            </div>
                          </div>,
                        )
                      }
                      if (turn.answer) {
                        nodes.push(
                          <div className="msg bot" key={`${turn.id}-a`}>
                            <div>
                              <div className="bub">
                                <Markdown text={turn.answer} theme={theme} />
                              </div>
                              {turn.cites.length > 0 && (
                                <div className="citation">
                                  {turn.cites.map((c, i) => {
                                    const doc = matchDoc(c.documentName, c.documentId)
                                    return (
                                      <button
                                        key={i}
                                        type="button"
                                        className="cite-chip"
                                        data-clickable
                                        title={doc ? t.kb.previewAria(doc.name) : t.kb.openInDocs}
                                        onClick={() => {
                                          if (doc) void openPreview(doc)
                                          else setTab('docs')
                                        }}
                                      >
                                        <FileText size={12} />
                                        <span>{c.documentName ?? t.kb.unknownDoc}</span>
                                        {typeof c.score === 'number' && <span className="badge">{(c.score * 100).toFixed(0)}%</span>}
                                      </button>
                                    )
                                  })}
                                </div>
                              )}
                              <span className="msg-state">
                                <StatusMark status={turn.failed ? 'failed' : 'done'} size={13} />
                                {turn.failed ? t.kb.genFailed : (turn.cites.length ? t.kb.doneWithCites(turn.cites.length) : t.kb.done)}
                              </span>
                              <div className="msg-ops">
                                <button
                                  className="msg-op"
                                  type="button"
                                  title={t.kb.copyAnswer}
                                  aria-label={t.kb.copyAnswer}
                                  onClick={() => void copyAnswer(turn.answer!)}
                                >
                                  <Copy size={15} aria-hidden="true" />
                                </button>
                                <button
                                  className="msg-op"
                                  type="button"
                                  title={t.kb.regenerate}
                                  aria-label={t.kb.regenerate}
                                  disabled={thinking}
                                  onClick={() => regenerate(turn)}
                                >
                                  <RefreshCw size={15} aria-hidden="true" />
                                </button>
                              </div>
                            </div>
                          </div>,
                        )
                      } else if (!turn.thinking) {
                        /* 主动停止 / 生成失败且没有正文：只给状态与"再来一次"，不留空白气泡 */
                        nodes.push(
                          <div className="msg bot" key={`${turn.id}-stop`}>
                            <div>
                              <span className="msg-state">
                                <StatusMark status="failed" size={13} />
                                {turn.stopped ? t.kb.stopped : t.kb.genFailed}
                              </span>
                              <div className="msg-ops" style={{ opacity: 1 }}>
                                <button
                                  className="msg-op"
                                  type="button"
                                  title={t.kb.regenerate}
                                  aria-label={t.kb.regenerate}
                                  disabled={thinking}
                                  onClick={() => regenerate(turn)}
                                >
                                  <RefreshCw size={15} aria-hidden="true" />
                                </button>
                              </div>
                            </div>
                          </div>,
                        )
                      }
                      return nodes
                    })}
                  </div>
                </div>

                <div className="composer-wrap">
                  <PromptBar
                    placeholder={t.kb.qaPlaceholder}
                    sources={promptSources}
                    commands={promptKbCommands(locale)}
                    efforts={promptEfforts(locale)}
                    busy={thinking}
                    onStop={stopAsk}
                    maxRows={6}
                    onSend={(text, detail) => { void ask(text, detail) }}
                    background="var(--surface)"
                    color="var(--text)"
                    menuBackground="var(--surface-muted)"
                    width={9999}
                    radius={26}
                  />
                  <p className="composer-hint">
                    {t.kb.qaHint}<code>/</code>
                  </p>
                </div>
              </div>
            </motion.section>
          )}

          {tab === 'adv' && (
            <motion.section
              key="adv"
              className="kb-adv"
              aria-label={t.kb.advAria}
              initial={reduce ? false : { opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              exit={reduce ? undefined : { opacity: 0, y: -6 }}
              transition={soft}
            >
              <aside className="kb-adv__config">
                <div className="kb-adv__config-body">
                  <div className="kb-adv__head">
                    <h2 className="col-title" style={{ marginTop: 0 }}><Loc>{t.kb.configTitle}</Loc></h2>
                    {/* 未生效 / 已生效：后端不持久化检索参数，"生效"= 最近一次检索实际用的取值 */}
                    <motion.span
                      className="kb-adv__state"
                      data-dirty={usageDirty ? '' : undefined}
                      initial={false}
                      animate={{ opacity: 1 }}
                      transition={soft}
                    >
                      <Loc>{usageDirty ? t.kb.dirtyBadge : t.kb.savedBadge}</Loc>
                    </motion.span>
                  </div>
                  <p className="hint kb-adv__lead">{t.kb.modeGroupHint}</p>

                  {/* 分组一：检索模式 */}
                  <section className="kb-adv__group">
                    <span className="kb-adv__label" id="kb-adv-mode-label">{t.kb.modeLabel}</span>
                    <RubberSegment
                      className="kb-adv__modes"
                      size="sm"
                      stretch={92}
                      items={modeItems}
                      value={usage.mode}
                      onChange={(v) => setUsage({ ...usage, mode: v })}
                      trackColor="color-mix(in oklab, var(--text) 6%, var(--surface-muted))"
                      thumbColor="var(--surface)"
                      textColor="var(--text-3)"
                      activeTextColor="var(--text)"
                      aria-label={t.kb.modeLabel}
                    />
                    <p className="hint">{t.kb.modeHint}</p>
                  </section>

                  {/* 分组二：召回数量 */}
                  <section className="kb-adv__group">
                    <div className="kb-adv__range-head">
                      <label htmlFor="kb-topk">{t.kb.topKLabel}</label>
                      <output className="kb-adv__range-val" htmlFor="kb-topk">{usage.topK}</output>
                    </div>
                    <input
                      id="kb-topk"
                      className="kb-range"
                      type="range"
                      min={TOPK_MIN}
                      max={TOPK_MAX}
                      step={1}
                      value={usage.topK}
                      aria-valuetext={`${t.kb.topKRange} ${usage.topK}`}
                      aria-valuemin={TOPK_MIN}
                      aria-valuemax={TOPK_MAX}
                      style={{ '--kb-fill': `${((usage.topK - TOPK_MIN) / (TOPK_MAX - TOPK_MIN)) * 100}%` } as CSSProperties}
                      onChange={(e) => setUsage({ ...usage, topK: Number(e.target.value) })}
                    />
                    <div className="kb-adv__scale" aria-hidden>
                      <span>{TOPK_MIN}</span>
                      <span>{TOPK_MAX}</span>
                    </div>
                    <p className="hint">{t.kb.topKHint}</p>
                  </section>

                  <div className="kb-adv__reset">
                    <button
                      type="button"
                      className="btn btn-ghost btn-sm"
                      onClick={resetUsage}
                      disabled={!usageDirty}
                    >
                      <Loc>{t.kb.resetBtn}</Loc>
                    </button>
                  </div>
                </div>
                {!USE_MOCK && (
                  <div className="panel kb-adv__status">
                    <h3 className="col-title">{t.kb.indexStatus}</h3>
                    <p className="hint" style={{ margin: '0 0 10px' }}>
                      {chunks.length
                        ? t.kb.indexChunks(chunks.length, chunks.filter((c) => c.vectorStatus === 'READY').length, chunks.filter((c) => c.esStatus === 'READY').length)
                        : t.kb.indexNone}
                      {chunks.some((c) => c.vectorStatus === 'PENDING' || c.esStatus === 'PENDING') ? t.kb.indexPending : ''}
                    </p>
                    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
                      <button className="btn btn-ghost btn-sm" type="button" disabled={indexing} onClick={() => void runIndex(false)}>{t.kb.buildIndex}</button>
                      <button className="btn btn-ghost btn-sm" type="button" disabled={indexing} onClick={() => void runIndex(true)}>{t.kb.rebuildIndex}</button>
                    </div>
                  </div>
                )}
              </aside>

              <div className="kb-adv__main">
                <form className="kb-adv__search" onSubmit={(e) => { e.preventDefault(); void runSearch() }}>
                  <div style={{ position: 'relative', flex: 1 }}>
                    <Search size={15} style={{ position: 'absolute', left: 12, top: '50%', transform: 'translateY(-50%)', color: 'var(--text-3)' }} />
                    <input
                      aria-label={t.kb.searchAria}
                      className="input"
                      value={searchQ}
                      onChange={(e) => setSearchQ(e.target.value)}
                      placeholder={t.kb.searchPlaceholder}
                    />
                  </div>
                  <button className="btn btn-primary" type="submit" disabled={!searchQ.trim() || searching}>
                    {searching ? t.kb.searching : t.kb.searchBtn}
                  </button>
                </form>
                <div className="kb-adv__results">
                  {searched && hits.length > 0 && (
                    <p className="kb-adv__count">{t.kb.resultsCount(hits.length)}</p>
                  )}
                  <AnimatePresence mode="popLayout">
                    {hits.map((h, i) => {
                      const doc = matchDoc(h.documentName, h.documentId)
                      return (
                        <motion.article
                          className="kb-adv__hit"
                          key={h.chunkId ?? h.id ?? i}
                          role={doc ? 'button' : undefined}
                          tabIndex={doc ? 0 : undefined}
                          title={doc ? t.kb.previewAria(doc.name) : undefined}
                          onClick={doc ? () => void openPreview(doc) : undefined}
                          onKeyDown={doc ? (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); void openPreview(doc) } } : undefined}
                          initial={reduce ? false : { opacity: 0, y: 10 }}
                          animate={{ opacity: 1, y: 0 }}
                          exit={reduce ? undefined : { opacity: 0, scale: 0.98 }}
                          transition={{ ...soft, delay: reduce ? 0 : Math.min(i * 0.04, 0.24) }}
                        >
                          <div className="kb-adv__hit-meta">
                            <span className="title">{h.documentName ?? h.headingPath ?? t.kb.searchBtn}</span>
                            {h.headingPath && h.documentName && <span className="badge">{h.headingPath}</span>}
                            {typeof h.score === 'number' && (
                              <span className="chip" title={t.kb.score}>{(h.score * 100).toFixed(0)}%</span>
                            )}
                          </div>
                          <p>{h.content}</p>
                        </motion.article>
                      )
                    })}
                  </AnimatePresence>
                  {!searching && searched && searchQ.trim() && hits.length === 0 && (
                    <div className="kb-adv__onboard">
                      <StateEmpty icon={<Search size={20} />} title={t.kb.searchBtn} desc={t.kb.noHit} />
                    </div>
                  )}
                  {!searched && (
                    <div className="kb-adv__onboard">
                      <StateEmpty icon={<Search size={22} />} title={t.kb.emptySearchTitle} desc={t.kb.emptySearchDesc} />
                    </div>
                  )}
                </div>
              </div>
            </motion.section>
          )}

          {tab === 'links' && (
            <motion.section
              key="links"
              className="kb-links"
              aria-label={t.kb.linksAria}
              initial={reduce ? false : { opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              exit={reduce ? undefined : { opacity: 0, y: -6 }}
              transition={soft}
            >
              <div className="kb-links__meta">
                <label className="kb-links__picker">
                  <span className="sr-only">{t.kb.pickDoc}</span>
                  <select
                    className="input"
                    aria-label={t.kb.pickDocAria}
                    value={activeDocId ?? ''}
                    disabled={!docs.length}
                    onChange={(e) => setContextDocId(e.target.value || null)}
                  >
                    {!docs.length && <option value="">{t.kb.metaEmpty}</option>}
                    {docs.map((d) => (
                      <option key={d.id} value={d.id}>{d.name}</option>
                    ))}
                  </select>
                </label>
                <span className="kb-links__stats">
                  {activeDoc
                    ? `${t.kb.graphStatBack} ${linkData?.backlinks.length ?? (USE_MOCK ? 1 : 0)} · ${t.kb.graphStatOut} ${linkData?.outlinks.length ?? (USE_MOCK ? 2 : 0)} · ${t.kb.graphStatNodes} ${Math.max(graphData.nodes.length - 1, 0)}`
                    : t.kb.statsEmpty}
                </span>
              </div>
              <div className="kb-links__body">
                <div className="kb-links__edges">
                  {uniqueLinks.map((r, i) => {
                    const doc = matchDoc(r.title, r.docId)
                    return (
                      <motion.div
                        className="list-row"
                        key={i}
                        role={doc ? 'button' : undefined}
                        tabIndex={doc ? 0 : undefined}
                        title={doc ? t.kb.previewAria(doc.name) : undefined}
                        onClick={doc ? () => void openPreview(doc) : undefined}
                        onKeyDown={doc ? (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); void openPreview(doc) } } : undefined}
                        initial={reduce ? false : { opacity: 0, x: -6 }}
                        animate={{ opacity: 1, x: 0 }}
                        transition={{ ...soft, delay: reduce ? 0 : i * 0.03 }}
                        whileHover={reduce || !doc ? undefined : { x: 2 }}
                      >
                        <span className="ic"><FileText size={15} /></span>
                        <div style={{ minWidth: 0, flex: 1 }}>
                          <div className="nm">{r.title}</div>
                          <div className="mt">{r.why}</div>
                        </div>
                        <span className="badge" data-tone={r.tone}>{r.tag}</span>
                      </motion.div>
                    )
                  })}
                  {docs.length > 0 && !uniqueLinks.length && (
                    <p className="hint" style={{ padding: 10 }}>
                      {t.kb.noEdgesHint}<code>[[wiki]]</code>
                    </p>
                  )}
                  {!docs.length && (
                    <p className="hint" style={{ padding: 10 }}>{t.kb.noDocsHint}</p>
                  )}
                </div>
                <div className="kb-links__graph">
                  {activeDoc ? (
                    <KbLinkGraph
                      key={activeDoc.id}
                      centerLabel={activeDoc.name}
                      nodes={graphData.nodes}
                      edges={graphData.edges}
                      onSelect={(node) => {
                        if (node.docId && node.docId !== activeDoc.id) {
                          setContextDocId(node.docId)
                        }
                        const doc = matchDoc(node.label, node.docId)
                        if (doc) void openPreview(doc)
                      }}
                    />
                  ) : (
                    <div className="kb-graph kb-graph--empty">
                      <p className="kb-graph__empty-title">{t.kb.emptyGraphTitle}</p>
                      <p className="kb-graph__empty-desc">{t.kb.emptyGraphDesc}</p>
                    </div>
                  )}
                </div>
              </div>
            </motion.section>
          )}
        </div>
      </div>

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
        open={metaOpen}
        title={t.kb.metaModalTitle}
        onClose={() => { if (!savingMeta) setMetaOpen(false) }}
        shouldBlockClose={() => savingMeta}
        width={500}
        footer={(
          <>
            <button className="btn btn-ghost" type="button" onClick={() => setMetaOpen(false)} disabled={savingMeta}>{t.common.cancel}</button>
            <motion.button
              className="btn btn-primary"
              type="submit"
              form="kb-meta-form"
              disabled={savingMeta}
              whileHover={reduce || savingMeta ? undefined : { y: -1 }}
              whileTap={reduce || savingMeta ? undefined : { scale: 0.97 }}
            >
              {savingMeta ? t.common.saving : t.common.save}
            </motion.button>
          </>
        )}
      >
        <form
          id="kb-meta-form"
          className="kb-meta-form"
          onSubmit={(e) => { e.preventDefault(); void saveMeta() }}
          noValidate
        >
          <div className="field">
            <label htmlFor="kb-meta-name">{t.kb.nameLabel}</label>
            <input
              id="kb-meta-name"
              className="input"
              autoComplete="off"
              maxLength={128}
              value={editName}
              onChange={(e) => { setEditName(e.target.value); setMetaError('') }}
              aria-invalid={Boolean(metaError)}
              autoFocus
            />
          </div>
          <div className="field">
            <label htmlFor="kb-meta-desc">{t.kb.descLabel} <span className="hint" style={{ fontWeight: 400 }}>{t.common.optional} / 512</span></label>
            <textarea
              id="kb-meta-desc"
              className="input kb-meta-form__desc"
              rows={5}
              maxLength={512}
              placeholder={t.kb.descPlaceholder}
              value={editDesc}
              onChange={(e) => { setEditDesc(e.target.value); setMetaError('') }}
            />
            <div className="kb-meta-form__foot">
              <p className="hint" style={{ margin: 0 }}>{t.kb.descShownHint}</p>
              <p className="hint" style={{ margin: 0 }}>{editDesc.trim().length} / 512</p>
            </div>
          </div>
          {metaError && <p className="form-error active" role="alert">{metaError}</p>}
        </form>
      </Modal>

      <Modal
        open={chunkDoc !== null}
        title="分块管理"
        width={1000}
        onClose={() => setChunkDoc(null)}
      >
        {chunkDoc && (
          <ChunkManager kbId={kbId} doc={chunkDoc} kbName={kbTitle} onBack={() => setChunkDoc(null)} />
        )}
      </Modal>

      <Modal
        open={importOpen}
        title={t.kb.importTitle}
        onClose={() => { if (!uploading) { setImportOpen(false); setUploadError(''); setPicked(null); setDragOver(false) } }}
        shouldBlockClose={() => uploading}
        width={560}
        footer={(
          <>
            <button className="btn btn-ghost" type="button" onClick={() => { setImportOpen(false); setPicked(null) }} disabled={uploading}>{t.common.cancel}</button>
            <motion.button
              className="btn btn-primary"
              type="button"
              onClick={() => { void uploadDocument() }}
              disabled={uploading || !picked}
              whileHover={reduce || uploading || !picked ? undefined : { y: -1 }}
              whileTap={reduce || uploading || !picked ? undefined : { scale: 0.97 }}
            >
              {uploading ? t.kb.importing : t.kb.importSubmit}
            </motion.button>
          </>
        )}
      >
        <div className="kb-import">
          <input
            ref={fileInputRef}
            id="kb-import-file"
            className="sr-only"
            type="file"
            accept={ACCEPT_MIME}
            disabled={uploading}
            onChange={(event) => {
              pickFile(event.target.files?.[0])
              event.target.value = ''
            }}
          />

          <motion.div
            className="kb-drop"
            data-over={dragOver ? '' : undefined}
            data-has={picked ? '' : undefined}
            data-busy={uploading ? '' : undefined}
            role="button"
            tabIndex={uploading ? -1 : 0}
            onClick={() => { if (!uploading) fileInputRef.current?.click() }}
            onKeyDown={(e) => {
              if (uploading) return
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault()
                fileInputRef.current?.click()
              }
            }}
            onDragEnter={(e) => { e.preventDefault(); if (!uploading) setDragOver(true) }}
            onDragOver={(e) => { e.preventDefault(); if (!uploading) setDragOver(true) }}
            onDragLeave={(e) => { e.preventDefault(); setDragOver(false) }}
            onDrop={onDropFile}
            whileHover={reduce || uploading ? undefined : { scale: 1.01 }}
            whileTap={reduce || uploading ? undefined : { scale: 0.985 }}
            animate={dragOver && !reduce ? { scale: 1.015 } : { scale: 1 }}
            transition={soft}
            aria-label={picked ? t.kb.dropReselect : t.kb.dropAria}
            aria-disabled={uploading || undefined}
          >
            {/* 同上：mode="wait" 在 motion 14.0.0 下失效，picked/空态改为 key 直接切换 */}
            {picked ? (
                <motion.div
                  key="picked"
                  className="kb-drop__picked"
                  initial={reduce ? false : { opacity: 0, y: 10, scale: 0.98 }}
                  animate={{ opacity: 1, y: 0, scale: 1 }}
                  exit={reduce ? undefined : { opacity: 0, y: -8 }}
                  transition={soft}
                >
                  <motion.span
                    className="kb-drop__icon"
                    aria-hidden
                    layout
                    animate={uploading && !reduce ? { rotate: [0, -6, 6, 0] } : { rotate: 0 }}
                    transition={uploading && !reduce ? { repeat: Infinity, duration: 1.4, ease: 'easeInOut' } : soft}
                  >
                    <PickedIcon size={22} />
                  </motion.span>
                  <div className="kb-drop__meta">
                    <div className="kb-drop__name">{picked.name}</div>
                    <div className="kb-drop__size">{formatBytes(picked.size)}</div>
                    {uploading && (
                      <div className="kb-drop__progress" role="progressbar" aria-valuetext={t.kb.importProgressAria}>
                        <motion.span
                          className="kb-drop__progress-bar"
                          initial={reduce ? false : { scaleX: 0 }}
                          animate={{ scaleX: 1 }}
                          transition={reduce ? { duration: 0 } : { duration: 1.6, ease: [0.22, 1, 0.36, 1], repeat: Infinity }}
                        />
                      </div>
                    )}
                  </div>
                  {!uploading && (
                    <button
                      type="button"
                      className="btn btn-ghost btn-sm"
                      onClick={(e) => { e.stopPropagation(); setPicked(null); setUploadError('') }}
                    >
                      {t.kb.swapFile}
                    </button>
                  )}
                </motion.div>
              ) : (
                <motion.div
                  key="empty"
                  className="kb-drop__empty"
                  initial={reduce ? false : { opacity: 0, y: 10 }}
                  animate={{ opacity: 1, y: 0 }}
                  exit={reduce ? undefined : { opacity: 0, y: -8 }}
                  transition={soft}
                >
                  <motion.span
                    className="kb-drop__icon"
                    aria-hidden
                    animate={dragOver && !reduce ? { y: [0, -4, 0] } : { y: 0 }}
                    transition={dragOver && !reduce ? { repeat: Infinity, duration: 0.9 } : soft}
                  >
                    <Upload size={22} />
                  </motion.span>
                  <p className="kb-drop__title">
                    {dragOver ? t.kb.dropRelease : t.kb.dropChoose}
                  </p>
                  <p className="kb-drop__hint">{t.kb.dropHint}</p>
                </motion.div>
              )}
          </motion.div>

          <div className="kb-import__types" aria-label={t.kb.typesAria}>
            {([
              { t: 'MD', tip: t.kb.typeMd },
              { t: 'TXT', tip: t.kb.typeTxt },
              { t: 'PDF', tip: t.kb.typePdf },
              { t: 'DOCX', tip: t.kb.typeDocx },
            ] as const).map((item, i) => (
              <motion.span
                key={item.t}
                className="kb-import__type"
                title={item.tip}
                initial={reduce ? false : { opacity: 0, y: 6 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ ...soft, delay: reduce ? 0 : 0.04 * i }}
              >
                {item.t}
              </motion.span>
            ))}
          </div>

          <ol className="kb-import__steps" aria-label={t.kb.stepsAria}>
            {[
              { n: 1, t: t.kb.step1 },
              { n: 2, t: t.kb.step2 },
              { n: 3, t: t.kb.step3 },
            ].map((s, i) => (
              <motion.li
                key={s.n}
                data-on={uploading && i === 0 ? '' : picked && !uploading && i === 0 ? '' : undefined}
                initial={reduce ? false : { opacity: 0, x: -8 }}
                animate={{ opacity: 1, x: 0 }}
                transition={{ ...soft, delay: reduce ? 0 : 0.06 + i * 0.05 }}
              >
                <span className="kb-import__step-n">{s.n}</span>
                <span>{s.t}</span>
              </motion.li>
            ))}
          </ol>

          {uploadError && <p className="form-error active" role="alert">{uploadError}</p>}
        </div>
      </Modal>

      <Modal
        open={confirmDelete !== null}
        title={t.kb.deleteDocTitle}
        onClose={() => setConfirmDelete(null)}
        width={440}
        footer={(
          <>
            <button className="btn btn-ghost" type="button" onClick={() => setConfirmDelete(null)}>{t.common.cancel}</button>
            <button className="btn btn-danger" type="button" onClick={() => confirmDeleteDoc()}>
              <Trash2 size={15} /> {t.kb.deleteConfirm}
            </button>
          </>
        )}
      >
        <p style={{ margin: 0, fontSize: 13.5, lineHeight: 1.7, color: 'var(--text-2)' }}>
          {t.kb.deleteDocBody(confirmDelete?.name ?? '')}
        </p>
      </Modal>
    </div>
  )
}
