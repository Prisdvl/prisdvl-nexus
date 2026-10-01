# prisdvl-nexus 平台化升级 · Phase 3 前代码基线扫描与设计

> 状态：**只读扫描 + 设计，未修改任何代码**（对应 `docs/update.txt` §七十「现在首先只执行 Phase 3 前代码基线扫描」）。
> 基线 commit：`bbc035b`。扫描方式：五个并行只读扫描（检索链 / app 面 / 数据库与实体 / 前端与工程化 / 硬编码耦合）
> + 对本仓库若干关键文件的直接核对。凡本文件给出的 `path:line` 都来自当前工作树。
> 本文提供 §七十 要求的八份交付物：§一 当前架构扫描、§二 Phase 3 设计、§三 Retriever 抽象方案、
> §四 Workspace 资源隔离迁移方案、§五 Agent/Tool/Model 配置化方案、§六 数据库变更方案、
> §七 API 变更方案、§八 测试方案。**集中未确认项在 §十**。

---

## 一、《当前架构扫描》

### 1.1 模块与依赖方向（硬约束，决定一切设计）

`nexus-common ← nexus-ai ← nexus-knowledge ← nexus-app`，单向、无环：

| 模块 | 依赖 | 依据 |
| --- | --- | --- |
| nexus-common | 无内部依赖（slf4j / jakarta.validation / lombok） | `nexus-common/pom.xml:23-45` |
| nexus-ai | common | `nexus-ai/pom.xml:30-34` |
| nexus-knowledge | common、**ai** | `nexus-knowledge/pom.xml:28-37` |
| nexus-app | common、ai、**knowledge** | `nexus-app/pom.xml:24-35` |

**推论（本次升级最关键的一条）**：`nexus-ai` **不能**依赖 `nexus-knowledge`。
任何"放在 ai 的抽象"都只能用 ai 自己能看到的类型 —— 这正是目标架构（`docs/update.txt` §五十、
`platform-upgrade-phase1-analysis.md` §10-2）"**抽象端口放 ai、RAG 具体实现在 knowledge、app 做组合根**"的由来。
Phase 3 的 Retriever 端口必须按这条规则选位置（详见 §三）。

启动类：`nexus-app/.../NexusApplication.java:23-24` —— `@SpringBootApplication(scanBasePackages = "com.lky.nexus")`
+ `@MapperScan(basePackages = "com.lky.nexus", annotationClass = Mapper.class)`。
**因此放在 ai / knowledge 里的 `@Component` 与 `@Mapper` 会自动注册**，端口与实现可以分处两个模块而无需额外配置
（现存证据：`nexus-knowledge/.../rag/LlmReranker.java:29` 是 `@Component`，一直被正常注入）。

其它构建事实：Boot 4.1.1 / Spring AI 2.0.1（BOM）/ Java 21 / MyBatis-Plus 3.5.17 / ES Java Client 9.4.5 /
pdfbox 3.0.7 / poi 5.5.1（`pom.xml:51-59`）；surefire 统一 `argLine`（`pom.xml:144`）；
**无 Maven profile**；`skipUnitTests` 属性声明但无人引用（`pom.xml:62`）。

### 1.2 规模与现状一句话

主代码约 205 个文件（common 9 / ai 7 / knowledge 94 / app 95）；测试文件 47 个。
**"能跑通"的部分质量不低**（端口、状态机、SSE、统一响应、AI 抽象都在），
**"平台化"的部分基本为零**：无 Agent/Tool/Workflow/Application/Model 的用户资源表，无审批，无审计，无持久化观测。

### 1.3 已落地能力（**保护区**，`docs/update.txt` §六十五：不允许因重构而失效）

| 能力 | 关键实现 | 状态 |
| --- | --- | --- |
| 账号 / JWT | `auth/**`，无状态 HS256，`CurrentUser.requireId()` 唯一用户来源 | ✅ 已真实验证 |
| RBAC | URL 规则 + `sys_role` 种子（USER/ADMIN） | ✅（仅 2 角色，`@PreAuthorize` 全仓不存在） |
| 会话 / 消息 / 记忆 | `chat/**` + `SPRING_AI_CHAT_MEMORY`（V3） | ✅ |
| SSE 流式 + 停止生成 | `ChatStreamServiceImpl`（Flux→SseEmitter）、`StreamCancellationRegistry`、`STOPPED` 落库 | ✅ |
| 知识库 / 导入 / Vault / 增量同步 | `knowledge/**` + MQ 异步（`RabbitImportConfig`） | ✅ |
| 切分 / 嵌入 / pgvector / ES+IK | `chunk/**`、`PgVectorStore`、`ElasticsearchChunkIndex` | ✅ 真实环境验证过 |
| 混合检索 / RRF / 重排 / 引用 | `RrfFusion`、`LlmReranker`、`ContextAssembler`、`Citations` | ✅ |
| RAG 问答（含 SSE） | `RagAnswerServiceImpl`、`RagStreamServiceImpl` | ✅ 非流式 + 独立流式 |
| Agent 工具循环 | `AgentService` + Spring AI `ToolCallingManager` + `ToolContext` | ✅（含跨线程修复，见 decisions.md B.30） |
| 工作流引擎 | `WorkflowEngine`（纯静态、顺序、失败即中止、后续 SKIPPED） | ✅ `WorkflowEngineTest` |
| 评测 MVP | `EvalService`（要点命中率 + 引用有效性，走 Agent 链） | ✅ |
| MCP | `McpEndpoint`（initialize / ping / tools/list / tools/call） | ✅（本地信任模型） |
| 观测 | `ObservabilityRegistry`（3 个计数器，仅内存） | ✅（最小） |

### 1.4 检索链现状（逐环节，Retriever 设计的输入）

```
Controller(GET /search, POST /ask, POST /ask/stream)
   └─ ChunkSearchService.search(userId, kbId, query, topK, mode)   ← 唯一的检索入口
        ├─ KnowledgeBaseService.requireOwned(userId, kbId)         ← 归属校验（user_id 维度）
        ├─ mode.usesBm25() → ChunkIndex.search(kbId, query, depth) → ES BM25
        ├─ mode.usesVector() → EmbeddingClient.embed + VectorStore.search(kbId, vec, depth, 0.25)
        ├─ switch(mode) → 单路 ranked(...) 或 RRF: RrfFusion.fuse(lists, limit)
        └─ hydrate(...) → 回表补 content/title（ChunkMapper/DocumentMapper）
```

| 环节 | 位置 | 关键事实 |
| --- | --- | --- |
| 检索入口 | `nexus-knowledge/.../service/ChunkSearchService.java:20` | `search(Long userId, Long knowledgeBaseId, String query, int topK, SearchMode mode)` —— **只有一个方法，没有 filters 参数** |
| 实现 | `.../service/impl/ChunkSearchServiceImpl.java:85` | 类内常量 `MAX_TOP_K=100`(:51)、`MAX_CANDIDATES=200`(:53)、**`MIN_VECTOR_SCORE=0.25`(:61)** |
| 召回深度 | 同上 :91 | `depth = min(200, limit*3)`（3 倍过召回） |
| 向量腿 | 同上 :142-150 | 阈值为**硬编码常量 0.25**，不读知识库配置 |
| 融合 | `.../search/RrfFusion.java:38` | 静态工具类，`DEFAULT_K=60`，只用排名 |
| 模式 | `.../search/SearchMode.java:9` | `BM25 / VECTOR / HYBRID`（`usesBm25()` :29、`usesVector()` :33） |
| 重排 | `.../rag/LlmReranker.java:68` | **不在 ChunkSearchService 里**，由 `RagAnswerServiceImpl` 调用，`RERANK_CANDIDATES=8`(:55) |
| 上下文组装 | `.../rag/ContextAssembler.java:37` | 入参是 `List<ChunkSearchService.SearchHit>`，`MAX_SEGMENT_CHARS=1500`(:29) |
| 回答 | `.../rag/RagAnswerServiceImpl.java` | `ask`(:124) / `prepare`(:204) 均写死 `SearchMode.HYBRID` |
| 命中模型 | `ChunkSearchService.SearchHit`(:29) | `chunkId, documentId, documentTitle, headingPath, content, score, source, matchedBy` —— **无 fileName / sourcePath / page** |
| 引用模型 | `RagAnswer.RagCitation`(:32) | `index, chunkId, documentId, documentTitle, headingPath, snippet, score, referenced` —— **同样无 page** |

**检索参数的真实来源（全部调用点）**：

| 调用点 | topK | mode | threshold | rerank |
| --- | --- | --- | --- | --- |
| `KnowledgeSearchController.java:64` | 请求参数（默认 10，clamp 1..100） | 请求参数（默认 hybrid） | 常量 0.25 | 无 |
| `KnowledgeSearchTool.java:78` | **字面量 5** | **字面量 HYBRID** | 常量 0.25 | 无 |
| `RagAnswerServiceImpl.java:124`、`:204` | 方法参数（来自 `KnowledgeRagController.java:44,75`，默认 12，上限 30） | **字面量 HYBRID** | 常量 0.25 | 是，前 8 条 |

> **`kb_knowledge_base.retrieval_top_k` / `similarity_threshold`（V10 新增）在业务路径上从未被读取。**
> 仅在创建/更新时写入（`KnowledgeBaseServiceImpl.java:87-89,107-108,164-168`）与返回响应时回显（`:254-255`）。
> V10 迁移脚本自己的注释就写着"本次只落库并暴露 API，实际生效在 Phase 3 的 Retriever 抽象"
> （`V10__knowledge_base_platform_fields.sql:23-29`）。**换句话说：现在这两个字段是"假可配置"。**

**元数据过滤（tags / folder / documentId）当前完全不存在** —— `ChunkIndex.search` 与 `VectorStore.search`
的签名里只有 kbId。但 **ES 索引 mapping 里已经有 `user_id`、`tags`(keyword)、`folder`(keyword) 字段**
（`ElasticsearchChunkIndex.java:231-243`），这是 Phase 3 做过滤时可以直接吃的既有资产（详见 §3.8）。

**降级阶梯只有 Agent 工具才有**：`KnowledgeSearchTool.java:79-92`（混合 → SQL LIKE → 如实说明），
`ChunkSearchService` 保持"不降级"是刻意设计（decisions.md B.29 第 3 点）。

### 1.5 SSE 与 Agent 链现状

- 事件名常量已定义 `retrieval` / `citation` / `tool_call` / `tool_result` / `approval_required`
  （`SseEventNames.java:31-37`），但 **`retrieval` 与 `citation` 至今没有任何发送点**。
- **`/api/chat/stream` 在 `mode=RAG` 下不做任何检索**：`ChatStreamServiceImpl.java:136-143` 只在
  `mode=AGENT` 时挂工具，其余情况就是纯生成。`ChatMode.java:14` 对 RAG 的注释（"先检索知识库再回答，必须带引用"）
  与实现不一致 —— **这是 Phase 3 最直接的功能缺口**。
- RAG 的检索+引用只走知识库侧独立接口：`POST /api/knowledge-bases/{id}/ask`（非流式）与
  `/{id}/ask/stream`（`RagStreamServiceImpl`，前端 `knowledge.ts:365-370` 只监听 `meta/delta/done`）。
- 工具痕迹落库：`chat_message.metadata.toolCalls`（`ChatStreamServiceImpl.java:337-338`）。
- Agent 系统提示词是**硬编码 Java 常量**：`AgentPrompts.java:15`（9 行内容），无变量插值。
- Agent 模型角色固定 `chat.agent`（`AgentService.java:42`）；角色→模型映射在 `ai.roles` 配置。

### 1.6 数据模型现状（MySQL 迁移 V1–V10 + 向量 V1）

已执行的主库迁移（`db/migration/`，共 10 个）：

| 迁移 | 创建/变更 |
| --- | --- |
| V1 `auth_baseline` | `sys_user` / `sys_role` / `sys_user_role`（含 USER、ADMIN 两条角色种子） |
| V2 `chat` | `chat_conversation` / `chat_message` |
| V3 `spring_ai_chat_memory` | `SPRING_AI_CHAT_MEMORY`（Spring AI 2.0.1 自带 DDL，全库唯一 `ENUM` 列） |
| V4 `knowledge` | `kb_knowledge_base` / `kb_document` / `kb_document_tag` |
| V5 `chunk_and_import_task` | `kb_chunk` / `kb_import_task` / `kb_import_task_item` |
| V6 `document_stored_path` | ALTER `kb_document` ADD `stored_path` |
| V7 `qa_record` | `kb_qa_record` |
| V8 `document_link` | `kb_document_link` |
| V9 `workspace` | CREATE `workspace`；ALTER `kb_knowledge_base` ADD `workspace_id`；CREATE `idx_kb_workspace` |
| V10 `knowledge_base_platform_fields` | 4 条独立 ALTER `kb_knowledge_base` ADD `icon` / `status` / `retrieval_top_k` / `similarity_threshold` |

向量库迁移（`db/vector-migration/`，共 1 个，仅 `nexus.vector.enabled=true` 即 local profile 执行）：
V1 `vector_store` → `kb_vector`（PK=`chunk_id`，`vector(1024)`，HNSW `m=16,ef_construction=64`）+ `embedding_cache`。

全部 15 张主库表：`sys_user` / `sys_role` / `sys_user_role` / `chat_conversation` / `chat_message` /
`SPRING_AI_CHAT_MEMORY` / `kb_knowledge_base` / `kb_document` / `kb_document_tag` / `kb_chunk` /
`kb_import_task` / `kb_import_task_item` / `kb_qa_record` / `kb_document_link` / `workspace`；
向量侧 2 张：`kb_vector` / `embedding_cache`。**逐列结构以迁移脚本为准，本文件不复制列清单**
（避免出现第三份会漂移的 schema 描述；需要列级参考时查 `docs/database.md` 或迁移脚本本身）。

