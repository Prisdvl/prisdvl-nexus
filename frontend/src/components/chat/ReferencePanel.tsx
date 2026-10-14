import { useEffect, useState } from 'react'
import { ExternalLink, FileText, X } from 'lucide-react'
import Markdown from '@/components/Markdown'
import type { ChatCitationRef } from '@/data'
import { api, errMessage, type DocumentDetail } from '@/lib/api'
import type { ThemeMode } from '@/lib/types'

interface Props {
  citation: ChatCitationRef
  theme: ThemeMode
  onClose: () => void
  onOpenInKb: (citation: ChatCitationRef) => void
}

/** Displays only the actual source excerpt/details returned by the backend; never synthesizes document contents. */
export default function ReferencePanel({ citation, theme, onClose, onOpenInKb }: Props) {
  const [document, setDocument] = useState<DocumentDetail | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const documentName = citation.documentTitle ?? '引用来源'
  const canOpenSource = Boolean(citation.knowledgeBaseId && citation.documentId)

  useEffect(() => {
    if (!canOpenSource) {
      setDocument(null)
      setError('这条历史引用未包含知识库与文档 ID，无法读取原文。')
      return
    }
    let alive = true
    setLoading(true)
    setError('')
    api.documents.get(String(citation.knowledgeBaseId), String(citation.documentId))
      .then((result) => { if (alive) setDocument(result) })
      .catch((reason) => { if (alive) setError(errMessage(reason)) })
      .finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [citation.knowledgeBaseId, citation.documentId, canOpenSource])

  const fileType = document?.fileExt?.toUpperCase() || '文档'

  return (
    <aside className="ref-panel" aria-label="引用详情">
      <header className="ref-head">
        <span className="ref-ic"><FileText size={15} aria-hidden="true" /></span>
        <div className="ref-title">
          <h3 title={documentName}>{document?.title || document?.fileName || documentName}</h3>
          <p>{fileType}{citation.headingPath ? ` · ${citation.headingPath}` : ''}</p>
        </div>
        <button className="btn btn-ghost btn-sm" type="button" disabled={!canOpenSource} onClick={() => onOpenInKb(citation)}>
          <ExternalLink size={13} aria-hidden="true" /> 在知识库中打开
        </button>
        <button className="close" type="button" aria-label="关闭引用面板" onClick={onClose}><X size={16} /></button>
      </header>

      <div className="ref-body" aria-busy={loading}>
        {loading && <p className="hint" role="status">正在读取引用来源…</p>}
        {error && <p className="form-error active" role="alert">{error}</p>}
        {citation.snippet && (
          <section className="ref-source">
            <h4>命中片段{citation.score != null ? ` · 相似度 ${(citation.score * 100).toFixed(1)}%` : ''}</h4>
            <Markdown text={citation.snippet} theme={theme} />
          </section>
        )}
        {document?.content && (
          <section className="ref-source ref-document">
            <h4>文档内容</h4>
            <Markdown text={document.content} theme={theme} />
          </section>
        )}
        {!loading && !error && !citation.snippet && !document?.content && (
          <p className="hint">服务端未提供这条引用的正文片段。</p>
        )}
        {canOpenSource && !document && !loading && error && (
          <button className="btn btn-ghost btn-sm" type="button" onClick={() => onOpenInKb(citation)}>
            <ExternalLink size={13} aria-hidden="true" /> 前往对应知识库
          </button>
        )}
      </div>
    </aside>
  )
}
