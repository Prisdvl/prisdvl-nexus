import { useMemo, type ReactElement } from 'react'
import { Badge, type BadgeTone } from '@/components/ui/badge'

/**
 * 文档处理状态 —— **后端有 8 个值，前 5 个是流水线的不同阶段**。
 *
 * 权威来源：`nexus-knowledge/.../knowledge/model/enums/DocumentStatus.java`
 * ```text
 * PENDING → PARSING → CHUNKING → EMBEDDING → INDEXING → READY
 *                                                    ↘ FAILED / SKIPPED
 * ```
 *
 * ## 为什么不能直接显示英文枚举（任务书 §17）
 *
 * `PARSING` 之于用户毫无意义——他关心的是"还要等多久"和"能不能开始提问"。
 * 所以中文词表把**五个中间态压缩成一个"处理中"**（配脉冲点表示还在跑），
 * 只把 `READY` 显示为「已完成」。
 *
 * ## 「已完成」的含义是被严格定义的（任务书 §21）
 *
 * `READY` 意味着 **解析 + 切片 + 向量化 + 建索引全部完成**，
 * 不只是"文件传上来了"。所以界面敢在这时才提示"可以开始提问了"——
 * 提前提示会让用户提了个空问，然后以为是检索坏了。
 *
 * `EMBEDDING` / `INDEXING` 这两个阶段名**刻意不显示**：
 * 它们是 L4 开发信息（Phase B §12），普通用户看到"正在向量化"只会困惑。
 */

type DocStatusMeta = { label: string; tone: BadgeTone; pending: boolean }

const DOC_STATUS: Record<string, DocStatusMeta> = {
  PENDING: { label: '等待处理', tone: 'info', pending: true },
  PARSING: { label: '处理中', tone: 'primary', pending: true },
  CHUNKING: { label: '处理中', tone: 'primary', pending: true },
  EMBEDDING: { label: '处理中', tone: 'primary', pending: true },
  INDEXING: { label: '处理中', tone: 'primary', pending: true },
  READY: { label: '已完成', tone: 'success', pending: false },
  FAILED: { label: '失败', tone: 'danger', pending: false },
  SKIPPED: { label: '已跳过', tone: 'neutral', pending: false }
}

/** 未知状态**回落到「处理中」而不是「已完成」**——不能让用户以为好了。 */
const FALLBACK: DocStatusMeta = { label: '处理中', tone: 'info', pending: true }

export function useDocStatus(status: string): DocStatusMeta {
  return useMemo(() => DOC_STATUS[status] ?? FALLBACK, [status])
}

export function DocStatusBadge({ status }: { status: string }): ReactElement {
  const meta = useDocStatus(status)
  return (
    <Badge
      tone={meta.tone}
      dot={meta.pending}
      pulse={meta.pending}
      data-testid="doc-status"
      data-value={status}
    >
      {meta.label}
    </Badge>
  )
}

/**
 * 文件大小。**后端给的 `fileSizeKb` 单位是 KB**。
 *
 * Vue 侧直接当字节传给 `formatBytes`，显示的大小差了 1024 倍。
 * 这里明确按 KB 处理，并对 null 显示 `-`（"后端没统计"）。
 */
export function formatSizeKb(kb: number | null): string {
  if (kb === null) return '-'
  if (kb < 1) return `${Math.round(kb * 1024)} B`
  if (kb < 1024) return `${kb.toFixed(kb < 10 ? 1 : 0)} KB`
  return `${(kb / 1024).toFixed(1)} MB`
}
