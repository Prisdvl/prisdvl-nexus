# 产品化 Gap Analysis

> 写这份文档的目的：把 prisdvl-nexus 从"核心链路已跑通的 Demo"变成"普通用户能用的产品"
> 之前，先**如实**列清差距。审计在动手改代码之前完成（update.txt §二十六）。
>
> - 审计时间：2026-10-01（**下面这两行是审计当时的基线，不是最新状态**。
>   最新进度看 §4 的实施顺序表，模块现状看 §2 的"现状"列——那两处才随每期更新）
> - 代码基线：分支 `platform-phase3`，HEAD `8ea85d0`，**工作区有 58 个未提交的改动**（Phase 4.x）
> - 测试基线：`mvn -B test` **382 个用例 / 0 失败 / 0 错误 / 18 跳过**（5.1 后；审计当时是 377。跳过的全部是 live 门控用例，
>   需要真实模型 key，见 `docs/platform-phase3-baseline.md` §9.6）
> - 端到端基线：9 个 `web/scripts/verify-*.mjs` 脚本（真机栈下退出码 0，见 decisions.md B.37/B.41）

## 0. 审计方法与证据口径

| 证据类型 | 怎么来的 | 能证明什么 |
| --- | --- | --- |
| 读代码 | 逐文件读 Controller / Service / Runtime / 迁移脚本 / 前端视图 | "有没有这个能力"、"谁在读这个字段" |
| 静态清点 | `grep` 全仓：端点、越权入口、`requireId` 用点、错误码抛出点 | "有没有第二份实现"、"有没有死代码" |
| 跑测试 | `mvn -B test`（本次实跑） | "现有功能有没有被破坏" |
| 查依赖实现 | `javap` 读 `spring-ai-model-2.0.1.jar` 的 `DefaultToolCallingManager$Builder` | **框架到底有没有上限**（不靠猜） |
| 既有 live 记录 | `docs/platform-phase3-baseline.md` §9.6、decisions.md B.37/B.41 | 真机行为 |

**这份文档里每一条"缺失"都写了证据位置**（文件:行 或 命令）。凡是我没有亲手验证的，
一律标注为「待验证」，不入 P0/P1 的结论。

---

## 1. 已经具备的能力（先看清，避免重复实现）

update.txt 第 2 条要求"不要重复实现已经存在的功能"。以下是审计确认**已经真实存在**的能力，
后续阶段不得重写：

### 1.1 认证与身份
- `POST /api/auth/register`（用户名 3–32 位、密码 8–64 位、邮箱/昵称可选，见 `RegisterRequest.java`）
- `POST /api/auth/login` → BCrypt 校验 → 自签 HS256 JWT，`sub` = userId
- 无状态、`anyRequest().authenticated()`、公开端点只有 `register`/`login`/`/mcp`（`SecurityConfig.java:46`）
- 自定义 401/403 处理；`nexus.jwt.secret` **无默认值**，缺配置直接启动失败（fail-closed）
- `GET /api/users/me`
- **身份只来自 JWT**：`CurrentUser.requireId()` 在 `nexus-app` 里有 **43 处**使用，
  没有任何一个 Controller 接受 `userId` / `ownerId` 参数（全仓 grep 确认）

### 1.2 知识库与文档闭环
- KB CRUD + 列表分页 + 详情 + `status`（`ACTIVE`/`DISABLED`）+ 检索参数（`topK`/`similarity_threshold`）
- **ZIP（Obsidian Vault）导入**：`POST /api/knowledge-bases/{id}/vault`，异步（RabbitMQ）/同步（standalone）
- 4 种解析器：Markdown / TXT / PDF（PDFBox）/ DOCX（POI），扩展名白名单由 `ParserRegistry` 统一提供
- 切分 → 向量化（Ollama `bge-m3`，1024 维）→ 建索引（pgvector + ES），三个动作都有独立端点
- **状态机**：文档 `PENDING → PARSING → CHUNKING → EMBEDDING → INDEXING → READY / FAILED / SKIPPED`；
  任务 `QUEUED / RUNNING / PARTIAL / DONE / FAILED / CANCELLED`，**有重试端点**（`/import-tasks/{id}/retry`，复用暂存包）
- **增量同步**：SHA-256 未变更跳过，变更重建，源里删除的清理
- **压缩包防御**：Zip Slip（`..`/绝对路径/盘符一律拒绝整批）、条目数上限、解压后总大小上限、单文件上限
  （`VaultPackageExtractor.java:62/86/159`）
- Spring 层上传上限：`max-file-size: 200MB` / `max-request-size: 220MB`（`application.yml:50`）
- 文档/切片浏览（切片带标题路径、Token 数、向量与索引状态）、关键词检索、RAG 问答留档（`kb_qa_record`）

### 1.3 RAG
- 混合检索：BM25（ES + IK 中文分词）+ 向量（pgvector HNSW）+ RRF 融合
- **元数据过滤下推**：`tags`/`folder`/`documentId` 到 ES filter 与 pgvector `WHERE`（向量路先解析成文档 id）
- 每库可配 `topK` / 相似度阈值；全局默认收敛到 `nexus.retrieval.*`（阈值 0.25）
- LLM 重排（`rerank-timeout-ms: 5000`，失败自动降级并如实标注）
- 引用编号 `[n]` + 引用可核对（区分"用到的"与"召回到的"）
- **无命中不调用模型**，直接返回固定文案（`RagAnswerService.EMPTY_ANSWER`），从根上挡"无资料硬答"
- 多轮指代消解（上一问 + 当前问拼接作为检索 query）

### 1.4 Agent
- `AgentDefinition`（不可变值对象，`nexus-ai`）/ `AgentRuntime`（执行）/ `AgentService`（资源 + 越权校验）三层分离
- 生命周期 `DRAFT / PUBLISHED / DISABLED` + `builtin` 保护 + 复制 + 试跑（`/test-chat`，不要求已发布）
- 可配：`systemPrompt` / `modelRole` / `kbIds`（检索范围，空=全部）/ `toolCodes`（工具白名单，空=全部）/ `answerPolicy`
- **回答策略单源**：`AgentPrompts.systemPromptFor` 是唯一组装点，两条执行链路（一次性 + 流式）都经过它
- 会话选 Agent、以及"Agent 被停用后当下就报错（5008/5009）"而不是悄悄降级

### 1.5 Tool / Agent Loop
- 工具目录 `ToolRegistry`（用户级启停覆盖行）+ 白名单取交集（`AgentToolFactory`）
- 执行壳 `ExecutingToolCallback`：计数、留日志、失败变成"观察结果"回灌给模型（可降级）、兜底留痕、观测计数
- **轮数上限确实存在，且在框架里**：`javap` 读 `spring-ai-model-2.0.1.jar` 的
  `DefaultToolCallingManager$Builder` → `defaultMaxCallsPerTool = 40`、`maxTotalToolCalls = 150`、
  超限行为 `THROW`（`ToolCallLimitExceededException`）。项目里那句"轮数有界在框架里"是**真的**
