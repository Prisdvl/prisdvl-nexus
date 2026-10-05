import type { ReactElement } from 'react'
import { Badge, type BadgeTone } from '@/components/ui/badge'
import { cn } from '@/lib/utils'
import { STATUS_META, toStatusKind, type StatusKind } from './status'

/**
 * StatusBadge —— 用户状态的**唯一**呈现方式。
 *
 * 替代"每个视图自己写一份 `STATUS_TEXT` + Badge"的现状——
 * Vue 侧改造前有两份（`KnowledgeBaseListView:199` 与 `AgentsView:82`），
 * 同一个状态在两个页面可能显示不同。
 *
 * ## 只负责呈现
 *
 * 不知道 Agent 是什么、也不知道任务跑到哪一步，只接收一个
 * {@link StatusKind}（或后端原始枚举）并按 `status.ts` 的词表显示。
 * 业务判断在调用方，不在这里。
 */
export interface StatusBadgeProps {
  /**
   * 状态。
   *
   * 同时接受两种输入：已归一的 `StatusKind`（`'running'`）与
   * 后端原始枚举（`'RUNNING'`）。大写形式不在 `StatusKind` 名单里，按后端枚举解析。
   */
  status: StatusKind | string
  /** 是否显示脉冲点。进行中默认显示，让"还在跑"这件事持续可见。 */
  showDot?: boolean
  /**
   * 定位锚点，**由调用方给**。
   *
   * 任务书 §28：基础组件不能硬编码业务 testid。这里刻意不写死
   * `data-testid="status-badge"` ——同一个组件在不同页面代表不同的业务对象，
   * 写死的 testid 会让 E2E 分不清是"哪个知识库的状态"还是"哪个 Agent 的状态"。
   */
  testId?: string
  className?: string
}

const KIND_NAMES = Object.keys(STATUS_META) as StatusKind[]

export function StatusBadge({
  status,
  showDot = true,
  testId,
  className
}: StatusBadgeProps): ReactElement {
  const kind: StatusKind = KIND_NAMES.includes(status as StatusKind)
    ? (status as StatusKind)
    : toStatusKind(status)
  const meta = STATUS_META[kind]

  return (
    <Badge
      // data-value 而不是 data-status：值里没有中文/大写，做成属性值更便于
      // E2E 用 [data-value="running"] 定位。**与 data-testid 配合**：
      // testId 说"这是谁的"，data-value 说"它是什么状态"。
      data-testid={testId}
      data-value={kind}
      tone={meta.tone as BadgeTone}
      dot={showDot}
      // 只在进行中加脉冲——终态也闪会让用户以为还没结束
      pulse={meta.pending}
      className={cn(className)}
    >
      {meta.label}
    </Badge>
  )
}
