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
 */
export interface ApiError extends Error {
  status?: number
  code?: number
}

const TOKEN_KEY = 'nexus.token'

/**
 * 令牌的<b>响应式</b>镜像。
 *
 * <p>踩过的坑：localStorage 不是响应式的。`App.vue` 的 `showShell` 曾经直接调
 * `getToken()`，而它读的是 localStorage —— 这个 computed 因此<b>没有任何依赖</b>，
 * 值只在首次求值时算一次并永久缓存。后果是登录成功后外壳（侧栏 + 主区）不挂载、
 * 页面看起来"样式全乱"（因为只剩一个没有布局的裸列表），必须 F5 整页刷新才恢复。
 *
 * <p>修法不是在 App.vue 里补一个 watch（那只是把症状挪个地方），而是让令牌本身可被追踪：
 * 所有读写都经过这里，写入时同步更新 ref，computed / 模板自然会在登录、退出、
 * 401 过期时立即重算。
 */
export const tokenRef = ref(readToken())

function readToken(): string {
  return localStorage.getItem(TOKEN_KEY) ?? ''
}

export function getToken(): string {
  return tokenRef.value
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
  tokenRef.value = token
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
  tokenRef.value = ''
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
      ElMessage.error(body.message || `请求失败（错误码 ${body.code}）`)
      return Promise.reject(new Error(body.message))
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
    const failure = new Error(body?.message || error.message || '网络错误') as ApiError
    failure.status = status
    failure.code = body?.code
    return Promise.reject(failure)
  }
)

export default client