| 目标模型（§十六/§四十九） | 现状 |
| --- | --- |
| user / workspace | ✅（`workspace` 1:1 个人工作区，`uk_ws_user_type`） |
| knowledge_base / document / chunk | ✅（KB 已有 workspace_id/icon/status/retrieval_top_k/similarity_threshold） |
| conversation / message | ✅ |
| **agent / agent_knowledge_base / agent_tool** | ❌ 不存在（Agent 是固定代码） |
| **tool / tool_invocation** | ❌ 不存在 |
| **workflow / workflow_version / workflow_execution / node_execution** | ❌ 不存在 |
| **application** | ❌ 不存在 |
| **model_provider / model_config** | ❌ 不存在（配置在 yml） |
| **ai_execution / obs_*** | ❌ 不存在（`kb_qa_record` + `chat_message` + 内存计数器分散三处） |
| evaluation dataset / run / case | ❌ 不存在（评测结果只返回不落库） |

其它实体层事实：
- 逻辑删除只靠 `@TableLogic` 注解（`SysUserPO:55`、`ChatConversationPO:57`、`KnowledgeBasePO:75`、`DocumentPO:85`）；
  **`WorkspacePO:37` 的 `deleted` 没有 `@TableLogic`**，所以工作区不会被框架自动软删除/过滤 —— Phase 4 前要修。
- mybatis-plus 全局 `logic-delete-field` 只配在 `application-test.yml:80-89`，local/standalone 没有；
  生产靠注解生效（能工作，但配置不一致）。
- **全库没有任何 `created_by` / `updated_by` 列** —— 审计要新加。

### 1.7 迁移机制（含一处需要更正的认知）

- MySQL/业务库：`PrimaryDataSourceConfig.java:41-49` 声明 `primaryFlyway`（`classpath:db/migration`），
  **无 `@Conditional`，因此在所有 profile 都会执行**（包括 H2 的 test/standalone）。
- 向量库：`VectorDataSourceConfig.java:63-70`（`classpath:db/vector-migration`），
  类上 `@ConditionalOnProperty("nexus.vector.enabled"=true)` → 仅 local。
- `spring.flyway.enabled: false` 只关掉 Boot 自动配置的 Flyway，**不影响上面那个显式 Bean**。
- 另有 `db/h2/schema.sql`（290 行，`CREATE TABLE IF NOT EXISTS` 风格）由
  `spring.sql.init` 在 H2 profile 加载。

> **更正（4.1 已实测复核）**：迁移脚本用的是**不带 `IF NOT EXISTS`** 的 `CREATE TABLE`（V1–V12 全部如此），
> 而 `schema.sql` 全部是 `IF NOT EXISTS`。若 `schema.sql` 先执行，无 `IF NOT EXISTS` 的迁移必然报"表已存在"；
> 测试长期全绿说明 **Flyway 先执行、`schema.sql` 随后成为空操作**。
>
> 4.1（V12）时直接读了启动日志，结论成立且两条 profile 都验了：
>
> | profile | 命令 | 日志证据 |
> | --- | --- | --- |
> | test | `mvn -pl nexus-app -am -Dtest=AgentResourceTest test` | `All configured schemas are empty; a baseline marker will not be added`（Flyway 面对的是**空库**）→ `Migrating schema "public" to version "1 - auth baseline"` … `to version "12 - agent"` → `Successfully applied 11 migrations to schema "public", now at version v12` |
> | standalone | `java -jar nexus-app-0.0.1-SNAPSHOT.jar --spring.profiles.active=standalone` | 同上，`Successfully applied 11 migrations … now at version v12` |
>
> 注意版本号是 1–10、12（**没有 V11**），这与 `db/migration/` 目录一致。
>
> **结论三条**：
> 1. Phase 3 起新增表/列只需写一个 Flyway 迁移，H2 会自动跟着迁移走；
> 2. `db/h2/schema.sql` 如今是**完全冗余的死文件**（15 张表在迁移里都有，且它比迁移旧——
>    没有 `agent`/`agent_knowledge_base`，也没有 V10 加的平台列），真实 DDL 来源只有迁移。
>    4.1 只更正了它的文件头注释与两个 profile 的注释，**没删**：删它要连带动 `spring.sql.init`，
>    属独立清理项（见 §10 待验证项 1）；
> 3. `application-test.yml` / `application-standalone.yml` 里 `spring.flyway.enabled: false` 配的是
>    Boot 的自动配置，**对主库迁移无效**——主库 Flyway 是 `PrimaryDataSourceConfig` 手工声明的 Bean +
>    `FlywayMigrationInitializer`，不看这个属性。这是"迁移照跑"能发生的原因，也是那两处注释
>    （"H2 测试中关闭"）与现实不符的原因；若要真的关掉，得给那个 Bean 加条件，而不是改 yml。

### 1.8 API 面

17 个 `@RestController` / 37 个端点。文件上传仅一处（`KnowledgeImportController.importVault`）。

| Controller | 基础路径 | 端点 |
| --- | --- | --- |
| AuthController | `/api/auth` | POST `/register`、POST `/login` |
| UserController | `/api/users` | GET `/me` |
| ChatConversationController | `/api/chat/conversations` | POST ``、GET ``、GET `/{id}`、PUT `/{id}`、DELETE `/{id}` |
| ChatController | `/api/chat` | POST `/conversations/{id}/messages` |
| ChatMessageController | `/api/chat/messages` | GET `/{conversationId}` |
| ChatStreamController | `/api/chat` | POST `/stream`（SSE）、POST `/stream/{requestId}/stop` |
| KnowledgeBaseController | `/api/knowledge-bases` | POST ``、GET ``、GET `/{id}`、PUT `/{id}`、DELETE `/{id}` |
| KnowledgeImportController | `/api` | POST `/knowledge-bases/{id}/vault`（**上传**）、GET `/import-tasks/{id}`、POST `/import-tasks/{id}/retry` |
| KnowledgeEmbeddingController | `/api/knowledge-bases` | POST `/{id}/embed` |
| KnowledgeSearchController | `/api/knowledge-bases` | POST `/{id}/index`、POST `/{id}/index/recreate`、GET `/{id}/search` |
| KnowledgeRagController | `/api/knowledge-bases` | POST `/{id}/ask`、POST `/{id}/ask/stream`（SSE）、GET `/{id}/qa-history` |
| KnowledgeDocumentController | `/api/knowledge-bases/{id}` | GET `/documents`、GET `/documents/{docId}`、GET `/documents/{docId}/chunks`、GET `/documents/{docId}/links`、GET `/documents/{docId}/related` |
| AgentController | `/api/agent` | POST `/ask` |
| EvalController | `/api/eval` | POST `/kb/{id}` |
| WorkflowController | `/api/workflow` | GET `/run/{knowledgeBaseId}` |
| ObservabilityController | `/api/observability` | GET `/summary` |
| McpEndpoint | `/mcp` | POST `/mcp`（JSON-RPC） |

安全：`SecurityConfig.java:47-59` 公开端点 = `register` / `login` / **`/mcp`** / health / error；
`/api/admin/**` 需 ADMIN；其余 `authenticated()`。
**授权只有两种手段**：URL 规则 + 手写 `requireOwned(userId, id)`（`id AND user_id` 查询，
`KnowledgeBaseServiceImpl.java:192-204`、`ConversationServiceImpl.java:146-155`），全仓无 `@PreAuthorize` / `@EnableMethodSecurity`。

> 注意"路径有、能力没有"的情况：`docs/api.md` 里还有大量**设计稿端点**（`/api/search` 统一检索、
> `/api/search/explain`、`/api/tools/**`、`/api/agents/**`、`/api/agent/runs/**`、`/api/workflows/**`、
> `/api/workflow-runs/**`、`/api/eval/datasets/**`、`/api/prompts/**`、`/api/mcp/servers/**`、
> `/api/observability/{overview,trend,models,requests}`）**全部未实现**。上表是唯一权威的"已实现"清单。

### 1.9 工程化现状（与 §四十二–四十五 的差距）

| 项 | 现状 |
| --- | --- |
| 应用 Dockerfile | ❌ 不存在（仓内唯一 Dockerfile 是 `deploy/elasticsearch/Dockerfile`） |
| compose | ✅ 6 个基础设施服务（mysql/postgres/redis/es/rabbitmq + ollama profile），**无 app 服务**，无破坏必要 |
| nginx.conf | ❌ 不存在（因此也没有任何 SSE 代理配置） |
| CI | ❌ 完全没有（无 `.github/`、无 `.gitlab-ci.yml`、无 Jenkinsfile） |
| 文档 | ✅ architecture / database / api / roadmap / decisions / deployment / phase1-analysis；<br>❌ **rag.md / agent.md / mcp.md / workflow.md / evaluation.md / observability.md / interview.md 全部缺失** |
| 前端 | Vue 3 + Vite 6 + Element Plus，4 个路由（login / kb-list / kb-detail / agent），无 components 目录；**无任何图形/画布库**（vue-flow/x6/logicflow/d3 均不在 lockfile） |
| 演示数据 | ✅ `scripts/seed-demo.mjs` + `demo-vault.zip`；6 个 `web/scripts/*.mjs` 验证脚本 |

### 1.10 测试基线

| 模块 | 测试类 | 门禁 |
| --- | --- | --- |
| nexus-common | 3 | 无 |
| nexus-ai | 3 | 无 |
| nexus-knowledge | 9 | **无任何门禁**（无 @Disabled/@ActiveProfiles） |
| nexus-app | 32（30 测试类 + 2 测试专用 Controller） | 7 个类用 `@EnabledIfEnvironmentVariable` |
| 合计 | **47 个文件** | 默认跳过 **18** 个方法 |

18 个默认跳过的方法精确清单（= 全部 live test，`docs/update.txt` §四十六 要求不得删除）：

| 类 | 环境变量 | 方法数 |
| --- | --- | --- |
| AgentLoopTest | `AI_CHAT_API_KEY` | 1 |
| LiveModelCallTest | `AI_CHAT_API_KEY` | 2 |
| ChatStreamLiveTest | `AI_CHAT_API_KEY` | 3 |
| MultiTurnMemoryTest | `AI_CHAT_API_KEY` | 1 |
| LiveEmbeddingTest | `AI_EMBEDDING_LIVE` | 2 |
| LiveVectorStoreTest | `AI_VECTOR_LIVE` | 4 |
| LiveChunkIndexTest | `AI_SEARCH_LIVE` | 5 |

事实：**全仓无 `@Disabled`、无 `assumeTrue`、无 `@Tag`** —— 跳过机制只有环境变量门禁这一种，口径干净。

**本轮 `mvn -B test` 实测（2026-09-30，`BUILD SUCCESS`）**：

| 模块 | run | failures | errors | skipped |
| --- | --- | --- | --- | --- |
| nexus-common | 14 | 0 | 0 | 0 |
| nexus-ai | 14 | 0 | 0 | 0 |
| nexus-knowledge | 66 | 0 | 0 | 0 |
| nexus-app | 105 | 0 | 0 | 18 |
| **合计** | **199** | **0** | **0** | **18** |

> 口径说明：数字来自 `*/target/surefire-reports/*.txt`，并**剔除两个已删除测试类留下的过期报告**
> （`JacksonProbeTest`、`StreamToolProbeTest` —— 上一轮修 ToolContext bug 时删掉的临时探针）。
> 不剔除的话 app 会虚报成 107。`target/` 是构建产物、不入库，但**"按报告文件数统计测试数"这个做法本身会骗人**，
> 记在这里避免下次再踩。跳过 18 = 7 个 live 类的 18 个方法，与上表精确吻合。

### 1.11 硬编码与耦合清单（Top 10，按阻断程度排序）

1. **工作流是"一个硬编码步骤链"，没有 workflow 标识** —— `WorkflowService.java:65-115`（4 个匿名 Step），
   `WorkflowController.java:26-28` 端点里写死；API/前端无法指定工作流；步骤名是字符串字面量。
2. **工具注册双重硬编码** —— `AgentService.java:47` 与 `ChatStreamServiceImpl.java:142`（`ToolCallbacks.from(单个 bean)`）
   两个调用点 + MCP 另有一份手写 `tools/list` 与 `switch`（`McpEndpoint.java:113-119,135-154`）。
   加一个工具要改三处源码并重编译。
   → **2026-10-01 已修（4.3，见 §9.7 / B.38）**。核对后**实际只有两处**（`ChatStreamServiceImpl` 那个调用点
   自 4.1 起已不存在，只剩一个没人用的 import，已删）：`AgentRuntime.configure` 与 `McpEndpoint`。
   现在两处都读同一个 `ToolCatalog`（从 `@Tool` 方法反射），加一个内置工具 = "写实现 + 在清单里登记一行"。
   **本条的时点描述保留不改**——它记录的是修复前的状态。
3. **嵌入角色与维度硬绑** —— `application.yml:185` `embedding.default: "ollama:bge-m3"`（唯一无 env 覆盖的角色）、
   `:177 dimensions: 1024`、错误文案写死模型名（`EmbeddingClientFactory.java:81`）；且 ADR-008 把维度冻结在 KB 上。
4. **`/mcp` 无鉴权、身份来自请求头** —— `SecurityConfig.java:50` 放行 + `McpEndpoint.java:46-49,76-85` 直接信
   `X-Nexus-User`；`.env.example:77` 的 `MCP_INTERNAL_API_KEY` **从未被代码引用**。任何人可达 `/mcp` 即可读任意用户知识库。
5. **角色→模型解析逻辑三份重复** —— `KnowledgeEmbeddingResolver.java:36-37`、`KnowledgeBaseServiceImpl.java:218-219`、
   `EmbeddingClientFactory.java:55`。
6. **API Key 明文、零加密** —— `AiModelProperties.java:82` 明文 `String`，`AiModelRegistry.java:111` 直接注入；
   全仓无 Jasypt/AES/Cipher。好处是**目前不落库、不回传、不打日志**（`AiModelRegistry` 只记 provider/model 名），
   但"用户自带 Key"这一步必须先有加密与脱敏层。
7. **默认 provider/model 三层重复** —— `application.yml:148-185`、`application-test.yml:57-78`、`.env.example:53-67`；
   测试 `AiModelWiringTest.java:51` 直接断言 `opencode:deepseek-v4-flash`（改默认值会连带改测试）。
8. **检索参数"假可配置"** —— 见 §1.4（KB 上的 topK/threshold 不生效，真生效的是常量）。
9. **同一配置项在代码里二次定义且互相冲突** —— `LlmReranker.java:60` 默认 `12000` vs `application.yml:124` 默认 `5000`；
   `WorkflowService.java:48-49` 默认 `false` vs `application.yml:103,110` 默认 `true`。
