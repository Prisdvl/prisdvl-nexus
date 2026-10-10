import { computed, ref, shallowRef, type Ref } from 'vue'

/**
 * 统一的"取一次数据"逻辑。
 *
 * ## 它解决的真问题（Phase B §13.2）
 *
 * 改造前的每个面板都是手搓的四件套：
 *
 * ```ts
 * const loading = ref(false)
 * const error = ref('')
 * const data = ref<X | null>(null)
 * async function load() { loading.value = true; try { … } catch (e) { error.value = … } finally { loading.value = false } }
 * ```
 *
 * 于是**"加载失败"和"没有数据"在代码里长得一样**——两者都是 `data === null`。
 * Phase A §6.3 记录的后果：详情页加载失败只有一个 3 秒 toast，
 * toast 一消失就落到 `v-if="…length === 0"` 的"没有匹配的文档"，
 * **用户最终看到的是"这里没有数据"**。
 *
 * 本 composable 把四态显式化，`state` 的取值与 `StateEmpty` / `StateError` /
 * `StateLoading` 三个组件一一对应，调用方不再自己判断。
 *
 * ## 四态的判别式只有一条
 *
 * > **服务端有没有说成功。**
 *
 * 成功但数据为空 → `empty`（给"还没有…" + 一个下一步动作）
 * 没成功 → `error`（给"…失败了" + [重试]）
 *
 * 二者的文案**不允许互相越界**，这是组件层强制的（见 StateEmpty 的 action 警告）。
 *
 * ## 用法
 *
 * ```ts
 * const { state, data, error, execute } = useAsyncState(() => listKnowledgeBases())
 * onMounted(execute)
 * ```
 *
 * ```vue
 * <StateLoading v-if="state === 'loading'" variant="list" />
 * <StateError v-else-if="state === 'error'" :description="error" @retry="execute" />
 * <StateEmpty v-else-if="state === 'empty'" title="还没有知识库">
 *   <template #action><el-button type="primary">新建知识库</el-button></template>
 * </StateEmpty>
 * <div v-else>{{ data }}</div>
 * ```
 */
export type AsyncState = 'idle' | 'loading' | 'error' | 'empty' | 'success'

export interface UseAsyncStateOptions<T> {
  /**
   * 判空函数。默认 `length === 0` 或 `null/undefined`。
   *
   * 需要它是因为"空"的含义随数据形状变：
   * 数组是 `length === 0`，数字是 `=== 0`，对象可能是 `Object.keys().length === 0`。
   */
  isEmpty?: (data: T) => boolean
  /** 首次是否自动执行。默认 false——由调用方决定何时加载（onMounted / watch）。 */
  immediate?: boolean
}

export function useAsyncState<T>(
  fetcher: () => Promise<T>,
  options: UseAsyncStateOptions<T> = {}
) {
  const { isEmpty, immediate = false } = options

  const data = shallowRef<T | null>(null)
  const loading = ref(false)
  const error = ref('')
  /** 是否已经跑过一次——用来区分 `empty`（跑过了，没数据）与 `idle`（还没跑）。 */
  const settled = ref(false)

  /**
   * 竞态防护序号。
   *
   * 问题：快速切换筛选时，先发的请求可能**后**回来，把后发的结果覆盖掉——
   * 于是界面显示的是上一次查询的数据，而用户以为看到的是刚点的那个。
   *
   * 每次 execute 自增；请求返回时若序号已变，说明期间又发起了新一轮，
   * 这次的结果直接丢弃。用序号而不是 AbortController，是为了让调用方
   * 不必管理取消逻辑（大多数场景压根不需要真的中断请求）。
   */
  let latest = 0

  const isEmptyData = (value: T): boolean => {
    if (isEmpty) return isEmpty(value)
    if (value == null) return true
    if (Array.isArray(value)) return value.length === 0
    return false
  }

  /**
   * 四态。
   *
   * 顺序有意为之：`loading` 先判（请求在途时即便有旧数据也该显示加载态），
   * 再判 `error`（失败优先于空——数据没拿到时"空"是误导），
   * 最后才判 `empty` / `success`。
   */
  const state = computed<AsyncState>(() => {
    if (loading.value) return 'loading'
    if (error.value) return 'error'
    if (!settled.value) return 'idle'
    return data.value != null && !isEmptyData(data.value) ? 'success' : 'empty'
  })

  async function execute(): Promise<T | null> {
    // 竞态防护：上一轮请求晚回来会把新一轮的结果覆盖掉。
    // 用自增序号而非 AbortController，是为了让调用方不必管理取消逻辑。
    const seq = ++latest
    loading.value = true
    error.value = ''
    try {
      const result = await fetcher()
      if (seq !== latest) return null
      data.value = result
      return result
    } catch (e) {
      if (seq !== latest) return null
      // 保留服务端原话：它是判断"是没权限还是网络断了"的唯一依据
      error.value = e instanceof Error ? e.message : '加载失败'
      // 失败时**清掉**旧数据：留着它会让界面显示"成功时的旧内容"，
      // 而用户以为看到的是刚拉到的结果
      data.value = null
      return null
    } finally {
      if (seq === latest) {
        loading.value = false
        settled.value = true
      }
    }
  }

  /** 手动换数据（乐观更新用，例如删除后立刻从列表里去掉）。 */
  function set(value: T | null): void {
    data.value = value
    settled.value = true
    error.value = ''
  }

  function reset(): void {
    data.value = null
    loading.value = false
    error.value = ''
    settled.value = false
  }

  if (immediate) {
    void execute()
  }

  return { data: data as Ref<T | null>, loading, error, state, execute, set, reset }
}
