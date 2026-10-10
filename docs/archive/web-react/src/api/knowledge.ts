import { api, pageQuery } from './client'
import type {
  ChunkView,
  CreateKnowledgeBaseRequest,
  DocumentListResponse,
  ImportTask,
  ImportTaskItem,
  KnowledgeBase,
  KnowledgeBaseListResponse,
  SearchHit
} from './types/knowledge'

/**
 * 知识库 API。
 *
 * 端点对应三个 Controller：
 * ```text
 * KnowledgeBaseController    @RequestMapping("/api/knowledge-bases")
 *   GET    /                        → Result<PageResult<KnowledgeBaseResponse>>
 *   POST   /                        → Result<KnowledgeBaseResponse>
 *   DELETE /{id}                    → Result<Void>
 *
 * KnowledgeDocumentController  @RequestMapping("/api/knowledge-bases")
 *   GET    /{kbId}/documents        → Result<PageResult<DocumentSummary>>
 *   DELETE /{kbId}/documents/{docId} → Result<Void>
 *
 * KnowledgeImportController   （同 /api 前缀）
 *   POST /knowledge-bases/{kbId}/documents   （multipart）→ Result<ImportTaskResponse>
 *   POST /knowledge-bases/{kbId}/vault        （multipart）→ Result<ImportTaskResponse>
 *   GET  /import-tasks/{taskId}                            → Result<ImportTaskResponse>
 *   GET  /import-tasks/{taskId}/items                      → Result<List<ImportTaskItemView>>
 *   POST /import-tasks/{taskId}/retry                       → Result<ImportTaskResponse>
 * ```
 */

/* ---------------- 知识库 ---------------- */

export function listKnowledgeBases(
  params: { pageNum?: number; pageSize?: number } = {},
  signal?: AbortSignal
) {
  const { pageNum, pageSize } = params
  return api.get<KnowledgeBaseListResponse>('/knowledge-bases', {
    query: pageQuery(pageNum, pageSize),
    ...(signal ? { signal } : {})
  })
}

/**
 * 创建知识库。
 *
 * `status` 不传：后端 `KnowledgeBaseStatus.parse(null)` 返回 `DEFAULT = ENABLED`。
 * 前端**不显式传**它——传了等于在前端复制一份默认策略，后端改默认值时两边会不一致。
 */
export function createKnowledgeBase(payload: CreateKnowledgeBaseRequest, signal?: AbortSignal) {
  return api.post<KnowledgeBase>('/knowledge-bases', payload, {
    ...(signal ? { signal } : {})
  })
}

/**
 * 删除知识库。**不可撤销**——后端会连同文档、切片、向量一并清除。
 * 调用方**必须**先用 `ConfirmDialog` 确认，并在 description 里写清影响。
 */
export function deleteKnowledgeBase(id: string, signal?: AbortSignal) {
  return api.delete<void>(`/knowledge-bases/${id}`, {
    ...(signal ? { signal } : {})
  })
}

/* ---------------- 文档 ---------------- */

export function listDocuments(
  kbId: string,
  params: { pageNum?: number; pageSize?: number } = {},
  signal?: AbortSignal
) {
  const { pageNum, pageSize } = params
  return api.get<DocumentListResponse>(`/knowledge-bases/${kbId}/documents`, {
    query: pageQuery(pageNum, pageSize),
    ...(signal ? { signal } : {})
  })
}

export function deleteDocument(kbId: string, documentId: string, signal?: AbortSignal) {
  return api.delete<void>(`/knowledge-bases/${kbId}/documents/${documentId}`, {
    ...(signal ? { signal } : {})
  })
}

/* ---------------- 导入任务 ---------------- */