10. **降级路径大多不可计数** —— `likeSingle` 失败静默返回空（`KnowledgeSearchTool.java:169-171`）、
    重排超时（`LlmReranker.java:77-89`）、`catch (Exception ignored)`（`DocumentLinkServiceImpl.java:121`）；
    `ObservabilityRegistry` 只有 3 个键。平台要求"记录 fallback"，数据面目前支撑不了。

补充：**ThreadLocal 已清零**（全仓无字段级 `ThreadLocal`；MDC 与 SecurityContext 都在 `finally` 清理/框架管理），
静态可变状态也只有并发安全的计数器与缓存 —— 这一点不需要返工。

### 1.12 与规格的差距矩阵

| 规格条目 | 现状 | Phase |
| --- | --- | --- |
| §十–十二 Retriever 端口 / Strategy / 查询对象 | ❌ 检索逻辑全在一个 Service + 静态 RRF | **3** |
| §十三 Workspace 资源隔离 | 🟡 列已加、查询未切 | **3**（检索侧）→ 8 |
| §十四 逐步迁移，不做完整多租户 | ✅ 与现状一致 | 3–8 |
| §十五–十八 用户可配置 Agent / Definition-Runtime 分离 | ❌ | 4 |
| §十九–二十二 Tool / 权限 / approval_required / Audit | ❌（`approval_required` 事件名已定义未实现） | 5 |
| §二十三–二十六 Workflow 持久化 / 版本 / NodeExecutor / Canvas | ❌ | 6 |
| §二十七–二十九 Application | ❌ | 7 |
| §三十–三十二 Model Provider / Config / Key 加密 | ❌ 配置在 yml | 7（可与 4 并行） |
| §三十三–三十六 HTML / CSV / JSON Parser | ❌（ParserRegistry 已存在，加实现即可） | 3 后段 |
| §三十七–三十九 Evaluation 指标 / Dataset / 跨 Run 比较 | 🟡 2 个指标，不落库 | 9 |
| §四十–四十一 Observability 持久化 | 🟡 内存 3 计数器 | 9 |
| §四十二–四十五 Dockerfile / compose / nginx / CI | ❌ | 工程化 |
| §五十–五十七 七篇深化文档 | ❌ 全部缺失 | 文档 |

### 1.13 §八 清单逐条对照

| §八 要求回答 | 结论 |
| --- | --- |
| 哪些代码已经存在 | §1.3 表（认证/会话/SSE/导入/检索/RAG/Agent/Workflow/Eval/MCP/观测 均已存在且可用） |
| 哪些代码可以直接复用 | **端口**：`VectorStore`、`ChunkIndex`、`Reranker`、`ParserRegistry`；**算法**：`RrfFusion`、`ContextAssembler`、`Citations`、`TokenEstimator`；<br>**基建**：SSE（`SseEmitter` + 取消注册）、统一响应/异常、`WorkflowEngine` 状态语义、`EmbeddingClient`、`ChatClientFactory`、<br>`requireOwned` 归属模型、导入流水线（Parser→Chunk→Embed→Index）、MQ 异步骨架、`ObservabilityRegistry`、Vue 前端骨架与 3 个验证脚本 |
| 哪些代码只是 MVP | `ObservabilityRegistry`（3 个内存计数器）、`EvalService`（2 个指标、不落库）、`WorkflowService`（单条硬编码步骤链）、<br>`McpEndpoint`（4 个方法、本地信任身份）、`LlmReranker`（LLM 打分 + 超时降级）、`AgentPrompts`（静态 prompt）、评测与工作流的**无持久化** |
| 哪些地方需要抽象 | 检索（§三 Retriever）、工具注册（§五 ToolRegistry）、Agent 配置（§五 Definition/Runtime）、工作流节点（NodeExecutor）、模型配置（§5.6）、观测事件（§四十） |
| 哪些地方存在耦合 | §1.11 Top 10（尤其：工具注册两份硬编码、角色→模型解析三份、默认值在代码里二次定义且冲突） |
| 哪些数据库表已经存在 | §1.6（主库 15 张 + 向量库 2 张） |
| 哪些 migration 已执行 | §1.6 表（V1–V10 主库 + 向量 V1） |
| 哪些 API 已存在 | §1.8 表（17 个 Controller / 37 个端点）；**设计稿里其余端点全部未实现** |
| 哪些测试已经覆盖 | §1.10（47 个测试文件，实测 199 run / 0 fail / 18 skip）+ 8 个 `web/scripts/*.mjs` 界面验证脚本 + `scripts/smoke.ps1` |
| 哪些能力不能重复实现 | §1.3 保护区（列了 14 项，§六十五 明文禁止推翻） |

---

## 二、《Phase 3 设计》

### 2.1 目标（一句话）

**把"检索"从"一个 Service 里的 if/else + 常量"变成"一个端口 + 可替换策略 + 有来源的参数"**，
并让 `/api/chat/stream` 的 `mode=RAG` 真的检索、真的推 `retrieval` 事件 —— 且**不改变现有检索结果**。

### 2.2 不变量（不可破坏）

1. `RagAnswerServiceImpl`、`KnowledgeSearchTool`、`KnowledgeSearchController` 的**对外行为不变**：
   同样的查询在同样环境下返回同样的命中顺序。
2. RAG 链路（重排 → 组装 → 引用）与 Agent 降级阶梯都不重写，只改"检索从哪来"。
3. `SearchHit` / `RagCitation` 的字段只增不改（前端与 `ChatMessage` 的 JSON 依赖它）。
4. 现有 47 个测试文件、18 个 live test 一个不删。
5. 检索参数默认值不变（`MIN_VECTOR_SCORE=0.25` 等只是**搬家成默认值**，不是改数值）。

### 2.3 分四个小增量（每个都能单独编译+测试+验证）

| 增量 | 内容 | 出口判据 |
| --- | --- | --- |
| **3.1 端口与查询对象** | 新增 `Retriever` / `RetrievalQuery` / `RetrievedDocument` / `RetrievalStrategy`（放 ai）；<br>现有逻辑包成 `HybridRetriever` 等实现（放 knowledge）；`ChunkSearchServiceImpl` **保留**为兼容门面 | 新单测（策略选择、参数解析）+ 现有 66 个 knowledge 测试全绿 |
| **3.2 参数可配置化** | KB 的 `retrieval_top_k` / `similarity_threshold` 真正生效；解析优先级 = 请求参数 > KB 配置 > 全局默认；<br>修 `LlmReranker`/`WorkflowService` 的 `@Value` 默认值冲突 | 新增"KB 配置生效"回归测试；RAG/Agent 两条链路实测 |
| **3.3 元数据过滤** | `RetrievalQuery.filters`（kbIds / tags / folder / documentIds）真正下发到 ES `filter` 与 pgvector `WHERE`（ES 字段已存在，无需重建索引） | 过滤单测 + ES 真机（若可用）验证 |
| **3.4 SSE `retrieval` 事件 + RAG 模式流式检索** | `mode=RAG` 的 `/api/chat/stream` 走 `prepare()` 并先发 `retrieval`；`RagStreamServiceImpl` 也补发 | 前端可见"检索到 N 个片段"；`verify-stream.mjs` 断言升级 |

### 2.4 明确**不做**（避免范围爆炸）

- 不做 workspace 维度的全量查询切换（§十四 明确"不要一次性强行完成完整多租户"）。
- 不引入新检索后端（GRAPH/SQL）；只在枚举里预留。
- 不做 Reranker 抽象迁移（Spring AI 无 Rerank API，自研已是终态，ADR-007）。
- 不重命名 `ChunkSearchService`（保护区外但要兼容；先并存、后收敛）。
- 不动 `kb_vector` 表结构（隔离靠 `kb_id` 集合，见 §4.4）。

---

## 三、《Retriever 抽象方案》

### 3.1 端口放哪里（依赖方向论证）

规格 §五十 的目标是"RAG 抽象在 ai、具体实现在 knowledge"。可行性已核查：

- 端口的类型必须**自包含**（不含 `ChunkSearchService.SearchHit` 等 knowledge 类型），否则 ai 会被迫依赖 knowledge。
- `nexus-ai` 目前**零处** import `com.lky.nexus.knowledge`（已核对），这个边界是干净的，必须保持。
- 消费者有两个：`knowledge`（`RagAnswerServiceImpl`）与 `app`（未来 Agent 的 RAG 工具）；
  两者都在 ai 的下游，都能看到 ai 的端口。
- 实现放 knowledge，靠 `scanBasePackages="com.lky.nexus"` 自动注册（§1.1），app 无需额外接线。

**结论**：端口与 DTO 放 `nexus-ai`（`com.lky.nexus.ai.rag.retriever`），实现放 `nexus-knowledge`
（`com.lky.nexus.knowledge.rag.retriever`），`app` 只做注入。这样 Phase 4/5 把 Tool/Agent 抽象也搬去 ai 时方向一致。

### 3.2 端口定义（示意，实现时以代码为准）

```java
package com.lky.nexus.ai.rag.retriever;

public interface Retriever {
    /** 该检索器声明的策略；一个实现可声明多个（如 HYBRID 同时支持 BM25/VECTOR 子路）。 */
    Set<RetrievalStrategy> strategies();

    /** 检索。实现必须：先做归属/权限校验；不吞异常；结果按相关性降序。 */
    List<RetrievedDocument> retrieve(RetrievalQuery query);

    /** 自描述名字，用于日志/观测/审计（如 "hybrid-rrf"）。 */
    String name();
}
```

`RetrievedDocument`（替代今天的 `SearchHit` 作为**跨模块契约**）：

```java
public record RetrievedDocument(
        Long chunkId, Long documentId, Long knowledgeBaseId,
        String documentTitle, String fileName, String sourcePath,   // ← 新增 fileName/sourcePath（§十九 要求）
        String headingPath, Integer page,                            // ← page 预留（PDF 页码）
        String content, String snippet,
        double score,
        Set<String> matchedBy,        // 如 ["bm25#1", "vector#3"]（保留今天的可解释性）
        Map<String, Double> scores)   // 各路原始分：bm25 / vector / rrf / rerank
```

> `SearchHit` 不删除：在 knowledge 内保留一个 `RetrievedDocument → SearchHit` 的适配函数，
> 让 `ContextAssembler` / `Citations` / 前端零改动。`page` 先恒为 null（文档里如实标注"未实现"）。

### 3.3 Strategy（§十一）

```java
public enum RetrievalStrategy {
    VECTOR, KEYWORD, HYBRID, HYBRID_RERANK;
    // 预留（不实现，不放进 switch 的必选分支）：GRAPH, SQL, KNOWLEDGE_GRAPH
}
```

- 与现有 `SearchMode(BM25/VECTOR/HYBRID)` 的关系：`SearchMode` **保留**（它是 API 契约，且 `BM25` 这个名字前端在用），
  在 ai 侧提供 `RetrievalStrategy.from(SearchMode)` 的映射（KEYWORD↔BM25），避免两套枚举打架。
- `HYBRID_RERANK` 由"组合检索器"实现（见 3.5），**不**在 `LlmReranker` 里加分支 —— 重排是装饰器，不是策略分支。
- 新增策略的代价：加一个 `Retriever` 实现 bean，不改 `RagAnswerService`、不改 Controller、不改前端。

### 3.4 查询对象（§十二）

```java
public record RetrievalQuery(
        String query,
        Long userId,                    // 归属校验（当前是 user 维度，Phase 8 切 workspace）
        Long workspaceId,               // 预留：仅记录与断言，不参与查询（见 §4.4）
        List<Long> knowledgeBaseIds,    // 空 = 调用方指定的全部（必须非空才检索）
        int topK,
        double similarityThreshold,
        RetrievalFilters filters,       // tags / folder / documentIds / (预留给 status 与时间)
        boolean rerank,
        int recallDepth) {              // 过召回深度，默认 max(topK*3, topK)
}
```

要点：
- **查询条件天然支持 workspace / KB 隔离**（`knowledgeBaseIds` + `userId`/`workspaceId` 同时存在）。
- `RetrievalQuery` 是 record + `withXxx()` 便捷方法（不是 builder），保持不可变与可测。
- 参数解析收敛到**一处**：`RetrievalQueryFactory#from(request params, KnowledgeBasePO, defaults)`
  —— 这就是 §1.11 第 8/9 条（假可配置 + 默认值冲突）的解药。

### 3.5 实现拆分（从 `ChunkSearchServiceImpl` 拆出，行为不变）

| 实现 | 放哪 | 职责 | 来源 |
| --- | --- | --- | --- |
| `VectorRetriever` | knowledge | 嵌入 → `VectorStore.search`（阈值来自查询对象） | `ChunkSearchServiceImpl.vectorSearch` :142-150 |
| `KeywordRetriever` | knowledge | `ChunkIndex.search`（ES BM25） | :94-95 |
| `HybridRetriever` | knowledge | 双路召回 → `RrfFusion.fuse` → 回表 | :96-103,136 |
| `RerankedRetriever` | knowledge | 装饰器：包住任意 `Retriever`，调 `Reranker`，失败原样返回（`reranked=false`） | `RagAnswerServiceImpl` :134,208 |
| `LikeFallbackRetriever` | app | SQL LIKE 降级（**保持在 app**，因为它是 Agent 工具的降级语义，不是知识库的检索语义） | `KnowledgeSearchTool.likeSearch` :122 |
| `RetrieverRegistry` | ai（默认实现）或 knowledge | 按 `RetrievalStrategy` 选实现；找不到 → 明确报错，不静默 fallback | 新 |

`ChunkSearchServiceImpl` 改造后只做三件事：`requireOwned` → 组装 `RetrievalQuery` → 委派 `RetrieverRegistry`。
它的公开签名**保持不变**，因此 5 个现存调用点（§1.4 表）零改动 —— 这是"不破坏保护区"的具体做法。

### 3.6 参数优先级（单一入口）

```
请求参数（topK / threshold / mode / tag / folder）
  > 知识库配置（kb_knowledge_base.retrieval_top_k / similarity_threshold / status）
  > 全局默认（application.yml: nexus.retrieval.*，与今天的常量同值）
```

