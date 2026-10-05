import { ApiError } from '@/lib/errors'
import { getToken, removeToken } from '@/lib/storage'
import { DEFAULT_PAGE_NUM, DEFAULT_PAGE_SIZE, SUCCESS_CODE, type Result } from './types/common'

/**
 * 统一 API Client（R3 任务书 §5）。
 *
 * ## ⚠️ 为什么这里必须有 BASE（而 Vue 版不需要）
 *
 * Vue 版用 axios，实例上有 `baseURL: '/api'`，所以各 api 文件里写
 * `/auth/login` 就行。**本项目用原生 fetch，没有那个隐式前缀**——
 * 于是路径必须在这里补齐，否则 `fetch('/auth/login')` 会打到 dev server 的
 * SPA fallback 或 nginx 的 404，表现为 **`请求失败（HTTP 404）`**。
 *
 * 这个 bug 真实发生过：R3 写完时用 `curl 5174/api/auth/login` 探测到 200，
 * 就以为链路正常——**那个测法绕过了本 client，等于没测**。
 * 换 UI 里真点一次登录才暴露。**验证必须走真实调用路径。**
 *
 * ## 唯一出口
 *
 * 页面上不允许直接 `fetch` / `axios`。所有请求走这里，才能保证：
 * token 注入、401 处理、错误归一化只做一次。
 *
 * ## 为什么用 `fetch` 而不是 axios
 *
 * Vue 侧用 axios，这里换 fetch 有三个理由：
 * 1. 少一个依赖（当前没有其它地方需要 axios 的特性）；
 * 2. `fetch` ���生支持 `AbortSignal`，而请求取消（R3 §16）是硬需求；
 * 3. 响应解析路径简单——这个项目的响应永远是 JSON，不需要拦截器链。
 *
 * 如果将来需要超时、���试、请求去重，再换 axios 也不迟（届时只需改这一个文件）。
 */

/**
 * 后端上下文路径。
 *
 * 与 nginx.conf 的 `location /api/` 一致。**所有 api 文件只写 `/auth/login`
 * 这种相对路径**，前缀由这里统一补——改部署路径时只改这一处。
 *
 * `/mcp` **不在这里**：它是独立的端点（nginx 里有自己的 location），
 * 走 SSE，不经本 client——但 `lib/sse.ts` 仍复用这个常量，
 * 否则两条链路的前缀会各自漂移。
 */
export const API_BASE = '/api'

/** 请求选项。在原生 `RequestInit` 基础上做两点收紧。 */
export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  /** 请求体。对象会自动 JSON 序列化。 */
  body?: unknown
  query?: Record<string, string | number | boolean | undefined>
  /**
   * 取消信号（R3 §16）。
   *
   * 页面切换 / 搜索 / 后续的 Chat 都靠它中止在途请求。
   * **不要用 `isMounted` 标志代替**——那只是"忽略结果"，
   * 请求仍会跑完、仍会占用连接与后端资源；而 abort 是真的停掉。
   */
  signal?: AbortSignal
  /** 401 时是否触发全局登出。登录接口本身要设 false。 */
  skipAuthRedirect?: boolean
  /** query 里不传的值（undefined）会被自动剔除。 */
}

/** 401 时的回调。由 authStore 注入，避免 client 反向依赖 store。 */
type UnauthorizedHandler = () => void

let onUnauthorized: UnauthorizedHandler | null = null

/**
 * 注册 401 处理。
 *
 * ## 为什么用注入而不是直接 import authStore
 *
 * `client` 是最底层的模块，`authStore` 依赖它（store 要调 client）。
 * 若 client 反过来 import store，就形成循环依赖。
 * 注入把依赖方向固定成 **store → client**，反向只留一个回调。
 *
 * ## single-flight（§15 的核心要求）
 *
 * 多个并发请求同时 401 时，只能触发**一次**登出与跳转。
 * 用一个 `handling401` 标志实现：进入后置 true，直到 `finally` 才复位。
 */
let handling401 = false

export function setUnauthorizedHandler(fn: UnauthorizedHandler | null): void {
  onUnauthorized = fn
}

