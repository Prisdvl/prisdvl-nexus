import { type ReactElement } from 'react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { agentStatusLabel, type Agent } from '@/api/types/agent'

/**
 * Agent 卡片。
 *
 * ## 显示什么（Phase B §15：L1 用户任务 + L2 用户状态）
 * 名称 · 状态 · 一句描述 · 知识库范围 · 工具数
 *
 * ## 刻意不显示（L4 开发信息）
 * `systemPrompt` / `modelRole` / `temperature` / `answerPolicy`
 *
 * 这四项在编辑器的「高级」区可见。放在卡片上会让普通用户以为
 * "回答不好是因为 temperature 太高"——而那通常是提示词或知识库的问题。
 *
 * ## 「知识库范围」的措辞是刻意的
 *
 * 后端注释明确：`Agent.kbIds` **为空 = 不限制**（检索全部库），
 * 这与 RAG 会话的「为空 = 必须绑定」**语义相反**。
 * 所以这里显示「全部知识库」而不是「未绑定」——
 * 后者会让用户以为这个 Agent 不能用。
 */
export interface AgentCardProps {
  agent: Agent
  onEdit: () => void
  onTest: () => void
  onDuplicate: () => void
  onDelete: () => void
}

function StatusPill({ status }: { status: string }): ReactElement {
  const tone = status === 'PUBLISHED' ? 'success' : status === 'DISABLED' ? 'neutral' : 'info'
  return (
    <Badge tone={tone} data-testid="agent-card-status">
      {agentStatusLabel(status)}
    </Badge>
  )
}

export function AgentCard({
  agent,
  onEdit,
  onTest,
  onDuplicate,
  onDelete
}: AgentCardProps): ReactElement {
  const kbCount = agent.kbIds.length

  return (
    <Card variant="interactive" padding="none" className="flex w-full flex-col" data-testid="agent-card" data-agent-id={agent.id}>
      <div className="flex flex-1 flex-col gap-2 p-4">
        <div className="flex items-start justify-between gap-2">
          <div className="min-w-0 flex-1">
            <h2 className="m-0 truncate text-[var(--nx-text-lg)] font-[var(--nx-weight-semibold)]">
              {agent.name}
            </h2>
            {/* code 是业务标识（后端主键是 id）——L2，属于"这是哪个 Agent" */}
            <p
              className="m-0 mt-0.5 font-mono text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]"
              data-testid="agent-card-code"
            >
              {agent.code}
            </p>
          </div>
          <div className="flex shrink-0 items-center gap-1">
            <StatusPill status={agent.status} />
            {agent.builtin ? (
              <Badge tone="neutral" data-testid="agent-card-builtin">
                内置
              </Badge>
            ) : null}
          </div>
        </div>

        {agent.description ? (
          <p className="m-0 line-clamp-2 text-[var(--nx-text-sm)] text-[var(--nx-color-text-secondary)]">
            {agent.description}
          </p>
        ) : null}

        <div className="mt-1 flex items-center gap-3 border-t border-[var(--nx-color-border)] pt-3 text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">
          {/* 措辞按后端语义：空 = 不限制 */}
          <span data-testid="agent-card-kbs">
            {kbCount === 0 ? '全部知识库' : `${kbCount} 个知识库`}
          </span>
          <span data-testid="agent-card-tools">
            {agent.toolCodes.length === 0 ? '未用工具' : `${agent.toolCodes.length} 个工具`}
          </span>
        </div>
      </div>

      {/*
        操作区。
        **试跑对草稿也可用** —— 后端明确允许 DRAFT 试跑（"先试跑再发布"是主流程）。
        内置 Agent 不给删除（后端也会拒），但仍可复制与编辑。
      */}
      <div className="flex items-center gap-1 border-t border-[var(--nx-color-border)] px-3 py-2">
        <Button variant="ghost" size="sm" onClick={onTest} data-testid="agent-test">
          试跑
        </Button>
        <Button variant="ghost" size="sm" onClick={onEdit} data-testid="agent-edit">
          编辑
        </Button>
        <Button variant="ghost" size="sm" onClick={onDuplicate} data-testid="agent-duplicate">
          复制
        </Button>
        {!agent.builtin ? (
          <Button variant="ghost" size="sm" onClick={onDelete} data-testid="agent-delete">
            删除
          </Button>
        ) : null}
      </div>
    </Card>
  )
}
