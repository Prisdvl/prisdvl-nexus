# 架构决策记录（ADR）

> 每个决策记录：背景 → 决策 → 理由 → 代价 → 被否方案。
> 面试时"你做过哪些技术选型"就靠这份文档回答。

---

## ADR-001 采用 Maven 多模块的模块化单体，而不是单模块或微服务

**背景**：功能域有 auth / chat / knowledge / rag / agent / tool / evaluation / observability 八个。需求明确第一版不要微服务化，但 Phase 7 需要一个**独立进程**的 MCP Server。

**决策**：单仓库 Maven 多模块（`nexus-common` + `nexus-knowledge` + `nexus-app`，Phase 7 增加 `nexus-mcp-server`），业务按包分模块，部署仍是一个应用。

**理由**
1. MCP Server 必须是独立进程，且要复用知识检索逻辑 —— 如果全在一个模块里，只能靠"复制代码"或"把整个单体也打进 MCP Server"（会带上 Security/JWT/Chat 等无关依赖）。拆模块后 `nexus-mcp-server` 依赖 `nexus-knowledge` 即可，**拆服务成本≈0**。
2. 模块边界由**编译器**保证，而不是靠代码评审时的自觉。`nexus-knowledge` 里根本 import 不到 `SecurityContextHolder`，租户隔离只能通过显式 `userId` 参数实现 —— 这恰好是未来拆服务必须的形态。
3. 现在项目小，多模块的维护成本低；等 8 个域都写满了再拆，重构成本高得多。

**代价**：每个模块都要单独 `mvn install`；IDE 里跨模块改动需要先安装依赖模块；不能像单模块那样随手 import。

**被否方案**
- 单模块 + 严格分包的"模块化单体"：最省事，但 Phase 7 必然要复制检索代码，且边界形同虚设。→ 若你更在意前两周的迭代速度，可以选它，但要接受 Phase 7 的重构。
- 直接微服务（knowledge-service / chat-service / agent-service）：对一个个人项目是过度设计，本地要跑 3+ 个 JVM，简历上也不加分（面试官会问"多少 QPS 需要拆"，答不上来反而扣分）。

---

## ADR-002 存储分工：MySQL 业务真源 + pgvector 向量索引 + ES 关键词索引

**背景**：需要同时具备事务型业务数据、向量相似度检索、中文全文检索（BM25）三种能力。

**决策**：MySQL 存业务与 Chunk 正文（真源）；pgvector 只做向量索引；Elasticsearch 只做 BM25 索引。两者都是**派生索引**，可从 MySQL 重建。

**理由**
1. MySQL 事务能力是业务刚需（导入任务的计数、会话消息顺序、评测结果一致性）。
2. pgvector 与业务库分离，避免向量写入把 MySQL 拖慢；HNSW 索引重建不影响业务表。
3. ES 的中文分词（IK）+ BM25 打分可控性远好于 MySQL 全文索引。
4. "派生索引 + 真源"的划分让**删库重建**成为可能：提供 `/api/documents/{id}/reindex` 与全库重建接口，索引坏了不是数据灾难。

**代价**：三套存储要同步，存在不一致窗口。→ 对策：写入顺序"MySQL 先落库（PENDING）→ 写索引 → 回填状态"；失败项由任务表标记，支持重试与重建；不追求分布式事务。

**被否方案**
- 只用 ES 做全部（含向量）：ES 支持 dense_vector，但业务事务、复杂关联查询、报表统计都不如 MySQL。
- 只用 PostgreSQL（pgvector + 全文检索）：中文 BM25 需要 zhparser 等扩展，生态与可控性不如 ES，且你在简历上"ES + 中文分词"是加分项。
- Redis 当向量库（Spring AI 支持 Redis VectorStore）：需要 Redis Stack，且持久化与查询能力不是首选。

---

## ADR-003 用 Spring AI 的抽象，但**不用** starter 自动配置模型

**背景**：Spring AI 的 starter 自动配置一个 `ChatModel`/`EmbeddingModel` bean，provider 由依赖决定。但本项目需要同时存在：对话模型、裁判模型（评测）、嵌入模型（本地），且要能按角色切换。

**决策**：`spring.ai.model.chat=none` 关掉自动配置（官方文档确认该属性存在，且其设计目的正是"允许多模型配置"），由 `AiModelRegistry` 按配置列表手工构建多个模型，`ChatClientFactory` 按逻辑角色（`chat.default` / `chat.agent` / `chat.judge` / `embedding.default`）提供 `ChatClient`。

**理由**
1. "模型可替换"是需求硬约束；角色化后，业务代码只写 `chatClientFactory.get("chat.agent")`。
2. 评测需要"裁判模型可能与生成模型不同"，这在单 bean 自动配置下做不干净。
3. 可以在测试里注入假 provider，实现不联网的单元测试。

**代价**：失去 starter 的自动重试/超时/客户端工厂配置，需要自己管 API Key 校验、超时、连接池。→ 对策：`AiModelRegistry` 统一封装构建参数，配置校验在启动时失败（fail fast）。

**被否方案**：多 profile + 每 profile 一个 starter（dev 用 ollama、prod 用 openai）——做不到"同一进程里同时用两个 provider"。

---

## ADR-004 导入、嵌入、评测、报告一律异步（RabbitMQ）

**背景**：上传一个 Obsidian Vault（几百到上千文件）时，解析 + 嵌入是分钟级操作，且嵌入按 Token 计费。

**决策**：HTTP 接口只做"存原始文件 + 建任务 + 投递消息"，立即返回 `taskId`；worker 消费后解析/切分/嵌入/建索引，进度写 DB + Redis，前端轮询或 SSE。

**理由**：不被 HTTP 超时截断；MQ 削峰并支持重试；任务可取消、可单文件重试；进度可观测。

**代价**：需要处理"任务状态机 + 幂等 + 重复消费"。→ 对策：`kb_import_task` 作为状态载体，worker 以 `taskId + source_path` 为幂等键；重复消费只产生 SKIPPED。

**被否方案**：Spring `@Async` 线程池（进程重启任务丢失、无法跨实例、无死信重试）；同步处理（必超时）。

---

## ADR-005 Markdown 采用**结构化切分**，定长切分只作为兜底

**背景**：笔记是 Markdown，标题就是语义边界。

**决策**：按 H1→H2→H3 构建标题树，节点内的正文在"目标 400 token"上限内整块成 Chunk，超出则按段落递归切分（重叠 50），代码块/表格整体保留；嵌入文本 = 标题路径 + 正文。

**理由**
1. 引用必须能定位到标题（前端要滚动到 `heading_path`），定长切分做不到。
2. 语义边界对齐后，向量的语义纯度更高，召回更准。
3. 代码块被切断会产生无意义的向量（且会误导模型）。

**代价**：切分逻辑复杂度上升，需要在 Phase 9 用评测集验证参数。→ 对策：切分器可配置，评测对比不同参数。

**被否方案**：Spring AI `TokenTextSplitter` 一把梭 —— 作为 TXT/PDF 的兜底使用，不作为 Markdown 主策略。

---

## ADR-006 BM25 用原生 Elasticsearch Java Client 实现（不用 Spring AI 的 ES VectorStore）

**背景**：混合检索需要 BM25。核对官方文档确认：Spring AI 2.0.1 的 Elasticsearch VectorStore **只支持 kNN 向量相似度**，没有任何 BM25 / 关键词 / 混合检索 API，其非向量路径只有 metadata 过滤。

**决策**：`nexus-knowledge` 内自建 ES 索引（IK 分词、字段与向量 metadata 对齐）与查询（`multi_match` + `bool.filter`），用官方 `ElasticsearchClient`。

**理由**：需求要求真正的 BM25 与混合检索；用原生客户端才能控制分词器、字段权重、filter 下推与打分解释（面试可展示 `explain`）。

**代价**：多一套客户端与索引映射维护；索引重建需要别名切换（`nexus_chunk_v1` → 别名 `nexus_chunk`）。

**被否方案**：给 `spring-ai-elasticsearch-store` 提 issue 等官方支持（不可行）；用 ES 的向量检索替换 pgvector（则失去"pgvector 专项经验"，且与 ADR-002 冲突）。

---

## ADR-007 自研 `Reranker` 接口（因为 Spring AI 2.0.1 没有 Rerank API）

**背景**：核对 2.0.1 完整 BOM 与文档目录：**不存在任何 rerank 制品，也没有 `RerankModel` 接口**。而混合检索后必须精排才能把 TopK 20 收敛到 TopN 6。

**决策**：定义 `Reranker` 接口（`List<RankedChunk> rerank(query, candidates, topN)`），提供两种实现：
1. `LlmReranker`：把候选片段编号后让裁判模型打 0–10 相关性分并输出 JSON，按分数排序（复用 ADR-003 的 `chat.judge` 角色，可用便宜模型）。
2. `HttpReranker`：对接外部重排 API（如 DashScope gte-rerank、BGE reranker），通过配置切换。
另提供 `NoopReranker`（关闭重排），供评测对比"有无重排"的差异。

