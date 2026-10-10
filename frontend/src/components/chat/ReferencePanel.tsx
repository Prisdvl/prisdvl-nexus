import { ExternalLink, FileText, Layers, X } from 'lucide-react'
import Markdown from '@/components/Markdown'
import { mockDocContent } from '@/components/DocPreview'
import { DOCS } from '@/data'
import type { ThemeMode } from '@/lib/types'

interface Props {
  /** 引用指向的文档名 */
  docName: string
  theme: ThemeMode
  onClose: () => void
  /** 到知识库详情页看原文（面板里的显式跳转，不再"点引用就跳页"） */
  onOpenInKb: (docName: string) => void
}

/**
 * 对话右侧的「引用 / 知识库」面板。
 *
 * 交互取舍：点引用**不再直接跳页**（那样会丢掉对话上下文），
 * 而是右滑出一块面板就地看；确实要去知识库时，再点面板顶部的「在知识库中打开」。
 * 面板是聊天主区的一列（不是悬浮层），因此不涉及 overflow 裁剪问题。
 */
export default function ReferencePanel({ docName, theme, onClose, onOpenInKb }: Props) {
  /* 尽量匹配到真实文档元信息；匹配不到就用引用名兜底 */
  const meta = DOCS.find((d) => d.name === docName)
  const doc = meta ?? { id: -1, name: docName, type: 'MD', size: '—', updated: '—' }
  const related = DOCS.filter((d) => d.name !== docName).slice(0, 4)
  const chunks = ['摘要', '关键点', '代码示例']

  return (
    <aside className="ref-panel" aria-label="引用详情">
      <header className="ref-head">
        <span className="ref-ic"><FileText size={15} /></span>
        <div className="ref-title">
          <h3 title={doc.name}>{doc.name}</h3>
          <p>{doc.type} · {doc.size} · {doc.updated}</p>
        </div>
        <button className="btn btn-ghost btn-sm" type="button" onClick={() => onOpenInKb(doc.name)}>
          <ExternalLink size={13} /> 在知识库中打开
        </button>
        <button className="close" type="button" aria-label="关闭引用面板" onClick={onClose}><X size={16} /></button>
      </header>

      <div className="ref-body">
        {doc.type === 'PDF' || doc.type === 'DOCX' ? (
          <div className="ref-binary">
            <span className="ref-binary-ic"><FileText size={20} /></span>
            <p className="ref-binary-title">原型不内嵌 {doc.type} 预览</p>
            <p className="ref-binary-desc">
              真实实现里这里会嵌 PDF.js（或 Office 在线预览），并在下方列出命中的切片与页码。
            </p>
            <button className="btn btn-primary btn-sm" type="button" onClick={() => onOpenInKb(doc.name)}>
              <ExternalLink size={13} /> 去知识库看原文
            </button>
          </div>
        ) : (
          <Markdown text={mockDocContent(doc as never)} theme={theme} />
        )}

        <section className="ref-chunks">
          <h4><Layers size={13} /> 命中的切片（{chunks.length}）</h4>
          {chunks.map((h) => (
            <div className="ref-chunk" key={h}>
              <span className="ref-chunk-h">## {h}</span>
              <span className="ref-chunk-d">按 headingPath 定位到原文这一节</span>
            </div>
          ))}
        </section>

        <section className="ref-related">
          <h4>相关文档</h4>
          <div className="ref-related-grid">
            {related.map((d) => (
              <button className="ref-card" type="button" key={d.id} onClick={() => onOpenInKb(d.name)}>
                <span className="ref-card-ic"><FileText size={14} /></span>
                <span className="ref-card-name" title={d.name}>{d.name}</span>
                <span className="ref-card-meta">{d.type} · {d.size}</span>
              </button>
            ))}
          </div>
        </section>
      </div>
    </aside>
  )
}
