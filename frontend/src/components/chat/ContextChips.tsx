import { AlertTriangle, BookOpen, FileText, Globe, Layers, MessagesSquare, Paperclip } from 'lucide-react'
import type { RunContext } from '@/data'
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
  const { kbs, retrieved, usedDocs, scannedDocs, historyTurns, web, attachments, truncated } = context

  return (
    <div className="ctx" aria-label="本轮上下文">
      {kbs.length > 0 && (
        <span className="ctx__chip" title={kbs.join('、')}>
          <BookOpen size={12} aria-hidden="true" />
          知识库 {kbs.length}
        </span>
      )}
      <span className="ctx__chip" title="在绑定的知识库里检索到的切片数">
        <Layers size={12} aria-hidden="true" />
        命中 {retrieved} 切片
      </span>
      <button
        className="ctx__chip ctx__chip--btn"
        type="button"
        onClick={onOpenRefs}
        title="查看引用的文档"
      >
        <FileText size={12} aria-hidden="true" />
        引用 {usedDocs} 篇{scannedDocs > 0 ? ` / 共 ${scannedDocs} 篇` : ''}
      </button>
      <span className="ctx__chip" title="带到这一轮的历史轮数">
        <MessagesSquare size={12} aria-hidden="true" />
        历史 {historyTurns} 轮
      </span>
      <span className="ctx__chip" data-off={web ? undefined : ''} title={web ? '本轮允许联网检索' : '本轮未联网'}>
        <Globe size={12} aria-hidden="true" />
        {web ? '已联网' : '未联网'}
      </span>
      {attachments && attachments.length > 0 && (
        <span className="ctx__chip" title={attachments.join('、')}>
          <Paperclip size={12} aria-hidden="true" />
          附件 {attachments.length}
        </span>
      )}
      {truncated && truncated > 0 ? (
        <span className="ctx__chip ctx__chip--warn" title="超出上下文窗口，早期对话已被省略">
          <AlertTriangle size={12} aria-hidden="true" />
          已省略早期 {truncated} 轮
        </span>
      ) : null}
    </div>
  )
}
