# API 设计（AI Knowledge Hub）

> STEP 1 设计稿。路径遵循需求文档给定的 `/api/...` 风格；版本演进通过 `Api-Version` 请求头（可选）而不是改路径，避免早期过度设计。

---

## 1. 通用约定

### 1.1 统一返回体

```json
{ "code": 0, "message": "success", "data": {} }
```

| 字段 | 说明 |
| --- | --- |
| `code` | 业务码，`0` 表示成功，非 0 表示业务失败（见 1.3） |
| `message` | 人类可读信息，直接可展示；i18n 时按 `code` 映射 |
| `data` | 业务数据；失败时为 `null` |

**Request ID 不放 body，放响应头**：`X-Request-Id`（由过滤器生成，贯穿日志与 `obs_ai_request`），这样"观测字段"不会污染业务契约。

### 1.2 HTTP 状态码与业务码的分工

| 层 | 表达方式 |
| --- | --- |
| 传输/协议层 | 用 HTTP 状态码：`400` 参数格式错、`401` 未认证、`403` 无权限、`404` 资源不存在、`409` 冲突、`413` 文件过大、`429` 限流、`500` 服务内部错误、`503` 依赖不可用 |
| 业务层 | 用 body 里的 `code`，例如"知识库名称已存在"= `3002`，HTTP 仍是 `200`/`409` 按语义选择 |

统一由 `GlobalExceptionHandler` 转换，业务代码只抛 `BusinessException(ErrorCode, message)`。

### 1.3 错误码规划

| 段 | 域 | 示例 |
| --- | --- | --- |
| `0` | 成功 | `0 success` |
| `1xxx` | 通用/参数 | `1001 参数校验失败`、`1002 请求体格式错误`、`1003 不支持的排序字段` |
| `2xxx` | 认证授权 | `2001 未认证`、`2002 Token 已过期`、`2003 无权限访问该资源`、`2004 账号被禁用` |
| `3xxx` | 知识库/文档 | `3001 知识库不存在`、`3002 知识库名称重复`、`3003 文档不存在`、`3004 DocumentParseFailed 文档解析失败`、`3005 文件类型不支持`、`3006 文件超出大小限制`、`3007 ZIP 结构非法`、`3008 导入任务不存在` |
| `4xxx` | AI 模型/嵌入/向量 | `4001 ModelUnavailable 模型不可用`、`4002 ModelTimeout 模型超时`、`4003 TokenLimitExceeded 超出上下文限制`、`4004 EmbeddingFailed 嵌入失败`、`4005 VectorStoreUnavailable 向量库不可用`、`4006 未配置对应逻辑角色的模型` |
| `5xxx` | 工具/Agent/MCP | `5001 ToolExecutionFailed 工具执行失败`、`5002 AgentMaxIterationsExceeded 超出最大步数`、`5003 人工确认被拒绝`、`5004 工具无权限`、`5005 MCP 服务不可用` |
| `6xxx` | 工作流/评测 | `6001 工作流定义非法`、`6002 工作流节点执行失败`、`6003 评测集为空`、`6004 评测裁判模型不可用` |
| `7xxx` | 限流/系统 | `7001 请求过于频繁`、`7002 配额已用尽`、`9999 系统内部错误` |

> 与需求文档示例的差异：需求里写 `5001 AI model unavailable`。这里把 AI 模型类错误统一放在 `4xxx`（AI 域），`5xxx` 留给工具/Agent，理由是错误码分段要能一眼看出"哪个域炸了"，评测与观测才好归类。若你更希望严格照抄示例，这一步可以改。

### 1.4 分页与排序

请求：`?pageNum=1&pageSize=20&sort=createdAt,desc`
响应：`{ "list": [], "total": 137, "pageNum": 1, "pageSize": 20, "pages": 7 }`

`pageSize` 上限 100（超出直接截断为 100，不报错）。

### 1.5 幂等与上传

| 场景 | 约定 |
| --- | --- |
| 创建类接口 | 支持 `Idempotency-Key` 头；同 key 10 分钟内返回首次结果（Redis 存结果摘要） |
| 文件上传 | `multipart/form-data`；单文件 ≤ 50 MB，ZIP ≤ 200 MB（可配）；一次最多 200 个文件 |
| 长任务 | 一律返回 `taskId`，用 `GET /api/import-tasks/{id}` 或 SSE 获取进度 |
| 流式接口 | `POST` + `Accept: text/event-stream`（不用 `EventSource`，因为它无法带 `Authorization` 头） |

