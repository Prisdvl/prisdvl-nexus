import { Link } from 'react-router'
import type { ReactElement } from 'react'
import { Badge } from '@/components/ui/badge'
import { Card } from '@/components/ui/card'
import { Tooltip } from '@/components/ui/tooltip'
import type { KnowledgeBase } from '@/api/types/knowledge'
import { cn } from '@/lib/utils'

/**
 * 知识库卡片。
 *
 * ## 显示什么（Phase B §15：L1 用户任务 + L2 用户状态）
 *
 * 名称 · 启用状态 · 文档数 / 切片数 / Token · 一句描述 · 更新时间
 *
 * ## 刻意不显示什么（L4 开发信息，默认隐藏）
 *
 * `embeddingDim` `chunkSize` `chunkOverlap` `embeddingProvider` `embeddingModel`
 * `chunkStrategy` `retrievalTopK` `similarityThreshold`
 *
 * 这些字段**后端都在返回**，写进卡片只需几行。但它们回答的是
 * "系统怎么配置的"，而不是"我的资料怎么样了"——
 * Phase B §12 明确它们属于 L4，普通用户不该看到。
 * 真要查，放到详情页的「高级」区（R10）。
 *
 * ## 数字为 null 的处理
 *
 * `docCount` / `chunkCount` / `totalTokens` 在后端都是包装类型，新建的库可能为 null。
 * **显示 `-` 而不是 `0`**——`0` 意味着"确实没有"，`null` 意味着"还没统计"。
 * 两者混为一谈会让用户以为资料丢了。
 */
export interface KnowledgeBaseCardProps {
  kb: KnowledgeBase
  /** 危险操作（删除）由页面通过菜单触发，不放在卡片右下角常驻。 */
  onDelete?: (kb: KnowledgeBase) => void
  selected?: boolean
}

/**
 * 数字展示：null → `-`（"还没统计"），0 → `0`（"确实没有"）。
 *
 * 形参用 `string | number | null`：`totalTokens` 后端是 `Long`，
 * **JSON 里是字符串**（见 `api/types/knowledge.ts` 文件头），
 * 而 `docCount` / `chunkCount` 是 `Integer`（number）。两种都要能显示。
 */
function stat(n: string | number | null): string {
  if (n === null) return '-'
  return typeof n === 'string' ? n : n.toLocaleString('zh-CN')
}

const TIME_FMT = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit'
})

function formatTime(iso: string | null): string {
  if (!iso) return ''
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? '' : TIME_FMT.format(d)
}

/** 启用状态 → 徽标。**只有两个值**（后端 `KnowledgeBaseStatus`）。 */
function StatusPill({ status }: { status: string }): ReactElement {
  const enabled = status === 'ENABLED'
  return (
    <Badge tone={enabled ? 'success' : 'neutral'} data-testid="kb-card-status">
      {enabled ? '启用中' : '已停用'}
    </Badge>
  )
}

export function KnowledgeBaseCard({ kb, selected }: KnowledgeBaseCardProps): ReactElement {
  const updated = formatTime(kb.updatedAt)

  return (
    <Card
      variant={selected ? 'selected' : 'interactive'}
      padding="none"
      className="group relative flex flex-col"
      data-testid="kb-card"
      data-kb-id={kb.id}
    >
      {/* 整卡可点：去详情。用 Link 包裹标题区而不是整卡，
          避免把"删除"按钮也变成链接（嵌套交互元素对读屏不友好）。 */}
      <Link
        to={`/kb/${kb.id}`}
        className="flex flex-1 flex-col gap-2 p-4 focus-visible:outline-none"
        data-testid="kb-card-link"
      >
        <div className="flex items-start justify-between gap-2">
          <h2 className="m-0 min-w-0 flex-1 truncate text-[var(--nx-text-lg)] font-[var(--nx-weight-semibold)]">
            {kb.name}
          </h2>
          <StatusPill status={kb.status} />
        </div>

        {kb.description ? (
          <p className="m-0 line-clamp-2 text-[var(--nx-text-sm)] text-[var(--nx-color-text-secondary)]">
            {kb.description}
          </p>
        ) : null}

        {/*
          三个指标。**不加图标、不用颜色区分**——它们是事实陈述，不是状态。
          用颜色暗示"好/坏"是过度解读（比如 0 文档不是"坏"，只是还没导入）。
        */}
        <dl className="mt-1 grid grid-cols-3 gap-2 border-t border-[var(--nx-color-border)] pt-3">
          <Metric label="文档" value={stat(kb.docCount)} testId="kb-card-docs" />
          <Metric label="切片" value={stat(kb.chunkCount)} testId="kb-card-chunks" />
          <Metric label="Token" value={stat(kb.totalTokens)} testId="kb-card-tokens" />
        </dl>
      </Link>

      {updated ? (
        <div className="border-t border-[var(--nx-color-border)] px-4 py-2">
          <p className="m-0 text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">
            更新于 {updated}
          </p>
        </div>
      ) : null}
    </Card>
  )
}

function Metric({
  label,
  value,
  testId
}: {
  label: string
  value: string
  testId: string
}): ReactElement {
  return (
    <div className="min-w-0">
      <dt className="m-0 text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">{label}</dt>
      <dd
        className={cn('m-0 truncate font-[var(--nx-weight-medium)]', 'text-[var(--nx-text-sm)]')}
        data-testid={testId}
      >
        <Tooltip content={value} side="top">
          <span>{value}</span>
        </Tooltip>
      </dd>
    </div>
  )
}
