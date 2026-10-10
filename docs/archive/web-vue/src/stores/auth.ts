import { defineStore } from 'pinia'
import { computed, reactive, ref } from 'vue'

/**
 * 认证状态 —— 全站唯一一份令牌与用户资料的真相。
 *
 * ## 为什么要换成 Pinia（Phase C §八 E）
 *
 * 改造前令牌与用户资料**分居两个文件**：
 * `api/client.ts` 的 `tokenRef` 管令牌，`stores/auth.ts` 的 `reactive({...})` 管资料。
 * 后果：
 * - 两处都要各自读一次 localStorage，各自判断"登着没有"；
 * - `isLoggedIn()` 要去问 client，令牌一改，路由守卫与 UI 都要各自重新求值；
 * - "登录初始化"这件事没有单一归属——App.vue 挂载时拉 profile，
 *   路由守卫同时在读 token，两条时序谁先谁后靠运气。
 *
 * `status`（三态）就是为第三条准备的：它把"**尚未判定**"和"**确定没登**"分开，
 * 消除了"profile 还没拉回来就当成游客"这类竞态。
 *
 * ## 为什么保留 `saveSession` 等函数（兼容层）
 *
 * `LoginView` / `App.vue` / `router` / `client.ts` 都已经在调
 * `saveSession` / `logout` / `displayName` / `isLoggedIn`。
 * 全部改成 `authStore.xxx` 会让 diff 扩散到四个视图——而 Phase C 明确禁止大改视图。
 * 文件末尾的兼容层保留这些函数名，内部全部转发到 store，
 * **没有第二份状态**，所以"只有一份真相"依然成立，而调用点保持稳定。
 *
 * @see client.ts —— 令牌的读写已改为转发到本 store，那里不再有第二份状态。
 */

const TOKEN_KEY = 'nexus.token'
const USERNAME_KEY = 'nexus.username'

/** 登录态是**三态**，不是两态。 */
export type AuthStatus =
  /** 还没判定（首次进入，本 store 尚未完成初始化） */
  | 'unknown'
  /** 已确认登着（令牌存在） */
  | 'authed'
  /** 已确认没登（令牌为空） */
  | 'guest'

export const useAuthStore = defineStore('auth', () => {
  /* ---------------- 令牌 ---------------- */

  /**
   * 令牌的响应式镜像。
   *
   * 必须是 ref 而不是每次直接读 localStorage：`App.vue` 的 `showShell` 曾因此
   * 没有任何响应式依赖、值被永久缓存，表现为"登录成功后外壳不挂载，
   * 页面看起来像没布局，必须 F5 整页刷新"。教训别处已记，这里不重复。
   */
  const token = ref(readToken())

  function readToken(): string {
    return localStorage.getItem(TOKEN_KEY) ?? ''
  }

  function setToken(value: string): void {
    token.value = value
    if (value) localStorage.setItem(TOKEN_KEY, value)
    else localStorage.removeItem(TOKEN_KEY)
  }

  /* ---------------- 用户资料 ---------------- */

  const user = reactive({
    username: localStorage.getItem(USERNAME_KEY) ?? '',
    /**
     * 昵称。刻意**不落 localStorage**：它只是展示资料，缓存下来会出现
     * "我明明改了昵称，另一台设备上还是旧的"。由 App.vue 挂载时拉一次
     * `GET /users/me` 填上，改了资料后本地立即同步。
     */
    nickname: '',
    roles: [] as string[]
  })

  /* ---------------- 派生 ---------------- */

  /**
   * 是否已完成过一次"登录态判定"。
   *
   * `status` 靠它区分 unknown 与 guest：应用刚启动、还没有任何代码读过令牌时
   * 是 unknown，此时**不应该**被当成游客踢回登录页。
   */
  const resolved = ref(false)

  const status = computed<AuthStatus>(() => {
    if (!resolved.value) return 'unknown'
    return token.value ? 'authed' : 'guest'
  })

  const isLoggedIn = computed(() => token.value.length > 0)

  /** 侧栏显示名：昵称优先，没设昵称就回退到用户名（服务端"清空昵称"的语义落在这行）。 */
  const displayName = computed(() => user.nickname || user.username)

  /* ---------------- 动作 ---------------- */

  function markResolved(): void {
    resolved.value = true
  }

  function saveSession(newToken: string, username: string): void {
    setToken(newToken)
    // 防御：任何"假值"都不该落进 localStorage（UA-002）。
    // 写进去的是字面量字符串 "undefined"，比留空更难排查——侧栏会一直显示
    // "undefined"，而"请求失败"的路径永远不会覆盖它。
    const safe = username && username !== 'undefined' ? username : ''
    localStorage.setItem(USERNAME_KEY, safe)
    user.username = safe
    // 换了账号，上一个账号的昵称必须立刻消失：留着就是"登成别人的名字"
    user.nickname = ''
    user.roles = []
    markResolved()
  }

  /** 用 `GET /users/me` 的返回把资料补齐。 */
  function applyProfile(profile: { username: string; nickname: string | null; roles: string[] }): void {
    user.username = profile.username
    localStorage.setItem(USERNAME_KEY, profile.username)
    user.nickname = profile.nickname ?? ''
    user.roles = profile.roles ?? []
    markResolved()
  }

  function logout(): void {
    setToken('')
    localStorage.removeItem(USERNAME_KEY)
    user.username = ''
    user.nickname = ''
    user.roles = []
    markResolved()
  }

  return {
    token,
    user,
    status,
    resolved,
    isLoggedIn,
    displayName,
    markResolved,
    saveSession,
    applyProfile,
    logout
  }
})

/* ============================================================
 * 兼容层：保留旧的函数式调用
 *
 * 目的：让 LoginView / App.vue / router / client.ts **一行都不用改**，
 * 避免"引入状态管理"扩散成一次全站重构（Phase C 禁止大改视图）。
 *
 * 全部转发到 store，内部**没有第二份状态**。
 * ============================================================ */

/**
 * 惰性拿 store。
 *
 * 不能缓存成模块级常量：`useAuthStore()` 必须在 `app.use(pinia)` 之后调用，
 * 而模块顶层可能早于那一步执行（那时会抛 "no active pinia"）。
 */
function store() {
  return useAuthStore()
}

export function saveSession(token: string, username: string): void {
  store().saveSession(token, username)
}

export function applyProfile(profile: { username: string; nickname: string | null; roles: string[] }): void {
  store().applyProfile(profile)
}

export function displayName(): string {
  return store().displayName
}

export function isLoggedIn(): boolean {
  return store().isLoggedIn
}

export function logout(): void {
  store().logout()
}