配套约束：
- `status=DISABLED` 的知识库**不参与检索**（V10 早已埋字段，Phase 3 起生效；与 §1.4 "status 从不被查询" 对应）。
- 统一 `@Value` 默认值：`LlmReranker` 的 `12000` 与 yml 的 `5000` 必须收敛为一个来源（§1.11 第 9 条）。
- 越界值一律 clamp（沿用现有 `Math.clamp` 风格）而不是抛错，避免把"参数离谱"变成"功能不可用"。

### 3.7 降级阶梯归属（一个有意的取舍）

`ChunkSearchService` 保持"不降级"（decisions.md B.29 第 3 点），LIKE 降级留在 Agent 工具层。
Phase 3 只做一件事：**给降级补上可计数**（`ObservabilityRegistry` 增加键，§九 的观测持久化再做落库）。
理由：平台要求"记录 fallback"（§四十），而现在的 `likeSingle` 失败是完全静默的（§1.11 第 10 条）。

### 3.8 与元数据过滤/隔离的关系（省掉两次重建索引）

- **ES**：mapping 已有 `knowledge_base_id` / `user_id` / `tags`(keyword) / `folder`(keyword)
  （`ElasticsearchChunkIndex.java:231-243`）。加过滤 = 在 `bool.filter` 里多加 `terms(ki)` / `term(tags)` / `term(folder)`
  （今天已有单个 `knowledge_base_id` 的 `term`，:162-166）。**无需重建索引。**
- **pgvector**：`kb_vector` 有 `kb_id` / `document_id`（vector V1:18-38）。多 KB = `kb_id = ANY(?)`。
  加 `workspace_id` 列到向量表**不做**（见 §4.4 理由）。
- 因此"检索必须支持 workspace / KB 隔离"（§十二）落地为：**先把 workspace 解析成 KB id 集合**，
  再作为过滤条件下发 —— 这是纯查询层改造，DML 与索引层零变更。

---

## 四、《Workspace 资源隔离迁移方案》

### 4.1 现状（精确）

- `workspace` 表存在（V9），个人工作区 1:1 用户，`WorkspaceService.requirePersonalWorkspace` 幂等。
- 只有 `kb_knowledge_base.workspace_id` 一列（V9:26，允许 NULL）。**所有查询仍走 `user_id`**
  （`requireOwned` 的 SQL 是 `id AND user_id`）。
- `workspace_id` 的写入点只有两处：`KnowledgeBaseServiceImpl.java:95`、`AuthServiceImpl.java:107`；
  读取只在响应回显（`:242`）。**没有任何 WHERE 用到它。**
- `WorkspacePO:37` 的 `deleted` 缺 `@TableLogic`。
- 没有 `workspace_member` 表、没有任何成员/角色（OWNER/ADMIN/EDITOR/VIEWER 只是保留字）。

### 4.2 迁移原则（§十四 + 只增不改）

1. **一个资源一次迁移**，顺序：KnowledgeBase → Document/Chunk（随 KB，不加列）→ Agent → Tool → Workflow → Application → Model。
2. **不删 `user_id`**。`user_id` 永远是"创建者/负责人"，`workspace_id` 是"归属容器"。两者并存，查询维度逐步加宽。
3. **没有成员模型之前，不写权限逻辑**：个人工作区与用户 1:1，`workspace_id` 查询与 `user_id` 查询语义等价，
   此时切维度只增加复杂度、不增加安全性。因此 Phase 3 只做"**检索侧隔离**"，全量切换留到 Phase 8（与 phase1-analysis §13 的顺序一致）。
4. 每个资源的切换都必须是"**新查询方法与旧查询并存 → 双跑对比 → 删除旧路径**"，不允许一次性替换。

### 4.3 分阶段表

| 阶段 | 资源 | 库变更 | 查询维度 | 安全测试 |
| --- | --- | --- | --- | --- |
| **3** | KnowledgeBase（检索侧） | 无（列已有） | `workspaceId → kbIds` 后过滤；`status=DISABLED` 不参与检索 | 用户 A 不能检索用户 B 的 KB；DISABLED 的库检索不到 |
| 4 | agent / agent_kb / agent_tool | 建表时**直接带** `workspace_id` + `owner_id` | 创建/列表/详情/更新/删除全带 workspace | Agent A 不能绑定未授权的 KB |
| 5 | tool | 建表带列 | 同上 + 权限（§五） | 普通成员不能调用管理员 tool |
| 6 | workflow / workflow_version | 建表带列 | 同上（按定义维度） | A 不能执行 B 的 workflow |
| 7 | application / model_config | 建表带列 | 同上 | A 不能使用 B 的模型配置 |
| 8 | 全量切换 + `workspace_member` | 新表 + 回填 | `workspaceId + resourceId + permission` 三元校验 | 跨 workspace 读写全部拒绝 |

> **执行进度（2026-10-01）**：第 4 行（agent）与第 5 行的**建表部分**（tool）已完成——
> 4.1 建 `agent` / `agent_knowledge_base`，4.3 建 `tool` / `agent_tool`，两张表都带了
> `workspace_id` + `owner_id`，查询走 `owner_id`（与 agent 一致，见 `docs/database.md` §6.0.1）。
> **第 5 行仍有一半没做**：`permission` 与"普通成员不能调用管理员 tool"这条安全测试属 §五/§二十 的权限一档，
> 仍是 P1——今天没有成员模型，也没有 WRITE / DANGEROUS 工具，这条测试**不适用**（不是"通过"）。
> 第 6–8 行未动。

### 4.4 检索侧的隔离做法（Phase 3 具体做法）

```
1. Controller/Service 侧：CurrentUser.requireId() → workspaceId = WorkspaceService.requirePersonalWorkspace(userId)
2. 解析可检索范围：KnowledgeBaseService.listByWorkspace(workspaceId, status=ENABLED) → kbIds
3. RetrievalQuery(userId, workspaceId, knowledgeBaseIds=kbIds, filters, ...)
4. Retriever 内部：ES 加 terms(knowledge_base_id)/tags/folder 过滤；pgvector 加 kb_id = ANY(?)
5. 断言（防御性）：返回的每条 document 的 kbId 必须在 kbIds 内，否则丢弃并计数（CrossTenantLeak counter）
```

第 5 步是**纵深防御**：即使将来某个 Retriever 实现的过滤写漏，也能在出口拦住并留下可观测信号。
**不给 `kb_vector` / ES 加 `workspace_id`**：那需要重建索引（ES）与向量表 DDL（pgvector），
而收益为零 —— `kb_id` 已经足够表达归属，workspace→kbIds 的解析成本在内存里可以忽略。

### 4.5 明确不做的部分与理由

- ❌ 不做 `workspace_member` / 角色（§十四："当前只完成 Workspace 即可"）。
- ❌ 不把 `workspace_id` 加进 chat_conversation（会话归属 Phase 7 与 Application 一起做）。
- ❌ 不做"一个用户多工作区"的 UI/API（个人工作区 1:1 已足够支撑 §十三 的资源归属原则）。
- ✅ 修 `WorkspacePO` 的 `@TableLogic`（1 行，属缺陷修复）。

### 4.6 安全验收清单（§六十一 映射到 Phase 3 能测的部分）

| 项 | Phase 3 是否可测 | 方式 |
| --- | --- | --- |
| 用户隔离（User A 不能访问 User B） | ✅ | 现有 `requireOwned` 已有测试；补检索侧用例 |
| Workspace 隔离（不能读别的 workspace） | 🟡 个人工作区 1:1，只能测"kbIds 越界被拦" | 伪造跨 workspace 的 kbId 直接调 Retriever，断言被拦 + 计数 |
| KnowledgeBase 隔离（Agent 不检索未授权 KB） | ✅ | Agent 工具固定用当前用户 KB 列表（`KnowledgeSearchTool.java:74-75`），补跨用户用例 |
| Tool 权限 / Approval / Audit / API Key / Prompt Injection | ❌ 属 Phase 5/7 | 见 §五 |

---

## 五、《Agent / Tool / Model 配置化方案》

> 本节是**方案**，实施在 Phase 4/5/7。原则来自 §十五–§二十二、§三十–§三十二。

### 5.1 Agent：Definition 与 Runtime 分离（§十七）

```
agent(表) → AgentDefinition(不可变配置对象) → AgentRuntime(每次运行新建) → AgentLoop(现有机制不动) → Tools/RAG/Memory/LLM
```

- **`AgentLoop` 不重写**：今天的机制是 Spring AI `ToolCallingManager` + `ToolContext`（`AgentService.java:42-47`），
  已修复跨线程用户传递（B.30），保留。
- `AgentDefinition`：由数据库行映射而来的**纯值对象**（`systemPrompt` / `modelRole` / `maxIterations` / `timeoutMs` /
  `kbIds` / `toolCodes` / `memoryConfig`），**不是 MyBatis 实体**。Entity 绝不进 Loop（§十七 明文禁止）。
- 今天写死的三样东西分别变成字段：`AgentPrompts.AGENT_SYSTEM` → `system_prompt`；
  `AiModelProperties.Role.CHAT_AGENT` → `model_role`；`ToolCallbacks.from(knowledgeSearchTool)` → `toolCodes` 白名单。
- 状态机：`DRAFT / PUBLISHED / DISABLED`（§十八），`PUBLISHED` 才可被 Application 引用。

> **落地情况（4.1，见 §9.4）**：实际建的 `AgentDefinition` 只有
> `agentId / code / name / systemPrompt / modelRole / kbIds / builtin` 七个字段。
> `maxIterations / timeoutMs / memoryConfig / toolCodes` **没有建**——今天没有任何机制会读它们
> （轮数上限在框架的 `ToolCallingManager`，超时在 HTTP 层，工具白名单只有内置一个）。
> 这条偏差的理由写在 decisions.md B.35 §7：**存下来但没人读的配置比没有这个配置更糟**。
> → **2026-10-01 更新**：`toolCodes` 已随 4.3 加上（字段与读它的装配机制同一次落地，
> 见 §5.3 落地情况与 §9.7）；`maxIterations / timeoutMs / memoryConfig` **仍然没有建**。
> 另：内置默认 Agent 是数据库里的真实一行（不是代码兜底），`kbIds` 为空 = 不限制。

### 5.2 表（§十六，建表时直接带 workspace/owner，§4.3 顺序）

```sql
agent(id, workspace_id, owner_id, code, name, description, system_prompt, model_role,
      max_iterations, timeout_ms, memory_config JSON, status, created_at, updated_at, deleted)
agent_knowledge_base(id, agent_id, kb_id, created_at)          -- uk(agent_id, kb_id)
agent_tool(id, agent_id, tool_code, enabled, approval_required, created_at)  -- uk(agent_id, tool_code)
agent_run(id, agent_id, conversation_id, user_id, status, input, output,
          iterations, started_at, finished_at, error_msg)       -- §二十二 审计的运行维度
agent_run_step(id, run_id, seq, type, tool_code, input, output, status, latency_ms, created_at)
```

> **落地情况（4.1，V12）**：只建了 `agent`（去掉 `max_iterations` / `timeout_ms` / `memory_config`
> 三列）与 `agent_knowledge_base`，外加 `chat_conversation.agent_id`。
> `agent_tool` / `agent_run` / `agent_run_step` 三表**未建**——它们分别要等 Tool 体系与
> 可观测性阶段。实际列与理由见 `docs/database.md` §6.0。
> → **2026-10-01 更新**：`agent_tool` 已随 4.3（V13）建为 `agent_tool(id, agent_id, tool_code, created_at)`，
> 与上表**不同**：**没有** `enabled`、按 `tool_code` 引用而非 `tool_id`（理由见 B.38 §3、V13 文件头）；
> `agent_run` / `agent_run_step` **仍未建**（属 §二十二 审计增量）。

### 5.3 Tool：Definition → Registry → Factory → Executor（§十九）

> **4.3 设计（落地前写下；**2026-10-01 已落地并在真机栈复核** → §9.7 完成记录、下面的"落地情况"、
> decisions.md B.38（实现）、B.39 与 B.40（真机验证 + 两个回归））**
>
> **这次的假旋钮是哪个**：本节原设计表里有 `permission` / `approval_required` /
> `timeout_ms` / `rate_limit` 四列。今天的代码里**没有任何一处会读它们**——审批要等 §5.4 的挂起/恢复机制，
> 超时与限流要等执行壳真的会拦截。按 V12 的同一条理由（B.35 §7：存下来但没人读的配置比没有更糟），
> **4.3 不建这四列**，只建"今天就真的会拦截"的那一层：`enabled`（工具级）+ `agent_tool.enabled`
> （Agent 级）。四个字段随 P1 的"Tool Permission / Approval"增量落地，**加列与接上拦截点必须在同一次改动里**。
>
> **4.3 落地范围**（后端，与 4.1 只做后端、4.2 再上前端同一节奏）：
>
> 1. `tool` + `agent_tool` 两张表（V13，只增不改）；
> 2. `ToolDefinition`（值对象，`nexus-ai`）+ `ToolCatalog`（代码侧的内置实现清单）；
> 3. `ToolRegistry`：**"有哪些工具、这个用户能不能用"的唯一答案**；内置工具按需为每个用户补行
>    （与 `AgentService.resolveDefault` 补内置 Agent 同一种做法——内置项是真实数据行，不是代码兜底）；
> 4. `AgentToolFactory`：给定 `userId + AgentDefinition` → `ToolCallback[]`，取代
>    `AgentRuntime.configure` 里那句 `ToolCallbacks.from(knowledgeSearchTool)` 与
>    `ChatStreamServiceImpl` 里的同一份写死；
> 5. `ToolExecutionShell`（执行壳）：统一把工具异常变成**明确的观察结果**回灌模型 + 观测计数 + 痕迹；
>    P1 的权限/审批/超时/限流都插在这一层，不改工具实现；
> 6. `McpEndpoint.tools/list` 的 `knowledge_search` 描述与 schema 改为从注册表取（§1.11 第 2 条）；
> 7. `GET /api/tools`（该用户的工具目录）+ `PUT /api/tools/{code}`（启停）+ Agent 侧绑定 `toolCodes`。
>
> **4.3 的内置工具是两件**：`knowledge_search`（**必须继续可用**——新路径下逐条回归老行为）
> 与 `calculator`（选它是因为确定性、无网络：它证明"多工具装配"这条缝是真的，又不引入新的失败模式）。
>
> **有意后置、附理由**（不是漏掉）：
> - `rag_answer`：RAG 模式已有一整套（会话绑库、引用、`retrieval` 事件），把它暴露成工具要处理
>   "工具的引用怎么进消息"这一层，值得单独一个增量；
> - `http_request` / `web_search`：**先做等于把它们做成不设防的**（SSRF、出站白名单、超时、限流、
>   以及"调用外部服务要先问用户"）。它们与 §二十/§二十一 的权限与审批是同一件事的两面，
>   应在 P1 一起落地；
> - `agent_tool.approval_required` 与 §5.4 的挂起/恢复：P1。
>
> **默认可见性（一条要拍板的语义）**：`agent_tool` 无行 = **不限制**（装该用户全部启用中的内置工具），
> 与 `kbIds` 空 = 不限制保持一致；有行 = 白名单。因此 4.3 之后，没绑过工具的既有 Agent 会多出
> `calculator`——这是产品意图（工具平台化后新工具默认可用），但**必须实测"检索路径没有因此改变"**。
> P1 有了 `permission` 之后收紧为"默认只装 READ，WRITE / DANGEROUS 必须显式绑定"。

