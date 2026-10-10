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
 * 3. **无 refresh token**（`grep refresh --include=*.java` 零命中）。
 *    令牌 30 分钟过期（实测 `expiresIn: 1800`），过期只能重新登录。
 *    决策：存 localStorage，不引入内存副本之外的持久化。
 */
import axios, { AxiosError, type AxiosInstance } from 'axios'

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
const EXPIRES_KEY = 'nx-token-expires-at'
/** 兼容旧版留下的 '1' 标记；新版登录成功后它会被移除 */
const LEGACY_AUTH_KEY = 'nx-proto-auth'

export const tokenStore = {
  get: (): string => localStorage.getItem(TOKEN_KEY) ?? '',
  /** 过期时间存成毫秒时间戳；缺失视为过期（宁可多登一次也不发一个必失败的请求） */
  expiresAt: (): number => Number(localStorage.getItem(EXPIRES_KEY) ?? '0'),
  isExpired: (): boolean => {
    if (!tokenStore.get()) return true
    const exp = tokenStore.expiresAt()
    if (!exp) return true
    return Date.now() >= exp
  },
  save: (token: string, expiresInSec: number) => {
    localStorage.setItem(TOKEN_KEY, token)
    localStorage.setItem(EXPIRES_KEY, String(Date.now() + expiresInSec * 1000))
    localStorage.removeItem(LEGACY_AUTH_KEY)
  },
  clear: () => {
    localStorage.removeItem(TOKEN_KEY)
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
  roles: string[]
}

export interface AuthToken {
  accessToken: string
  tokenType: string
  expiresIn: number
  user: AuthUser
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
  //登录 / 注册本身还没有令牌，跳过以免带上陈旧 token
  const isAuthEntry = config.url?.includes('/auth/login') || config.url?.includes('/auth/register')
  if (!isAuthEntry && !tokenStore.isExpired()) {
    config.headers.Authorization = `Bearer ${tokenStore.get()}`
  }
  return config
})

http.interceptors.response.use(
  (resp) => resp,
  (error: AxiosError<Result<unknown>>) => {
    const status = error.response?.status
    const bizCode = error.response?.data?.code
    // 2001 = 未认证或凭证缺失（实测）；401 也可能由网关返回
    if (status === 401 || bizCode === 2001) {
      tokenStore.clear()
      onUnauthorized()
    }
    return Promise.reject(error)
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
    /** 实测响应：{ accessToken, tokenType, expiresIn, user } */
    login: (username: string, password: string) =>
      http.post<Result<AuthToken>>('/auth/login', { username, password }).then((r) => r.data.data),
    /** 实测响应：UserInfoResponse */
    register: (body: { username: string; password: string; email?: string; nickname?: string }) =>
      http.post<Result<AuthUser>>('/auth/register', body).then((r) => r.data.data),
    /** 实测响应：与登录同一形状的 user */
    me: () => http.get<Result<AuthUser>>('/users/me').then((r) => r.data.data),
  },
  conversations: {
    /** 实测：{ list, total, pageNum, pageSize, pages } */
    list: (pageNum = 1, pageSize = 50) =>
      http.get<Result<PageResult<Conversation>>>('/chat/conversations', { params: { pageNum, pageSize } })
        .then((r) => r.data.data),
    create: (body: { title?: string; mode?: string; agentId?: string | null }) =>
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
    create: (body: { code: string; name: string }) =>
      http.post<Result<BackendAgent>>('/agents', body).then((r) => r.data.data),
    update: (id: string, body: Partial<AgentPayload>) =>
      http.put<Result<BackendAgent>>(`/agents/${id}`, body).then((r) => r.data.data),
    remove: (id: string) => http.delete<Result<null>>(`/agents/${id}`).then((r) => r.data.data),
  },
  stream: {
    /** 停止生成。幂等：流已结束时后端返回 cancelled=false，不算错误。 */
    stop: (requestId: string) =>
      http.post<Result<{ requestId: string; cancelled: boolean }>>(`/chat/stream/${requestId}/stop`)
        .then((r) => r.data.data),
  },  knowledge: {
    list: (pageNum = 1, pageSize = 100) =>
      http.get<Result<PageResult<KnowledgeBase>>>('/knowledge-bases', { params: { pageNum, pageSize } })
        .then((r) => r.data.data),
    get: (id: string) => http.get<Result<KnowledgeBase>>(`/knowledge-bases/${id}`).then((r) => r.data.data),
    create: (body: { name: string; description?: string }) =>
      http.post<Result<KnowledgeBase>>('/knowledge-bases', body).then((r) => r.data.data),
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
    remove: (kbId: string, docId: string) =>
      http.delete<Result<null>>(`/knowledge-bases/${kbId}/documents/${docId}`).then((r) => r.data.data),
    chunks: (kbId: string, docId: string) =>
      http.get<Result<ChunkView[]>>(`/knowledge-bases/${kbId}/documents/${docId}/chunks`).then((r) => r.data.data),

  },
  importTasks: {
    get: (taskId: string) => http.get<Result<ImportTask>>(`/import-tasks/${taskId}`).then((r) => r.data.data),
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
