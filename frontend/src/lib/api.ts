/**
 * 后端 API 契约层（对齐 docs/api-observed-standalone.md 的实测结果）
 *
 * 三条硬约束，全部来自实测而非文档转抄：
 *
 * 1. **主键是字符串**。后端雪花 ID（19 位）由 `LongToStringJacksonComponent`
 *    统一序列化成字符串，JavaScript 的 Number 只能安全表示到 2^53-1。
 *    所以本文件里所有 id 都是 `string`，**任何地方都不要 Number() 转一下**。
 *
 * 2. **统一包络**。后端一律返回 `{ code, message, data }`，`code === 0` 才算成功。
 *    业务错误码（实测）：2001 未认证 / 2006 凭证错 / 1001 参数校验 / 4006 模型未注册。
 *
 * 3. **Access + Refresh**。access 短寿命（默认 30m）；refresh 为 opaque 长寿命凭证。
 *    access 401 时用单飞 Promise 队列刷新并重放原请求；refresh 失败才清登录态。
 *    决策：存 localStorage（XSS 风险见 Phase 4 报告；httpOnly Cookie 属后续项）。
 */
import axios, { AxiosError, type AxiosInstance, type InternalAxiosRequestConfig } from 'axios'

/* ------------------------------------------------------------------ *
 * 环境开关
 * ------------------------------------------------------------------ */

/** 默认走相对路径（由 vite dev proxy 转发到后端，见 vite.config.ts） */
const RAW_BASE = import.meta.env.VITE_API_BASE ?? '/api'
/** 末尾斜杠会让 axios 多出一个 `/`，请求路径变成 `//auth/login` */
export const API_BASE = RAW_BASE.replace(/\/+$/, '')

/**
 * 默认真实 API。
 *
 * `VITE_USE_MOCK=false` 时才走本地假数据；**默认必须是真实**，
 * 否则"没配环境变量就悄悄退回原型数据"是最难发现的失败模式。
 * 16 阶段 QA 门禁靠 `localStorage['nx-proto-auth']='1'` + 种子数据运行，
 * 需要离线跑时显式设 `VITE_USE_MOCK=true`。
 */
export const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'

/* ------------------------------------------------------------------ *
 * 令牌存取（决策 1：localStorage）
 * ------------------------------------------------------------------ */

const TOKEN_KEY = 'nx-token'
const REFRESH_KEY = 'nx-refresh-token'
const EXPIRES_KEY = 'nx-token-expires-at'
/** 兼容旧版留下的 '1' 标记；新版登录成功后它会被移除 */
const LEGACY_AUTH_KEY = 'nx-proto-auth'

export const tokenStore = {
  get: (): string => localStorage.getItem(TOKEN_KEY) ?? '',
  getRefresh: (): string => localStorage.getItem(REFRESH_KEY) ?? '',
  /** 过期时间存成毫秒时间戳；缺失视为过期（宁可多登一次也不发一个必失败的请求） */
  expiresAt: (): number => Number(localStorage.getItem(EXPIRES_KEY) ?? '0'),
  isExpired: (): boolean => {
    if (!tokenStore.get()) return true
    const exp = tokenStore.expiresAt()
    if (!exp) return true
    return Date.now() >= exp
  },
  save: (accessToken: string, expiresInSec: number, refreshToken?: string | null) => {
    localStorage.setItem(TOKEN_KEY, accessToken)
    localStorage.setItem(EXPIRES_KEY, String(Date.now() + expiresInSec * 1000))
    if (refreshToken) {
      localStorage.setItem(REFRESH_KEY, refreshToken)
    }
    localStorage.removeItem(LEGACY_AUTH_KEY)
  },
  clear: () => {
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(REFRESH_KEY)
    localStorage.removeItem(EXPIRES_KEY)
  },
}

/* ------------------------------------------------------------------ *
 * 统一响应类型
 * ------------------------------------------------------------------ */

export interface Result<T> {
  code: number
  message: string
  data: T
}

export interface PageResult<T> {
  list: T[]
  total: number
  pageNum: number
  pageSize: number
  pages: number
}

export interface AuthUser {
  /** 字符串雪花 ID —— 不要转Number */
  id: string
  username: string
  nickname: string | null
  email: string | null
  /** 当前绑定邮箱是否已通过归属验证；无邮箱时为 false */
  emailVerified: boolean
  roles: string[]
}

