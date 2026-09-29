import { reactive } from 'vue'
import { clearToken, getToken, setToken } from '@/api/client'

/**
 * 极简会话状态。
 *
 * 刻意不引入 Pinia：整个前端只有"当前用户"这一份全局状态，
 * 一个 reactive 对象就够；为它加一个状态管理库属于为了架构而架构。
 */
export const session = reactive({
  username: localStorage.getItem('nexus.username') ?? '',
  roles: [] as string[]
})

export function isLoggedIn(): boolean {
  return getToken().length > 0
}

export function saveSession(token: string, username: string): void {
  setToken(token)
  localStorage.setItem('nexus.username', username)
  session.username = username
}

export function logout(): void {
  clearToken()
  localStorage.removeItem('nexus.username')
  session.username = ''
  session.roles = []
}
