import client, { type PageResult } from './client'
import { postSse } from '@/utils/sse'

/*
 * 说明：所有 id 都是 string。
 * 后端按 ADR-017 把 Long 序列化成字符串（雪花 id 超出 JS 安全整数范围，
 * 用 number 会在前端悄悄丢精度——这类 bug 极难发现，所以类型上就不给机会）。
 */

export interface KnowledgeBase {
  id: string
  name: string
  description: string | null
  sourceType: string
  embeddingProvider: string
  embeddingModel: string
  embeddingDim: number
  chunkStrategy: string
  chunkSize: number
  chunkOverlap: number
  docCount: number
  chunkCount: number
  totalTokens: number
  createdAt: string
  updatedAt: string
}

export interface DocumentSummary {
  id: string
  sourcePath: string
  fileName: string
  fileExt: string
  folder: string | null
  title: string | null
  fileSizeKb: number | null
  status: string
  chunkCount: number
  tokenCount: number
  version: number
  tags: string[]
  updatedAt: string
}

export interface ChunkView {
  id: string
  chunkIndex: number
  chunkType: string
  headingPath: string | null
  content: string
  tokenCount: number
  charCount: number
  vectorStatus: string
  esStatus: string
}

export interface ImportTask {
  id: string
  knowledgeBaseId: string
  taskType: string
  status: string
  totalFiles: number
  createdCount: number
  updatedCount: number
  skippedCount: number
  deletedCount: number
  failedCount: number
  totalChunks: number
  totalTokens: number
  progress: number
  startedAt: string | null
  finishedAt: string | null
  errorMsg: string | null
}

export interface EmbedResult {
  total: number
  embedded: number
  cacheHits: number
  failed: number
  costMillis: number
}

export interface IndexResult {
  total: number
  indexed: number
  costMillis: number
}

export interface HeadingView {
  level: number
  text: string
  anchor: string
  startOffset: number
}

export interface DocumentDetail {
  id: string
  sourcePath: string
  fileName: string
  fileExt: string
  folder: string | null
  title: string | null
  status: string
  /** 原始 Markdown 正文；原始文件丢失时为 null */
  content: string | null
  headings: HeadingView[]
  tags: string[]
  fileSizeKb: number | null
  chunkCount: number
  tokenCount: number
  version: number
  updatedAt: string
}

export interface SearchHit {
  chunkId: string
  documentId: string
  documentTitle: string | null
  headingPath: string | null
  content: string
  score: number
  source: string
  /** 命中来源明细，如 ["BM25 #2", "向量 #1"] */
  matchedBy: string[]
}

export type SearchMode = 'hybrid' | 'bm25' | 'vector'

export interface RagCitation {
  /** 资料块编号，与回答里的 [n] 对应 */
  index: number
  chunkId: string
  documentId: string
  documentTitle: string | null
  headingPath: string | null
  snippet: string
  score: number
  /** 回答是否真的引用了它（未引用也返回，便于核对模型有没有漏引） */
  referenced: boolean
}

export interface RagAnswer {
  /** 留档记录 id（用于问答历史；旧记录可能为空） */
  recordId: string | null
  answer: string
  citations: RagCitation[]
  contextTokens: number
  truncated: number
  reranked: boolean
  costMillis: number
}

// ---------- 认证 ----------

export function register(username: string, password: string) {
  return client.post<never, void>('/auth/register', { username, password })
}

export interface LoginResponse {
  accessToken: string
  tokenType: string
  expiresIn: number
  username: string
}

export function login(username: string, password: string) {
  return client.post<never, LoginResponse>('/auth/login', { username, password })
}

export interface CurrentUser {
  id: string
  username: string
  roles: string[]
}

export function me() {
  return client.get<never, CurrentUser>('/users/me')
}

// ---------- 知识库 ----------

export function listKnowledgeBases(pageNum = 1, pageSize = 50) {
  return client.get<never, PageResult<KnowledgeBase>>('/knowledge-bases', {
    params: { pageNum, pageSize }
  })
}

export function createKnowledgeBase(name: string, description?: string) {
  return client.post<never, KnowledgeBase>('/knowledge-bases', { name, description })
}

export function deleteKnowledgeBase(id: string) {
  return client.delete<never, void>(`/knowledge-bases/${id}`)
}

// ---------- 导入 ----------