export interface AuthToken {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresIn: number
  user: AuthUser
}

export interface RefreshTokenResult {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresIn: number
}

export interface UserSession {
  id: string
  device: string
  ip: string | null
  lastUsedAt: string
  createdAt: string
  current: boolean
}

/** 标记已做过一次 refresh 重试，避免死循环 */
type RetriableConfig = InternalAxiosRequestConfig & { _retry?: boolean }

/** 单飞：并发 401 共用同一个 refresh Promise */
let refreshInFlight: Promise<RefreshTokenResult> | null = null

/**
 * 登录世代：logout / 强制清态时递增。
 * 防止「主动退出后仍在飞的 refresh」把新令牌写回 localStorage。
 */
let authGeneration = 0

/** 主动清登录态时调用（logout / 401 终态）；使进行中的 refresh 结果作废 */
export function bumpAuthGeneration() {
  authGeneration += 1
  refreshInFlight = null
}

function isAuthPublicUrl(url: string): boolean {
  return (
    url.includes('/auth/login') ||
    url.includes('/auth/register') ||
    url.includes('/auth/refresh') ||
    url.includes('/auth/logout') ||
    url.includes('/auth/password/reset/')
  )
}

async function refreshTokens(): Promise<RefreshTokenResult> {
  if (!refreshInFlight) {
    const generationAtStart = authGeneration
    refreshInFlight = (async () => {
      const refreshToken = tokenStore.getRefresh()
      if (!refreshToken) {
        throw new Error('missing refresh token')
      }
      // 用裸 axios，避开本实例拦截器（否则 refresh 401 会递归）
      const resp = await axios.post<Result<RefreshTokenResult>>(
        `${API_BASE}/auth/refresh`,
        { refreshToken },
        { timeout: 20_000 },
      )
      if (resp.data.code !== 0 || !resp.data.data?.accessToken) {
        throw new Error(resp.data.message || 'refresh failed')
      }
      // 退出或清态发生在 refresh 途中：丢弃结果，勿写回令牌
      if (generationAtStart !== authGeneration) {
        throw new Error('auth cleared during refresh')
      }
      const data = resp.data.data
      tokenStore.save(data.accessToken, data.expiresIn, data.refreshToken)
      return data
    })().finally(() => {
      refreshInFlight = null
    })
  }
  return refreshInFlight
}

export interface Conversation {
  id: string
  title: string | null
  /** PLAIN / RAG / AGENT */
  mode: string
  modelRole: string | null
  messageCount: number
  lastMessageAt: string | null
  pinned: boolean
  /** 元素是字符串雪花 ID（源码推断，未实测到非空值） */
  kbIds: string[]
  /** null = 用内置默认 Agent */
  agentId: string | null
  createdAt: string
  updatedAt: string
}

/** 会话模式：PLAIN 普通对话 / RAG 知识库问答 / AGENT 智能体 */
export type ConversationMode = 'PLAIN' | 'RAG' | 'AGENT'

export interface Agent {
  id: string
  code: string
  name: string
  description: string | null
  builtin: boolean
  status: string
}

/**
 * 后端消息实体（`ChatMessageResponse` record，16 字段，源码抄）。
 *
 * ⚠️ **字段的实际 JSON 形态未实测**——standalone 下没有可用模型，产生不了消息。
 * 这里只声明已知可靠的形状，映射到 UI 的 `ChatMsg` 时要逐字段核对。
 *
 * ⚠️ **`role` 与前端完全不同**：后端是 `USER / ASSISTANT / SYSTEM / TOOL`（4 个大写值），
 * 前端 UI 用的是 `'user' | 'bot'`（2 个小写值），**后端没有 `bot`**。
 * 转换必须显式映射，不能直接赋值。
 */
export interface BackendMessage {
  id: string
  role: 'USER' | 'ASSISTANT' | 'SYSTEM' | 'TOOL'
  /** AGENT 模式下产出这条消息的 Agent id；用户消息为 null */
  agentId: string | null
  content: string | null
  model: string | null
  promptTokens: number | null
  completionTokens: number | null
  totalTokens: number | null
  latencyMs: number | null
  /** 首token 耗时——正好是 HANDOFF P1-2 想要的字段 */
  firstTokenMs: number | null
  status: string | null
  errorMsg: string | null
  createdAt: string
  toolCalls: unknown[]
  citations: unknown[]
}