function buildUrl(path: string, query: RequestOptions['query']): string {
  // 已经在前缀上的路径不重复拼（避免出现 `/api/api/...`）
  const full = path.startsWith(API_BASE) ? path : `${API_BASE}${path}`
  const qs = Object.entries(query ?? {})
    .filter(([, v]) => v !== undefined)
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
    .join('&')
  return qs ? `${full}?${qs}` : full
}

/** 请求体有两种形态：普通对象（JSON）与 FormData（文件上传）。 */
type Body = unknown

/** 是否是 FormData —— 决定 Content-Type 怎么处理。 */
function isFormData(b: Body): b is FormData {
  return typeof FormData !== 'undefined' && b instanceof FormData
}

/**
 * 构造带 token 的请求头。
 *
 * ## ⚠️ FormData **绝对不能设 Content-Type**（R7 实测踩过）
 *
 * `multipart/form-data` 的 `boundary` 由浏览器自己生成并写进 Content-Type。
 * 前端手动设 `multipart/form-data`（不带 boundary）会**覆盖**掉浏览器的决定，
 * 于是后端收到一个没有边界的 body，无法解析出任何文件——
 * 表现是"文件内容为空"这类完全看不出原因的错误。
 *
 * 所以这里对 FormData **一个 Content-Type 都不设**，交给浏览器。
 */
function buildHeaders(body: Body): HeadersInit {
  const headers: Record<string, string> = {}
  if (body !== undefined && !isFormData(body)) {
    headers['Content-Type'] = 'application/json'
  }
  const token = getToken()
  if (token) headers.Authorization = `Bearer ${token}`
  return headers
}

/** 请求体序列化：FormData 原样传，其余 JSON 化。 */
function serializeBody(body: Body): BodyInit | undefined {
  if (body === undefined) return undefined
  return isFormData(body) ? body : JSON.stringify(body)
}

/**
 * 发出请求并解包 `Result.data`。
 *
 * @throws 一切失败都以 {@link ApiError} 抛出（网络、401、403、业务码、非 2xx）。
 */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, query, signal, skipAuthRedirect } = options

  let response: Response
  try {
    response = await fetch(buildUrl(path, query), {
      method,
      headers: buildHeaders(body),
      ...(serializeBody(body) !== undefined ? { body: serializeBody(body) } : {}),
      ...(signal ? { signal } : {})
    })
  } catch (e) {
    // 取消与网络失败在这里是同一类异常（fetch 抛），必须靠 name 区分
    if (e instanceof Error && (e.name === 'AbortError' || e.name === 'CanceledError')) {
      throw new ApiError({ kind: 'aborted', message: '请求已取消', cause: e })
    }
    throw new ApiError({
      kind: 'network',
      message: '网络连接失败，请检查网络后重试',
      cause: e
    })
  }

  // 204 / 205 没有响应体。删除接口就是这种。
  const text = await response.text()
  const parsed: unknown = text ? safeJsonParse(text) : null

  if (!response.ok) {
    if (response.status === 401) {
      // 令牌失效：先清本地，再交给上层跳转。放在这里是为了让**所有** 401 都有统一处置。
      //
      // ⚠️ **实测踩过的坑**：登录失败也是 401（后端返回
      // `HTTP 401` + `{code: 2006, message: "用户名或密码错误"}`）。
      // 这里原本**硬编码** message 为「登录已过期，请重新登录」，于是
      // 用户密码输错却被告知"登录过期"——他会以为登录状态坏了，
      // 而不是"密码错了"。那正是任务书 §24 禁止的"提示掩盖真实原因"。
      //
      // 所以：**401 一律优先用后端给的 message**，只有它缺失时才用兜底文案。
      // 两种 401 由此自然区分开：
      //   登录接口  → 后端给「用户名或密码错误」→ 显示它
      //   令牌失效  → 后端给「认证失败」/不给   → 显示「登录已过期」
      removeToken()
      if (!skipAuthRedirect) {
        if (handling401) {
          // 并发 401 已经在处理中，**不重复触发**。
          // 不这样做的话，页面初始化并发 5 个请求就会跳 5 次登录页。
          throw new ApiError({
            kind: 'auth',
            message: backendMessage(parsed) ?? '登录已过期',
            status: 401
          })
        }
        handling401 = true
        try {
          onUnauthorized?.()
        } finally {
          handling401 = false
        }
      }
      throw new ApiError({
        kind: 'auth',
        // 后端有原话就用它的（登录失败时是「用户名或密码错误」）
        message: backendMessage(parsed) ?? '登录已过期，请重新登录',
        code: backendCode(parsed),
        status: 401,
        payload: parsed
      })
    }
    if (response.status === 403) {
      throw new ApiError({
        kind: 'forbidden',
        message: '没有权限执行该操作',
        status: 403,
        payload: parsed
      })
    }
    throw new ApiError({
      kind: 'http',
      message: `请求失败（HTTP ${response.status}）`,
      status: response.status,
      payload: parsed
    })
  }

  // 2xx 但 body 不是标准包装（例如某个接口直接返回数组）
  if (parsed === null || typeof parsed !== 'object') {
    return parsed as T
  }

  const result = parsed as Result<T>
  if (typeof result.code !== 'number') {
    // 后端约定每个响应都带 code。没有就不是 Result，**不猜**。
    throw new ApiError({
      kind: 'unexpected',
      message: '响应格式不符合约定',
      status: response.status,
      payload: parsed
    })
  }
  if (result.code !== SUCCESS_CODE) {
    // 业务错误：后端在 200 里返回错误码，这是它既有的约定（Vue 侧也是这么处理的）
    throw new ApiError({
      kind: 'business',
      message: result.message || `操作失败（错误码 ${result.code}）`,
      code: result.code,
      payload: parsed
    })
  }
  return result.data
}

