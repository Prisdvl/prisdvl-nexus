import type { PageResult } from './common'

/*
 * ## ⚠️⚠️ `id` 是 **string**，不是 number —— R7 实测踩出来的
 *
 * 后端 id 是 Java `Long`（雪花 ID，19 位，如 `2106725528004902914`），
 * 而 **Jackson 把 Long 序列化成 JSON 字符串**，实测：
 * ```json
 * { "id": "2106725528004902914", "workspaceId": "2105290932599996417",
 *   "docCount": 0, "chunkCount": 0, "totalTokens": "0" }
 * ```
 * 注意字段之间的差别：
 *
 * | Java 类型 | JSON 形态 | 本文件字段 |
 * | --- | --- | --- |
 * | `Long`   | **string** | `id` / `workspaceId` / `totalTokens` |
 * | `Integer`| number     | `docCount` / `chunkCount` |
 * | `Double` | number     | `similarityThreshold` |
 *
 * **我第一版全写成了 `number`**（照 Java 字段名想当然），于是详情页
 * `Number(useParams().id)` 把 19 位 id 压成 `2106725528004903000`——
 * 请求打到一个**不存在的库**，表现为「资料加载失败 / HTTP 404」，
 * 而 URL 看过去完全正常，**毫无报错线索**。
 *
 * 教训：「逐字段抄后端 DTO」还不够，**必须核实 JSON 序列化后的形态**——
 * Java 的 `Long` / `Integer` / `String` 在 JSON 里的映射各不相同。
 */

/**
 * 知识库 —— 后端唯一的"资料空间"实体。
 *
 * 权威来源：
 * ```text
 * nexus-knowledge/.../knowledge/model/dto/KnowledgeBaseResponse.java
 * ```
 * 字段逐个对应，**不增不减**。
 *
 * ## 关于任务书里的 "workspace"
 *
 * R3 任务书 §12 要求建 `workspaceStore` 管理"workspace 列表 / 当前 workspace / 切换"。
 * **后端没有 WorkspaceController**——`WorkspacePO`（表 `workspace`）是内部归属概念，
 * 每个用户由 `workspaceService.requirePersonalWorkspace(userId)` 自动解析出唯一���"个人 workspace"，
 * 前端既拿不到它的列表，也没有切换它的接口。
 *
 * 所以本项目的对应关系是：
 * ```text
 * 任务书的 workspace  ≡  前端看得见的 KnowledgeBase 列表
 * ```
 * 知识库列表就是用户全部的"资料空间"。`workspaceId` 只是一个归属 id，
 * 用户不感知（Phase B §1「知识库 = 用户的资料空间」）。按 R3 任务书 §3
 * 「API 必须以真实 Controller/DTO 为准」，**不虚构 workspace API**。
 */

/** 知识库。字段与后端 record 一一对应。 */
export interface KnowledgeBase {
  /** 雪花 ID，**字符串形态**（后端 Long → JSON string）。见文件头说明。 */
  id: string
  /** 所属 workspace 的 id。用户不感知，但后端会返回。 */
  workspaceId: string
  name: string
  description: string | null
  /** 图标标识（后端存的是字符串，不是 URL）。 */
  icon: string | null
  /** 状态字符串。后端未枚举化，R6 之前按 L2 状态处理。 */
  status: string
  /** 来源类型：手工创建 / Vault 导入等。 */
  sourceType: string
  embeddingProvider: string | null
  embeddingModel: string | null
  embeddingDim: number | null
  chunkStrategy: string | null
  chunkSize: number | null
  chunkOverlap: number | null
  retrievalTopK: number | null
  similarityThreshold: number | null
  /** 文档数 / 切片数——后端 `Integer`，JSON number。 */
  docCount: number | null
  chunkCount: number | null
  /** Token 总量——后端 `Long`，JSON **string**（全零时是 `"0"` 而不是 `0`）。 */
  totalTokens: string | null
  /** ISO 字符串（后端是 LocalDateTime，序列化成字符串）。 */
  createdAt: string | null
  updatedAt: string | null
}

/** `GET /api/knowledge-bases` 的响应。 */
export type KnowledgeBaseListResponse = PageResult<KnowledgeBase>

/** `POST /api/knowledge-bases` 的请求体。 */
export interface CreateKnowledgeBaseRequest {
  name: string
  description?: string
}


/* ==================================================================
 * 文档（Phase R7）
 *
 * 权威来源：
 * ```text
 * nexus-knowledge/.../knowledge/model/dto/DocumentSummary.java
 *   record DocumentSummary(Long id, String sourcePath, String fileName, String fileExt,
 *                          String folder, String title, Double fileSizeKb, String status,
 *                          Integer chunkCount, Integer tokenCount, Integer version,
 *                          List<String> tags, LocalDateTime updatedAt)
 * ```
 * ================================================================== */

