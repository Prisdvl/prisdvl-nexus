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
  /**
   * 昵称（5.6）。刻意<b>不落 localStorage</b>：它和 username 不一样，
   * username 是"令牌属于谁"的凭据性信息，昵称只是展示用的资料。
   * 缓存下来就会出现"我明明改了昵称，另一台设备上还是旧的"——
   * 它由 App.vue 挂载时拉一次 `GET /users/me` 填上，改了资料后本地立即同步。
   */
  nickname: '',
  roles: [] as string[]
})

/** 侧栏显示名：昵称优先，没设昵称就回退到用户名（服务端的"清空昵称"语义就落在这一行）。 */
export function displayName(): string {
  return session.nickname || session.username
}

export function isLoggedIn(): boolean {
  return getToken().length > 0
}

export function saveSession(token: string, username: string): void {
  setToken(token)
  localStorage.setItem('nexus.username', username)
  session.username = username
  // 换了账号，上一个账号的昵称必须立刻消失：留着就是"登成别人的名字"
  session.nickname = ''
  session.roles = []
}

/** 用 `GET /users/me` 的返回把会话补齐（昵称 / 角色）。 */
export function applyProfile(profile: { username: string; nickname: string | null; roles: string[] }): void {
  session.username = profile.username
  localStorage.setItem('nexus.username', profile.username)
  session.nickname = profile.nickname ?? ''
  session.roles = profile.roles ?? []
}

export function logout(): void {
  clearToken()
  localStorage.removeItem('nexus.username')
  session.username = ''
  session.nickname = ''
  session.roles = []
}
