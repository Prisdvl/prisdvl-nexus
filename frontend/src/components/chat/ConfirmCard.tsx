import { ShieldAlert, Check, X, Infinity as InfinityIcon } from 'lucide-react'
import '@/styles/confirm.css'

export interface ConfirmRequest {
  /** 工具调用 id */
  callId: string
  /** 工具名，例如 js.sandbox */
  name: string
  /** 参数摘要（展示给用户判断） */
  argument: string
  /** 人话说明这次会干什么，以及风险点 */
  explain: string
  /** 风险等级：high 才需要确认 */
  risk: 'high'
}

interface Props {
  request: ConfirmRequest
  /** 允许一次 */
  onAllowOnce: () => void
  /** 始终允许（写入工具策略，后续不再询问） */
  onAllowAlways: () => void
  /** 拒绝（本次不执行，链继续往下走） */
  onDeny: () => void
}

/**
 * 高风险工具确认卡（human-in-the-loop）。
 *
 * 设计取舍：
 * - **内联在工具调用轨迹里**，不用弹窗：确认是"这一步"的事，弹窗会打断整段阅读。
 * - 三个动作必须都在：只给"允许/拒绝"会逼用户反复点；只给"始终允许"则等于没有把关。
 * - 「拒绝」不是报错：它让**链路继续**（跳过这一步），回答里说明跳过了什么，而不是整轮失败。
 * - Esc = 拒绝（把最安全的行为绑在 Esc 上）。
 */
export default function ConfirmCard({ request, onAllowOnce, onAllowAlways, onDeny }: Props) {
  return (
    <div
      className="confirm-card"
      role="alertdialog"
      aria-label={`需要确认：${request.name}`}
      onKeyDown={(e) => { if (e.key === 'Escape') { e.stopPropagation(); onDeny() } }}
    >
      <span className="confirm-card__ic" aria-hidden="true"><ShieldAlert size={15} /></span>
      <div className="confirm-card__body">
        <p className="confirm-card__title">这一步需要你确认</p>
        <p className="confirm-card__desc">{request.explain}</p>
        <code className="confirm-card__cmd">{request.name} · {request.argument}</code>
      </div>
      <div className="confirm-card__actions">
        <button className="btn btn-primary btn-sm" type="button" autoFocus onClick={onAllowOnce}>
          <Check size={13} /> 允许一次
        </button>
        <button className="btn btn-ghost btn-sm" type="button" onClick={onAllowAlways}>
          <InfinityIcon size={13} /> 始终允许
        </button>
        <button className="btn btn-ghost btn-sm" type="button" onClick={onDeny}>
          <X size={13} /> 拒绝
        </button>
      </div>
    </div>
  )
}
