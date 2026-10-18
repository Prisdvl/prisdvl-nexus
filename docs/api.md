# API 设计（Prisdvl-Nexus）

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

#### 失败归因表（Phase 5.2）

"模型调用失败"曾经是所有生成期故障的唯一说法——撞到工具轮数上限、密钥过期、网关超时，
用户看到的都是同一句话。归错不会让程序崩，只会让所有人沿着错误方向排查，因此判定收敛到
**一处**：`FailureClassifier`（`nexus-ai`），下列入口共用它，所以同一个故障在所有入口
报的是同一个码、同一句话：

| 判定顺序 | 异常 | 码 / HTTP | retryable | 给用户的话 |
| --- | --- | --- | --- | --- |
| 1 | `ToolCallLimitExceededException`（分单工具 / 合计两种） | `5002` / `500` | ✗ | 哪个工具、上限几次（确定性上限，重试必然再撞） |
| 2 | `BusinessException`（任何域） | 它自己的码 | 按域 | 它自己的文案（已判定过的事实，不被下面的猜测覆盖） |
| 3 | 401/403 类（`*Unauthorized*`、`*Authentication*`…） | `4007` / `502` | ✗ | 密钥无效或已过期，请联系管理员 |
| 4 | 超时（`*Timeout*`、`SocketTimeoutException`） | `4002` / `504` | ✓ | 模型响应超时，请稍后重试 |
| 5 | 其余未知故障 | `4001` / `503` | ✓ | 模型调用失败，请稍后重试 |

- 判定沿 cause 链上溯（Spring AI 会包 `RetryExhausted → WebClientRequest → ConnectException`），
  只看最外层等于永远落到第 5 行。
- 第 3 行刻意不匹配 `AccessDenied`：在 Spring Security 里那是"**我们的**接口被越权访问"，
  与"上游拒绝我们的密钥"是两件相反的事。
- **同一个故障在各入口的口径**（2026-10-01 真机实测，无效 Key）：`/api/agent/ask`、
  `/api/chat/conversations/{id}/messages` 均返回 `502 / 4007`；SSE 的 `error` 帧
  `{"code":4007,"retryable":false,"message":"…"}`。修复前这三处分别是
  `500 / 9999 系统内部错误`、`502 / 4001` + **上游原文**、`4001 / retryable=true`。
- 生成失败**不再是"一种回答"**：同步 RAG（`POST /api/knowledge-bases/{id}/ask`）此前会把
  `"生成回答失败：" + ex.getMessage()` 当成 200 的答案返回，并落进 `kb_qa_record`；
  现在抛业务异常，且不落档（留档只记真实发生过的问答）。
- `chat_message.error_msg` 存的是**给用户的那句话**（不是上游原文），它会经
  `GET /api/chat/messages/{id}` 返回给前端，因此原文只留在日志里、靠 `requestId` 关联。

### 1.3 错误码规划

| 段 | 域 | 示例 |
| --- | --- | --- |
| `0` | 成功 | `0 success` |
| `1xxx` | 通用/参数 | `1001 参数校验失败`、`1002 请求体格式错误`、`1003 不支持的排序字段` |
| `2xxx` | 认证授权 | `2001 未认证`、`2002 Token 已过期`（**当前无抛出点**：`RestAuthenticationEntryPoint` 不区分过期与非法）、`2003 无权限访问该资源`、`2004 账号被禁用`、`2005 用户名已被占用`、`2006 用户名或密码错误`、`2007 邮箱已被占用` |
| `3xxx` | 知识库/文档 | `3001 知识库不存在`、`3002 知识库名称重复`、`3003 文档不存在`、`3004 DocumentParseFailed 文档解析失败`、`3005 文件类型不支持`、`3006 文件超出大小限制`、`3007 ZIP 结构非法`、`3008 导入任务不存在` |
| `4xxx` | AI 模型/嵌入/向量 | `4001 ModelUnavailable 模型不可用`、`4002 ModelTimeout 模型超时`、`4003 TokenLimitExceeded 超出上下文限制`、`4004 EmbeddingFailed 嵌入失败`、`4005 VectorStoreUnavailable 向量库不可用`、`4006 未配置对应逻辑角色的模型`、`4007 ModelAuthFailed 模型服务鉴权失败`（密钥被上游拒绝；**不可重试**，与 4001 的区别见 1.2 的错误归因表） |
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
| 文件上传 | `multipart/form-data`；单文件 ≤ 50 MB，ZIP ≤ 200 MB（可配）；一次最多 200 个文件。超过 `spring.servlet.multipart.max-file-size` 时返回 `413` + code `3006`，文案里带**配置的实际值**（形如"单文件上限 200MB，单次请求上限 220MB"），不写死数字 |
| 长任务 | 一律返回 `taskId`，用 `GET /api/import-tasks/{id}` 或 SSE 获取进度 |
| 流式接口 | `POST` + `Accept: text/event-stream`（不用 `EventSource`，因为它无法带 `Authorization` 头） |

### 1.6 序列化契约：`Long` 一律以 JSON **字符串**返回

**这是全项目统一的约定，不是单接口特例。**

雪花ID（如 `2104750903666438145`）是 19 位十进制数，**超出 JavaScript `Number.MAX_SAFE_INTEGER`（2^53−1 = 9007199254740991）**。若按数字返回，前端解析后会变成 `2104750903666438144` —— 表现为"拿到的 id 查不到数据"，**且不报错**，是最难排查的一类 bug。

**实现**：`nexus-app/.../infra/jackson/LongToStringJacksonComponent.java`
（Boot 4 的 Jackson 3 写法：`@JacksonComponent` + `ValueSerializer<Long>`）。

| Java 类型 | JSON 形态 | 前端类型 |
| --- | --- | --- |
| `Long`（包装类型） | `"2104750903666438145"` | `string` |
| `int` / `long`（基本类型） | `123` | `number` |
| `Integer` / `Double` | `123` / `0.93` | `number` |

⚠️ **DTO 编写约定（务必遵守）**：既然 `Long` 会变成字符串，
**计数类字段用 `int` / `long` 基本类型**（保持数字形态），**只有 ID 类字段才用包装类型 `Long`**。
否则计数字段会意外变成字符串。

> ⚠️ 已知缺口：`LongToStringJacksonComponent` 的 javadoc 提到有一个
> `LongToStringSerializationTest` 断言这条约定，但**该测试在当前仓库里不存在**（已核实）。
> 即这条约定目前**没有自动化测试守护**，改DTO 时需人工留意。
>
> 迁移自已删除的 `docs/project-tutorial.md`（2026-10-10）。

---

## 2. 认证与账号（Phase 0；账号安全 2026-10-02；Refresh / 会话 Phase 4）

### 2.1 已实现

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/auth/register` | 注册（用户名 + 密码），密码 BCrypt 存储；按**来源 IP** 限流（10 次/小时，超限 `429/7001`） |
| POST | `/api/auth/login` | 登录 → `accessToken`（默认 30m）+ `refreshToken`（默认 7d，opaque）+ `expiresIn` + `user`；连续失败冻结 → `429/7001` |
| POST | `/api/auth/refresh` | 用 refresh 换新的 access + refresh（**rotation**：旧 refresh 立即失效）；公开端点 |
| POST | `/api/auth/logout` | 撤销当前 refresh 会话（body：`refreshToken`）；access 不立刻删，靠 `exp` / `token_version`；公开端点 |
| GET | `/api/users/me` | 当前用户信息与角色 |
| PUT | `/api/users/me` | 修改资料（昵称 / 邮箱） |
| PUT | `/api/users/me/password` | 修改密码（校验原密码；成功后 `token_version+1` **且**撤销全部 refresh 会话） |
| GET | `/api/users/me/sessions` | 当前用户未撤销且未过期的设备会话列表（可选头 `X-Refresh-Token` 标记 `current`；**不回传 token/hash**） |
| DELETE | `/api/users/me/sessions/{id}` | 退出指定设备（只能删自己的；否则 `404/2010`） |
| POST | `/api/users/me/sessions/revoke-all` | 退出全部设备（批量 revoke） |
| GET | `/api/users/me/quota` | 当前用户当日额度（只读，用于界面上"今日 N/M 次 · x/y tokens"那一行） |

```http
POST /api/auth/login
{ "username": "lky", "password": "******" }

200 OK
{ "code": 0, "message": "success", "data": {
    "accessToken": "eyJ...",
    "refreshToken": "<opaque>",
    "tokenType": "Bearer",
    "expiresIn": 1800,
    "user": { "id": "...", "username": "lky", "nickname": "...", "email": null,
              "emailVerified": false, "roles": ["USER"] } } }
```

```http
POST /api/auth/refresh
{ "refreshToken": "<opaque>" }

200 OK
{ "code": 0, "data": {
    "accessToken": "eyJ...", "refreshToken": "<new-opaque>",
    "tokenType": "Bearer", "expiresIn": 1800 } }