- 痕迹：工具调用/结果随消息落库（`chat_message.tool_calls`），刷新后仍可查

### 1.6 Chat
- 会话 CRUD（含**重命名接口**）、消息列表、SSE 流式、停止生成
- 四条终止路径（正常/停止/异常/客户端断开）用 `AtomicBoolean` 保证**只落库一次**；
  停止会真的 `dispose()` 上游订阅（不停就继续烧 Token）
- 落库链路：先落 user 消息 + `STREAMING` 助手消息，再流式回填（进程崩溃也不会凭空多/少消息）
- 三种模式共用一条流式链路：`PLAIN` / `RAG`（先检索再答，`retrieval` 事件先于第一个字）/ `AGENT`
- 多轮上下文：`JdbcChatMemoryRepository`（V3 表）

### 1.7 观测
- `requestId`：MDC + 响应头 `X-Request-Id` + 落库
- 消息上记录：`model` / `provider` / 三类 token / `latency` / `first_token` / `finish_reason` / `error_msg` / `tool_calls`
- 计数注册表 `ObservabilityRegistry` + `GET /api/observability/summary`

### 1.8 前端
- 5 个视图、4138 行：登录 / 知识库列表 / 知识库详情（正文+大纲+切分+检索+问答+关联+工作流）/ 对话 / Agent 管理
- Markdown 渲染链路：`marked` → **DOMPurify 净化** → `highlight.js`（XSS 有防线）
- 错误透出的是**后端原话**而不是 `Request failed with status code 502`（`client.ts` 里有专门的踩坑记录）
- 各页已有 loading / empty / 危险操作确认（删除会话、删除 Agent、停用 Agent 都有 `ElMessageBox`）

### 1.9 数据隔离（本项是全项目做得最扎实的一处）
- 约定：`requireOwned(userId, id)` + 查询谓词一律带 `user_id` / `owner_id`
- **全应用只有一处 `selectById`**（`AuthServiceImpl:143`，读的是自己）
- 向量检索的 `kb_id` 过滤**写在 SQL 里**（`PgVectorStore.search`），不是查完再筛
- 越权一律返回 **404 而不是 403**（不泄露资源存在性）
- 已有跨用户测试：KB 列表/详情、文档、会话、Agent（含"绑定别人的库被拒"）、
  工具启停按用户隔离、RAG 会话绑定别人的库、取消别人的生成 —— 共 8 组
  （`grep -rn "DisplayName" nexus-app/src/test | grep 别人`）

**结论：这些都不许重写。** 后续阶段的动作只有两类——补缺口、把已有能力接到界面上。

---

## 2. 模块 Gap 表（update.txt §四要求的 16 行）

