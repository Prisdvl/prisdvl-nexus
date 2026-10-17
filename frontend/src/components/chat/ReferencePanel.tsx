import { useEffect, useState } from 'react'
import { ExternalLink, FileText, X } from 'lucide-react'
import Markdown from '@/components/Markdown'
import type { ChatCitationRef } from '@/data'
import { api, errMessage, type DocumentDetail } from '@/lib/api'
import { pagesCopy } from '@/lib/pagesCopy'
import { useLocale } from '@/lib/locale'
import Loc from '@/components/Loc'
import type { ThemeMode } from '@/lib/types'

interface Props {
  citation: ChatCitationRef
  theme: ThemeMode
  onClose: () => void
  onOpenInKb: (citation: ChatCitationRef) => void
}

/** Displays only the actual source excerpt/details returned by the backend; never synthesizes document contents. */
export default function ReferencePanel({ citation, theme, onClose, onOpenInKb }: Props) {
  const { locale } = useLocale()
  const c = pagesCopy(locale).chat
  const [document, setDocument] = useState<DocumentDetail | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const documentName = citation.documentTitle ?? c.refSourceFallback
  const canOpenSource = Boolean(citation.knowledgeBaseId && citation.documentId)

  useEffect(() => {
    if (!canOpenSource) {
      setDocument(null)
      setError(c.refNoIdError)
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

  const fileType = document?.fileExt?.toUpperCase() || c.docFallback

  return (
    <aside className="ref-panel" aria-label={c.refAria}>
      <header className="ref-head">
        <span className="ref-ic"><FileText size={15} aria-hidden="true" /></span>
        <div className="ref-title">
          <h3 title={documentName}>{document?.title || document?.fileName || documentName}</h3>
          <p>{fileType}{citation.headingPath ? ` · ${citation.headingPath}` : ''}</p>
        </div>
        <button className="btn btn-ghost btn-sm" type="button" disabled={!canOpenSource} onClick={() => onOpenInKb(citation)}>
          <ExternalLink size={13} aria-hidden="true" /> <Loc>{c.refOpenInKb}</Loc>
        </button>
        <button className="close" type="button" aria-label={c.refCloseAria} onClick={onClose}><X size={16} /></button>
      </header>

      <div className="ref-body" aria-busy={loading}>
        {loading && <p className="hint" role="status">{c.refLoading}</p>}
        {error && <p className="form-error active" role="alert">{error}</p>}
        {citation.snippet && (
          <section className="ref-source">
            <h4><Loc>{c.refHitTitle}</Loc>{citation.score != null ? c.refScoreSuffix(citation.score) : ''}</h4>
            <Markdown text={citation.snippet} theme={theme} />
          </section>
        )}
        {document?.content && (
          <section className="ref-source ref-document">
            <h4><Loc>{c.refDocTitle}</Loc></h4>
            <Markdown text={document.content} theme={theme} />
          </section>
        )}
        {!loading && !error && !citation.snippet && !document?.content && (
          <p className="hint">{c.refNoSnippet}</p>
        )}
        {canOpenSource && !document && !loading && error && (
          <button className="btn btn-ghost btn-sm" type="button" onClick={() => onOpenInKb(citation)}>
            <ExternalLink size={13} aria-hidden="true" /> <Loc>{c.refGoKb}</Loc>
          </button>
        )}
      </div>
    </aside>
  )
}