# 已用过 / 已撤销 / 已过期 → 401 / 2001
```

**`/users/**` 全部没有 userId 参数**：身份只从 JWT 的 `sub` 取（`CurrentUser.requireId()`），
从请求参数或请求体接收 userId 是水平越权的典型来源（ADR-018 第 8 条）。

#### 修改资料 `PUT /api/users/me`

```http
PUT /api/users/me
{ "nickname": "老孔", "email": "lky@example.com" }
```

**字段语义是"半更新"，两条规则要一起看**：

- **不传某个字段（`null`）= 不修改**；
- **传空串 = 清空**（邮箱清空后落 `NULL`；昵称清空后界面回退显示用户名）。

一个值不能同时表示"不改"和"清空"，所以这两种意图在 JSON 里必须是不同取值。
若把"缺失"当成"清空"，前端少渲染一个输入框就会静默删掉用户数据。

错误：邮箱与其他账号重复 → `409 / 2007`（与 `2005` 用户名重复对称）。同一预检也加在注册入口上——
在此之前，用重复邮箱注册会撞唯一键 → `DataIntegrityViolationException` → `500/9999`，用户看到的是"服务器错误"。

#### 修改密码 `PUT /api/users/me/password`

```http
PUT /api/users/me/password
{ "oldPassword": "******", "newPassword": "******" }

// 原密码错误：400（不是 401）
{ "code": 1001, "message": "原密码不正确" }
```

**为什么原密码错误返回 `1001 / 400` 而不是 `2006 / 401`**：调用方已经用自己的令牌通过了认证，
此时回 401 会让前端全局拦截器（`frontend/src/lib/api.ts` 的响应拦截器）**立刻清 token 并跳登录页**——
把一个"打错一个字"的输入错误升级成会话中断。（此处也不构成账号枚举：攻击者必须已持有该账号的令牌。）
代价是登录失败（`2006`）与改密失败（`1001`）口径不同，这是**刻意的区分**：
登录 = 未认证；改密 = 已认证的参数错误。（原 Vue 版 E2E）`docs/archive/web-vue/scripts/verify-security.mjs` 有一条断言专门守这个分支，该脚本已随 `web/` 归档。

**改密成功后本次会话也会失效**：`sys_user.token_version` 自增（access JWT，见 ADR-020），
并批量撤销该用户全部 refresh 会话（`sys_user_session`）。这是"改密码 = 所有设备下线"的正确语义，
前端必须明说「密码已修改，请用新密码重新登录」并主动跳登录页，而不是等下一个请求撞 401。
忘记密码确认成功同样：`token_version+1` + 撤销全部 refresh 会话。

#### 设备会话

列表项形状：`{ id, device, ip, lastUsedAt, createdAt, current }`。  
前端设置页「会话与设备」调用上述三个 sessions 接口；`api.ts` 在 access 401/2001 时用单飞 Promise 调 `/auth/refresh` 并重放原请求。

#### 当日额度 `GET /api/users/me/quota`

```http
GET /api/users/me/quota

200 OK
{ "code": 0, "data": {
    "requestUsed": 12, "requestLimit": 200,
    "tokenUsed": 3421, "tokenLimit": 200000,
    "resetAt": "2026-10-03T00:00:00" } }
```

`requestUsed` 可能**大于** `requestLimit`：被拒的请求也占一次计数（否则连点没有成本），
界面展示时用 `min(已用, 上限)` 收敛。

### 2.2 规划中（尚未实现）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/admin/users` | 管理员：用户列表（`ADMIN` 角色） |

> Phase 4 已落地：opaque refresh + rotation + `sys_user_session`（Flyway V21）+
> `/auth/refresh` / `/auth/logout` / `/users/me/sessions*`。
> Access 仍是 HS256 JWT（`sub` + `ver`）；**不做** jti 黑名单、不做 Redis 存 refresh。
> Token 仍在前端 `localStorage`（XSS 窗口见 ADR-021）；HttpOnly Cookie 迁移仍属后续。

---

## 3. 知识库与文档（Phase 2）

### 3.1 知识库

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/knowledge-bases` | 创建（name、description、icon、chunkStrategy、retrievalTopK、similarityThreshold） |
| GET | `/api/knowledge-bases` | 我的知识库列表（含 doc/chunk 统计、最近同步时间） |
| GET | `/api/knowledge-bases/{id}` | 详情（含 embedding 模型、维度、切分参数） |
| PUT | `/api/knowledge-bases/{id}` | 改名/描述/图标/enabled 状态/检索参数（**不允许**改 embedding 模型，见 ADR-008） |
| DELETE | `/api/knowledge-bases/{id}` | 删除知识库（级联删除文档、Chunk、向量、ES 文档） |
| GET | `/api/knowledge-bases/{id}/tree` | 目录树（供左侧栏：Java / Spring / Redis…） |
| GET | `/api/knowledge-bases/{id}/tags` | 标签云与计数 |
| GET | `/api/knowledge-bases/{id}/stats` | 文档数/Chunk 数/Token 数/向量数/失败数 |

平台化字段（Phase 2，均可选，缺省见下）：

| 字段 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `workspaceId` | Long | 个人工作区 | 所属工作区，服务端解析后写入，**请求里不需要传** |
| `icon` | String(≤512) | null | 图标（URL 或 emoji） |
| `status` | String | `ENABLED` | `ENABLED` / `DISABLED` |
| `retrievalTopK` | Integer(1–50) | 5 | 该库的检索 TopK |
| `similarityThreshold` | Double(0–1) | 0.25 | 相似度阈值 |

> 上表默认值即 `nexus.retrieval.*`（Phase 3.2 起为唯一来源）。检索时的优先级是
> **请求参数 > 知识库配置 > 全局默认**；问答/检索接口的 `topK` **不传**即表示"用该知识库的 `retrievalTopK`"。


### 3.2 文档

文档一律挂在知识库下（嵌套路由），不存在跨库的 `/api/documents`——归属校验因此只有一处（路径上的 `{kbId}`），
下面所有 `{id}` 都是 `{documentId}`：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/knowledge-bases/{kbId}/documents` | **单文件上传（Phase 5.3 · P1-1）**：md/txt/pdf/docx → `taskId` |
| POST | `/api/knowledge-bases/{kbId}/vault` | 上传 Obsidian Vault ZIP → `taskId` |
| GET | `/api/knowledge-bases/{kbId}/documents` | 文档分页列表（按 folder/tag/status/关键词过滤） |
| GET | `/api/knowledge-bases/{kbId}/documents/{id}` | 文档详情（frontmatter、标签、标题树、图片、关联文档、Chunk/向量状态） |
| GET | `/api/knowledge-bases/{kbId}/documents/{id}/chunks` | Chunk 列表（含 heading_path、token 数、向量状态） |
| GET | `/api/knowledge-bases/{kbId}/documents/{id}/links` | 关联文档（WikiLink / Markdown Link，含未解析目标） |
| GET | `/api/knowledge-bases/{kbId}/documents/{id}/related` | 相关推荐（按关联度排序） |
| DELETE | `/api/knowledge-bases/{kbId}/documents/{id}` | **删除单篇文档（Phase 5.3 · P1-2）**，见下 |

#### 单文件上传（Phase 5.3 · P1-1）

```http
POST /api/knowledge-bases/1/documents   (multipart: file=笔记.md)
200 OK
{ "code": 0, "data": { "id": 9001, "taskType": "UPLOAD",
                       "status": "QUEUED", "totalFiles": 1, ... } }
```

- **复用整条异步导入链路**，没有第二条流水线：同一张 `kb_import_task`、同一个执行器、
  同一套状态机与重试端点。区别只有两处——
  - 任务 `taskType = UPLOAD`，文件名记在 `kb_import_task.source_name`（V15 新列；
    ZIP 导入是 `VAULT_ZIP` / 压缩包名）。该列**不随响应返回**，执行器内部用它确定导入目标；
  - **不做删除检测**：单文件上传的"清单"只有这一个文件，按它去比对会把库里其余文档全删掉
    （`VaultSyncPlanner` 的 `detectDeletions=false`，这个口子本来就是为它留的）。
- **扩展名校验在服务层**（`ParserRegistry.requireParser`），不是只在 Controller：
  这样别的调用方（未来的 MCP 工具）绕过 HTTP 也会拿到 `3005 文件类型不支持` +
  "当前支持哪些格式"，而不是一个含糊的"导入失败"。
- **不适用压缩包里那套 20MB 单条目上限**：直接上传是用户显式点名的文件，
  `VaultScanOptions.DEFAULT_MAX_FILE_BYTES` 是给 ZIP 里来路不明的条目设的防线，
  多一道 20MB 的闸只会让合法的 25MB PDF 被拒。服务层改用**压缩包整体上限**
  `DEFAULT_MAX_TOTAL_BYTES`（200MB）判定，与 Spring 的 `max-file-size` 对齐——
  校验放在服务里而不只靠 multipart，换一个调用方（MCP、批量导入）也成立。
  超限 → `3006`；Spring 层先拦下的则是 `413 / 3006`，见 1.2。
- 落库的文档 `sourceType = UPLOAD`（此前该枚举值不可达，详见 `docs/decisions.md` B.44）。
- **重复上传同名同内容**不会报错：增量同步判定 `SKIPPED`，并在 `items` 里给出一条
  "文件未变化，已跳过（未重新解析，也未重新嵌入向量）"——这正是 P1-3 要解决的问题。

#### 删除单篇文档（Phase 5.3 · P1-2）

```http
DELETE /api/knowledge-bases/1/documents/7
200 OK  { "code": 0, "data": null }
```

删除会连着清六处数据（少清哪一处都不会报错，只会留下看不见的残留）：

| 清理对象 | 语义 |
| --- | --- |
| 向量库（pgvector） | 按 `document_id` 清 |
| 检索索引（ES） | 按 `document_id` 清 |
| `kb_chunk` / `kb_document_tag` | **物理删除**（量大且无审计价值） |
| `kb_document_link` 的**出链** | 删文本档指向别人的边；**保留入链**——别人指向它的边会退化成"待创建的引用"，这是 Obsidian 语义 |
| 磁盘上的原始文件 | 不留垃圾 |
| `kb_knowledge_base` 的统计 | 重算 `docCount` / `chunkCount` / `totalTokens` |

- **外部清理在前，且失败即整体失败（fail-closed）**：向量/ES 不在 MySQL 事务里，
  无法回滚，所以顺序只能是"先外部、后 MySQL"。外部失败 → 事务回滚 → 文档原封不动，
  用户重试即可；反过来会在向量库里留下**检索命中却取不出正文**的孤儿，且没有任何入口能修。
- **同名路径的墓碑会被先清掉**：`uk_doc_kb_path (kb_id, source_path, deleted)` 是逻辑删除陷阱——
  同一路径第二次逻辑删除会写出第二行 `deleted=1` 而撞唯一键（传 → 删 → 再传 → 再删即可复现）。
- 错误：库不属于该用户 → `3001`；文档不在该库里（或不存在）→ `3003`。两者都是 404，不泄露资源存在性。

### 3.3 导入任务

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/import-tasks/{taskId}` | 任务状态与进度（总数/新增/更新/跳过/删除/失败/百分比） |
| GET | `/api/import-tasks/{taskId}/items` | **逐文件明细（Phase 5.3 · P1-3）**，见下 |
| POST | `/api/import-tasks/{taskId}/retry` | 只重试失败项（复用暂存包） |

> `/stream`（SSE 实时进度）与 `/cancel` 在 API 设计文档里规划过，但**没有实现**——
> 前端目前靠 1.2s 轮询 `GET /api/import-tasks/{taskId}` 拿终态。规划与实际不符的以本节为准。

#### 任务状态机（`ImportTaskStatus`）

```
QUEUED → RUNNING → ┬→ DONE       全部成功
                   ├→ PARTIAL    部分成功（有失败项）
                   ├→ FAILED     全部失败
                   └→ CANCELLED  被取消
```

**终态是 4 个**：`DONE` / `PARTIAL` / `FAILED` / `CANCELLED`。
轮询判断"是否已结束"时**必须把这 4 个都算作终态** —— 只认 `DONE` / `FAILED`，
会让"3 成功 2 失败"这类任务**永远转圈**。

⚠️ `PARTIAL` 是**刻意保留**的（见 `nexus-knowledge/.../model/ImportTaskStatus.java` 源码注释）：
批量导入时"部分成功"远比"全失败"常见，用独立状态把它与"全部成功"区分开，
用户才知道是否需要处理失败项。**不要在解析时把它归并进 `DONE` 或 `FAILED`。**

另：`PARTIAL` 与 `FAILED` 都可被抢占重试（见 `ImportTaskExecutor`）。

> 迁移自已删除的 `docs/project-tutorial.md`（2026-10-10）。

#### 逐文件明细（Phase 5.3 · P1-3 / P1-4）

```http
GET /api/import-tasks/9001/items?action=FAILED&limit=100
200 OK
{ "code": 0, "data": [
    { "id": 55012, "sourcePath": "坏文件.pdf", "action": "FAILED",
      "documentId": null, "chunkCount": 0, "durationMs": 12,
      "message": "解析失败：不是有效的 PDF（missing %PDF header）",
      "createdAt": "2026-10-01T19:20:31" } ] }
```

- **这张表此前只有写入方、没有任何读接口**：于是"失败 3 个"是一个没有下文的数字，
  用户只能去翻服务端日志——而 `kb_import_task_item` 存在的全部意义就是回答"为什么这篇没进来"。
- `action` 可选过滤，大小写不敏感（`FAILED` / `failed` / `SKIPPED` / `skipped`）；
  不传则返回全部。`limit` 默认 100，**收敛到 1–500**（0 抬到 1，超大值压到 500）——
  一个 Vault 会有上千行明细，不能让一次查询全拖回来。
- **`SKIPPED` 现在真的会被写入了**（此前是死枚举）：扫描期被跳过的条目
  （`.pptx`、隐藏目录、空文件…）以前只进计数、不落明细，
  于是用户最常问的"我的 `.pptx` 为什么没进来"恰恰是明细答不了的那一类。现在每条都带上原因。
- **一处刻意的不对称**：整包同步时"内容未变化"的文件**只计数、不逐条落明细**——
  否则一次全量重导会插入上千行同样的记录（`VaultIncrementalSyncTest` 守着这条）。
  单文件上传只有一行，因此会如实给出"文件未变化，已跳过"。
  界面必须解释这个差异（`task.skippedCount` 可能大于明细条数），不能让用户以为明细丢了。
- 归属校验与 `GET /api/import-tasks/{taskId}` 同一道：别人的任务一律 `3008`，
  不泄露文件清单。

```http
POST /api/knowledge-bases/1/vault   (multipart: file=MyVault.zip)

200 OK
{ "code": 0, "message": "success", "data": { "id": 9001, "taskType": "VAULT_ZIP",
                                             "status": "QUEUED", "totalFiles": 380 } }

GET /api/import-tasks/9001
{ "code": 0, "data": {
    "id": 9001, "status": "RUNNING", "progress": 42,
    "totalFiles": 380, "createdCount": 120, "updatedCount": 15, "skippedCount": 240,
    "deletedCount": 5, "failedCount": 0,
    "totalChunks": 5120, "errorMsg": null, "startedAt": "2026-09-24T02:11:00Z" } }
```

---

## 4. 检索与调试（Phase 3）

### 4.1 已实现：知识库内检索（带元数据过滤）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/knowledge-bases/{id}/index` | 为待索引切片建索引（幂等，`batchLimit` 默认 500） |
| POST | `/api/knowledge-bases/{id}/index/recreate` | 重建索引：新建物理索引 → 原子切别名 |
| GET | `/api/knowledge-bases/{id}/search` | 检索：`q`（必填）、`mode`、`topK`、`tags`、`folder`、`documentId` |

查询参数语义（Phase 3.3）：

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `q` | ✅ | 查询串 |
| `mode` | — | `bm25` / `vector` / `hybrid`（默认 hybrid）。不认识的值 → **400 + 1001**，不静默退回 hybrid |
| `topK` | — | **不传 = 用该知识库的 `retrievalTopK`**（优先级：请求 > 知识库配置 > `nexus.retrieval`）。越界夹到 1–100 |
| `tags` | — | 标签过滤，多个标签之间是 **OR**（与 Obsidian `tag:#a OR tag:#b` 一致）。支持 `?tags=a,b` 与 `?tags=a&tags=b`，两者可混用 |
| `folder` | — | 目录过滤，**包含子目录**（`算法` 匹配 `算法` 及 `算法/…`；不匹配 `算法入门`） |
| `documentId` | — | 限定文档，`?documentId=7&documentId=8` 或 `?documentId=7,8`；非数字 → 400 + 1001 |

> 多个条件同时给是 **AND**（逐个求交集）；标签内部是 OR；空值/空白项都表示"该条件不参与"。
> ⚠️ 与"条件给了、但在库内一篇都没命中"是两回事：后者**必须返回空结果**，
> 绝不能退化成"不过滤"（否则过滤会静默消失）。实现见 `RetrievalFilterResolver`。

```http
GET /api/knowledge-bases/1/search?q=缓存击穿&mode=hybrid&tags=Redis,缓存&folder=Redis

200 OK
{ "code": 0, "data": [
  { "chunkId": 20001, "documentId": 100, "documentTitle": "缓存击穿.md",
    "headingPath": "Redis > 缓存 > 缓存击穿", "content": "……互斥锁方案……",
    "score": 0.0324, "source": "hybrid", "matchedBy": ["BM25 #2", "向量 #1"] } ] }
```

### 4.2 规划中（尚未实现）

下表是目标形态，当前代码里**没有**这两个端点（`/api/knowledge-bases/{id}/search` 是今天的实际入口）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/search` | 统一检索（跨库 `kbIds`）：`mode=VECTOR\|BM25\|HYBRID`、`topK`、`rerank`、filters（kbIds/tags/folder/documentId） |
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

### 4.3 RAG 问答（已实现）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/knowledge-bases/{id}/ask` | 检索 → 重排 → 组装 → 生成，返回**带引用**的回答（同步） |
| POST | `/api/knowledge-bases/{id}/ask/stream` | 同上，SSE 流式（`text/event-stream`） |
| GET | `/api/knowledge-bases/{id}/qa-history` | 该库的问答留档（B.27） |

> `topK` 同样是"不传 = 用该知识库的 `retrievalTopK`"。
> 两者的关系与分工见 decisions.md B.31 第 7 条：**会话流式（`/api/chat/stream`）是主链路**，
> 这里的知识库问答接口是"单次问答/调试"入口，保留但不再叠加会话与记忆。

---

## 5. AI 会话与流式输出（Phase 1 / Phase 3）

### 5.1 会话与消息

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/chat/conversations` | 新建会话（title 可空，首轮后自动生成标题；可指定 mode/kbIds/agentId） |
| — | — | `mode` ∈ `PLAIN` / `RAG` / `AGENT`；`kbIds` 只在 `RAG` 下有意义，**可以绑多个**（Phase 5.5，上限 10 个，超限 1001）；重复 id 会去重并保持顺序 |
| — | — | `agentId`（Phase 4.1）只在 `AGENT` 下有意义，且必须是**自己已发布**的 Agent（草稿 5008、停用 5009、别人的 5006→404）；不传 = 用内置默认 Agent |
| GET | `/api/chat/conversations` | 会话列表（分页、置顶优先、按最后消息时间倒序） |
| GET | `/api/chat/conversations/{id}` | 会话详情（含配置：mode、kbIds、agentId、模型角色、prompt 版本） |
| PUT | `/api/chat/conversations/{id}` | 改标题 / 置顶 / 绑定知识库 / 换 Agent（`agentId` 为 null = 不改；`resetAgent:true` = 回到内置默认，见下）。⚠️ 校验用**目标模式**，见下 |
| DELETE | `/api/chat/conversations/{id}` | 删除会话。⚠️ **不可恢复，且消息被物理删除**，见下方说明 |
| GET | `/api/chat/messages/{conversationId}` | 历史消息（分页，含引用、Token、耗时） |
| POST | `/api/chat/messages` | 非流式发送（用于简单场景与自动化测试） |
| POST | `/api/chat/stream` | **SSE 流式发送**（见 5.2） |
| POST | `/api/chat/messages/{messageId}/regenerate` | 重新生成（删除该 assistant 消息重跑，保留原 prompt 版本） |
| POST | `/api/chat/stream/{requestId}/stop` | 停止生成（写 Redis 取消标记；服务端落库 `status=STOPPED`，保留已生成内容） |
| POST | `/api/chat/messages/{messageId}/feedback` | 点赞/点踩 + 备注（回流评测集） |

#### ⚠️ `DELETE /api/chat/conversations/{id}` 的三重副作用（不可恢复）

实现见 `ConversationServiceImpl.delete`，它**同时做三件事**：

| # | 动作 | 后果 |
| --- | --- | --- |
| 1 | 会话**逻辑删除**（`@TableLogic` → `UPDATE deleted=1`） | 会话行仍在库里，但列表查询不再返回 |
| 2 | 该会话的**消息被物理删除** | ⚠️ **不是软删，消息真的没了** |
| 3 | `chatMemory.clear("conv:{id}")` | 该会话的对话记忆窗口清空 |

因此：

- ❌ **不能把本端点当"归档"用**。"归档"是**可恢复**的语义，与本端点的**不可恢复**是两种不同产品语义。
  若用 DELETE 实现归档，用户点"恢复"后只会得到一个**空会话**。
- ✅ **要"可恢复的收起"，前端应走本地归档**（从主列表隐藏、入口可找回），**不调用本端点**。
- 为什么必须清记忆（不只是"多留几行无主数据"）：记忆是"这次发给模型多少历史"的唯一来源，
  一旦该 id 被重新读到（重建会话、同 id 复用），新会话会**凭空记得旧会话说过的话**。
- 为什么不软删消息：不删会随会话删除不断堆积无主数据。

> 迁移自已删除的 `docs/frontend-integration-decisions.md`（2026-10-10），已按当前实现核实。

#### 模式切换时，Agent / 知识库校验用**目标模式**（`targetMode`）

`update` 先算出 `targetMode = request.mode() == null ? 库里当前模式 : 解析(request.mode())`，
**后续所有校验都用 `targetMode`，不是库里的旧模式**。因此"先切模式再绑定"是**合法的一次请求**：

| 会话当前 | 请求 | 结果 |
| --- | --- | --- |
| `AGENT` | 只带 `resetAgent:true` | ✅ 清空 `agentId`（`targetMode` 仍是 AGENT） |
| `PLAIN` | 同时带 `mode:"AGENT"` + `resetAgent:true` | ✅ 合法：先切模式再重置 |
| `PLAIN` | 只带 `resetAgent:true`（不传 mode） | ❌ `PARAM_INVALID`（1001）："只有 AGENT 会话可以清空 Agent" |
| `PLAIN` | 带 `mode:"RAG"` + `kbIds` | ✅ 合法：切到 RAG 后立刻绑库 |
| `PLAIN` | 只带 `kbIds`（不切模式） | ❌ 被 `resolveKbIds` 挡下（1001） |

⚠️ **静默跳过比报错更坏**：`resetAgent` 的校验不能省。
早期实现写成"是 AGENT 才校验，否则静默跳过"，结果给 PLAIN 会话发 `resetAgent` 返回 `200`，
而"选用 Agent"路径却要求 `1001` —— 同一件事两种口径；
更糟的是"返回成功但什么都没变"，属于**假成功**（用户以为回到默认助手，实际 Agent 还挂着）。

实现见 `ConversationServiceImpl.update`（`targetMode` 约在 194 行）与 `requireAgentMode`。

> 迁移自已删除的 `docs/backend-findings-agent-constraints.md`（2026-10-10）。
> 该文档记录的两个具体缺陷均已修复，这里只保留当前真实语义。

**消息上的产出来源 `agentId`（5.4 · P1-14）**：`GET /api/chat/messages/{conversationId}`
返回的每条消息多一个 `agentId`。

- **只有助手消息有值，用户消息恒为 `null`**（用户消息不是任何 Agent 产出的；给它也写 id 会让
  "按 agent_id 聚合"把每轮对话重复计两次）；
- 值是**解析后**的具体 id：会话没选 Agent 时，落的是**内置默认那一行的真实 id**，不是 `null`——
  否则"这条是谁答的"永远没有答案；
- 它与 `Conversation.agentId` **不是一个东西**：那个是**活指针**（会话现在用哪个 Agent，可随时改），
  这个是**历史事实**（这条回答当时是哪个 Agent 跑的，插入即定）。会话换绑后旧回答仍指回原来那个；
- 5.4 之前的存量消息为 `null`（当时确实没记，不回填）。

**非流式入口的模式约束（5.4）**：`POST /api/chat/messages` 此前**完全不看会话模式**，
于是同一个会话走不同入口会得到两种答案，而且都不报错。现在：

| 会话模式 | 非流式 `POST /api/chat/messages` 的行为 |
| --- | --- |
| `PLAIN` | 正常回答（与之前一致） |
| `AGENT` | **正常回答**，且用的是该 Agent 的提示词 / 模型 / 工具（与流式链路同一份装配）；出处照记 |
| `RAG` | **报 `8003`**（HTTP 409），message 指向 `POST /api/chat/stream`；**在任何副作用之前拒绝**（不落用户消息、不调模型） |

`8003` 是"这个模式没走这个入口"，与 `1001`（参数填错）分开：前者换接口，后者改参数。
合并成一个码，用户会一直在参数上找问题。RAG 之所以是报错而不是顺手补上检索：
那需要把流式那条链路的"检索 → 无结果短路 → 提示词组装"在非流式下再实现一遍，
而多库 RAG（P1-6）本身还没定——现在接上来等于把两个未决问题焊在一起。

### 5.2 SSE 协议（本项目自定义事件）

```http
POST /api/chat/stream
Authorization: Bearer <accessToken>
Accept: text/event-stream
Content-Type: application/json

{ "conversationId": 5001, "content": "解释一下 Redis 缓存击穿，并结合我的笔记回答" }
```

> 上面是**当前实现**的请求体；设计稿里的 `mode` / `kbIds` / `options` 已收敛：
> `mode` 属于会话（建会话时定），检索范围由会话/知识库配置决定（见 5.2 末的"当前实现的事件子集"）。

响应 `Content-Type: text/event-stream`，事件序列：

| 事件 | 何时发送 | data 示例 |
| --- | --- | --- |
| `meta` | 立即（**已实现**） | `{"requestId":"...","conversationId":5001,"userMessageId":91,"assistantMessageId":92,"modelRole":"chat.default","mode":"RAG"}` |
| `retrieval` | 检索完成、生成前（**已实现**，Phase 3.4；多库形状见 5.5；失败明细见 5.5.1） | `{"knowledgeBaseIds":["5001","5002"],"count":2,"hits":[{"chunkId":"9001","documentId":"100","knowledgeBaseId":"5001","documentTitle":"缓存击穿.md","headingPath":"Redis > 缓存 > 缓存击穿","score":0.83,"source":"hybrid"}],"contextTokens":1200,"truncated":0,"reranked":true,"empty":false,"failedKnowledgeBases":[{"knowledgeBaseId":"5002","knowledgeBaseName":"内部 FAQ","reason":"ES 连接被拒绝"}]}` |
| `delta` | 每个 token 片段（**已实现**） | `{"text":"缓存击穿是指"}` |
| `tool_call` | Agent/工具场景（**已实现**） | `{"tool":"knowledge_search","input":"缓存击穿怎么解决"}` |
| `tool_result` | 工具返回（**已实现**） | `{"tool":"knowledge_search","input":"缓存击穿怎么解决","resultSummary":"3 条结果"}` |
| `approval_required` | 工具需要人工确认（**已实现**，P0：只对 `WRITE`/`DANGEROUS` 工具且用户没有 `always` 授权时发送） | `{"runId":"9","approvalId":"11","toolCode":"test_write_tool","arguments":"{\"payload\":\"…\"}","reason":"该工具会修改数据（WRITE），执行前需要你确认。"}` |
| `citation` | 生成结束后（Phase 3 预留，**仍未作为独立事件实现**） | 引用随 `done` 的 `citations` 字段给出，并同时落库（见下） |
| `usage` | 结束前（**已实现**） | `{"promptTokens":1820,"completionTokens":240,"totalTokens":2060,"latencyMs":2900,"firstTokenMs":380}` |
| `done` | 正常结束（**已实现**） | `{"finishReason":"stop"}`；用户停止时 `{"finishReason":"stopped","stopped":true}` |
| `error` | 异常结束（**已实现**） | `{"code":4001,"message":"模型调用失败，请稍后重试","retryable":true}`；`code`/`message`/`retryable` 由 `FailureClassifier` 判定，可能是 `4007`（密钥被拒，不可重试）、`4002`（超时）、`5002`（工具轮数超限）等，见 1.2 的失败归因表 |

⚠️ **两条 SSE 链路的帧结构不同，不要假设可以共用一个解析类型。**

上面这张表描述的是 `/api/chat/stream`（实现见 `ChatStreamServiceImpl`）。
知识库问答的 `/api/knowledge-bases/{id}/ask/stream`（实现见 `RagStreamServiceImpl`）字段名**不一样**：

| | `/api/chat/stream` | `/api/knowledge-bases/{id}/ask/stream` |
| --- | --- | --- |
| `done` 的 `finishReason` | `"stop"`；用户中止时 `"stopped"` 且带 `stopped: true` | `"empty"`（无命中）；出错时 `"error"` |
| 错误字段名 | `code` / `message` / `retryable` | **`errorCode` / `error`** / `retryable` |
| 引用 | 由 `usage` 帧或消息落库给出 | `done` 帧里带 `citations` |

即：**错误文案字段一个是 `message`、一个是 `error`**，
所以写不出一个兼容两者的解析器 —— 前端必须按链路分别处理。

> 迁移自已删除的 `docs/project-tutorial.md`（2026-10-10），字段名已按当前源码逐一核实。

**实现约束**

1. 用 `SseEmitter`（Spring MVC）而不是 WebFlux：全栈保持 MVC，避免为流式引入两套编程模型；`SseEmitter` 的超时设 5 分钟，每 15 秒发注释行心跳防代理断连。
2. 事件顺序有语义：`retrieval` 必须在第一个 `delta` 之前（前端可先展示"检索到 6 个片段"）。
3. 停止生成：`delta` 循环每片检查一次 `nexus:stream:cancel:{requestId}`，命中即终止并落库部分内容。
4. 断线重连：给每个事件带 `id:`，客户端可用 `Last-Event-ID` 续传（第一版只保证"不重复插入消息"，不做严格续传）。
5. 前端用 `fetch` + `ReadableStream` 解析 SSE（因为要带 JWT 头），不使用 `EventSource`。

**当前实现的事件子集**（Phase 3.4 实测口径）：

| 配置 | 事件序列 |
| --- | --- |
| `mode=PLAIN` | `meta` → `delta…` → `usage` → `done`（异常时 `error`） |
| `mode=RAG` | `meta` → **`retrieval`** → `delta…` → `usage` → `done`；知识库无命中时 `retrieval(count=0)` → 一句固定回答（**有库检索失败时措辞不同**，见下）→ `done{finishReason:"empty"}`（**不调模型**） |
| `mode=AGENT` | `meta` → (`tool_call` → `tool_result`)* → `delta…` → `usage` → `done` |

- 工具事件的实际字段是 `{tool, input, resultSummary}`（`tool` 为工具名，如 `knowledge_search`），
  **不是**设计稿早期的 `toolCode/arguments/step` 形状；工具痕迹同时随消息落库
  （`chat_message.metadata.toolCalls`），刷新后仍可回看。
- `retrieval` 的 `hits` 是**切片级**（与回答里的 `[n]` 编号一一对应），不是设计稿当初写的
  `documents` 文档级列表；`hits[]` 只带展示用的字段（不含正文片段），`meta` 里另有一份更精简的
  `citationPreview`。两条流式链路（`/api/chat/stream` 的 RAG 模式与
  `/api/knowledge-bases/{id}/ask/stream`）共用同一个构造器，因此事件形状完全一致。
- **`retrieval` 的知识库维度是列表**（Phase 5.5）：`knowledgeBaseIds` 是本次检索覆盖的库，
  `hits[].knowledgeBaseId` 是**这一片**出自哪个库。会话可以绑多个库，所以"检索到 N 个片段"
  不再能回答"资料从哪来"——出处必须逐条给。知识库问答流式那条链路固定单库
  （路径里的 `{id}`），因此 `knowledgeBaseIds` 恒为一个元素、`hits[].knowledgeBaseId` 恒等于它。
- `citation` 事件仍未实现（引用随 `done` 的 `citations` 字段给出）；
  `approval_required` **已实现**（P0）：事件来自工具执行壳的真实拦截点，
  发送前 `ai_tool_approval` 与运行状态已落库，`runId`/`approvalId` 就是提交审批要用的两个 id。
  客户端断开不影响持久化状态——刷新后用 `GET /api/agent/runs/{runId}` 仍能看到待审批，
  但**断开不等于批准**，工具不会执行。引用随 `done` 的 `citations` 字段给出，
  并**同时写进 `chat_message.metadata.citations`（Phase 5.9 五）**——刷新聊天页面后引用仍在。
- **RAG 流式生成失败**（`/api/knowledge-bases/{id}/ask/stream`，Phase 5.2）不再发 `error` 帧，
  而是把失败放进**最后一个 `done` 帧**：`{"finishReason":"error","error":"<给用户的话>","errorCode":4001,
  "retryable":true,"citations":[]}`。理由是这条链路此时已经产出过 `retrieval` 与部分 `delta`，
  收尾仍以 `done` 结束，前端只需要处理"一个终止帧"；`errorCode` 的取值同 1.2 的失败归因表。
  若失败前一个字都没产出，**不写问答留档**（`kb_qa_record` 只记真实发生过的问答）。
- **引用随消息落库（Phase 5.9 五）**：回答结束时把 `Citations.build` / `Citations.merge` 的结果
  （即 `done` 里那份 `citations`，含 `knowledgeBaseId` / `chunkId` / `documentId` / `documentTitle` /
  `headingPath` / `snippet` / `score` / `referenced`）写进 `chat_message.metadata.citations`，
  与工具痕迹**同一列**。刷新聊天页面后引用仍在，多库下还标得出"这一片出自哪个库"。
  用的是与同步问答同一个 `RagCitation`，**没有第二套引用系统、没有新表**。
  `referenced` 是"模型正文里有没有真的出现 `[n]`"——未引用也返回，用于核对模型有没有漏引。
- 仍然**不落库**的是 `retrieval` 事件里的命中列表本身：刷新后"检索到 N 个片段"那张卡片不再显示
  （正文与引用仍在）。引用列表回答的是"这条回答照着什么说的"，检索事件回答的是"这次搜到了什么"，
  两者不是同一件事；前者已留档，后者仍然只活在这一次连接里。
- `mode` 在**建会话时**指定（`POST /api/chat/conversations` 的 `mode` 字段），
  不是每次发送时指定；`/api/chat/stream` 的请求体只有 `conversationId` 与 `content`。
- **RAG 会话必须绑定知识库**：没绑就提问，在开流之前返回统一 JSON（HTTP 400 / code 1001，
  文案"该会话（RAG 模式）尚未绑定知识库，请先绑定后再提问"），而不是"悄悄退化成不检索"。
  绑定用 `PUT /api/chat/conversations/{id}` 的 `kbIds`：传 `[]` = 解绑，不传该字段 = 不改。
- **多库检索的口径（Phase 5.5）**：绑了多个库时**逐库检索后跨库归并**——
  每个库按**自己的** `retrievalTopK` / `similarityThreshold` 召回（不是共用调用方的 `topK`），
  然后按分数降序归并、再按全局 `topK` 截断（请求值，没传用 `nexus.retrieval.top-k` = 5）。
  因此**多绑库不会让上下文变长、也不会让回答变慢**。
  任意一个库不存在或不属于本人 → 整批 404 / `3001`，文案带出是哪个 id，不做部分成功。
- **逐库并行 + 失败隔离（Phase 5.5.1）**：上面那条管线不变，但**逐库那一段是并行跑的**
  （虚拟线程，整体 deadline 由 `nexus.retrieval.fanout-timeout` 控制，默认 10s），
  结果按**输入顺序**装配。单个库失败不再让整次检索报错，而是：
  - 失败的库在 `failedKnowledgeBases` 里点名（`{knowledgeBaseId, knowledgeBaseName, reason}`），
    `hits` 里不会有它的一条——所以 `count = 5, failedKnowledgeBases = [内部 FAQ]` 读作
    "这 5 段只来自其余库"，**不是**"内部 FAQ 里没有相关内容"；
  - 全部库都失败 → 整批发 `4005`（不把"全挂"降级成 `count = 0`）；
  - 空结果且**有**失败库时，固定回答换成"有知识库暂时无法检索…请稍后重试"，
    `done.finishReason` 仍为 `empty`——此时**不能**说"资料中没有相关内容"，那是假话
    （同上，模型也被明令不许这么说，见 `RagAnswerServiceImpl.userMessage`）。
  两种失败**不被隔离**，仍在扇出之前整批失败：归属校验（404/`3001`）与嵌入模型不一致（`4006`）。
- **换 Agent / 回到内置默认**（Phase 4.2，仅 `AGENT` 会话）：

  ```http
  PUT /api/chat/conversations/5001
  { "agentId": "12" }      // 换成某个已发布的 Agent
  PUT /api/chat/conversations/5001
  { "resetAgent": true }   // 回到内置默认 Agent（清空选用）
  ```

  `resetAgent` 必须是**独立信号**：本接口是局部更新，`agentId: null` 已经表示"这个字段不改"，
  一个值不能同时是"不改"和"清空"。两个字段同时传属自相矛盾的请求 → HTTP 400 / code 1001
  （不猜用户意图）；非 `AGENT` 会话传 `resetAgent` 同样 1001。


---

## 6. Agent 与工具（Phase 5–6）

### 6.0 已实现：Agent 资源（平台化 Phase 4.1）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/agents` | 我的 Agent 列表（内置默认 Agent 置顶；`pageNum` / `pageSize`） |
| POST | `/api/agents` | 创建 Agent：`{code, name, description?, systemPrompt?, modelRole?, answerPolicy?, temperature?, kbIds?, toolCodes?}` → 落 `DRAFT` |
| GET | `/api/agents/{agentId}` | 详情（含绑定知识库 `kbIds`、工具白名单 `toolCodes`、`answerPolicy` 与 `temperature`） |
| PUT | `/api/agents/{agentId}` | 局部更新（null = 不改）：`{name?, description?, systemPrompt?, modelRole?, answerPolicy?, temperature?, status?, kbIds?, toolCodes?}`；`kbIds: []` / `toolCodes: []` = 回到"不限制" |
| POST | `/api/agents/{agentId}/duplicate` | 复制（服务端生成 `-copy` 标识，副本回到 `DRAFT`） |
| DELETE | `/api/agents/{agentId}` | 删除（内置默认 Agent → 5010） |
| POST | `/api/agents/{agentId}/test-chat` | 试跑：`{question}` → 与 `/api/agent/ask` 同形的答案 + 工具痕迹。**不受发布状态限制**（草稿正是要被测试的） |
| POST | `/api/agent/ask` | 用**内置默认 Agent** 跑一次（旧接口，路径保持单数） |
| POST | `/api/chat/stream` | AGENT 模式会话按所选 Agent 的提示词/模型/检索范围执行 |

要点：

- `code` 是**稳定标识**（`[a-z0-9][a-z0-9._-]{1,63}`，同一个用户下唯一），显示名可随时改；
- 状态：`DRAFT`（新建默认，**不能被会话选用** → 5008）/ `PUBLISHED`（可用）/ `DISABLED`（明确报错 5009，不静默降级）；
- 内置默认 Agent（`code=builtin.default`）不可删除、不可停用（5010），但可改名、改提示词——
  AGENT 模式因此永远有可用的兜底；`agentId` 为空的 AGENT 会话在执行时解析到它；
- `kbIds` 为空 = **不限制**（检索该用户全部知识库，注意与 RAG 会话的 kbIds 语义相反）；
- `toolCodes`（5.4 · P1-9，界面在 5.4 才接上，后端 4.3 起支持）= 工具白名单，**空 = 不限制**
  （装该用户全部**启用中**的工具，与 `kbIds` 同一套语义）。装配时取"启用中的 ∩ 白名单"，
  所以白名单里留着一个已停用的 code 不会让它复活——界面上给这类工具标"（已停用）"。可选值来自
  `GET /api/tools`；
- `temperature`（5.4 · P1-8，可空字段）：采样温度 **0–2**，越界 → `1001`（错误信息带范围与当前值）。
  **`null` = 跟随全局 `ai.defaults.temperature`，与 `0` 不是一回事**（0 = 显式要求确定性）。
  响应里原样返回（含 `null`），服务端**不**把全局默认值回填进字段——回填了用户就分不清
  "我配过"和"系统给的"。生效范围只限**由 Agent 驱动的调用**（AGENT 模式会话与试跑）；
  PLAIN / RAG 会话与 `/api/knowledge/**/ask` 仍用全局默认。
  ⚠️ **没有"改回跟随默认"的传法**：`PUT` 里 `null` 已被"不改"占用（与 `kbIds` 不同，那两个把"空"
  复用成了"不限制"）。想清空只能新建 Agent；
- `answerPolicy`（4.4，可空字段；**不传 = 不改**，新建时不传落服务端默认）：
  `KB_PREFERRED`（默认）没有可引用资料时**允许**用模型自身知识回答，但必须注明"不是来自你的笔记"；
  `KB_ONLY` 只依据笔记，没有就直说。两者都**禁止**把自身知识标注成"来源：笔记"，
  区别只在"没有资料时答不答"。非法值 → `1001`。响应里该字段**保证是合法值**
  （服务端把库里的 NULL 收敛成默认），前端不必再兜底；
  注意它与 RAG 会话的契约**有意相反**：RAG 没有可引用资料时一律拒绝（见 B.41 第 9 条）；
- 错误码：`5006` 不存在/非本人（HTTP 404，不泄露存在性）、`5007` 标识重复、`5008` 未发布、
  `5009` 已停用、`5010` 内置项只读（后四个 HTTP 409，语义是"请求格式没问题、资源状态不允许"）。

```http
PUT /api/agents/12
{ "status": "PUBLISHED", "kbIds": [3, 7], "toolCodes": ["knowledge_search"], "temperature": 0.2 }
```

### 6.0.2 已实现：工具目录（平台化 Phase 4.3，界面 5.4 接入）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/tools` | 当前用户的工具目录：`{code, name, description, source, category, permission, enabled, builtin}`；`permission` 取自数据库（运行时权威），不是代码里的默认值 |
| PUT | `/api/tools/{code}` | 启停一个工具（`{enabled}`） |

