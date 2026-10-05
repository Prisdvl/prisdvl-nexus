import { create } from "zustand";
import { fetchProfile, login as loginApi } from "@/api/auth";
import { setUnauthorizedHandler } from "@/api/client";
import { listKnowledgeBases } from "@/api/knowledge";
import type { UserInfo } from "@/api/types/auth";
import { getErrorMessage, isApiError } from "@/lib/errors";
import {
  clearAuthStorage,
  getLastUsername,
  getToken,
  removeToken,
  setLastUsername,
  setToken,
} from "@/lib/storage";

/**
 * 认证状态。
 *
 * ## 为什么 status 是五态而不是 `isLoggedIn: boolean`（R3 任务书 §6）
 *
 * ```text
 * unknown      还没初始化（store 刚创建）
 * initializing 正在用 token 换用户信息
 * guest        已确认没登录
 * authed       已确认登录
 * failed       登录失败（区别于 guest——UI 上要显示错误而不是"请登录"）
 * ```
 *
 * 两态方案（`isLoggedIn: boolean`）会制造一个**必然发生的假跳转**：
 * 应用刚启动时 `isLoggedIn` 还是 `false`（还没读 token），而 Protected Route
 * 一看就是 false，立刻跳登录页——**哪怕 token 其实有效**。
 * Vue 侧没有这个状态机，所以它的路由守卫只能靠"内存里的 tokenRef"硬撑。
 *
 * `initializing` 期间路由守卫应当**等**，而不是跳。
 */
export type AuthStatus =
  "unknown" | "initializing" | "guest" | "authed" | "failed";

interface AuthState {
  token: string | null;
  currentUser: UserInfo | null;
  status: AuthStatus;
  /** 上次登录的用户名，仅用于登录表单预填。**不是** currentUser 的来源。 */
  lastUsername: string | null;
  /** 登录/初始化失败的提示。 */
  error: string | null;
  /** 登录中。表单按钮用它转圈并禁用。 */
  loggingIn: boolean;
}

interface AuthActions {
  /**
   * 应用启动时调用一次。
   *
   * 流程：读 token → 没有就直接 guest；有则用 `GET /users/me` 换真实用户 →
   * 再用一次 `GET /knowledge-bases` 验证令牌在业务侧也可用。
   *
   * 为什么要第二步：profile 通了不代表知识库接口通了（越权、索引服务异常等）。
   * 而**只**验 profile 的话，用户会进到工作区才看到第一个错误，体验更差。
   */
  initialize: () => Promise<void>;
  login: (username: string, password: string) => Promise<boolean>;
  logout: () => void;
  /** 401 集中处理入口。由 client 通过 setUnauthorizedHandler 注入调用。 */
  handleUnauthorized: () => void;
  clearError: () => void;
}

export type AuthStore = AuthState & AuthActions;

/**
 * 进行中的初始化 promise（single-flight）。
 *
 * ## 为什么需要它
 *
 * `useAuthInitializer` 挂在根组件的 effect 里，而 **React StrictMode 在开发环境
 * 会故意把 effect 跑两遍**（用重复执行来暴露不幂等的副作用）。没有这层保护时，
 * 应用启动会**并发发出两次 `GET /users/me`**（外加两次知识库探活）。
 *
 * 现在只是浪费一次请求；但**一旦后端给 `/users/me` 加上副作用**
 * （记录活跃时间、累加登录次数这类），它就变成"每次刷新都记两次"的真 bug。
 *
 * ## 为什么要放在模块级而不是 store 的 state 里
 *
 * 它不是业务状态（不需要进 devtools、不需要持久化、也不该出现在任何 UI 里），
 * 只是一把"这次初始化正在跑"的锁。放进 state 反而会被组件订阅到无意义的变化。
 */
let inflight: Promise<void> | null = null;

