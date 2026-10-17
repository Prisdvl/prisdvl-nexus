import { memo } from 'react'
import { Check, RotateCcw, X } from 'lucide-react'
import LatticeLoader from '@/bits/LatticeLoader/LatticeLoader'
import type { ToolCall } from '@/data'
import './ToolTrace.css'

interface Props {
  calls: ToolCall[]
  onRetry?: (callId: string) => void
  className?: string
}

const RUNNING = new Set(['idle', 'awaiting', 'running'])
const RETRYABLE = new Set(['error', 'timeout'])

/**
 * 对话里的工具轨迹：终端式一行一条，不再用胶囊卡片。
 * 运行态复用工作台“最近文档处理”的 LatticeLoader sweep 动画。
 */
function ToolTrace({ calls, onRetry, className = '' }: Props) {
  if (calls.length === 0) return null
  return (
    <ol className={`tool-calls tool-trace${className ? ` ${className}` : ''}`} aria-label="Tool calls">
      {calls.map((call) => {
        const running = RUNNING.has(call.status)
        const failed = RETRYABLE.has(call.status)
        return (
          <li className="call-chip tool-trace__row" data-status={call.status} key={call.id}>
            <span className="tool-trace__prompt" aria-hidden="true">$</span>
            <span className="call-chip__name tool-trace__name">{call.name}</span>
            <code className="tool-trace__arg">{call.argument}</code>
            <span className="tool-trace__status">
              {running && (
                <LatticeLoader
                  status="working"
                  label=""
                  pattern="sweep"
                  grid={3}
                  cellSize={3.5}
                  gap={1.5}
                  fontSize={11}
                  showTimer={false}
                  className="tool-trace__spinner"
                />
              )}
              {call.status === 'done' && <Check size={13} aria-hidden="true" />}
              {failed && onRetry && (
                <button className="call-chip__retry tool-trace__retry" type="button" title="Retry" aria-label={`Retry ${call.name}`} onClick={() => onRetry(call.id)}>
                  <RotateCcw size={12} />
                </button>
              )}
              {failed && !onRetry && <X size={13} aria-hidden="true" />}
              {call.status === 'cancelled' && <X size={13} aria-hidden="true" />}
            </span>
            {call.explain && <span className="tool-trace__note">{call.explain}</span>}
          </li>
        )
      })}
    </ol>
  )
}

export default memo(ToolTrace)