要点：

- **工具是平台内置的能力，不是用户创建的资源**：`tool` 表里的行只是**用户态覆盖**（启用/停用），
  真正的实现与它的身份（`code`，即 `@Tool(name=…)`）在服务端代码里。所以这里**只有读与启停**，
  没有新建/删除——用户能选的是"这个 Agent 允许用哪些"，不是"我能有哪些工具"；
- 没有覆盖行 = `enabled=true`（默认开着）。**描述等元数据不落库**：唯一真源是代码里的 `@Tool`，
  库里再存一份就是永远不被读的死数据（见 `database.md` 6.0.1）；
- **停用的效果是"任何 Agent 都不再装它"**——模型连这个工具的存在都看不到，因此不会再被调用
  （而不是"调用后被拒"）。而**需要审批的语义是"模型看得见、但执行前要用户点头"**，
  已在 P0 落地（见 6.0）；
- 界面上配白名单的入口在 **Agent 编辑弹窗**（5.4 · P1-9），字段是 `toolCodes`，
  不是 `PUT /api/agents/{id}/tools` ——那个端点**不存在**，也不会做（白名单就是 Agent 的一个字段）。

### 6.0 已实现：工具授权与人工审批闭环（P0，迁移 V22）

权限语义：**`READ` 直接执行；`WRITE` / `DANGEROUS` 必须人工确认**，
除非该用户对该工具有 `always` 授权。权限与授权都在数据库里（`ai_tool_registry.permission` 是运行时权威），
执行拦截读的是库里的值——管理员把一个只读工具提级为 `DANGEROUS` 后，下一次调用立即开始要求审批
（`ToolApprovalFlowTest.registrySyncDoesNotOverwriteAdminPermission` 钉住了这条）。