> **落地情况（4.3，V13，见 §9.7 与 decisions.md B.38）**：本节上面的链
> （Definition → Registry → Factory → Executor）**四段都建了**，对应的类是
> `ToolDefinition`（+`ToolSource`/`ToolCategory`）→ `ToolCatalog` + `ToolRegistry`
> → `AgentToolFactory` → `ExecutingToolCallback`。
>
> 与设计稿的三处**有意偏差**（理由见 B.38 §2–§4）：
> 1. **`tool` 表的行是"用户覆盖"，不是"内置项的数据行"**——设计稿写的是"按需为每个用户补一行"，
>    本轮反过来：**没有行 = 用代码侧默认（启用）**。补行方案下，新用户 / 清库 / 换环境之后工具会
>    凭空消失（症状是"Agent 突然不检索"，静默降级）；反过来之后，工具的存在性由代码保证，
>    库里只记用户改过的部分。**代价：`tool` 表与 `agent` 表不能照抄同一套模式。**
> 2. **`json_schema` / `name` / `description` / `category` / `source` 五个描述性列一个都没建**——
>    与 V12 不建 `max_iterations` 同一条规则：唯一真实来源是 `@Tool` 方法，读路径也只能用代码那份；
>    库里那份是永远不会被读的死数据。**MCP 的 `tools/list` 因此改为从 `ToolCatalog` 生成**
>    （这才是本节那条"硬性要求"的落点）。
> 3. **`permission` / `approval_required` / `timeout_ms` / `rate_limit` 仍不建**：今天没有一处会读它们，
>    加列与接上拦截点必须同一次做（P1）。
>    **注意有一处比上面的设计更紧**：设计说"只建 `enabled`（工具级）+ `agent_tool.enabled`（Agent 级）"，
>    实际**只建了工具级的 `tool.enabled`**，`agent_tool.enabled` 没有建——白名单的"在不在里面"
>    已经表达了用不用这个工具；一个恒为 1、没有任何写入点的标志位，只会让后来的人以为它能关。
>
> `toolCodes` 的语义**刻意与 `kbIds` 对齐**（空 = 不限制），并已进 `AgentDefinition`——
> 字段与读它的机制（装配时取交集）在同一次改动里落地，正是 §5.1 那条规则的用法。


```
tool(表: tool_code, workspace_id, owner_id, source[BUILTIN/MCP], json_schema, enabled,
      permission[READ/WRITE/DANGEROUS], approval_required, timeout_ms, rate_limit, created_at)
   ↓
ToolRegistry（单一注册表，取代今天的两份硬编码：AgentService/ChatStreamServiceImpl 与 McpEndpoint）
   ↓
ToolFactory（按 tool_code 造出 Spring AI 可调用的 ToolCallback）
   ↓
ToolExecutor（统一执行壳：参数校验 → 权限检查 → 超时 → 限流 → 审计 → 结果裁剪）
```

- **第一阶段工具**：`knowledge_search`（**必须继续可用**）、`rag_answer`、`calculator`、`http_request`、`web_search`（可后置）。
- 硬性要求：`McpEndpoint` 的 `tools/list` 改为**从 ToolRegistry 生成**，消灭第二份硬编码（§1.11 第 2 条）。
- 权限（§二十）：`tool.permission` + `agent_tool.approval_required`，取值 `AUTO / CONFIRM / DENY`。
- 禁用工具的行为：`DENY` 返回明确拒绝作为观察结果回灌模型（让它换方案，而不是整轮失败）。

### 5.4 `approval_required` 真实现（§二十一，不许只写在文档里）

```
Agent 准备调用 tool
  → ToolExecutor 检查 agent_tool.approval_required
  → true: 落 agent_approval 行(status=PENDING) → SSE 发 approval_required{approvalId, tool, arguments, description}
  → Agent 挂起（run 状态 WAITING_APPROVAL），不占用线程等待
  → 用户 POST /api/agent/runs/{runId}/approval {approved:true|false}
  → 批准: 继续执行该 tool 调用；拒绝: 把"用户拒绝"作为观察结果回灌，Agent 继续或收尾
  → 审计：谁、何时、批/拒、理由
```

关键设计取舍：**批准后重放的是"这一次工具调用"，不是"整个 Agent 重新跑"** —— 否则用户批准的内容与实际执行的内容可能不一致（这是审批语义被架空的最常见方式）。SSE 事件名 `approval_required` 已存在（`SseEventNames.java:37`），字段形状按 §二十一 的示例。

### 5.5 Audit（§二十二）

统一 `agent_run` / `agent_run_step` / `agent_approval` + `tool_invocation` 落"谁、何时、哪个 workspace、
哪个 Agent、哪个 Tool、输入、输出、是否经人工审批、最终状态"。审计是**写路径的强制副作用**（不是可选日志），
与 §1.11 第 10 条（降级不可计数）一起解决"平台可解释性"。

### 5.6 Model Provider / Config + API Key 加密（§三十–§三十二）

现状：配置在 yml，Key 明文在内存，全仓无加密（§1.11 第 6 条）。目标：

```sql
model_provider(id, workspace_id, owner_id, name, type[OPENAI_COMPATIBLE/OLLAMA/DASHSCOPE/DEEPSEEK],
               base_url, api_key_cipher, api_key_hint, headers JSON, enabled, created_at, updated_at)
model_config(id, provider_id, role, model_name, context_window, dimensions, capabilities JSON,
             temperature, max_tokens, enabled)
```

Key 处理**四条硬要求**（§三十一）：
1. **落库加密**：`api_key_cipher` 用 AES-GCM，主密钥来自环境变量（缺失则拒绝启动，与 JWT secret 同策略）；
2. **日志永不打印**（沿用今天的做法：`AiModelRegistry` 只记 provider/model 名）；
3. **异常不泄露**（`GlobalExceptionHandler` 已只返回 requestId，:84-92）；
4. **API 只回脱敏串**：`sk-****abcd`，且**永不回原文**（`model_provider` 的响应 DTO 里根本没有 key 字段）。

`AiModelRegistry` 改造为"从 `model_config` 读取 + 缓存 + 变更失效"，`ChatClientFactory.get(role)` 签名不变
→ 业务层零改动（§三十："业务层只碰 ChatService/EmbeddingService"）。
兼容性：yml 里的 `ai.providers/ai.roles` 作为**引导默认值**保留（首次启动时导入为 workspace 0 的系统级 provider），
避免"配置化"变成"必须先配库才能启动"。

---

## 六、《数据库变更方案》

### 6.1 机制与纪律

- **唯一入口**：`nexus-app/src/main/resources/db/migration/V<n>__*.sql`（MySQL 方言）。
  H2（test/standalone）会跑同一份迁移（§1.7），因此**只写一次**。
- `db/h2/schema.sql` 视为**遗留副本**：新表不再同时维护；若将来确认无用，单独提一个清理项（不在 Phase 3 里删）。
- **只增不改**（§四十九）：`ALTER TABLE ... ADD COLUMN`（带默认值或允许 NULL）、`CREATE TABLE`、`CREATE INDEX`。
  不改名、不删列、不改类型、不加 NOT NULL 无默认值。
- 每个 `ADD COLUMN` **独立一条 `ALTER TABLE`** —— H2 不接受逗号链式多列（V10:11-13 已记录该坑）。
- 列位置不用 `AFTER`（H2 兼容性风险）；物理列序不重要。
- 向量库迁移（`db/vector-migration/`）**Phase 3–8 预期无变更**（§3.8、§4.4）。

### 6.2 Phase 3 的迁移（预期 V11）

Phase 3 大体**不需要新表**。可能的一条（等 3.2 落地时确认是否必要）：

```sql
-- V11__knowledge_base_retrieval_defaults.sql（仅当需要"库级检索模式"时）
ALTER TABLE kb_knowledge_base ADD COLUMN retrieval_mode VARCHAR(16) NOT NULL DEFAULT 'HYBRID';
```
> 若 3.2 只需要"读取已有的 retrieval_top_k / similarity_threshold"，则 **V11 不存在** —— 我倾向如此，
> 因为 §十四 要求"不要一次性强行完成"，能不加列就不加列。

### 6.3 后续阶段迁移规划（先列清单，实施时逐个落地）

| 迁移 | 阶段 | 内容 |
| --- | --- | --- |
| V12 | 4 | ✅ 已落地（4.1）：`agent` / `agent_knowledge_base` / `chat_conversation.agent_id`；`agent_tool` 延后到 Tool 体系落地时（B.35 §7） |
| V13 | 5 | `tool` / `tool_invocation` / `agent_approval` |
| V14 | 6 | `workflow` / `workflow_version` / `workflow_execution` / `node_execution` |
| V15 | 7 | `application` / `model_provider` / `model_config` + `chat_conversation.application_id` |
| V16 | 9 | `ai_execution` / `obs_event`（或 `obs_execution`）/ eval 三表 |
| V17 | 8 | `workspace_member` + 资源表回填 |

### 6.4 索引原则

- 新表一律带 `(workspace_id, deleted, created_at)` 或 `(workspace_id, status, deleted)` 组合索引 —— 与 V9 的
  `idx_kb_workspace`（`V9__workspace.sql:29`）保持一致风格。
- 高频审计表（`agent_run_step` / `tool_invocation` / `ai_execution`）必须带 `(created_at)` 与
  `(workspace_id, created_at)`，因为 §四十一 的查询都是"某时间窗内按维度聚合"。
- 不为"看起来规范"加索引（§四十一 的"不要为了表数量而建表"同理适用于索引）。

### 6.5 回滚与兼容

- 纯 `ADD COLUMN` 的迁移**向后兼容**：旧代码忽略新列即可运行（这是"只增不改"的直接收益）。
- `CREATE TABLE` 同理。因此**不需要 down migration**，回滚 = 部署上一版 jar。
- 唯一风险是"新代码用了新列、旧代码在读同一行" —— 由于新列都有默认值或允许 NULL，无破坏。

---

## 七、《API 变更方案》

### 7.1 新增（Phase 3）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/knowledge-bases/{id}/search` | **已存在**，增量：支持 `tags` / `folder` / `documentId` 过滤参数（`docs/api.md` §4 早已承诺，今天未实现） |
| GET | `/api/knowledge-bases/{id}/search/explain` | 新增：返回两路原始结果 + 融合排名 + 重排分数（`docs/api.md` §4 承诺的调试接口） |

### 7.2 变更（Phase 3 的核心）

- `/api/chat/stream`（`mode=RAG`）：事件序列从 `meta → delta…` 变为
  **`meta → retrieval → delta… → usage → done`**，并补齐 `citation`（生成后）。
  `retrieval` 载荷：`{"count":6,"documents":[{"documentId":100,"name":"缓存击穿.md","headingPath":"..."}]}`（沿用 `docs/api.md` §5.2 的形状）。
- `/api/knowledge-bases/{id}/ask/stream`：同样补 `retrieval` / `citation`（前端 `knowledge.ts:365-370` 需要新增分支）。
- **兼容性**：新增事件是**纯增量**。前端按事件名 switch，未知事件忽略（`chat.ts:117-141` 已有 default 分支，
  `knowledge.ts:365-370` 只有 3 个 case，加 case 即可）。**不改变任何现有事件的名称与载荷。**
- `/api/knowledge-bases/{id}/search` 的响应用 `RetrievedDocument` 承载时，**字段只增**（补
  `fileName` / `sourcePath` / `scores`），`chunkId/documentId/documentTitle/headingPath/snippet/score` 保持原名不动。

### 7.3 与 `docs/api.md` 设计稿的差异（文档要如实对齐）

`docs/api.md` 里有相当多**设计稿而非实现**的内容，Phase 3 起必须逐条标注状态，避免"文档声称完成但代码没实现"（§六十二）：
`/api/search`（统一检索，今天只有 KB 内 `/search`）、`/api/search/explain`、`reindex`、`feedback`、
`regenerate`、`/api/prompts/**`、`/api/mcp/servers/**`、`/api/tools/**`、`/api/agents/**`、`/api/workflows/**`、
`/api/eval/datasets/**`、`/api/observability/overview|trend|models|requests` —— **全部未实现**。

---

## 八、《测试方案》

### 8.1 分层与门禁（§四十七）

| 层 | 判据 | 执行策略 |
| --- | --- | --- |
| Unit | 纯函数 / 无 Spring 上下文（`RrfFusionTest`、`WorkflowEngineTest`、`AgentContextTest`） | **必跑** |
| Contract | `@SpringBootTest` + `webmvc-test` 打 HTTP 契约（`WebContractTest`、`ChatStreamContractTest`、`AuthHttpSmokeTest`） | **必跑**（H2） |
| Integration | 需要 MySQL/ES/pgvector/RabbitMQ 的真机 | **环境存在才跑**；不在默认 `mvn test` 里 |
| Live | 打真实模型/嵌入 API（7 个 `@EnabledIfEnvironmentVariable` 类，18 方法） | **默认 skip**，给出环境变量才跑 |
| E2E | Playwright 驱动真实浏览器（`web/scripts/verify-*.mjs`） | 手动触发，需后端 + 演示数据 |