**理由**：`Reranker` 是检索质量的关键一环且官方缺失，自己实现既满足需求，也是很好的面试素材（能讲清 cross-encoder 与 bi-encoder 的区别、为什么重排贵但有效）。

**代价**：LLM 重排有额外延迟与成本。→ 对策：只在启用重排时对 TopK 候选做重排，结果写缓存；评测量化收益后再决定默认是否开启。

---

## ADR-008 Embedding 模型与维度在知识库上**冻结**

**背景**：不同嵌入模型的向量空间不可比；即使同为 1024 维，语义空间也不同。pgvector 的 HNSW 索引最大支持 2000 维，且维度在列定义上固定。

**决策**：`kb_knowledge_base` 记录 `embedding_provider/model/dim`，建库后**不允许修改**；换模型 = 新建知识库 + 全量重建。pgvector 表固定 `vector(1024)`，默认嵌入模型为本地 `bge-m3`（中文强、免费、离线、1024 维）。

**理由**：避免"库里一半是 A 模型的向量、一半是 B 模型的向量"这种静默的质量灾难（向量照常返回结果，但相似度毫无意义——这是 RAG 最隐蔽的坑）。

**代价**：不能平滑升级嵌入模型。→ 对策：重建接口 + Embedding 缓存（同内容不重复付费）。默认用本地模型，重建成本≈电费。

---

## ADR-009 Agent 手动驱动工具循环，而不是依赖 `ToolCallingAdvisor` 自动循环

**背景**：Spring AI 2.0 中 `ChatModel` 不再内部执行工具；`ChatClient` 会自动注册 `ToolCallingAdvisor` 在内部完成"模型 → 工具 → 模型"的循环。但本项目要求：权限校验、写操作人工确认、审计、超时/步数控制。

**决策**：普通聊天（简单工具）用框架自动 advisor；**Agent 走"用户控制工具执行"路径**：关闭自动注册，自己用 `ToolCallingManager` 驱动循环，并持久化每一步到 `ai_agent_run_step`。

**理由**：自动循环内部执行工具，**没有插入点**做权限判定与人工确认；人工确认还要求循环能挂起并跨 HTTP 请求恢复（`WAITING_APPROVAL`），这必须由自己的状态机实现。

**代价**：要自己处理消息历史拼接、工具结果回灌、最大步数、重试与超时。→ 这正是 Agent 的核心工程价值所在，也是面试重点。

---

## ADR-010 自研轻量工作流引擎，不引入 Flowable / Camunda

**背景**：需要"AI 节点 + 工具节点 + 条件 + 并行 + 人工确认"的编排。

**决策**：自研约 10 个类的引擎（定义/节点/边 + 引擎 + 节点执行器 + 运行表），节点类型固定 7 种。

**理由**：需求是 AI 编排而不是 BPMN 全语义；引入重型引擎会让项目变成"配置工程师"，且难以与 Agent/Tool 体系融合；自研引擎能讲清 DAG 调度、状态持久化、失败重试、人工挂起恢复。

**代价**：要自己保证无环校验、并行收敛、幂等重试。→ 这些恰好是有价值的测试用例。

---

## ADR-011 Chat Memory（框架）与 Chat History（自建表）并存

**背景**：Spring AI 提供 `ChatMemory` + `ChatMemoryRepository`（默认 20 条窗口、turn-boundary 淘汰），官方明确说明"Memory 用于给模型的有界上下文，History 需要自己存"。

**决策**：`chat_message` 存完整历史（前端展示、评测、观测）；`ChatMemory`（JDBC repository）存发给模型的窗口。

**理由**：职责分离，且官方 `JdbcChatMemoryRepository` **不保存 tool-call 消息**，所以 Agent 的完整轨迹必须落在自己的 `ai_agent_run_step`，不能指望 Memory。

**代价**：两处写入，需注意一致性（先写 History，再写 Memory）。

---

## ADR-012 观测双轨：自建 `obs_ai_request` + Spring AI 自带 Micrometer 观测

**背景**：Spring AI 2.0 自带观测（`gen_ai.client.operation`、`gen_ai.client.token.usage` 等，Micrometer）；但前端看板需要按业务维度（用户/会话/场景/成本）统计。

**决策**：① 用框架观测做**单次调用链路**排查（可接 Actuator/Prometheus）；② 自建 `obs_ai_request` 做**业务统计**（Token、延迟、首 Token、检索数、工具数、成本、错误率）。

**理由**：框架指标是技术维度、无业务上下文；业务看板需要自己落库并聚合（`obs_daily_stat`）。第一版**不引入** OTel Collector + Grafana + Loki 全家桶——个人项目里那只是给截图加负担。

**代价**：埋点要写在 Advisor/Orchestrator 统一点位，否则会漏。→ 约束："业务 Service 不得直接注入 `ChatClient`"。

---

## ADR-013 SSE 用 Spring MVC `SseEmitter`，前端用 `fetch` 读取（不用 `EventSource`，不引 WebFlux）

**背景**：需要逐字流式输出、工具调用中间态、引用与用量事件。

**决策**：服务端 `POST /api/chat/stream` 返回 `text/event-stream`（`SseEmitter`，15s 心跳，5min 超时）；前端用 `fetch` + `ReadableStream` 解析。

**理由**：① 全栈保持 Spring MVC，不为流式引入第二套编程模型；② 浏览器 `EventSource` 只能发 GET 且不能带 `Authorization` 头，`fetch` 流式可带 JWT；③ 自定义事件类型（`retrieval`/`tool_call`/`approval_required`）比纯文本流更利于交互设计。

**代价**：要自己写 SSE 解析（十几行）、自己处理重连与取消。

---

## ADR-014 前端为独立 npm 工程（不做 Maven 模块、不用 frontend-maven-plugin）

**决策**：`frontend/` 目录独立 `package.json`，Vite 开发时通过 proxy 调 `:8080`；生产由 Docker 多阶段构建成静态资源交给 Nginx。

**理由**：前后端构建周期完全不同；`frontend-maven-plugin` 把 npm 生命周期的复杂度塞进 Maven，调试体验差；独立工程可以单独用 pnpm 管理。

---

## ADR-015 知识缺口分析必须"规则 + 证据"，禁止模型凭空断言

**背景**：需求明确"不允许凭空声称我的知识库缺少某知识"。

**决策**：缺口分析采用两段式：① **规则层**产出候选（标签/目录/未解析 WikiLink 的覆盖率统计，全部可 SQL 查询）；② **表述层**只负责把规则结果组织成自然语言，且每条结论必须携带证据（文档路径、命中数、未解析链接名）。响应结构里 `evidence` 字段必填，前端默认展开显示。

**理由**：LLM 对"有没有某知识"这类**存在性判断**极不可靠（它看不到全库）。把判断权交给可验证的规则，模型只做表达，才能既好看又不假。

---

## ADR-016 MCP Server 独立进程且第一版只读

**决策**：`nexus-mcp-server` 独立 Spring Boot 进程（Streamable HTTP），只暴露 4 个只读工具，内部 API Key 鉴权。

**理由**：体现 MCP 的本意（能力提供方与调用方解耦、可被任意 MCP 客户端复用）；只读能规避"外部客户端通过 MCP 改我的数据"的风险；写工具等到权限与确认机制成熟后再评估。

---

## ADR-017 统一返回体、错误码分段，以及"雪花 ID 必须序列化为字符串"

**背景**：需要统一 `{code, message, data}` 返回体、统一异常处理与请求追踪；同时主键是 64 位雪花 ID。

**决策**
1. `Result<T>` / `PageResult<T>` 用 **Java 21 record** 实现，放在 **nexus-common**（零框架依赖，Jackson 2/3 都能序列化）；HTTP 状态码映射放在 Web 层 `ErrorCodeHttpStatusMapper`。
2. 错误码分段：`0` 成功 / `1xxx` 通用参数 / `2xxx` 认证授权 / `3xxx` 知识库文档 / **`4xxx` AI 模型与向量** / `5xxx` 工具与 Agent / `6xxx` 工作流与评测 / `7xxx` 限流 / `9999` 兜底。
3. 业务码 → HTTP 状态码用 **switch 表达式穷举枚举**：将来给 `ErrorCode` 加常量却忘了映射，**编译就会失败**，而不是悄悄返回 500。
4. 请求 ID 用 **MDC + `OncePerRequestFilter`**：上游 `X-Request-Id` 必须通过 `[A-Za-z0-9_-]{1,64}` 白名单校验后才透传（**防日志注入**），否则重新生成；响应头回写同一 ID，日志模板用 `%X{requestId}` 自动携带。
5. **所有 `Long` 序列化为 JSON 字符串**（Boot 4 写法：`@JacksonComponent` + `tools.jackson.databind.ValueSerializer<Long>`），配套硬约定：**DTO 中 ID 用包装类型 `Long`，计数用基本类型 `int/long`**。