export const useAuthStore = create<AuthStore>()((set, get) => ({
  token: getToken(),
  currentUser: null,
  status: "unknown",
  lastUsername: getLastUsername(),
  error: null,
  loggingIn: false,

  async initialize() {
    const token = getToken();
    if (!token) {
      set({ token: null, currentUser: null, status: "guest" });
      return;
    }

    // 已经有一次初始化在跑 → 复用它，不要发第二次请求
    if (inflight) return inflight;

    // 已完成且令牌没变 → 不重复拉。
    // 判据是 `token` 而不是 `status`：`logout()` 把 status 置为 guest，
    // 但那之后若有人再调 initialize（比如登录成功后），仍应能正常重新初始化。
    if (get().status === "authed" && get().token === token) return;

    set({ status: "initializing", error: null, token });

    const run = (async () => {
      // 两次请求串行：第二次依赖第一次的结果（先确认是谁，再验能用）
      try {
        const user = await fetchProfile();
        // 令牌有效但拿不到资料空间：登录态仍然是"已登录"，
        // 只是里面是空的（Phase B 的空态场景），**不能**降级成 guest。
        try {
          await listKnowledgeBases({ pageSize: 1 });
        } catch (e) {
          // 知识库不可用不阻断登录态——它可能是索引服务在重启，用户还是能进来的
          if (isApiError(e) && e.isAuth) {
            get().handleUnauthorized();
            return;
          }
          console.warn(
            "[auth] 知识库列表暂不可用，但登录态有效：",
            getErrorMessage(e),
          );
        }
        set({ currentUser: user, status: "authed" });
      } catch (e) {
        if (isApiError(e) && e.isAuth) {
          // 令牌过期/无效：清干净，退回 guest
          clearAuthStorage();
          set({ token: null, currentUser: null, status: "guest" });
          return;
        }
        // 网络故障等：令牌还在，**不能**判定为未登录（否则用户被无端踢出）
        set({ error: getErrorMessage(e) });
        set({ status: "guest" });
      }
    })();

    inflight = run;
    try {
      await run;
    } finally {
      // 无论成败都释放锁：失败时要允许重试（网络恢复后用户刷新即可）
      inflight = null;
    }
  },

  async login(username, password) {
    set({ loggingIn: true, error: null });
    try {
      const res = await loginApi({ username, password });
      setToken(res.accessToken);
      // currentUser 用**登录响应里的 user**，不是再去拉一次 profile：
      // 后端刚注册/更新过的资料在这次响应里就是最新的。
      // 字段来源严格按 AuthTokenResponse —— user 嵌在 user 里，**顶层没有 username**
      // （Vue 侧的「欢迎回来 undefined」就是读了这个不存在的字段）。
      setLastUsername(res.user.username);
      set({
        token: res.accessToken,
        currentUser: res.user,
        lastUsername: res.user.username,
        status: "authed",
        loggingIn: false,
      });
      return true;
    } catch (e) {
      removeToken();
      set({
        token: null,
        currentUser: null,
        status: "failed",
        error: getErrorMessage(e),
        loggingIn: false,
      });
      return false;
    }
  },

  logout() {
    // 顺序有讲究：先清存储，再清内存态。
    // 反过来的话，若清内存时抛错（比如有组件在监听），token 还留在盘上，
    // 刷新一下就"又登录回来了"——R3 任务书 §11 明确点了这个。
    clearAuthStorage();
    set({
      token: null,
      currentUser: null,
      status: "guest",
      error: null,
      loggingIn: false,
    });
  },

  handleUnauthorized() {
    // 已经在 guest 就别再动（并发 401 时 client 已做 single-flight，这里是第二道保险）
    if (get().status === "guest") return;
    clearAuthStorage();
    set({ token: null, currentUser: null, status: "guest", error: null });
  },

  clearError() {
    set({ error: null });
  },
}));

/**
 * 把 401 处理接到 client。
 *
 * ## 为什么要在这里接，而不是在组件里
 *
 * 401 会出现在任何请求上（知识库、Chat、Agent……）。若每个页面自己处理，
 * 一定会漏掉一处——而漏掉的那处表现为"用户莫名其妙停在空白页"。
 * 接在 store 里是**唯一入口**（R3 §5「401 必须集中处理」）。
 *
 * ## 为什么可以在这里 import store（而 client 那边用注入）
 *
 * 依赖方向是 **store → client**。本文件 import client 是顺的；
 * client 不 import store（它只有一个回调），所以没有循环。
 */
setUnauthorizedHandler(() => {
  useAuthStore.getState().handleUnauthorized();
});
