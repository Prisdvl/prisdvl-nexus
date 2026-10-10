import { api } from './client'
import type {
  AuthTokenResponse,
  LoginRequest,
  ProfileResponse,
  RegisterRequest,
  RegisterResponse
} from './types/auth'

/**
 * 认证与用户 API。
 *
 * 端点逐个对��后端 Controller（R3 任务书 §3「API 必须以真实 Controller/DTO 为准」）：
 * ```text
 * AuthController   @RequestMapping("/api/auth")
 *   @PostMapping("/register")  Result<UserInfoResponse>
 *   @PostMapping("/login")     Result<AuthTokenResponse>
 * UserController   @GetMapping("/me")          （在 /api/users 下）
 * ```
 */

/**
 * 登录。
 *
 * `skipAuthRedirect: true` 的原因：登录失败时后端也可能返回 401
 * （用户名或密码不对）。**这时不能触发全局登出跳转**——
 * 否则用户正在登录页，会被"跳到登录页"，看起来像什么都没发生。
 */
export function login(payload: LoginRequest, signal?: AbortSignal) {
  return api.post<AuthTokenResponse>('/auth/login', payload, {
    skipAuthRedirect: true,
    ...(signal ? { signal } : {})
  })
}

/**
 * 注册。
 *
 * 注意：**后端注册只返回用户信息，不返回 token**（`Result<UserInfoResponse>`），
 * 所以注册后必须再调一次 `login`。R5 做完整注册流程时别忘了。
 */
export function register(payload: RegisterRequest, signal?: AbortSignal) {
  return api.post<RegisterResponse>('/auth/register', payload, {
    skipAuthRedirect: true,
    ...(signal ? { signal } : {})
  })
}

/**
 * 当前登录用户。
 *
 * **这是 `currentUser` 的唯一权威来源**（R3 任务书 §8）。
 * 不从 localStorage 读、不从登录响应的残留字段推断——那些都可能过期或结构不符。
 */
export function fetchProfile(signal?: AbortSignal) {
  return api.get<ProfileResponse>('/users/me', {
    // 这里**不** skipAuthRedirect：令牌失效本来就该跳转登录页。
    // 恰恰相反，调用方（authStore 初始化）需要知道"是 401 才清空态"，
    // 于是它自己捕获 ApiError 并判断 kind，不依赖这里跳转。
    ...(signal ? { signal } : {})
  })
}