/* ------------------------------------------------------------------ *
 * axios 实例
 * ------------------------------------------------------------------ */

/** 401 / 2001 时通知外部（auth.ts 订阅它做清 token + 跳登录） */
type UnauthorizedHandler = () => void
let onUnauthorized: UnauthorizedHandler = () => {}
export function setUnauthorizedHandler(fn: UnauthorizedHandler) {
  onUnauthorized = fn
}

export const http: AxiosInstance = axios.create({
  baseURL: API_BASE,
  timeout: 20_000,
})

http.interceptors.request.use((config) => {
  // 登录 / 注册 / refresh / logout / 忘记密码：不带陈旧 access
  const url = config.url ?? ''
  if (!isAuthPublicUrl(url) && !tokenStore.isExpired()) {
    config.headers.Authorization = `Bearer ${tokenStore.get()}`
  }
  return config
})

http.interceptors.response.use(
  (resp) => resp,
  async (error: AxiosError<Result<unknown>>) => {
    const status = error.response?.status
    const bizCode = error.response?.data?.code
    const config = error.config as RetriableConfig | undefined
    const url = config?.url ?? ''

    // 2001 = 未认证或凭证缺失；401 也可能由网关返回
    if (status !== 401 && bizCode !== 2001) {
      return Promise.reject(error)
    }

    // refresh / login 等公开入口失败：直接清态，勿再 refresh
    if (!config || isAuthPublicUrl(url) || config._retry) {
      tokenStore.clear()
      onUnauthorized()
      return Promise.reject(error)
    }

    if (!tokenStore.getRefresh()) {
      tokenStore.clear()
      onUnauthorized()
      return Promise.reject(error)
    }

    try {
      config._retry = true
      const tokens = await refreshTokens()
      config.headers = config.headers ?? {}
      config.headers.Authorization = `Bearer ${tokens.accessToken}`
      return http.request(config)
    } catch {
      tokenStore.clear()
      onUnauthorized()
      return Promise.reject(error)
    }
  },
)

/* ------------------------------------------------------------------ *
 * 错误信息：把后端的 code/message 翻成能直接展示给人看的话
 * ------------------------------------------------------------------ */

/** 校验失败的 message 形如`username: 用户名长度需在 3–32 之间`，字段名前缀要不要剥掉由调用方决定 */
export function errMessage(e: unknown): string {
  const ax = e as AxiosError<Result<unknown>>
  const msg = ax?.response?.data?.message
  if (msg) return msg
  if (ax?.code === 'ECONNABORTED') return '请求超时，请检查后端是否在运行'
  if (ax?.message === 'Network Error') return '连不上后端服务，请确认 8080 端口的进程已启动'
  return '请求失败，请稍后重试'
}

/** 业务包络 code：200x 之外都是业务错误，需要调用方处理 */
export function bizCodeOf(e: unknown): number | undefined {
  return (e as AxiosError<Result<unknown>>)?.response?.data?.code
}

/* ------------------------------------------------------------------ *
 * 端点封装（只调用已有端点，不新增）
 * ------------------------------------------------------------------ */