export function uploadVault(id: string, file: File) {
  const form = new FormData()
  form.append('file', file)
  return client.post<never, ImportTask>(`/knowledge-bases/${id}/vault`, form, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

/**
 * 上传单个文件（P1-1）：TXT / MD / PDF / DOCX。
 *
 * 与「上传 Vault」走同一条后端流水线，因此**同名再传是更新、内容未变会跳过**——
 * 界面上应当把这两件事解释给用户，否则"传了但什么都没变"看起来像失败。
 * 支持的格式以服务端解析器注册表为准，不在前端另写一份清单（写两份必然分叉）。
 */
export function uploadDocument(id: string, file: File) {
  const form = new FormData()
  form.append('file', file)
  return client.post<never, ImportTask>(`/knowledge-bases/${id}/documents`, form, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

export function getImportTask(taskId: string) {
  return client.get<never, ImportTask>(`/import-tasks/${taskId}`)
}

/** 导入明细的一条：哪个文件被做了什么、为什么（P1-3 / P1-4）。 */
export interface ImportTaskItem {
  id: string
  sourcePath: string
  action: string
  documentId: string | null
  chunkCount: number
  durationMs: number
  message: string | null
  createdAt: string
}

/**
 * 导入明细。`action` 过滤在**服务端**做：一个 Vault 上千条明细、失败的可能只有 3 条，
 * 拿回来再在前端筛，会被 limit 提前截断掉真正想看的那几条。
 */
export function listImportTaskItems(taskId: string, action?: string, limit = 100) {
  return client.get<never, ImportTaskItem[]>(`/import-tasks/${taskId}/items`, {
    params: { ...(action ? { action } : {}), limit }
  })
}

// ---------- 向量化 / 索引 ----------

export function embedChunks(id: string, batchLimit = 200) {
  return client.post<never, EmbedResult>(`/knowledge-bases/${id}/embed`, null, {
    params: { batchLimit }
  })
}

export function buildIndex(id: string, batchLimit = 500) {
  return client.post<never, IndexResult>(`/knowledge-bases/${id}/index`, null, {
    params: { batchLimit }
  })
}

export function recreateIndex(id: string) {
  return client.post<never, void>(`/knowledge-bases/${id}/index/recreate`)
}

// ---------- 浏览 / 检索 ----------

export function listDocuments(id: string, pageNum = 1, pageSize = 50) {
  return client.get<never, PageResult<DocumentSummary>>(`/knowledge-bases/${id}/documents`, {
    params: { pageNum, pageSize }
  })
}

export function listChunks(id: string, documentId: string) {
  return client.get<never, ChunkView[]>(`/knowledge-bases/${id}/documents/${documentId}/chunks`)
}

/** 文档详情：原始正文（Markdown 渲染）+ 大纲。 */
export function getDocument(id: string, documentId: string) {
  return client.get<never, DocumentDetail>(`/knowledge-bases/${id}/documents/${documentId}`)
}

/**
 * 删除一篇文档（P1-2）。
 *
 * 失败要按「**没删掉**」处理，而不是「删了一半」：后端把向量/索引的清理放在最前面且
 * 失败即整体失败，因此报错时文档仍然完好地在库里，用户可以放心重试。
 */
export function deleteDocument(id: string, documentId: string) {
  return client.delete<never, void>(`/knowledge-bases/${id}/documents/${documentId}`)
}

export interface DocumentLinkView {
  linkId: string
  linkType: string
  target: string
  display: string | null
  resolvedDocumentId: string | null
  resolvedTitle: string | null
  external: boolean
  startOffset: number
}

export interface DocumentLinks {
  outlinks: DocumentLinkView[]
  backlinks: DocumentLinkView[]
  /** 指向尚不存在文档的引用数——知识库最有用的一份"待创建笔记"清单 */
  dangling: number
}

/** 文档关联（Phase 4）：出链 + 反向链接。 */
export function getDocumentLinks(id: string, documentId: string) {
  return client.get<never, DocumentLinks>(`/knowledge-bases/${id}/documents/${documentId}/links`)
}

export interface RelatedDocument {
  documentId: string
  title: string | null
  sharedTags: number
  linked: boolean
  /** 为什么相关（"互相链接"、"共同标签: Java"） */
  reasons: string[]
}

/** 相关推荐（Phase 4.2）：共同标签 + 链接关系加权。 */
export function getRelatedDocuments(id: string, documentId: string, limit = 5) {
  return client.get<never, RelatedDocument[]>(`/knowledge-bases/${id}/documents/${documentId}/related`, {
    params: { limit }
  })
}

export interface WorkflowStepResult {
  name: string
  status: string
  durationMs: number
  message: string
}

/** 知识工作流（Phase 6.2）：统计 → 向量化 → 建索引，返回每步状态/耗时/消息。 */
export function runWorkflow(id: string) {
  return client.get<never, WorkflowStepResult[]>(`/workflow/run/${id}`)
}

/**
 * 检索。mode 三选一都开放——融合效果好不好，只有在能单独看每一路时才判断得了。
 */
export function search(id: string, q: string, topK = 10, mode: SearchMode = 'hybrid') {
  return client.get<never, SearchHit[]>(`/knowledge-bases/${id}/search`, {
    params: { q, topK, mode }
  })
}

/**
 * RAG 问答：检索 → 重排 → 组装 → 生成，返回带引用的回答。
 *
 * `topK` **不传就是不传**（不要在这里补默认值）：服务端按"请求 > 知识库配置 > 全局默认"
 * 解析，不传时用该知识库的 `retrieval_top_k`。原先这里写死 12，
 * 等于让知识库的检索参数对问答永远无效——按库调好的参数只对调试检索面板有用。
 */
export function askQuestion(
  id: string,
  question: string,
  topK?: number,
  history: Array<{ question: string; answer: string }> = []
) {
  return client.post<never, RagAnswer>(`/knowledge-bases/${id}/ask`, {
    question,
    ...(topK ? { topK } : {}),
    history
  })
}

/** 问答历史（含引用），用于回看"当时依据了哪些资料"。 */
export function getQaHistory(id: string, limit = 10) {
  return client.get<never, RagAnswer[]>(`/knowledge-bases/${id}/qa-history`, { params: { limit } })
}

export interface StreamMeta {
  citations: Array<{
    chunkId: string
    documentId: string
    documentTitle: string | null
    headingPath: string | null
  }>
  contextTokens: number
  truncated: number
  reranked: boolean
  empty: boolean
}

export interface StreamDone {
  finishReason?: string
  recordId?: string
  citations?: RagCitation[]
  error?: string
}

/**
 * retrieval 事件：检索完成、生成之前到达（"检索到 N 个片段"就来自这里）。
 *
 * 形状与会话流式（`api/chat.ts` 的 `StreamRetrieval`）**必须保持一致**：
 * 服务端两条链路共用同一个构造器（`RetrievalEvent`），前端却有两份类型声明。
 * 这是历史遗留——将来若合并成一个共享类型，这两处可以删掉一份。
 */
export interface StreamRetrieval {
  /** 本次检索覆盖的知识库。这条链路固定单库（路径里的 {id}），列表长度为 1。 */
  knowledgeBaseIds: string[]
  count: number
  hits: Array<{
    chunkId: string
    documentId: string
    /** 这一片出自哪个知识库（单库链路下恒等于路径里的那个库）。 */
    knowledgeBaseId: string | null
    documentTitle: string | null
    headingPath: string | null
    score: number
    source: string
  }>
  contextTokens: number
  truncated: number
  reranked: boolean
  empty: boolean
}

export interface StreamHandlers {
  onMeta?: (meta: StreamMeta) => void
  onRetrieval?: (retrieval: StreamRetrieval) => void
  onDelta?: (text: string) => void
  onDone?: (done: StreamDone) => void
}

/**
 * 流式问答（SSE over POST）。
 *
 * 帧的解析规则与 Agent 对话完全一样，因此复用 {@code utils/sse.ts}（解析只有一处实现）。
 * 事件顺序由后端保证：meta → retrieval → delta… → done（done 里带引用与留档 id）。
 * retrieval 里的命中片段就是"检索到 N 个片段"的来源，且必定早于第一个 delta。
 */
export async function askQuestionStream(
  id: string,
  question: string,
  topK: number | undefined,
  history: Array<{ question: string; answer: string }>,
  handlers: StreamHandlers,
  signal?: AbortSignal
): Promise<void> {
  await postSse(
    `/api/knowledge-bases/${id}/ask/stream`,
    // topK 不传 = 用知识库配置（与同步路径同一套解析规则）
    { question, ...(topK ? { topK } : {}), history },
    {
      signal,
      onFrame: ({ event, data }) => {
        const payload = data as never
        if (event === 'meta') handlers.onMeta?.(payload as StreamMeta)
        else if (event === 'retrieval') handlers.onRetrieval?.(payload as StreamRetrieval)
        else if (event === 'delta') handlers.onDelta?.((payload as { text: string }).text ?? '')
        else if (event === 'done') handlers.onDone?.(payload as StreamDone)
      }
    }
  )
}