#### 运行状态机

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/agent/runs` | 发起一次运行 → `{"runId":"9"}`（异步；不阻塞 HTTP 线程） |
| GET | `/api/agent/runs/{runId}` | 运行详情：状态 + `pendingApproval`（等待审批时非空，刷新后可据此恢复现场） |
| GET | `/api/agent/runs/{runId}/steps` | 运行轨迹，按 `seq` 升序 |
| POST | `/api/agent/runs/{runId}/approval` | 提交审批决策 → 返回决策后的运行详情 |
| POST | `/api/agent/runs/{runId}/cancel` | 取消运行（幂等，等待审批时同样可取消）→ `{"runId":"9","cancelled":true}` |

```http
POST /api/agent/runs
{ "agentId": "12", "conversationId": "5001", "question": "把这份纪要写进我的学习计划" }
200 OK  { "code": 0, "data": { "runId": "9" } }

GET /api/agent/runs/9
200 OK  { "code": 0, "data": {
    "id": "9", "status": "WAITING_APPROVAL",
    "pendingApproval": { "approvalId": "11", "toolCode": "test_write_tool",
                         "arguments": "{\"payload\":\"…\"}",
                         "reason": "该工具会修改数据（WRITE），执行前需要你确认。",
                         "status": "PENDING", "createdAt": "2026-10-15T19:33:23.742" } } }

