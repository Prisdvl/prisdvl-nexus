import { useEffect, useRef, type ReactNode } from 'react'
import { Download, ExternalLink, FileSpreadsheet, FileText, Layers, X } from 'lucide-react'
import Markdown from '@/components/Markdown'
import { toast } from '@/lib/toast'
import type { ThemeMode } from '@/lib/types'
import type { DocItem } from '@/data'

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
}

/** 文档预览抽屉：右侧滑出，Esc / 点遮罩关闭，焦点关在抽屉内 */
export default function DocPreview({ doc, theme, onClose }: Props) {
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
          <button className="btn btn-ghost btn-sm" type="button" onClick={() => toast.task('开始下载原文', doc.name)} title="下载原文">
            <Download size={14} />
          </button>
          <button className="close" type="button" aria-label="关闭" onClick={onClose}><X size={17} /></button>
        </header>

        <div className="drawer-body">
          {isBinary ? (
            <div className="doc-binary">
              <span className="doc-binary-ic"><Icon size={22} /></span>
              <p className="doc-binary-title">原型不内嵌 {doc.type} 预览</p>
              <p className="doc-binary-desc">
                真实实现里这里会嵌 PDF.js 阅读器（或 Office 在线预览）；DOCX 会先转 HTML 再渲染。
                原型阶段先用占位说明，保证「点击文档有反馈」这条交互是通的。
              </p>
              <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', justifyContent: 'center' }}>
                <button className="btn btn-primary btn-sm" type="button" onClick={() => toast.task('开始下载原文', doc.name)}>
                  <Download size={13} /> 下载原文
                </button>
                <button className="btn btn-ghost btn-sm" type="button" onClick={() => toast.info('已在新标签打开（原型示意）', doc.name)}>
                  <ExternalLink size={13} /> 新标签打开
                </button>
              </div>
            </div>
          ) : (
            <>
              <Markdown text={mockDocContent(doc)} theme={theme} />
              <section className="doc-chunks">
                <h4><Layers size={13} /> 解析出的切片（3）</h4>
                {[
                  ['## 摘要', '对应 headingPath：摘要'],
                  ['## 关键点', '对应 headingPath：关键点'],
                  ['### 代码示例', '对应 headingPath：代码示例'],
                ].map(([h, d]) => (
                  <div className="doc-chunk" key={h}>
                    <span className="doc-chunk-h">{h}</span>
                    <span className="doc-chunk-d">{d}</span>
                  </div>
                ))}
              </section>
            </>
          )}
        </div>
      </aside>
    </>
  )
}