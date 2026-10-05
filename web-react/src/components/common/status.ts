/**
 * 状态的**唯一真相**（Phase B §12 的 L2「用户状态」层）。
 *
 * ## 为什么必须是"唯一"
 *
 * Vue 侧改造前有两份词表，散在两个视图里各写一份：
 *
 * ```text
 * KnowledgeBaseListView:199   QUEUED→排队中  RUNNING→处理中  DONE→已完成 …
 * AgentsView:82               DRAFT→草稿  PUBLISHED→已发布  DISABLED→已停用
 * ```
 *
 * 后果不是"多写了几个字"，而是**同一个状态在两个页面可能显示不同**，
 * 而且没人能说清以哪个为准。React 版第一版就把它合并到这里。
 *
 * ## 边界：只管 L2，不管 L3/L4
 *
 * 检索相关的标签（「已重排」「预算截断 N 段」「知识库里没有命中」）**不在这里**。
 * 按 Phase B §12 它们是 L3/L4，默认折叠、只在"检索详情"里出现，
 * 语义上也和"任务状态"不是一回事。硬塞进来会让这张表失去意义。
 *
 * 本文件不依赖 React，可在非组件代码（E2E 辅助、格式化函数）里引用。
 */

/**
 * 跨领域通用的用户状态。
 *
 * 命名用**中性词**（pending/running/done）而不是业务词（queued/imported），
 * 因为同一个词要能同时描述"导入任务"和"Agent 发布"这类不同领域的对象。
 */
export type StatusKind =
  | 'pending' // 排队中（任务已提交，尚未开始）
  | 'running' // 处理中
  | 'done' // 已完成
  | 'partial' // 部分成功（有一部分失败或被跳过）
  | 'failed' // 失败
  | 'cancelled' // 已取消
  | 'draft' // 草稿（可编辑、不可被选用）
  | 'published' // 已发布（可被选用）
  | 'disabled' // 已停用（曾经可用，现在不可用）

export interface StatusMeta {
  /** 用户看到的中文。 */
  readonly label: string
  /** 语义色。映射到 Badge 的 tone。 */
  readonly tone: 'primary' | 'success' | 'warning' | 'danger' | 'info' | 'neutral'
  /**
   * 是否"进行中"。
   *
   * 为什么要单独标出：进行中的状态要显示脉冲点（让人知道还在跑），
   * 终态不需要。页面用 `isPending(kind)` 判断，而不是各自比较字符串。
   */
  readonly pending: boolean
}

export const STATUS_META: Record<StatusKind, StatusMeta> = {
  pending: { label: '等待中', tone: 'info', pending: true },
  running: { label: '处理中', tone: 'primary', pending: true },
  done: { label: '已完成', tone: 'success', pending: false },
  partial: { label: '部分成功', tone: 'warning', pending: false },
  failed: { label: '失败', tone: 'danger', pending: false },
  cancelled: { label: '已取消', tone: 'info', pending: false },
  draft: { label: '草稿', tone: 'neutral', pending: false },
  published: { label: '已发布', tone: 'success', pending: false },
  disabled: { label: '已停用', tone: 'warning', pending: false }
}

/**
 * 后端枚举 → {@link StatusKind}。
 *
 * 存在的意义：页面手上拿到的往往是**后端的原始枚举**
 * （`task.status === 'PARTIAL'`、`agent.status === 'DISABLED'`），
 * 让它们自己 `switch` 一遍就又出现了 N 份映射。
 *
 * 认不出来的值一律回落到 `failed` 而不是 `done`：
 * 未知状态**不能**被显示成"已完成"——那会让用户以为成功了，而它可能根本没跑完。
 * 要看原始值用 `unknownStatusLabel()`。
 */
const BACKEND_TO_KIND: Record<string, StatusKind> = {
  // 导入任务
  QUEUED: 'pending',
  RUNNING: 'running',
  DONE: 'done',
  PARTIAL: 'partial',
  FAILED: 'failed',
  CANCELLED: 'cancelled',
  // Agent
  DRAFT: 'draft',
  PUBLISHED: 'published',
  DISABLED: 'disabled'
}

export function toStatusKind(backendStatus: string): StatusKind {
  return BACKEND_TO_KIND[backendStatus] ?? 'failed'
}

/** 进行中（该显示脉冲点、不该显示"完成"）。 */
export function isPending(kind: StatusKind): boolean {
  return STATUS_META[kind].pending
}

/**
 * 认不出的状态值怎么显示。
 *
 * 刻意**原样返回后端的值**：把它显示成"未知状态"会让人以为系统坏了，
 * 而原样透出能让排查的人一眼看到服务端到底返了什么。
 */
export function unknownStatusLabel(backendStatus: string): string {
  return backendStatus
}