export const api = {
  auth: {
    /** 登录：accessToken + refreshToken + expiresIn + user */
    login: (username: string, password: string) =>
      http.post<Result<AuthToken>>('/auth/login', { username, password }).then((r) => r.data.data),
    /** 实测响应：UserInfoResponse */
    register: (body: { username: string; password: string; email: string; nickname?: string }) =>
      http.post<Result<AuthUser>>('/auth/register', body).then((r) => r.data.data),
    /** 实测响应：与登录同一形状的 user */
    me: () => http.get<Result<AuthUser>>('/users/me').then((r) => r.data.data),
    /** 当前设备退出（凭 refreshToken；access 过期仍可调用） */
    logout: (refreshToken: string) =>
      http.post<Result<null>>('/auth/logout', { refreshToken }).then((r) => r.data.data),
    /**
     * 请求密码重置验证码（公开）。
     * 无论账户是否存在均返回防枚举提示文案（取自 Result.message）。
     */
    requestPasswordReset: (email: string) =>
      http.post<Result<null>>('/auth/password/reset/request', { email }).then((r) => r.data.message),
    /** 提交验证码与新密码完成重置（公开）；成功时取 Result.message */
    confirmPasswordReset: (body: { email: string; code: string; newPassword: string }) =>
      http.post<Result<null>>('/auth/password/reset/confirm', body).then((r) => r.data.message),
  },
  users: {
    me: () => http.get<Result<AuthUser>>('/users/me').then((r) => r.data.data),
    updateProfile: (body: { nickname?: string | null; email?: string | null }) =>
      http.put<Result<AuthUser>>('/users/me', body).then((r) => r.data.data),
    changePassword: (oldPassword: string, newPassword: string) =>
      http.put<Result<null>>('/users/me/password', { oldPassword, newPassword }).then((r) => r.data.data),
    quota: () => http.get<Result<UserQuota>>('/users/me/quota').then((r) => r.data.data),
    /** 读取当前用户的自定义对话模型配置（apiKey 只回掩码） */
    llmConfig: () =>
      http.get<Result<UserLlmConfig>>('/users/me/llm-config').then((r) => r.data.data),
    /** 保存自定义对话模型配置；apiKey 留空 = 保留已存的 key */
    saveLlmConfig: (body: { baseUrl?: string | null; apiKey?: string; modelName?: string | null; enabled: boolean }) =>
      http.put<Result<UserLlmConfig>>('/users/me/llm-config', body).then((r) => r.data.data),
    /** 向当前绑定邮箱发送归属验证码（服务端强制冷却与 IP 限流） */
    sendEmailVerification: () =>
      http.post<Result<null>>('/users/me/email/verification/send').then((r) => r.data.data),
    /** 提交验证码，完成当前绑定邮箱验证 */
    verifyEmail: (code: string) =>
      http.post<Result<AuthUser>>('/users/me/email/verification', { code }).then((r) => r.data.data),
    /** 设备会话列表；可选带 refresh 以标记 current */
    sessions: () => {
      const headers: Record<string, string> = {}
      const rt = tokenStore.getRefresh()
      if (rt) headers['X-Refresh-Token'] = rt
      return http
        .get<Result<UserSession[]>>('/users/me/sessions', { headers })
        .then((r) => r.data.data)
    },
    revokeSession: (id: string) =>
      http.delete<Result<null>>(`/users/me/sessions/${id}`).then((r) => r.data.data),
    revokeAllSessions: () =>
      http.post<Result<null>>('/users/me/sessions/revoke-all').then((r) => r.data.data),
  },
  conversations: {
    /** 实测：{ list, total, pageNum, pageSize, pages } */
    list: (pageNum = 1, pageSize = 50) =>
      http.get<Result<PageResult<Conversation>>>('/chat/conversations', { params: { pageNum, pageSize } })
        .then((r) => r.data.data),
    create: (body: { title?: string; mode?: ConversationMode; kbIds?: string[]; agentId?: string | null }) =>
      http.post<Result<Conversation>>('/chat/conversations', body).then((r) => r.data.data),
    /**
     * 局部更新会话。
     *
     * ⚠️ **三个必须知道的语义**（读 `ConversationServiceImpl.update` 确认）：
     * 1. `agentId: null` 表示**"不改这个字段"**，不是"清空"。
     *    清空必须用 `resetAgent: true`（后端注释：「null 既是不改又是解绑分不清，
     *    所以清空必须有独立信号」）。
     * 2. `kbIds: []` = 解绑，`undefined` = 不改。
     * 3. **mode 现在可以改**（本轮新增，原先刻意禁止）。
     *    传了 mode 就以目标模式为准清理不兼容绑定：
     *      - PLAIN：清 agentId + kbIds
     *      - RAG：清 agentId；kbIds 不传则保留
     *      - AGENT：清 kbIds；agentId 不传则保留
     *    并且后端会同步重算 `modelRole`。
     */
    update: (
      id: string,
      body: {
        agentId?: string
        resetAgent?: boolean
        title?: string
        pinned?: boolean
        kbIds?: string[]
        mode?: ConversationMode
      },
    ) => http.put<Result<Conversation>>(`/chat/conversations/${id}`, body).then((r) => r.data.data),
    /** ⚠️ 级联物理删：会话 deleted=1 + 消息物理删除 + 会话记忆清空（不可恢复） */
    remove: (id: string) => http.delete<Result<null>>(`/chat/conversations/${id}`).then((r) => r.data.data),
    /**
     * 分支（P1-3.7）：复制新会话，消息复制到 beforeMessageId（含）为止。
     * 标题由前端按「分支 / 分支1 / …」计算后显式传入。
     */
    fork: (id: string, body: { title: string; beforeMessageId?: string | null }) =>
      http.post<Result<Conversation>>(`/chat/conversations/${id}/fork`, body).then((r) => r.data.data),
  },
  messages: {
    /** 实测：空会话返回 { list: [], total: 0, ... } */
    list: (conversationId: string, pageNum = 1, pageSize = 100) =>
      http.get<Result<PageResult<BackendMessage>>>(
        `/chat/messages/${conversationId}`,
        { params: { pageNum, pageSize } },
      ).then((r) => r.data.data),
  },
  agents: {
    list: (pageNum = 1, pageSize = 100) =>
      http.get<Result<PageResult<BackendAgent>>>('/agents', { params: { pageNum, pageSize } })
        .then((r) => r.data.data),
    get: (id: string) => http.get<Result<BackendAgent>>(`/agents/${id}`).then((r) => r.data.data),
    create: (body: {
      code: string
      name: string
      description?: string | null
      systemPrompt?: string | null
      kbIds?: string[]
      toolCodes?: string[]
    }) => http.post<Result<BackendAgent>>('/agents', body).then((r) => r.data.data),
    update: (id: string, body: Partial<AgentPayload>) =>
      http.put<Result<BackendAgent>>(`/agents/${id}`, body).then((r) => r.data.data),
    /** 复制（副本回到 DRAFT，服务端生成新 code） */
    duplicate: (id: string) =>
      http.post<Result<BackendAgent>>(`/agents/${id}/duplicate`).then((r) => r.data.data),
    testChat: (id: string, question: string) =>
      http.post<Result<AgentAnswer>>(`/agents/${id}/test-chat`, { question }).then((r) => r.data.data),
    remove: (id: string) => http.delete<Result<null>>(`/agents/${id}`).then((r) => r.data.data),
  },
  tools: {
    /** 当前用户的工具目录（GET /api/tools）：code/名称/描述/是否启用 */
    list: () => http.get<Result<ToolInfo[]>>('/tools').then((r) => r.data.data),
  },
  stream: {
    /** 停止生成。幂等：流已结束时后端返回 cancelled=false，不算错误。 */
    stop: (requestId: string) =>
      http.post<Result<{ requestId: string; cancelled: boolean }>>(`/chat/stream/${requestId}/stop`)
        .then((r) => r.data.data),
  },
  /**
   * Agent 运行的人工审批（P0 §六）。工具被权限闸门拦下时后端推 `approval_required`，
   * 前端拿事件里的 `runId` 提交决定：once 只放行这一次、always 写长期授权、deny 回灌模型。
   * 提交后**同一条 SSE 流会继续**（后端在 await 上被唤醒），不需要重开流。
   */
  agentRuns: {
    decide: (runId: string, decision: 'allow' | 'always' | 'deny') =>
      http.post<Result<unknown>>(`/agent/runs/${runId}/approval`, {
        approved: decision !== 'deny',
        scope: decision === 'allow' ? 'once' : decision,
      }).then((r) => r.data.data),
  },  knowledge: {
    list: (pageNum = 1, pageSize = 100) =>
      http.get<Result<PageResult<KnowledgeBase>>>('/knowledge-bases', { params: { pageNum, pageSize } })
        .then((r) => r.data.data),
    get: (id: string) => http.get<Result<KnowledgeBase>>(`/knowledge-bases/${id}`).then((r) => r.data.data),
    create: (body: { name: string; description?: string }) =>
      http.post<Result<KnowledgeBase>>('/knowledge-bases', body).then((r) => r.data.data),
    /** 局部更新：传了的字段才改；description 可传空串清空 */
    update: (id: string, body: { name?: string; description?: string | null }) =>
      http.put<Result<KnowledgeBase>>(`/knowledge-bases/${id}`, body).then((r) => r.data.data),
    remove: (id: string) => http.delete<Result<null>>(`/knowledge-bases/${id}`).then((r) => r.data.data),
    /** 检索：mode=bm25|vector|hybrid（三种都开放，便于对照融合效果）。 */
    search: (kbId: string, q: string, mode = 'hybrid', topK?: number) =>
      http.get<Result<SearchHit[]>>(`/knowledge-bases/${kbId}/search`, {
        params: { q, mode, ...(topK ? { topK } : {}) },
      }).then((r) => r.data.data),
    /** 为待索引切片建索引（幂等，可重复调用）。 */
    index: (kbId: string) => http.post<Result<unknown>>(`/knowledge-bases/${kbId}/index`).then((r) => r.data.data),
    /** 重建索引：新建物理索引 → 原子切换别名 → 旧索引转待重建。 */
    recreateIndex: (kbId: string) => http.post<Result<null>>(`/knowledge-bases/${kbId}/index/recreate`).then((r) => r.data.data),
  },
  documents: {
    list: (kbId: string, pageNum = 1, pageSize = 100) =>
      http.get<Result<PageResult<KbDocument>>>(`/knowledge-bases/${kbId}/documents`, { params: { pageNum, pageSize } })
        .then((r) => r.data.data),
    upload: (kbId: string, file: File) => {
      const fd = new FormData()
      fd.append('file', file)
      return http.post<Result<ImportTask>>(`/knowledge-bases/${kbId}/documents`, fd).then((r) => r.data.data)
    },
    get: (kbId: string, docId: string) =>
      http.get<Result<DocumentDetail>>(`/knowledge-bases/${kbId}/documents/${docId}`).then((r) => r.data.data),
    remove: (kbId: string, docId: string) =>
      http.delete<Result<null>>(`/knowledge-bases/${kbId}/documents/${docId}`).then((r) => r.data.data),
    chunks: (kbId: string, docId: string) =>
      http.get<Result<ChunkView[]>>(`/knowledge-bases/${kbId}/documents/${docId}/chunks`).then((r) => r.data.data),
    /** 某篇文档的关联：出链 + 反向链接 + 待创建引用数。 */
    links: (kbId: string, docId: string) =>
      http.get<Result<DocumentLinks>>(`/knowledge-bases/${kbId}/documents/${docId}/links`).then((r) => r.data.data),
    /** 相关推荐（共同标签 + 链接关系加权）。 */
    related: (kbId: string, docId: string, limit = 5) =>
      http.get<Result<RelatedDoc[]>>(`/knowledge-bases/${kbId}/documents/${docId}/related`, { params: { limit } }).then((r) => r.data.data),

  },
  importTasks: {
    get: (taskId: string) => http.get<Result<ImportTask>>(`/import-tasks/${taskId}`).then((r) => r.data.data),
  },
  stats: {
    /** 工作台汇总：趋势(14) + 热力(30) + 最近活动(8)。
     *  用户维度由服务端从令牌解析，前端不传 userId。 */
    summary: () => http.get<Result<StatsSummary>>('/stats/summary').then((r) => r.data.data),
  },
  workflows: {
    list: (pageNum = 1, pageSize = 50) =>
      http.get<Result<PageResult<Workflow>>>('/workflows', { params: { pageNum, pageSize } })
        .then((r) => r.data.data),
    create: (body: { name: string; description?: string }) =>
      http.post<Result<Workflow>>('/workflows', body).then((r) => r.data.data),
    get: (id: string) => http.get<Result<Workflow>>(`/workflows/${id}`).then((r) => r.data.data),
    /** 改元信息（名称 / 描述）：局部更新，只传要改的字段；description 传空串清空。不产生新版本。 */
    update: (id: string, body: { name?: string; description?: string | null }) =>
      http.put<Result<Workflow>>(`/workflows/${id}`, body).then((r) => r.data.data),
    saveDraft: (id: string, body: { nodes: WorkflowNode[]; edges: WorkflowEdge[] }) =>
      http.put<Result<Workflow>>(`/workflows/${id}/draft`, body).then((r) => r.data.data),
    validate: (id: string) =>
      http.post<Result<WorkflowValidation>>(`/workflows/${id}/validate`).then((r) => r.data.data),
    publish: (id: string) => http.post<Result<Workflow>>(`/workflows/${id}/publish`).then((r) => r.data.data),
    versions: (id: string) =>
      http.get<Result<WorkflowVersion[]>>(`/workflows/${id}/versions`).then((r) => r.data.data),
    debug: (id: string, inputs: Record<string, unknown>) =>
      http.post<Result<WorkflowRun>>(`/workflows/${id}/debug`, { inputs }).then((r) => r.data.data),
    run: (id: string, inputs: Record<string, unknown>) =>
      http.post<Result<WorkflowRun>>(`/workflows/${id}/runs`, { inputs }).then((r) => r.data.data),
    /** 内置模板清单。 */
    presets: () => http.get<Result<WorkflowPreset[]>>('/workflows/presets').then((r) => r.data.data),
    /** 用内置模板新建：图为草稿，检索节点的知识库还需用户自己选才能发布。 */
    instantiatePreset: (code: string) =>
      http.post<Result<Workflow>>(`/workflows/presets/${code}`).then((r) => r.data.data),
  },
  workflowRuns: {
    /** 某工作流的运行历史（按 id 倒序分页）。
     *  运行是同步的，失败时 POST 直接抛错、拿不到 runId，所以失败运行要靠这里找回再读轨迹。 */
    list: (workflowId: string, pageNum = 1, pageSize = 20) =>
      http.get<Result<PageResult<WorkflowRun>>>('/workflow-runs', { params: { workflowId, pageNum, pageSize } })
        .then((r) => r.data.data),
    get: (runId: string) => http.get<Result<WorkflowRun>>(`/workflow-runs/${runId}`).then((r) => r.data.data),
    nodes: (runId: string) =>
      http.get<Result<WorkflowNodeRun[]>>(`/workflow-runs/${runId}/nodes`).then((r) => r.data.data),
    cancel: (runId: string) => http.post<Result<WorkflowRun>>(`/workflow-runs/${runId}/cancel`).then((r) => r.data.data),
    /** 人工审批：decision 只接受 APPROVE / REJECT（后端非等待态返回 6007/409）。 */
    approve: (runId: string, body: { decision: 'APPROVE' | 'REJECT'; comment?: string }) =>
      http.post<Result<WorkflowRun>>(`/workflow-runs/${runId}/approval`, body).then((r) => r.data.data),
    /** 从失败节点续跑：以同一版本新建一次运行（仅 FAILED/CANCELLED，否则 6008/409）。 */
    retry: (runId: string) =>
      http.post<Result<WorkflowRun>>(`/workflow-runs/${runId}/retry`).then((r) => r.data.data),
  },
  rag: {
    ask: (kbId: string, question: string, topK = 5) =>
      http.post<Result<RagAnswer>>(`/knowledge-bases/${kbId}/ask`, { question, topK }).then((r) => r.data.data),
  },
}

