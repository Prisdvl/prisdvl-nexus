import type { PageResult } from './common'

/**
 * Agent 与工具 —— **逐字段抄自后端 record**。
 *
 * 权威来源：
 * ```text
 * nexus-app/.../agent/model/dto/AgentResponse.java
 *   record AgentResponse(Long id, String code, String name, String description,
 *                        String systemPrompt, String modelRole, String answerPolicy,
 *                        Double temperature, String status, Boolean builtin,
 *                        List<Long> kbIds, List<String> toolCodes,
 *                        LocalDateTime createdAt, LocalDateTime updatedAt)
 *
 * nexus-app/.../agent/model/dto/ToolResponse.java
 *   record ToolResponse(String code, String name, String description, String source,
 *                       String category, boolean enabled, boolean builtin)
 * ```
 *
 * ## `id` 与 `code` 是两个不同的标识，别混
 *
 * 后端**同时**给了 `id`（数据库主键，路径参数用它：`/api/agents/{agentId}`）
 * 与 `code`（业务编码，可读，用于显示与引用）。
 *
 * 任务书 §36 要求「统一 `id / name / agentId / agentName`，不要前后端各叫各的」。
 * 本项目的做法是：**完全照搬后端字段名，不发明 `agentName`**。
 * 历史上 Vue 侧出现过 `agentName` / `agentId` 手工映射混乱（Phase A §3.1），
 * 根因就是前端自造字段。此处保持一致即可。
 *
 * **前端以 `id` 为主键**（URL 与 React key 都用它），`code` 仅作显示。
 */

export interface Agent {
  /** 雪花 ID，**字符串形态**（后端 Long → JSON string）。 */
  id: string
  /** 业务编码，可读。**不是主键**。 */
  code: string
  name: string
  description: string | null
  /** 系统提示词。属于"高级"信息，默认不在卡片上显示（Phase B §12 L3）。 */
  systemPrompt: string | null
  /** 模型角色名。高级信息。 */
  modelRole: string | null
  /** 回答策略。高级信息。 */
  answerPolicy: string | null
  temperature: number | null
  /** DRAFT / PUBLISHED / DISABLED。映射见 `components/common/status.ts`。 */
  status: string
  /** 内置 Agent 不可删除（R9 的约束来源）。 */
  builtin: boolean
  /** 检索范围：绑定的知识库 id 列表（**字符串**形态）。 */
  kbIds: string[]
  /** 绑定的工具编码（对应 `Tool.code`，**不是 id**）。 */
  toolCodes: string[]
  createdAt: string | null
  updatedAt: string | null
}

export interface Tool {
  code: string
  name: string
  description: string | null
  /** 来源：内置 / MCP 等。后端是 enum `source().name()`。 */
  source: string
  /** 分类。用于 Agent 编辑器里分组展示。 */
  category: string
  enabled: boolean
  builtin: boolean
}

/** `GET /api/agents` 的响应（分页）。 */
export type AgentListResponse = PageResult<Agent>

/**
 * `POST /api/agents` 的请求体。
 *
 * 字段来自 `CreateAgentRequest.java`，**只列后端真实存在的**。
 * 高级项（prompt / temperature / policy）在 R9 的编辑器里可填，
 * 但创建时后端有默认值，所以全部可选。
 */
export interface CreateAgentRequest {
  name: string
  description?: string
  code?: string
  systemPrompt?: string
  modelRole?: string
  answerPolicy?: string
  temperature?: number
  kbIds?: string[]
  /** 工具**编码**（不是 id）——与 `Agent.toolCodes` 同构。 */
  toolCodes?: string[]
}

/** `PUT /api/agents/{id}` 的请求体。全部可选（部分更新）。 */
export type UpdateAgentRequest = Partial<CreateAgentRequest>

/**
 * 列表查询参数。
 *
 * `status` 用于按状态过滤（对应后端的 `AgentQuery`）。
 * 不确定后端是否支持时**不传**——传了后端忽略与传了后端报错同样糟，
 * 而前者可通过 E2E 观察，后者会直接坏。
 */
export interface AgentQuery {
  pageNum?: number
  pageSize?: number
}


/* ==================================================================
 * 状态（R9）
 *
 * 权威来源：`nexus-app/.../agent/model/AgentStatus.java`
 * ```java
 * public enum AgentStatus { DRAFT, PUBLISHED, DISABLED; public static final AgentStatus DEFAULT = DRAFT; }
 * ```
 * ================================================================== */

export type AgentStatus = 'DRAFT' | 'PUBLISHED' | 'DISABLED'

/** 状态中文词表。**只有 3 个值**，别自己发明。 */
const AGENT_STATUS_LABEL: Record<AgentStatus, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  DISABLED: '已停用'
}

export function agentStatusLabel(s: string): string {
  return AGENT_STATUS_LABEL[s as AgentStatus] ?? s
}

/**
 * 回答策略。
 *
 * | 值 | 含义 |
 * | --- | --- |
 * | `KB_PREFERRED` | 有资料就引用，没资料就用模型自身知识（**默认**） |
 * | `KB_ONLY` | 只用资料，没有就不答 |
 */
export type AnswerPolicy = 'KB_PREFERRED' | 'KB_ONLY'

export const ANSWER_POLICY_LABEL: Record<AnswerPolicy, string> = {
  KB_PREFERRED: '优先用资料',
  KB_ONLY: '只用资料'
}

/* ==================================================================
 * 试跑（R9）
 *
 * 权威来源：
 * ```text
 * nexus-app/.../agent/AgentContext.java
 *   public record ToolCallRecord(String tool, String input, String resultSummary) {}
 * nexus-app/.../agent/AgentAnswer.java
 *   public record AgentAnswer(String answer, List<ToolCallRecord> toolCalls, boolean usedTools) {}
 * nexus-app/.../agent/model/dto/TestAgentRequest.java
 *   public record TestAgentRequest(@NotBlank String question) {}
 * ```
 * ================================================================== */

export interface AgentToolRecord {
  tool: string
  input: string | null
  resultSummary: string | null
}

export interface AgentAnswer {
  answer: string
  toolCalls: AgentToolRecord[]
  usedTools: boolean
}

export interface TestAgentRequest {
  question: string
}

/* ==================================================================
 * ⚠️ `kbIds` 的空值语义与 RAG 会话**相反**（后端注释明确写了）
 * ==================================================================
 *
 * ```java
 * // CreateAgentRequest 的类注释：
 * //   kbIds 可空：空 = 不限制（检索该用户全部知识库）
 * //   注意这与 RAG 会话的 kbIds 语义相反——那边为空是"必须绑定才能提问"的硬错误
 * ```
 *
 * | 场景 | `kbIds` 为空时 |
 * | --- | --- |
 * | **Agent** | **不限制**（检索全部库）——这是合法的默认状态 |
 * | **RAG 会话** | **必须绑定才能提问**——后端返回 400 / code 1001 |
 *
 * 所以 UI 上：
 * - Agent 编辑器里"不选知识库"要显示成「全部知识库」而不是「未选择」；
 * - RAG 会话那里才需要阻止并提示。
 * 混了会让用户以为 Agent 没绑库就不能用。
 */

/** `code` 的后端约束（`CreateAgentRequest` 的 `@Pattern`）。 */
export const AGENT_CODE_PATTERN = /^[a-z0-9][a-z0-9._-]{1,63}$/
