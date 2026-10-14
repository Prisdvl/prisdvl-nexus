import { useEffect, useRef, type ReactNode } from 'react'
import { Download, ExternalLink, FileSpreadsheet, FileText, Layers, X } from 'lucide-react'
import Markdown from '@/components/Markdown'
import { toast } from '@/lib/toast'
import type { ThemeMode } from '@/lib/types'
import type { DocItem } from '@/data'
import type { ChunkView } from '@/lib/api'

const FOCUSABLE = 'a[href],button:not([disabled]),input:not([disabled]),select,textarea,[tabindex]:not([tabindex="-1"])'

/** 原型：按文档名生成演示正文（真实实现由后端返回原文/解析结果） */
export function mockDocContent(doc: DocItem): string {
  if (doc.type === 'PDF' || doc.type === 'DOCX') return ''
  return `# ${doc.name.replace(/\.[^.]+$/, '')}

## 摘要

这一节对应切片中的 **headingPath**，检索命中时会把它一并带进上下文，用来告诉模型"这段话在原文的什么位置"。

## 关键点

1. 非叶子节点只存键，单页容纳更多键，**树更矮**，磁盘 IO 更少；
2. 叶子节点按序互链，范围查询退化为一次顺序扫描；
3. 与 16KB 页大小配合，一次 IO 加载整页。

### 代码示例

\`\`\`java
public int search(int[] keys, int target) {
    int lo = 0, hi = keys.length - 1;
    while (lo <= hi) {
        int mid = lo + (hi - lo) / 2;
        if (keys[mid] == target) return mid;
        if (keys[mid] < target) lo = mid + 1;
        else hi = mid - 1;
    }
    return -1;
}
\`\`\`

### 检索流程

\`\`\`mermaid
flowchart LR
  Q[提问] --> R[混合检索]
  R --> V[向量召回]
  R --> B[BM25 召回]
  V --> F[RRF 融合]
  B --> F
  F --> C[取 topK 切片]
  C --> A[拼上下文回答]
\`\`\`

> 引用是可点的：点回对话页会定位到这份文档。
`
}

interface Props {
  doc: DocItem | null
  theme: ThemeMode
  onClose: () => void
  content?: string | null
  chunks?: ChunkView[]
  loading?: boolean
  error?: string | null
  onDownload?: () => void
}

/** 文档预览抽屉：右侧滑出，Esc / 点遮罩关闭，焦点关在抽屉内 */
export default function DocPreview({ doc, theme, onClose, content, chunks = [], loading = false, error = null, onDownload }: Props) {
  const panelRef = useRef<HTMLDivElement>(null)
  const lastActive = useRef<HTMLElement | null>(null)
  const open = doc !== null

  useEffect(() => {
    if (!open) return
    lastActive.current = document.activeElement as HTMLElement | null
    const panel = panelRef.current
    ;(panel?.querySelector<HTMLElement>(FOCUSABLE) ?? panel)?.focus({ preventScroll: true })

    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { e.stopPropagation(); onClose(); return }
      if (e.key !== 'Tab' || !panel) return
      const nodes = Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE)).filter((el) => el.offsetParent !== null)
      if (!nodes.length) return
      const first = nodes[0]
      const last = nodes[nodes.length - 1]
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus() }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus() }
    }
    document.addEventListener('keydown', onKey, true)
    const prev = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.removeEventListener('keydown', onKey, true)
      document.body.style.overflow = prev
      lastActive.current?.focus?.({ preventScroll: true })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  if (!doc) return null

  const isBinary = doc.type === 'PDF' || doc.type === 'DOCX'
  const Icon = doc.type === 'DOCX' ? FileSpreadsheet : FileText

  const meta: ReactNode = (
    <>
      <span>{doc.type}</span>
      <span>·</span>
      <span>{doc.size}</span>
      <span>·</span>
      <span>{doc.updated}</span>
    </>
  )

  return (
    <>
      <div className="drawer-scrim" onClick={onClose} aria-hidden="true" />
      <aside className="drawer" role="dialog" aria-modal="true" aria-label={`预览 ${doc.name}`} ref={panelRef} tabIndex={-1}>
        <header className="drawer-head">
          <span className="doc-preview-ic"><Icon size={16} /></span>
          <div style={{ minWidth: 0, flex: 1 }}>
            <h3 className="drawer-title">{doc.name}</h3>
            <p className="drawer-meta">{meta}</p>
          </div>
          <button className="btn btn-ghost btn-sm" type="button" onClick={onDownload} disabled={!onDownload} title={onDownload ? "下载原文" : "当前文件类型暂不支持下载"}>
            <Download size={14} />
          </button>
          <button className="close" type="button" aria-label="关闭" onClick={onClose}><X size={17} /></button>
        </header>

        <div className="drawer-body">
          {loading && <p className="hint" role="status">正在读取文档内容…</p>}
          {error && <p className="form-error active" role="alert">{error}</p>}
          {isBinary ? (
            <div className="doc-binary">
              <span className="doc-binary-ic"><Icon size={22} /></span>
              <p className="doc-binary-title">暂不支持内嵌 {doc.type} 预览</p>
              <p className="doc-binary-desc">当前 API 返回文档元信息与解析切片，不提供 PDF/DOCX 原始文件下载或内嵌预览。</p>
            </div>
          ) : content ? (
            <>
              <Markdown text={content} theme={theme} />
              <section className="doc-chunks">
                <h4><Layers size={13} /> 解析切片（{chunks.length}）</h4>
                {chunks.map((chunk) => (
                  <div className="doc-chunk" key={chunk.id}>
                    <span className="doc-chunk-h">{chunk.headingPath || '切片 ' + (chunk.chunkIndex + 1)}</span>
                    <span className="doc-chunk-d">{chunk.content}</span>
                  </div>
                ))}
                {chunks.length === 0 && !loading && <p className="hint">暂无可显示的切片。</p>}
              </section>
            </>
          ) : !loading && !error ? (
            <p className="hint">服务端没有返回可预览的原始文本。</p>
          ) : null}
        </div>
      </aside>
    </>
  )
}