POST /api/agent/runs/9/approval
{ "approved": true, "scope": "always", "reason": "信任这个工具" }
200 OK  { "code": 0, "data": { "id": "9", "status": "RUNNING", "pendingApproval": null } }
```

状态取值：`RUNNING` / `WAITING_APPROVAL` / `DONE` / `FAILED` / `MAX_ITERATIONS` / `CANCELLED`。
状态转换一律是**条件更新**（`UPDATE … WHERE status=?`），因此并发审批、审批与取消同时到达时只有一次能生效；
取消之后再提交审批返回 `5013`（HTTP 409），工具不会执行。

#### 审批决策

| 字段 | 取值 | 语义 |
| --- | --- | --- |
| `approved` | `true` / `false` | 是否放行**本次**调用 |
| `scope` | `once` | 只放行这一次，不写长期授权（缺省值就是它） |
| `scope` | `always` | 放行本次，并写入 `ai_tool_grant`（后续同工具不再询问） |
| `scope` | `deny` | 拒绝本次；**不写授权**，并把"用户拒绝"作为 observation 回灌模型 |
| `reason` | 字符串（≤500） | 可空；拒绝时会出现在回灌给模型的那句话里 |

约束：

- `approved=false` 一律按拒绝处理（`scope` 被收敛成 `deny`），**绝不会**写长期授权；
- `approved=true` + `scope=deny` 是矛盾组合 → `1001`；
- 重复审批、审批已过期、运行没有待决策审批 → `5014`（HTTP 409）；
- **拒绝不是失败**：运行回到 `RUNNING` 让模型换方案，运行不会因为一次拒绝被标成 `FAILED`。

#### 用户工具授权

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/users/me/tool-grants` | 当前用户的长期授权列表 |
| PUT | `/api/users/me/tool-grants` | 写入/更新 `always` 授权：`{"toolCode":"test_write_tool"}`（幂等） |
| DELETE | `/api/users/me/tool-grants/{toolCode}` | 撤销单个授权（幂等：本来就没有也返回成功） |
| DELETE | `/api/users/me/tool-grants` | 撤销全部授权（幂等） |