---

## 2. 认证与账号（Phase 0）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/auth/register` | 注册（用户名/邮箱/密码），密码 BCrypt 存储 |
| POST | `/api/auth/login` | 登录 → `accessToken`(30min) + `refreshToken`(7d) |
| POST | `/api/auth/refresh` | 刷新访问令牌（Refresh Token 轮换 + 旧 token 立即失效） |
| POST | `/api/auth/logout` | 登出（吊销 refresh + access jti 进黑名单） |
| GET | `/api/users/me` | 当前用户信息与角色 |
| PUT | `/api/users/me` | 修改昵称/头像 |
| PUT | `/api/users/me/password` | 修改密码（验证旧密码，改后吊销所有 refresh） |
| GET | `/api/admin/users` | 管理员：用户列表（`ADMIN` 角色） |

```http
POST /api/auth/login
{ "username": "lky", "password": "******" }

200 OK
{ "code": 0, "message": "success", "data": {
    "accessToken": "eyJ...", "refreshToken": "eyJ...",
    "expiresIn": 1800, "user": { "id": 1001, "nickname": "LKY", "roles": ["USER"] } } }
```

---

## 3. 知识库与文档（Phase 2）

### 3.1 知识库

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/knowledge-bases` | 创建（name、description、embedding 角色） |
| GET | `/api/knowledge-bases` | 我的知识库列表（含 doc/chunk 统计、最近同步时间） |
| GET | `/api/knowledge-bases/{id}` | 详情（含 embedding 模型、维度、切分参数） |
| PUT | `/api/knowledge-bases/{id}` | 改名称/描述（**不允许**改 embedding 模型，见 ADR-008） |
| DELETE | `/api/knowledge-bases/{id}` | 删除知识库（级联删除文档、Chunk、向量、ES 文档） |
| GET | `/api/knowledge-bases/{id}/tree` | 目录树（供左侧栏：Java / Spring / Redis…） |
| GET | `/api/knowledge-bases/{id}/tags` | 标签云与计数 |
| GET | `/api/knowledge-bases/{id}/stats` | 文档数/Chunk 数/Token 数/向量数/失败数 |

### 3.2 文档

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/knowledge-bases/{id}/documents` | 单/多文件上传（md/txt/pdf/docx）→ `taskId` |
| POST | `/api/knowledge-bases/{id}/vault` | 上传 Obsidian Vault ZIP → `taskId` |
| POST | `/api/knowledge-bases/{id}/resync` | 用已存原始文件重新同步（增量，不动没变的文件） |
| GET | `/api/documents` | 文档分页列表（按 kbId/folder/tag/status/关键词过滤） |
| GET | `/api/documents/{id}` | 文档详情（frontmatter、标签、标题树、图片、关联文档、Chunk/向量状态） |
| GET | `/api/documents/{id}/content` | 原始 Markdown 正文（前端渲染用） |
| GET | `/api/documents/{id}/chunks` | Chunk 列表（含 heading_path、token 数、向量状态） |
| DELETE | `/api/documents/{id}` | 删除文档及其 Chunk/向量/ES 记录 |
| POST | `/api/documents/{id}/reindex` | 单文档重建索引（切分/嵌入参数变更后使用） |
| GET | `/api/documents/{id}/links` | 关联文档（WikiLink / Markdown Link，含未解析目标） |

### 3.3 导入任务

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/import-tasks/{taskId}` | 任务状态与进度（总数/成功/跳过/失败/百分比） |
| GET | `/api/import-tasks/{taskId}/stream` | SSE 实时进度 |
| GET | `/api/import-tasks/{taskId}/items` | 逐文件明细（含 SKIPPED 原因、失败原因） |
| POST | `/api/import-tasks/{taskId}/cancel` | 取消（worker 检查取消标记后停止） |
| POST | `/api/import-tasks/{taskId}/retry` | 只重试失败项 |

```http
POST /api/knowledge-bases/1/vault   (multipart: file=MyVault.zip)

200 OK
{ "code": 0, "message": "success", "data": { "taskId": 9001, "status": "QUEUED" } }

GET /api/import-tasks/9001
{ "code": 0, "data": {
    "taskId": 9001, "status": "RUNNING", "progress": 42,
    "totalFiles": 380, "created": 120, "updated": 15, "skipped": 240, "deleted": 5, "failed": 0,
    "totalChunks": 5120, "startedAt": "2026-09-24T02:11:00Z" } }
