/**
 * 导入进度模型（纯函数，不依赖 React / 网络）。
 *
 * <p>这一层的存在意义是<b>不编造百分比</b>。后端对导入任务给出的 `progress`
 * 是"已处理文件数 / 总文件数"（见 ImportTaskExecutor#updateProgress，运行期封顶 99，
 * 全部结束才写 100），所以：
 * <ul>
 *   <li>单文件任务（{@code totalFiles === 1}）从上传到结束只有 0 → 100 两个点，
 *       中间那一段没有任何真实刻度，一律走不确定进度条；</li>
 *   <li>多文件任务才有真实比例，直接显示后端给的那个数；</li>
 *   <li>上传这一步另有一条真实来源：浏览器上报的已发送字节数（onUploadProgress），
 *       只有它给出了 total 才谈得上百分比，否则同样是不确定态。</li>
 * </ul>
 *
 * <p>轮询次数、经过时间、切片数都不参与进度计算——它们不是"处理到哪儿了"的度量。
 */

/** 一个文件在界面上的状态。与后端的状态机不是一回事，故单独命名。 */
export type ImportPhase = 'queued' | 'uploading' | 'processing' | 'done' | 'skipped' | 'failed'

/** 任务视图里本模型用得到的字段（与 ImportTaskResponse 对齐，可选以免强依赖） */
export interface ImportTaskLike {
  status?: string | null
  totalFiles?: number | null
  progress?: number | null
  errorMsg?: string | null
}

/** 明细里本模型用得到的字段（与 ImportTaskItemView 对齐） */
export interface ImportItemLike {
  sourcePath?: string | null
  action?: string | null
  message?: string | null
}

/** 后端 ImportTaskStatus 的终态 */
const TERMINAL_STATUSES = new Set(['DONE', 'PARTIAL', 'FAILED', 'CANCELLED'])

/** 任务是否已到终态（QUEUED / RUNNING 之外的都算） */
export function isTerminalStatus(status?: string | null): boolean {
  return TERMINAL_STATUSES.has((status ?? '').toUpperCase())
}

/**
 * 终态任务 → 行状态。
 *
 * <p>调用方应优先用明细（action）判定单个文件；这里只是"明细查不到时"的兜底。
 * PARTIAL 按保守方向显示为有问题的行：单文件任务不可能出现 PARTIAL，
 * 真出现说明任务里不止一个文件，此时把该文件说成"成功"更不负责任。
 */
export function phaseOfStatus(status?: string | null): ImportPhase {
  switch ((status ?? '').toUpperCase()) {
    case 'DONE':
      return 'done'
    case 'PARTIAL':
      return 'failed'
    case 'FAILED':
    case 'CANCELLED':
      return 'failed'
    default:
      return 'processing'
  }
}

/** 明细 action（ImportAction）→ 行状态；未知 action 不外推成成功 */
export function phaseOfAction(action?: string | null): ImportPhase {
  switch ((action ?? '').toUpperCase()) {
    case 'CREATED':
    case 'UPDATED':
      return 'done'
    case 'SKIPPED':
    case 'DELETED':
      return 'skipped'
    case 'FAILED':
      return 'failed'
    default:
      return 'processing'
  }
}

/**
 * 后端真比例：只有多文件任务才把它当百分比用。
 * 单文件任务给 null —— 显示 0%/100% 之外没有别的真实刻度。
 */
export function fileRatio(task?: ImportTaskLike | null): number | null {
  if ((task?.totalFiles ?? 0) <= 1) return null
  const progress = task?.progress
  if (typeof progress !== 'number' || !Number.isFinite(progress)) return null
  return Math.max(0, Math.min(100, Math.round(progress)))
}

export type BarModel = { kind: 'determinate'; percent: number } | { kind: 'indeterminate' }

/** 进度条模型：有真数才给数字，没有就是不确定态 */
export function barOf(input: { uploadPercent?: number | null; task?: ImportTaskLike | null }): BarModel {
  const upload = input.uploadPercent
  if (typeof upload === 'number' && Number.isFinite(upload)) {
    return { kind: 'determinate', percent: Math.max(0, Math.min(100, Math.round(upload))) }
  }
  const ratio = fileRatio(input.task)
  if (ratio !== null) return { kind: 'determinate', percent: ratio }
  return { kind: 'indeterminate' }
}

/** 取路径里的文件名（Vault 明细里的 sourcePath 可能带目录） */
export function baseName(path: string): string {
  const index = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'))
  return index >= 0 ? path.slice(index + 1) : path
}

/**
 * 从明细里挑出属于这个文件的那一条。
 *
 * <p>优先级：同名 → 明细只有一条时就用它 → 挑不出来（例如 ZIP 批量导入的明细
 * 与界面上的单文件不是一对一，那种入口不在本界面的职责里）。
 */
export function pickItem<T extends ImportItemLike>(
  items: T[] | null | undefined,
  fileName: string,
): T | null {
  if (!items || items.length === 0) return null
  const target = baseName(fileName)
  const hit = items.find((item) => baseName(item.sourcePath ?? '') === target)
  if (hit) return hit
  return items.length === 1 ? items[0] : null
}
