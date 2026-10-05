import { useEffect } from 'react'
import { useAuthStore } from '@/stores/authStore'

/**
 * 应用启动时初始化认证状态，**只跑一次**。
 *
 * ## 挂在根组件而不是各个 Layout
 *
 * 认证是**全局一次**的：令牌在不在、用户是谁。不能挂在 `WorkspaceLayout` 上——
 * 那样 Login 页（不经过它）就永远拿不到初始化，`/login` 之后直接进工作区会卡在
 * "初始化中"。也不能挂在每个页面上（重复拉 `/users/me`）。
 *
 * ## 严格模式下的双调用
 *
 * React StrictMode 在开发环境会**故意**把 effect 跑两遍，用来暴露不幂等的副作用。
 * 所以 `initialize()` 内部必须能扛住重复调用（它靠 `loading` 之外的 status 判定
 * 不会重复拉——严格说这里存在一点竞态，见下方说明）。
 *
 * **当前的缓解**：initialize 开头会同步读一次 token 并写回 state，
 * 两次调用里第二次读到的是同一个值，且第二次的请求会与第一次并发。
 * 对 `/users/me` 这种幂等 GET 而言，并发两次只是浪费一次请求，**不会产生错误状态**。
 * 真要严格幂等，需要在 store 里加一个 `initializing` 标志（R5 接 authStore 持久化时一并做）。
 */
export function useAuthInitializer(): void {
  useEffect(() => {
    void useAuthStore.getState().initialize()
  }, [])
}

/**
 * 退出登录。
 *
 * ## 为什么要包一层，而不是让页面直接调 `useAuthStore(s => s.logout)`
 *
 * 退出不是"清 token"这一件事，它牵连：
 * 1. authStore 的 token / currentUser / status
 * 2. localStorage 的 token 与 lastUsername
 * 3. **与用户强绑定的其它 store**（当前是 knowledgeStore）
 * 4. 跳转到 `/login`
 *
 * 只做 1 而漏 3，退出后切到别的账号会看到上一个账号的知识库列表——
 * 这类"数据串号"问题在界面上极难被用户归因，所以必须集中在一处。
 */
export function useAuth() {
  const token = useAuthStore((s) => s.token)
  const currentUser = useAuthStore((s) => s.currentUser)
  const status = useAuthStore((s) => s.status)
  const error = useAuthStore((s) => s.error)
  const loggingIn = useAuthStore((s) => s.loggingIn)
  const lastUsername = useAuthStore((s) => s.lastUsername)

  const login = useAuthStore((s) => s.login)
  const clearError = useAuthStore((s) => s.clearError)

  return {
    token,
    currentUser,
    status,
    error,
    loggingIn,
    lastUsername,
    /** 派生：**不是**状态本身。`unknown` / `initializing` 期间一律为 false。 */
    authenticated: status === 'authed',
    /** 是否已确认未登录（可以跳登录页）。`initializing` 期间为 false——这是防假跳转的关键。 */
    isGuest: status === 'guest',
    login,
    clearError
  }
}