/* ------------------------------------------------------------------ *
 * 后端响应类型（依据真实接口实测响应定义，2026-10-10）
 * ------------------------------------------------------------------ */

export interface BackendAgent {
  id: string
  code: string
  name: string
  description: string | null
  systemPrompt?: string | null
  modelRole: string | null
  answerPolicy: string
  temperature: number | null
  status: string
  builtin: boolean
  kbIds: string[]
  toolCodes?: string[]
}

export interface AgentPayload {
  name: string
  description: string | null
  systemPrompt: string | null
  answerPolicy: string
  temperature: number | null
  status: string
  kbIds: string[]
  toolCodes: string[]
}

/** GET /api/tools 的工具视图（对应用户级启用状态） */
export interface ToolInfo {
  code: string
  name: string
  description: string | null
  source: string
  category: string
  enabled: boolean
  builtin: boolean
}

export interface KnowledgeBase {
  id: string
  name: string
  description: string | null
  status: string
  docCount: number
  chunkCount: number
  totalTokens: string | number
  embeddingModel?: string
  createdAt: string
  updatedAt: string
}

export interface WorkflowNodePosition { x: number; y: number }

export interface WorkflowNode {
  id: string
  type: 'START' | 'END' | 'KNOWLEDGE_RETRIEVAL' | 'LLM' | 'AGENT' | 'IF_ELSE' | 'TEMPLATE' | 'HUMAN_APPROVAL'
  version: number
  position?: WorkflowNodePosition | null
  config: Record<string, unknown>
}