/**
 * 上传**一个**资料文件。
 *
 * ## 字段名是 `file`（**单数**）——这是 R7 实测踩出来的
 *
 * 后端签名（R7 勘察，`KnowledgeImportController:84`）：
 * ```java
 * public Result<ImportTaskResponse> uploadDocument(
 *     @PathVariable Long knowledgeBaseId,
 *     @RequestPart("file") MultipartFile file)   // ← 单数、单文件
 * ```
 *
 * 我第一版写的是 `form.append('files', f)`（复数），后端直接
 * `MissingServletRequestPartException: Required part 'file' is not present` → HTTP 500。
 *
 * **更糟的是我还在注释里写"字段名必须是 `files`（复数）——后端按这个名取"**，
 * 写得很确信。那是**照 Vue 侧猜的，没核实**——正是 R3 任务书 §三
 * 「根据旧 Vue 类型直接复制而不核实」明令禁止的。**错的注释比没有注释危险**，
 * 它会让下一个人不再去核实。
 *
 * ## 端点只支持单文件，所以多文件由调用方循环
 *
 * 后端签名是 `MultipartFile file`（不是 `MultipartFile[]`），一次只收一个。
 * 多个文件要传就得发多次请求 —— 这正好让**每个文件有独立的任务与状态**
 * （任务书 §20），比"一个任务塞 N 个文件"更好：失败的那个能单独重试。
 *
 * **必须用 FormData 且不能自己设 `Content-Type`** —— 浏览器会自动补
 * `multipart/form-data; boundary=…`，手设会丢掉 boundary。
 */
export function uploadDocument(
  kbId: string,
  file: File,
  signal?: AbortSignal
): Promise<ImportTask> {
  const form = new FormData()
  form.append('file', file)
  return api.upload<ImportTask>(`/knowledge-bases/${kbId}/documents`, form, {
    ...(signal ? { signal } : {})
  })
}

/** 查任务进度。轮询用。 */
export function getImportTask(taskId: string, signal?: AbortSignal) {
  return api.get<ImportTask>(`/import-tasks/${taskId}`, {
    ...(signal ? { signal } : {})
  })
}

/**
 * 查任务的**逐文件**明细。
 *
 * 这是"每个文件独立状态"的数据来源（任务书 §20）——
 * 前端不自己维护每个文件的状态机，直接渲染后端给的明细。
 */
export function listImportTaskItems(taskId: string, signal?: AbortSignal) {
  return api.get<ImportTaskItem[]>(`/import-tasks/${taskId}/items`, {
    ...(signal ? { signal } : {})
  })
}

/** 重试失败的文件。 */
export function retryImportTask(taskId: string, signal?: AbortSignal) {
  return api.post<ImportTask>(`/import-tasks/${taskId}/retry`, undefined, {
    ...(signal ? { signal } : {})
  })
}


/* ---------------- 高级区（R10） ---------------- */

/**
 * 某篇文档的切片（含向量 / 索引状态）。
 *
 * `GET /api/knowledge-bases/{kbId}/documents/{docId}/chunks`
 *
 * **这是排查"为什么搜不到"的主入口**：切片在库里但 `vectorStatus` / `esStatus`
 * 没成功时，检索一定命中不了。
 */
export function listChunks(
  kbId: string,
  documentId: string,
  signal?: AbortSignal
) {
  return api.get<ChunkView[]>(`/knowledge-bases/${kbId}/documents/${documentId}/chunks`, {
    ...(signal ? { signal } : {})
  })
}

/**
 * 在知识库里检索（高级区的"试试能不能搜到"）。
 *
 * `GET /api/knowledge-bases/{kbId}/search?q=...&topK=...&mode=...`
 *
 * `mode` 默认 `hybrid`（向量 + 关键词混合）。用 `vector` 可单独验证"是不是
 * 只有向量这一路在起作用"——这是排查召回问题最有用的开关。
 */
export function searchChunks(
  kbId: string,
  params: { q: string; topK?: number; mode?: string },
  signal?: AbortSignal
) {
  const { q, topK, mode } = params
  return api.get<SearchHit[]>(`/knowledge-bases/${kbId}/search`, {
    query: {
      q,
      ...(topK !== undefined ? { topK } : {}),
      mode: mode ?? 'hybrid'
    },
    ...(signal ? { signal } : {})
  })
}

/**
 * 重建索引。**不可撤销且耗时**（会重新写全部切片的 ES/向量）。
 * 调用方必须先确认，且 description 里要写清影响面。
 */
export function recreateIndex(kbId: string, signal?: AbortSignal) {
  return api.post<void>(`/knowledge-bases/${kbId}/index/recreate`, undefined, {
    ...(signal ? { signal } : {})
  })
}

/** 触发向量化。 */
export function embedKnowledgeBase(kbId: string, signal?: AbortSignal) {
  return api.post<unknown>(`/knowledge-bases/${kbId}/embed`, undefined, {
    ...(signal ? { signal } : {})
  })
}


