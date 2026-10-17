import { AlertTriangle, BookOpen, FileText, Globe, Layers, MessagesSquare, Paperclip } from 'lucide-react'
import type { RunContext } from '@/data'
import { pagesCopy } from '@/lib/pagesCopy'
import { useLocale } from '@/lib/locale'
import Loc from '@/components/Loc'
import '@/styles/context.css'

interface Props {
  context: RunContext
  /** 点击"引用 N 篇"时打开右侧引用面板 */
  onOpenRefs?: () => void
}

/**
 * 上下文胶囊（P1-2）。
 *
 * 回答不满意时，用户要能一眼分清**是检索没给到料，还是模型没答好**。
 * 工具轨迹只说明"做了什么动作"，这里说明"拿到了什么"，两者互补。
 *
 * 设计取舍：只读信息用中性胶囊；**只有"省略了历史"用提醒色** ——
 * 那是唯一会造成"模型像是失忆"的情况，必须让用户看见，否则会被误判成模型变笨。
 */
export default function ContextChips({ context, onOpenRefs }: Props) {
  const { locale } = useLocale()
  /* 字典取名 c：本文件解构出的 kbs / retrieved / web 等均无冲突 */
  const c = pagesCopy(locale).chat
  const { kbs, retrieved, usedDocs, scannedDocs, historyTurns, web, attachments, truncated } = context

  return (
    <div className="ctx" aria-label={c.ctxAria}>
      {kbs.length > 0 && (
        <span className="ctx__chip" title={kbs.join(c.listSep)}>
          <BookOpen size={12} aria-hidden="true" />
          <Loc>{c.ctxKb(kbs.length)}</Loc>
        </span>
      )}
      <span className="ctx__chip" title={c.ctxHitTitle}>
        <Layers size={12} aria-hidden="true" />
        <Loc>{c.ctxHit(retrieved)}</Loc>
      </span>
      <button
        className="ctx__chip ctx__chip--btn"
        type="button"
        onClick={onOpenRefs}
        title={c.ctxCiteTitle}
      >
        <FileText size={12} aria-hidden="true" />
        <Loc>{c.ctxCite(usedDocs, scannedDocs)}</Loc>
      </button>
      <span className="ctx__chip" title={c.ctxHistoryTitle}>
        <MessagesSquare size={12} aria-hidden="true" />
        <Loc>{c.ctxHistory(historyTurns)}</Loc>
      </span>
      <span className="ctx__chip" data-off={web ? undefined : ''} title={web ? c.ctxWebOnTitle : c.ctxWebOffTitle}>
        <Globe size={12} aria-hidden="true" />
        <Loc>{web ? c.ctxWebOn : c.ctxWebOff}</Loc>
      </span>
      {attachments && attachments.length > 0 && (
        <span className="ctx__chip" title={attachments.join(c.listSep)}>
          <Paperclip size={12} aria-hidden="true" />
          <Loc>{c.ctxAttach(attachments.length)}</Loc>
        </span>
      )}
      {truncated && truncated > 0 ? (
        <span className="ctx__chip ctx__chip--warn" title={c.ctxTruncatedTitle}>
          <AlertTriangle size={12} aria-hidden="true" />
          <Loc>{c.ctxTruncated(truncated)}</Loc>
        </span>
      ) : null}
    </div>
  )
}
