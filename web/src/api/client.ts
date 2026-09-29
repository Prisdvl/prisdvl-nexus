import axios from 'axios'
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

const TOKEN_KEY = 'nexus.token'

export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) ?? ''
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
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
    return Promise.reject(error)
  }
)

export default client