/**
 * 文档摘要。
 *
 * `fileSizeKb` 是 **Double 且单位是 KB** —— 不是字节。
 * Vue 侧直接当字节用（`formatBytes(fileSizeKb)`），于是显示的大小差了 1024 倍。
 */
export interface DocumentSummary {
  /** 雪花 ID，字符串形态。 */
  id: string
  /** 相对路径（Vault 导入时有目录层级）。 */
  sourcePath: string
  fileName: string
  /** 扩展名，**含点**（如 `.md`）还是不含需实测——显示时别再补点。 */
  fileExt: string | null
  /** Vault 的文件夹名。非 Vault 来源为 null。 */
  folder: string | null
  title: string | null
  /** KB。**不是字节。** null = 后端没统计。 */
  fileSizeKb: number | null
  status: string
  chunkCount: number | null
  tokenCount: number | null
  /** 版本号。同一 sourcePath 重新导入会递增。 */
  version: number | null
  tags: string[]
  updatedAt: string | null
}

/** `GET /api/knowledge-bases/{id}/documents` 的响应。 */
export type DocumentListResponse = PageResult<DocumentSummary>

/* ==================================================================
 * 导入任务（Phase R7）
 *
 * 权威来源：
 * ```text
 * nexus-knowledge/.../knowledge/model/dto/ImportTaskResponse.java
 * nexus-knowledge/.../knowledge/model/dto/ImportTaskItemView.java
 * ```
 * ================================================================== */

/**
 * 导入任务的状态机。
 *
 * **终态是四个值不是两个**：`PARTIAL`（部分成功）也是终态——
 * 漏了它，界面会对着"3 个成功 2 个失败"的任务一直转圈。
 */
export type ImportTaskStatus = 'QUEUED' | 'RUNNING' | 'DONE' | 'PARTIAL' | 'FAILED' | 'CANCELLED'

/** 尚未结束的状态。用于决定要不要继续轮询。 */
export const PENDING_IMPORT_STATUS: ReadonlySet<ImportTaskStatus> = new Set<ImportTaskStatus>([
  'QUEUED',
  'RUNNING'
])

export function isImportFinished(status: ImportTaskStatus): boolean {
  return !PENDING_IMPORT_STATUS.has(status)
}

export interface ImportTask {
  id: string
  knowledgeBaseId: string
  /** UPLOAD / VAULT 等。后端字符串，未枚举化。 */
  taskType: string
  status: ImportTaskStatus
  totalFiles: number
  createdCount: number
  updatedCount: number
  skippedCount: number
  deletedCount: number
  failedCount: number
  totalChunks: number
  totalTokens: number
  /** 进度百分比（0~100）。后端给的是 int。 */
  progress: number
  startedAt: string | null
  finishedAt: string | null
  /** 整体失败原因。**逐文件**的失败原因在 items 里。 */
  errorMsg: string | null
}

/**
 * 导入任务的**逐文件**明细。
 *
 * ## 为什么这个类型很关键（任务书 §20）
 *
 * 任务书要求「每个文件独立状态，不能用一个全局 `isUploading` 控制所有文件」。
 * 而 Vue 侧恰恰只有一个 `busy` ref——3 个文件里第 2 个失败时，
 * 用户看到的是"导入失败"，**不知道是哪几个失败、为什么**。
 *
 * 后端**已经给了**逐文件明细（`sourcePath` / `action` / `message`），
 * 所以前端**不需要自己维护**逐文件状态——只要把 items 渲染出来。
 * 这比"前端给每个文件建一个状态机"可靠得多：
 * 后端才是真正知道每个文件发生了什么的一方。
 */
export interface ImportTaskItem {
  id: string
  /** 相对路径，**用作显示名**（比 fileName 更完整，Vault 下带目录）。 */
  sourcePath: string
  /** 后端判定的动作：CREATED / UPDATED / SKIPPED / DELETED / FAILED。 */
  action: string
  /** 产出的文档 id（字符串形态）。失败时为 null。 */
  documentId: string | null
  chunkCount: number
  durationMs: number
  /** 失败原因 / 说明。**直接展示给用户**。 */
  message: string | null
  createdAt: string | null
}

/** 导入任务里，单个 action 的显示文案。未收录的**原样透出**后端值。 */
const ITEM_ACTION_LABEL: Record<string, string> = {
  CREATED: '已导入',
  UPDATED: '已更新',
  SKIPPED: '已跳过',
  DELETED: '已删除',
  FAILED: '失败'
}