纪律：**SKIPPED 绝不许写成 PASSED**（§四十七/§四十八）。测试报告必须四态分明：
`PASSED / FAILED / SKIPPED / UNAVAILABLE`。

### 8.2 Phase 3 新增测试清单

| 增量 | 新增测试 | 层次 |
| --- | --- | --- |
| 3.1 端口 | `RetrieverRegistryTest`（策略选择、未知策略明确报错）、`RetrieverContractTest`（各实现共同的契约：按分数降序、不返回越界 KB） | Unit |
| 3.1 拆分 | **回归护栏**：`ChunkSearchServiceParityTest` —— 同一 query 下"旧实现 vs 新实现"结果差异必须为空（这是"行为不变"的机器证明） | Contract |
| 3.2 参数 | `RetrievalQueryFactoryTest`（优先级：请求 > KB > 默认；clamp 越界） | Unit |
| 3.2 参数 | `KnowledgeBaseRetrievalConfigTest`（改 KB 的 topK/threshold 后检索参数真的变了） | Contract |
| 3.2 参数 | `DisabledKnowledgeBaseTest`（DISABLED 的库检索不到） | Contract |
| 3.3 过滤 | `RetrievalFilterTest`（tags / folder / documentIds 生效；空过滤器 = 不过滤） | Contract |
| 3.4 事件 | `RagStreamRetrievalEventTest`（`meta` 后必到 `retrieval`，且早于第一个 `delta`） | Contract |
| 3.4 事件 | 升级 `web/scripts/verify-stream.mjs`：断言页面上出现"检索到 N 个片段" | E2E |

### 8.3 回归保护（§六十五：保护区不许破坏）

- **必跑**：全部 47 个测试文件，尤其 `RrfFusionTest` / `ContextAssemblerTest` / `ChunkEmbeddingServiceTest`
  （knowledge 的 66 个）、`ChatStreamContractTest` / `ChatAgentModeTest` / `AgentContextTest` / `AgentLoopTest`。
- **必跑 live（在有 Key 时）**：`ChatStreamLiveTest`（流式端到端）、`AgentLoopTest`（工具循环守卫）。
- 3.1 的 `ChunkSearchServiceParityTest` 是本次重构**唯一的"行为不变"证据**，必须有；没有它就不算拆分完成。

### 8.4 演示数据与 E2E（§四十九）

`scripts/seed-demo.mjs` 要逐步扩展到 §四十九 要求的全资源：demo user → workspace → KB → documents →
**agent → tool → workflow → application** → conversation，做到"一条命令恢复演示环境"。
今天它只覆盖到 KB + documents（+ `web/.demo-token`）。

### 8.5 CI（§四十五–§四十八）

- `.github/workflows/build.yml`：Checkout → JDK 21 → Maven cache → `mvn -B test` → `mvn -B package`；
  前端：Node → `npm ci` → `npm run build`。
- **不得假设 Docker 可用**（§四十六）：CI 只跑 Unit + Contract（H2），Integration/Live 靠环境变量门禁自动跳过。
- 前端 `vue-tsc --noEmit` 已在 `npm run build` 里（`web/package.json:6-10`），CI 顺带获得类型检查。

### 8.6 本轮实测基线（`mvn -B test`，2026-09-30）

```
prisdvl-nexus / nexus-common / nexus-ai / nexus-knowledge / nexus-app  全部 SUCCESS
合计：Tests run: 199, Failures: 0, Errors: 0, Skipped: 18

PASSED   : 181
SKIPPED  : 18（= 7 个 live 类，环境变量未设置）
FAILED   : 0
UNAVAILABLE：MySQL / ES / pgvector / RabbitMQ 未验证（本机 Docker WSL2 后端不可用，
             故 knowledge 的检索类测试与 LiveVectorStoreTest / LiveChunkIndexTest 均未真机执行）
```

**口径声明（§四十八）**：上表是 `standalone + H2 + fallback` 环境的验证结果。
**不得据此声称"完整生产链路已真实验证"** —— ES 检索、pgvector 向量检索、RabbitMQ 异步导入在本轮
处于 `UNAVAILABLE`，与 `Validated` 严格区分。

> 上表是**规划当轮**的快照（199 运行）。每个增量落地后的当前数字在各增量的完成记录里
> （§9.1 起逐级递增，最新为 §9.5 的 **335 运行 / 317 通过 / 18 跳过 / 0 失败 / 0 错误**；
> 真机栈的补验结果见 **§9.6**）。
> 本表保留原样不改，避免"历史基线被后来的数字覆盖"这种文档造假。

---

## 九、执行顺序与首个增量

按 §六十六 的 P0 → P1 → P2 → P3，但**每步只做一个增量**（§七/§六十三）：

```
P0 ─┬─ 3.1 Retriever 端口与拆分（含 parity 回归测试）
    ├─ 3.2 检索参数真正可配置（KB 的 topK/threshold 生效 + 统一默认值来源）
    ├─ 3.3 元数据过滤（tags/folder/documentId）
    ├─ 3.4 SSE retrieval + RAG 模式流式检索
    ├─ 3.5 检索侧 Workspace 隔离（workspaceId → kbIds + 出口断言 + 计数）
    ├─ 3.6 HTML / CSV / JSON Parser（ParserRegistry 加三个实现）
    ├─ 4.1 Agent Definition（后端：表 + Definition/Runtime 分离 + CRUD/Preview）✅
    ├─ 4.2 Agent 管理页 + 会话选 Agent（前端 + E2E）✅ ← 当前进度
    ├─ 5.x Tool Registry + 权限 + approval_required + Audit
    └─ 7.x Model Provider/Config + Key 加密
P1  ── Workflow 持久化/版本、Application
P2  ── Evaluation 指标、跨 Run 比较、观测持久化、Dockerfile/compose/nginx/CI
P3  ── MCP 完整接入/鉴权、Planner、高级 Memory
文档 ── 七篇深化文档 + README 重定位（§五十–§五十八）
验收 ── §六十一 安全验收 + §六十八 最终输出 30 项
```

**第一个增量建议**：`3.1 Retriever 端口与拆分` + `ChunkSearchServiceParityTest`。
理由：它是 3.2/3.3/3.4/3.5 的共同前置（参数、过滤、隔离、事件都要挂在新的查询对象上）；
且它有明确的机器判据（新旧实现结果一致），能在不改行为的前提下先把架构缝出来。

### 9.1 增量 3.1 完成记录（2026-09-30）

| 项 | 结果 |
| --- | --- |
| 状态 | ✅ 已完成（编译 / 测试 / 回归 全绿） |
| 新增主代码 | `nexus-ai/.../ai/rag/retriever/`：`Retriever`、`RetrievalQuery`、`RetrievalFilters`、`RetrievalStrategy`、`RetrievedDocument`、`RetrieverRegistry`（6 个） |
| 新增主代码 | `nexus-knowledge/.../knowledge/rag/retriever/`：`VectorRetriever`、`KeywordRetriever`、`HybridRetriever`、`RerankedRetriever`、`ChunkHydrator`、`RecallSupport`（6 个） |
| 改写 | `ChunkSearchServiceImpl` 从"什么都做"退化为门面（检索逻辑全部迁出，**公开签名与 `SearchHit` 结构一字未改**） |
| 数据库 | 无（本增量不动表） |
| API | 无（对外契约不变，五个调用点零改动） |
| 新增测试 | 33 条（ai +15：`RetrievalQueryTest` 9 / `RetrieverRegistryTest` 6；knowledge +18：`ChunkSearchServiceParityTest` 11 / `RerankedRetrieverTest` 7） |
| 测试基线 | 199 → **232 通过 / 0 失败 / 0 错误 / 18 跳过** |

**与 §三 设计稿的三处偏差（如实记录）**：

1. `RetrievedDocument` **去掉了 `snippet` 字段**。设计稿里有，但本轮没有任何代码能产出它，
   留着就是"永远为 null 的字段"——不如等真正需要摘要（流式检索事件展示）时再加。
2. `RetrievalQuery` **多了 `rerankQuery`**。追问场景下召回用"上一轮问题+当前问题"消解指代，
   而重排打分应以用户原问题为准，两者不是同一个字符串；不留这个字段，3.4/3.2 迁移时就得改端口签名。
3. **`RerankedRetriever` 本轮已实现并注册**（`HYBRID_RERANK` 可被派发），但 `RagAnswerServiceImpl`
   仍走原来的内联重排路径。切换属独立的增量，理由见 B.31 第 7 条。

**本轮新增的两条"已确认"**（原 §十 的推断项，尚未复核，故不移动）：
`RetrieverRegistry` 在单测与 `standalone` 启动日志中均已确认注册四种策略；
`standalone` 下三种检索模式分别返回 4005/4005/4004（ES 与 Ollama 不可用），**与改造前一致**。

### 9.2 增量 3.2 完成记录（2026-09-30）

| 项 | 结果 |
| --- | --- |
| 状态 | ✅ 已完成（编译 / 测试 / 回归 全绿） |
| 目标 | 知识库的 `retrieval_top_k` / `similarity_threshold` 真正生效；全局默认值收敛到一处 |
| 新增主代码 | `nexus-ai/.../rag/retriever/RetrievalDefaults.java`（`@ConfigurationProperties("nexus.retrieval")`）、`nexus-knowledge/.../rag/retriever/RetrievalQueryFactory.java` |
| 改写 | `ChunkSearchService(.Impl)`（`topK: int → Integer`，null = 用知识库配置；解析集中到 factory）、`KnowledgeBaseServiceImpl`（建库默认值改读 `RetrievalDefaults`）、`RagAnswerServiceImpl`（`reranked` 误报修正）、`KnowledgeSearchController` / `KnowledgeRagController` / `RagStreamService(.Impl)` / `KnowledgeSearchTool` |
| 配置 | `application.yml` 新增 `nexus.retrieval.top-k` / `similarity-threshold`（可用 `NEXUS_RETRIEVAL_*` 覆盖）；H2 `schema.sql` 的列默认值 0.7 → 0.25 |
| 数据库 | **无迁移**（V10 的列本就是 0.7 默认，本增量只改"运行时读哪个值"；生产库历史数据不追改，改由建库/更新接口写入） |
| API | 请求/响应结构不变；`topK` 语义变为"不传 = 用知识库配置"（前端改为不再补默认值） |
| 新增测试 | 16 条（`ChunkSearchServiceKnowledgeConfigTest` 6 / `RetrievalQueryFactoryTest` 5 / `RagAnswerServiceRerankFlagTest` 5） |
| 测试基线 | 232 → **248 通过 / 0 失败 / 0 错误 / 18 跳过** |
| 运行时验证 | `standalone` + 演示数据：配置 12/0.25 生效、请求 topK=3 覆盖、7/0.42 到达 VectorStore、不传参数建库得 5/0.25（原 0.7） |
| 未验证 | ES / pgvector / Ollama 真机（本机 Docker/WSL2 不可用）→ **UNAVAILABLE**；故"结果质量随参数变化"未验证，只验证了参数下推 ｜ **2026-09-30 补**：真机栈已跑通（阈值与 topK 在真 pgvector 上实测，见 §9.6），但"排序质量随参数变化"的对比仍未做 |

**与 §三 设计稿的偏差（如实记录）**：设计稿只说"`retrieval_top_k` 生效"，未提默认值冲突。
实施中发现表默认 0.7 与检索默认 0.25 **从来不一致**，故本增量包含一次默认值统一
（取 0.25，依据 B.25 的真实测量：top-1 余弦 0.7458）。详见 decisions.md B.32。

**留给后续增量的**：`status=DISABLED` 的知识库仍参与检索（属"是否参与"而非"参数多少"，单独增量）；
知识库检索参数暂无设置界面（仅 API）。

### 9.3 增量 3.4 完成记录（2026-09-30）

| 项 | 结果 |
| --- | --- |
| 状态 | ✅ 代码与测试完成（编译 / 全量测试 / 前端构建 全绿）；**浏览器 E2E 只完成一半**（见下） |
| 目标 | `mode=RAG` 的 `/api/chat/stream` 走 `prepare()` 并先发 `retrieval`；`RagStreamServiceImpl` 也补发 |
| 新增主代码 | `chat/stream/RetrievalEvent.java`（两条链路共用的 `retrieval` 事件构造器）、`chat/service/ConversationKbIds.java`（JSON 列读写与 H2/MySQL 形态差异）、`chat/service/ChatMemoryTurns.java`（RAG 的历史读/写） |
| 改写 | `ChatStreamServiceImpl`（RAG 分支：读绑定 → 读历史 → `prepare()` → 发 `retrieval` → 零资料短路；提示词改调 `RagAnswerService`）、`ConversationServiceImpl`（`resolveKbIds` 三条准入规则）、`RagStreamServiceImpl`（补发 `retrieval`、改用共用构造器与 `EMPTY_ANSWER`）、`RagAnswerService(.Impl)`（`RagTurn` + `EMPTY_ANSWER` 收敛）、三个会话 DTO、`KnowledgeRagController`（历史窗口由"最早 3 轮"改为"最近 3 轮"） |
| 配置 / 数据库 | **无迁移**（`chat_conversation.kb_ids` 是 V2 既有列） |
| API | `retrieval` 事件由"预留"变为**已实现**（`hits` 是切片级，不是设计稿的 `documents`）；新建/更新/会话详情多出 `kbIds`；RAG 未绑库提问在开流前 400/1001 |
| 前端 | 对话页新增模式/知识库选择与检索卡片；知识库问答页新增"检索到 N 个片段"；`streamAgentChat` 更名 `streamChat`（三种模式共用）；导航标签 `Agent 对话` → `对话`（`/agent` 路径与路由名不变） |
| 新增测试 | 16 条（`RagTurnTest` 6 / `ChatRagModeTest` 7 / `RagStreamRetrievalEventTest` 3） |
| 测试基线 | 290 → **306 运行 / 288 通过 / 0 失败 / 0 错误 / 18 跳过（7 个 live 类）**，BUILD SUCCESS |
| 运行时验证（真机） | `standalone` + 演示数据 + Edge：Agent 腿全过（默认模式 Agent、工具卡片、真实摘要、275 字回答、刷新后痕迹 4→4）；RAG 会话从界面创建时确实带上了 `kbIds` |
| 运行时验证（桩帧） | `web/scripts/verify-retrieval-ui.mjs`：桩掉 `/api/chat/stream`，真浏览器里断言检索卡片渲染（文案 / 命中列表 2 条带文档名与标题路径 / `已重排` / **卡片在回答之前** / 请求体带 `conversationId`）、零命中分支（`知识库里没有命中`）、以及"服务端校正后卡片消失、答案仍在"这一已知缺口 → **PASSED**（退出码 0） |
| 未验证 | **真机上的检索链路本身**：`standalone` 无 ES，`/ask/stream` 与 RAG 会话流式在检索处即 4005 → **SKIPPED**（退出码 2，脚本明确打印原因）；ES / pgvector / MySQL 真机 → **UNAVAILABLE**（Docker 未运行） ｜ **2026-09-30 补**：真机栈已补验——RAG 腿与 `verify-stream.mjs` 首次真跑、退出码 0，见 §9.6 |