**理由**
- 雪花 ID 是 64 位，而 JavaScript `Number` 只能安全表示到 2^53-1。直接返回数字会**静默丢精度**（`2104750903666438145` → `...144`），前端表现为"拿到的 id 查不到数据"且不报错——最难排查的一类 bug。
- 业务码与 HTTP 状态码各司其职：前端拦截器按业务码分支，Nginx/监控按状态码统计。
- 业务异常打 WARN（不打堆栈）、未预知异常打 ERROR（带堆栈 + requestId），避免告警被业务噪声淹没。

**代价 / 注意**
- 计数字段若误用 `Long` 会变成字符串 → 已用测试锁住（`WebContractTest` 断言 `totalTokens` 必须是数字 `42`）。
- **Jackson 2 的写法在 Boot 4 不适用**：Boot 4 默认 Jackson 3（`tools.jackson`），自动配置的 bean 是 `JsonMapper`（不是 `ObjectMapper`），自定义序列化用 `@JacksonComponent`；`SimpleModule` / `Jackson2ObjectMapperBuilderCustomizer` 那套属于 Jackson 2 路径。

**验证**：`WebContractTest`（8 用例）+ `ErrorCodeTest`（5）+ `PageResultTest`（5）全绿，见 B.8。

---

## ADR-018 认证方案：无状态 JWT（自签自发 HS256）+ 自定义 401/403 + 账号枚举防护

**背景**：需要注册 / 登录 / API 鉴权 / RBAC，且 Phase 1 的 SSE 流式接口同样要鉴权。

**决策**
1. **自签自发 JWT**：应用既是签发方又是校验方，因此用对称密钥 HS256（`NimbusJwtEncoder` + `ImmutableSecret`），不引入 RSA 密钥对。将来拆服务时换成 RS256（私钥签、公钥验）即可让其他服务独立校验。
2. **过滤器链无状态**：`STATELESS` + 关闭 CSRF / formLogin / httpBasic / logout。纯 Token API 不用 Cookie，不存在 CSRF 的适用场景。
3. **401 与 403 都自定义**（`RestAuthenticationEntryPoint` / `RestAccessDeniedHandler`），返回统一 `{code,message,data}`（2001 / 2003）。否则 Spring Security 的默认响应会破坏前端契约，前端无法区分"去登录页"还是"提示无权限"。
4. **权限来自 Token 的 `roles` 声明**（`JwtAuthenticationConverter` + `ROLE_` 前缀）：无状态鉴权不在每个请求上查库。
5. **不使用 `AuthenticationManager` / `UserDetailsService`**：手工 `passwordEncoder.matches()` 校验，语义更直接可控（那套机制的默认异常链与我们要的业务码语义冲突）。接入第三方 OAuth2 登录时再引入 `AuthenticationProvider`。
6. **账号枚举防护**：用户不存在与密码错误返回**完全相同**的 2006 提示（已用测试断言两者 message 相等）；"账号被禁用"只在密码校验通过之后才提示。
7. **密钥 fail-fast**：`nexus.jwt.secret` 不设默认值，缺失或不足 32 字节直接启动失败——避免开发期弱密钥被带进生产。
8. **userId 只从 JWT 的 `sub` 取**（`CurrentUser.requireId()`），任何接口都不得从请求参数接收 userId（水平越权的典型来源）。

**代价 / 后续**
- 无状态意味着**登出与吊销需要额外机制**（Redis 黑名单 + `jti`）→ claims 里已预置 `jti`，Phase 11 落地。
- 短 access token + 长会话需要 refresh token → **Step 1.1b** 实现（`sys_refresh_token` 表已设计好，存哈希并支持轮换与吊销）。

---

## ADR-019 网关特有的请求要求一律收敛到配置（额外头 / 会话标识 / 协议端点）

**背景**：接入真实网关（OpenCode Zen）时发现，所谓"OpenAI 兼容"并不等于"只兼容路径"：
缺 `x-opencode-session` 直接 400；官方还要求客户端使用自有 User-Agent。
更麻烦的是同一网关内不同模型走**不同协议端点**（`/chat/completions`、`/responses`、`/messages`）。

**决策**
1. `ai.providers[].headers` 支持任意附加请求头，经 `OpenAiChatOptions.customHeaders(Map)` 下发；
   业务代码与注册表逻辑完全不感知具体网关。
2. 会话标识（如 `x-opencode-session`）同样走配置，为 Phase 1.6 的"按会话注入"预留：
   届时用 per-request options 把 `conversationId` 映射成 session id，从而吃到网关的 prompt 缓存。
3. **协议差异显式记录在配置注释与本文档中**：选模型时必须先确认它属于哪种协议；
   若选到 Anthropic 协议的模型（如 qwen3.8 / minimax），要换 Spring AI 的 Anthropic 接入模块。
4. 不把 `max_tokens` 当作成本控制手段（实测传入后被忽略），成本控制交给 Phase 10 的用量看板。

**理由**：把"某个网关的特殊规矩"写进 Java 代码，等于把可替换性重新钉死。
配置化之后，换网关只是改几行 yaml；出问题时（400/401）日志里也能直接看出是哪个头没发。

**代价**：配置项变多，且头名称是字符串（无编译期检查）→ 用 `LiveModelCallTest` 这类真实调用测试兜底。

---

## Appendix A. 版本与 API 核对结果（2026-09-24）

> 规则：**不确定的一律不写进代码**。下表 `✅ 已核对` 项可直接使用；`⚠️ 待核对` 项在动手前必须现场验证（Phase 0 Step 0.3）。

### A.1 已核对（有官方来源）

| 项 | 结论 |
| --- | --- |
| Spring AI BOM | `org.springframework.ai:spring-ai-bom:2.0.1`（2.0.x 最新；`2.1.0-M1` 为里程碑，不用） |
| Boot 兼容 | 官方原文："Spring AI 2.0.x supports Spring Boot 4.0.x and 4.1.x" |
| Spring Boot 版本 | 4.0.x 最新 = 4.0.8；4.1.x 最新 = 4.1.1（本项目用 **4.1.1**） |
| Java 要求 | Boot 4.1.1 最低 Java 17、最高兼容 Java 26 → **Java 21 可用**（本项目用 21） |
| 模型 starter | `spring-ai-starter-model-openai`（chat 与 embedding 同一个）、`spring-ai-starter-model-ollama`（同）、另有专用 `spring-ai-starter-model-deepseek` |
| 向量库 starter | `spring-ai-starter-vector-store-pgvector`、`spring-ai-starter-vector-store-elasticsearch` |
| ChatMemory starter | `spring-ai-starter-model-chat-memory-repository-jdbc`（表 `SPRING_AI_CHAT_MEMORY`，含 `sequence_id`；`initialize-schema` 默认 `embedded`）；Redis 版 `spring-ai-starter-model-chat-memory-repository-redis`（2.0.1 改名，属性前缀 `spring.ai.chat.memory.repository.redis.*`） |
| 关闭模型自动配置 | `spring.ai.model.chat=none` 有效（官方明确该属性用于多模型场景）；`spring.ai.openai.chat.enabled` 已移除 |
| OpenAI 兼容第三方 | 通过属性 `spring.ai.openai.base-url` / `spring.ai.openai.chat.base-url` 指向 DeepSeek 等（官方有 DeepSeek 示例） |
| PgVectorStore 配置 | `initialize-schema` **默认 false（2.0 起破坏性变更）**；HNSW 默认索引类型；`dimensions` 需显式设置；`max-document-batch-size` 默认 10000 |
| VectorStore 删除 | 接口含 `delete(List<String>)` 与 `delete(Filter.Expression)` |
| SearchRequest | `SearchRequest.builder().query(..).topK(..).similarityThreshold(..).filterExpression(..)`；默认 topK=4、threshold=0.0 |
| 元数据过滤语法 | 裸键表达式，如 `user_id == 1001 && kb_id in [1,2]`（不是 `metadata['x']` 形式） |
| **Rerank** | **Spring AI 2.0.1 不存在**（BOM 无 rerank 制品、无对应文档页）→ 自研（ADR-007） |
| ES VectorStore 能力 | **仅 kNN 向量检索**，无 BM25/混合检索 API → 自研 BM25（ADR-006） |
| 工具调用 | 2.0 起 `ChatModel` 不再内部执行工具；`ChatClient` 自动注册 `ToolCallingAdvisor`；`FunctionCallback` → `ToolCallback`；`ToolCallAdvisor` → `ToolCallingAdvisor`；`@Tool` 注解为推荐写法 |
| 工具调用限额 | `DefaultToolCallingManager` 默认每工具 40 次/150 次总量上限（2.0.1 新增），可用 `spring.ai.tools.limits.*` 调整 |
| JDBC ChatMemory 限制 | 不保存 tool-call 消息（`AssistantMessage` 工具调用与 `ToolResponseMessage` 被静默过滤） |
| 观测 | 观测名 `gen_ai.client.operation`、`spring.ai.chat.client`、`spring.ai.tool`、`db.vector.client.operation`；Token 指标 `gen_ai.client.token.usage`；内容默认不导出，需显式开启 `spring.ai.*.observations.log-prompt/log-completion` |
| 评测 API | 存在 `Evaluator`（`EvaluationResponse evaluate(EvaluationRequest)`）、`RelevancyEvaluator`、`FactCheckingEvaluator`（用 `ChatClient.Builder` 构造） |

