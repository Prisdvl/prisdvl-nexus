import { useState, type ReactElement } from 'react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Textarea } from '@/components/ui/form'
import { testAgent } from '@/api/agent'
import type { Agent, AgentAnswer } from '@/api/types/agent'
import { getErrorMessage } from '@/lib/errors'

/**
 * Agent 试跑面板。
 *
 * ## 试跑是主流程的一部分，不是附属功能
 *
 * 后端注释写得很明确：
 * > 试跑**不受发布状态限制**……若试跑也要求 PUBLISHED，
 * > 草稿就永远没法被验证，只能靠"发布后才发现提示词写错了"。
 *
 * 所以这个面板对 `DRAFT` 状态的 Agent 同样开放。
 *
 * ## 展示工具痕迹（任务书 §19：L1 是"用了什么工具"）
 *
 * `AgentAnswer.toolCalls` 里有 `tool` / `input` / `resultSummary`。
 * 只显示**工具名与结果摘要**，不显示完整 input——
 * 那是调试信息（L4），且可能包含用户的问题原文。
 */
export interface AgentTesterProps {
  agent: Agent
  onClose: () => void
}

export function AgentTester({ agent, onClose }: AgentTesterProps): ReactElement {
  const [question, setQuestion] = useState('')
  const [running, setRunning] = useState(false)
  const [answer, setAnswer] = useState<AgentAnswer | null>(null)
  const [error, setError] = useState<string | null>(null)

  const onRun = async () => {
    const q = question.trim()
    if (!q || running) return
    setRunning(true)
    setError(null)
    setAnswer(null)
    try {
      setAnswer(await testAgent(agent.id, { question: q }))
    } catch (e) {
      setError(getErrorMessage(e))
    } finally {
      setRunning(false)
    }
  }

  return (
    <Dialog
      open
      onClose={onClose}
      title={`试跑「${agent.name}」`}
      // 试跑进行中不允许点遮罩关闭——否则用户会以为请求停了，其实还在跑
      closeOnOverlayClick={!running}
      footer={
        <>
          {/* data-testid：试跑面板会盖住下面的卡片，E2E 必须能精确关掉它 */}
          <Button variant="ghost" onClick={onClose} disabled={running} data-testid="agent-test-close">
            关闭
          </Button>
          <Button
            onClick={() => void onRun()}
            loading={running}
            disabled={!question.trim()}
            data-testid="agent-test-run"
          >
            运行
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        <p className="m-0 text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">
          草稿也可以试跑——先验证提示词写对了，再发布。
        </p>

        <Textarea
          rows={2}
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          placeholder="问一句试试"
          data-testid="agent-test-input"
        />

        {error ? (
          <p role="alert" data-testid="agent-test-error" className="m-0 text-[var(--nx-text-sm)] text-[var(--nx-color-danger)]">
            {error}
          </p>
        ) : null}

        {answer ? (
          <div className="flex flex-col gap-2" data-testid="agent-test-answer">
            <div className="rounded-[var(--nx-radius-md)] bg-[var(--nx-color-surface-muted)] px-3 py-2 text-[var(--nx-text-sm)] whitespace-pre-wrap">
              {answer.answer}
            </div>
            {answer.toolCalls.length > 0 ? (
              <ul className="m-0 flex list-none flex-col gap-1 p-0" data-testid="agent-test-tools">
                {answer.toolCalls.map((t, i) => (
                  <li key={i} className="flex items-center gap-2 text-[var(--nx-text-xs)]">
                    <Badge tone="info">{t.tool}</Badge>
                    {/* 只显示结果摘要，不显示完整 input（含用户问题原文，属调试信息） */}
                    <span className="min-w-0 flex-1 truncate text-[var(--nx-color-text-muted)]">
                      {t.resultSummary ?? ''}
                    </span>
                  </li>
                ))}
              </ul>
            ) : null}
          </div>
        ) : null}
      </div>
    </Dialog>
  )
}