**与 §八 测试方案的偏差**：方案里的两条都落地了（`RagStreamRetrievalEventTest` 为 Contract 层；
`verify-stream.mjs` 已升级为断言"检索到 N 个片段"，且加了"生成期间轮询记录器"以避免
标签消失后再采样的假阴性）。但**升级后的 `verify-stream.mjs` 在本地无法执行**——它本来就需要 full 栈
（脚本注释记录的"前置阶段（检索 + 重排 + 组装）10~20 秒"就是这个原因）。

为此补了一层**桩验证**（`verify-retrieval-ui.mjs`）：把 `retrieval` 事件的
"后端发出"（契约测试）与"前端渲染"（桩帧 UI 测试）拆成两件事分别证明，
剩下真正没验的只有"真机检索能跑通"这一条。三层口径写在 decisions.md B.34 的验证小节里，
不得把桩验证说成真机 E2E。

**顺带修掉的两个真问题**（都不是本增量引入的）：

1. `PUT {"kbIds":[]}` 解绑静默失效（MyBatis-Plus `updateById` 跳过 null 字段）——
   接口 200、返回体看着对、数据库没变；
2. 对话页 `onMounted` 的"进来就选中最近会话"会覆盖用户在 RPC 期间的操作
   （点了"新对话"却被跳回旧会话）——**由 E2E 脚本"跳出假通过"暴露**。

其余设计决策与理由见 decisions.md B.34。

### 9.4 增量 4.1 完成记录（2026-09-30，Agent Definition 后端）

| 项 | 结果 |
| --- | --- |
| 状态 | ✅ 后端完成（编译 / 全量测试 全绿）；**前端（Agent 管理页）留到 4.2**（已在 §9.5 补完） |
| 目标 | §十五–§十八：Agent 可创建/修改/删除/启停/复制、可绑知识库、可配提示词与模型角色；§十七：Definition 与 Runtime 分离 |
| 新增主代码 | `nexus-ai`：`ai/agent/AgentDefinition`（纯值对象） |
| 新增主代码 | `nexus-app`：`agent/AgentService`（资源服务）、`agent/AgentRuntime`（运行，即改名前的 `AgentService`）、`agent/AgentResourceController`、`agent/model/{AgentStatus, dto×4, entity×2}`、`agent/mapper×2` |
| 改写 | `AgentContext`（新增 `KB_IDS` 与 `of(userId, kbIds, listener)`）、`KnowledgeSearchTool`（按 Agent 绑定的范围检索 + 跳过 DISABLED 库 + 如实报告跳过项）、`ChatStreamServiceImpl`（AGENT 分支改用 Definition）、`ConversationServiceImpl`（`agentId` 准入）、`ChatConversationPO` + 三个会话 DTO、`ErrorCodeHttpStatusMapper` |
| 配置 / 数据库 | **V12**：`agent` + `agent_knowledge_base` 两张表、`chat_conversation.agent_id`。**刻意没建** `max_iterations` / `timeout_ms` / `memory_config` / `agent_tool`（无机制读它们，见 B.35 §7） |
| API | 新增 `/api/agents` 7 个（CRUD + duplicate + test-chat）；`/api/agent/ask` 保留（改为读内置默认 Agent）；会话 DTO 多出 `agentId` |
| 错误码 | 新增 `5006`（404）/ `5007`·`5008`·`5009`·`5010`（409） |
| 新增测试 | 27 条（`AgentDefinitionTest` 6 / `AgentResourceTest` 9 / `ConversationAgentBindingTest` 7 / `KnowledgeSearchToolScopeTest` 5） |
| 测试基线 | 306 → **333 运行 / 315 通过 / 0 失败 / 0 错误 / 18 跳过（7 个 live 类）**，BUILD SUCCESS |
| 前端构建 | 本轮未改前端 → **未执行**（4.2 已补跑：`npm run build` PASSED，见 §9.5） |
| 运行时验证（真机） | **SKIPPED**：真实模型跑一次 Agent 需 `AI_CHAT_API_KEY`（`AgentLoopTest` 门控，本机未设置）；真实检索需 ES/pgvector（本机 Docker 不可用） ｜ **2026-09-30 补**：两项都已真跑（13 项 live + 3 项流式 live，见 §9.6） |
| E2E 浏览器 | **UNAVAILABLE**：Agent 管理页尚未实现（4.2 已补：`verify-agents.mjs` 腿 ①–⑦、⑨–⑪ PASSED，试跑 SKIPPED，见 §9.5） |

**与 §5.2 设计表的偏差**（三处，理由见 B.35 §7 与 database.md §6.0）：
表名去掉了 `ai_` 前缀（`agent` 而非 `ai_agent`）；`max_iterations` / `timeout_ms` / `memory_config`
三列与 `agent_tool` / `agent_run` / `agent_run_step` 三表**本轮不建**——它们各自要等的机制
（第二类工具、可观测性阶段）还没到，先建就是"假旋钮"。

**本轮发现、有意没修**：`kb_knowledge_base.status` 的"禁用后不参与检索"承诺，
只在新的 Agent 工具路径兑现；RAG 链路仍不读它（"禁用一个库"对 RAG 问答不生效）。
RAG 链路属 §六十五 保护区，且语义边界值得单独一个增量 + 自己的回归，故记录而不顺手改。

### 9.5 增量 4.2 完成记录（2026-09-30，Agent 管理页 + 会话选 Agent）

| 项 | 结果 |
| --- | --- |
| 状态 | ✅ 前后端完成（编译 / 全量测试 / `npm run build` / 真机栈 E2E **无跳过、退出码 0**）；配好模型后试跑腿也真跑了（真模型 + 真工具，见下） |
| 目标 | §十五/§十六/§十八 的界面落地：Agent 全生命周期在浏览器里可走通（新建 → 配置 → 发布 → 选用 → 停用 → 删除），会话能选/换 Agent |
| 新增前端 | `web/src/views/AgentsView.vue`（管理页，四个弹窗：新建/配置/试跑）、`web/src/api/agents.ts`（8 个接口 + 类型，`AgentTestResult` 按后端 `AgentAnswer` 实际字段写） |
| 改写前端 | `AgentView.vue`（AGENT 模式的 Agent 选择器 + "N 个不可选用"提示 + 发送时带 `agentId`）、`api/chat.ts`（`Conversation.agentId`、`bindAgent`、`resetConversationAgent`）、`api/client.ts`（**错误抛后端原话**，见下）、`router/index.ts`（`/agents`，路径用复数与 `/agent` 区分）、`App.vue`（导航项） |
| 改写后端 | `UpdateConversationRequest`（新增 `resetAgent`）、`ConversationServiceImpl`（`resetAgent` 分支 + 抽出共用 `requireAgentMode`） |
| 配置 / 数据库 | **无迁移**：`resetAgent` 是请求侧信号，复用了 V12 既有的 `chat_conversation.agent_id` |
| API | `PUT /api/chat/conversations/{id}` 新增 `resetAgent:true`（回到内置默认 Agent）；与 `agentId` 同传 → 400/1001 |
| 新增测试 | 1 条（`ConversationAgentBindingTest.resetAgentReturnsToBuiltin`，含两个 1001 分支；该类 7 → 8） |
| 测试基线 | 334 → **335 运行 / 317 通过 / 0 失败 / 0 错误 / 18 跳过（7 个 live 类）**，BUILD SUCCESS（nexus-app 163 → 164） |
| 前端构建 | `npm run build`（vue-tsc + vite）→ **PASSED** |
| E2E 浏览器（真机栈） | `web/scripts/verify-agents.mjs`，11 条腿全部执行：①内置项可见且显示"全部知识库" ②新建落草稿（+ 按钮映射） ③配置提示词与绑库 ④复制为草稿 ⑤发布 ⑥对话页选择器列出它 ⑦"草稿需先发布"提示 ⑧试跑（真模型 4 次工具调用） ⑨停用 ⑩停用后不再可选 ⑪清理 → **PASSED（退出码 0，无跳过）**；截图 `agents-list.png` / `agent-picker.png` / `agents-test.png` |
| 真机（真模型 + 真工具） | 配好 `AI_CHAT_API_KEY` 后试跑跑通：`opencode:deepseek-v4-flash` 调用 `knowledge_search` 命中演示笔记内容，回答带来源 → **PASSED**（无 ES 时工具降级为"关键词匹配"并如实说明，**不等于**真 ES/pgvector 检索）｜ **2026-09-30 补**：真机栈（`local`，ES + pgvector 就绪）上重跑通过，见 §9.6 |
| 未验证 | ES / pgvector / MySQL 真机 **UNAVAILABLE**（Docker 未运行）｜ **2026-09-30 补**：真机栈已补验，见 §9.6；头部选择器"绑定的 Agent 后被停用"这一分支**仍**只有构建层验证（E2E 未覆盖），见 B.36 §6 口径 |

**界面逼出来的两个后端问题**（都不是本增量引入的，见 B.36 §3、§4）：

1. **`agentId` 只进不出**：`PUT /api/chat/conversations/{id}` 是局部更新，`agentId:null` 已表示"不改"，
   于是"清空选用的 Agent"没有任何表达方式——选过 Agent 的会话回不到内置默认。
   在此之前没有界面会去点它，所以只是一条理论死路。修法是加显式信号 `resetAgent:true`，
   两个字段同传报 1001（自相矛盾的请求，不猜意图）。
2. **试跑的失败原因在界面上被丢掉**：后端把 4006 映射成 HTTP 502，axios 抛出
   `Request failed with status code 502`，后端那句"模型 … 未注册。已注册：[…]"被丢在响应体里。
   修在 `client.ts`：错误的 `message` 用后端原话，`status`/`code` 保留。这个坑同时让 E2E
   无法判断"是不是环境缺模型"，只能误判。

**E2E 抓到的第三个问题**（前两个见上面"界面逼出来的两个后端问题"，理由在 B.36 §3/§4）：
配好模型后试跑腿失败于"没有使用任何工具"。
查下来不是检索坏了——绑定知识库时"用示例问题"填的是"我的笔记里**和这个问题**相关的内容是什么？"，
"这个问题"没有指代对象，模型正确地反问了"你指的是哪个问题"、一次都不检索，
于是界面弹出一条**对正常配置的误报**："未使用工具……别急着发布"。修法：两种情况都填有主题的问题
（绑库时范围已知，问得更短即可）。模型的行为是对的，错的是我给它的样例。

**与 §5 设计表的偏差**：界面上**没有**模型角色选择器——可选值应由服务端模型注册表决定，
而"列出角色"的接口还没有，属 Model Config 增量。今天只读显示（`服务端默认` / 已配角色名），
不做假下拉（与 B.35 §7 同一条原则：假旋钮比缺失更糟）。

其余决策与实测记录见 decisions.md B.36。

---

### 9.6 真机栈验证补记（2026-09-30，Docker + `local`）

**为什么单独记**：§9.3–§9.5 的"未验证"行里都写着同一句环境前提——"本机 Docker（WSL2 后端）不可用"。
Docker 起来之后，用 `local` profile（真 MySQL 3307 / ES 9200 + analysis-ik / pgvector 5433 / Redis / RabbitMQ）
把这几行重新跑了一遍。本节只记**结果**与**口径**；这一轮逼出来的三个缺陷（两个后端死路、
一张"只活 41ms"的界面卡片）见 decisions.md **B.37**。

| 层次 | 结果 |
| --- | --- |
| 全量回归 | **335 运行 / 317 通过 / 18 跳过 / 0 失败 / 0 错误**，BUILD SUCCESS——与 §9.5 同一数字，说明本轮两处修法**没有回归** |
| Live 真机测试（此前 18 个跳过里的 13 个） | `LiveEmbeddingTest` 2 / `LiveModelCallTest` 2 / `LiveChunkIndexTest` 5 / `LiveVectorStoreTest` 4 = **13 项全过**；另 `ChatStreamLiveTest` **3 项全过**（真实模型流式 + 落库 + 中断） |
| 真机 ES：（IK 分词 / BM25 / 字段权重 / `kb_id` 过滤） | `usesIkAnalyzer`（"缓存击穿与互斥锁"切出「缓存」「击穿」，且**不**退化成单字）、`searchesChineseByBm25`、`titleBoostAffectsRanking`（标题 3 倍权重生效）、`filtersByKnowledgeBase`（**双库对照**：同内容两个库，不串库；按库删除不影响别人）→ **PASSED** |
| 真机 pgvector（余弦排序 / 阈值 / 按库过滤 / 缓存） | `upsertAndSearchByCosineSimilarity`（1.0 / 0.6 / 0.0 精确排序，**另一库里的相同向量检索不到**）、`respectsScoreThresholdAndDeletion`、`upsertIsIdempotent`、`cachesEmbeddingsByHash` → **PASSED** |
| 真机 RAG 检索链路（端到端） | RAG 会话流式：`retrieval` 事件**先于第一个字**、`count=4`、`hits[].source="hybrid"`（BM25 与向量两路都命中，例：`缓存击穿 > 互斥锁方案`）、`contextTokens=108`；知识库向量化 4 切片、ES 建索引 4 切片均实测成功 |
| E2E 浏览器（真机栈 + Edge） | `verify-agent-chat.mjs`（Agent 腿 + **RAG 腿首次真跑**）、`verify-stream.mjs`（**首次真跑**）、`verify-agents.mjs`（11 腿全执行）→ 三者**退出码均为 0，无跳过** |
| 前端构建 | `npm run build`（vue-tsc + vite）→ **PASSED** |
| **仍未验（别把本节读成"全都验过了"）** | ① **应用自身的 Docker 部署形态**（Dockerfile / nginx 提供前端）——本轮 Docker 只当**基础设施**用，应用仍是本机 `java -jar` + Vite dev server；② `tags` / `folder` / `documentIds` **三个过滤字段仍只有代码级验证**（真机跑的是 `kb_id` 单键过滤，ES 侧是 `.term()` 等值；多值 `terms` 形态未单独验）；③ Vue 单测（前端无框架）；④ 头部选择器"绑定的 Agent 后被停用"分支的 E2E |