### A.2 ⚠️ 待核对（动手前现场验证，不在设计里下结论）

| 项 | 为什么重要 | 验证方式 |
| --- | --- | --- |
| Boot 4.1.x 的 starter 坐标是否更名（如 `spring-boot-starter-web` → `spring-boot-starter-webmvc`）、Jackson 3 默认的影响 | 依赖写错直接起不来 | 用 **start.spring.io 生成 4.1.1 基线 pom** 作为唯一事实来源，再逐项加依赖 |
| MyBatis / MyBatis-Plus 对 Boot 4 的支持版本 | 数据访问层选型 | Maven Central + 官方 release notes；**若 MyBatis-Plus 尚不支持 Boot 4，回退到官方 MyBatis Starter 或 Spring Data JDBC** |
| `spring.ai.model.embedding=none` 是否有效 | 多嵌入模型共存 | Spring AI 2.0.1 文档 Ollama/OpenAI embedding 页 |
| `ChatClient` 流式重载（`.stream().content()` / `.stream().chatClientResponse()`）在 2.0.1 的确切方法名 | SSE 实现 | 官方 ChatClient 文档页 |
| `Usage` 的访问器命名（`getPromptTokens()` 还是 `getInputTokens()`） | 观测埋点 | 读 v2.0.1 源码 `Usage.java` |
| `Evaluator` 系列所在包与所属 artifact | 评测实现 | v2.0.1 源码树；若不在 BOM 内则手动加模块 |
| `OpenAiChatOptions.Builder` 是否有 `baseUrl(...)` | 多 provider 手工构建模型 | 读 v2.0.1 源码 |
| MCP client/server starter 坐标与属性（`@McpTool` 包名、`spring.ai.mcp.client.streamable-http.connections.*`） | Phase 7 | 官方 MCP starter 文档页 |
| ES 当前稳定版本 + IK 插件版本匹配 + 《pgvector 当前版本/镜像 tag》+《Redis Stack 镜像 tag》 | Phase 2/3 基础设施 | Docker Hub / GitHub releases |
| Vue / Vite / Element Plus / Pinia 当前版本 | Phase 1 前端 | npm registry |

### A.3 环境实测（2026-09-24）

| 项 | 实测 |
| --- | --- |
| Maven | ✅ 3.9.9（`D:\develop\Maven\apache-maven-3.9.9`） |
| Java | ⚠️ 仅 JDK 17（`C:\Program Files\Java\jdk-17`）→ **需装 JDK 21** |
| Docker | ⚠️ 客户端 29.5.2 / Compose v5.1.3 已装，**守护进程未启动** |
| Node.js | ✅ v22.22.0 |
| 工作目录 | `D:\develop\Prisdvl-Nexus`（NTFS，属主 `LKY-PC\LKY`） |
| 文件沙箱 | ✅ 已解除：会话切换为 `danger-full-access`，`pwsh` 可直接执行（此前 `SetNamedSecurityInfoW failed (Win32 5)` 的问题消失） |

---

## Appendix B. 版本矩阵（第二轮核对，2026-09-24）

> 来源：`spring-boot-dependencies-4.1.1.pom`、Maven Central metadata、MyBatis / MyBatis-Plus release notes、pgvector README、docker-library/official-images、infinilabs 官方发布站、npm registry。

### B.1 Spring Boot 4.1.1 坐标（**关键更正**）

| 用途 | 坐标 |
| --- | --- |
| Web MVC | ⚠️ `spring-boot-starter-web` 仍存在但**已废弃**（POM 描述 `deprecated in favor of spring-boot-starter-webmvc`）→ 本项目用 **`spring-boot-starter-webmvc`** |
| 校验 / 安全 | `spring-boot-starter-validation`、`spring-boot-starter-security` |
| 资源服务器 | `spring-boot-starter-oauth2-resource-server`；Boot 4 另有新风格 `spring-boot-starter-security-oauth2-resource-server`（二选一，Phase 1 Step 1.1 定） |
| 可观测 / MQ / 缓存 | `spring-boot-starter-actuator`、`spring-boot-starter-amqp`、`spring-boot-starter-data-redis` |
| 数据 / 迁移 | `spring-boot-starter-jdbc`、`spring-boot-starter-flyway` |
| ES | `spring-boot-starter-elasticsearch`（客户端）与 `spring-boot-starter-data-elasticsearch`（Spring Data）用途不同 |
| 测试 / 开发 | `spring-boot-testcontainers`（**无** `starter-` 前缀）、`spring-boot-docker-compose` |
| MyBatis | **不在 Boot BOM 中**，必须自己钉版本 |

### B.2 Boot 4.1.1 托管版本

| 组件 | 版本 | 组件 | 版本 |
| --- | --- | --- | --- |
| Spring Framework | 7.0.9 | Spring Security | 7.1.1 |
| Spring AMQP | 4.1.1 | Spring Data BOM | 2026.0.1 |
| Flyway | 12.4.0 | Testcontainers | 2.0.5 |
| Elasticsearch Java Client | 9.4.5 | Micrometer | 1.17.1 |
| mysql-connector-j | 9.7.0 | PostgreSQL JDBC | 42.7.13 |
| Lettuce | 7.5.2 | Rabbit AMQP Client | 5.30.0 |
| Jackson 3 | 3.1.5（group `tools.jackson`） | Jackson 2 | 2.21.5（`com.fasterxml.jackson`） |

**注意**：Boot 4 同时管理**两套 Jackson**，Jackson 3 为默认。本项目统一用 Jackson 3，禁止混用两套 API。

### B.3 数据访问层选型

| 方案 | 最新版本 | 状态 |
| --- | --- | --- |
| `org.mybatis.spring.boot:mybatis-spring-boot-starter` | **4.1.0**（2026-07-16） | 官方声明 4.1.x → Spring Boot 4.1 ✅ |
| `com.baomidou:mybatis-plus-spring-boot4-starter` | **3.5.17** | ✅ **实测通过**（2026-09-29，Boot 4.1.1 + Java 21，见 B.7）——其 POM 虽以 Boot 4.0.1 为基线，但实际兼容 4.1.1 |
| `mybatis-plus-spring-boot3-starter` | 3.5.17 | ❌ 仅 Boot 3，禁用 |

**决策（已实测锁定）**：使用 **MyBatis-Plus `mybatis-plus-spring-boot4-starter:3.5.17`**。原本准备了"若 Boot 4.1.1 不兼容就回退 `mybatis-spring-boot-starter:4.1.0`"的退路，**实测通过后该退路不再需要**（证据见 B.7）。

### B.4 中间件镜像与版本（compose）

| 组件 | 选定镜像 | 说明 |
| --- | --- | --- |
| MySQL | `mysql:8.4.11` | 8.4 为 LTS 线；⚠️ `mysql:lts` 标签现指向 **9.7.2**，不要用 |
| PostgreSQL + pgvector | `pgvector/pgvector:0.8.6-pg18-trixie` | 扩展 0.8.6（2026-07-29） |
| Redis | `redis:8.10.2` | **Redis 8 起 Query Engine / JSON / 向量已内建** → 不需要 `redis-stack` |
| RabbitMQ | `rabbitmq:4.3.6-management` | 4.3 为当前稳定线；⚠️ **3.x 已 EOL**（3.13 社区支持止于 2024-09-30） |
| Elasticsearch | `docker.elastic.co/elasticsearch/elasticsearch:9.4.5` | **与 Boot 4.1.1 托管的 ES Java Client 9.4.5 完全同版本** |
| IK 分词插件 | `https://get.infini.cloud/elasticsearch/analysis-ik/9.4.5` | 已确认 `elasticsearch-analysis-ik-9.4.5.zip` 存在于官方发布站；⚠️ **已不在 GitHub Releases 发布**；**版本必须与 ES 完全一致**（每个 ES 版本一个独立 zip，非范围兼容） |

- ES 版本选择依据：Boot 托管客户端为 9.4.5，而 IK 发布站同时提供 9.4.5 与 9.5.4 → 选 **9.4.5** 让「服务端 = 客户端 = 分词插件」三者完全对齐，消除版本偏差这一整类问题。
- pgvector HNSW 维度上限（官方 README）：`vector` ≤ 2000、`halfvec` ≤ 4000、`bit` ≤ 64000 → **`vector(1024)` 安全**。
- 建索引语法：`CREATE INDEX ON vector_store USING hnsw (embedding vector_cosine_ops);`
- 备选中文分词：`analysis-smartcn` 仍随 ES 提供（`bin/elasticsearch-plugin install analysis-smartcn`），作为 IK 出问题时的退路。

### B.5 前端依赖版本（npm latest）

| 包 | 版本 | 备注 |
| --- | --- | --- |
| vue | 3.5.43 | |
| vite | **8.3.1** | 要求 Node `^20.19.0 \|\| >=22.12.0` → 本机 v22.22.0 ✅ |
| typescript | 7.0.2 | |
| element-plus | 2.14.6 | peer `vue ^3.3.7` |
| pinia | 4.0.3 | |
| vue-router | 5.3.1 | |

