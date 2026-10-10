import axios from 'axios'
import { ref } from 'vue'
import { ElMessage } from 'element-plus'

/**
 * 后端统一响应包装（ADR-017）：{ code, message, data, requestId }。
 * code === 0 表示成功；其余为业务错误码。
 */
export interface Result<T> {
  code: number
  message: string
  data: T
  requestId?: string
}

/** 分页结果。 */
export interface PageResult<T> {
  list: T[]
  total: number
  pageNum: number
  pageSize: number
}

/**
 * 请求失败时抛出的错误：`message` 是**后端原话**（可直接展示给用户），
 * `status` / `code` 保留 HTTP 状态与业务码，供需要分支处理的调用方使用。
 *
 * Phase C §十四 之后，`code` 的用途从"锦上添花"变成**页面分级提示的依据**：
 * 页面据此判断该给"重试"（网络/5xx，可重试）还是只说一句"重试也没用"
 * （`1001` 这类参数校验失败）。
 */
export interface ApiError extends Error {
  status?: number
  code?: number
}

/** 构造一个带业务码的错误。两条失败路径（业务错误 / HTTP 错误）都用它，保证形状一致。 */
export function toApiError(message: string, code?: number, status?: number): ApiError {
  const failure = new Error(message) as ApiError
  failure.name = 'ApiError'
  failure.code = code
  failure.status = status
  return failure
}

/**
 * 参数类错误：重试多少次结果都一样，界面**不该**给"重试"动作。
 *
 * 1001 是服务端约定的参数校验失败（见 `docs/update.txt` 的错误码表）。
 * 放在这里而不是每个页面各判一次，是为了让"哪些错误不可重试"只有一处定义。
 */
export function isValidationError(e: unknown): boolean {
  return typeof e === 'object' && e !== null && (e as ApiError).code === 1001
}

/*
 * 令牌的读写**已迁到 `stores/auth.ts`**（Phase C §八 E：token 只保留一份真相）。
 *
 * 这里保留同名函数是为了让既有调用点（LoginView / App.vue / router）一行都不用改——
 * 兼容层内部全部转发到 store，本文件**不再持有任何令牌状态**。
 *
 * 迁移前的历史（保留是因为它解释了"为什么必须是响应式的"）：
 * 令牌曾经是本文件里的一个 `ref`。更早之前它直接读 localStorage，
 * 而 `App.vue` 的 `showShell` computed 依赖它 —— localStorage 不是响应式的，
 * 那个 computed 因此没有任何依赖、值只在首次求值时算一次并永久缓存。
 * 后果：登录成功后外壳（侧栏 + 主区）不挂载，页面看起来像"样式全乱"
 * （只剩一个没有布局的裸列表），必须 F5 整页刷新才恢复。
 *
 * 教训：**令牌必须可被追踪**，且只有一份。现在它在 store 里，
 * 既响应式又单一真相，两个问题同时消掉。
 */
import { useAuthStore } from '@/stores/auth'

function authStore() {
  return useAuthStore()
}

export function getToken(): string {
  return authStore().token
}

export function setToken(token: string): void {
  authStore().$patch({ token })
  // $patch 不会走 store 里的 setToken（那会再递归一次），所以这里补上副作用：
  // 令牌必须落盘，否则刷新页面就掉登录态。
  if (token) localStorage.setItem('nexus.token', token)
}

export function clearToken(): void {
  setToken('')
}

const client = axios.create({
  baseURL: '/api',
  // 向量化/建索引是同步接口，第一次调用可能包含模型加载，给足超时
  timeout: 180_000
})

client.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

client.interceptors.response.use(
  (response) => {
    const body = response.data as Result<unknown>
    // 兼容非包装响应（理论上不存在，但防御一下会让排查更简单）
    if (!body || typeof body.code !== 'number') {
      return body as never
    }
    if (body.code !== 0) {
      /*
       * 业务错误**不在这里弹提示**（Phase C §十四）。
       *
       * 改造前这里弹一次、调用方的 catch 又弹一次，同一件事用户看到两条。
       * 而 catch 那边说的话更有用——它知道"是发布失败还是删除失败"，
       * 这里只知道服务端说了什么。所以职责反过来：**这里负责把 code 带出去，
       * 由页面决定怎么呈现**（一次性 toast，或常驻的 StateError + 重试）。
       *
       * 仍然会弹的只有下面 error 分支里的两类：401（要自动跳登录）
       * 与没有 HTTP 响应的网络错误（页面无从判断，且不属于业务错误）。
       */
      return Promise.reject(toApiError(body.message ?? `请求失败（错误码 ${body.code}）`, body.code))
    }
    // 直接返回业务数据，调用方不必层层 .data.data
    return body.data as never
  },
  (error) => {
    const status = error.response?.status
    const body = error.response?.data as Result<unknown> | undefined
    if (status === 401) {
      clearToken()
      ElMessage.warning('登录已过期，请重新登录')
      if (!location.hash.startsWith('#/login')) {
        location.hash = '#/login'
      }
    } else {
      ElMessage.error(body?.message || error.message || '网络错误')
    }
    /*
     * 抛出的错误必须带上**后端原话**，而不是 axios 那句 HTTP 状态描述。
     *
     * 踩过的坑（Phase 4.2 试跑功能）：后端按错误码映射 HTTP 状态（4006 → 502），
     * axios 由此抛出 `Request failed with status code 502`。界面上想"把失败原因显示在
     * 弹窗里而不是弹个 3 秒就消失的提示"时，拿到的就是这句毫无信息量的话——
     * 而真正有用的"模型 opencode:xxx 未注册。已注册：[ollama:qwen3:7b]"在响应体里被丢掉了。
     * 状态码仍挂在 error.status 上，需要区分 404/409 的调用方不必回去翻 response。
     */
    return Promise.reject(
      toApiError(body?.message || error.message || '网络错误', body?.code, status)
    )
  }
)

export default client