**口径（§四十八，三件事不合并）**：live 测试证明**适配器对真 ES/pgvector 正确**；
E2E 证明**界面在真机上把过程显示给用户**；真机 SSE 原始帧证明**端到端链路**。任一条都不能替代另外两条。
本节升级的是"那几个 `UNAVAILABLE` 的**当前状态**"，**不改写** §9.3–§9.5 已写下的历史判定
——那几行的环境前提（当时确实没有 Docker）是真的。

截图证据：`docs/screenshots/agent-chat-rag.png`（RAG 腿：「检索到 4 个片段」+ 带 `[1]` 引用的回答）、
`agent-chat-failure.png` / `agents-failure.png`（失败现场留档，说明见 B.37 §6）。

---

### 9.7 增量 4.3 完成记录（2026-10-01，Tool Definition 后端）

| 项 | 结果 |
| --- | --- |
| 状态 | ✅ 后端完成（编译 / 全量测试 全绿）；**前端（工具目录页）留到 4.4**；**真机栈未跑**（Docker 未运行，见下"未验证"） ｜ **2026-10-01 同日补**：真机栈已跑（含模型腿），抓到 **两个** 4.3 引入的回归并修掉，见下面"同日补跑"与 decisions.md **B.39**（`topK`）、**B.40**（工具痕迹） |
| 目标 | §十九：Tool Definition → Registry → Factory → Executor；消灭"工具注册硬编码"（§1.11 第 2 条）；**MCP 的 `tools/list` 改为从注册表生成** |
| 新增主代码（`nexus-ai`） | `ai/agent/ToolDefinition`（纯值对象）、`ToolSource`、`ToolCategory` |
| 新增主代码（`nexus-app`） | `agent/tool/`：`ToolCatalog`（代码侧清单，从 `@Tool` 反射）、`ToolRegistry`（用户态唯一答案）、`AgentToolFactory`（装配）、`ExecutingToolCallback`（执行壳）、`CalculatorTool`；`agent/ToolController`、`agent/mapper/{ToolMapper,AgentToolMapper}`、`agent/model/{entity×2, dto×2}` |
| 改写 | `AgentRuntime.configure`（写死的 `ToolCallbacks.from(knowledgeSearchTool)` → `toolFactory.applyTo(...)`）、`AgentService`（`toolCodesOf` / `replaceTools` / 复制与删除带工具白名单）、`AgentDefinition`（+`toolCodes`、`describe()` 带上工具范围）、三个 Agent DTO、`McpEndpoint`（`tools/list` 与 `tools/call` 都走注册表 + 执行壳）、`ChatStreamServiceImpl`（删掉 4.1 遗留的无用 import）、`ErrorCode`（+`5011`）、`ObservabilityRegistry`（+4 个计数） |
| 配置 / 数据库 | **V13**：`tool`（**覆盖行**：无行 = 代码侧默认，即启用）+ `agent_tool`（无行 = 不限制）。**刻意不建** `name` / `description` / `category` / `json_schema` / `source` 五个描述性列，以及 `permission` / `approval_required` / `timeout_ms` / `rate_limit` / `agent_tool.enabled`（无机制读它们，见 B.38 §4） |
| API | 新增 `GET /api/tools`、`PUT /api/tools/{code}`；`POST`/`PUT /api/agents` 新增 `toolCodes`（空 = 不限制，与 `kbIds` 同一套语义） |
| 错误码 | 新增 `5011`（工具不存在，404） |
| 新增测试 | 23 条（`ToolResourceTest` 10 / `CalculatorToolTest` 6 / `ExecutingToolCallbackTest` 4 / `AgentDefinitionTest` 6→9） |
| 测试基线 | 335 → **358 运行 / 340 通过 / 0 失败 / 0 错误 / 18 跳过（7 个 live 类）**，BUILD SUCCESS（nexus-app 164 → 184，nexus-ai 35 → 38） |
| 前端构建 | 本轮未改前端 → **未执行**（4.3 只做后端，与 4.1 只做后端、4.2 再上前端同一节奏） |
| 运行时验证（真机） | **未跑**：Docker Desktop 未运行（`npipe:////./pipe/dockerDesktopLinuxEngine` 不存在）。**这一轮比往常更依赖它**——见下面"必须真机回归的一条" ｜ **2026-10-01 同日补跑**：`local` profile 真机栈（MySQL 8.4 / ES 9.4.5+ik / pgvector / RabbitMQ / Redis / Ollama bge-m3）已起，逐条复核见下 |
| E2E 浏览器 | **不适用**：本轮无界面（工具目录页属 4.4） |

**同日补跑（2026-10-01，真机栈 `local`）**

| 项 | 结果 |
| --- | --- |
| V13 迁移 | ✅ 真 MySQL 上应用成功（`tool` / `agent_tool` 建出） |
| 覆盖行写入 / 幂等 | ✅ `PUT /api/tools/calculator` 落该用户覆盖行，来回切换幂等；**代码侧默认值未被改写** |
| 目录 / 装配一致性 | ✅ `GET /api/tools` 的 `enabled` 随覆盖行变化，且装配结果同步变化（不是"配了没人读"） |
| `agent_tool` 生命周期 | ✅ 绑定 / 解绑（空列表）/ 删 Agent 后清理无残留 |
| 错误码 | ✅ 不认识的 code → 5011 / HTTP 404，报文列出可用值 |
| 执行壳计数 | ✅ `/api/observability/summary` 的 `tool.call.succeeded` 随调用增长（本轮 = 2） |
| **检索路径未被 4.3 改动**（§5.3 的硬要求） | ✅ 真 ES + pgvector + bge-m3 上 `knowledge_search` 返回真实片段；缺省 5 条、`topK=2` 两条 |
| **抓到的回归 ①** | ❌→✅ MCP 漏传 `topK` 使工具 500 级失败（`NullPointerException: Cannot invoke "java.lang.Number.intValue()"`）。根因有两层：`int` 拆箱 + **`@ToolParam.required()` 默认 `true`**（反射 schema 把"可选"的 `topK` 标成必填，与自己那句"默认 5"自相矛盾）。已修（`Integer` + `required = false`）并补了回归断言，详见 **B.39** |
| **抓到的回归 ②** | ❌→✅ 工具痕迹原先由**工具自己**上报，`CalculatorTool` 不上报 → 它执行成功而日志写"工具调用 0 次"、`usedTools=false`、流式界面**一张卡片都没有**。已改为**执行壳兜底**（`ToolCatalog` 登记时声明 `selfReports`），并以"每次成功调用恰好一条痕迹"为不变量对注册表逐条断言（**故意改坏验证过守卫会红**），详见 **B.40** |
| 回归测试 | ✅ 共 +2 条：`mcpToolsComeFromTheRegistry` 内补 `required` 恰为 `["query"]` / 漏传 `topK` 时 `isError=false`；新增 `everyToolLeavesExactlyOneTrace`。全量测试 **358 → 359 运行 / 341 通过 / 0 失败 / 0 错误 / 18 跳过** |
| **模型驱动的那条腿** | ✅ **已跑通**（key 是 shell 里那个 `oc_` 开头的，实为 OpenCode 的 key，变量名起错了）。日志：`为 Agent 默认助手(…) 装配 2 个工具：[knowledge_search, calculator]`；一个同时需要检索与算术的问题里**两个工具都真调了**——`knowledge_search` 耗时 2502 ms（**真 ES + pgvector**，LIKE 兜底是毫秒级）且回答带 `缓存击穿 › 什么是缓存击穿` 这类真实标题路径，`calculator` 给出正确的 `12345 × 6789 = 83810205`；模型还自己丢掉了 2 条无关命中 |
| 现场清理 | ✅ 验证写入的覆盖行（demo 的 `calculator`）与临时 Agent 已删除：`tool` 覆盖行 = 0、`agent_tool` = 0，回到验证前状态 |

**必须真机回归的一条（别跳）**：4.3 之后，**没绑过工具的既有 Agent 会多出一个 `calculator`**——
`agent_tool` 无行 = 不限制，于是"该用户启用中的全部内置工具"里现在有两个成员。
这是**有意的产品语义**（工具平台化后，新工具默认可用），但它同时改动了**每一个既有 Agent 送给模型的工具清单**。
§5.3 的 4.3 设计里把这件事写成了硬要求：**"必须实测检索路径没有因此改变"**。
在真机跑通之前，这一条只能算"设计上成立"。

> **2026-10-01 已补跑**：真机栈上检索路径**确实没变**（真 ES + pgvector + bge-m3 返回真实片段，
> 缺省 5 条 / `topK=2` 两条；模型腿也跑了，装配行写着 `装配 2 个工具：[knowledge_search, calculator]`）。
> **并且正因为真跑了，才抓到两个回归**：① `topK`（B.39）——4.3 删掉 MCP 里那句手写的 `asInt(5)`
> （删得对）时，一并拆掉了 `int` 拆箱 bug 的掩体；② 工具痕迹（B.40）——第二个工具一登记，
> "痕迹由工具自己上报"这个洞才第一次显形。
> **这条"必须真机跑"不是形式：它当天兑回了两个测试环境永远看不见的缺陷。**

**两处"注释说 A、代码做 B"已清掉**（写本记录时对照代码发现，详见 B.38 §9）：
`ToolDefinition` 的注释还在描述一版被否掉的"落库/补行"设计；
`ToolSource.parse()` / `ToolCategory.parse()` 没有任何调用方却写着"可选值：…"。
两者都不是功能缺陷，但错注释比缺注释更难查。

**一条新引入的、必须留痕的不一致**（B.38 §6）：工具可被停用之后，
系统提示里点名的工具可能与这次装配不一致（内置默认 Agent 的提示词仍写着"必须先调用 `knowledge_search`"）。
做法是**只计数报警、不代改用户数据**（`tool.prompt.mismatch`）；真正把可用工具生成进提示词要单独一轮。

其余决策（覆盖行语义、执行壳两个重载、为什么第二个工具是 `calculator`、有意后置的 `http_request` 等）
见 decisions.md **B.38**。

---

## 十、未确认项（如实列出，不假装已核实）

1. ~~**H2 的 DDL 来源**~~ ✅ **4.1（V12）已实测关闭**：test 与 standalone 两个 profile 的启动日志都显示
   `Successfully applied 11 migrations … now at version v12`，且 Flyway 起点是**空库**（
   `All configured schemas are empty`）→ Flyway 先跑、`db/h2/schema.sql` 成空操作（§1.7 已按实测改写）。
   **遗留清理项（本轮有意没做）**：`db/h2/schema.sql` 与两个 profile 里的 `spring.sql.init` 已成死代码，
   删除它要连带动 `spring.sql.init.schema-locations` 与 standalone 的启动回归，属独立增量。
2. ~~**ES / pgvector 真机行为**~~ ✅ **2026-09-30 实测关闭（部分）**：Docker 起来后用 `local` profile 跑真机，
   `kb_id` 过滤在**真 ES**（双库对照：同内容两个库不串库）与**真 pgvector**（另一库里的相同向量检索不到）
   上都生效；IK 分词、BM25 中文排序、标题 3 倍权重同样是实测而非读代码（详见 §9.6）。
   **仍未覆盖**：`tags` / `folder` / `documentIds` 三个过滤字段只有代码级验证
   （`ElasticsearchChunkIndex.metadataClauses`），真机只跑了 `kb_id`；ES 侧走的是 `.term()` 等值，
   多值 `terms` 形态未单独验。
3. **Spring AI 工具循环的迭代上限**：`AgentRuntime` 的 Javadoc 说由 `ToolCallingManager` 限步，
   **仍未读框架源码确认默认上限值与超限时的异常类型**。4.1 因此**没有**把 `max_iterations` 做成字段
   （无机制读它 = 假旋钮）；要真做成可配置，必须先确认上限在哪一层、超限时抛什么异常。
4. **`RagStreamService`（独立 RAG 流式）与 `/api/chat/stream`（会话流式）的功能重叠**：两者都能"流式 RAG"，
   但今天只有前者检索、只有后者有会话/记忆。Phase 3 的 3.4 必须明确"哪个是主链路"（我倾向以会话流式为主链路，
   知识库独立接口保留为调试/单次问答入口），此决策需要在动手前与你确认。
5. **`skipUnitTests` 属性（`pom.xml:62`）无人引用** —— 确认是历史残留后可删，属清理项。
6. **`nexus-ai` 的 3 个测试类**未逐条纳入 §1.10 的门禁统计（已确认该模块无环境变量门禁）。

---

## 附：本文件与其它文档的关系

- `docs/update.txt` §七十 —— 本文的直接依据（八份交付物）。
- `docs/platform-upgrade-phase1-analysis.md` —— Phase 1 分析 + Phase 2 完成记录（§13）；本文是其 Phase 3 部分的前置。
- `docs/decisions.md` B.23–B.30 —— 检索链/Agent/评测的实现记录与踩坑（本文凡涉及"为什么这样设计"都回溯到那里）。
- `docs/database.md` / `docs/api.md` —— 结构与契约的当前版本；Phase 3 落地时按 §6/§7 同步更新。