```

---

## 4. 检索与调试（Phase 3）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/search` | 统一检索：`mode=VECTOR\|BM25\|HYBRID`、`topK`、`rerank`、filters（kbIds/tags/folder/documentId） |
| POST | `/api/search/explain` | 调试接口：返回两路原始结果、融合后的排名、重排分数（用于调参与面试演示） |

```http
POST /api/search
{ "query": "Redis 缓存击穿", "mode": "HYBRID", "topK": 10, "rerank": true,
  "filters": { "kbIds": [1], "tags": ["Redis"], "folder": "Redis" } }

200 OK
{ "code": 0, "data": { "took": 78, "results": [
  { "chunkId": 20001, "documentId": 100, "documentName": "缓存击穿.md",
    "sourcePath": "Redis/缓存击穿.md", "headingPath": "Redis > 缓存 > 缓存击穿",
    "snippet": "……互斥锁方案……", "vectorScore": 0.83, "bm25Score": 12.4,
    "rrfScore": 0.032, "rerankScore": 8.7, "finalRank": 1 } ] } }
```

---

## 5. AI 会话与流式输出（Phase 1 / Phase 3）

### 5.1 会话与消息

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/chat/conversations` | 新建会话（title 可空，首轮后自动生成标题；可指定 mode/kbIds） |
| GET | `/api/chat/conversations` | 会话列表（分页、置顶优先、按最后消息时间倒序） |
| GET | `/api/chat/conversations/{id}` | 会话详情（含配置：mode、kbIds、模型角色、prompt 版本） |
| PUT | `/api/chat/conversations/{id}` | 改标题 / 置顶 / 绑定知识库 |
| DELETE | `/api/chat/conversations/{id}` | 删除会话（逻辑删除 + 清理 ChatMemory 窗口） |
| GET | `/api/chat/messages/{conversationId}` | 历史消息（分页，含引用、Token、耗时） |
| POST | `/api/chat/messages` | 非流式发送（用于简单场景与自动化测试） |
| POST | `/api/chat/stream` | **SSE 流式发送**（见 5.2） |
| POST | `/api/chat/messages/{messageId}/regenerate` | 重新生成（删除该 assistant 消息重跑，保留原 prompt 版本） |
| POST | `/api/chat/stream/{requestId}/stop` | 停止生成（写 Redis 取消标记；服务端落库 `status=STOPPED`，保留已生成内容） |
| POST | `/api/chat/messages/{messageId}/feedback` | 点赞/点踩 + 备注（回流评测集） |

### 5.2 SSE 协议（本项目自定义事件）

```http
POST /api/chat/stream
Authorization: Bearer <accessToken>
Accept: text/event-stream
Content-Type: application/json

{ "conversationId": 5001, "content": "解释一下 Redis 缓存击穿，并结合我的笔记回答",
  "mode": "RAG", "kbIds": [1], "options": { "temperature": 0.3, "stream": true } }
