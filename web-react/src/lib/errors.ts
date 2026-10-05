/**
 * 统一错误模型（R3 任务书 §14）。
 *
 * ## 为什么要自己定义，而不是直接抛 `Error`
 *
 * 页面需要区分的不只是"出错了"，而是**五种完全不同的情况**——它们的处理动作不一样：
 *
 * | 类别 | 处理 |
 * | --- | --- |
 * | Network | 网络不通、跨域被拦、DNS 失败 → 提示"检查网络"，**不重试业务** |
 * | Authentication | 401 → 清 token、跳登录（集中处理，页面不处理） |
 * | Permission | 403 → 提示无权限，**不跳登录** |
 * | Business | 后端业务码（如 1001 参数错）→ 显示后端原话 |
 * | Unexpected | 解析失败等 → 兜底提示，不泄漏堆栈 |
 *
 * 混成一个 `Error` 的后果很具体：401 与 403 都变成"登录已过期"，
 * 用户明明有权限却被反复踢回登录页——**这正是要避免的**。
 */

/** 错误分类。 */
export type ErrorKind =
  | 'network' // 网络/连接层失败，没有 HTTP 响应
  | 'auth' // 401
  | 'forbidden' // 403
  | 'business' // HTTP 2xx 但 body.code !== 0（后端约定错误在 200 里返回）
  | 'http' // 其他非 2xx
  | 'aborted' // 请求被 AbortController 取消（**不是错误**，不该弹提示）
  | 'unexpected' // 响应结构不符合后端约定
  | 'unknown' // 兜底

export interface ApiErrorInit {
  kind: ErrorKind
  message: string
  /** HTTP 状态码。网络错误没有。 */
  status?: number
  /** 后端业务码（`Result.code`）。 */
  code?: number
  /** 原始响应体，便于排查（可能含敏感信息，只在开发模式打）。 */
  payload?: unknown
  /** 引发本错误的底层异常。 */
  cause?: unknown
}

/**
 * 统一 API 错误。
 *
 * 刻意**继承 Error**：这样 `catch (e)` 里的 `e instanceof Error` 仍然成立，
 * 而需要分类的页面用 `isApiError(e)` 取 `kind`。
 */
export class ApiError extends Error {
  readonly kind: ErrorKind
  readonly status?: number
  readonly code?: number
  readonly payload?: unknown

  constructor(init: ApiErrorInit) {
    super(init.message, init.cause === undefined ? undefined : { cause: init.cause })
    this.name = 'ApiError'
    this.kind = init.kind
    this.status = init.status
    this.code = init.code
    this.payload = init.payload
  }

  /** 401：需要清登录态。 */
  get isAuth(): boolean {
    return this.kind === 'auth'
  }

  /** 请求被取消——调用方应当静默忽略，不要显示错误。 */
  get isAborted(): boolean {
    return this.kind === 'aborted'
  }
}

/** 是不是本项目定义的 API 错误。 */
export function isApiError(e: unknown): e is ApiError {
  return e instanceof ApiError
}

/**
 * 归一化任意抛出物为 `ApiError`。
 *
 * 页面不应该写 `catch (e) { console.log(e); 猜错误 }`（R3 任务书 §14 明确禁止），
 * 而是 `catch (e) { setError(getErrorMessage(normalizeError(e))) }`。
 */
export function normalizeError(e: unknown): ApiError {
  if (isApiError(e)) return e
  if (e instanceof Error) {
    // axios 取消与 fetch 取消都走这里
    if (e.name === 'AbortError' || e.name === 'CanceledError') {
      return new ApiError({ kind: 'aborted', message: '请求已取消', cause: e })
    }
    return new ApiError({ kind: 'unknown', message: e.message || '未知错误', cause: e })
  }
  return new ApiError({ kind: 'unknown', message: typeof e === 'string' ? e : '未知错误', cause: e })
}

/**
 * 面向用户的中文提示。
 *
 * ## 为什么不直接返回 `error.message`
 *
 * 后端在**很多**情况下会返回技术性 message（例如 DTO 校验的英文路径），
 * 直接展示既不友好也可能泄漏实现细节。分层的做法是：
 * 有业务 message 就用它（后端已经写成人话了），否则按 `kind` 给一句兜底。
 */
export function getErrorMessage(e: unknown): string {
  const err = normalizeError(e)
  // 取消不是错误，展示"请求已取消"只会让用户困惑
  if (err.isAborted) return ''
  if (err.message) return err.message
  switch (err.kind) {
    case 'network':
      return '网络连接失败，请检查网络后重试'
    case 'auth':
      return '登录已过期，请重新登录'
    case 'forbidden':
      return '没有权限执行该操作'
    default:
      return '操作失败，请稍后重试'
  }
}