| 模块 | 当前状态 | 缺失问题 | 严重程度 | 建议方案 |
| --- | --- | --- | --- | --- |
| **Auth** | 注册/登录/JWT/BCrypt/401/403/无状态，fail-closed | 无登出接口、无令牌失效（`jti` 预留未用）；无改密码；无登录失败计数；令牌存 localStorage | P2 | 先补改密码（P1 项）；登出用短 TTL + `jti` 黑名单（Redis 已在栈里但应用零使用）；把"登出=客户端丢弃令牌"的现状写进 `docs/api.md` |
| **User** | 只有 `GET /api/users/me` | 无资料编辑、无改密码、无账户注销 | P1 | `PUT /api/users/me`（昵称/邮箱）+ `PUT /api/users/me/password`（验旧密码 + BCrypt 重编码 + 撤销旧令牌） |
| **Knowledge Base** | 创建/编辑/删除/禁用/检索参数/workspace，异步导入 + 重试，单文件上传，删单篇文档，失败原因可见 | ✅ **P1-1/P1-2/P1-3 三项均已修（5.3）**：`POST /knowledge-bases/{id}/documents`、`DELETE /knowledge-bases/{kbId}/documents/{documentId}`、`GET /import-tasks/{id}/items` + 面板渲染。删除的清理范围比审计写的宽（存量路径也在漏，见上文"5.3 修正的审计结论"） | ~~P1~~ → 已修 | 见 B.44 |
| **Document** | 4 种解析器、状态机、增量同步、压缩包防御、逐文件失败明细 | ✅ **P1-4 已修（5.3）**：原方案（给 `DocumentSummary` 补 `errorMsg`）是**死字段**——`DocumentStatus.FAILED` 无写入路径。改为补 `kb_import_task_item` 的读接口；✅ 超 200MB 的失败落到兜底 500 **已修（5.2）** | ~~P1~~ → 已修 | 见 B.44 §1/§6 |
| **RAG** | 混合检索+Rerank+引用+无命中不调模型 | ✅ **P1-6 已修（5.5）**：多库改为**逐库检索后跨库归并**（~~只取第一个~~ 的 `kbIds.get(0)` 已删），每库按**自己的** `retrieval_top_k` 召回、归并后按全局 `topK` 截断；✅ 同步路径把模型异常**当成正常回答**返回并落库**已修（5.2）**；"检索到 N 个片段"**仍**不落库 | P1 | ~~①多库改成合并检索~~（5.5 已修，且**不需要**下推多 `kb_id`——下推表达不了"每库各自的召回深度"）；~~②`generate()` 的 catch 改为抛业务异常~~（5.2 已修）；③`retrieval` 事件随消息落库（P2-4） |
| **Agent** | Definition/Runtime 分离、状态机、白名单、复制、试跑 | ✅ **P1-8/P1-9 已修（5.4）**：`temperature` 可配（迁移 V16，NULL = 跟随全局、与 0 不同义；生效点在 `AgentRuntime.configure` 的请求级 options）；管理页可配 Tools（`toolCodes` 多选 + 卡片回显 + "空 = 全部工具"写明）。Agent 使用统计见 Trace 行 | ~~P1~~ → 已修 | 见 B.45 |
| **Tool** | 注册表 + 用户启停 + 白名单交集 + 执行壳 + 痕迹 | **无 Tool 超时**；✅ **超循环上限没被识别已修（5.2）**（`ToolCallLimitExceededException` 现在 → `5002`，`5002` 不再是死码）；✅ 40/150 上限**已显式配置并真撞测过（5.2）**（但**不是**写 `spring.ai.tools.limits.*`——本项目手工建模型，那两个 key 无人读，见 B.43 §2）| P1 | ~~①显式配置 `spring.ai.tools.limits.*`~~（该方案是假旋钮，已否）；②映射 `ToolCallLimitExceededException` → `5002` + 如实文案；③工具加了超时字段，等第一个会阻塞的外部工具落地时一起实现 |
| **Chat** | 会话 CRUD/重命名接口/流式四路终止/痕迹落库/多轮 | ✅ **P1-12 已修（5.4）**：侧栏会话项加重命名入口（接口从 Phase 1 就在，一直无人调用）；✅ 流式错误一律 `4001 模型调用失败`**已修（5.2）**（判定收敛到 `FailureClassifier`）；✅ **5.4 另修一处静默不一致**：非流式入口此前**完全不看会话模式**（AGENT 会话不用该 Agent 的提示词/工具、RAG 会话不查笔记，两者都不报错）→ 抽 `ConversationAgentResolver` 两条链路共用，RAG 非流式显式报 `8003` | ~~P1~~ → 已修 | 见 B.45 §1/§5；消息级清空仍列 P3 |
| **Trace** | requestId + 消息上 8 个字段 + 计数注册表 | ✅ **P1-14 已修（5.4）**：消息补 `agent_id`（迁移 V17，**只记助手消息**、落**解析后**的具体 id）+ `GET /api/observability/agents` 按 Agent 聚合（`LEFT JOIN`，被删的 Agent 仍留在报表里并由 `deleted` 标注）。仍**无 trace 表**（现表已够，不造第二份真相）；RAG 片段数仍不入消息 | ~~P1~~ → 已修 | 见 B.45 §4 |
| **Permission** | 后端强制、越权 404、8 组跨用户测试 | ✅ `/mcp` 那条绕过已修（5.1） | ~~P0~~ → 已修 | 见 Security S-1；余下的工具级权限/审批列 P2 |
| **Data Isolation** | `requireOwned` + 谓词 + 唯一 `selectById` + SQL 内 `kb_id` 过滤 | ✅ `/mcp` 那条绕行已堵（5.1，含"旧实现下测试全红"的反证）；无 `tenant_id`（单层归属，属取舍） | ~~P0~~ → 已修 | `tenant_id` 不做，把"单层归属"的取舍写进 `docs/database.md` |
| **Error Handling** | 统一 `{code,message,data,requestId}`、8 域错误码、9 条 handler、前端透出后端原话 | ✅ **三处漏洞均已修（5.2）**：上传超限→413/3006；循环超限→5002；同步 RAG 异常→抛业务异常且不落档。真机验证又暴出两处审计没写到的：四个非流式 Agent 入口回 500/9999、`ChatServiceImpl` 把上游原文回显并落库（B.43 §4） | ~~P1~~ → 已修 | 集中在 `FailureClassifier`（判定唯一入口）+ `GlobalExceptionHandler` 补 handler；`error_msg` 改存给用户的话；新增"错误码↔HTTP"契约测试与真实 Tomcat 上传超限测试；**未做**：前端没有体积预检、`retryable` 前端还没消费 |
| **Security** | JWT、BCrypt、越权 404、Zip Slip/zip bomb、DOMPurify、两条链路都有反注入规则、密钥走环境变量 | ✅ **S-1 已修（5.1）**：`/mcp` 改走 JWT，头自证身份已删除；无速率限制/配额（`7001/7002` 是死码）；令牌存 localStorage | ~~P0~~ → 已修 | S-1 已完成；限流与配额列 P1（配了真实 key 之后，一个用户就能把额度烧光） |
| **Observability** | requestId、计数注册表、消息级指标、`/summary` | 无 Prometheus/actuator 指标端点；降级事件只在日志与内存计数里（重启即失） | P2 | 保留自建注册表（够用且零依赖），补 `/actuator/prometheus` 暴露；降级事件落库（P2） |
| **Testing** | 审计当时 377 用例 / 71 类 / 0 失败，**5.6 后为 541 用例 / 101 类 / 0 失败**；10 个 E2E 脚本；真机栈补验 | 无 Testcontainers（真机回归靠手工）；缺测试：超循环上限、上传超限 ~~MCP 越权~~（5.1 已补）~~多库 RAG~~（5.5 已补）；文档里测试数字过期（`deployment.md` 写 174） | P1 | 每修一处补一条回归测试（见 §4 每阶段）；Testcontainers 列 P2；`deployment.md` 数字跟着改 |
| **Deployment** | `deploy/docker-compose.yml` 起 6 个基础设施服务 + healthcheck + `.env.example` | **应用本身与前端不在容器里**（无 Dockerfile、无 nginx），`docker compose up -d` 起不来整个项目；上传文件落本地磁盘 | P1 | 补 `nexus-app/Dockerfile`（多阶段）+ `web/nginx.conf` + compose 加 `backend`/`frontend` 两个服务 + 上传目录挂卷；README 写清"哪些服务不能容器化"（Ollama 可留宿主） |
| **README / Docs** | README 有技术栈/两种跑法/脚本清单/开发约定；docs 有架构/数据库/API/决策/部署 | README 的 Phase 进度表**停在早期**（Phase 2/3 标 ⏳，实际已实现）；缺 Troubleshooting / Security / Roadmap / Screenshots 章节 | P1 | 重写 §6 进度表（或改成"功能清单"），补四章 |

---

## 3. 分级清单

### P0 —— 不解决就不能称为可用产品（1 条）

#### P0-1 · `/mcp` 是公开端点，且身份由请求头自证 ✅ **已修复（Phase 5.1，2026-10-01）**

> **状态**：已修复。`/mcp` 移出 `PUBLIC_ENDPOINTS`，身份改为 `CurrentUser.requireId()`
> （即令牌里的 `sub`），`X-Nexus-User` 请求头与 `resolveUser` 整条删除；
> 兜底错误不再回传 `ex.getMessage()`。新增 `McpEndpointAuthTest` 5 条，
> **并已用"把旧实现临时放回"的方式验证这 5 条在旧代码上全红**——
> 下面第 2 条审计边界（"只有代码证据、没有实际发过跨用户请求"）由此关闭。
> 全量 382 运行 / 0 失败 / 0 错误 / 18 跳过。过程见 `docs/decisions.md` B.42。
> **下文保留审计当时的原文**（记录的是修复前的状态，不改写）。

**现象**：`POST /mcp` 在 `SecurityConfig.PUBLIC_ENDPOINTS` 里（不需要认证）。
调用方用 `X-Nexus-User: <任意用户 id>` 声明自己是谁，服务端**照单全收**：

```java
// McpEndpoint.java:100-109
private Long resolveUser(String nexusUser) {
    if (nexusUser == null || nexusUser.isBlank())
        throw new IllegalArgumentException("缺少 X-Nexus-User 请求头（个人本机信任模型）");
    return Long.valueOf(nexusUser.trim());
}
```

`tools/call` 能做什么（`McpEndpoint` 的 `toolCatalog` + `AgentRuntime`）：

- `knowledge_search` → 检索**该 userId 名下全部知识库**的正文片段
- `qa_ask` → 用该 userId 的默认 Agent 跑一次完整问答（消耗他的模型额度、读他的笔记）

**影响**：任意能访问该端口的人，只要猜/枚举一个雪花 id（或从任何日志里读到），
就能读走该用户的所有笔记内容并替他花钱。这一条把 §1.9 那套"43 处 `requireId` + 越权 404"
建立起来的隔离**整条绕开**。

**为什么它是 P0 而不是 P1**：update.txt §六 把多租户隔离列为"最重要"，
§十四 把 IDOR 列为必查项。隔离的口径是"用户 A 不能读用户 B 的任何数据"——
现在存在一条**不需要任何凭据**的路径可以直接读，其它地方做得再对也不成立。

