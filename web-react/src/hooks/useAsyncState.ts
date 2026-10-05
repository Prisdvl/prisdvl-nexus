import { useCallback, useEffect, useRef, useState } from 'react'
import { getErrorMessage, isApiError } from '@/lib/errors'

/**
 * 通用异步数据流。
 *
 * ## 解决的问题：四态判断散落各处
 *
 * 没有它的时候，每个页面都写一遍：
 * ```ts
 * const [data, setData] = useState(null)
 * const [loading, setLoading] = useState(false)
 * const [error, setError] = useState(null)
 * // …然后每次请求都要记得三件事：开始前清 error、失败时处理、结束要置 loading
 * ```
 * 而"忘记某一件"是必然的——Vue 侧审计出的问题里有一类正是
 * "错误状态没被处理，于是落到 `v-if="length === 0"` 显示成『没有数据』"。
 *
 * 本 hook 把四态收敛成一个 `state` 字段，与 R1 的
 * `StateEmpty` / `StateError` / `StateLoading` **一一对应**。
 *
 * ## 用法
 * ```ts
 * // 依赖由调用方显式给出——`fn` 用闭包捕获参数
 * const kb = useAsyncState((signal) => getKnowledgeBase(id, signal), [id])
 * kb.state === 'loading' ? <StateLoading /> : kb.data ? <Detail /> : null
 *
 * // 不自动执行（例如要等用户点了搜索）
 * const search = useAsyncState((signal) => listAgents({ keyword }, signal), [], {
 *   immediate: false
 * })
 * search.execute({ keyword: 'java' })
 * ```
 */
export type AsyncState = 'idle' | 'loading' | 'success' | 'error'

export interface UseAsyncStateOptions {
  /** 关闭自动执行，由调用方自己 `execute()`。 */
  immediate?: boolean
}

export interface UseAsyncStateResult<T, A extends unknown[]> {
  data: T | null
  state: AsyncState
  error: string | null
  loading: boolean
  /** 发起一次请求。 */
  execute: (...args: A) => Promise<void>
  /** 重跑一次（不带参数）。用于"重试"按钮。 */
  retry: () => Promise<void>
  /** 清空回到 idle。 */
  reset: () => void
  /** 局部改数据（例如乐观更新），不重新请求。 */
  setData: (updater: T | ((prev: T | null) => T | null)) => void
}

export function useAsyncState<T, A extends unknown[] = []>(
  fn: (signal: AbortSignal, ...args: A) => Promise<T>,
  deps: readonly unknown[],
  options: UseAsyncStateOptions = {}
): UseAsyncStateResult<T, A> {
  const { immediate = true } = options

  const [data, setData] = useState<T | null>(null)
  const [state, setState] = useState<AsyncState>('idle')
  const [error, setError] = useState<string | null>(null)

  // 竞态防护用的自增序号
  const seq = useRef(0)
  // 组件是否还挂着。卸载后不再 setState
  const alive = useRef(true)
  // 持有 AbortController，参数变化/卸载时能真正 abort 上一次
  const controllerRef = useRef<AbortController | null>(null)

  // fn 每次渲染都是新函数（闭包），用 ref 存住最新的，避免把它放进依赖
  const fnRef = useRef(fn)
  fnRef.current = fn

  useEffect(() => {
    alive.current = true
    return () => {
      alive.current = false
      // 卸载时**真的取消**在途请求，而不是让它跑完再丢弃结果
      controllerRef.current?.abort()
    }
  }, [])

  const execute = useCallback(async (...args: A) => {
    const mySeq = ++seq.current

    // 上一次还没跑完就再发起 → 先取消它，否则两个响应会互相覆盖
    controllerRef.current?.abort()
    const ctrl = new AbortController()
    controllerRef.current = ctrl

    setState('loading')
    setError(null)

    try {
      const result = await fnRef.current(ctrl.signal, ...args)
      // 过期响应直接丢弃：**不看内容**，否则旧结果会覆盖新结果
      if (mySeq !== seq.current || !alive.current) return
      setData(result)
      setState('success')
    } catch (e) {
      if (mySeq !== seq.current || !alive.current) return
      // 取消不是错误：静默回到 idle，而不是显示"加载失败"
      if (isApiError(e) && e.isAborted) {
        setState('idle')
        return
      }
      // 失败时清掉旧数据——留着旧值继续显示就是撒谎
      setData(null)
      setError(getErrorMessage(e))
      setState('error')
    } finally {
      if (mySeq === seq.current) controllerRef.current = null
    }
  }, [])

  const retry = useCallback(async () => {
    await execute(...([] as unknown as A))
  }, [execute])

  const reset = useCallback(() => {
    seq.current++ // 让在途响应失效
    controllerRef.current?.abort()
    setData(null)
    setError(null)
    setState('idle')
  }, [])

  // 首次 / 依赖变化时自动执行。
  // 用 ref 存 execute（它是 useCallback([]) 出来的，引用稳定），
  // 所以依赖数组里放它不会引起额外重跑。
  const executeRef = useRef(execute)
  executeRef.current = execute

  useEffect(() => {
    // `A` 是泛型，TS 无法假定"可以不传参数"——自动执行时按空参数调用。
    if (immediate) void executeRef.current(...([] as unknown as A))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [immediate, ...deps])

  return { data, state, error, loading: state === 'loading', execute, retry, reset, setData }
}