```

响应 `Content-Type: text/event-stream`，事件序列：

| 事件 | 何时发送 | data 示例 |
| --- | --- | --- |
| `meta` | 立即 | `{"requestId":"...","conversationId":5001,"userMessageId":91,"assistantMessageId":92,"model":"deepseek-chat","mode":"RAG"}` |
| `retrieval` | 检索完成、生成前 | `{"count":6,"documents":[{"documentId":100,"name":"缓存击穿.md","headingPath":"Redis > 缓存 > 缓存击穿"}]}` |
| `delta` | 每个 token 片段 | `{"text":"缓存击穿是指"}` |
| `tool_call` | Agent/工具场景 | `{"toolCode":"searchKnowledge","arguments":{...},"step":1}` |
| `tool_result` | 工具返回 | `{"toolCode":"searchKnowledge","success":true,"summary":"命中 6 个片段","durationMs":134}` |
| `approval_required` | 需要人工确认 | `{"approvalId":"a1","toolCode":"createStudyPlan","arguments":{...},"reason":"该操作会写入你的学习计划"}` |
| `citation` | 生成结束后 | `{"citations":[{...}]}` |
| `usage` | 结束前 | `{"inputTokens":1820,"outputTokens":240,"latencyMs":2900,"firstTokenMs":380}` |
| `done` | 正常结束 | `{"finishReason":"stop","grounded":true}` |
| `error` | 异常结束 | `{"code":4002,"message":"模型响应超时","retryable":true}` |

**实现约束**

1. 用 `SseEmitter`（Spring MVC）而不是 WebFlux：全栈保持 MVC，避免为流式引入两套编程模型；`SseEmitter` 的超时设 5 分钟，每 15 秒发注释行心跳防代理断连。
2. 事件顺序有语义：`retrieval` 必须在第一个 `delta` 之前（前端可先展示"检索到 6 个片段"）。
3. 停止生成：`delta` 循环每片检查一次 `akh:stream:cancel:{requestId}`，命中即终止并落库部分内容。
4. 断线重连：给每个事件带 `id:`，客户端可用 `Last-Event-ID` 续传（第一版只保证"不重复插入消息"，不做严格续传）。
5. 前端用 `fetch` + `ReadableStream` 解析 SSE（因为要带 JWT 头），不使用 `EventSource`。

---

## 6. Agent 与工具（Phase 5–6）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/tools` | 工具列表（来源 BUILTIN/MCP、权限级别、是否需确认、启用状态） |
| GET | `/api/tools/{code}` | 工具详情（入参 JSON Schema、最近调用记录） |
| POST | `/api/agents` | 创建 Agent（名称、系统提示词、模型角色、最大步数、超时、工具白名单） |
| GET | `/api/agents` | Agent 列表 |
| GET | `/api/agents/{id}` | Agent 详情（含权限矩阵） |
| PUT | `/api/agents/{id}/tools` | 配置工具白名单与确认策略 |
| POST | `/api/agent/runs` | 发起一次 Agent 任务 → `runId`（可流式，同 SSE 协议） |
| GET | `/api/agent/runs/{runId}` | 运行状态（RUNNING/WAITING_APPROVAL/DONE/FAILED/MAX_ITERATIONS） |
| GET | `/api/agent/runs/{runId}/steps` | 完整步骤轨迹（THINK/TOOL_CALL/OBSERVATION/ANSWER） |
| POST | `/api/agent/runs/{runId}/approval` | 人工确认：`{"approved":true,"reason":"..."}` → 恢复执行 |
| POST | `/api/agent/runs/{runId}/cancel` | 取消运行 |
| GET | `/api/tool-invocations` | 工具调用审计流水（按时间/工具/成功与否过滤） |

```http
POST /api/agent/runs
{ "agentCode": "knowledge-analyst", "goal": "分析我的 Redis 学习情况并给出 7 天计划",
  "kbIds": [1], "stream": true }
→ SSE: meta → tool_call(searchKnowledge) → tool_result → ... → approval_required(createStudyPlan)
   → (用户确认) → tool_result → delta → citation → usage → done
```

**权限语义**：`READ` 直接执行；`WRITE`/`DANGEROUS` 必须 `approval_required` 且用户确认后执行；拒绝时把"拒绝"作为观察结果回灌给模型，让它换方案而不是失败退出。

---

## 7. MCP（Phase 7）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/mcp/servers` | 已注册的 MCP Server（名称、URL、状态、工具数） |
| POST | `/api/mcp/servers/{name}/refresh` | 重新拉取工具清单（MCP 工具可动态变化） |
| GET | `/api/mcp/servers/{name}/tools` | 该 Server 暴露的工具与 Schema |

MCP Server 进程自身暴露（Streamable HTTP）：

| 能力 | 名称 | 权限 |
| --- | --- | --- |
| Tool | `searchKnowledge` | READ |
| Tool | `getDocument` | READ |
| Tool | `searchByTag` | READ |
| Tool | `listKnowledgeBase` | READ |

---

## 8. 工作流（Phase 8）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/workflows` | 创建/导入工作流定义（nodes + edges） |
| GET | `/api/workflows` | 工作流列表 |
| GET | `/api/workflows/{id}` | 定义详情（含图结构与节点配置） |
| PUT | `/api/workflows/{id}` | 更新（发布新版本） |
| POST | `/api/workflows/{id}/runs` | 触发执行 → `runId` |
| GET | `/api/workflow-runs/{runId}` | 运行状态与当前节点 |
| GET | `/api/workflow-runs/{runId}/nodes` | 各节点执行明细（输入/输出/耗时/重试次数） |
| POST | `/api/workflow-runs/{runId}/approval` | 人工确认节点放行 |
| POST | `/api/workflow-runs/{runId}/retry` | 从失败节点重试 |