**建议方案**（三选一，我倾向 1）：

1. **改成与其余端点同一套身份**：`/mcp` 移出 `PUBLIC_ENDPOINTS`，用同一个 `Authorization: Bearer`
   取 `CurrentUser.requireId()`；`X-Nexus-User` 头直接删掉（保留会变成"两个身份来源"，
   而"身份只有一个来源"正是这个项目一路在守的不变量）。
   代价：MCP 客户端的配置要带一个 token —— 这是所有正规 MCP server 的做法。
2. **默认关闭**：加 `nexus.mcp.enabled`（默认 `false`），README/api.md 写明"个人本机用法需显式打开，
   打开即等于把该用户的数据暴露给本机所有进程"。
3. 两者都做（默认关闭 + 打开时要求 JWT）。

**回归测试**（必须）：无凭据 `POST /mcp` → 401；带 A 的 token 用 B 的 userId 调 `knowledge_search`
→ 拿到的是 A 的内容或直接失败，**绝不能是 B 的内容**。

> **实际落地（5.1）**：`nexus-app/src/test/java/com/lky/nexus/app/mcp/McpEndpointAuthTest.java`
> 5 条——无凭据 401/2001、只带 `X-Nexus-User` 头仍 401、A 的 token + 伪造 B 的 id 只能看到 A 的事实
> （且 B 的库名一个字都不出现）、零知识库调用方看到自己的空状态、带 token 的 `tools/list` 正向对照。

---

### P1 —— 正式使用前应该解决（14 条）

| 编号 | 模块 | 问题 | 证据 | 方案 |
| --- | --- | --- | --- | --- |
| P1-1 | KB | ~~**不能上传单个文件**，只能打 ZIP~~ | ✅ **5.3（2026-10-01）**：`POST /knowledge-bases/{id}/documents`，复用**同一条**异步导入链路（`taskType=UPLOAD` + V15 `source_name`），差异只有"不做删除检测"与"绕开 20MB 单条目上限"。见 B.44 §2 | ✅ 按原方案落地 |
| P1-2 | KB | ~~**没有删除单个文档**的接口~~ | ✅ **5.3（2026-10-01）**：`DELETE /knowledge-bases/{kbId}/documents/{documentId}`。**方案比审计写的宽**——存量删除路径今天就在漏，而且不止一条，详见下方"5.3 修正的审计结论" | ✅ 按原方案落地，范围扩大 |
| P1-3 | KB | ~~**导入失败原因不可见**：面板只显示 `createdCount / skippedCount`~~ | ✅ **5.3（2026-10-01）**：`GET /import-tasks/{id}/items`（action 过滤 + limit 收敛到 1–500），面板逐条列出路径与原因；`task.errorMsg` 也接上了 | ✅ 按原方案落地，另补一个洞 |
| P1-4 | Document | ~~每文档的失败原因没进接口~~ | ✅ **5.3（2026-10-01）**：**原方案是个死字段**——`DocumentStatus` 全仓没有任何路径写 `FAILED`（`applyMetadata` 永远写 `READY`/`errorMsg=null`）。真正的原因在 `kb_import_task_item.message`，而那张表**只有写入方**。改补 items 读接口 | ⚠️ 方案被推翻，见 B.44 §1 |
| P1-5 | Document | 超 200MB 上传返回 500「系统内部错误」 | `application.yml:50` 只有 Spring 上限；`GlobalExceptionHandler` **没有** `MaxUploadSizeExceededException` 分支 → 落到兜底 `Exception` → `INTERNAL_ERROR` | ✅ **5.2（2026-10-01）**：`GlobalExceptionHandler` 补 `MaxUploadSizeExceededException` → `413 / 3006`，文案带**配置里读出来的**上限值（不写死）；真机 201MB 实测通过，见 B.43 §6 |
| P1-6 | RAG | ~~会话绑多个知识库时**只用第一个**~~ | `ChatStreamServiceImpl.requireKnowledgeBaseId` → `kbIds.get(0)` | ✅ **5.5（2026-10-02）**：改为**逐库检索后跨库归并**（下推 `kb_id IN (...)` 表达不了"每库各自的 `retrieval_top_k`"，且要动 `PgVectorStore`/ES 查询并重做 B.31 的"行为不变"证明）。探索后发现**检索层本来就是多库的、数据库一行不用改**，卡点只是三道人工闸门（写入口拒绝 > 1、读出口 `.get(0)`、门面签名单库）。接口契约随之变化：`retrieval` 事件的 `knowledgeBaseId` → `knowledgeBaseIds` + 每 hit 带 `knowledgeBaseId`。见 B.46 | ✅ 方向不变，成本比原估低（无迁移） |
| P1-7 | RAG | 同步问答把模型异常**当成回答**返回并落库 | `RagAnswerServiceImpl.generate` 的 `catch (Exception ex) { return "生成回答失败：" + ex.getMessage() …}` | ✅ **5.2（2026-10-01）**：改成抛业务异常（`FailureClassifier` 给码），**失败不落档**——`kb_qa_record` 没有 status 列，写进去就是一条假回答（与本文措辞的偏离见 B.43 §5） |
| P1-8 | Agent | ~~**`temperature` 不可配**~~ | `AgentDefinition` 无该字段；`application.yml` 只有 `ai.defaults.temperature: 0.7` | ✅ **5.4（2026-10-02）**：迁移 V16 + 字段 + 请求级 options **同一次改动**落地。关键取舍：**NULL（跟随全局）≠ 0（显式贪心）**，响应原样返回 null 不回填；生效点是两条链路唯一的公共装配点 `AgentRuntime.configure`；范围 0–2 取 OpenAI/Anthropic 并集。见 B.45 §2 |
| P1-9 | Agent | ~~**管理页不能配 Tools**~~ | `web/src/api/agents.ts` 的 `AgentPayload` 无 `toolCodes`；`AgentsView.vue` 全文无 tool（grep 确认） | ✅ **5.4（2026-10-02）**：`toolCodes` 多选 + 卡片回显 + **"空 = 全部工具"写在界面上** + 已停用工具标注。后端 4.3 起就支持，缺的一直只有这一页；顺带修正 `api.md` 把 `GET /api/tools` 列为"规划中"的过期条目。见 B.45 §3 |
| P1-10 | Tool | **超循环上限被报成"模型调用失败"**，`5002` 是死码 | `AGENT_MAX_ITERATIONS_EXCEEDED(5002)` 仅出现在 `ErrorCode` 与 `ErrorCodeHttpStatusMapper`；框架超限抛 `ToolCallLimitExceededException`（javap 确认）→ 流式 `onError` → `code 4001 "模型调用失败"` | ✅ **5.2（2026-10-01）**：`5002` + 如实文案（分"单工具/合计"两种上限），并补 `ToolCallLimitTest`（真撞一次上限，见 P1-11）；`5002` 不再是死码 |
| P1-11 | Tool | 框架轮数上限（40/150）**不可见**：没配置、没文档、没测试 | `grep -rn "spring.ai.tools" application.yml` → 无 | ✅ **5.2（2026-10-01）**：**没有**写 `spring.ai.tools.limits.*`——本项目手工建模型（`spring.ai.model.chat=none`），那两个 key 无人读取，写了就是**假旋钮**。改为本项目自己的 `ai.tools.*` + 显式传给模型，见 B.43 §2 |
| P1-12 | Chat | ~~**重命名会话在界面上不存在**~~ | `web/src/api/chat.ts:91 renameConversation` 有定义；全仓视图无任何调用 | ✅ **5.4（2026-10-02）**：侧栏会话项加"重命名"入口（复用已有接口，无新端点）。E2E 断言用"**刷新后名字仍在**"而不是"点保存后文字变了"。见 B.45 §5 |
| P1-13 | Chat/Error | 流式错误帧不区分错误来源 | `ChatStreamServiceImpl.onError` 写死 `code 4001`、`retryable true` | ✅ **5.2（2026-10-01）**：判定收敛到 `FailureClassifier`（唯一入口），四条链路共用；真机实测同一故障在三处给同一个码同一句话（B.43 §4）。**`retryable` 前端还没消费**，见 B.43 §7 |
| P1-14 | Trace | ~~消息上**不记 `agentId`**，无法回答"这条回答是哪个 Agent 跑的"~~ | `ChatMessagePO` 无该列；`agent_id` 只在会话上 | ✅ **5.4（2026-10-02）**：迁移 V17 + 落库 + `GET /api/observability/agents` 按 Agent 聚合（**字段与读它的机制同一次落地**）。三处刻意：只记助手消息、落解析后的 id（含内置默认）、索引带 `user_id` 前缀。**顺带发现并修掉**：非流式入口完全不看会话模式（见 B.45 §1）。见 B.45 §4 |
| P1-15 | User | ~~无修改密码 / 无资料编辑~~ | `UserController` 只有 `GET /me` | ✅ **5.6（2026-10-02）**：`PUT /users/me`（昵称/邮箱）+ `PUT /users/me/password`。**令牌吊销是真做的**：`sys_user.token_version`（V18）+ `ver` claim + `TokenVersionValidator`（ADR-020），改密后旧令牌 401（E2E 用改密前抓下的 token 直接打接口验证过）。前端同为最小实现：两个弹窗 + 侧栏账号入口 + 昵称即时生效。见 B.47 |
| P1-16 | Security | ~~无速率限制 / 无配额（配了真实 key 后单用户可烧光额度）~~ | `RATE_LIMITED(7001)` / `QUOTA_EXHAUSTED(7002)` 无任何抛出点；`nexus-app` 全仓无 `RedisTemplate` 使用 | ✅ **5.6（2026-10-02）**：一个过滤器收口（`RateLimitQuotaFilter`），覆盖注册（IP）/ 模型入口（用户分钟级 + 每日次数 + 每日 token）/ 改密（仅限速）。**方案比审计写的宽**：审计只要求"登录/注册/问答三类入口加限流"，实际补了**登录失败冻结**与**每日 token 记账**（记账点选 Advisor，见 B.47 §①②）。**降级方向按关注点分开定**，不是一刀切（ADR-020 第 5 条）。存储默认进程内存——多实例部署必须开 `nexus.security.redis-enabled` |
| P1-17 | Deployment | `docker compose up -d` **起不来应用** | `deploy/` 只有 6 个基础设施服务 + ES 的 Dockerfile；无应用 Dockerfile、无 nginx | 见 §2 Deployment 行 |
| P1-18 | README | 进度表停在早期，首次阅读者会误判 | `README.md` §6："Phase 2 Knowledge Base ⏳ / Phase 3 RAG ⏳"，而实现早已完成 | 重写为"功能清单 + 状态"，并补 Troubleshooting/Security/Roadmap/Screenshots |