export interface WorkflowEdge {
  id: string
  source: string
  target: string
  sourceHandle?: string | null
}

/** 内置工作流模板（清单项不含图，图在实例化时才写进草稿）。 */
export interface WorkflowPreset {
  code: string
  name: string
  description: string
  nodeCount: number
}

export interface Workflow {
  id: string
  name: string
  description: string | null
  status: string
  draftVersionId: string | null
  publishedVersionId: string | null
  draftVersionNo: number | null
  publishedVersionNo: number | null
  nodes: WorkflowNode[]
  edges: WorkflowEdge[]
  createdAt: string
  updatedAt: string
}

export interface WorkflowValidation { valid: boolean; errors: string[] }

export interface WorkflowVersion {
  id: string
  versionNo: number
  status: string
  graphHash: string | null
  createdAt: string
  publishedAt: string | null
}

export interface WorkflowRun {
  id: string
  workflowId: string
  versionId: string
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'WAITING_APPROVAL'
  triggerType: string
  /** 重试自哪次运行（RETRY 时指向源运行）；非重试为 null */
  retryOfRunId?: string | null
  input: Record<string, unknown> | null
  output: unknown
  errorCode: number | null
  errorMessage: string | null
  startedAt: string | null
  finishedAt: string | null
  durationMs: number | null
}