### B.6 仍未核对（写码前现场验证）

| 项 | 验证方式 |
| --- | --- |
| ~~MyBatis-Plus boot4-starter 在 **Boot 4.1.1** 上的实测~~ | ✅ 已关闭，见 **B.7** |
| ~~OAuth2 资源服务器两种 starter 坐标的取舍~~ | ✅ 已关闭，见 **B.9**（旧坐标已废弃） |
| ~~Spring AI 2.0.1 关键 API~~ | ✅ 已关闭，见 **Appendix D** |

### B.7 实测验证记录（2026-09-29）

| 验证项 | 结论 | 证据 |
| --- | --- | --- |
| `mvn -B clean package`（4 模块 reactor） | ✅ BUILD SUCCESS | Java 21.0.12.1 (Temurin) + Boot 4.1.1 |
| `spring-boot-starter-webmvc` 在 Boot 4.1.1 下可解析 | ✅ 可用 | 无需回退到已废弃的 `spring-boot-starter-web` |
| **MyBatis-Plus 3.5.17 boot4-starter 在 Boot 4.1.1 + Java 21** | ✅ **可用（最大风险关闭）** | `MybatisPlusCompatibilityTest` 通过（1/1，0 失败） |
| Flyway 12.4.0 的 `flyway-mysql` / `flyway-database-postgresql` 由 Boot 托管版本 | ✅ 无需手写版本 | 依赖解析成功 |

MyBatis-Plus 实测 SQL 证据（来自 `nexus-app` 测试日志，一字未改）：

```sql
-- 1) 雪花 ID 回填（IdType.ASSIGN_ID）
INSERT INTO sys_user ( id, username, password_hash, nickname, status ) VALUES ( ?, ?, ?, ?, ? )
  Parameters: 2104750903666438145(Long), lky(String), ...
-- 2) @TableLogic 自动追加 deleted = 0
SELECT id,username,... FROM sys_user WHERE id=? AND deleted=0
-- 3) deleteById 被改写为逻辑删除 UPDATE（不是物理 DELETE）
UPDATE sys_user SET deleted=1 WHERE id=? AND deleted=0
```

**方法论价值（面试可讲）**：MyBatis-Plus 官方只声明支持 Boot 4.0.x，若直接照文档写业务代码，风险会推迟到 Phase 1 才爆发。这里用 **H2 内存库**把验证成本压到最低——在 Docker 尚不可用（Hyper-V 未启用）的情况下，依然完成了依赖兼容性的端到端验证。

**已知待办（不影响 Phase 0）**：`spring-boot-starter-test` 自带的 Mockito 会动态自附加 javaagent 并打印警告（未来 JDK 将禁止）。等 Phase 1 Step 1.8 真正编写 Mock 测试时，按官方建议改为显式 javaagent 配置。

### B.8 Step 0.5 实测验证记录（Web 契约）

| 验证项 | 结论 | 证据 |
| --- | --- | --- |
| Boot 4 的 Jackson 3 自定义序列化方案 | ✅ **`@JacksonComponent` + `ValueSerializer<Long>` 生效** | 雪花 ID 序列化为 `"2104750903666438145"`（带引号） |
| 基本类型计数保持数字 | ✅ | `totalTokens` = `42`（无引号），支撑 ADR-017 的"ID 用 Long / 计数用基本类型"约定 |
| 业务码 → HTTP 状态码映射 | ✅ | `3001 → 404`、`3006 → 413`、`1001 → 400`、`9999 → 500` |
| 参数校验失败带字段级提示 | ✅ | `{"code":1001,"message":"name: name 不能为空"}` |
| 未预知异常不泄漏堆栈 | ✅ | body 中不含 `IllegalStateException` 与异常消息，只给 requestId |
| 请求 ID 透传与日志注入防护 | ✅ | 合法上游 ID 原样回写；含换行符的恶意 ID 被替换为随机 ID |
| Boot 4 的 Web 测试模块拆分 | ⚠️ **踩坑记录** | `@AutoConfigureMockMvc` 已移到独立模块 `spring-boot-webmvc-test`，包名改为 `org.springframework.boot.webmvc.test.autoconfigure`，**不再随 `spring-boot-starter-test` 提供** |
| Spring 7 废弃常量 | ⚠️ **踩坑记录** | `HttpStatus.PAYLOAD_TOO_LARGE → CONTENT_TOO_LARGE`、`UNPROCESSABLE_ENTITY → UNPROCESSABLE_CONTENT`；测试断言 `isPayloadTooLarge() → isContentTooLarge()` |

**测试总览（`mvn -B clean test`）**：`nexus-common` 10 通过 / `nexus-app` 9 通过，**0 失败**。

### B.9 Step 1.1 实测验证记录（认证与 JWT）

| 验证项 | 结论 |
| --- | --- |
| Boot 4 资源服务器 starter 坐标 | ⚠️ **踩坑**：`spring-boot-starter-oauth2-resource-server` 的 POM 描述已写明 *deprecated in favor of* **`spring-boot-starter-security-oauth2-resource-server`**（后者传递引入 `spring-boot-starter-security` + `spring-security-oauth2-jose`） |
| 完整鉴权闭环：注册 → 登录 → 带 Token 访问 `/api/users/me` | ✅ |
| 无 Token / 伪造 Token → 401 + 业务码 2001 + 统一返回体（非 Security 默认响应） | ✅ |
| 重复用户名 → 409 + 业务码 2005 | ✅ |
| 密码错误与用户不存在的提示完全一致（防账号枚举） | ✅ 断言两者 message 相等 |
| RBAC：普通用户访问 `/api/admin/**` → 403 + 2003；授予 ADMIN 后重新登录即可访问 | ✅ |
| 雪花 ID 在注册响应中是字符串、`expiresIn` 是数字 | ✅ |
| 响应体绝不包含密码哈希 | ✅ |
| **真实 HTTP 冒烟**（真实 Tomcat + JDK 原生 `HttpClient`） | ✅ 日志出现 `Tomcat started on port 50388`；中文提示经真实 HTTP 编解码正确 |
| 测试专用安全链隔离 | ✅ `@TestConfiguration` 嵌套类只放行 `/test-only/**`，不会进入生产 jar |

**测试总览（Step 1.1）**：`nexus-common` 10 + `nexus-app` 18 = **28 通过 / 0 失败**。

### B.10 Step 1.2 实测验证记录（模型可替换层）

| 验证项 | 结论 |
| --- | --- |
| `spring.ai.model.chat=none` 是否真能关掉自动配置 | ✅ `AiModelWiringTest` 断言"容器内 `ChatModel` bean 数为 0"通过 |
| 仅关 `chat` 是否足够 | ⚠️ **不够**：`OpenAiAudioSpeechAutoConfiguration` 仍会因缺凭证让上下文启动失败（`At least one credential source must be specified`）→ 六个能力全部关闭，清单见 **D.9** |
| 多 provider 配置绑定 | ✅ `deepseek:deepseek-chat` 被注册；Ollama 的 `qwen3:7b` 按第一个冒号解析、模型名不被截断 |
| 逻辑角色 → ChatClient | ✅ `chat.default` / `chat.agent` / `chat.judge` / `embedding.default` 均可解析，同一引用共享同一实例（惰性缓存） |
| 缺 API Key 的行为 | ✅ 该 provider 被跳过并打 WARN，用到对应角色时抛 4006（而不是拿空 Key 去请求、最后收到供应商晦涩的 401） |
| 能力标签隔离 | ✅ 仅声明 `EMBEDDING` 的模型不会被注册成对话模型 |
| builder 方法名 | ✅ 全部用 `javap` 从 2.0.1 的 jar 读出（见 D.9 表格），无一处凭记忆 |
| 真实模型调用（Step 1.3） | ⏳ `LiveModelCallTest` 已就绪：设置 `AI_CHAT_API_KEY` 后运行，验证内容、**真实 Token 用量**与首 Token 延迟；未设置时自动跳过 |

**测试总览（Step 1.2）**：`nexus-common` 10 + `nexus-ai` 9 + `nexus-app` 22 = **41 通过 / 0 失败**。

### B.11 Step 1.3 真实模型调用实测记录（2026-09-29）

首次让项目真正与大模型对话，全程走 `ChatClientFactory` 的逻辑角色（而非直连 SDK）。

| 项 | 实测值 |
| --- | --- |
| 网关 / 模型 | OpenCode Zen `https://opencode.ai/zen/go/v1` → `opencode:deepseek-v4-flash` |
| 非流式调用 | content=`收到指令`；prompt=91，completion=13，**total=104**（访问器计算正确），latency=**1624ms** |
| 流式调用 | 分片数 > 0，拼接内容非空，首 Token 延迟 ≤ 总耗时（断言通过） |
| Token 用量访问器 | ✅ 运行时验证（此前只从 `Usage.java` 源码读出） |
| 附加请求头是否真的下发 | ✅ 未下发时网关会返回 400 MissingSessionID，实测通过 |
| 测试守卫 | ✅ 未配置 `AI_CHAT_API_KEY` 时自动跳过（`@EnabledIfEnvironmentVariable`），CI 与无网环境不失败、不消耗 Token |

