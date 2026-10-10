/**
 * 浏览器存储的统一入口（R3 任务书 §7）。
 *
 * ## 为什么要这一层
 *
 * 否则会散落成 `localStorage.getItem('nexus.token')` 出现在各个页面里——
 * 于是键名写错一次要到运行时才发现，而且**每个调用点都要自己 try/catch**
 * （无痕模式、禁用 cookie、配额满时 `localStorage` 会抛）。
 *
 * ## 为什么用 localStorage 而不是 sessionStorage
 *
 * 与 Vue 侧保持一致（`nexus.token`），这样两套前端在同一浏览器上表现相同，
 * 迁移期切换前端不会莫名其妙掉登录态。
 *
 * ## 键名沿用 Vue 侧
 *
 * `nexus.token` / `nexus.username` 是**后端与前端约定俗成的一组键**，
 * R0/R1/R2 已在用（侧栏折叠键 `nexus.sidebar.collapsed` 也是同族）。
 * 改名会让"两套前端并存"期间的行为不一致。
 */

const TOKEN_KEY = 'nexus.token'
const USERNAME_KEY = 'nexus.username'

/**
 * 底层读写，**全部包 try/catch**。
 *
 * `localStorage` 在三种情况会抛：Safari 无痕模式、用户禁用站点数据、存储配额满。
 * 抛出方向都是"用不了"，所以统一回退到安全值（读 → null，写 → 静默失败）。
 */
function readRaw(key: string): string | null {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

function writeRaw(key: string, value: string | null): void {
  try {
    if (value === null) localStorage.removeItem(key)
    else localStorage.setItem(key, value)
  } catch {
    // 写不进去不是致命问题：本次会话内状态仍正确（由 store 持有），
    // 只是刷新后丢失。**不在这里抛**——否则每个调用点都要处理它。
  }
}

/** 读取访问令牌。未登录返回 `null`（不是空串——两者语义不同）。 */
export function getToken(): string | null {
  return readRaw(TOKEN_KEY)
}

export function setToken(token: string): void {
  writeRaw(TOKEN_KEY, token)
}

export function removeToken(): void {
  writeRaw(TOKEN_KEY, null)
}

/**
 * 读取上次登录的用户名。
 *
 * **只用于"登录前预填表单"这类纯展示场景**，绝不作为 `currentUser` 的来源——
 * 用户资料必须来自 `GET /api/users/me`（R3 任务书 §8）。
 * Vue 侧正是在这里出过「欢迎回来 undefined」。
 */
export function getLastUsername(): string | null {
  return readRaw(USERNAME_KEY)
}

export function setLastUsername(username: string | null): void {
  writeRaw(USERNAME_KEY, username)
}

/** 清空全部与本应用相关的键。退出登录时调用。 */
export function clearAuthStorage(): void {
  removeToken()
  setLastUsername(null)
}

/**
 * 键名常量导出，供需要操作**非认证**键时统一引用。
 *
 * 现在只有 R2 的 `nexus.sidebar.collapsed`，它由 `AppSidebar` 自己管理。
 * 放在这里是为了让"这个应用往浏览器里写了什么"有一个总账。
 */
export const STORAGE_KEYS = {
  token: TOKEN_KEY,
  username: USERNAME_KEY,
  sidebarCollapsed: 'nexus.sidebar.collapsed'
} as const
