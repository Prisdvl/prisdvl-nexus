/**
 * 后端 DTO → 界面类型 的映射层（真实联调版）。
 *
 * 存在的意义：页面组件（KBS/DOCS/AGENTS 的渲染逻辑）保持不动，只把"数据来源"
 * 从 `@/data` 的种子常量换成这里从真实接口拉取并映射后的数据。
 * 这样任何契约变化只影响本文件，不会散落到各个页面。
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import { api, USE_MOCK, errMessage, type BackendAgent, type KbDocument, type KnowledgeBase } from '@/lib/api'
import type { AgentItem, DocItem, KbItem } from '@/data'

/* ---------------- 通用异步状态 ---------------- */

export interface AsyncState<T> {
  data: T | null
  loading: boolean
  error: string | null
  reload: () => void
}

/** 极简数据加载 hook：loading / error / reload，且在 mock 模式下不请求后端。 */
export function useLoad<T>(fn: () => Promise<T>, deps: unknown[] = []): AsyncState<T> {
  const [data, setData] = useState<T | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [tick, setTick] = useState(0)
  const alive = useRef(true)
  const run = useRef(fn)
  run.current = fn

  useEffect(() => {
    alive.current = true
    if (USE_MOCK) {
      setLoading(false)
      return
    }
    setLoading(true)
    setError(null)
    run.current()
      .then((d) => { if (alive.current) setData(d) })
      .catch((e) => { if (alive.current) setError(errMessage(e)) })
      .finally(() => { if (alive.current) setLoading(false) })
    return () => { alive.current = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tick, ...deps])

  const reload = useCallback(() => setTick((t) => t + 1), [])
  return { data, loading, error, reload }
}

/* ---------------- 格式化 ---------------- */

/** ISO 时间 → 「x 分钟前 / 昨天 / N 天前」。 */
export function relTime(iso: string | null | undefined): string {
  if (!iso) return ''
  const t = Date.parse(iso)
  if (Number.isNaN(t)) return ''
  const diff = Date.now() - t
  const min = Math.floor(diff / 60000)
  if (min < 1) return '刚刚'
  if (min < 60) return `${min} 分钟前`
  const h = Math.floor(min / 60)
  if (h < 24) return `${h} 小时前`
  const d = Math.floor(h / 24)
  if (d === 1) return '昨天'
  if (d < 30) return `${d} 天前`
  return `${Math.floor(d / 30)} 个月前`
}

const TONES = ['terracotta', 'turquoise', 'lime'] as const
/** 用 id 稳定地取一个强调色，避免每次渲染换色。 */
function toneOf(id: string): string {
  let h = 0
  for (const ch of id) h = (h * 31 + ch.charCodeAt(0)) >>> 0
  return TONES[h % TONES.length]
}

function fmtSize(kb: number): string {
  if (!kb) return '—'
  if (kb < 1024) return `${kb < 10 ? kb.toFixed(1) : Math.round(kb)} KB`
  return `${(kb / 1024).toFixed(1)} MB`
}

/* ---------------- 各域映射 ---------------- */

export const toKbItem = (kb: KnowledgeBase): KbItem => ({
  id: kb.id,
  name: kb.name,
  desc: kb.description ?? '',
  docs: kb.docCount ?? 0,
  updated: relTime(kb.updatedAt ?? kb.createdAt),
  tone: toneOf(kb.id),
})

/** 后端文档状态 → 界面三态。 */
export function docStatus(s: string): DocItem['status'] {
  const up = (s ?? '').toUpperCase()
  if (up === 'READY') return 'done'
  if (up === 'FAILED') return 'failed'
  return 'indexing'
}

const EXT_LABEL: Record<string, string> = { md: 'Markdown', txt: 'Text', pdf: 'PDF', docx: 'DOCX', zip: 'Vault' }

export const toDocItem = (d: KbDocument): DocItem => ({
  id: d.id,
  name: d.fileName,
  type: EXT_LABEL[(d.fileExt ?? '').toLowerCase()] ?? (d.fileExt || 'File').toUpperCase(),
  size: fmtSize(d.fileSizeKb),
  updated: relTime(d.updatedAt),
  status: docStatus(d.status),
})

export const toAgentItem = (a: BackendAgent): AgentItem => ({
  id: a.id,
  name: a.name,
  desc: a.description ?? '',
  model: a.modelRole ?? 'auto',
  tools: a.toolCodes ?? [],
  kb: a.kbIds?.length ? `${a.kbIds.length} 个知识库` : '无',
})

/* ---------------- 拉取（含排序） ---------------- */

export async function fetchKbs(): Promise<KbItem[]> {
  const page = await api.knowledge.list(1, 100)
  return page.list.map(toKbItem)
}

export async function fetchDocs(kbId: string): Promise<DocItem[]> {
  const page = await api.documents.list(kbId, 1, 200)
  return page.list.map(toDocItem)
}

export async function fetchAgents(): Promise<AgentItem[]> {
  const page = await api.agents.list(1, 100)
  return page.list.map(toAgentItem)
}