> P1 共 18 条，按模块归并后是 14 个模块行（表里合并了同模块的项）。

#### 5.3 修正的审计结论（动手时发现的，审计当时没看到）

审计的价值在于把范围框住；它写下的**方案**则要在动手第一步复核。5.3 复核出四处偏差，
其中两处改变了做法（详见 `docs/decisions.md` B.44）：

| # | 审计写的 | 实际是 | 后果 |
| --- | --- | --- | --- |
| 1 | P1-4：给 `DocumentSummary` 补 `errorMsg` | `DocumentStatus.FAILED` **没有任何写入路径**，补上去是一个永远为 null 的字段 | 改成补 `kb_import_task_item` 的**读接口**（那张表只有写入方） |
| 2 | P1-2：删文档时清理 chunk / 向量 / ES | **存量删除路径今天就在漏**：`DocumentPersistServiceImpl.delete` 不清向量/ES/关联边；`KnowledgeBaseServiceImpl.delete` 连 `kb_chunk` 都没删（`@TableLogic` 逻辑删除，注释却写"物理删除"），向量与 ES 也不碰，代码里留着 TODO 自认 | 只修新端点等于"修了一个，漏了仨"，库级那条一并堵上 |
| 3 | （未提及） | **孤儿还有第四条路径：重新切分**。覆盖已有文档时切片换新 id，向量按 `chunk_id` 存 → 旧向量全部成孤儿。`ChunkEmbeddingService.deleteVectors` 的 javadoc 早写着"文档被删除或**重新切分**时调用"，但从没人从这个方向调用过 | `save()` 更新分支补一次外部清理；**代价如实披露**：每个变更文件多一次向量清理 + 一次索引清理（此前为 0） |
| 4 | （未提及） | **扫描期跳过的条目从来没被记录**：执行器只加了计数、没调 `recordItem`，于是 `ImportAction.SKIPPED` 与 `Counters.case SKIPPED` 是**死代码** | 补齐明细。用户最常问的"我的 `.pptx` 为什么没进来"从此有答案 |
| 5 | （未提及） | **墓碑陷阱比预想的近**：`uk_doc_kb_path (kb_id, source_path, deleted)` 此前只有"重复导入 + 删文件"碰得到，加了删除按钮就是**一键复现** | 逻辑删除前先清同路径旧墓碑（原生 SQL 绕过 `@TableLogic`） |
| 6 | （未提及） | `refreshKnowledgeBaseStats` 把整个库的 chunk 行读进内存求 token 和 → 大库上 O(全库切片)；单文档删除会走它 | 改成一条 `COUNT` + 一条 `SUM(token_count)` |

第 1 条和第 3 条是同一类问题的两种形态：**字段/注释描述的"意图"被当成了"事实"**。
`DocumentStatus.FAILED` 看起来是个完备的状态机状态，实际无人写入；
`deleteVectors` 的 javadoc 说"重新切分时也调用"，但从来没有人从这个方向调用过。
审计靠"读代码 + 读字段"很难发现这一类，只有追一遍**写入方**才能确认。

#### 5.6 修正的审计结论（动手时发现的，审计当时没看到）