**测试总览（Step 1.3）**：`nexus-common` 10 + `nexus-ai` 9 + `nexus-app` 24 = **43 通过 / 0 失败 / 0 跳过**。

**顺带修掉一个工程问题**：测试日志里的中文一直是乱码，排查后发现是两处叠加：

1. Java 19 起决定 stdout 编码的是 `stdout.encoding`（**不是** `file.encoding`），中文 Windows 上默认 GBK；
2. 用管道捕获子进程输出时，PowerShell 5.1 会按 OEM 代码页解码，于是 UTF-8 字节被当 GBK 读。

工程上的正确修法是**显式声明**而不是依赖默认值：surefire 增加
`-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8`，应用侧设置 `logging.charset.console: UTF-8`。
（终端本身也需为 UTF-8：Windows Terminal 默认即是，旧 conhost 需先 `chcp 65001`。）

### B.12 Step 1.4 实测验证记录（会话与消息持久化）

| 验证项 | 结论 |
| --- | --- |
| 会话 CRUD（建 / 列 / 详情 / 改名+置顶 / 删除） | ✅ |
| mode 宽松解析（`rag` → `RAG`，缺省 → `PLAIN`）与非法值 → 400/1001 | ✅ |
| **多租户隔离**：B 访问 A 的会话返回 **404 + 8001**（不是 403，避免泄露资源存在性） | ✅ 详情 / 删除 / 消息列表三处都断言 |
| 消息按时间正序（雪花 ID 天然有序）、角色正确、Token 与延迟字段落库 | ✅ |
| 会话冗余字段：`message_count` 用 SQL 自增、`last_message_at`、首条用户消息自动生成标题 | ✅ |
| **分页真的生效**（25 条 / 每页 10 → 3 页；`pageSize=99999` 截断为 100） | ✅ 专门用来钉住分页插件 |
| 删除会话时其消息被物理清理 | ✅ |

**两个真实踩坑（都值得记住）**

1. **MyBatis-Plus 自 3.5.9 起把 JSqlParser 拆成了独立模块**：`PaginationInnerInterceptor`
   已从 `mybatis-plus-extension` 移到 **`mybatis-plus-jsqlparser`**，只引 starter 会直接编译失败。
   更隐蔽的变体是：**类有了但没注册拦截器** —— 此时 `selectPage` 不报错，
   只是安静地把 SQL 变成不带 LIMIT 的全表查询（接口"正常"，实则全表扫描 + 全量返回）。
   本项目因此显式注册拦截器，并用"每页 10 条"的断言把它钉死。
2. **校验规则过严的代价**：用户名最初只允许 `[A-Za-z0-9_]`，导致测试里 `conv-lifecycle`
   这类含连字符的名字注册被 400 拦下，而症状表现为**后续登录 401**（排查成本高，本项目已第二次踩）。
   已放宽为允许连字符（GitHub / GitLab 同样允许），并让测试辅助方法**显式断言注册状态**，
   避免"失败延迟表现"这种最难查的形态。

**测试总览（Step 1.4）**：`nexus-common` 10 + `nexus-ai` 9 + `nexus-app` 30 = **49 通过 / 0 失败**。

### B.13 Step 1.5 实测验证记录（ChatMemory 多轮对话）

| 验证项 | 结论 |
| --- | --- |
| 记忆仓库是 JDBC 实现（非默认内存实现） | ✅ `JdbcChatMemoryRepository` |
| 写入后可读回、按时间顺序、`clear` 生效 | ✅ |
| 窗口超限淘汰最老消息、保留最新 | ✅ |
| **真实多轮对话**：第二轮说出第一轮给出的事实（"我的名字是 LKY"） | ✅ `MultiTurnMemoryTest`（真实模型调用） |
| 记忆顾问的"写"生效（本轮问答应出现在记忆仓库中） | ✅ |
| 完整历史（`chat_message`）同时记录两轮问答共 4 条 | ✅ |

**四个真实踩坑**

1. **`SPRING_AI_CHAT_MEMORY.conversation_id` 是 `VARCHAR(36)`**。最初把记忆 key 设计成
   `user:{userId}:conv:{conversationId}`，对 6 位用户 id + 19 位雪花 id **恰好 36 字符**——
   正好卡在边界上，用户 id 位数再多就会在运行期以数据库异常炸掉（由一条离线测试暴露）。
   纠正：key 简化为 `conv:{conversationId}`（最大 24 字符），并用测试钉住长度不变量。
   **教训：会话 id 已全局唯一、归属校验已在服务层完成，多拼 user 前缀不提供真实保护，
   却引入了列宽溢出这个真实故障模式——"看起来更安全"的设计往往更脆弱。**
2. **`finishReason` 不在 `ChatResponseMetadata` 上**（后者只有 `usage` / `model` / `id` / `rateLimit`），
   而在 `response.getResult().getMetadata().getFinishReason()`。
3. **记忆仓库类的包名**：`org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository`
   （记忆仓库是独立 artifact；`ChatMemoryRepository` / `InMemoryChatMemoryRepository` 在 `spring-ai-model`）。
4. **记忆顾问必须按调用挂载**，不能作为 ChatClient 的默认顾问：
   `MessageChatMemoryAdvisor` 要求每次调用都带 `ChatMemory.CONVERSATION_ID`，
   否则抛 `IllegalArgumentException`。评测裁判、字段抽取这类无会话的调用不该被迫造一个会话 id。

**一条刻意的设计选择**：`ChatServiceImpl.ask` **不加 `@Transactional`**——
模型调用是秒级外部 IO，把数据库事务包在它外面会长时间占用连接（常见性能事故）。
落库由 `ChatMessageService` 内部各自的小事务完成。

**测试总览（Step 1.5）**：`nexus-common` 10 + `nexus-ai` 9 + `nexus-app` 35 = **54 通过 / 0 失败**。

### B.14 Step 1.6 实测验证记录（SSE 流式与停止生成）

| 验证项 | 结论 |
| --- | --- |
| SSE 事件顺序：`meta` → `delta`* → `usage` → `done` | ✅ |
| 流式内容拼接与落库（`DONE`、内容非空、`latencyMs` 已写入） | ✅ |
| **流式也能拿到 Token 用量**（Phase 10 的关键指标） | ✅ 实测 `prompt=94 / completion=76 / total=170`（=94+76）、`firstTokenMs=2269ms`、`latencyMs=2385ms`；共 12 个分片。**无需**额外开启 `streamUsage`，兼容网关默认就回传 usage |
| **停止生成**：收到 3 个分片后调用停止接口 | ✅ 上游订阅被 `dispose()`（不是只关连接，否则 Token 继续烧）、部分内容以 `STOPPED` 落库、`done` 事件带 `"stopped":true` |
| 先插 `STREAMING` 再更新不会让消息计数翻倍 | ✅ 一轮问答始终是 2 条消息 |
| 未认证 → 401 / 空内容 → 400+1001 / 会话不存在或非本人 → 404+8001 | ✅ 错误走统一 JSON，**不会"开一条 SSE 再报错"** |
| 越权停止他人的流 / 停止不存在的流 | ✅ 分别返回 `false`（越权被拒）与 `false`（幂等，不算错误） |

**一个刻意的取舍**：停止信号目前是**单实例内存实现**
（`InMemoryStreamCancellationRegistry`），因为 Redis 尚未接入（依赖 Docker，见 Step 0.4）。
接口已抽出，将来加 Redis 实现可支持多实例部署，业务代码零改动。

**已知未做（留待 Phase 11）**：SSE 心跳（每 15 秒注释行）与反向代理侧的
`proxy_buffering off` / `proxy_read_timeout` 配套配置。当前只有 5 分钟的 emitter 超时，
无反向代理时不影响使用。

**测试总览（Step 1.6）**：`nexus-common` 10 + `nexus-ai` 9 + `nexus-app` 45 = **64 通过 / 0 失败**。

### B.15 让应用不依赖 Docker 也能跑起来（standalone profile）

**背景**：Docker 因 WSL2 / Hyper-V 未启用而不可用（根因见 architecture.md 第 19 节），
但"应用真的能跑"是本项目第一优先级，因此补一个零依赖的开发 profile。

- 新增 `application-standalone.yml`：H2 内存库 + 共用 `db/h2/schema.sql` + 关闭 Flyway + 打开 H2 控制台
- H2 依赖从 `test` 提升为 `runtime`（生产镜像在 Phase 11 排除）
- `StandaloneDevSecurityConfig` 用 `@Profile("standalone")` 放行 `/h2-console/**`：
  **生产环境根本不会加载这个 Bean**——把能直接执行 SQL 的控制台暴露到公网等于交出数据库
