import { useCallback, useEffect, useRef, useState } from 'react'
import { getImportTask, listImportTaskItems, uploadDocument } from '@/api/knowledge'
import {
  isImportFinished,
  type ImportTask,
  type ImportTaskItem
} from '@/api/types/knowledge'
import { getErrorMessage, isApiError } from '@/lib/errors'

/**
 * 导入任务轮询。
 *
 * ## 为什么后端有 items，前端就**不维护**逐文件状态（任务书 §20）
 *
 * 任务书要求「每个文件独立状态，不能用一个全局 `isUploading`」。
 * Vue 侧只有一个 `busy` ref，3 个文件里第 2 个失败时用户只看到"导入失败"，
 * **不知道是哪几个、为什么**。
 *
 * 后端的 `ImportTaskItemView` 已经给了逐文件明细（sourcePath / action / message）。
 * 所以本 hook 只做两件事：**轮询任务**，**拉明细**——逐文件状态由后端说了算。
 * 这比"前端给每个文件建状态机"可靠：后端才是真正知道每个文件发生了什么的一方。
 *
 * ## 终态是四个值不是两个
 *
 * `DONE` / `PARTIAL` / `FAILED` / `CANCELLED` 都是终态。
 * **漏掉 `PARTIAL`**，界面会对着"3 成功 2 失败"的任务一直转圈——
 * 轮询永不停止。Vue 侧的 `['DONE','PARTIAL','FAILED','CANCELLED']` 是对的，
 * 这里照抄，并抽成 `isImportFinished()` 供全项目复用。
 */
export interface UseImportTaskResult {
  task: ImportTask | null
  items: ImportTaskItem[]
  /** 正在上传（提交 FormData 那一段）。 */
  uploading: boolean
  /** 任务已提交、在后端跑（轮询中）。 */
  polling: boolean
  error: string | null
  /** 选中的文件（用于展示与"取消选择"）。 */
  pendingFiles: File[]
  /** 提交文件。**立即返回**，进度靠轮询更新。 */
  start: (kbId: string, files: File[]) => Promise<void>
  /** 取消"已选但还没传"的文件选择。 */
  clearFiles: () => void
  /** 停止轮询（组件卸载 / 离开页面）。已在途的请求会真的被 abort。 */
  stop: () => void
  /** 清空全部状态。 */
  reset: () => void
}

/** 轮询间隔。3 秒：既不刷屏，也能让用户看到进度在动。 */
const POLL_INTERVAL_MS = 3000

export function useImportTask(): UseImportTaskResult {
  const [task, setTask] = useState<ImportTask | null>(null)
  const [items, setItems] = useState<ImportTaskItem[]>([])
  const [uploading, setUploading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [pendingFiles, setPendingFiles] = useState<File[]>([])

  const seq = useRef(0)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const alive = useRef(true)
  // 上传用的 controller：切换页面时能真的中断
  const uploadCtrl = useRef<AbortController | null>(null)

  useEffect(() => {
    alive.current = true
    return () => {
      alive.current = false
      if (timer.current) clearTimeout(timer.current)
      uploadCtrl.current?.abort()
    }
  }, [])

  const stop = useCallback(() => {
    if (timer.current) {
      clearTimeout(timer.current)
      timer.current = null
    }
  }, [])

  const reset = useCallback(() => {
    stop()
    seq.current++
    setTask(null)
    setItems([])
    setError(null)
    setUploading(false)
    setPendingFiles([])
  }, [stop])

  /** 轮询一次并按需排下一次。 */
  const poll = useCallback(
    (kbId: string, taskId: string, mySeq: number) => {
      if (mySeq !== seq.current || !alive.current) return

      void (async () => {
        try {
          const t = await getImportTask(taskId)
          // 过期响应直接丢弃，否则慢响应会覆盖新任务的状态
          if (mySeq !== seq.current || !alive.current) return
          setTask(t)

          // 拉逐文件明细（失败不阻断：主状态更重要）
          try {
            const list = await listImportTaskItems(taskId)
            if (mySeq === seq.current && alive.current) setItems(list)
          } catch {
            // 明细拿不到就让它空着——**不要因为它失败而显示整个导入失败**
          }

          if (isImportFinished(t.status)) {
            // 终态：停止轮询。**PARTIAL 也在这里停**（漏了会一直转圈）
            stop()
            return
          }
          timer.current = setTimeout(() => poll(kbId, taskId, mySeq), POLL_INTERVAL_MS)
        } catch (e) {
          if (mySeq !== seq.current || !alive.current) return
          if (isApiError(e) && e.isAborted) return
          setError(getErrorMessage(e))
          stop()
        }
      })()
    },
    [stop]
  )

  const start = useCallback(
    async (kbId: string, files: File[]) => {
      if (files.length === 0) return

      const mySeq = ++seq.current
      stop()
      setError(null)
      setUploading(true)
      setItems([])
      setTask(null)

      const ctrl = new AbortController()
      uploadCtrl.current = ctrl

      /*
       * 后端端点一次只收**一个**文件（`@RequestPart("file") MultipartFile file`），
       * 所以多文件 = 发多次请求。
       *
       * **串行而不是并发**：后端对每个文件都要切片 + 调 Ollama 向量化（很慢），
       * 并发 N 个会把本地模型压垮，且用户看到"N 个都在 0%"不如"第 2 个在跑"清楚。
       *
       * 好处：**每个文件有独立的任务 id**，于是失败的那个能单独看见原因、
       * 单独重试（任务书 §20）——这比"一个任务塞 N 个文件、只给一个总结果"强得多。
       */
      const taskIds: string[] = []
      try {
        for (const f of files) {
          if (mySeq !== seq.current || !alive.current) return
          const created = await uploadDocument(kbId, f, ctrl.signal)
          taskIds.push(created.id)
        }
        if (mySeq !== seq.current || !alive.current) return

        setUploading(false)
        setPendingFiles([])

        // 展示用最后一个任务（它完成时前面的基本都完成了）；
        // 逐文件明细则把**所有**任务的 items 合并，用户能看到每个文件各自的结果。
        const lastId = taskIds[taskIds.length - 1]
        if (lastId === undefined) return
        const last = await getImportTask(lastId)
        if (mySeq !== seq.current || !alive.current) return
        setTask(last)

        const merged: ImportTaskItem[] = []
        for (const id of taskIds) {
          try {
            merged.push(...(await listImportTaskItems(id)))
          } catch {
            // 单个任务的明细拿不到就跳过，不影响其他
          }
        }
        if (mySeq === seq.current && alive.current) setItems(merged)

        if (!isImportFinished(last.status)) {
          poll(kbId, lastId, mySeq)
        }
      } catch (e) {
        if (mySeq !== seq.current || !alive.current) return
        if (isApiError(e) && e.isAborted) {
          setUploading(false)
          return
        }
        setUploading(false)
        setError(getErrorMessage(e))
      } finally {
        if (mySeq === seq.current) uploadCtrl.current = null
      }
    },
    [poll, stop]
  )

  return {
    task,
    items,
    uploading,
    polling: task !== null && !isImportFinished(task.status),
    error,
    pendingFiles,
    start,
    clearFiles: useCallback(() => setPendingFiles([]), []),
    stop,
    reset
  }
}