export function importItemLabel(action: string): string {
  return ITEM_ACTION_LABEL[action] ?? action
}

/** action 是否表示"没成功"。用于给列表上色。 */
export function isItemFailed(action: string): boolean {
  return action === 'FAILED'
}


/* ==================================================================
 * 切片与检索（R10 · 高级区）
 *
 * 权威来源：
 * ```text
 * nexus-knowledge/.../knowledge/model/dto/ChunkView.java
 *   record ChunkView(Long id, Integer chunkIndex, String chunkType, String headingPath,
 *                    String content, Integer tokenCount, Integer charCount,
 *                    String vectorStatus, String esStatus)
 * nexus-app/.../knowledge/service/ChunkSearchService.java
 *   record SearchHit(Long chunkId, Long documentId, String documentTitle, String headingPath,
 *                    String content, double score, String source,
 *                    List<String> matchedBy, Long knowledgeBaseId)
 * ```
 *
 * `vectorStatus` / `esStatus` 是**排查"为什么搜不到"的唯一依据**——
 * 切片在库里但向量或 ES 没写成功时，用户看到的是"明明导入过了却搜不到"。
 * 高级区就是给这种问题用的。
 * ================================================================== */

export interface ChunkView {
  id: string
  chunkIndex: number | null
  chunkType: string | null
  headingPath: string | null
  content: string
  tokenCount: number | null
  charCount: number | null
  /** 向量化状态。**非 null 说明这一步还没成功**——用户搜不到多半是这个。 */
  vectorStatus: string | null
  /** ES 索引状态。同上。 */
  esStatus: string | null
}

export interface SearchHit {
  chunkId: string
  documentId: string
  documentTitle: string | null
  headingPath: string | null
  content: string
  score: number
  /** 命中来源：vector / es / hybrid。 */
  source: string
  /** 命中的检索路径（关键词 / 向量 / …），用于解释"它为什么匹配上"。 */
  matchedBy: string[]
  knowledgeBaseId: string | null
}

/*
 * ## ⚠️ 这里踩过一次错判断（R10 E2E 抓到的）
 *
 * 我第一版写的是：
 * ```ts
 * // ❌ 错：以为「非 null = 异常」
 * return c.vectorStatus !== null || c.esStatus !== null
 * ```
 * 实测后端返回的是：
 * ```json
 * { "chunkIndex": 0, "vectorStatus": "DONE", "esStatus": "DONE", "tokenCount": 23 }
 * ```
 * **`DONE` 是成功**，不是"未就绪"。于是刚导入完的文档被标成"有 1 个切片未完成
 * 向量化或索引"——**与事实完全相反**，比不显示更糟。
 *
 * 正确判据要看**状态值**，而不是"有没有值"。
 */

/** 已就绪的状态值。实测后端正常状态是 `DONE`。 */
const READY_STATES: ReadonlySet<string> = new Set(['DONE', 'SUCCESS'])

/** 处理中（还没到终态）。 */
const PENDING_STATES: ReadonlySet<string> = new Set(['PENDING', 'QUEUED', 'RUNNING'])

/** 切片状态的中文词表。未知值**原样透出**——不静默兜底成"正常"。 */
const CHUNK_STATE_LABEL: Record<string, string> = {
  PENDING: '待处理',
  QUEUED: '排队中',
  RUNNING: '处理中',
  SUCCESS: '正常',
  DONE: '正常',
  FAILED: '失败',
  SKIPPED: '已跳过'
}

export function chunkStateLabel(s: string | null): string {
  if (s === null) return '-'
  return CHUNK_STATE_LABEL[s] ?? s
}

/** 单个状态是否已就绪。`null` 视为**未就绪**（后端还没写这个字段）。 */
export function isStateReady(s: string | null): boolean {
  if (s === null) return false
  return READY_STATES.has(s)
}

/** 单个状态是否仍在处理中。 */
export function isStatePending(s: string | null): boolean {
  if (s === null) return false
  return PENDING_STATES.has(s)
}

/** 状态徽标的语气：就绪→success，处理中→primary，失败→danger，未知→warning。 */
export function stateTone(s: string | null): 'success' | 'primary' | 'danger' | 'warning' {
  if (isStateReady(s)) return 'success'
  if (s === 'FAILED') return 'danger'
  if (isStatePending(s)) return 'primary'
  return 'warning'
}

/** 切片是否**未就绪**（向量或 ES 之一还没成功）。判据是状态值，不是"非 null"。 */
export function isChunkNotReady(c: ChunkView): boolean {
  return !isStateReady(c.vectorStatus) || !isStateReady(c.esStatus)
}