约束：只接受 `scope=always`（`once`/`deny` → `1001`）；不能给未注册或已禁用的工具授权（`5011`）；
授权只对当前用户生效（唯一键 `(user_id, tool_code)`）；撤销后下一次调用重新走审批。

#### 审计

每次进入执行壳的工具调用都会写一行 `ai_tool_invocation`（含被拒、被取消、等待超时的），
运行轨迹写 `ai_agent_run_step`，审批写 `ai_tool_approval`。
入参与结果都会**脱敏 + 截断**后才落库（`AgentTraceGuards`：`apiKey`/`token`/`password` 等键值、
`Bearer …`、`sk-…` 一律替换为 `***`）。
`THINK` 只是阶段标记（"开始运行""等待人工确认"），**不落库也不外发模型的隐藏推理原文**。

### 6.1 规划中（尚未实现）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/tools/{code}` | 工具详情（入参 JSON Schema、最近调用记录） |
| GET | `/api/tool-invocations` | 工具调用审计流水（按时间/工具/成功与否过滤）；本轮只落库，未开查询接口 |

> `POST /api/agent/runs` 的**流式**形态（把整个运行挂到 `POST /api/chat/stream` 上并复用同一条 SSE）
> 仍未实现：本轮审批事件是通过对话流的工具拦截点真实发出的（`approval_required` 已可用），
> 但运行状态机自身的接口是查询式的。`GET /api/tool-invocations` 的审计数据已经完整落库。

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