- 测试 profile 与 standalone **共用同一份建表脚本**，避免"测试用的表结构"与"本地开发用的表结构"漂移
- `scripts/smoke.ps1`：一键冒烟（注册 → 登录 → 建会话 → 非流式问答 → 流式+停止 → 查历史）

**两个 Windows 环境坑（都值得记住）**

1. **PowerShell 5.1 读取无 BOM 的 UTF-8 `.ps1` 会按 GBK 解析**，含中文注释的脚本直接语法报错
   （`Unexpected token ...`）。含非 ASCII 的 `.ps1` 必须保存为 **UTF-8 with BOM**。
2. **`Invoke-RestMethod`（PS 5.1）在响应头没有 charset 时按 ISO-8859-1 解码 JSON**，
   中文回答在控制台显示为乱码——**服务端是对的**（JSON 按 RFC 8259 即 UTF-8，浏览器 `fetch` 也按 UTF-8）。
   测试脚本改为显式 UTF-8 解码，避免"客户端显示问题被误判成后端 bug"。

**实测**：应用 **7.22 秒**启动（`Tomcat started on port 8080`），
非流式问答 `tokens=93/83/176, latency=1158ms`；流式收到第 3 个分片后停止成功，
`done` 事件带 `stopped:true`，被停止消息以 `STOPPED` 落库且保留部分内容；
`messageCount` 与消息条数一致（无重复计数）。

### B.16 Step 0.4 + 0.6 实测验证记录（Docker 基础设施 + Flyway 真实建表）

**基础设施全部就绪**（`deploy/docker-compose.yml`）：

| 服务 | 端口 | 实测 |
| --- | --- | --- |
| MySQL | 3307→3306 | 8.4.11，`nexus` 库字符集 `utf8mb4` |
| PostgreSQL + pgvector | 5433→5432 | 19/pg18 镜像，`vector 0.8.6` + `hstore` + `uuid-ossp` 扩展就绪 |
| Elasticsearch + IK | 9200 | 9.4.5，`analysis-ik 9.4.5` 插件已装（日志 `-> Installed analysis-ik`） |
| Redis | 6379 | 8.10.2，`PONG` |
| RabbitMQ | 5672 / 15672 | 4.3.6-management |

**Flyway 真实建表**：V1 账号权限 / V2 会话消息 / V3 Spring AI 记忆表 三个迁移全部 `success=1`，
共 7 张表；预置角色中文存储正确（`普通用户` / `管理员`）。
冒烟测试在真实 MySQL 上全通过（含流式与停止生成，STOPPED 消息保留部分内容）。

**五个真实踩坑（都是"看起来正常其实错了"的类型）**

1. **PostgreSQL 18+ 镜像改了数据目录布局**：v18 起必须把单个卷挂在 `/var/lib/postgresql`
   （数据落在 `<mount>/18/docker`），仍然挂旧的 `/var/lib/postgresql/data` 会让容器**启动即失败并进入重启循环**。
   官方报错已直接给出建议：*"in 18+ … place a single mount at /var/lib/postgresql"*。
2. **Boot 4 又一处自动配置模块拆分**：只引 `flyway-core` **没有自动配置**，
   表现为"应用照常启动、连上了数据库、但迁移从未执行"（表一直建不出来，且没有任何报错）。
   必须引 `spring-boot-starter-flyway`。这与之前 `spring-boot-webmvc-test`、`spring-boot-jackson` 是同一类问题：
   **Boot 4 的 starter 与自动配置模块是分离的，引错就静默失效**。
3. **H2 在 runtime 作用域会导致"静默退回内存库"**：当某个 profile 没有配置 `spring.datasource.url` 时，
   Boot 的 `DataSourceAutoConfiguration` 会启用嵌入式数据库自动配置，于是应用正常启动、接口正常работа，
   但数据写进了进程内存（重启即丢）。已加 `DatasourceGuard`：local / prod 下强制校验连的是 MySQL，否则启动失败。
4. **端口冲突**：本机已有原生 MySQL 8.0（服务 `MySQL80`，占 3306）与 PostgreSQL 18（`postgresql-x64-18`，占 5432），
   因此容器改用 3307 / 5433，避免与已有项目互相干扰（可用 `deploy/.env` 覆盖）。
5. **jar 是快照**：新增 profile 配置与迁移脚本后必须重新 `package`，
   否则"代码改了、跑起来还是旧的"（本次即因此先失败了一次）。

**新增可运行性**：`localhost:8080/actuator/health = UP`，`local` profile 全程走真实 MySQL；
`standalone` profile 仍可在没有 Docker 时用 H2 跑通全链路（见 B.15）。

---

## Appendix C. 已确认的选型（用户拍板，2026-09-24）

| # | 决策项 | 结论 |
| --- | --- | --- |
| 1 | 模块结构 | **采用 Maven 多模块**（`nexus-common` / `nexus-knowledge` / `nexus-app`，Phase 7 加 `nexus-mcp-server`）→ ADR-001 生效 |
| 2 | 默认模型方案 | **对话走云端 OpenAI-compatible API**（DeepSeek 起步，可换 Qwen/智谱）；**嵌入走本地 Ollama `bge-m3`**（1024 维，免费离线）；裁判模型复用云端（可指定更便宜的模型） |
| 3 | 工作目录 | 维持 `D:\develop\Prisdvl-Nexus`（会话已切换为全量访问，shell 可直接执行） |
| 4 | 错误码分段 | 按分段方案：**AI 模型/嵌入/向量 = `4xxx`**（不照抄需求示例中的 `5001`），`5xxx` 保留给工具 / Agent / MCP |

由决策 2 派生的固定约束：

- `kb_knowledge_base.embedding_model = bge-m3`、`embedding_dim = 1024`、pgvector 列类型 `vector(1024)`（ADR-008 冻结）。
- compose 需要 `ai-local` profile 拉起 `ollama`，并在首次启动后 `ollama pull bge-m3`。
- 云端对话模型必须支持 **Tool Calling**（Agent 阶段的前提），选型时优先验证该项。

---

## Appendix D. Spring AI 2.0.1 关键 API 核对（源码级，2026-09-24）

> 来源：`github.com/spring-projects/spring-ai` tag `v2.0.1` 的源码与文档（`raw.githubusercontent.com` / `api.github.com` + docs.spring.io 2.0.1）。
> **写码时直接用这里的签名，不要凭记忆写 1.x 的名字。**

### D.1 ChatClient 流式（直接决定 SSE 实现）

| 需求 | 2.0.1 确切 API |
| --- | --- |
| 纯文本流 | `chatClient.prompt().user(x).stream().content()` → `Flux<String>` |
| 需要 usage / metadata | `.stream().chatResponse()` → `Flux<ChatResponse>` |
| 需要执行上下文 | `.stream().chatClientResponse()` → `Flux<ChatClientResponse>` |

三者**都存在**，不是二选一。⚠️ **没有** `.stream().usage()` 这类终结方法 → 用量必须从流末尾的 `ChatResponse` 上取。

### D.2 Token 用量（观测埋点，最容易照抄错的地方）

`org.springframework.ai.chat.metadata.Usage` 是**接口**，且只有**扁平 getter**：

```java
Integer getPromptTokens();          // 注意：不是 getInputTokens()
Integer getCompletionTokens();      // 注意：不是 getOutputTokens()
default Integer getTotalTokens();   // 默认 prompt + completion
default Long getCacheReadInputTokens();   // 2.0 新增，缓存命中计价用
default Long getCacheWriteInputTokens();  // 2.0 新增
```

取用路径：`chatResponse.getMetadata().getUsage()`；`ChatResponseMetadata` 另有 `getModel()`、`getId()`、`getRateLimit()`。

### D.3 评测（Phase 9 可直接用）

| 类 | 包 | 所属 artifact |
| --- | --- | --- |
| `Evaluator` / `EvaluationRequest` / `EvaluationResponse` | `org.springframework.ai.evaluation` | **`spring-ai-commons`**（在 BOM 内） |
| `RelevancyEvaluator` | `org.springframework.ai.chat.evaluation` | **`spring-ai-client-chat`**（在 BOM 内） |
| `FactCheckingEvaluator` | 推测同包（FQCN 未核实） | `spring-ai-client-chat` |

构造方式：`new RelevancyEvaluator(ChatClient.builder(chatModel))`，`RelevancyEvaluator` 还支持 `.promptTemplate(...)`。
→ 用我们 `AiModelRegistry` 的 `chat.judge` 角色构造 `ChatClient.Builder` 即可，与 ADR-003 无缝衔接。

### D.4 多 provider 手工构建模型（ADR-003 可行性确认）

- ✅ `OpenAiChatOptions.Builder.baseUrl(String)` **存在**（另有 `getBaseUrl()`、`apiKey(ApiKey)`、`customHeaders`、`extraBody`）→ 手工构建 OpenAI-compatible 模型**可行**，不必依赖属性。
- 属性侧：`spring.ai.openai.base-url`、`spring.ai.openai.chat.base-url`（优先级更高）、`spring.ai.openai.chat.model`（默认 `gpt-5-mini`）、`.chat.temperature`（0.8）、`.chat.extra-body.*`。
- ⚠️ `spring.ai.openai.chat.completions-path` **不存在**（2.0 改用官方 `openai-java` SDK）→ 若第三方服务的路径不是标准 `/v1/chat/completions`，**不能靠配置解决**，需改用其 compatible 端点或换 provider。
- 禁用自动配置：`spring.ai.model.chat=none`、`spring.ai.model.embedding=none`（**embedding 侧同样有效，已确认**）。