| # | 审计写的 | 实际是 | 后果 |
| --- | --- | --- | --- |
| 1 | P1-15：`PUT /users/me` "修改昵称/头像" | **头像没有存写入路径**（`sys_user.avatar_url` 有列，但没有上传接口），本期只放开昵称 + 邮箱 | 少做一个字段，也少一条"改了但没生效"的路径；`api.md` 已改成实话 |
| 2 | （未提及） | **`register` 没预检邮箱，而 `sys_user` 上有 `uk_user_email(email, deleted)`**：用重复邮箱注册会撞唯一键 → `500/9999`，用户看到"系统内部错误"却不知道该换邮箱 | 新增 `EMAIL_ALREADY_EXISTS(2007)` → `409`，注册与改资料共用同一个预检 |
| 3 | P1-16 隐含"限流就是拦请求" | **配额的另一半是记账**：只拦不记 = 只能按次数计费，token 用量永远是 0 | 补 `UsageMeteringAdvisor`；`recordTokens` 是**尽力而为**（模型已调用，无法回滚），所以刻意不抛异常 |
| 4 | （未提及） | **`api.md` §2 有五条"声称有、实际没有"的接口**（refresh / logout / `PUT /me` / `PUT /me/password` / `admin/users`），§12 整套限流描述同样是零代码 | 一并改成实话：做成的移进"已实现"，没做的移进"规划中"表。**文档不实比功能缺失更贵**——它让"照文档接一下就有了"的判断落空，而且落空发生在集成之后 |
| 5 | （未提及） | **一个 order 恰好等于 `Ordered.LOWEST_PRECEDENCE` 的 Advisor 会被静默跳过**（框架的 `ChatModelCallAdvisor` 就占着这个值）：模型照调、回答照回、日志全无，只是记账永远 0 | 加回归断言钉死顺序（B.47 §②）。这是本期最有价值的发现，也是"错了也不报错"这一类问题的又一例 |
| 6 | P1-16："登录/注册/问答三类入口加限流" | **加上去的注册限流会把开发环境自己拦在外面**：本机请求全来自 `127.0.0.1`（实测报出来是 IPv6 的 `0:0:0:0:0:0:0:1`），而 E2E 脚本每轮会自己注册临时账号。真机实测同源连续注册**第 9 次**开始 `429/7001`，且**症状跑到别处**——注册失败后紧接的登录自然 401，看起来像认证坏了 | `standalone` / `local` 默认放宽到 `1000`（`${NEXUS_REGISTER_PER_HOUR:1000}`），生产默认仍是 10；要验真实阈值显式设 `NEXUS_REGISTER_PER_HOUR=10`。`test` profile 上一期就放宽过，**这次是把同一个结论补到另外两个开发 profile** |
| 7 | （未提及） | **配额路径清单初稿是 `/api/chat/**` 通配**：读历史消息、列出会话、删除会话、停止生成都被算进当日额度——真机 E2E"刚进对话页就显示 1/200"就是它的痕迹 | 收窄成 `/api/chat/stream` + `/api/chat/conversations/*/messages` 并加反证测试；否则用户在**不花钱的操作**上被扣额度，「今日 N/M 次」也不再等于"问了几次"。**通配与 Advisor 的 order 同类：越界了不会响，只能靠数字对不对去发现** |

---

### P2 —— 可以后续迭代

| 编号 | 模块 | 问题 | 备注 |
| --- | --- | --- | --- |
| P2-1 | Auth | **无登出接口、无 refresh token** | 5.6 只解决了"改密码后旧令牌失效"（`token_version`，ADR-020）。**登出仍然是"客户端丢弃令牌"**——服务端没有任何动作。refresh / `sys_refresh_token` 属 ADR-021 第 ⑤ 阶段，未做 |
| P2-2 | Auth | ~~登录失败无计数/无锁定~~ | ✅ **5.6（2026-10-02）**：`LoginAttemptLimiter`（5 次 / 15 分钟 → 冻结 15 分钟，fail-open）。**已知代价**：按 username 冻结可被用来定向刷锁别人的账号，窗口因此取得短（ADR-020） |
| P2-3 | Security | **令牌存 localStorage（XSS 可窃取）** | ⚠️ **5.6 只出了设计，风险仍在**：ADR-021 给了分阶段迁移路径（Cookie + CSRF + refresh），代码零改动——用户明确要求"不要直接大改"。今天的缓解只有 30 分钟 TTL，且**没有 refresh ⇒ 被盗后无法主动止损** |
| P2-17 | Security | **配额可被绕过**：Redis 重启 / `allkeys-lru` 淘汰会丢当日计数；多实例下内存实现不共享（每实例各算一份，限流被放大 N 倍） | 5.6 新增。要真正可靠得把当日用量落到表（`sys_user_daily_usage`）或至少确保 Redis 不淘汰这些键；**多实例部署必须 `nexus.security.redis-enabled=true`**（已写进 `application.yml` 注释） |
| P2-18 | Security | **账号 `status` 改动 / 软删除不会立即吊销已签发令牌** | 5.6 新增。`TokenVersionValidator` 只比对 `token_version`：软删除时 `selectById` 为 null 会被拒，但仅把 `status` 改成禁用，令牌会活到过期（≤30 分钟） |
| P2-4 | RAG | 「检索到 N 个片段」不落库，刷新即消失 | 已知缺口，B.37 记录在案 |
| P2-5 | RAG | 无真正的 query rewrite（只有指代拼接） | |
| P2-6 | Tool | 无 Tool 超时 | 当前唯一工具是进程内调用；**第一个会阻塞的外部工具落地时必须一起补** |
| P2-7 | Tool | `query` 空串未校验（只校验了 `topK` 边界） | |
| P2-8 | Tool | 无权限分级 / 人工审批 | update.txt §十 未要求，项目自己标为后续 |
| P2-9 | Observability | 无 Prometheus 端点；降级事件重启即失 | |
| P2-10 | Testing | 无 Testcontainers；真机回归靠手工脚本 | |
| P2-11 | Deployment | 上传文件落本地磁盘（容器化后需卷/MinIO） | 随 P1-17 一起定 |
| P2-12 | Docs | 缺 Troubleshooting / Security / Roadmap / Screenshots 章节 | |
| P2-13 | Chat | 长会话无"清空消息" | |
| P2-14 | DB | 无外键约束（全部逻辑关联） | 需在 database.md 写明为什么（MyBatis-Plus 逻辑删除 + 应用层校验） |
| P2-15 | DB | `uk_user_username (username, deleted)` 的逻辑删除陷阱：同一用户名删两次会撞唯一键 | 当前**无删号接口**，尚不可达；补删号功能前必须先修 |
| P2-16 | DB | `kb_attachment` 不在删除清理范围内 | Phase 5.3 的删除矩阵只覆盖 chunk / tag / link / 文档 / 向量 / ES。该表目前只有登记与展示、**没有写入路径**，所以还产生不了孤儿；做多模态附件上传时，删文档 / 删库必须同步补上它 |

### P3 —— Nice to have

