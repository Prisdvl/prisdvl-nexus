import { useState, type ReactNode } from 'react'
import { AlertTriangle, Check, ChevronDown, Circle, RotateCcw, X } from 'lucide-react'
import LatticeLoader from '@/bits/LatticeLoader/LatticeLoader'
import type { RunContext, ToolCall } from '@/data'
import './AgentThoughtChain.css'

interface Props {
  calls: ToolCall[]
  working?: boolean
  steps?: string[]
  /** 兼容旧调用点；上下文元数据由下方的 ContextChips 统一呈现，这里不再重复 */
  context?: RunContext
  /** 兼容旧调用点：引用数量由回答下方的引用区呈现，顶部摘要不复述统计数字 */
  citations?: number
  elapsedMs?: number
  onRetry?: (callId: string) => void
  children?: ReactNode
}

/**
 * 状态文案：`label` 给图标 title / 展开详情用（七个状态都齐全），
 * `tail` 只在"没跑完或跑坏了"时露一个字 —— 完成态交给行首那个安静的勾。
 */
const STATUS_LABEL: Record<ToolCall['status'], string> = {
  idle: '排队中', awaiting: '等待确认', running: '进行中', done: '已完成',
  error: '失败', cancelled: '已停止', timeout: '超时',
}
const STATUS_TAIL: Partial<Record<ToolCall['status'], string>> = {
  idle: '排队中', awaiting: '等待确认', running: '进行中',
  error: '失败', timeout: '超时', cancelled: '已停止',
}

/**
 * 工具名 → 人话动作。对话里读一行就知道"它做了什么"；
 * 函数名是给人看的噪声，动作才是信息（函数名退到动作后面的弱化位）。
 * `verb` 是记录行的标题（动作已发生），`title` 给顶部摘要拼"正在…"。
 */
const ACTIONS: Array<[RegExp, string, string]> = [
  [/web|http|fetch|browse|crawl/i, '访问了网络', '访问网络'],
  [/kb\.|knowledge|retriev|search|rag/i, '检索了知识库', '检索知识库'],
  [/doc\.read|file\.read|\bread\b/i, '读取了文件', '读取文件'],
  [/write|fs\.|save|edit|patch/i, '写入了文件', '写入文件'],
  [/js\.|sandbox|python|code|compile|test/i, '执行了代码', '执行代码'],
  [/calc|math|arithmetic|compute|计算/i, '执行了计算', '执行计算'],
  [/shell|bash|cmd|command|exec|\brun\b/i, '执行了命令', '执行命令'],
  [/mcp|tool/i, '调用了工具', '调用工具'],
]

function actionOf(name: string) {
  for (const [re, verb, title] of ACTIONS) if (re.test(name)) return { verb, title }
  return { verb: '调用了工具', title: '调用工具' }
}

function statusLabel(status: ToolCall['status'], warn: boolean) {
  return warn && (status === 'idle' || status === 'awaiting') ? STATUS_LABEL.awaiting : STATUS_LABEL[status]
}

/**
 * 行首状态图标：只有"待确认 / 跑坏了"才上色，完成态是一个安静的勾。
 * 具体颜色由 CSS 按 `[data-status]` / `[data-warn]` 给（见 AgentThoughtChain.css）。
 */
function StatusIcon({ status, warn, label }: { status: ToolCall['status']; warn: boolean; label: string }) {
  const alert = warn || status === 'error' || status === 'timeout'
  return (
    <span
      className={`thought-call__mark${status === 'running' ? ' is-spin' : ''}`}
      role="img"
      aria-label={label}
      title={label}
    >
      {status === 'done' ? <Check size={12} aria-hidden="true" />
        : alert ? <AlertTriangle size={12} aria-hidden="true" />
          : status === 'cancelled' ? <X size={12} aria-hidden="true" />
            : <Circle size={11} aria-hidden="true" />}
    </span>
  )
}

/** `12分06秒` / `8.4秒`：与标题同一行的计时文案，不出现 "0分" 这种噪声 */
function formatDuration(ms?: number) {
  if (!ms || ms <= 0) return null
  const total = Math.round(ms / 1000)
  if (total < 60) return `${(ms / 1000).toFixed(1)}秒`
  const m = Math.floor(total / 60)
  return `${m}分${String(total % 60).padStart(2, '0')}秒`
}

