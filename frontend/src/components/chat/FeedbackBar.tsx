import { useState } from 'react'
import { ThumbsDown, ThumbsUp, Check } from 'lucide-react'
import { feedbackStore, type FeedbackEntry, type FeedbackReason, type FeedbackVote } from '@/lib/feedback'
import '@/styles/feedback.css'

/* 只有 👎 才追问原因；顺序按"最常见 → 最罕见"排，不追求分类学完备 */
const REASONS: FeedbackReason[] = ['没答到点', '引用不对', '事实错误', '太啰嗦', '格式差', '其他']

interface Props {
  /** 除 vote / reason / createdAt 之外的元信息（由对话页按当前运行时填充） */
  meta: Omit<FeedbackEntry, 'vote' | 'reason' | 'createdAt'>
}

/**
 * 回答反馈条。
 *
 * 交互取舍：
 * - **一次点击就落库**（👍 立即记；👎 先记「无用」，原因可选补充）—— 不要用"必须选原因才能提交"，
 *   那样收集率会掉一半。
 * - 记录以 `msgId` 为键**覆盖**：用户改主意不应该产生两条数据。
 * - 记完给一个明确的"已记录"态，并提供撤销（避免误点无法挽回）。
 */
export default function FeedbackBar({ meta }: Props) {
  const [voted, setVoted] = useState<FeedbackVote | null>(null)
  const [reason, setReason] = useState<FeedbackReason | null>(null)

  const save = (vote: FeedbackVote, nextReason?: FeedbackReason | null) => {
    setVoted(vote)
    setReason(nextReason ?? null)
    feedbackStore.record({
      ...meta,
      vote,
      ...(vote === 'down' && (nextReason ?? reason) ? { reason: (nextReason ?? reason) as FeedbackReason } : {}),
      createdAt: new Date().toISOString(),
    })
  }

  if (voted) {
    return (
      <span className="fb fb--done" role="status">
        <Check size={12} /> 已记录
        {voted === 'down' && (
          <>
            <span className="fb__sep">·</span>
            {REASONS.map((r) => (
              <button
                key={r}
                type="button"
                className="fb__reason"
                aria-pressed={reason === r}
                onClick={() => save('down', r)}
              >
                {r}
              </button>
            ))}
          </>
        )}
        <button
          className="fb__undo"
          type="button"
          onClick={() => { setVoted(null); setReason(null); feedbackStore.remove(meta.msgId) }}
        >
          撤销
        </button>
      </span>
    )
  }

  return (
    <span className="fb" role="group" aria-label="这条回答有帮助吗">
      <button className="msg-op" type="button" title="有帮助" aria-label="有帮助" onClick={() => save('up')}>
        <ThumbsUp size={15} aria-hidden="true" />
      </button>
      <button className="msg-op" type="button" title="没答好" aria-label="没答好" onClick={() => save('down')}>
        <ThumbsDown size={15} aria-hidden="true" />
      </button>
    </span>
  )
}