> 上面这张表是**目标态**（把一个独立 MCP Server 进程里的知识库能力暴露成标准工具）。
> 当前**已实现并可调用**的是下面这一节——同一个 Spring Boot 进程内的最小 MCP Server，
> 没有独立进程。两者不是同一个东西，别把这张表当成"已经在跑的接口清单"。

### 7.1 已实现的 MCP 端点：`POST /mcp`

JSON-RPC 2.0 over HTTP（MCP Streamable HTTP 的最小子集）。

**必须携带 `Authorization: Bearer <JWT>`**（2026-10-01 起，与其余所有端点同一套身份；
此前它免认证且用 `X-Nexus-User` 头自证身份——那条路径可读任意用户的笔记，属 P0 安全漏洞，
见 `docs/decisions.md` B.42）。**用户身份只取自令牌里的 `sub`**，任何请求头都不再影响它。

| method | 说明 |
| --- | --- |
| `initialize` | 协议握手（返回 `protocolVersion` / `capabilities` / `serverInfo`） |
| `ping` | 存活探测 |
| `tools/list` | 工具清单：**只列该用户启用中的工具**（`@Tool` 方法由注册表反射而来，名称/描述/schema 单一来源），另有非 `@Tool` 的便利入口 `qa_ask` |
| `tools/call` | 执行工具；失败时 `result.isError = true`，未知工具 `error.code = -32601`，**工具被该用户停用** `error.code = -32003`（见下） |

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"Demo@12345"}' | jq -r .data.accessToken)

curl -s -X POST localhost:8080/mcp \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
# tools/call: {"jsonrpc":"2.0","id":2,"method":"tools/call",
#              "params":{"name":"knowledge_search","arguments":{"query":"缓存击穿"}}}
```

**未认证时**返回的是本项目的统一失败体（HTTP 401 / `code 2001`），不是 JSON-RPC 的 `error` 对象：
认证发生在协议层之前。**兜底错误不回传异常原文**，只给 `-32603` + `requestId`。

**工具权限（Phase 5.9 八）**：`tools/list` 与 `tools/call` 遵守**同一份**用户工具权限
（`ToolRegistry.enabledFor`，与 Agent 装配是同一个判定）。用户停用 `knowledge_search` 后：

- `tools/list` **不再返回**它（客户端不该拿到一个"看起来能用"的名字）；
- `tools/call` 拒绝，回 JSON-RPC `error`：

  ```json
  {"jsonrpc":"2.0","id":1,
   "error":{"code":-32003,
            "message":"工具调用无权限：knowledge_search 已被停用（业务码 5004）",
            "data":{"businessCode":5004,"tool":"knowledge_search"}}}
  ```

为什么是 `error` 而不是 `result.isError=true`：本端点里 `isError` 的含义是"工具真的跑了、但失败了"，
而这里工具**一次都没跑**——混在一起，调用方就再也分不清是用户的开关生效了，还是检索真的出错了。
为什么是 `-32003` 而不是标准码：JSON-RPC 2.0 只规定了"方法不存在（`-32601`）""参数不合法（`-32602`）"，
没有"无权限"这一档。**`-32601`（名字写错了）与 `-32003`（你的开关生效了）刻意不合并**，
它们各自指向完全不同的下一步动作。

`qa_ask` 不受这个开关影响（它不是 `@Tool`，而是"跑一次默认 Agent"的入口）：
它内部的检索同样按用户启停过滤，因此停用检索工具后它仍会回答、只是不再检索
（这一点由 `AGENT_TOOLS_EMPTY` 计数与 WARN 留痕，不静默）。

---

## 8. 工作流（Phase 8）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/workflows` | 创建/导入工作流定义（nodes + edges） |
| GET | `/api/workflows` | 工作流列表 |
| GET | `/api/workflows/{id}` | 定义详情（含图结构与节点配置） |
| PUT | `/api/workflows/{id}` | 改元信息（名称 / 描述，局部更新；**不产生新版本**） |
| POST | `/api/workflows/{id}/runs` | 触发执行 → `runId` |
| GET | `/api/workflow-runs/{runId}` | 运行状态与当前节点 |
| GET | `/api/workflow-runs/{runId}/nodes` | 各节点执行明细（输入/输出/耗时/重试次数） |
| GET | `/api/workflow-runs?workflowId=` | 某工作流的运行历史（按 id 倒序分页；失败运行靠它找回 runId） |
| POST | `/api/workflow-runs/{runId}/approval` | 人工确认节点放行 |
| POST | `/api/workflow-runs/{runId}/retry` | 从失败节点重试 |

> ✅ **本表（连同下面的预置模板）均已实现**（`architecture.md` §0 的规矩仍适用：与代码冲突时以代码为准）。
> 已实现：`POST/GET /api/workflows`、`GET /api/workflows/{id}`、`PUT /api/workflows/{id}`（仅元信息）、
> `PUT /api/workflows/{id}/draft`（草稿）、`POST .../validate`、`POST .../publish`、`GET .../versions`、
> `POST .../debug`（跑草稿）、`POST .../runs`（跑已发布）、`DELETE /api/workflows/{id}`、
> `GET /api/workflows/presets` + `POST /api/workflows/presets/{code}`（预置模板），
> `/api/workflow-runs` 的列表 / 详情 / 节点轨迹 / 取消，以及 **`/approval`（人工审批推进）** 与
> **`/retry`（从失败节点续跑）**。
>
> 三条容易搞错的契约事实：
> ① `/approval` 的 `decision` 只接受 `APPROVE` / `REJECT`（大小写不敏感），其他值 → `1001`；
>    运行不在等待审批状态 → `6007` / 409（重复审批、已取消都在此列）。
> ② `/retry` 只接受 `FAILED` / `CANCELLED` 的运行，否则 `6008` / 409；它沿用源运行的版本与入参，
>    把已成功节点的输出复制进新运行的轨迹，只重跑未成功的节点。
> ③ `/presets/{code}` 的 `code` 不存在 → `1001`；实例化只落**草稿**不自动发布，
>    且检索节点的 `kbIds` 刻意留空，所以要用户先选知识库才能发布。

**预置工作流**（`GET /api/workflows/presets` 列出，`POST /api/workflows/presets/{code}` 实例化）：
`knowledge-gap-analysis`（知识缺口分析）、`study-plan-7d`（7 天学习计划，带人工审批闸门）、`knowledge-report`（知识报告生成）。
⚠️ 它们是**用现有 8 种节点写出的顺序版**，不是 `architecture.md` §12 设计稿里那张带 `PARALLEL` 扇出的图——
那套枚举没有实现，所以没有并行分析，也没有工具节点。

---

## 9. 评测（Phase 9）

> ⚠️ **已移除（2026-10-08）**：评测接口（`EvalController` / `EvalService`）已从后端删除 —— 原型没有对应界面，
> 也没有外部调用方。下表是**当初的设计规格**，注意它与实现有差距：实际只落了 1 个端点。
> 保留此表仅作设计记录；决策见 `decisions.md` D.11。

| 方法 | 路径 | 说明 | <!-- 已移除（2026-10-08）：仅存设计记录 -->
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

> ⚠️ **部分已移除（2026-10-08）**：`/api/observability/summary` 与 `/api/observability/agents` 两个只读接口已删除
> （原型无对应界面）。底层的 `ObservabilityRegistry`（`nexus-common`）与 Micrometer / Actuator 指标**保留**。
> 下表后半部分（`overview` / `trend` / `models` / `requests` …）**从未实现**，属于当初的规划。

### 10.0 已实现（两个端点，**数据来源不同，不要混着看**）

| 方法 | 路径 | 说明 | <!-- 部分已移除（2026-10-08）：summary/agents 已删 -->
| --- | --- | --- |
| GET | `/api/observability/summary` | 进程内计数快照：降级/不可用/失败次数。**自本次进程启动以来的累计，重启即归零**，且不含任何归属维度（全局） |
| GET | `/api/observability/agents` | 按 Agent 的用量（5.4 · P1-14）：`[{agentId, code, name, deleted, messages, failed, lastUsedAt}]`，**按消息数倒序** |

`/agents` 要点：

- 数据来自数据库里的 `chat_message`（跨重启的既成事实），**按当前登录用户隔离**；
  归属取自 JWT、**不接受任何入参**——没有可以填错的参数，也就没有越权查询的入口；
- 它回答"我配的那些 Agent，到底哪个在被真的用"：加这一列之前只能靠会话上那个**活的**
  `agentId` 去猜，而它随时会被改、且为 NULL 时连"内置默认在跑"都看不出来（见 `database.md` V17）；
- 只统计**助手消息**（只有它们有 `agent_id`）；`failed` 单独给出来，因为"用了 100 次"和
  "用了 100 次、其中 80 次失败"是两句完全不同的话；
