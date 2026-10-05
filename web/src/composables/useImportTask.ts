import { computed, ref, type Ref } from 'vue'
import { getImportTask, type ImportTask } from '@/api/knowledge'

/**
 * 导入任务的轮询 —— 一处实现，两个页面共用。
 *
 * ## 它收掉的重复（Phase A §7）
 *
 * 改造前两处各写一遍轮询：
 * - `KnowledgeBaseListView.vue`：`pollTask()`，240 次 × 1.2s 轮询到终态
 * - `KnowledgeBaseDetailView.vue`：详情页自己的轮询
 *
 * 两份的**终态判定、间隔、超时、失败处理**必须人工保持一致，
 * 而这类"两份相似逻辑"最常见的结局是：改了一处忘了另一处。
 *
 * ## 终态的四个值（不是两个）
 *
 * `DONE` / `PARTIAL` / `FAILED` / `CANCELLED`。
 * 特别注意 `PARTIAL`：**部分成功仍是终态**，此时界面该显示"3 个成功 / 1 个失败"
 * 并给出原因，而不是继续转圈。当前两个页面都把 `PARTIAL` 当终态处理，
 * 这里显式写成常量集合，避免以后漏掉哪一个。
 */
const TERMINAL = new Set(['DONE', 'PARTIAL', 'FAILED', 'CANCELLED'])

/** 轮询间隔。与后端异步解析的节奏匹配：太快只会白白发请求。 */
const POLL_INTERVAL_MS = 1200

/** 最大轮询次数。240 × 1.2s = 4.8 分钟，对应 5 分钟 SSE/任务超时。 */
const MAX_POLLS = 240

export interface ImportTaskController {
  /** 当前任务快照。null 表示"没有正在跑的任务"。 */
  task: Ref<ImportTask | null>
  /** 是否在轮询中。 */
  polling: Ref<boolean>
  /** 轮询本身出错（例如网络断了）。与任务本身 FAILED 是两件事。 */
  error: Ref<string>
  /** 任务是否已到终态。 */
  finished: Ref<boolean>
  /** 是否是"部分成功"——界面该显示警告而不是成功。 */
  partial: Ref<boolean>

  /** 开始跟踪一个任务，返回终态快照（超时返回最后一次读到的）。 */
  start: (taskId: string) => Promise<ImportTask | null>
  /** 停止轮询（组件卸载、用户离开页面时调用）。 */
  stop: () => void
  /** 直接读一次任务状态（不进入轮询），用于"刷新页面后恢复显示"。 */
  refresh: (taskId: string) => Promise<ImportTask | null>
  reset: () => void
}

export function useImportTask(): ImportTaskController {
  const task = ref<ImportTask | null>(null)
  const polling = ref(false)
  const error = ref('')
  const finished = ref(false)
  const partial = ref(false)

  /** 自增序号：stop() 之后仍在途的那次 await 返回时，必须被丢弃。 */
  let seq = 0

  async function refresh(taskId: string): Promise<ImportTask | null> {
    try {
      const next = await getImportTask(taskId)
      task.value = next
      partial.value = next.status === 'PARTIAL'
      finished.value = TERMINAL.has(next.status)
      return next
    } catch (e) {
      error.value = e instanceof Error ? e.message : '导入状态查询失败'
      return null
    }
  }

  async function start(taskId: string): Promise<ImportTask | null> {
    const mine = ++seq
    error.value = ''
    finished.value = false
    partial.value = false
    polling.value = true

    for (let i = 0; i < MAX_POLLS; i++) {
      // 先等一拍再查：任务刚创建时立刻查，多半还是 QUEUED，
      // 那一次请求纯属浪费（实测约 400ms 内的重复）
      await sleep(POLL_INTERVAL_MS)
      // stop() 之后这一轮 await 才回来：必须在这里再确认一次，
      // 否则 stop() 形同虚设——组件已经卸载还在写 ref
      if (mine !== seq) return null

      const next = await refresh(taskId)
      if (mine !== seq) return null
      if (next && TERMINAL.has(next.status)) {
        polling.value = false
        return next
      }
    }

    // 超时：不是失败，是"还没到终态"。如实返回最后一次读到的，
    // 让调用方决定是继续等还是提示用户稍后刷新
    polling.value = false
    error.value = '导入处理时间较长，可稍后刷新查看进度'
    return task.value
  }

  function stop(): void {
    seq++
    polling.value = false
  }

  function reset(): void {
    stop()
    task.value = null
    error.value = ''
    finished.value = false
    partial.value = false
  }

  return { task, polling, error, finished, partial, start, stop, refresh, reset }
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

/** 任务状态 → 界面文案。集中一处，避免两个页面各写一张映射表。 */
export function importStatusLabel(status: string): string {
  const map: Record<string, string> = {
    QUEUED: '排队中',
    RUNNING: '处理中',
    DONE: '已完成',
    PARTIAL: '部分成功',
    FAILED: '失败',
    CANCELLED: '已取消'
  }
  return map[status] ?? status
}

/** 任务是否该显示进度条（排队中也该显示，用户需要知道"系统收到了"）。 */
export function importIsPending(status: string): boolean {
  return status === 'QUEUED' || status === 'RUNNING'
}

/** 供调用方直接组合用的派生：当前是否该显示"正在处理"。 */
export function useImportPending(task: Ref<ImportTask | null>) {
  return computed(() => (task.value ? importIsPending(task.value.status) : false))
}