export interface WorkflowNodeRun {
  id: string
  nodeId: string
  nodeType: string
  status: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED' | 'WAITING_APPROVAL'
  attempt: number
  input: Record<string, unknown> | null
  output: unknown
  errorCode: number | null
  errorMessage: string | null
  latencyMs: number | null
  startedAt: string | null
  finishedAt: string | null
}

export interface KbDocument {
  id: string
  fileName: string
  fileExt: string
  title: string | null
  fileSizeKb: number
  status: string
  chunkCount: number
  tokenCount: number
  updatedAt: string
}

export interface ChunkRow { id: string; content?: string; heading?: string | null; tokenCount?: number }

export interface ImportTask {
  id: string
  status: string
  totalFiles?: number
  createdCount?: number
  updatedCount?: number
  failedCount?: number
  totalChunks?: number
  errorMsg?: string | null
}

export interface RagCitation { documentId?: string; documentName?: string; chunkId?: string; score?: number; snippet?: string }
export interface RagAnswer { answer: string; citations: RagCitation[] }

export interface SearchHit { chunkId?: string; id?: string; documentId?: string; documentName?: string; headingPath?: string; content?: string; score?: number }
export interface ChunkView { id: string; chunkIndex: number; chunkType: string; headingPath: string | null; content: string; tokenCount: number; charCount: number; vectorStatus: string; esStatus: string }

