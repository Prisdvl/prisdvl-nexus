/**
 * 认证态（token 存localStorage，见 docs/frontend-integration-decisions.md 决策 1）
 *
 * ## 为什么不引入 `unknown` 态
 *
 * 接后端最容易犯的错，是把"判断登录态"改成异步（拿令牌去后端问一次）。
 * 那样`RequireAuth` 就必须在 unknown 期间做选择，而**无论选哪个都会出问题**：
 * 选跳登录 → 刷新页面必然闪一下登录页；选放行 → 未登录用户能短暂看到空壳页面。
 *
 * 本实现刻意不走那条路：**令牌本身存在 localStorage 里，同步可读**，
 * 所以 `isAuthed` 仍然是同步返回值，`RequireAuth` 的结构完全不用动。
 * 「不闪登录页」这个现有性质被完整保留。
 *
 * ## 过期怎么办
 *
 * Access 短寿命；本地有 refresh 时由 `api.ts` 拦截器单飞刷新并重放请求。
 * refresh 失败或双令牌皆无时再清态跳登录。
 * 过期判定：
 *   - 本模块：有 access 或 refresh 即视为已登录（避免刷新页因 access 刚过期被踢）；
 *   - `api.ts` 请求拦截器：access 过期就不带 Authorization，由 401 → refresh 兜底。
 */
import { useSyncExternalStore } from 'react'
import { api, bumpAuthGeneration, errMessage, tokenStore, USE_MOCK, setUnauthorizedHandler } from '@/lib/api'

/** 旧版原型留下的标记；新版以 token 为准，读到它时做一次性迁移 */
const LEGACY_KEY = 'nx-proto-auth'

type Listener = () => void
const listeners = new Set<Listener>()

/**
 * 是否已登录。
 *
 * 两条路径：
 * 1. **有真实 access 或 refresh** → 已登录（正常路径）。
 * 2. **只有旧版`nx-proto-auth='1'` 标记** → **仅在 `USE_MOCK` 下**算已登录。
 *
 * ⚠️ 为什么 mock 下要认这个标记：16 阶段 QA 门禁全部靠
 * `localStorage['nx-proto-auth']='1'` 预置来跳过登录（scripts/*.mjs 里有十几处）。
 * 若只认 token，所有门禁都会跳登录页 → 找不到 `.shell` → 全线失败。
 *而真实模式下**绝不**认它：那只是一个本地字符串，任何人手动设一下就能"登录"，
 * 后端一拒就401，等于绕过了"必须有令牌"这条底线。
 */
function computeAuthed(): boolean {
  return (
    tokenStore.get() !== '' ||
    tokenStore.getRefresh() !== '' ||
    (USE_MOCK && localStorage.getItem(LEGACY_KEY) === '1')
  )
}

let authed = computeAuthed()

/** 未登录原因，供登录页提示区分 */
export type LogoutReason = 'manual' | 'expired' | null
let lastLogoutReason: LogoutReason = null

const emit = () => listeners.forEach((l) => l())
const subscribe = (l: Listener) => {
  listeners.add(l)
  return () => listeners.delete(l)
}

/**
 * 真实登录成功后清掉旧标记（避免它长期留在 localStorage 里误导）。
 *
 * ⚠️ mock 模式下**必须保留**这个键 —— QA 门禁的每个脚本都会重新写入它，
 * 这里删掉会让下一次门禁跑不起来。
 */
function migrateLegacyFlag() {
  if (
    !USE_MOCK &&
    localStorage.getItem(LEGACY_KEY) === '1' &&
    !tokenStore.get() &&
    !tokenStore.getRefresh()
  ) {
    localStorage.removeItem(LEGACY_KEY)
  }
}
migrateLegacyFlag()

export const authStore = {
  isAuthed: () => authed,
  lastReason: () => lastLogoutReason,

  /**
   * 真实登录：调后端拿 access + refresh。
   *
   * ⚠️ 后端 register 的校验规则（实测）：用户名 3–32 且 `^[A-Za-z0-9_-]+$`、
   * 密码 8–64。返回 1001 是参数校验失败，message 形如 `username: 用户名长度需在 3–32 之间`。
   */
  login: async (username: string, password: string, totpCode?: string) => {
    if (USE_MOCK) {
      /* 离线演示：保留旧的原型行为——只置标记，不发请求 */
      localStorage.setItem(LEGACY_KEY, '1')
      authed = true
      lastLogoutReason = null
      emit()
      return
    }
    const token = await api.auth.login(username, password, totpCode)
    tokenStore.save(token.accessToken, token.expiresIn, token.refreshToken)
    authed = true
    lastLogoutReason = null
    emit()
  },

  /**
   * 只注册，**不自动登录**。
   *
   * ⚠️ 为什么不是"注册完顺手登录"：后端 register 返回的是 user、**不返令牌**，
   * 要拿令牌必须再调一次 login。而一旦登录成功，`RedirectIfAuthenticated`
   * 立刻把 /login 弹到工作台 —— 用户根本没机会确认"注册成功"，
   * 也没机会看到自己刚注册的账号名。
   * 所以这里只建号，由用户在登录页主动登录（多一次输入，换来流程可预期）。
   */
  register: async (body: { username: string; password: string; email: string; nickname?: string }) => {
    if (USE_MOCK) throw new Error('注册需要真实后端；请关闭离线测试模式后重试。')
    await api.auth.register(body)
  },

  logout: async () => {
    // 先捕获 refresh，立刻清本地并 bump 世代，避免并发 refresh 写回令牌
    const refreshToken = !USE_MOCK ? tokenStore.getRefresh() : ''
    bumpAuthGeneration()
    tokenStore.clear()
    /* mock 下不清 legacy：QA 脚本只写这一个键，删了等于自己把门禁弄挂 */
    if (!USE_MOCK) localStorage.removeItem(LEGACY_KEY)
    authed = false
    lastLogoutReason = 'manual'
    emit()
    if (refreshToken) {
      try {
        await api.auth.logout(refreshToken)
      } catch {
        // 服务端会话可能已失效；本地已清态
      }
    }
  },

  /** 由 api.ts 的 401 拦截器在 refresh 失败后调用 */
  handleUnauthorized: () => {
    if (!authed) return
    bumpAuthGeneration()
    tokenStore.clear()
    /* mock 下不清 legacy：QA 脚本只写这一个键，删了等于自己把门禁弄挂 */
    if (!USE_MOCK) localStorage.removeItem(LEGACY_KEY)
    authed = false
    lastLogoutReason = 'expired'
    emit()
  },
}

setUnauthorizedHandler(authStore.handleUnauthorized)

/** 仅注册（App.tsx 的 onRegister 用）。独立导出便于静态 import。 */
export async function registerAccount(body: { username: string; password: string; email: string; nickname?: string }) {
  return authStore.register(body)
}

export function useAuth() {
  const isAuthed = useSyncExternalStore(subscribe, authStore.isAuthed, authStore.isAuthed)
  return { isAuthed, login: authStore.login, logout: authStore.logout, lastReason: authStore.lastReason }
}

export { errMessage }