- 会话搜索、会话置顶/归档
- Agent 版本历史与回滚
- 引用高亮跳转到切片内的具体位置
- 用户头像 / 主题切换
- 工具市场（外部 MCP / HTTP 工具接入）
- 评测集管理界面（`EvalController` 已有接口）
- 错误码的字符串别名（`AGENT_NOT_FOUND` 形式）—— 现有数字码 + 枚举已等价，属表达偏好

---

## 4. 建议的实施顺序

按 update.txt §二十二 的节奏（Audit → Gap → P0 → Test → P1 → Test → P2 → Final E2E → Docs），
把 P0/P1 拆成小步，**每步都跑测试 + 构建 + 更新文档**：

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| **Phase 5.1** ✅ 完成 2026-10-01 | **P0-1**：`/mcp` 身份改走 JWT（头直接删除，不留第二身份来源） | 新增 5 条测试（无凭据 401、只带头仍 401、伪造他人 id 只能看到自己的数据、零库调用方、正向对照）+ **旧实现下 5/5 全红**；`mvn test` 382/0/0/18 绿；api.md/deployment.md/.env.example 改口径 |
| **Phase 5.2** ✅ 完成 2026-10-01 | 错误契约三处漏洞：P1-5（上传超限）、P1-7（同步 RAG 异常）、P1-10（循环超限）+ P1-11（上限显式化）+ P1-13（错误帧不区分来源）。**真机验证把范围扩大了**：`/api/agent/ask` 等四个非流式入口原本回 500/9999、`ChatServiceImpl` 把上游原文回显并落库（B.43 §4） | 新增 24 条测试（含错误码↔HTTP 契约、真实 Tomcat 上传超限、真撞一次工具上限、同步 RAG 失败不落档）；`mvn test` 406/0/0/18 绿；`mvn package` 成功；真机实测三种入口口径一致 + 413/3006 |
| **Phase 5.3** | 知识库闭环补完：P1-1（单文件上传）、P1-2（删文档）、P1-3/P1-4（失败原因可见）。**动手时范围扩大**：存量删除路径（文档级 + 库级）都在漏，且多出"重新切分"这条孤儿路径，一并修（见 B.44） | ✅ **全部达成**。代码/测试/构建/文档：新增 20 条测试 → `mvn test` 426/0/0/18，`mvn package` 与 `npm run build` 成功；新增 `verify-doc-lifecycle.mjs`（退出 0，三张截图）。**真机验收已执行且全绿**（2026-10-01）：V15 在真 MySQL 落地；PDF/DOCX/TXT/MD 各上传一次全部 `created=1 failed=0`、`source_type='UPLOAD'`；删除后向量 / ES / 切片 / 标签 / 出链全部归零、磁盘原件消失、入链按 Obsidian 语义保留；墓碑路径两轮通过；删库同样归零。证据与脚本陷阱见 B.44 §9 |
| **Phase 5.4** | Agent 闭环补完：P1-8（temperature）、P1-9（Tools UI）、P1-12（重命名会话）、P1-14（agentId 落库）。**动手时范围扩大**：非流式入口完全不看会话模式，AGENT 会话不用 Agent 提示词、RAG 会话不查笔记，两者都不报错（见 B.45 §1） | ✅ **全部达成**。较 5.3 净增 14 条测试 → `mvn test` **440/0/0/18**；`mvn package` 与 `npm run build`（含 `vue-tsc`）成功。**真机验收已执行且全绿**（2026-10-02）：V16/V17 在真实 MySQL 落地且结构与设计逐字一致；工具/温度回读、重命名刷新后仍在、出处落库（含"会话没选 Agent 时记内置默认真实 id"）、`/api/observability/agents`（无 token 401）、非流式 AGENT 200 / 非流式 RAG 409+8003 且零副作用；**8 个 verify 脚本全部退出 0**。**跑出两个"脚本在骗人"的问题**（其中一个把成功的重命名判成失败），均已修，见 B.45 §7 |
| **Phase 5.5** ✅ 完成 2026-10-02 | 多库 RAG：P1-6。**动手时范围小于原估**：检索层（`RetrievalQuery.knowledgeBaseIds` + 四个 Retriever 的逐库循环）早就是多库的，数据库 `kb_ids` 早就是 JSON 列、Agent 的 `agent_knowledge_base` 也存在——**零迁移**。真正要拆的是三道人工闸门，外加 `SearchHit` 丢掉 `knowledgeBaseId`（见 B.46 §1） | ✅ **全部达成**。较 5.4 净增 22 条测试 → `mvn test` **462/0/0/18**；`mvn package` 与 `npm run build`（含 `vue-tsc`）成功。**真机验收已执行且全绿**（2026-10-02，local + ES + pgvector + 真模型）：两库各导入一份内容不同的文档 → 界面**多选**绑定两库 → 提问只有第二个库能回答的问题，检索卡片显示"检索到 2 个片段 / 2 个知识库"、**每条命中各自标出来源库**，回答引用了第二个库的独有事实（`NX-7741` / `220 毫秒`）；越权绑定返回 404 + 3001 **且文案指名 id**；**8 个既有 verify 脚本全部退出 0**（单库回归）；`flyway_schema_history` 不变。新增脚本 `verify-multi-kb.mjs` |
| **Phase 5.6** ✅ 完成 2026-10-02 | 用户与安全：P1-15（改密码 + 资料编辑）、P1-16（限流 + 配额）。**动手时范围扩大**：审计只要求"三类入口加限流"，实际补了登录失败冻结与每日 token 记账；**顺带修掉一个没人报的静默不一致**（重复邮箱注册返回 500 而不是 409，见 §5.6 修正表）；**Redis 那部分照写实现 + 开关**，默认走进程内存（见 B.47） | ✅ **全部达成**。较 5.5 净增 79 条测试（逐类相加 78 + 收窄配额路径后补的反证测试 1）→ `mvn test` **541/0/0/18**；`mvn package` 与 `npm run build`（含 `vue-tsc`）成功。**真机验收已执行且全绿，且全程不使用 Docker**（见 B.47 末节）：`standalone` + 内存实现、`standalone` + **本机原生 Redis（非 Docker）**两条路径各跑一次 `verify-security.mjs`，**均退出 0**；`redis-cli` 直接看到 `nexus:auth:tv` = 1（改密后版本 +1）、`nexus:auth:lf` = 5 / `ll` = 1（连错 5 次后**正确密码也被 429 拦下**）、`nexus:rl:u` / `nexus:q:req` 计数与 TTL 全部符合设计；用户级限流实测第 21 次 `429 + Retry-After: 60`，每日配额压到 2 后第 3 次 `403/7002`（文案与"服务不可用"可区分）；**降级语义逐条实测**（Redis 停掉后：注册/登录/改密照常 200，模型入口 403/7002，旧令牌 401）。**逼出两个问题**（注册限流误伤开发环境、`repackage` 的 jar 占用报错），均见 §5.6 修正表与 B.47。⚠️ **8 个既有 `verify-*.mjs` 本轮未复跑**——它们依赖 Docker 基础设施栈（ES / pgvector / 真模型），留待 5.7 容器化后统一复跑 |
| **Phase 5.7** | 部署产品化：P1-17（Dockerfile + nginx + compose 补两个服务） | `docker compose up -d` 后前端能登录并用 Agent 问答 |
| **Phase 5.8** | README/docs 产品化：P1-18 + P2-12 | 按 update.txt §二十 的十节清单核对 |
| **Phase 5.9** | Final E2E + 全量验收 | `mvn test`、`mvn package`、`npm run build`、9 个 `verify-*.mjs` 全绿；update.txt §二十四 的验收矩阵逐项留证 |