### D.5 Ollama（本地嵌入 bge-m3）

扁平属性（**没有** `.options` 段）：
`spring.ai.ollama.base-url`（默认 `http://localhost:11434`）、`spring.ai.ollama.chat.model`（默认 mistral）、`.chat.temperature`（0.8）、`spring.ai.ollama.embedding.model`（默认 `mxbai-embed-large`）、`.embedding.keep_alive`、`.embedding.truncate`。

**省事的一条**：`spring.ai.ollama.init.pull-model-strategy=when_missing` + `spring.ai.ollama.init.embedding.additional-models=bge-m3` → 应用启动时自动拉取模型，省掉手工 `ollama pull`。

⚠️ 官方 Ollama 文档里同一页的示例 YAML 仍写着 `.options.model`，与属性表自相矛盾 → **以属性表（扁平）为准**。

### D.6 工具调用（ADR-009 可行性确认）

| 需求 | 2.0.1 确切 API |
| --- | --- |
| 单次调用关闭自动工具循环 | `AdvisorParams.toolCallingAdvisorAutoRegister(false)`（`org.springframework.ai.chat.client.AdvisorParams`） |
| 全局关闭 | `spring.ai.chat.client.tool-calling.enabled=false` |
| 手工执行工具 | `ToolCallingManager.builder().build()` → `executeToolCalls(prompt, chatResponse)` → `ToolExecutionResult.conversationHistory()` |
| 收集工具定义 | `ToolCallbacks.from(bean)` → `ToolCallback[]`（`org.springframework.ai.support.ToolCallbacks`） |
| 读工具元信息 | `ToolCallback.getToolDefinition()` → `name()` / `description()` / `inputSchema()`（**record 风格，不是 getXxx()**） |
| 调用限额 | `ToolCallingManager.builder().maxCallsPerTool(40).maxTotalToolCalls(150).onLimitExceeded(...)`；属性 `spring.ai.tools.limits.*` |

**关键结论**：`ToolMetadata` 只有 `returnDirect()`，**2.0.1 完全没有权限 / 风险元数据 API**。
→ 工具的 `READ / WRITE / DANGEROUS` 分级、是否需人工确认，**必须由我们自己的 `ai_tool_registry` + `ToolPermissionChecker` 维护**。这从框架层面证实了 ADR-009 与 `ai_tool_registry` 表设计的必要性（面试可直接讲这一点）。

### D.7 MCP（Phase 7）

| 用途 | 坐标 / 配置 |
| --- | --- |
| client（STDIO + JDK HttpClient 的 SSE/Streamable-HTTP） | `spring-ai-starter-mcp-client` |
| client（WebFlux 传输） | `spring-ai-starter-mcp-client-webflux` |
| server（STDIO） | `spring-ai-starter-mcp-server`（`spring.ai.mcp.server.stdio=true`） |
| server（Servlet / MVC） | **`spring-ai-starter-mcp-server-webmvc`** ← 本项目使用 |
| server（WebFlux） | `spring-ai-starter-mcp-server-webflux` |
| 协议选择 | `spring.ai.mcp.server.protocol=STREAMABLE`（Streamable-HTTP）或 `=STATELESS`；`SSE` 自 2.0.0 起废弃 |
| 服务端注解 | `@McpTool` → `org.springframework.ai.mcp.annotation.McpTool`（同包：`@McpToolParam`、`@McpResource`、`@McpPrompt`） |
| 客户端按 URL 连接 | `spring.ai.mcp.client.streamable-http.connections.<name>.url`（`.endpoint` 默认 `/mcp`） |
| 客户端通用 | `.request-timeout`（默认 20s）、`.type=SYNC\|ASYNC`、`.toolcallback.enabled` |

**关键结论**：**没有**独立的 stateless starter，协议靠属性切换；MCP 工具以 `SyncMcpToolCallbackProvider` 暴露且**不会自动注册到 ChatClient**，必须显式 `.tools(...)` 传入 → 与我们的 `ToolRegistry` 统一汇聚设计一致。

### D.8 仍未核实（不影响设计，写码时顺手确认）

- `FactCheckingEvaluator` 的确切 FQCN（推测 `org.springframework.ai.chat.evaluation`）
- `@ToolParam` 的确切 FQCN（同 `org.springframework.ai.tool.annotation` 包）
- Elasticsearch **Docker Hub 官方**镜像 tag 字符串（本项目用 `docker.elastic.co/...` 官方仓库，不受影响）

### D.9 关闭模型自动配置的**完整**开关清单（实测补充，2026-09-29）

ADR-003 要求关掉 Spring AI 的模型自动配置、由 `AiModelRegistry` 手工构建多 provider 模型。
**只关 `chat` 是不够的**——实测启动报错：

```
Error creating bean with name 'openAiSdkAudioSpeechModel' defined in class path resource
[org/springframework/ai/model/openai/autoconfigure/OpenAiAudioSpeechAutoConfiguration.class]:
At least one credential source must be specified: credential (apiKey), workloadIdentity, or adminApiKey
```

完整清单（由 `spring-ai-autoconfigure-model-openai-2.0.1.jar` 中各 `AutoConfiguration` 的
`@ConditionalOnProperty` 读出，六个都必须显式设为 `none`）：

| 属性 | 对应自动配置类 |
| --- | --- |
| `spring.ai.model.chat` | `OpenAiChatAutoConfiguration` |
| `spring.ai.model.embedding` | `OpenAiEmbeddingAutoConfiguration` |
| `spring.ai.model.image` | `OpenAiImageAutoConfiguration` |
| `spring.ai.model.audio.speech` | `OpenAiAudioSpeechAutoConfiguration` |
| `spring.ai.model.audio.transcription` | `OpenAiAudioTranscriptionAutoConfiguration` |
| `spring.ai.model.moderation` | `OpenAiModerationAutoConfiguration` |

**builder 方法名核对结果**（用 `javap` 从 2.0.1 的 jar 直接读出，不凭记忆）：

| 用途 | 确切签名 |
| --- | --- |
| OpenAI 兼容对话模型 | `OpenAiChatModel.builder().options(OpenAiChatOptions).build()` |
| OpenAI 兼容选项 | `OpenAiChatOptions.builder().baseUrl(String).apiKey(String).model(String).temperature(Double).build()` |
| Ollama 对话模型 | `OllamaChatModel.builder().ollamaApi(OllamaApi).options(OllamaChatOptions).build()` |
| Ollama API | `OllamaApi.builder().baseUrl(String).build()` |
| ChatClient | `ChatClient.builder(ChatModel).defaultSystem(...).defaultAdvisors(...).defaultTools(...).build()` |

### D.10 接入真实网关时的三个"兼容性陷阱"（2026-09-29 实测）

以 OpenCode Zen（`https://opencode.ai/zen/go/v1`）为例——它是标准 OpenAI 兼容网关，但仍有三点必须处理：

1. **要求额外请求头**。缺少时直接 400：
   ```json
   {"type":"error","error":{"type":"MissingSessionID",
    "message":"Request is missing x-opencode-session and cannot be routed efficiently."}}
   ```
   官方要求：客户端"用自己的 User-Agent 标识自己，而不是通用 SDK / HTTP 库的名字"，
   并且"为每个会话发送稳定的 `x-opencode-session`"（用于路由优化与 prompt 缓存）。
   → 已为 provider 增加 `headers` 配置，经 `customHeaders(Map)` 下发（ADR-019）。

2. **同一网关内不同模型走不同协议端点**（官方 Endpoints 表）：

   | 端点 | 协议 | 代表模型 |
   | --- | --- | --- |
   | `/chat/completions` | OpenAI 兼容 | `deepseek-v4-flash`、`glm-5.3`、`kimi-k2.6`、`longcat-2.0`、`mimo-v2.6-flash` |
   | `/responses` | OpenAI Responses API | `grok-4.7`、`gpt-6-luna`、`muse-spark-*` |
   | `/messages` | **Anthropic 协议** | `qwen3.8-flash`、`qwen3.8-max`、`minimax-m3` |

   → 选模型前必须先确认协议。选到 Anthropic 协议的模型，就要换成 Spring AI 的 Anthropic 接入模块，
   而不是我们当前的 OpenAI 兼容模块。**这正是"模型可替换"必须连协议一起抽象的原因。**

3. **`max_tokens` 可能不生效**。实测传 `max_tokens=32` 却返回了 684 个 completion token
   （推测是推理型模型的隐藏思考 token 计入）。结论：**不能把 max_tokens 当成本控制手段**，
   成本控制要靠 Phase 10 的使用量看板与配额。