export default function AgentThoughtChain({
  calls, working = false, steps = [], elapsedMs, onRetry, children,
}: Props) {
  const [open, setOpen] = useState(true)
  const [expandedId, setExpandedId] = useState<string | null>(null)
  const running = working || calls.some((c) => c.status === 'running' || c.status === 'idle' || c.status === 'awaiting')
  const duration = formatDuration(elapsedMs)
  const hasBody = steps.length > 0 || calls.length > 0 || !!children
  /* 头部已经有「用时」时，正文首行再点一次状态；历史消息没有计时，就不重复同一句话 */
  const showState = !running && !!duration

  /*
   * 顶部摘要：只说"现在在做什么 / 做完了"。
   * 不复述引用数、知识库数、历史轮次、联网状态 —— 那些是"拿到了什么"，
   * 由回答上方的 ContextChips 单独回答，两处一起说就是同一句话说两遍。
   */
  const pending = calls.find((c) => c.requiresConfirm && (c.status === 'awaiting' || c.status === 'idle'))
  const runningCall = calls.find((c) => c.status === 'running')
  /* 工具都收尾了但流还活着 = 模型正在写答案，这不是"处理中"，说清楚 */
  const anyDone = calls.some((c) => c.status === 'done')
  const lastStep = steps.length > 0 ? steps[steps.length - 1] : ''
  /* 真的在跑的动作优先于"待确认"：待确认的那条可能还没轮到（链路仍在推进前一步） */
  const summary = running
    ? (runningCall
        ? `正在${actionOf(runningCall.name).title}…`
        : pending
          ? `等待确认 · ${actionOf(pending.name).title}`
          : lastStep || (anyDone ? '正在生成回答…' : '正在处理…'))
    : (duration ? `已完成 · 用时 ${duration}` : '已完成')

  return (
    <section className="thought-chain" data-working={running ? '' : undefined} aria-label="执行时间线（工具调用与结果）">
      <button className="thought-chain__head" type="button" aria-expanded={open && hasBody} onClick={() => setOpen((v) => !v)}>
        {running && (
          <span className="thought-chain__spinner" aria-hidden="true">
            <LatticeLoader status="working" label="" pattern="sweep" grid={3} cellSize={3.5} gap={1.5} showTimer={false} />
          </span>
        )}
        <span className="thought-chain__time">{summary}</span>
        {hasBody && <ChevronDown size={14} className={`thought-chain__chev${open ? ' is-open' : ''}`} aria-hidden="true" />}
      </button>

      {open && hasBody && (
        <div className="thought-chain__body">
          {showState && (
            <p className="thought-chain__state">
              <Check size={13} aria-hidden="true" />
              已完成分析
            </p>
          )}

          <ol className="thought-chain__list">
            {steps.map((text, i) => (
              <li className="thought-step" key={`s-${i}-${text}`} style={{ animationDelay: `${Math.min(i, 8) * 60}ms` }}>
                {text}
              </li>
            ))}
            {calls.map((call, i) => {
              const awaiting = !!call.requiresConfirm && (call.status === 'awaiting' || call.status === 'idle')
              const expanded = expandedId === call.id
              const failed = call.status === 'error' || call.status === 'timeout'
              const action = actionOf(call.name)
              const label = statusLabel(call.status, awaiting)
              const tail = awaiting ? STATUS_TAIL.awaiting : STATUS_TAIL[call.status]
              return (
                <li
                  className="thought-call"
                  data-status={call.status}
                  data-tool={call.name}
                  data-warn={awaiting ? '' : undefined}
                  key={call.id}
                  style={{ animationDelay: `${Math.min(steps.length + i, 8) * 60}ms` }}
                >
                  <div className="thought-call__line">
                    {/*
                      整行是一个按钮：点开看参数与结果。状态变化只改这一条（key = call.id），
                      不新增记录；动作标题对同一个 call 恒定，所以状态推进时文字不会跳。
                    */}
                    <button
                      className="thought-call__row"
                      type="button"
                      aria-expanded={expanded}
                      aria-label={`${action.verb} ${call.name} ${label}`}
                      onClick={() => setExpandedId((id) => (id === call.id ? null : call.id))}
                    >
                      <StatusIcon status={call.status} warn={awaiting} label={label} />
                      <span className="thought-call__verb">{action.verb}</span>
                      <span className="thought-call__tool">{call.name}</span>
                      <span className="thought-call__tail">
                        {tail && <span className="thought-call__status">{tail}</span>}
                        <ChevronDown size={12} className={`thought-call__chev${expanded ? ' is-open' : ''}`} aria-hidden="true" />
                      </span>
                    </button>
                    {failed && onRetry && (
                      <button
                        className="thought-call__retry"
                        type="button"
                        aria-label={`重试 ${call.name}`}
                        title="重试"
                        onClick={() => onRetry(call.id)}
                      >
                        <RotateCcw size={12} aria-hidden="true" />
                      </button>
                    )}
                  </div>
                  {expanded && (
                    <pre className="thought-call__code">
{`$ ${call.name}${call.argument ? ` ${call.argument}` : ''}`}
{call.result ? `\n\n# 结果：${call.result}` : ''}
{call.explain ? `\n\n# ${call.explain}` : ''}
{`\n\n# ${label}`}
                    </pre>
                  )}
                </li>
              )
            })}
          </ol>

          {children}
        </div>
      )}
    </section>
  )
}