/** 从响应体里取后端的原话。取不到返回 null（**不返回空串**——那会当成"有话但为空"）。 */
function backendMessage(parsed: unknown): string | null {
  if (parsed && typeof parsed === 'object') {
    const m = (parsed as { message?: unknown }).message
    if (typeof m === 'string' && m.trim()) return m.trim()
  }
  return null
}

/** 从响应体里取业务码。HTTP 错误时 body 里常常也有（后端把 code 映射成状态码后再带回来）。 */
function backendCode(parsed: unknown): number | undefined {
  if (parsed && typeof parsed === 'object') {
    const c = (parsed as { code?: unknown }).code
    if (typeof c === 'number') return c
  }
  return undefined
}

function safeJsonParse(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

/* ---------- 语法糖：把 method 固定住，调用处更短 ---------- */

export const api = {
  get: <T>(path: string, options: Omit<RequestOptions, 'method' | 'body'> = {}) =>
    request<T>(path, { ...options, method: 'GET' }),
  post: <T>(path: string, body?: unknown, options: Omit<RequestOptions, 'method' | 'body'> = {}) =>
    request<T>(path, { ...options, method: 'POST', body }),
  put: <T>(path: string, body?: unknown, options: Omit<RequestOptions, 'method' | 'body'> = {}) =>
    request<T>(path, { ...options, method: 'PUT', body }),
  delete: <T>(path: string, options: Omit<RequestOptions, 'method' | 'body'> = {}) =>
    request<T>(path, { ...options, method: 'DELETE' }),
  /**
   * 文件上传。body 必须是 `FormData`。
   *
   * 单独开这个方法而不是让 `post` 猜，是为了**让"发的是文件"在调用处一眼可见**——
   * `api.post(path, form)` 看不出传的是文件还是普通对象。
   */
  upload: <T>(path: string, form: FormData, options: Omit<RequestOptions, 'method' | 'body'> = {}) =>
    request<T>(path, { ...options, method: 'POST', body: form })
}

/** 分页查询的公共参数。与后端 `PageResult` 的字段名一致。 */
export function pageQuery(pageNum = DEFAULT_PAGE_NUM, pageSize = DEFAULT_PAGE_SIZE) {
  return { pageNum, pageSize }
}