- Agent 被删除（逻辑删除，或行被物理清掉）后，它历史上跑过的消息**仍出现在报表里**，
  由 `deleted=true` 标注——把那一行抹掉的话，用户会看到总数对不上却找不到少掉的是谁；
- 没有任何消息时返回**空列表**（不是错误）。

> 同一个数字在 `/summary` 与 `/agents` 上对不上是正常的：一个是"这次进程见过多少"，一个是"库里有多少"。

**运行指标：`/actuator/metrics`（Phase 5.9 六）**

上面两个端点回答"哪些降级发生了多少次、哪个 Agent 在被用"；运行指标回答"系统忙不忙、慢不慢"。
两者**共用同一个上报口**（`ObservabilityRegistry`），只是读法不同——刻意**不**写第二套采集逻辑：
同一个事实有两处记录，一旦对不上就没人知道该信哪个。

| 指标名（`/actuator/metrics/{name}`） | 类型 | 含义 |
| --- | --- | --- |
| `nexus.llm.calls` | counter | 模型调用次数（含 Agent 工具循环的每一轮、以及重排这种系统调用） |
| `nexus.llm.tokens` | summary | 单次调用的 token 总量（prompt + completion） |
| `nexus.llm.latency` | timer | 单次调用耗时（从**订阅**那一刻起算，不含排队等待） |
| `nexus.chat.requests` | counter | 对话请求数（流式与非流式各算一次） |
| `nexus.chat.latency` | timer | 一次对话的端到端耗时（入口 → 落库/返回） |
| `nexus.retrieval.requests` | counter | 真正发起检索的次数（"没有库可搜"不计） |
| `nexus.agent.executions` | counter | Agent 执行次数 |
| `nexus.errors` | counter | 以错误结束的请求数（全局异常处理器与流式失败各记一次） |

降级类计数（`agent.search.degraded`、`retrieval.kb.failed` 等，见 `/summary`）同样以
`nexus.` 前缀暴露在 `/actuator/metrics` 上，名字就是 `nexus.` + `/summary` 里的键。

两点如实说明：①**没有引入任何指标后端**（无 Prometheus / Grafana），
暴露的就是 Micrometer 的进程内注册表 + Actuator 的 JSON——这正是本阶段要的粒度；
②token 与耗时**只在真的发生了调用/时长 ≥ 0 时记录**。供应商不回传 usage（流式下常见）时
token 记不到，但"调了几次、花了多久"仍然记——把它一起丢掉会让指标整片为空，
看起来像"没人在用"。

### 10.1 规划中

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

## 12. 限流与配额（2026-10-02 实现）

### 12.1 已实现

拒绝点是**一个** HTTP 过滤器（`RateLimitQuotaFilter`），挂在 `BearerTokenAuthenticationFilter` 之后：

| 分支 | 路径（`nexus.security.*.paths`，Ant 风格） | 限制 |
| --- | --- | --- |
| 注册 | `/api/auth/register` | 每 **IP** 每小时 10 次（`standalone` / `local` / `test` 三个开发 profile 放宽到 1000，理由见下） |
| 模型入口（花真钱的那几条） | `/api/chat/stream`、`/api/chat/conversations/*/messages`、`/api/knowledge-bases/*/ask`、`/api/knowledge-bases/*/ask/stream`、`/api/agent/ask`、`/api/agents/*/test-chat`、`/mcp` | 每用户 20 次/分钟 **＋** 每日 200 次 **＋** 每日 200,000 tokens |
| 只限速、不计配额 | `/api/users/me/password` | 每用户 20 次/分钟 |
| 其余全部 | —— | 不限（默认放行） |

**为什么挂在过滤器而不是各 Service 里**：五类入口各写一遍检查，等于把"哪些入口花钱"散布到五处，
漏一个就静默免配额。收口之后路径清单是唯一事实来源。

**⚠️ 为什么是那两条 chat 路径、而不是 `/api/chat/**`**：清单初稿就是那个通配，写错了。
它会把**读历史消息、列出会话、删除会话、停止生成**一起算进当日额度——
用户在自己完全不花钱的操作上被扣额度，"今日 N/200 次"也不再等于"问了几次"。
真机 E2E 里"刚进对话页就显示 1/200"就是这个通配留下的痕迹。
代价是 `/api/chat` 下的读接口不受限速约束：它们只查库、不调用模型，
按上面那条口径本来就不该限（`RateLimitQuotaTest.nonModelChatPathsAreNotCharged` 钉住这一点）。

**为什么位置必须在 `BearerTokenAuthenticationFilter` 之后、控制器之前**：那个位置 `SecurityContext`
已经填好（`CurrentUser` 可用），但仍在 `new SseEmitter` 之前——所以超限时返回的是**统一 JSON**，
而不是已经开流的 SSE 帧（Phase 5.2 口径：开流前失败走 JSON）。

超限响应：`429` + `7001`（限流）/ `403` + `7002`（配额用尽），带 `Retry-After`
（分钟级桶 = 60；注册桶 = 3600；**配额用尽不带**——那是次日 00:00 才重置，回 60 秒会让客户端白试一整天）。
两种 `7002` 的 `message` 是分开的：「今日问答次数已用尽（上限 N 次/天）」vs「配额服务暂时不可用」——
把"你没额度了"和"我们坏了"说成一句，用户只会去骂产品。

**为什么三个开发 profile 把注册上限放宽到 1000**：本机请求全来自同一个回环地址，
而 E2E 脚本每轮会自己注册临时账号（`verify-security.mjs` / `verify-agent-no-kb.mjs` 各一个）。
真机实测：同源连续注册到**第 9 次**开始 `429/7001`，而且**症状出现在别处**——
注册失败之后紧接的那次登录自然 401，于是"限流"看起来像"认证坏了"（排查方向完全跑偏）。
生产（不加 profile 覆盖）仍是 **10**；要在开发 profile 上验真实阈值，
显式设 `NEXUS_REGISTER_PER_HOUR=10` 即可。

**token 记账**：所有 chat 调用都经过 `ChatClientFactory`，因此记账顾问（`UsageMeteringAdvisor`）
挂在那里一次覆盖六个调用点，各调用点只多一行 `.advisors(a -> a.param(UserContextKeys.USER_ID, userId))`。
漏传 userId 时**不静默**：计入 `quota.usage.unattributed` 度量并打日志（带模型名）。

### 12.2 存储与降级

| 存储 | 何时用 | 键 |
| --- | --- | --- |
| `InMemorySecurityStore`（默认） | `standalone` / `test`（零外部基础设施） | 进程内 `ConcurrentHashMap` |
| `RedisSecurityStore` | `nexus.security.redis-enabled=true`（`local` / 生产） | 见下 |

```
nexus:rl:u:{userId}:{yyyyMMddHHmm}   用户分钟级限流计数
nexus:rl:ip:{ip}:{yyyyMMddHH}        注册限流计数（按小时）
nexus:q:req:{userId}:{yyyyMMdd}      当日请求次数
nexus:q:tok:{userId}:{yyyyMMdd}      当日 token 用量
nexus:auth:lf:{username}             登录失败次数
nexus:auth:ll:{username}             登录冻结标记
nexus:auth:tv:{userId}               用户令牌版本（缓存，DB 是事实来源）
```

**时间片写进键名，是窗口机制本身**（不是装饰）：固定窗口不需要"到点清零"的动作——
下一分钟是另一个键，计数天然从 0 开始，TTL 只负责回收。这也解释了为什么
`{ip}` 那一格在真机上是 `0:0:0:0:0:0:0:1`：`getRemoteAddr()` 在 IPv6 栈上返回的是这种写法，
按字符串入键即可，不需要归一化（同一台机器上不会一半 v4 一半 v6）。
上表是**实测** `redis-cli KEYS 'nexus:*'` 之后校正的：初稿少了三个时间片后缀。

**降级按关注点分开**（这是刻意的，不是遗漏）：

| 关注点 | 存储故障时 | 为什么 |
| --- | --- | --- |
| 登录失败限制 / 限流 | **fail-open**（放行 + 计数） | 否则存储一挂，全体用户被锁在门外，比"少限了几次"严重得多 |
| 每日配额 | **fail-closed**（拒绝，`7002`） | 否则存储一挂就是无限烧钱。文案区分"已用尽"与"配额服务不可用" |
| 令牌版本 | **回落查库** | DB 是事实来源，缓存只是加速——故障时"每请求查一次库"，依然正确 |

**⚠️ 多实例部署必须 `nexus.security.redis-enabled=true`**：内存实现每个实例各算一份，限流会被放大 N 倍。

### 12.3 规划中（尚未实现）

| 接口组 | 规划中的限制 |
| --- | --- |
| 检索 | 60 次/分钟/用户 |
| 上传 | 10 次/分钟/用户；单文件 50 MB；ZIP 200 MB |
| 并发流 | 5 并发流/用户 |
| Agent 运行 | 最长 5 分钟/次 |
| 评测批量 | 1 次/分钟/用户（后台异步跑） |

> 本节此前描述的是一套"Redis 令牌桶 + 全接口分组限流"，**全都没有代码**：
> `7001` / `7002` 两个错误码已定义、已映射（429 / 403），但**零抛出点**，`nexus-app` 里也没有
> `RedisTemplate`。2026-10-02 只实现了上表 §12.1 那一列；其余保持"规划中"，不再写成已完成。

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
| `POST /mcp`（进程内 MCP Server，见 §7.1） | 已实现（4.3 起；身份 5.1 起改为 JWT） |
| `/api/workflows/**`、`/api/workflow-runs/**` | Phase 8 |
| ~~`/api/eval/**`~~（已移除）、`/api/prompts/**` | Phase 9 |
| ~~`/api/observability/**`~~（只读接口已移除，指标仍在 `/actuator/metrics`） | Phase 10 |