export interface DocLink { linkId?: string; linkType?: string; target?: string; display?: string; resolvedDocumentId?: string; resolvedTitle?: string; external?: boolean }
export interface DocumentLinks { outlinks: DocLink[]; backlinks: DocLink[]; dangling: number }
export interface RelatedDoc { documentId?: string; title?: string; sharedTags?: number; linked?: boolean; reasons?: string[]; score?: number }

export interface StatsTrendPoint { day: string; count: number }
export interface StatsActivityItem { type: string; refId: string; label: string | null; at: string | null }
export interface StatsSummary { trend: StatsTrendPoint[]; heat: StatsTrendPoint[]; activity: StatsActivityItem[] }

export interface UserQuota {
  requestUsed: number
  requestLimit: number
  tokenUsed: number
  tokenLimit: number
  resetAt: string
}

/** 当前用户的自定义对话模型配置（GET 回显；apiKey 不回明文） */
export interface UserLlmConfig {
  baseUrl: string | null
  modelName: string | null
  enabled: boolean
  hasApiKey: boolean
  apiKeyMasked: string | null
}

export interface AgentToolTrace { tool: string; input?: unknown; output?: unknown; error?: string | null; status?: string }
export interface AgentAnswer { answer: string; toolCalls: AgentToolTrace[]; usedTools: boolean }

export interface DocumentDetail {
  id: string
  fileName: string
  title: string | null
  fileExt: string
  status: string
  content: string | null
  tags: string[]
  chunkCount: number
  tokenCount: number
  updatedAt: string
}