---

## 5. 明确**不做**的重构（update.txt §二）

| 不做 | 为什么 |
| --- | --- |
| 不拆微服务、不引入 Nacos | 现在是模块化单体，边界靠依赖方向保证；拆开只增加部署复杂度，不解决任何现存问题 |
| 不换数据库、不引入第二套向量库 | MySQL + pgvector + ES 已经够用，且 pgvector 的 `kb_id` 下推正是隔离的实现方式 |
| 不重写 Agent Loop | 两条链路共用 `AgentRuntime.configure`，轮数上限已由框架提供（本轮 javap 已确认） |
| 不重写 `requireOwned` 体系 | 43 处 `requireId` + 8 组跨用户测试是全项目最扎实的部分，只补 `/mcp` 这一处 |
| 不为 trace 新建一张表 | 消息上的 8 个字段已经覆盖 §12 的绝大部分；只补 `agent_id`，不造第二份真相 |
| 不引入 Redis 做缓存/会话 | 除非用于令牌失效或限流（P1-16 确实需要时才加），否则是"为了看起来高级"。**5.6 正是"确实需要"的那一次**：令牌版本、登录失败计数、限流计数、每日配额四项都落在 Redis 上——但**端口化 + 内存实现**，默认不依赖它（`standalone` / `test` 零外部基础设施），`nexus.security.redis-enabled` 才切到 Redis |

---

## 6. 本次审计**没有**验证到的部分（诚实边界）

1. **真机全栈行为**没有重跑（本次只跑了 `mvn test`）。上一轮真机记录见 `platform-phase3-baseline.md` §9.6。
2. ~~**MCP 越权是读代码 + 读 Security 配置得出的结论**，我没有实际发一个跨用户请求去验证。~~
   **已关闭（Phase 5.1）**：跨用户请求真的发过了，而且是把**旧实现**放回去发的——
   `McpEndpointAuthTest` 在旧代码上 5/5 全红，其中一条的输出是
   `在全部知识库检索了「缓存击穿」，没有匹配的资料`：身份被请求头改写成了受害者，工具去查了**他的**知识库。
   这条 P0 现在是可复现的事实，不是推断。
3. **N+1 / 慢查询**没有做（没有执行计划分析）。缺失索引只在迁移脚本层面看过，
   没有真实数据量下的压测。
4. **前端只有 5 个视图**，我按行读完了 grep 结果与关键模板，但没有逐行读完 4138 行。
   可能存在我没注意到的局部交互问题。
5. ~~**`temperature`、单文件上传**等建议方案的**实现成本**没有评估（是否牵连测试、是否需要新依赖），
   实际动手时若发现成本超出预期会先报告再调整。~~
   **两者均已落地**（单文件上传 5.3、temperature 5.4），且成本都低于预期：
   temperature 的实际牵连是**一次范围扩大**（非流式入口不看会话模式，见 B.45 §1），
   **不是**新依赖或测试负担。

---

## 7. 一句话总结

这个项目的**核心链路（认证 → 知识库 → RAG → Agent → 工具 → 对话 → 痕迹）和**数据隔离
（`requireOwned` 体系）**都已经真实存在且测试充分**（审计当时 377 用例 0 失败；
写完 5.6 后是 **541 用例 / 101 类 / 0 失败**——每一期净增的用例都逐类记在 §4 对应行里）。

真正的差距集中在三类：

1. ~~**一条 P0 安全/隔离漏洞**：`/mcp` 无认证且身份可自证 —— 必须最先修；~~
   ✅ **5.1 已完成**（含"旧实现下 5 条测试全红"的反证）；
2. ~~**闭环的"最后一段"没接到界面上**：配 Tool、重命名会话、导入失败原因、单文件上传、删文档 ——
   后端大半已经有，缺的是接口或界面；~~ ✅ **已全部接上**：知识库部分 5.3（P1-1/P1-2/P1-3/P1-4），
   Agent 部分 5.4（P1-9 配 Tool、P1-12 重命名会话、P1-8 温度回显）；
3. **失败路径的口径不统一**：上传超限→500、循环超限→"模型失败"、同步 RAG 异常→当成回答 ——
   与 §15「统一异常」直接冲突。

**先做 1，再做 3，再做 2**：安全漏洞的暴露面是"零成本"，失败路径的口径问题会让所有后续排查都被误导，
界面缺口虽然最显眼，但它是唯一"不影响正确性"的一类。

**进度**：1 与 3 已完成（5.1 / 5.2，均 2026-10-01）；第 2 类（"最后一段没接到界面上"）
**已全部完成**——知识库部分 5.3（2026-10-01，P1-1/P1-2/P1-3/P1-4）、
Agent 部分 5.4（2026-10-02，P1-8/P1-9/P1-12/P1-14）、
多库 RAG 5.5（2026-10-02，P1-6），三期均**含真机验收**，见 §4。

**5.6 已完成并真机验收**（用户与安全：P1-15 资料/密码自助 + P1-16 限流与配额）——
两条路径（`standalone` + 内存实现 / `standalone` + 本机原生 Redis）各跑一次
`verify-security.mjs` 均退出 0，降级语义逐条实测，见 §4 的 5.6 行与 B.47 末节。
**验证全程不使用 Docker**：`standalone` 用 H2，Redis 用本机原生实例。
（因此 8 个依赖 ES / pgvector / 真模型的既有 E2E 脚本本轮未复跑，留待 5.7。）
下一阶段是 **P1-17（应用容器化，`docker compose up -d` 起不来这件事）**。

> 5.4（Agent 闭环补完）**2026-10-02 已完成并真机验收**，见 §4 的 5.4 行与 B.45。
> 5.5（多知识库 RAG）**2026-10-02 已完成并真机验收**，见 §4 的 5.5 行与 B.46。
> 5.5 是这几期里唯一**没有数据库迁移**的一期：`chat_conversation.kb_ids` 本来就是 JSON 列，
> Agent 侧的 `agent_knowledge_base` 关联表也早就存在——卡点是三道人工闸门，不是数据结构。
> 5.6 **只加了一列**（`sys_user.token_version`，V18），没有新表；
> 它同时**修掉了本文档最难看的一处**——`api.md` 里五条"声称有、实际没有"的接口。
> 新增的两个安全缺口（refresh token 缺席、localStorage 的 XSS 暴露面）登记为 P2-1 / P2-3，
> 而不是假装被这一期顺手解决了。