**预置工作流**：`knowledge-gap-analysis`（知识分析）、`study-plan-7d`（7 天学习计划）、`knowledge-report`（知识报告生成）。

---

## 9. 评测（Phase 9）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/eval/datasets` | 创建评测集 |
| POST | `/api/eval/datasets/{id}/import` | 导入 JSON/YAML 测试问题（question + expectedSource 等） |
| GET | `/api/eval/datasets/{id}/cases` | 用例列表 |
| POST | `/api/eval/runs` | 批量执行（可指定 prompt 版本、chunk 参数、topK、是否 rerank）→ `runId` |
| GET | `/api/eval/runs` | 历史运行列表（用于跨版本对比） |
| GET | `/api/eval/runs/{runId}` | 汇总指标（平均 HitRate/MRR/Faithfulness/Citation 准确率） |
| GET | `/api/eval/runs/{runId}/cases` | 逐用例结果（含检索结果、回答、引用、裁判理由） |
| GET | `/api/eval/runs/compare?base=1&target=2` | 两个 run 的指标差异对比 |

```http
POST /api/eval/datasets/1/import
[ { "question": "Spring Boot 自动配置是什么？", "expectedSource": "Spring/SpringBoot.md" },
  { "question": "Redis 缓存击穿怎么解决？", "expectedSource": "Redis/缓存.md" } ]
```

---

## 10. 观测（Phase 10）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/observability/overview` | 总览：调用次数、Token 消耗、平均延迟、首 Token 延迟、RAG 次数、工具次数、失败数、错误率、估算成本 |
| GET | `/api/observability/trend` | 时间趋势（按小时/天，scene/model 维度） |
| GET | `/api/observability/models` | 模型维度统计（调用数、Token、延迟、成本、错误率） |
| GET | `/api/observability/requests` | 调用明细（按 requestId/会话/场景/成功与否过滤） |
| GET | `/api/observability/requests/{requestId}` | 单次调用全链路（检索文档、工具调用、Token、延迟、错误） |

---

## 11. 提示词管理（Phase 9 前置）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/prompts` | 提示词模板列表（按场景） |
| POST | `/api/prompts` | 创建模板（code 如 `rag.answer`、`agent.planner`） |
| GET | `/api/prompts/{id}/versions` | 版本列表（DRAFT/PUBLISHED/ARCHIVED） |
| POST | `/api/prompts/{id}/versions` | 新建草稿版本（content、变量、temperature、maxTokens） |
| POST | `/api/prompts/{id}/versions/{v}/publish` | 发布（同模板下旧版本自动 ARCHIVED） |
| POST | `/api/prompts/{id}/rollback` | 回滚到指定历史版本（实现为发布一个新的等价版本，不篡改历史） |

---

## 12. 限流与配额

| 接口组 | 默认限制 |
| --- | --- |
| 认证 | 10 次/分钟/IP（防撞库） |
| 聊天/流式 | 20 次/分钟/用户；5 并发流/用户 |
| 检索 | 60 次/分钟/用户 |
| 上传 | 10 次/分钟/用户；单文件 50 MB；ZIP 200 MB |
| Agent 运行 | 5 次/分钟/用户；最长 5 分钟/次 |
| 评测批量 | 1 次/分钟/用户（后台异步跑） |

实现：Redis 令牌桶（`akh:ratelimit:{scope}:{userId}`），超限返回 `429` + `7001`，响应头带 `Retry-After`。

---

## 13. 接口与 Phase 对照

| 接口组 | 落地 Phase |
| --- | --- |
| `/api/auth/**`、`/api/users/**` | Phase 0 |
| `/api/chat/**`（含 SSE） | Phase 1（RAG 部分 Phase 3） |
| `/api/knowledge-bases/**`、`/api/documents/**`、`/api/import-tasks/**` | Phase 2 |
| `/api/search/**` | Phase 3 |
| `/api/tools/**` | Phase 5 |
| `/api/agents/**`、`/api/agent/runs/**`、`/api/tool-invocations` | Phase 6 |
| `/api/mcp/**` | Phase 7 |
| `/api/workflows/**`、`/api/workflow-runs/**` | Phase 8 |
| `/api/eval/**`、`/api/prompts/**` | Phase 9 |
| `/api/observability/**` | Phase 10 |
