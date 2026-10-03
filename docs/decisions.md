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
   > ⚠️ **2026-10-02 部分修正**：为了"改密码后旧令牌立即失效"，`jwtDecoder` 现在多挂了一个
   > `TokenVersionValidator`，正常情况下**每个请求会多一次 Redis GET**（缓存未命中或 Redis 故障时查一次库）。
   > 权限仍然只来自 Token 声明，这条没有变；变的是"完全不查库"不再成立。见 ADR-020。
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

## ADR-020 令牌吊销用"用户级版本号"，不用黑名单；三个关注点各自定降级方向

**背景**：ADR-018 第 8 条之后，改密码是一个"必须真的生效"的需求——旧令牌不能继续用。
但 ADR-018 是**无状态** JWT，本身没有撤销机制（当时写的是"Redis 黑名单 + jti，Phase 11 落地"，
`jti` 声明预置了但从未被存储或校验）。同时本期要加登录失败限制与每日配额，两者都要一个存储，
而项目的既有范式是"端口 + 内存实现，Redis 作为可选实现"，不能让 `standalone` / `test` 依赖 Redis。

**决策**

1. **吊销粒度 = 用户级版本号，不是 jti 黑名单**。`sys_user.token_version`（V18）自增，
   令牌里带 `ver` claim，校验时比对。理由：黑名单要存到**每支令牌的过期时刻**，键的数量随
   登录次数线性增长，且"改密码"这个唯一场景要的是"全部作废"，黑名单表达它反而要多一步枚举。
   版本号的键是**每个用户一个**，且天然表达"所有设备一起下线"这个正确语义。
2. **`ver` 缺失按 0 处理** → 本次改动之前签发的令牌全部继续有效，不会集体下线。
3. **缓存失败回落查库，不是 fail-open/closed 二选一**。`UserAuthCache` 是 cache-aside：
   Redis GET 未命中或抛异常 → 查 `sys_user` 并回填。**DB 是事实来源，缓存只是加速**，
   所以故障时降级成"每请求查一次库"，依然正确。这比两种 fail 策略都好，但代价必须说清楚（见下）。
4. **改密码时 invalidate 而不是写穿**：写穿需要"读旧值 → 写新值"，两个并发改密请求会把
   旧版本写回去，等于吊销失效。invalidate 之后下一次读必然回源。
5. **降级方向按关注点分开定**（不是一刀切，也不是遗漏）：
   - 登录失败限制、分钟级限流 → **fail-open**：存储一挂，全体用户被锁在门外，代价远大于少限几次；
   - 每日配额 → **fail-closed**：存储一挂就是无限烧钱，宁可报 `7002`（文案区分"已用尽"与"服务不可用"）；
   - 令牌版本 → **回落查库**（见上）。
6. **改密时原密码错误返回 `1001 / 400` 而不是 `2006 / 401`**：调用方已通过认证，
   回 401 会被前端全局拦截器解读为"会话过期"并清 token 跳登录页，把输入错误升级成会话中断。
   这是**已认证场景**与**登录场景**的刻意区分，`verify-security.mjs` 有一条断言专门守它。

**代价 / 诚实边界**

- **每个请求多一次 Redis GET（故障时是一次查库）**：这与 ADR-018 第 4 条"无状态鉴权不在每个请求上查库"
  相反，是本条决策的直接代价，不是疏忽。ADR-018 第 4 条已就地标注并回链到这里。
- **配额可被绕过**：Redis 重启 / `allkeys-lru` 淘汰会丢当日计数；多实例下内存实现不共享
  （因此多实例必须开 `nexus.security.redis-enabled`）。要真正可靠得把当日用量落到
  `sys_user_daily_usage` 之类的表，本期不做。
- **账号 `status` 改动与软删除不会立即吊销已签发令牌**：本期只校验 `token_version`
  （软删除时 `selectById` 为 null 会被拒，但仅改 `status` 的令牌会活到过期）。已知缺口。
- **登录冻结按 username 维度 ⇒ 可被用来定向刷锁**某个账号（拿错密码刷到阈值）。
  按需求以 username 为键，窗口取短（15 分钟）而不是几小时，宁可让攻击者多试几次。
  跨用户名的分布式猜测不在覆盖范围内。
- **`/api/auth/register` 的 IP 限流不信任 `X-Forwarded-For`**：反向代理后 `getRemoteAddr()`
  可能是代理地址，届时该维度会退化成"全局一个桶"。这是**不知道就不猜**的取舍，不是遗漏。
- **token 记账是尽力而为**：模型已经调用，无法回滚；供应商不回传 usage 时记不到
  （靠 `quota.usage.unattributed` 让缺口可见，而不是静默少计）。`LlmReranker` 与 embedding
  不计入用户配额——前者被显式标成系统调用，否则那个缺陷计数会被正常流量打满而失去意义。

---

## ADR-021 Token 从 localStorage 迁到 HttpOnly Cookie：**本期只出设计，不改代码**

**背景**：用户要求"分析 localStorage 的 XSS / Token 泄露风险，给出 HttpOnly Cookie 升级方案，
**但不要直接大改**"。所以这是一份**设计记录**，本期代码零改动，缺口如实登记。

**现状与风险**

- 令牌存在 `localStorage['nexus.token']`（`web/src/api/client.ts:33`），
  用户名同样在 `localStorage['nexus.username']`。
- **任意一次 XSS 即可读走一支 30 分钟有效的令牌**并离线使用。前端已经把 Markdown 输出
  过 `marked + DOMPurify`（`web/src/utils/markdown.ts`），但"渲染层已消毒"不等于"没有 XSS"——
  依赖供应链、第三方脚本、内联事件处理器都不在 DOMPurify 的覆盖范围内。
- 今天的缓解只有 **access token TTL = 30 分钟**，且**没有 refresh token、没有登出**
  （见 `docs/api.md` §2.2），所以令牌一旦被偷，30 分钟内无法主动止损。

**Cookie 不是"白拿的安全"**——迁移必须一起处理三件事，否则是拿 XSS 换 CSRF：

1. `SecurityConfig` 关 CSRF 的**前提正是"不用 Cookie"**（ADR-018 第 2 条：纯 Token API
   不存在 CSRF 的适用场景）。换成 Cookie 之后这个前提消失，`CookieCsrfTokenRepository` 必须加回来。
2. `HttpOnly` + `Secure` + `SameSite` 三者的组合要按部署形态定：
   `SameSite=Lax` 能挡住跨站表单提交，但前端与后端不同源时（Vite dev 代理、独立域名的生产部署）
   还要 `withCredentials` 与 CORS `allow-credentials` 一起配。
3. 前端 401 处理（`client.ts` 拦截器）要从"清 localStorage 跳登录页"改成
   "回调 `/auth/refresh` 再重放原请求"，否则每一个浏览器重启都会把用户踢出去。

**分阶段迁移路径**（本期均不实施）

| 阶段 | 内容 | 前置 |
| --- | --- | --- |
| ① | 登录时**同时**下发 `HttpOnly + Secure + SameSite=Lax` Cookie，保留 `Bearer` 头路径 | 无 |
| ② | 后端加自定义 `BearerTokenResolver`（**请求头优先、Cookie 兜底**），使两种客户端都能用 | ① |
| ③ | 前端去掉 localStorage 读写，改为 `withCredentials` | ② |
| ④ | 开 `CookieCsrfTokenRepository`，前端读 `XSRF-TOKEN` 并回填 `X-XSRF-TOKEN` | ③ |
| ⑤ | 缩短 access TTL + 引入 refresh token（**需要 `sys_refresh_token` 表**：存哈希、可轮换、可吊销） | ④ |

**诚实边界**：本期不做，所以 **localStorage 的 XSS 暴露面依然存在**，
只是被 30 分钟的 TTL 限了窗口。这一点登记在 `docs/productization-gap-analysis.md` 里，
而不是假装它被这一期顺手解决了。

---

## ADR-022 多库检索的并行与失败隔离收在一个独立执行器里，不写进 Retriever 也不散在门面

**背景**：Phase 5.5 让一次问答能绑多个知识库，但那条路是**逐库串行**的
（3 个库 = 3 次 ES + 嵌入 + pgvector + 回表，尾延迟相加），而且**没有失败隔离**：
任一库抛异常，整次检索变成 4005。5.5.1 要把这两条补上。

**决策**：新增 `MultiKnowledgeRetriever`（nexus-knowledge），唯一的职责是
"给定 N 个库和一个逐库动作，并行跑完、按输入顺序装配、把失败隔离并如实记下来"。
RAG 门面（`ChunkSearchServiceImpl.searchManyDetailed`）与 Agent 工具（`KnowledgeSearchTool`）
共用它。它**不实现 `Retriever` 端口**。

**备选与为什么不选**

| 备选 | 不选的原因 |
| --- | --- |
| 在 `HybridRetriever` 等三个策略内部各自并行 | 策略的语义是"**一个库**怎么召回"（BM25/向量/RRF 只在库内有意义）。把扇出塞进去，"几个库"这件事就有三个实现，而失败表达还得分三份对齐——正是 `ChunkSearchServiceParityTest` 当初存在的理由 |
| 门面里直接写 `CompletableFuture.allOf` | 两处调用方（RAG + Agent）会各写一遍，而"超时算失败还是算空结果""按输入顺序还是完成顺序装配""全部失败怎么报"这三个判断**每处都要重新做对一次**，且做错时不报错 |
| `@Async` + `Future` | 依赖 Spring 的线程池与代理；单库路径会**被迫**切线程（异步注解无法表达"只有 N>1 才异步"），而单库路径的运行时行为必须逐字段不变（`ChunkSearchServiceParityTest` 钉着） |
| `parallelStream()` | 用的是 ForkJoinPool.commonPool，并发度与虚拟线程无关，且**没有任何超时手段**；阻塞式的 ES/JDBC 调用会占死公共池 |
| `StructuredTaskScope`（JDK 21 孵化） | 需要用 `--enable-preview` 编译整个模块。本项目的 JDK 21 是"目标版本"，不是"预览实验场" |
| 给扇出加"并行度"配置 | 假旋钮：绑库上限本身是 10（`AgentView.vue` 的 `multiple-limit`），虚拟线程又便宜。只留一个超时（`nexus.retrieval.fanout-timeout`） |

**四条定死的口径**（写在这里，是因为它们属于"决定"，不属于实现）

1. **单库不切线程**：库数 == 1 就在调用线程上直接执行。这不是优化，是**契约**——
   单库路径的线程、异常、顺序必须和改造前完全一致。
2. **按输入顺序装配，不按完成顺序**。两处下游对顺序敏感：`RecallSupport.merge` 是稳定排序，
   `KnowledgeSearchTool` 的 `all.sort(...)` 也是；按完成顺序装配会让**同分条目的次序随
   网络抖动而变**，同一份资料在两次相同提问里以不同顺序出现在引用编号里。
3. **失败的库占位成空列表，不是从列表里剔除**。`RecallSupport.merge` 有一条
   `size() == 1` 的快速路径（直接返回，不截断），剔掉一个失败的库会让"3 个库"变成"1 个库"
   而静默改变全局截断行为——这一点有专门的测试钉住（改回过滤即变红）。
4. **两种失败必须分开**：归属校验失败（`requireAllOwned` 的 404/3001）与嵌入模型不一致
   （4006）**不被隔离**，仍在扇出之前整批失败。把越权探测变成"部分成功"比报错更糟；
   而"某个库的嵌入模型和别的库不一样"是配置错误，隔离它只会让用户拿到一堆无法解释的缺料。

**代价（如实记）**

- 下游并发压力最多乘 N（N ≤ 10）。虚拟线程本身便宜，但 ES / pgvector / 嵌入服务的压力
  是真的上升了。`retrieval.kb.failed` 计数与 WARN 日志让"部分失败"可巡检，不至于
  变成无人察觉的常态化降级。
- 超时是**尽力取消**（与既有 `TimeLimitedCall` 同一措辞）：`cancel(true)` 能中断等待，
  但外部 HTTP 请求能否真正停下取决于客户端实现。

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

### B.17 Step 2.1 / 2.2 实测验证记录（知识库 CRUD + Markdown/TXT 解析）

| 验证项 | 结论 |
| --- | --- |
| 建库即冻结嵌入模型与维度（ADR-008） | ✅ 从 `embedding.default` 角色解析出 `ollama / bge-m3 / 1024` 并写入记录；配置缺失则报 4006，不让维度不一致的向量悄悄入库 |
| 切分参数（策略/大小/重叠）落库并校验 | ✅ `overlap >= size` 直接 400（否则切片会无限重叠） |
| 知识库 CRUD + 租户隔离 | ✅ 别人的知识库一律 404 + 3001；列表看不到；改名不影响已冻结的嵌入模型 |
| Markdown 解析：frontmatter / 标题树 / 标签 / WikiLink / Markdown 链接 / 图片 / 偏移量 | ✅ 12 个解析器用例 |
| **代码块内的 `# 标题`、`#标签`、`[[链接]]` 一律不识别** | ✅ 单独用例覆盖（Markdown 解析最典型的坑） |
| 容错：只有开头 `---` 没有结束符、CRLF、无标题回退文件名 | ✅ |
| frontmatter YAML 语法错误 | ✅ 报 3004 并带可定位信息，而不是静默丢弃 |

**四个真实踩坑**

1. **MyBatis 的 Mapper 自动扫描只覆盖启动类所在包**。`kb_*` 的 Mapper 按分层放在
   `com.lky.nexus.knowledge.mapper`，于是出现"应用能启动、一注入就报
   `No qualifying bean of type ...Mapper`"。已改为 `@MapperScan(basePackages = "com.lky.nexus",
   annotationClass = Mapper.class)`（扫全树 + 只认注解），后续新增模块无需再改。
2. **MySQL 索引键长度上限 3072 字节**。`source_path VARCHAR(1024)` 参与唯一键时
   utf8mb4 下 1024×4 = 4096 字节 → 迁移直接失败：
   `Error Code 1071: Specified key was too long; max key length is 3072 bytes`。
   已把 `source_path` 改为 `VARCHAR(512)`（512×4+8+1 = 2057，安全；
   Windows `MAX_PATH` 才 260，足够容纳任何实际 Vault 路径）。
3. **失败的 Flyway 迁移会留下 `success=0` 记录并阻塞后续迁移**。
   修复方式：清掉该记录与半成品表（生产环境应使用 `flyway repair`）。
   这条也说明：迁移脚本一旦发布就不该改，本次因为是开发库且 V4 尚未成功过，直接修正即可。
4. **POM 里硬编码 `<skipTests>` 会覆盖命令行的 `-DskipTests`**，
   表现为"想跳过测试却仍在跑全量测试"（本机内存紧张时直接把构建搞崩）。
   已移除该配置，让 Maven 内建的 `-DskipTests` / `-Dmaven.test.skip` 正常工作。

**环境约束（已适配）**：本机 15.6GB 内存、Docker VM 占 7.6GB，可用常低于 2GB。
因此 surefire 的 fork JVM 限定 `-Xmx512m`，应用自身也以 `-Xmx512m` 启动；
构建时先停应用，避免多 JVM 叠加触发 `Native memory allocation (malloc) failed`。

**测试总览（Step 2.1/2.2）**：`nexus-common` 10 + `nexus-ai` 9 + `nexus-knowledge` 12 + `nexus-app` 51（含 5 个需 API Key 的用例跳过）= **82 通过 / 0 失败**。
真实 MySQL 上：Flyway V1–V4 全部 `success=1`，`kb_*` 三张表与唯一键 `uk_doc_kb_path` 就位，
建库接口落库记录中 `embedding_provider=ollama / embedding_model=bge-m3 / embedding_dim=1024 / chunk_size=600`。

### B.18 Step 2.4 实测验证记录（结构化切分）

| 验证项 | 结论 |
| --- | --- |
| 按标题切分：每小节一片，嵌入文本带 `headingPath`、原文不含路径 | ✅ |
| 三级标题路径 `Spring > SpringBoot > 自动配置` | ✅ |
| **代码块绝不被腰斩**（超预算时只按行边界切，每行语句完整） | ✅ |
| 表格整体成片、类型为 `TABLE` | ✅ |
| 超长小节按预算切成多片，各片均在预算内 | ✅ |
| **过小切片并回同小节的上一块**（避免产生检索不到的碎片） | ✅ |
| 偏移量可回溯到原文区间 | ✅ |
| `contentHash` 稳定（相同内容 → 相同哈希，作为 Embedding 缓存键） | ✅ |
| 无标题文档 / 空文档 / 只有标题无正文的小节 | ✅ 分别产出整篇一片、空列表、不产出 |
| 递归切分带重叠（防"答案跨在交界处被切丢"） | ✅ |
| Token 估算：中文约 1 token/字、英文约 4 字符/token | ✅ |

**两个真实踩坑**

1. **分段偏移量的基准错了**（本次最有价值的发现）。结构化切分先把正文按标题切成小节，
   再对每个小节的**子串**分段；分段器却从 0 开始计数，于是所有切片的 `startOffset/endOffset`
   都变成了"小节内相对位置"——前端据此高亮会定位到完全错误的地方，而且<b>不会报错</b>。
   修法：`TextSegmenter.segment(text, baseOffset)`，由切分器传入小节的 `bodyStart`。
   <b>排查手法值得记下</b>：控制台在中文 Windows 上把中文日志显示成乱码，
   因此让测试把诊断信息写入 UTF-8 文件（`target/chunk-offsets-debug.txt`），
   再用读取工具查看——一次就看清了 `chunk0 offsets=[1,7)` 这个明显偏小的区间。
2. **"小块合并"与"代码块整体保留"会叠加**：紧随代码块之后的短说明文字会被并进代码切片
   （这是合并策略的预期行为），因此测试断言不能写成"CODE 切片里所有行都必须以 `;` 结束"，
   而应只校验**代码行本身**未被腰斩。教训：断言要针对"真正的约束"，不要顺手把无关条件也写进去。

**环境约束（构建期对策）**：本机 15.6GB 内存叠加「完整 Docker 栈 + Maven + 测试 JVM」会触发
`Native memory allocation (malloc) failed`。已验证可行的做法：**构建/测试期间停掉 ES 与 RabbitMQ**
（`docker compose stop elasticsearch rabbitmq`，测试只用 H2 不需要它们），
跑完再 `docker compose start`。长期建议把 Docker Desktop 的内存上限调到 4GB 左右（Settings → Resources）。

**测试总览（Step 2.4）**：`nexus-common` 10 + `nexus-ai` 9 + `nexus-knowledge` 25 + `nexus-app` 51（5 跳过）= **95 通过 / 0 失败**。

### B.19 Step 2.5 实测验证记录（Obsidian Vault 导入）

**真实端到端**（HTTP 上传 → 真实 MySQL）：造一个 3 篇笔记 + `.obsidian/` + png 附件的 Vault 压缩包，
经 `POST /api/knowledge-bases/{id}/vault` 导入后实测：

| 项 | 实测值 |
| --- | --- |
| 任务结果 | `status=DONE`、总文件 3、新增 3、跳过 2、失败 0、切片 4、进度 100% |
| 文档 | `Java/SpringBoot.md`、`Java/JVM/GC.md`、`Redis/缓存击穿.md`；**Vault 顶层目录已剥离**；folder/title/status/version/file_hash 均正确 |
| 切片 | `heading_path` 正确（如 `Spring Boot > 自动配置`）；`vector_status=PENDING` 等 Phase 3 回填 |
| 标签 | `java`、`springboot` 独立成表（`kb_document_tag`） |
| 任务明细 | `kb_import_task_item` 3 条 CREATED |
| 知识库统计 | `doc_count=3 / chunk_count=4 / total_tokens=108` |

**五个设计要点 / 踩坑**

1. **安全解压是第一道关口，且必须"看解压后的字节"**。`VaultPackageExtractor` 限制条目数、
   单文件字节、**解压后总字节**（不是压缩包大小——那才是 ZIP 炸弹的真实指标），并且边读边计数；
   路径穿越（`../`、绝对路径、盘符）**直接拒绝整批导入**而不是静默跳过——
   一个带 `../` 的压缩包说明它不是正常的 Obsidian 导出，应当让用户看到明确原因。
2. **`ZipInputStream` 面对非 ZIP 字节不会抛异常**，只是 `getNextEntry()` 返回 null。
   如果不显式判断"零条目"，用户上传一个乱码文件会得到"导入成功，0 个文件"这种误导性结果。
   已改为显式报 3007（这个问题正是被单元测试逼出来的）。
3. **必须剥离 Vault 顶层目录**。导出的 ZIP 通常是 `MyVault/Java/SpringBoot.md`，
   而 Obsidian 内部路径是相对 Vault 根的 `Java/SpringBoot.md`。不剥离的话，
   所有文档都多一层前缀，按目录过滤与内部链接匹配都会变别扭。
   实现上只在"全部文件确实共享同一顶层目录"时才剥离。
4. **覆盖时必须先清理旧切片**。`kb_chunk` 的唯一键是 `(document_id, chunk_index)`，
   若重新切分后行数变少而不清旧行，会留下"幽灵切片"——检索能命中、正文里却早已没有。
   已用"重复导入两次"的用例断言切片总数不增。
5. **切片必须批量插入**。一个 Vault 上千篇笔记会产生几万行切片，逐条 insert 就是几万次数据库往返。
   `ChunkMapper.insertBatch` 按 200 行一批，把它压到几十次。

**已知限制（刻意记录而非隐藏）**：① 解压在内存中完成（个人笔记量级足够，Phase 3 引入大文件时改为流式落盘）；
② 文本按 UTF-8 解码，非 UTF-8 文件会出现替换字符；③ 图片等二进制附件只保留**引用**（`kb_document` 的 links/attachments），
不存储二进制本体；④ 重复导入会重新解析全部文件——**按 `file_hash` 跳过未变化文件是 Step 2.6 的内容**。

**另一个教训（配置类）**：给 `application.yml` 追加 `spring.servlet.multipart` 时，我差点写成
**第二个顶级 `spring:` 块**——YAML 不允许重复键，Spring Boot 会直接启动失败。
追加配置时应把新项挂到已有节点下，而不是另起一个同名节点。

**测试总览（Step 2.5）**：`nexus-common` 10 + `nexus-ai` 9 + `nexus-knowledge` 35 + `nexus-app` 55（5 跳过）= **109 通过 / 0 失败**。

### B.20 Step 2.6 实测验证记录（SHA-256 增量同步）

**真实端到端**（同一个 Vault 连续导入 5 次，每次只改一处，跑在真实 MySQL 上）：

| 轮次 | 变更 | 任务结果 | 关键可观测量 |
| --- | --- | --- | --- |
| ① 首次导入 | — | 新增 3 | 切片 3 个 |
| ② 原样重导 | 无 | **跳过 3** | **任务明细 0 条**——"零写入"是可观测的 |
| ③ 只加一个 tag | frontmatter | **`METADATA_ONLY`** | GC.md 的 `version` 仍为 1、切片数不变 → **向量被保留** |
| ④ 改正文 | 正文 | `UPDATED` | SpringBoot.md 的 `version` → 2、切片重建 |
| ⑤ 改名 | 路径 | **`RENAMED`** | `说明.txt` 复用原文档、`version` 仍为 1、**全库切片总数仍为 3**（无重复、无重新嵌入） |

**五个设计要点 / 踩坑**

1. **"零写入"要能被断言**。跳过的文件既不解析、不写文档、不建切片，**也不写任务明细**
   （否则一次"什么都没改"的重导会插入上千行明细，把增量同步的优势直接抹掉）。
   跳过数量在任务行里有汇总，明细只记录真正发生了动作的文件。
2. **删除判定必须限定"完整 Vault 同步"**。单文件上传时清单里只有一个文件，
   若也按"清单里没有就删"处理，会把库里其余文档全部误删——这是增量同步最危险的失误点。
   `VaultSyncPlanner.plan(..., detectDeletions)` 用显式参数把这条约束钉住，并有专门用例覆盖。
3. **`version` 的语义是"内容版本"**：改名与"只改元数据"都<b>不递增</b>。
   如果让它们也递增，任何"用 version 判断内容是否变化"的逻辑都会失效。
4. **改名的识别靠 `file_hash` 配对**：把"新路径"与"清单里消失的文档"按内容哈希配对，
   配对成功就只改路径字段（切片与向量原样保留），而不是"删一个再建一个"。
   不做这一步的话，改个文件名就要整篇重新嵌入。
5. **`stored_path` 必须落库**（V6 迁移）。原先靠"命名规则算出来"，
   后果是：删除文档时找不到磁盘文件（原始文件不断泄漏）、
   重新解析时也无法可靠定位。落库后它就是事实记录，命名规则再怎么调整都不受影响。

**两个过程性教训**

- **过期断言要被提升为"行为变更的确认"**：Step 2.5 写的测试断言的是"重复导入会走更新路径"
  （当时还没有 hash 跳过）。2.6 落地后它立刻失败，这是测试在正确地提醒"行为变了"——
  此时应该更新断言并写清新语义，而不是把测试改松。
- **端到端演示脚本本身也要严谨**：第一次演示"改名"时脚本没有删掉暂存目录里的旧文件，
  于是压缩包里同时存在新旧两个文件，结果自然是"新增"而非"改名"。
  差点误判成代码有 bug——**演示不等于随意跑一遍，它和测试一样需要控制变量**。

**已知限制**：① 删除判定只支持"整库同步"语义，尚不支持"只想同步某个子目录"；
② 失败项可重试的目标（`kb_import_task_item.action=FAILED`）已就位，但重试接口留到 Step 2.7 异步化时一并提供。

**测试总览（Step 2.6）**：`nexus-common` 10 + `nexus-ai` 9 + `nexus-knowledge` 43 + `nexus-app` 60（5 跳过）= **122 通过 / 0 失败**。

### B.21 Step 2.7 实测验证记录（RabbitMQ 异步化）

**真实端到端**（真实 broker + 真实 MySQL）：上传 Vault 压缩包 →

| 观测点 | 实测 |
| --- | --- |
| HTTP 返回 | **90 ms 返回 `QUEUED`**（此前同步模式要等整批处理完） |
| 生产者 | `RabbitImportTaskPublisher - 导入任务已投递 taskId=...`（HTTP 线程） |
| 消费者 | `[no-request] ImportTaskListener - 收到导入任务消息 taskId=...`（**消费者线程**，与 HTTP 请求无关） |
| 幂等抢占 | `UPDATE kb_import_task SET status=RUNNING WHERE id=? AND status IN (QUEUED,PARTIAL,FAILED)` → `Updates: 1` |
| 处理结果 | 3 篇文档 + 3 个切片，`status=DONE, progress=100` |
| 收尾 | `导入暂存已清理 taskId=...`（成功后清理压缩包；部分失败则保留以便重试） |
| 队列拓扑（自动声明） | `nexus.import.vault.queue`（durable，参数含 `x-dead-letter-exchange=nexus.import.dlx`）、`nexus.import.vault.dlq` |
| 队列积压 | 消费后 `messages=0`（无堆积） |

**六个设计要点**

1. **压缩包不进消息体**。Vault 可能几十 MB，而消息中间件不是文件通道：超大消息会撑爆 broker 内存、
   触发 `max_message_size`，消费者失败重试时还会被反复投递。
   做法是"压缩包落暂存区 + 消息只传 taskId"，消费者按 id 取数据。
2. **幂等靠数据库 CAS，而不是"应该不会重复"**。消息队列只保证"至少一次"投递，
   因此执行前必须做一次比较并设置（QUEUED/PARTIAL/FAILED → RUNNING），抢不到就直接返回。
   这条在 `ImportTaskAsyncTest` 里有专门用例（重复投递 3 次，文档数、明细数、版本号都不变）。
3. **失败分两类，处理方式不同**：
   业务性失败（压缩包损坏、参数非法）重试不会有不同结果 → 落 FAILED 且**不抛异常**；
   非预期失败（数据库抖动、网络）→ 落 FAILED 并**抛出**，交给重试策略，最终进死信队列。
   这个区分是有意的：把不可恢复的错误也送进重试，只会浪费三次尝试并把死信队列塞满。
4. **`default-requeue-rejected=false` + 死信拓扑**。默认行为是消费者抛异常后消息重新入队，
   于是同一条"注定失败"的消息会被无限重放，把 CPU 与日志打满——这是消息队列最典型的生产事故。
   这里配合 `retry.max-attempts=3` 与 DLX，把"重试"和"兜底"都变成显式配置。
5. **同步 / 异步可切换**（`nexus.import.mode`）。standalone profile 与集成测试没有 broker，
   因此提供同步投递器：**两条路径走同一个执行器**，差别只有"谁在什么时候调用它"，
   从而避免"本地跑通了、上线异步却不一样"这类最难查的问题。
   注意 sync profile 必须关掉 RabbitMQ 健康检查，否则 `/actuator/health` 会因连不上 broker 而误报 DOWN。
6. **投递失败必须收尾**。broker 不可用时若不处理，任务会永远停在 QUEUED，
   用户以为"排队中"其实永远不动。因此投递异常时要标记任务 FAILED 并清理暂存，
   再向调用方返回 503（新增错误码 3009）——让客户端知道"依赖挂了、可以稍后重试"。

**一个架构上的必要调整（循环依赖）**

最初把"执行"放在入队服务里，于是同步投递器形成
`投递器 → 导入服务 → 投递器` 的环——Spring Boot 默认拒绝循环引用，应用会直接启动失败。
解决办法不是加 `@Lazy`，而是把"消费者侧的执行"抽成独立组件
`VaultImportExecutor`（它同时被 MQ 监听器、同步投递器、手工重试复用），
依赖关系随即变成一棵树。`@Lazy` 会把设计问题藏起来，抽组件则让它消失。

**两个过程性教训**

- **重构时"顺手删掉"的方法要检查引用**：我把 `toResponse` 挪到 DTO 的静态工厂时，
  连带删掉了邻近的 `nz()` 私有方法，而它在另外两处仍在被调用——编译立刻报错。
  小方法散落时，最好让"删除"与"引用检查"成为一个动作（IDE 的查找引用），不要凭记忆。
- **本机内存约束再次打断构建**：全量测试（4 个 Spring 上下文）叠加 ES/RabbitMQ 容器会触发
  `Native memory allocation (malloc) failed`。已验证可行的流程是
  **构建前 `docker compose stop elasticsearch rabbitmq`，跑完再 start**
  （测试只用 H2，不需要它们）。长期建议把 Docker Desktop 内存上限调到 4GB 左右。

**已知限制**：① 依赖"至少一次投递 + 幂等执行"，因此极端情况下同一篇文档会被处理两次
（结果仍一致，但会多一次解析开销）；② 死亡队列中的消息目前没有自动回放界面，需要人工从 DLQ 重投；
③ 暂存压缩包在"部分失败"时保留，长期堆积需要 Phase 11 的清理任务。

**测试总览（Step 2.7）**：`nexus-common` 10 + `nexus-ai` 9 + `nexus-knowledge` 43 + `nexus-app` 64（5 跳过）= **126 通过 / 0 失败**。

### B.22 Step 2.3 实测验证记录（PDF / DOCX 解析）

| 验证项 | 结论 |
| --- | --- |
| PDF 多页文本抽取 + 页数 + **每页起始偏移** | ✅ 5 个用例（测试用 PDF 现场生成，不放二进制样本进仓库） |
| PDF 不产出标题结构（不靠字号猜） | ✅ 切分自动退化为按段落递归切 |
| PDF 扫描件（无文字层）| ✅ 报 3004 并说明"需要 OCR"，而不是导入一篇空文档 |
| 加密 PDF | ✅ 单独识别并提示"请先去除密码" |
| DOCX 按样式抽出真标题层级（1/2 级） | ✅ 5 个用例 |
| DOCX 表格 → Markdown 表格文本（下游识别为 TABLE 整体保留） | ✅ |
| 损坏文件 / 旧版 `.doc` 改名成 `.docx` | ✅ 给出可操作提示（"请先用 Word 另存为 .docx"） |
| **混合格式 Vault 端到端**（md + pdf + docx 同一个 ZIP） | ✅ 3 篇全导入，且结构差异如实反映：md/docx 的切片有标题路径，pdf 的为 null |

**六个设计要点 / 踩坑**

1. **解析入口统一为字节数组**（SPI 修正）。原接口只接受 `String`，对 Markdown/TXT 够用，
   但 PDF/DOCX 是二进制——若为它们另开一条接口，导入流水线就要按格式分支，SPI 的意义也就没了。
   现在文本解析器在内部按 UTF-8 解码，二进制解析器直接消费字节，**调用方永远只有一种写法**。
2. **"支持哪些扩展名"改由解析器自己声明**（`extensions()`），注册表汇总。
   此前这份清单硬编码在注册表里，新增解析器极易忘记同步——
   而那种不一致的表现是"解析器写好了，却在压缩包导入阶段就被过滤掉"，很难第一时间想到。
3. **PDF 不猜标题**。PDF 里没有"标题"语义，只有"某字号加粗的一行字"；靠字号猜会同时产生
   大量假标题与漏标题，而假标题会污染切片边界与引用路径——比"没有标题"更糟。
   代价是 PDF 拿不到标题路径，因此**用分页信息补偿**：把每页起始字符偏移写进元数据
   （`pageOffsets`），引用时可由切片偏移反查页码（"第 12 页"），一个信息都不丢。
4. **DOCX 走的是另一条路**：标题带样式，因此能拿到**真标题层级**，切片质量与 Markdown 笔记同级。
   两个实现细节：① 必须按 `getBodyElements()` 的文档顺序遍历，否则表格会全部跑到文末、破坏图文顺序；
   ② 标题识别有三条路径——先读大纲级别（最可靠），再匹配样式名/ID（`Heading1`、`heading 2`，
   以及中文 Word 模板里常见的数字样式 ID `1`），都识别不出就当正文段落（宁可少认、不要错认）。
5. **Boot 4 的 BOM 不再托管 PDFBox / POI 的版本**（Boot 3 是托管的）。
   遇到 "version is missing" 时不能随手写一个版本号，而应去 Maven Central 核实当前稳定版：
   本次取 `pdfbox 3.0.7`、`poi-ooxml 5.5.1`，并集中声明在根 POM 的 properties 里。
6. **PDFBox 3 有破坏性 API 变化**：入口从 `PDDocument.load(...)` 变成 `Loader.loadPDF(...)`，
   字体构造从 `new PDType1Font(COSName.getPDFName("Helvetica"))` 变成
   `new PDType1Font(Standard14Fonts.FontName.HELVETICA)`。
   这正是"版本先确认、再写代码"的价值——猜 API 会写出一堆编译不过或行为不同的代码。

**两个过程性教训**

- **测试断言与设计冲突时，先判断哪个行为才是对的**：我最初断言"标题 = 首个一级标题"，
  而实现是"文档属性 title 优先"（与 Markdown 解析器的 frontmatter 优先保持一致）。
  此时正确做法是<b>修正断言并写清理由</b>，而不是把实现改成迁就断言。
- **Windows 下运行中的应用会锁住 jar**：`mvn clean` 报"另一个程序正在使用此文件"。
  构建前必须先停应用——这条此前已因为内存原因要求过，现在多了一个同样硬的理由。

**已知限制**：① 不支持旧版 `.doc`（POI 的 HWPF 是另一个模块），需要用户先转换；
② 不支持扫描件 OCR（会明确报错而不是静默导入空文档）；③ DOCX 的列表项暂按普通段落处理，
排版信息（字号/颜色/批注/修订）不保留——RAG 需要的是内容与结构，不是排版。

**测试总览（Step 2.3）**：`nexus-common` 10 + `nexus-ai` 9 + `nexus-knowledge` 53 + `nexus-app` 65（5 跳过）= **137 通过 / 0 失败**。

### B.23 Phase 3.1 / 3.2 实现记录（嵌入客户端 + pgvector 向量存储）

**已完成并有单测覆盖**：

| 组件 | 关键点 |
| --- | --- |
| `EmbeddingClient` | 批量（单批 32）+ **维度自校验** + 统一错误包装（4004 带模型引用） |
| `EmbeddingClientFactory` | 按逻辑角色取模型（ADR-003）；能力校验（必须声明 EMBEDDING）；按角色缓存 |
| `ChunkEmbeddingService` | 只处理 `vector_status='PENDING'`；按 `content_hash` 去重 + 查缓存；写入幂等；失败保持 PENDING 以便重试 |
| `VectorStore`（端口） | 领域定义能力，app 提供 pgvector 适配器（与消息队列同样的依赖倒置） |
| `PgVectorStore` | 手写 SQL：`ON CONFLICT (chunk_id) DO UPDATE` 幂等写入、`<=>` 余弦距离检索、强制 `kb_id` 过滤 |
| `DisabledVectorStore` | 未启用向量库时**明确报 4003**，而不是静默什么都不做 |
| 向量表 DDL | 独立数据源 + **独立 Flyway**（`db/vector-migration`），HNSW 索引 + `embedding_cache` |

**测试**：`EmbeddingClientTest` 5（维度不一致拦截 / 批量切分 / 空向量 / 异常包装）、
`ChunkEmbeddingServiceTest` 4（缓存命中不调模型 / 批内去重 / 模型不一致拒执行 / 空集合），
`LiveEmbeddingTest` 2（真实 bge-m3，需 `AI_EMBEDDING_LIVE=true`，默认跳过）。
全量 **148 通过 / 7 跳过**（跳过项即需要外部模型与 API Key 的守卫测试）。

**六个设计要点 / 踩坑**

1. **"OpenAI 兼容"不等于"支持所有端点"**。实测本项目的模型网关：`/chat/completions` 正常，
   但 `/embeddings` 返回的是 HTML 页面（网关只代理对话模型，模型列表里也没有任何 embedding 模型）。
   结论：**不要假设网关能力，要实测**；嵌入只能走本地模型。
2. **Spring AI 2.0 的 OpenAI 嵌入路径需要自建官方 SDK 客户端**（`com.openai.client.OpenAIClient`，
   与 chat 的 `OpenAiChatOptions.baseUrl()` 写法完全不同，已用 `javap` 核实）。
   我没有写一段"看起来对但没验证过"的代码，而是：实现已核实的 Ollama 路径，
   OpenAI 路径**明确报错并说明扩展点**——错误信息本身也是文档。
3. **维度必须自校验**。知识库存了冻结维度（ADR-008），而模型实际维度只有调用一次才知道。
   不一致时向量能写进去、检索却会莫名其妙，因此 `EmbeddingClient` 首次调用就探针校验并直接失败。
4. **模型一致性要校验两次**：建库时（解析角色→冻结）与向量化时（配置的模型 vs 库的冻结模型）。
   后者防的是"改了配置后给旧库补向量"——同一个库里混入两个语义空间的向量，检索结果必然错乱且不报错。
5. **缓存放在向量库里，而不是 Redis**（与路线图的差异，理由如下）：
   缓存内容是"一段文本 → 1024 维向量"，放 Redis 需要额外的序列化与内存预算，
   而 pgvector 表本身就在 PostgreSQL 里、天然支持向量列与精确哈希查询，
   一次索引查找即可命中。Redis 更适合放"查询→最终检索结果"这类热数据（Phase 3.6 再做）。
6. **`kb_chunk.vector_id` 刻意不回填**（与路线图的差异）：pgvector 表的主键就是 `chunk_id`，
   两库用同一个 id 直接对应，再多一个 `vector_id` 只会引入"两个 id 不一致"的可能。
   该列保留给"向量库自行生成 id"的场景，当前为空并在实体注释里说明。

**两个过程性教训**

- **判断"卡住"要看真实进度，不要看结果列表**：我在 `docker pull` 进行中查询镜像列表为空，
  误判为"镜像拉不下来"（甚至走了镜像源重试的弯路）。实际上镜像最终拉成功了
  （容器已 Up）。教训是：进度条/字节数才是证据，中间态的空结果不是。
- **单测里漏桩会以 NPE 的形式失败**：三个用例只在其中一个里桩了 `requireOwned`，
  其余拿到 null。把公共桩提到 `@BeforeEach` 后一次通过。
  另外 MyBatis-Plus 的 LambdaQueryWrapper 在纯单测里需要手工初始化实体元数据
  （`TableInfoHelper.initTableInfo`），否则构造 wrapper 会抛
  "can not find lambda cache for this entity"。

**环境约束与实测结果（重要）**

本机网络下 `registry-1.docker.io:443` **不可达**，依靠 `.docker/daemon.json` 里的国内镜像加速；
ollama 镜像与 bge-m3 模型（1.08 GB）最终都拉取成功，因此**真实联调已完成**：

| 联调项 | 实测 |
| --- | --- |
| pgvector DDL | `V1__vector_store.sql` 由独立 Flyway 在 PG 18.6 上执行成功（`kb_vector` + `embedding_cache` + HNSW 索引） |
| 幂等写入 | 同一 chunkId 连写两次（第二次向量不同）→ 只保留一行，检索以最后一次为准 |
| 余弦检索排序 | 单位向量 e0 查询：`[1.0, 0.6, 0.0]` 精确命中预期顺序（`1 - (embedding <=> q)`） |
| 跨库隔离 | 另一知识库里放入**完全相同**的向量 → 检索结果中不出现 ✓ |
| 阈值过滤 / 删除 | `minScore=0.5` 正确滤掉正交向量；按知识库删除后计数归零 |
| 嵌入缓存精度 | 向量经 `::text` 往返后首维误差 < 1e-4 ✓ |
| **真实 bge-m3 调用** | 维度 1024 ✓；向量非零 ✓；**语义排序正确**（同主题相似度显著高于无关句，差值 > 0.1）✓；同文本两次余弦 > 0.99 ✓ |
| 真实延迟 | 首次 3 条 5272 ms（含加载模型）；热态单条 42~169 ms |

**测试总览（Phase 3.1/3.2）**：`nexus-common` 10 + `nexus-ai` 14 + `nexus-knowledge` 57 + `nexus-app` 71（11 跳过）= **152 通过 / 0 失败**。

### B.24 Phase 3.3 实现记录（ES 索引与 BM25）

| 组件 | 关键点 |
| --- | --- |
| `ElasticsearchConfig` | ES 9 官方客户端：`Rest5Client.builder(URI...)` + `Jackson3JsonpMapper`（复用 Boot 4 的 Jackson 3） |
| `ElasticsearchChunkIndex` | IK 分析器映射、批量写入（逐条收集失败原因）、多字段加权检索、**别名原子切换重建** |
| `ChunkIndexService` | 由 `kb_chunk.es_status='PENDING'` 驱动；补齐文档级字段（标题/标签/目录）后写入 |
| `ChunkSearchService` | 检索 + 回表补齐正文与文档标题，**严格保持相关性顺序** |
| `DisabledChunkIndex` | 未启用 ES 时明确报错（与向量库同样的原则） |
| 接口 | `POST /{id}/index`、`POST /{id}/index/recreate`、`GET /{id}/search?q=` |

**真实联调（`LiveChunkIndexTest` 5/5，真实 ES 9.4.5 + IK）**：

| 验证项 | 结果 |
| --- | --- |
| **IK 分析器真实生效** | 直接调 `_analyze`：`缓存击穿与互斥锁` → 切出「缓存」「击穿」，且**不含单字「缓」**（标准分析器才会切单字）✓ |
| 中文 BM25 检索 | 查询「缓存击穿怎么解决」→ 缓存相关切片排第一，分数为正 ✓ |
| 字段权重 | `title^3 / heading_path^2 / content^1`：标题命中排在正文命中之前 ✓ |
| 跨库隔离 | 另一知识库写入**完全相同**的文本 → 检索结果不出现 ✓ |
| 重建索引 | 别名原子切换、旧索引被清理、切换后别名仍可用 ✓ |

**六个实现要点 / 踩坑**

1. **ES 9 的客户端 API 与 8.x 差异不小**，本次全部用 `javap` 逐一核实后才写代码：
   传输层换成 `elasticsearch-rest5-client`（入口 `Rest5Client.builder(URI...)`，8.x 是 HttpHost + RestClient）；
   `GetAliasResponse` 直接暴露 `aliases()`（8.x 是 `result()`）；
   `updateAliases` 的动作需要 `Action.of(...)` 包一层（接口签名收的是 Action 容器类型）。
2. **客户端 9.4.5 自带 `Jackson3JsonpMapper`**，可以直接复用 Spring Boot 4 的 Jackson 3 `JsonMapper`——
   不必再引入 Jackson 2 或额外的 JSON-P 提供者，整个项目保持一套 JSON 栈。
   客户端版本与服务端**严格同版本**（9.4.5）：官方客户端会做版本兼容性检查，跨大版本直接拒绝。
3. **索引期与检索期用不同的 IK 分析器**：索引期 `ik_max_word`（最细粒度 → 召回高），
   检索期 `ik_smart`（粗粒度 → 精确度高）。这是 IK 的推荐用法：
   两边都用最细粒度会让查询词被切碎，命中大量无关内容。
4. **字段权重是 BM25 相对向量检索的核心补充**：标题命中通常意味着整篇都相关，
   因此 `title^3 / heading_path^2 / content^1`；而标签与目录必须是 `keyword`
   （要精确过滤，不能分词）。
5. **别名是为了零停机重建**：物理索引名带时间戳，对外只暴露别名；
   重建时新建 → `updateAliases` 原子切换 → 删旧索引。直接删表重建会出现检索空窗期。
6. **"刚写完就查不到"是 ES 的正常行为**（`refresh_interval` 默认 1s）。
   第一次跑联调时 4 个用例一起失败，全部是"写完立刻查"造成的。
   生产环境**不该**为每次写入等待 refresh（那会把写入吞吐压到每秒一次），
   但测试需要确定性，因此测试里显式 `refresh`。这条同样适用于**删除**。

**一个自找的教训（值得记下来）**

我用 PowerShell 做文本替换（`Get-Content -Raw` → 正则 → `WriteAllText`）修改一个含中文的测试文件，
结果**中文全部变成乱码**——PS 5.1 的 `Get-Content` 默认按 ANSI/GBK 读取 UTF-8 文件。
结论：**不要用 shell 文本处理工具改 UTF-8 源码**，即便只是"批量替换一个方法名"；
这类修改应当用专用编辑工具完成（本项目此前所有中文乱码问题都源于编码，这次是操作方式的问题）。

**测试总览（Phase 3.3）**：`nexus-common` 10 + `nexus-ai` 14 + `nexus-knowledge` 57 + `nexus-app` 76（16 跳过）= **157 通过 / 0 失败**。

### B.25 Phase 3.4/3.5/3.6 实现记录（向量检索 + BM25 + RRF 混合融合）

| 组件 | 关键点 |
| --- | --- |
| `RrfFusion` | 纯函数式融合：只用排名、不用分数；保留每路原始排名供解释；同路内重复项只算一次 |
| `SearchMode` | bm25 / vector / hybrid 三模式都可单独调用——调参与验证融合效果的前提 |
| `ChunkSearchServiceImpl` | 两路召回（深度 = 3×返回条数）→ RRF → 按相关性顺序回表补齐 |
| `KnowledgeEmbeddingResolver` | 写入侧与读取侧共用同一份"模型一致性"校验（查询与索引必须同一模型） |
| `DocumentQueryService.getDocument` | 返回**正文（已剥离 frontmatter）+ 大纲**，供前端 Markdown 渲染与目录跳转 |
| 前端 | Markdown 渲染（marked + DOMPurify + highlight.js）、可跳转大纲、三模式检索面板 |

**真实联调（同一查询「缓存击穿怎么解决」，真实 ES + pgvector + bge-m3）**：

| 模式 | 结果 |
| --- | --- |
| BM25 | 命中 5 条，第一位「缓存击穿 › 什么是缓存击穿」，分数 7.0165，耗时 908 ms |
| 向量 | 命中 5 条，**第一位与 BM25 相同**，余弦 0.7458，耗时 78 ms（查询嵌入 46 ms） |
| **融合** | 第一位 `BM25 #1 + 向量 #1`，RRF 0.0328，耗时 100 ms；第 2/3 位分别是 `BM25 #2 + 向量 #3`、`BM25 #3 + 向量 #2` |

**六个设计要点 / 踩坑**

1. **RRF 只用排名，不用分数**。BM25 分数无上界（同一次查询里命中几条、词频多高都会影响），
   余弦相似度固定在 [-1,1]，直接加权求和等于比较苹果和橘子。
   RRF 对每路取 {@code 1/(k+rank)} 之和（k=60），天然不需要归一化，也不怕某一路尺度变化。
2. **保留每路原始排名**：界面上直接显示 `BM25 #2 · 向量 #1`。
   "为什么这条排第一"必须能回答——只给一个总分是没法调参、也没法解释的。
3. **召回深度要大于返回条数**（默认 3 倍）：否则"两路各自的第一名"可能因为不在另一路的候选里而被丢掉，
   融合反而比单路更差。
4. **同一路内的重复候选只算一次**（这条是单测逼出来的）：我当时写的用例故意在一路里放了重复 id，
   发现实现会把重复项计两次分，凭空抬高分数。融合是公共逻辑，不该假设上游永远干净。
5. **模型一致性校验收敛到一处**：查询嵌入与索引嵌入必须同一模型，否则向量空间不一致、
   检索结果离谱且不报错。这条规则原先只写在向量化路径里，现在抽成
   `KnowledgeEmbeddingResolver` 由读写两侧共用——"两边各写一遍校验"必然一边改一边忘。
6. **正文接口必须剥离 frontmatter**：直接把原始文件返回给前端，Markdown 渲染后
   `tags: [java, JVM]` 会以一大坨 YAML 出现在正文顶部（截图里亲眼看到才知道）。
   元数据本来已作为结构化字段单独返回，正文就该只是正文。

**测试总览（Phase 3.4/3.5/3.6）**：`nexus-common` 10 + `nexus-ai` 14 + `nexus-knowledge` 62 + `nexus-app` 79（16 跳过）= **165 通过 / 0 失败**。

### B.26 Phase 3.7/3.8/3.9 实现记录（重排 → 上下文组装 → 带引用的回答）

**链路**：混合检索（RRF）→ LLM 重排 → 上下文组装（去重 + 编号 + 预算）→ 生成 → 引用归位。

**真实联调**（问「缓存击穿有哪些解决方案？」）：

```
耗时 51.3 s | 资料 269 tok | 重排=True | 截断 0 段

回答：
1. 互斥锁方案：用 SETNX 加锁，只让一个请求去数据库加载数据，其余请求短暂等待后重试 [1]。
2. 逻辑过期方案：不设置真实 TTL，而是把过期时间写进 value，异步刷新缓存 [2]。

引用 9 条：[1] 互斥锁方案(已引用) [2] 逻辑过期方案(已引用) [3..9] 召回到但未引用
```

回答完全忠于示例笔记、引用落点正确，且**能区分"用到的资料"与"召回到但没引用的资料"**。

**五个设计要点 / 踩坑**

1. **没有资料就不调用模型**：检索为空时直接返回"资料中没有相关内容"。
   这一次调用既省钱，也从根上避免了"没有资料却硬答"的幻觉——比任何提示词工程都有效。
2. **重排必须能降级**：`LlmReranker` 对任何异常（模型不可用、JSON 格式不对、条数不匹配）都返回空列表，
   调用方保持召回顺序继续回答，并用 `reranked=false` 如实告知。
   重排是增强项，不该成为整条链路的单点故障。
3. **一次请求打完整批候选**：把 20 个候选放进一个 prompt 要一组分数，而不是每个候选调一次——
   后者意味着 20 倍延迟与成本，效果却几乎一样。解析时按 `id` 归位，不依赖模型保持顺序。
4. **引用要能被核对**：把资料块里用到的片段**全部**返回（含未被回答引用的）并标出哪些真被引用，
   于是"模型有没有漏引/乱引"变成可检查的事实。这是抑制幻觉最实用的一环。
5. **上下文组装的三件小事都会静默损害质量**：重叠切片不去重会挤占预算；
   编号与引用对不上会让引用成为装饰；超预算时悄悄截断会让模型以为"资料就这些"。
   `ContextAssemblerTest` 把这三条都钉住了。

**观察到的性能问题（记下来，Phase 10 再优化）**：一次问答 51.3 s，其中检索部分是毫秒级
（BM25 908 ms / 向量 78 ms / 融合 100 ms），绝大部分时间花在两次 LLM 调用上
（重排用 `chat.judge`、生成用 `chat.default`）。可优化方向：重排只对前 8~10 条做、
或换成专用的重排模型/外部 API；生成改成流式（用户能立刻看到字，感知延迟大幅下降）。

**已知缺口**：① 引用尚未落库（`chat_message_citation` 表待建，前端引用当前来自本次响应）；
② 回答是非流式的；③ 暂无"同一会话内多轮追问"的上下文（会话与记忆在 Phase 1 已具备，需接上）。

### B.27 收尾：问答留档 + 一个潜伏了很久的 Flyway 坑

**新增**：`kb_qa_record`（V7）+ `POST /{id}/ask` 留档 + `GET /{id}/qa-history`。
引用以 JSON 整体存储——历史是整体读取、不会按引用单独查询，
等真正做多轮 RAG 时再把引用挂到 `chat_message` 上。

**⚠️ 发现的严重问题：自 pgvector 那一步起，MySQL 的迁移就再没执行过**

`FlywayAutoConfiguration` 与 `DataSourceAutoConfiguration` 是同一类设计：
内层带 `@ConditionalOnMissingBean(Flyway.class)`。我在 Phase 3.2 为向量库声明了
`vectorFlyway` 之后，**Boot 就整体放弃了 MySQL 的迁移配置**——而且完全没有任何报错，
表现只是"新迁移静默不生效、表不存在"。

更值得警惕的是：这个坑**潜伏了整整一个阶段才被发现**（V6 是最后一个成功应用的迁移，
之后新增的 V7 一直没跑）。之所以拖了这么久，是因为落库代码是<b>容错</b>的
（写记录失败只打 warn、不影响用户拿到回答），于是"表不存在"被优雅地吞掉了——
只有主动去查 `flyway_schema_history` 才看得见。

**两条教训**：
1. **多数据源场景下，主数据源与主库迁移都必须显式声明**，不能依赖自动配置的"缺失即创建"策略。
   已把 MySQL 的 Flyway 也放进 `PrimaryDataSourceConfig`（`@Primary` + 显式 initializer）。
2. **容错设计要配一个"可观测的失败信号"**。容错本身是对的（留档失败不该让用户拿不到回答），
   但只在日志里 warn 就等于让问题隐身。凡是"被吞掉的异常"，都应该有对应的计数或健康指标
   （Phase 10 的观测里补：留档失败计数 + Flyway 版本校验）。

**测试总览（Phase 3.7/3.8/3.9）**：`nexus-common` 10 + `nexus-ai` 14 + `nexus-knowledge` 66 + `nexus-app` 79（16 跳过）= **169 通过 / 0 失败**。

### B.29 Phase 5（Agent）与 Phase 6.1（评测）实现记录

**Phase 5：模型工具循环（三个"用户可控"点）**
1. 工具定义的 API 先验后写：`ToolCallbacks.from` 与 `@Tool`/`@ToolParam` 都在
   `spring-ai-model`（2.0.1）里，**不需要**额外引入 `spring-ai-tool-calling` 模块。
   守卫测试（AgentLoopTest）证明：OpenCode 网关 + deepseek-v4-flash **支持函数调用**
   （若网关不支持，这条测试会如实失败——这是守卫测试存在的意义）。
2. 工具执行发生在响应处理线程，没有请求上下文——用上下文对象（AgentContext）绑定
   "当前用户 + 本轮痕迹"。（**这一版最初是 ThreadLocal，非流式可用、流式静默失效；
   已改为框架的 `ToolContext`，详见 B.30——这里的"线程绑定"是已被推翻的初版方案。）
3. **检索降级阶梯**（只有 Agent 工具有，`ChunkSearchService` 保持严格是刻意设计）：
   混合检索 → SQL LIKE 关键词匹配 → 如实说明。第一版 LIKE 用完整问题做
   `%LIKE%`（"缓存击穿怎么解决"不会出现在正文里）→ 空结果；修法是剥问句助词 +
   按"前 6/4/2 字"逐级缩短，live 验证命中。

**Phase 6.1：评测走 Agent 链路 + record 组件坑**
- 评测若走 RAG 同步路径，standalone 下必然 4005（检索未启用是设计使然）；
  改走 **Agent 链路**（自带降级阶梯）后**任何环境都能评**——"端到端质量"该有的样子。
- 指标：要点命中率（子串匹配，确定性、不用 LLM 判分）+ 引用有效性（回答带来源标注）。
- **JSON 坑**：record 的方法（`avgPointRecall()` 等）不会被 Jackson 序列化进 JSON——
  报告指标必须做成 **record 组件 + 静态工厂计算**，否则前端/脚本读不到。
- **环境坑**：H2 内存库重启即失，演示数据要重建（每轮都在做）；
  Docker Desktop 常处于未启动状态（round 10），因此依赖容器的能力都要有降级路径。

**说明**：B.27 之后阶段（4.x 知识关联、5.x Agent、6.1 评测）的细节主要在
提交信息与各模块代码注释里，本节只记"可直接写在简历/面试里"的结论与坑。

### B.28 一个 bug 花了三轮：JSON 字段被二次编码 + 弱诊断断言

**现象**：`DocumentQueryTest` 的 2 条用例失败——接口返回的文档 `tags` 与 `headings` 都是空数组，
而文档本身存在、标题也正确（`title` 来自首个 H1 ✓）。

**根因**：库里存的不是 `["java","springboot"]`，而是 **`"[\"java\",\"springboot\"]"`**
——JSON 数组被**又序列化了一次**（H2 路径上出现；MySQL 上的真实导入是单层编码 ✓）。
于是 `readTree` 得到的是**字符串节点**而不是数组，`isArray()` 为假 → 方法返回空列表，
而且**不抛异常、不写日志**，完美隐身。

**修法**：读取侧遇到字符串节点就再解析一次（兼容二次编码），并 `warn` 出来。
兼容是为了不丢数据，告警是为了它不再隐身——这条与 B.27 的教训是同一条：
**被吞掉的异常必须有可观测信号**。

**为什么花了三轮（三条可复用的教训）**

1. **诊断断言写得太弱**。我第一版写的是 `assertThat(stored.getTags()).isNotNull()`——
   而问题是"值存在但形态不对"，非空断言照样通过，等于什么都没验证。
   最后靠"故意写错的期望值"（`isEqualTo("__打印原始值__")`）才把真实字符串逼出来。
   **断言必须能打印出真实值**；调不出值的断言等于没写。
2. **用错误的前提解释现象**：先去 MySQL 查测试产生的 id（测试跑在 H2 上，两者根本不是一个库）；
   又从 `chunkCount=1` 推断"没有标题"（空小节本来就会被跳过）。
   教训：**先确认数据在哪个环境，再动手解释**。
3. **只在怀疑的那一段找日志**。我 grep 了"解析失败"却没 grep"序列化失败"，
   而真正的问题不在任何"失败"上——它是"成功但形态不对"。

**顺带产出**：`VaultParseDiagnosticTest` 用与失败用例**逐字相同**的 zip 内容，
把"解压 + 解析"这一段单独钉住；这条测试会在未来任何一次元数据回归里立刻报警。

**又两个踩坑（本轮修复）**

- **第二个 DataSource 会让 Boot 放弃自动配置主数据源**。`DataSourceAutoConfiguration` 的内层配置带
  `@ConditionalOnMissingBean(DataSource.class)`：应用里一旦出现**任何** DataSource bean，
  它整体退让——表现是"MySQL 连接池根本没创建"，而应用会用**错误的那个数据源**继续跑
  （DatasourceGuard 报出的 URL 是 PostgreSQL，才让人意识到问题）。
  修法：显式声明主数据源并用 `@Primary` 标明默认注入目标（`PrimaryDataSourceConfig`），
  同时给 `DatasourceGuard` 加 `@Qualifier("dataSource")`。
- **测试清理用错 id，等于没清理**。向量记录的 `documentId` 与 `chunkId` 在真实链路里是不同的值，
  我第一版按 `documentId = TEST_KB_ID` 清理，实际删不到任何行；
  残留数据让后续用例的计数断言离奇失败。顺便补上了真实需要的能力
  `VectorStore.deleteByKnowledgeBase`（删除知识库时必须清向量，否则留下"检索命中却查不到内容"的孤儿向量），
  并把清理同时放到用例**之前**（上一个用例失败退出时也能保证干净）。

### B.30 Phase 2（工作区归属 + 知识库平台字段）与一个静默失效的工具上下文 bug

**Phase 2 平台化（§三十六 归属、§四十九 只增不改）**
1. 新增 `workspace` 表与"资源带 `workspace_id` + `owner_id`"的归属骨架（V9）：个人工作区
   1:1 用户，注册时创建（`requirePersonalWorkspace` 幂等，重复注册/并发也不会建出两个）；
   成员角色 OWNER/ADMIN/EDITOR/VIEWER 先落位，Phase 8 才真正使用。
2. **依赖方向不能倒**：`workspace` 属于 app 层，`nexus-knowledge` 不认识它。
   因此归属解析放在**控制器**（app 层）——先把 `workspaceId` 解出来，再调
   `knowledgeBaseService.create(userId, workspaceId, request)`。知识库模块保持"只认 userId/workspaceId 两个数字"。
3. 知识库平台字段（V10）：`icon` / `status`(ENABLED|DISABLED，默认 ENABLED) /
   `retrieval_top_k`(默认 5) / `similarity_threshold`(默认 0.7 —— **Phase 3.2 起改为 0.25，见 B.32**)，校验边界在 DTO 上
   （`@Min(1)@Max(50)`、`@DecimalMin("0.0")@DecimalMax("1.0")`）。
   **迁移四条独立的 `ADD COLUMN`**：H2 不接受逗号连写的 `ADD COLUMN`，一次一条才能在 `test`/`standalone` 上同样跑通。

**静默失效的 bug：流式链路上工具拿不到"当前用户"（本轮最值得记的一条）**

现象：Agent **非流式**问答（`AgentService.ask`）能真实调用 `knowledge_search`（live 测试通过），
但 **Agent 模式流式对话**里工具从不生效——SSE 里没有 `tool_call`/`tool_result` 事件，
回答由模型自身的知识生成。**接口 200、界面无报错、日志无异常**，只是"它不检索"。

定位（三次实验，每次都排掉一种可能）：
1. 非流式 live 测试通过 → 网关与 prompt 支持函数调用，问题只可能在流式链路；
2. 手工 `curl` 观察网关的流式响应 → 网关**确实**回了标准 `tool_calls` 分片与
   `finish_reason:"tool_calls"`，不是网关的问题；
3. 打开 `org.springframework.ai=DEBUG` 跑同一条链路 → 决定性日志：
   `Executing tool call: knowledge_search` 之后紧跟
   `Exception thrown by tool: 工具调用缺少用户上下文（AgentContext 未初始化）`。

根因：`AgentContext` 当时用 **ThreadLocal** 绑定用户，而流式链路上**工具的执行线程不是绑定上下文的那个线程**
（订阅在 boundedElastic，工具由响应式 HTTP 客户端聚合完 `tool_calls` 后执行）。
更糟的是 **Spring AI 把工具抛出的异常当成工具结果回填给模型**——模型于是收到
"缺少用户上下文"这句话，改用自身知识作答。异常被框架吞掉，所以从外部看一切正常。

修法（换通道，而不是修线程）：上下文改用框架自带的 **`ToolContext`**——
`ChatClient.prompt().toolContext(map)` 传入，框架注入到工具方法参数上
（`KnowledgeSearchTool.search(query, topK, ToolContext)`），与线程无关，这类 bug 从根上不存在。
`ToolContext` 参数会被 `JsonSchemaGenerator` 跳过，**不会**出现在给模型看的工具 schema 里。
配套：`AgentService` 用监听器收痕迹（`AgentContext.collector(sink)`）而不是读 ThreadLocal；
MCP 的 `tools/call` 是"我们自己直接调工具"，显式 `new ToolContext(...)`。

两条复用的教训：
- **跨线程的上下文不要用 ThreadLocal**——响应式链路上"哪个线程执行"是框架的实现细节，
  不是可以依赖的契约；
- **回归测试要钉住失败模式本身**：`AgentContextTest.traceCrossesThreads` 特意在**另一个线程**里
  写痕迹并断言发起侧收得到——这正是原实现会失败、新实现会通过的那条线。

**顺带修掉的噪声**：降级（SQL LIKE）路径把 `headingPath` 传成 `null`，工具拼给模型的
资料里出现 `来源: 缓存击穿 › null`，模型把它**照抄进回答**（用户可见的脏数据）。
修法：降级路径改用切片自带的 `heading_path`/`heading`，拼装时对空值直接不输出该段。
教训：**给模型的上下文同样要按"用户可见"的标准要求**，模型会忠实复制里面的噪声。

### B.31 Phase 3.1 实现记录（Retriever 端口抽象）与"行为不变"的机器证明

平台化 Phase 3 的第一个增量：把检索从"一个什么都知道的 Service"拆成**端口 + 策略实现族**。
对外行为**一字未改**，改动全部发生在结构上。

**1. 端口放 `nexus-ai`，实现放 `nexus-knowledge`**

这是模块依赖方向倒逼出来的唯一解：`common ← ai ← knowledge ← app`，knowledge 依赖 ai，
**ai 不能依赖 knowledge**。所以 `Retriever` / `RetrievalQuery` / `RetrievedDocument` /
`RetrievalStrategy` / `RetrieverRegistry` 放 ai（纯接口与数据，无基础设施），
四个实现放 knowledge（要碰 ES / pgvector / 嵌入模型），app 通过 Spring 自动装配做组合根
（`scanBasePackages = "com.lky.nexus"` 使跨模块 bean 注册零成本）。
Phase 4/5 的 Tool / Workflow 抽象走同一条路。

**2. 策略与实现一一对应，越界即响亮失败**

`RetrieverRegistry` 收集所有 `Retriever` bean，按 `strategies()` 建策略 → 实现的映射，两条规则：

- **同一策略被两个实现认领 → 启动即抛 `IllegalStateException`**。这种情况没有"合理默认行为"可退
  （选错实现不会报错，只会让检索结果静默变差），不如让应用起不来。报错里同时给"实现名 + 类名"：
  只给类名的话，同一个类的两个不同配置实例会打印出一模一样的两串字，等于什么都没说。
- **请求了没人认领的策略 → 抛 1001**，而不是返回空列表。"没有结果"和"这个策略没实现"是两回事——
  返回空会让上游据此回答"资料中没有相关内容"，而真正的原因是配置写错了。

**3. `SearchMode`（用户可见）与 `RetrievalStrategy`（内部）刻意分成两个枚举**

`SearchMode` 是前端下拉框里的三个选项（bm25 / vector / hybrid）；`RetrievalStrategy` 多了
`HYBRID_RERANK`，未来还会有 GRAPH / SQL。用户不需要知道"重排"的存在，内部却需要把它当成一种策略
来派发。**把内部枚举直接暴露成 API 契约，等于以后每加一种策略都要做一次接口变更**——
所以在边界处做一次显式映射（`ChunkSearchServiceImpl.toStrategy`），两侧各自演进。

**4. 参数与上限收进 `RetrievalQuery`，不散在各个实现里**

`topK` 上限 100、召回深度倍数 3、候选深度上限 200、相似度下限默认 0.25 全是**检索契约的一部分**
（"topK 超过 100 一律按 100 算"）。放在每个 Retriever 里迟早两路不一致，而那种不一致不会报错，
只会让某一路悄悄多召回或少召回。记录对象同时让"这次检索按什么条件做的"可以被完整落日志、回放、进评测集。

**5. `HybridRetriever` 不持有 ES，也不持有 pgvector**

它只调用另外两个 Retriever 的**召回方法**（`recall`，只返回 chunkId + 分数，不回表），
拿到两路原始排名后做 RRF，再统一回表。好处：融合层不认识任何基础设施；
"新增一路召回"= 加一个实现 + 在这里多接一路，而不是在融合代码里加 if。

**6. `ChunkSearchServiceImpl` 退化为门面——这是本增量最重要的一个决定**

对外签名、`SearchHit` 结构、异常语义**一字未改**，因此五个调用点
（检索接口、RAG 问答、Agent 工具、引用构建、上下文组装）零改动。这不是"懒得改"，
而是**先让重构可被证明**：调用方一行不改，才谈得上"改前改后行为一致"。

**7. 重排（`RerankedRetriever`）已实现，但 RAG 暂未切过去**

`HYBRID_RERANK` 已能被注册表派发、有 7 条单测，但 `RagAnswerServiceImpl` 仍走原来的内联重排。
原因：那里有一个**语义差异必须单独处理**——旧代码用
`reranked = scores.size() == RERANK_CANDIDATES`（硬比常量 8）判断"是否重排成功"，
当召回不足 8 条时它永远判为 false（重排其实跑了，却被记为"降级"）。
新实现按"候选实际条数"判断。这是**修正而非平移**，必须连带更新 RAG 侧的行为期望与测试，
不能顺手藏在端口拆分里——否则一次提交里同时有"结构变了"和"行为变了"，出问题无法归因。

**8. "行为不变"怎么被证明（本轮最值得复用的一条）**

把改造前的实现**逐字固化进测试代码**（`ChunkSearchServiceLegacyReference`，只存在于 test 目录，
头部写明"禁止修改"），让新旧两个实现在同一份假数据上跑同一组场景、逐字段比对。
断言分两层：

1. **先断言具体期望值**（顺序、分数、来源、命中说明、回表请求的 id）；
2. **再断言新旧完全一致**。

只有第 2 层，会退化成"两边都错成一样也能过"；只有第 1 层，则无法防止后续有人无意中偏离。
两层叠加才既非空洞、又能长期保护。被钉住的隐蔽语义（这些都是"改了不会报错"的东西）：

- 重复命中只取第一次出现的分数，**但仍消耗一个名次**（`BM25 #3` 而不是 `#2`）；
- 命中已删除的切片时**跳过**，导致返回条数可能少于 limit，而不是补位或报错；
- 正文取 `rawContent`，只有为空时才退回参与嵌入的 `content`；
- RRF 平局按 `chunkId` 升序稳定排序（否则同一查询两次可能给出不同顺序）；
- `limit` 在**回表之前**生效（不多查用不上的切片）；
- BM25 单路不碰向量库与嵌入模型（多打一次嵌入调用 = 白花的钱与延迟）；
- 归属校验在"空查询短路"**之前**（越权 + 空查询仍报 3001，不能悄悄变成空结果）。

**9. 本轮环境实测（如实记录）**

`standalone`（H2 + 无 ES / 无 Ollama）下三种模式分别返回 **4005 / 4005 / 4004**，
且都是领域错误码而不是 500——说明新的实现族把基础设施不可用**原样**翻译成了既有错误语义，
与改造前一致。启动日志确认四种策略注册成功：
`{VECTOR=vector, HYBRID_RERANK=hybrid-rerank, HYBRID=hybrid, KEYWORD=keyword}`。
**ES / pgvector 真机行为未验证（本机 Docker/WSL2 不可用），标 `UNAVAILABLE`。**

**10. 顺带修掉的**：`web/scripts/verify-login.mjs` 的默认口令是 `Passw0rd!23`，
而 `scripts/seed-demo.mjs` 种的是 `Demo@12345`——两个脚本描述同一个演示用户却各写各的默认值，
表现为"种了数据却登不进去"，且报错只是"登录超时"（很难定位）。已对齐。

### B.32 Phase 3.2 实现记录（检索参数真正可配置 + 默认值收敛到一处）

知识库表里的 `retrieval_top_k` / `similarity_threshold` 从 Phase 2 就存在，但**一直是死字段**：
建库时会写进去，检索时没人读。本增量让它们真正生效，并把散落各处的"默认值"收敛到一个来源。

**1. 先确认"死字段"到底死在哪——三处各写各的默认值**

改动前，同一次检索的条数由三个互不相干的地方决定：

| 入口 | 条数来源 | 值 |
| --- | --- | --- |
| 检索接口 `/search` | Controller 的 `@RequestParam(defaultValue = "10")` | 10 |
| RAG 问答 | `KnowledgeRagController` 里写死的 `12` | 12 |
| Agent 工具 | `KnowledgeSearchTool` 的常量 | 5 |
| **知识库配置 `retrieval_top_k`** | **没人读** | — |

也就是说：用户在知识库设置里把 topK 调到 20，三个入口谁都不理他。更糟的是阈值：
建库默认写 0.7，而实际检索用的默认是 0.25（`RetrievalQuery.DEFAULT_SIMILARITY_THRESHOLD`）——
**表里存的配置和跑起来的配置从一开始就是两个不同的数**，且没有任何一处会报错。

**2. 阈值按 0.25 统一，不是按 0.7**

两个值里选哪个，不靠"哪个看起来更合理"，靠已记录的一次真实测量（B.25）：
真实查询的 **top-1 余弦相似度是 0.7458**。若下限取 0.7，只有 0.046 的余量——
命中与否几乎由噪声决定，而 0.5–0.7 之间的**合法命中会被静默丢掉**
（表现为"明明导进去了却答不出来"，且日志里什么异常都没有）。
取 0.25 意味着全局默认是"噪声下限"，**收紧是每个库自己的事**（这正是 `similarity_threshold` 该有的用法）。
凡是把默认值往严改的，都得先拿出这种量级的证据，否则就是在用配置掩盖检索质量问题。

**3. 唯一解析入口 `RetrievalQueryFactory`，优先级写死为 请求 > 知识库 > 全局**

调用方传 `null` 表示"没指定"（而不是 0 或 -1 这类哨兵）。`topK` 因此从 `int` 变成 `Integer`
——这一步不能用哨兵糊过去：`topK=0` 在既有 parity 测试里被钉死为"钳到 1（召回深度 3）"，
拿它当"未指定"会悄悄改掉已钉住的行为。宁可改签名，也不改已证明的语义。

生效范围（**"知识库配置"这一层只在门面里解析一次**）：`ChunkSearchServiceImpl` 拿得到 `KnowledgeBasePO`，
所以 `ChunkSearchService` 的 `topK` 参数语义变成"null = 用这个库自己的配置"。
RAG 问答与 Agent 工具都不再各自实现一遍优先级——两处各写一遍的优先级，迟早会有第三处写错。

**4. 日志打在召回之前**

解析后的参数（`topK=12 threshold=0.25`）在调用 Retriever **之前**打日志。原因很实际：
调参时最常问的是"我改了配置怎么没反应"，只有解析后的值能回答（请求传了 10 还是"没传"，事后从日志里看不出区别）；
而检索失败（ES / 向量库不可用）会直接抛异常，日志留在失败之后的话，**最需要排查参数的那条路径反而什么都没有**。

**5. 顺带修掉 `reranked` 的静默误报（3.2 才让它可达）**

`RagAnswerServiceImpl` 原本用 `scores.size() == RERANK_CANDIDATES`（硬比 8）判断"重排是否成功"。
召回不足 8 条时恒为 false：**重排确实跑了、顺序也确实变了，界面却显示"未重排"**。
B.31 记录过它是"修正而非平移"，当时不可达（默认 topK ≥ 8）；3.2 让知识库 topK 生效后
（demo 库就是 12，用户可设更小），它立刻变成可达路径。改为按候选实际条数判断。

**6. 参数钳制而不是抛错**

`topK=999` → 按 `RetrievalQuery.MAX_TOP_K`(100) 取；`topK=0` → 1；非正阈值 → 回落全局默认。
理由：检索参数是"调参"而不是"契约"，越界时给一个可预测的上界比 400 更有用；
但**上界必须只有一处定义**（`RetrievalQuery`），否则两路召回各自钳制，会出现"某一路悄悄少召回"。

**7. `ChunkSearchServiceParityTest` 的冻结参照物与接口解耦**

`topK` 从 `int` 变 `Integer` 后，冻结的旧实现（`ChunkSearchServiceLegacyReference`）
不再 `implements ChunkSearchService`，`SearchHit` 也改用限定名。
**它的检索逻辑一字未改**，改的只是它如何与一个正在演进的接口相处。
同时把 parity 夹具的 KB 参数显式设为 `null`，让解析落到全局默认 0.25 ——
即改造前硬编码的那个值，从而保持逐字段一致（否则这个测试会因为"参数来源变了"而失败，
而它本该证明的是"重构没改检索逻辑"）。

**8. 本轮环境实测（如实记录）**

| 场景 | 日志/返回 | 判定 |
| --- | --- | --- |
| demo 库（配置 12 / 0.25），不传 topK | `topK=12 threshold=0.25（知识库 … 的配置 12 / 0.25）` | PASSED |
| 同上，请求 `topK=3` | `topK=3 threshold=0.25`（知识库配置仍为 12） | PASSED（请求优先） |
| 新建库（7 / 0.42），`mode=vector` | `topK=7 threshold=0.42` | PASSED（阈值已到达 VectorStore） |
| 不传任何参数新建的库 | 解析为 5 / 0.25 | PASSED（原为 0.7） |
| `/ask` 不带 topK | 解析出 12 后返回 **4005**（ES 未启用），非 500 | PASSED（新增日志在失败路径同样可见） |

**ES / pgvector / Ollama 真机行为仍为 `UNAVAILABLE`（本机 Docker/WSL2 不可用）**，
故"检索结果质量随参数变化的实际效果"未验证——已验证的是**参数确实解析并下推到了 VectorStore 入参**
（`ChunkSearchServiceKnowledgeConfigTest` 直接断言 `search(kbId, vector, 12, 0.25)`）。

**9. 未做（有意留下）**：`status = DISABLED` 的知识库仍会参与检索（本增量只动参数解析，不动"是否参与"）；
知识库参数目前只能通过 API 改，没有设置界面；`RagAnswerServiceImpl` 仍未切到 `RerankedRetriever`（理由同 B.31 第 7 条）。

### B.33 Phase 3.3 实现记录（元数据过滤：tags / folder / documentId）

检索此前只有"按相似度取前 K 条"。真实使用里大量查询要带限定——"只在这几篇里找"、
"只要 #算法 这个标签的"、"只看某个目录下的"。本增量把三个条件下推到索引/SQL 侧，
并处理它们最危险的失败方式：**过滤条件静默消失**。

**1. 端口只接受它真能兑现的条件——`VectorStore` 收 documentIds，不收 `RetrievalFilters`**

ES 索引里 `tags` / `folder` 是 keyword、`document_id` 是 long，三个条件都能在索引侧精确过滤
（放在 `filter` 上下文里还不参与打分、能吃 filter cache）；而 pgvector 表只有 `kb_id` / `document_id` 两列。
如果给 `VectorStore` 一个 `RetrievalFilters`，它唯一能做的就是**忽略 tags 和 folder**——
用户在混合检索里按标签筛，关键词那一路筛对了、向量那一路返回全库，
融合之后"筛了等于没筛"，且不报错。所以签名上只给它 documentIds，
标签/目录必须先由 `RetrievalFilterResolver` 在关系库里翻译成文档 id。
**端口类型即约束**：让一个实现无法"假装支持"，比在文档里写"请不要忽略它"可靠。

**2. 关系库是标签/目录的唯一事实来源**

解析走 `kb_document_tag` 与 `kb_document.folder`，而不是 ES 索引里的同名副本。
ES 里那份是索引时的快照，重打标签后要等重新索引才一致；用关系库解析保证"昨天改的标签今天就能筛"。
代价是两条腿可能短暂不一致（关键词腿按快照、向量腿按当前值）——可接受，因为改标签会改变文档
hash，本来就会触发重新索引（B.20 的 SHA-256 对账）。

**3. `matchNothing` 与"没有条件"必须分开——这是本增量最核心的一个区别**

按标签筛、而这个标签在库内谁都没有，结果必须是**空**。若把"解析不到"当成"没有条件"，
过滤会静默消失，用户看到的是"筛了等于没筛"的全库结果。
所以 `RetrievalFilterResolver.resolve()` 返回 `FilterPlan`，`nothing()` 与 `none()` 是两个不同的值，
`VectorRetriever` / `HybridRetriever` 收到 `matchNothing` 时**直接返回空、连检索都不发起**。
（`FilterPlan.nothing()` 不能叫 `matchNothing()`：会与 record 分量访问器同签名冲突。）

**4. 目录匹配含子目录，且 LIKE 的通配符必须转义**

在目录树上点一个节点，用户预期是"这下面所有的东西"，所以 `folder` 匹配本身 + `folder/` 前缀，
ES 侧是 `should(term, prefix)` 并显式写 `minimum_should_match: 1`（bool 里只有 should 时默认就是"至少一个"，
显式写出来才不会在以后加子句时被悄悄改掉语义）。
前缀用 `!` 作转义字符（避开 MySQL 字符串字面量的二次转义），**`_` 与 `%` 都要转**：
`_` 在笔记命名里很常见（`day_01`），不转义会把 `day101` 也匹配进来。这条有 H2 真 SQL 测试。

**5. `limit` 在回表之前生效，过滤也在索引侧生效（不许"先检索再内存筛"）**

内存筛会让 `topK` 的语义悄悄变成"筛之前的前 K 条"——本来相关的第 11 条永远回不来。
同理 BM25 单路不会因为过滤而多打一次嵌入调用。

**6. "行为不变"仍然被 parity 测试保护**

`ChunkSearchServiceFilterTest` 里有一条 `noFiltersKeepsOldBehaviour`：不带过滤时，
向量路收到的仍是"空的范围（= 全库）"，与改造前一致。过滤是新增维度，不是对既有语义的改写。

**7. HTTP 边界上抓到的两个真实缺陷（都是本增量的测试发现的，不是猜的）**

写 `KnowledgeSearchControllerFilterTest` 时，第一版断言就红了，红了两次：

| 现象 | 根因 | 修法 |
| --- | --- | --- |
| `?tags=Redis,算法&tags=Redis` 得到标签 `"Redis, 算法"`（字面量，谁也匹配不到） | Spring 只在参数**只出现一次**时才把 `String` 按逗号拆成 `List`；同名参数出现两次时每个值都当整体 | 拆分责任移到边界：`splitMultiValue()` 对每个元素再按逗号拆并 trim/丢弃空项 |
| `documentIds` 不去重（`describe()` 把 2 篇报成"限定 3 篇"） | `RetrievalFilters` 对 tags 做了 `distinct()`，对 documentIds 只做了 `copyOf` | 同样去重（两者都是集合语义） |

第一个是典型的"过滤条件静默变成一个不存在的标签"：不报错、只返回空结果，
与"库里真的没有相关内容"完全无法区分——正是本增量最想消灭的失败方式。
`documentId` 因此也从 `List<Long>` 改成 `List<String>` + 自行解析：
`?documentId=7,8` 在旧签名下会因元素 `"7,8"` 无法转成 `Long` 而绑定失败，
失败信息是框架的类型转换异常，对使用者毫无指向性；自己解析能说清"哪个值不对"。

**8. 顺带修掉的：参数类型写错返回 500（把客户端笔误记成服务端 BUG）**

`GlobalExceptionHandler` 原本没有接 `MethodArgumentTypeMismatchException`，
于是 `?topK=abc` 落进兜底处理器 → **HTTP 500 + 一条带堆栈的 ERROR 日志**。
这不是本增量引入的，但被本增量的边界测试撞上了（同一类参数）。已补处理器：返回 400 + 1001，
提示带上参数名、实际值与期望类型。**"客户端错误"与"服务端 BUG"必须在日志和状态码上分得清**，
否则告警会被参数笔误淹没。

**9. 本轮验证（如实记录）**

| 层次 | 内容 | 判定 |
| --- | --- | --- |
| Unit | ES 查询 DSL（terms/前缀/AND/空标签）、pgvector 语句（`document_id IN` 的位置、占位符数、`LIMIT` 居末） | PASSED |
| Unit | `ChunkSearchServiceFilterTest`：两路下推、`matchNothing` 短路、不过滤时行为不变 | PASSED |
| Contract（H2 真 SQL） | `RetrievalFilterResolutionH2Test`：标签 any-of、目录含子目录且不误匹配兄弟目录、`_`/`%` 字面量、逻辑删除、不跨库、命中零篇 ≠ 不过滤、三条件 AND | PASSED |
| Contract（HTTP） | `KnowledgeSearchControllerFilterTest`：多值参数三种写法归一、folder 透传、不过滤 = `none()`、`topK` 语义与钳制、mode 严格解析、非法参数 400 而非 500 | PASSED |
| 全量回归 | `mvn -B test`：290 通过 / 18 跳过（7 个 live 类）/ 0 失败 | PASSED |
| 真机 | ES `filter` 下推的实际行为、pgvector `WHERE` 的实际行为 | **UNAVAILABLE**（Docker 守护进程未运行：`docker ps` 连不上 `dockerDesktopLinuxEngine`） |

**口径**：本增量在 ES / pgvector 上的结论是**代码级 + 查询构造级**验证，
"真机上过滤确实生效、且过滤后 topK 语义正确"未验证。按 §四十八，不得据此声称生产链路已验证。

**10. 未做（有意留下）**：过滤目前只有 API 入口，前端检索面板没有标签/目录筛选控件
（界面上的筛选该长什么样还没定，先不加半成品）；RAG 问答与 Agent 工具仍按 `none()` 调用
（"按标签问"要等 Agent 侧的工具参数化，属 Phase 5）；`kbIds` 多库过滤未做（那是 3.5 工作区隔离的事）。

---

### B.34 Phase 3.4 实现记录（RAG 模式的会话流式：检索事件 + 知识库绑定）

**0. 本增量是什么**

让"会话流式"（`POST /api/chat/stream`）在 `mode=RAG` 下真正走检索问答，并把检索结果作为
`retrieval` 事件在**第一个字之前**推给前端；知识库的归属落在会话上（`chat_conversation.kb_ids`）。
`/api/knowledge-bases/{id}/ask/stream` 保留为单发/调试入口，但它与主链路**共用同一份提示词与同一个事件构造器**。

**1. 主链路先定下来，两条链路共用提示词**

上一增量刚把检索抽成 Retriever 家族，同一件事有两套提示词拼装就等于养两个真相。
本增量把 `RagAnswerService.prepare() / systemPrompt() / userMessage()` 显式做成共用缝：
会话流式调它们，知识库问答流式也调它们；契约测试用 `verify(ragAnswerService).userMessage(...)`
钉住"会话流式走的是这套方法，而不是自己又拼了一份"——否则同一个人问同一个问题，
从两个入口会得到两种答案，且极难被发现。

**2. `retrieval` 事件的顺序是语义，不是实现细节**

前端靠"先收到 retrieval 再收到 delta"决定何时显示"检索到 N 个片段"。
只断言"响应里包含 retrieval"是不够的：晚一拍出现，用户看到的就是
"回答已经蹦字，检索条数才突然冒出来"——像界面抖动，而根因在服务端顺序。
因此断言写成**索引大小关系**（`orderIn("retrieval") < orderIn("delta")`）+ 事件序列
（`containsSubsequence("meta","retrieval","delta","usage","done")`）。

**3. 零资料分支：不调模型，但事件一个不少**

`prepared.empty()` 时发 `retrieval(count=0, empty=true)` → 一句固定回答（走 `delta`）→
`done{finishReason:"empty"}`，**不调用模型**（省钱，也从根上避免"无资料硬答"）。
为什么仍要走完 delta/done：前端一旦只等 delta，这条分支就会让它永远停在"检索中"。
空资料文案收敛成一个常量 `RagAnswerService.EMPTY_ANSWER`，两条链路同一句话。

**4. 知识库绑在会话上：三条准入规则都是"宁可不做、也不装作做了"**

`kb_ids` 用的是既有列（`V2__chat.sql` 的 JSON 列 / H2 的 `VARCHAR(2000)`），无需迁移：

1. **只有 RAG 能绑**：PLAIN 绑了没用；AGENT 的检索范围由模型自己决定（工具会扫该用户全部知识库），
   写进去只会让人误以为检索被限定了；
2. **最多一个**：检索侧目前是单库签名（`ChunkSearchService` 吃一个 kbId），绑多个只能"取第一个"——
   那是静默丢条件，必须显式拒绝（1001，文案带上收到的个数）；
3. **必须是自己的库**：走 `requireOwned`，别人的库返回 3001、"不泄露存在性"与既有约定一致。

配套：RAG 会话没绑库就提问，**在开流之前**返回统一 JSON（HTTP 400 / 1001 + "尚未绑定知识库"），
而不是降级成"不检索但看起来一切正常"——用户会得到一个像模像样、其实没查过笔记的回答，
那比报错糟糕得多。

**5. 实测抓到的静默失效：MyBatis-Plus 的 `updateById` 跳过 null 字段**

`PUT {"kbIds":[]}`（解绑）原本写的是 null。表现极具欺骗性：接口 200、返回体里 `kbIds` 甚至是空的
（它来自内存对象）、日志无异常，**但数据库那一列纹丝不动**——刷新后绑定又回来了。
根因是 `updateById` 默认字段策略 `NOT_NULL`：null 字段不进 UPDATE 语句。
修法不是"记得别写 null"，而是让这一列**只有一种表示法**：空就是 `"[]"`，永不为 null
（`ConversationKbIds.write`），并在注释里记下这个坑。这条是被 `rebindAndUnbind` 测试抓到的，
不是靠代码审阅发现的。

**6. 历史用 ChatMemory，且 RAG 模式刻意不用 `MessageChatMemoryAdvisor`（落地 ADR-011）**

RAG 模式自己读/写历史，两个理由：

- advisor 存进去的是**拼好的【资料】块**。下一轮又把这段资料当"历史对话"重新喂一遍，
  等于把 2000 token 的资料重复注入，还让模型分不清"这是资料还是我之前说过的话"；
- **指代消解发生在检索之前**：`buildRetrievalQuery` 要把上一轮问题与当前问题拼成检索词，
  所以历史必须在**检索前**就能读到；advisor 的记忆是在调用模型时才插入的，那时检索已经做完了。

于是：读走 `ChatMemoryTurns.read`（`RagTurn.of/lastTurns` 把最近 3 轮压成"问 + 答摘要(300 字)"），
问完把**纯 Q/A** 写回（`remember`），写失败只告警不打断（记忆是增强，不是主链路）。

**7. 顺带修掉一个真 bug：`/ask` 的历史窗口取反了**

原先 `history.stream().limit(3)` 拿的是**最早**的 3 轮（列表正序），即"最近 3 轮"被实现成了
"最旧 3 轮"——越是长对话，模型越只看得到开头。现在统一走 `RagTurn.lastTurns`（取末尾 3 轮），
规则只存在一处。

**8. 界面（前端）**

- 新建会话时选模式（Agent / 知识库问答）与知识库，两个控件只在"还没有会话"时出现
  ——模式是会话属性，中途改等于换了一种问答，服务端也拒绝；
- 已有会话在标题栏显示模式；RAG 会话的知识库可**改绑**（换了库，后续轮次检索的就是新库）；
- 检索卡片排在回答正文**之前**：`检索到 N 个片段` + 命中切片的文档名/标题路径
  + `已重排` / `预算截断 N 段` / `知识库里没有命中` 标签；
- 知识库详情页的问答页签同样显示"检索到 N 个片段"（生成期间显示，生成完由"资料 N 段 / tok"接手，
  不并列两处数字）。

**9. E2E 跑出来的前端竞态：异步挂载覆盖用户的选择**

`onMounted` 里的"进来就选中最近一次会话"要跨两个 RPC（会话列表、知识库列表）。
在这段时间里用户完全可能已经点了"新对话"——那边回来后无条件套用默认选中，就会
**把用户的操作覆盖掉**：明明点了新对话，界面又跳回旧会话、旧消息重新铺满屏幕，
接着发的消息会被算到旧会话名下。修法是 `pickedByUser` 标记（点"新对话"或点某个会话时置位），
默认选中只在"用户还没动过"时生效。

这条是被验证脚本"跳出一个假通过"暴露的：脚本的后续断言全被**旧 DOM** 满足了
（最后表现为刷新后测试超时）。所以脚本也加了一条硬断言——**新建会话后消息数必须为 0**；
只加在产品里不够，脚本必须能自己发现这类污染。

**10. 验证脚本的退出码约定（新增）**

界面验证脚本现在区分三种结果：`0` 全部通过 / `1` 真实失败 / `2` 有跳过。
"跳过"只发生在**环境能力缺失**时（standalone 没有 ES：RAG 在检索处即返回 4005），
判定依据取**后端响应体**而不是界面提示——ElMessage 默认 3 秒就消失，
等断言超时（几十秒）后再去读 DOM 什么也读不到，会把环境问题误报成产品缺陷。
按 §四十八，跳过**不等于通过**，脚本会明确打印 `⚠ SKIPPED` 与原因。

**11. 本轮验证（如实记录）**

| 层次 | 内容 | 判定 |
| --- | --- | --- |
| Unit | `RagTurnTest` 6 项：空问题不产生轮次、答摘要在 300 字处截断并加 `…`、换行拍平、空回答无摘要、`lastTurns` 只留最近 3 轮、null/空入参 | PASSED |
| Contract（HTTP） | `ChatRagModeTest` 7 项：绑定后详情与列表都能读回、未绑定提问 400/1001 且 `kbIds` 是空数组而非 null、PLAIN/AGENT 绑库被拒、绑多个被拒、绑别人的库 404/3001、改绑与解绑（含"解绑后再改标题仍是解绑"）、绑不存在的库 3001 | PASSED |
| Contract（HTTP，SSE） | `RagStreamRetrievalEventTest` 3 项：会话流式 `meta→retrieval→delta→usage→done` 且 retrieval 索引小于 delta、载荷字段（count/文档名/标题路径/knowledgeBaseId/empty）正确、`userMessage` 被复用；`/ask/stream` 同序；零资料分支不调模型、回答非空、`finishReason=empty` | PASSED |
| 全量回归 | `mvn -B test`：306 运行 / 288 通过 / 18 跳过（7 个 live 类）/ 0 失败，BUILD SUCCESS | PASSED |
| 前端 | `npm run build`（`vue-tsc --noEmit && vite build`）通过（类型错误会让构建失败，故模板改动也被检查） | PASSED |
| E2E 浏览器（standalone 真机） | `verify-agent-chat.mjs` 的 **Agent 腿**：默认模式为 Agent、工具卡片出现（`检索知识库`）、摘要从占位变成真实结果（"已降级为关键词匹配（1 条）"）、275 字回答渲染完成、刷新后工具痕迹 4→4 条仍在 | PASSED |
| E2E 浏览器（standalone 真机） | 同脚本的 **RAG 腿**：知识库选择器读到「我的笔记」，创建的会话带 `kbIds=['2105265847872802817']`（浏览器里点出来的绑定链路是通的）；但提问在检索处返回 4005，检索卡片与顺序断言无法验证 | **SKIPPED**（standalone 无 Elasticsearch；脚本退出码 2） |
| E2E 浏览器（standalone 真机） | `verify-stream.mjs`（知识库问答流式 + "检索到 N 个片段"） | **SKIPPED**（同上，4005；退出码 2） |
| 前端契约（桩 SSE 帧，真浏览器） | `verify-retrieval-ui.mjs`：桩掉 `/api/chat/stream`，断言检索卡片文案「检索到 2 个片段」、命中列表 2 条且带文档名与标题路径、`已重排` 标签、**卡片排在回答之前**（`compareDocumentPosition`）、请求体带 `conversationId`/`content`；零命中变体显示「知识库里没有命中」；并断言"服务端校正后卡片消失、答案仍在"这个已知缺口（会话创建与消息校正仍走真接口） | PASSED |
| 真机 | ES / pgvector / MySQL 上的检索与 RAG 全链路 | **UNAVAILABLE**（Docker 守护进程未运行，`deploy/docker-compose.yml` 起不来） |

**口径（三层分开说，不许合并成一句"验证通过"）**：

1. **后端发什么帧、按什么顺序**：`RagStreamRetrievalEventTest` 用 MockMvc 抓完整响应体证明；
2. **前端拿到这套帧渲染成什么样**：`verify-retrieval-ui.mjs` 用桩帧在真浏览器里证明
   （卡片文案、命中列表、排序位置、零命中分支）；
3. **真机上检索能不能跑通**：**SKIPPED / UNAVAILABLE** —— standalone 没有 ES（4005），
   Docker 起不来。所以 `verify-agent-chat.mjs` 的 RAG 腿只验到"会话绑定知识库"这一步。

第 2 层是**桩**验证：它证明"给定后端文档化的帧，界面是对的"，
**不证明**后端真的发这套帧（第 1 层的事），也**不证明**真机检索可用（第 3 层的事）。
按 §四十八，不得把第 2 层说成"RAG 检索卡片已在真机验证通过"。

**12. 未做（有意留下）**

- `retrieval` 不回放：命中片段没随消息落库，刷新后检索卡片消失（正文与引用仍在）。
  要回放需把命中写进 `chat_message.metadata`——与工具痕迹同一处，是一件独立的小事，本轮没做；
  这条缺口已由 `verify-retrieval-ui.mjs` 断言成**预期行为**（校正后卡片消失、答案仍在），
  免得将来有人把它当回归来"修"；
- 多库检索：此处当时绑定仍限一个（多库要先把 `ChunkSearchService` 的单库签名改成多库）。
  → **已由 B.46（Phase 5.5）取代**：RAG 会话现在可以绑多个库，逐库检索后跨库归并；
- 历史窗口按**轮数**截断（3 轮 / 300 字摘要），没有按 token 预算；长回答的摘要可能仍然偏长；
- `citation` 事件仍未实现（引用仍随 `done` / `meta.citations` 给出），`approval_required` 同理；
- Agent 模式仍不支持绑定知识库（其检索范围由模型决定），若将来要"限定 Agent 的检索范围"，
  需要把工具参数化（标签/目录/文档）而不是复用会话绑定；
  → **这一条已被 B.35（Phase 4.1）取代**：Agent 现在可以绑定自己的知识库，范围经 ToolContext 传给工具。
- 没有给 RAG 流式加 live 测试腿：`ChatStreamLiveTest` 那一类除了 `AI_CHAT_API_KEY` 还需要
  一个**已建索引**的知识库（ES + pgvector），在大多数环境里必然失败，加了等于制造噪音。
  它的 SSE 顺序已由 MockMvc 契约测试覆盖（顺带更正了 `ChatStreamLiveTest` 的一条过时注释：
  MockMvc 并非"读不到完整事件"，它只是不能在流到达过程中逐行读，"中途停止"才是 live 的必要理由）。

---

### B.35 Phase 4.1 实现记录（Agent Definition：可配置、可绑定、可试跑）

**0. 本增量是什么**

§十五–§十八 要求"Agent 是用户可管理的资源"：创建 / 修改 / 启用停用 / 复制 / 绑定知识库 / 配置提示词与模型。
本轮做**后端**（前端与 Agent 管理页是 4.2，单独一个增量）；同时把 §十七 的
"Definition 与 Runtime 分离"真正落到代码结构上。

**1. 分离落到文件上，而不是停在文档里**

| 文件 | 职责 | 它碰数据库吗 |
| --- | --- | --- |
| `ai/agent/AgentDefinition` | 不可变配置值对象（纯 record） | 否 |
| `app/agent/AgentService` | 资源服务：CRUD、准入规则、Entity ↔ Definition 装配 | 是 |
| `app/agent/AgentRuntime` | 跑一次 Agent：装好提示词/工具/上下文，调用模型 | 否（只认 Definition） |
| `app/agent/AgentResourceController` | `/api/agents` 管理面（CRUD/复制/试跑） | 否 |

`AgentRuntime` 就是改名前那个写死配置的 `AgentService`，**Agent Loop 一行没动**（§六十七 明文禁止推翻）：
仍是 Spring AI 的 `ToolCallingManager`，仍是 `ToolContext` 传用户（B.30 那个静默失效的教训）。
改动只有三处：`AgentPrompts.AGENT_SYSTEM` → `definition.systemPrompt()`、
`Role.CHAT_AGENT` → `definition.modelRole()`、工具"扫全部库" → 随 `definition.kbIds()` 收窄。

> **回链（B.41，4.4）**：上面第一处后来**又改了一次**——不是改回常量，而是变成
> `AgentPrompts.systemPromptFor(definition)`（用户提示词 + 按 `answer_policy` 追加的策略块）。
> 本节写的"提示词来自 Definition"这一点不变，变的是"Definition 里的提示词并非模型最终看到的全部"。
> 详见 B.41。

`AgentDefinition` 放在 `nexus-ai` 而不是 `nexus-app`：它是"Agent 是什么"的抽象，与厂商、存储无关；
依赖方向因此保持 `app → ai`，不需要倒挂。它**不是** MyBatis 实体——Entity 绝不进 Loop。

**2. 内置默认 Agent 是数据库里的真实一行，不是代码里的 if-else 兜底**

这是本增量最值得记的一条决策。「AGENT 会话没选 Agent」有两种实现：代码里 `if (agent == null) 用常量`，
或者数据库里真的存一行"默认助手"。选了后者：

- 写死的兜底**就是** §六十七 禁止的"硬编码 Agent"——用户改不动它，只能改代码；
- 两套路径（"选了 Agent" / "没选 Agent"）意味着两处都要维护模型角色与提示词，迟早不一致；
- 采用真实一行后，"用哪个 Agent"永远只有一条路径（查库），用户在管理页直接改默认助手的提示词，
  `/api/agent/ask`、MCP 的 `qa_ask`、评测三条老入口的行为**一起**跟着变——这正是"可配置"的含义。

配套约束：内置项不可删除、不可停用（5010），因此 `resolveDefault` 取到的必然是 `PUBLISHED`，
AGENT 模式不会出现"没有可用 Agent"的状态。`resolveDefault` 幂等（缺失就补、状态被改坏就修回），
与 `WorkspaceService.requirePersonalWorkspace` 是同一种"按需补齐"。

**3. 状态机必须有后果，否则它只是三个字**

§十八 给了 `DRAFT / PUBLISHED / DISABLED`。若只有存储没有作用，它就是装饰。本轮的落法：

- **DRAFT 不能被会话选用**（5008）——这就是 §十八 "先测试、再发布"里那道闸；
- **DISABLED 不是"悄悄换回默认"，而是明确报错**（5009）。静默降级会让用户以为"我选的 Agent 生效了"；
- **两道校验，缺一不可**：创建/改绑会话时校验（错误在用户操作的那一刻出现），
  **提问时再校验一次**。会话建好之后 Agent 可以被停用或退回草稿——只有第一道校验的话，
  "停用"就形同虚设。测试 `disabledAgentFailsBeforeAnySideEffect` 同时断言
  **失败发生在落库之前**（消息表仍为 0 条），否则会留下"一条用户消息 + 一条永远 STREAMING 的空助手消息"。

错误码与 HTTP 状态：`AGENT_NOT_FOUND(5006)→404`（不泄露存在性，与知识库/会话同规矩）；
`5007/5008/5009/5010→409`（请求格式没问题，是**资源状态**不允许）。
`ErrorCodeHttpStatusMapper` 是穷举 switch 表达式，漏映射会**编译失败**，这条护栏本轮又生效了一次。

**4. 检索范围进 ToolContext，而不是工具入参**

Agent 绑定知识库后，`knowledge_search` 的搜索范围要收窄。范围**不能**做成工具参数：模型既不知道
知识库 id，也没有任何理由允许它指定别人的库。做法是复用 B.30 那条通道——
`AgentContext.of(userId, kbIds, listener)`，`kbIds` 与 `userId` 一起进 `ToolContext`，
框架生成工具入参 schema 时会跳过它，所以始终不会发给模型。

语义上有一条**刻意与 RAG 相反**的约定：`AgentDefinition.kbIds` **为空 = 不限制**（搜该用户全部库，
也就是今天的行为），而 RAG 会话的 `kbIds` 为空是"必须先绑定才能提问"的硬错误。
理由是控制点不同：RAG 的范围由用户显式指定，Agent 的范围是给模型的一个默认视野。
这条差异有单元测试钉着（`emptyKbIdsMeansUnrestricted`），因为搞反的后果是
"以为收窄了范围，其实搜了全部"——不报错，只是悄悄多喂了资料。

顺带兑现一个**早就写在文档里、但一直没有代码兑现**的承诺：`kb_knowledge_base.status`
（V10 引入，注释写着"禁用后不参与检索"）。工具现在跳过 DISABLED 的库，
并把"跳过了谁"写进工具结果（`已跳过：「X」已禁用`）。为什么必须说出来：
静默少搜几个库的表现是"明明有资料却回答不知道"，用户无从判断是检索坏了还是库被禁了。
若绑定的库**全部**不可用，工具结果会明确写"本 Agent 绑定的知识库当前都不可用，
请如实告知用户检索不可用"——不能让模型退回自身知识硬答，那正是关掉幻觉的初衷。

**5. 试跑（`POST /api/agents/{id}/test-chat`）刻意不受发布状态限制**

§十八 的流程是"先测试再发布"。若试跑也要求 `PUBLISHED`，草稿就永远无法被验证，
发布就成了盲盒。因此 `testDefinition` 只校验归属、**不校验状态**；宽松的边界很明确——
只放宽状态，归属一步不让（越权仍是 404）。DISABLED 的 Agent 也允许试跑：
用户停用它之后仍然需要能确认"是不是它的问题"。

**6. 两条 Agent 执行链路，组装收敛到一处**

Agent 有两条真实链路：`AgentRuntime.ask`（一次性）与 `ChatStreamServiceImpl`（流式 SSE）。
它们的差别只在终止操作（`call()` vs `stream()`），**提示词 + 工具 + ToolContext 的组装是同一件事**。
所以抽成 `AgentRuntime.configure(request, userId, definition, listener)`，两条链路都调它。

不抽的话，将来给 Agent 加第二个工具（P0 的下一项 Tool Definition 就会加）只改一处，
症状是"对话能用、流式不能用"——不报错，只是某个入口不生效。这与 B.30 的教训同源：
**同一件事有两份实现，迟早只改一份**。

**7. 刻意不建的字段与表（"假旋钮"比缺失更糟）**

方案文档 5.2 里列的 `agent` 完整字段是
`… max_iterations, timeout_ms, memory_config JSON …`。本轮**只建了有机制读的**：

| 没建 | 理由 |
| --- | --- |
| `max_iterations` | 轮数上限在框架的 `ToolCallingManager` 里，读了也没有生效路径 |
| `timeout_ms` | 超时在 HTTP 层（SSE 超时常量），同样无处消费 |
| `memory_config` | 记忆策略目前由模式决定（RAG 不用 advisor），没有第二套策略可选 |
| `agent_tool` 表 | 现在只有一个内置工具，注册表要等 Tool 体系（P0 下一项）落地才有内容 |
| `agent_run` / `agent_run_step` | §二十二 的审计属于可观测性阶段；痕迹今天落在 `chat_message.metadata` |

存下来但没人读的配置**比没有这个配置更糟**：用户以为配了就生效。
这些会随对应机制一起落地，届时是 `ALTER TABLE ADD COLUMN`（§四十九 只增不改）。

**8. 验证（如实记录）**

| 层次 | 内容 | 判定 |
| --- | --- | --- |
| Unit | `AgentDefinitionTest` 6 项：kbIds 防御性拷贝（改原列表不影响定义）、去重保序、空 kbIds = 不限制、null 被拒、身份字段缺失即失败、`describe()` 如实说范围 | PASSED |
| Contract（HTTP） | `AgentResourceTest` 10 项：创建落 DRAFT/非内置/绑定随详情返回、内置置顶、**新用户的列表里就有内置项**、code 重复 5007、code 非法 1001、模型角色未配置 4006、绑别人的库 3001、发布后可用（5008→可用）与停用后 5009、状态值非法 1001、空列表解绑、复制（`-copy`/DRAFT/带绑定）、删除（自己可删、内置 5010、内置不可停用但可改名改提示词）、越权一律 404、`resolveDefault` 幂等 | PASSED |
| Contract（HTTP） | `ConversationAgentBindingTest` 7 项：AGENT 选用已发布 Agent（创建与详情都带 agentId）、不选 Agent 时 agentId 为空、PLAIN/RAG 选用被拒 1001、草稿 5008、别人的 Agent 404/5006、改绑（AGENT 可、PLAIN 被拒）、**提问时 Agent 已停用 → 409/5009 且消息表仍为 0 条** | PASSED |
| Contract（工具范围） | `KnowledgeSearchToolScopeTest` 5 项：未绑定搜全部、绑定两个库时第三个库**一次都没被搜到**（`never()`）、禁用库被跳过且如实报告、全部不可用时明确说"检索不可用"+ 悬空绑定被指出、空列表等价于不限制 | PASSED |
| 全量回归 | `mvn -B test`：**334 运行 / 316 通过 / 18 跳过（7 个 live 类，环境变量门控）/ 0 失败 / 0 错误**，BUILD SUCCESS（nexus-common 14 / nexus-ai 35 / nexus-knowledge 122 / nexus-app 163） | PASSED |
| 真机冒烟（standalone，无 Docker） | 打包后用 `--spring.profiles.active=standalone --server.port=8099` 起服务：注册/登录取 token → `GET /api/agents` → `POST /api/agents`（落 DRAFT，返回 `kbIds:[]`）→ `GET /api/agents`（内置项置顶）→ `POST /api/agent/ask`（无 key → 4006，信息里列出已注册模型）。见 §11 | PASSED |
| 前端构建 | 本轮**未改前端**，因此没有重跑 `npm run build` | **未执行**（4.2 已补跑，见 B.36 §6） |
| 真机（真实模型跑一次 Agent、真实检索） | 需 `AI_CHAT_API_KEY` + ES/pgvector；`AgentLoopTest` 被环境变量门控，本机未设置 | **SKIPPED** |
| E2E 浏览器（Agent 管理页） | 页面尚不存在（4.2） | **UNAVAILABLE**（本轮不做） |

**口径**：上表 PASSED 的三类都是**离线契约**——它们证明"接口的准入规则与范围计算是对的"，
**不证明**"接上真实模型后 Agent 会正确调用工具"（那是 `AgentLoopTest` / live 层的事，本轮 SKIPPED），
也**不证明**前端能用（页面还没写）。按 §四十八，不得把这三层合并成一句"Agent 配置化已验证通过"。

**9. 本轮发现、但有意没修的问题**

`kb_knowledge_base.status` 的语义承诺只在**新写的 Agent 工具路径**里兑现了；
RAG 链路（`ChatRagModeServiceImpl` / `RagAnswerService` 的检索）仍然读不到它——
也就是说"禁用一个库"对 RAG 问答不生效。没有顺手一起改的原因：
RAG 链路是 §六十五 明列的保护区，且"禁用/启用的语义边界"值得单独一个增量 +
自己的回归（用户可能期望的其实是"禁用的库连文档列表也不可检索"）。把它记在这里，
而不是假装它已经一致。

**10. 未做（有意留下）**

- **前端（4.2）**：Agent 管理页（列表/新建/编辑/绑定知识库/启停/复制/试跑）、
  创建 AGENT 会话时的 Agent 选择器、E2E 脚本；
- **Tool Definition**（§十九，P0 的下一项）：`tool` 表 + Registry + Factory + Executor，
  届时 `AgentRuntime.configure` 里那句写死的 `ToolCallbacks.from(knowledgeSearchTool)` 换成按 Definition 组装；
- `agent_tool` / `agent_run` 两张表与 §二十二 审计；
- `duplicate` 的 code 生成是 `-copy` / `-copy-2` 递增，未考虑用户手工占用了这些名字后的极端情形（会继续递增，不会冲突）；
- Agent 的 `description` / `builtin` 未参与任何检索或提示词组装（纯展示字段）。

**11. 打包后在 standalone 上冒烟（抓到两个"离线契约测不出来"的问题）**

提交前把 jar 起在 8099 上真跑了一遍接口。两件事只有真机才暴露：

1. **新用户的 Agent 列表是空的**（已修）。内置默认 Agent 是"按需创建"的，
   而最先触发创建的只有**执行路径**（`resolveDefault` 被 `ask` / 会话执行调用）。
   于是一个从没跑过 Agent 的用户打开列表看到 `total=0`——界面上看不出 AGENT 模式有兜底，
   得先"盲跑一次"它才出现。修法：`AgentService.list` 先 `resolveDefault(userId)`
   （幂等，同一用户至多一行），并补 `AgentResourceTest` 第 10 项钉住它。
   之前那条测试是**手工调 `resolveDefault` 绕过**这个问题的（`// 内置默认 Agent 也在列表里`），
   也就是说旧断言在"列表自己会补齐"这件事上是**假的通过**——现在把绕过删掉了，断言才算真的。
2. **H2 profile 的表是 Flyway 建的，不是 `db/h2/schema.sql`**（只改注释与文档，见 §1.7）：
   启动日志 `Successfully applied 11 migrations to schema "public", now at version v12`。
   这解释了"`schema.sql` 里没有 `agent` 表，而 4.1 的测试照样能往 `agent` 里写"——
   也让两个 profile 里那句 `spring.flyway.enabled: false`（"H2 下不使用迁移脚本"）
   被证实是**注释与现实不符**：它只关自动配置，关不掉 `PrimaryDataSourceConfig` 手工声明的 Bean。
   顺带的收获是**这其实是好事**：H2 用的就是 MySQL 那套 DDL，
   "standalone 上验过"与"local 上验过"在表结构这一层是同一件事。

> 记这两条的用意：它们是 §四十八 说的"验证层次不能合并"的实例——
> 上面 PASSED 的 HTTP 契约测试全绿，仍然盖不住"第一次打开页面看到空列表"这种问题。

---

### B.36 Phase 4.2 实现记录（Agent 管理页 + 会话选 Agent：界面把两个后端死路逼出来了）

**0. 本增量是什么**

B.35 把 Agent 做成了资源，但只有接口没有界面——§十五/§十六 要求的"创建 / 启停 / 复制 / 绑定知识库 /
配提示词"在浏览器里一步都做不了。本轮补上：Agent 管理页（生命周期全流程）、对话页的 Agent 选择器、
E2E 脚本，以及**做界面时才暴露出来的两个后端问题**（下面 §3、§4）。

**1. 页面的主线是生命周期，所以按钮的顺序就是状态机的顺序**

§十八 的流程是"先测试、再发布"。管理页把这条流程直接铺成卡片上的按钮序列：

| 状态 | 卡片上的按钮 |
| --- | --- |
| `DRAFT`（草稿） | 试跑 · 配置 · **发布** · 复制 · 删除 |
| `PUBLISHED`（已发布） | 试跑 · 配置 · **停用** · 复制 · 删除 |
| `DISABLED`（已停用） | 试跑 · 配置 · **重新启用** · 复制 · 删除 |
| 内置项（任意状态） | 试跑 · 配置 · 复制（**没有**停用与删除） |

按钮映射由状态算出，不是各写一套。E2E 因此可以断言一件比"文案对不对"更有价值的事：
**草稿的按钮里"发布"恰好一个、"停用"恰好零个**——草稿本来就没生效，给它一个"停用"按钮，
用户会以为它已经在跑了。

三条刻意写进界面的约束（都不是文案洁癖，各自对应一个真实误解）：

1. **状态必须看得见**：草稿/已停用标在卡片上，而不是让用户绑到会话里再吃一个 409；
2. **检索范围必须看得见**：`kbIds` 为空显示"全部知识库"——它与"知识库问答会话必须绑一个库"**正好相反**，
   是本项目最容易读反的一处语义（B.35 §4）。不写出来，用户会以为"没绑库所以搜不到"；
3. **试跑对草稿开放**：草稿正是要被测的对象（B.35 §5）。试跑弹窗里还会显示"本次跑的范围"，
   与线上一致，否则会出现"试跑正常、用到会话里搜不到"这种没法提前发现的情况。

试跑结果里另有一条**会失败的断言式提示**：若这一轮 `usedTools=false`，界面直接写
"这一轮模型没有检索……别急着发布"。带着知识库范围试跑却一次都没检索，
"试跑通过"这个结论本身就是假的。

> **这条警告后来真的报了一次误警，而它是对的**（E2E 抓到，已修）：配好模型再跑 E2E 时，
> 试跑腿失败于"没有使用任何工具"。查下来不是检索坏了——`applyTestQuestion` 在
> **绑了知识库时**填的示例问题是"我的笔记里**和这个问题**相关的内容是什么？"，
> 而"这个问题"没有指代对象。模型的反应完全正确：它反问"你指的是哪个问题"、一次都不检索。
> 错的是样例本身：绑了库的 Agent 恰恰最需要一个有主题的问题，而我给了它一个没有主题的。
> 修法是两种情况都问具体的（绑库时范围已确定，可以更短：`缓存击穿怎么解决？`）。
> 教训与 §1 上面那条相关：**界面上任何"自动填好"的内容都要能被直接执行**，
> 否则用户按一下按钮就吃到一条误导性的警告。

**2. 选择器只列已发布，但**不**静默隐藏草稿**

对话页的 Agent 选择器只有两类项：**内置默认 Agent** + 自己**已发布**的 Agent。
草稿与已停用不出现在选项里，但会出现在下方一行提示：

> 还有 N 个 Agent 不能选用（草稿需先发布、已停用需先启用）：xxx —— 到「Agent 管理」里试跑后发布

为什么不干脆隐藏干净：用户刚建完一个 Agent，回到对话页发现"我建的 Agent 不见了"，
只会以为功能坏了，而不是"它还没发布"。E2E 的 ⑦ 就是钉这句话的（草稿被静默隐藏即失败）。

**3. 界面逼出来的后端死路：选过 Agent 之后就回不到内置默认**

`PUT /api/chat/conversations/{id}` 是**局部更新**接口，`agentId: null` 的含义是"这个字段不改"
（与 `kbIds` / `title` 同一套约定）。于是"清空选用的 Agent"没有任何表达方式——
一个值不能同时是"不改"和"清空"。

这不是新问题，但在此之前**没有界面会去点它**，所以它只是一条理论上的死路。做了选择器才发现：
用户一旦选了某个 Agent，就只能一直用它，除非新建会话。

修法是加一个显式信号 `resetAgent: true`（而不是把 `null` 的含义改掉——那会立刻破坏
所有"只改标题"的调用）：

```java
if (Boolean.TRUE.equals(request.resetAgent()) && request.agentId() != null) {
    throw new BusinessException(ErrorCode.PARAM_INVALID, "agentId 与 resetAgent 不能同时传");
}
if (Boolean.TRUE.equals(request.resetAgent())) {
    requireAgentMode(mode, "清空 Agent");
    conversation.setAgentId(null);
} else if (request.agentId() != null) {
    conversation.setAgentId(resolveAgentId(userId, mode, request.agentId()));
}
```

两个字段同时传是**自相矛盾**的请求（"换成它"+"清空"），报 1001 让调用方看到自己的 bug，
而不是猜一个意图执行下去。顺带把 `requireAgentMode` 抽成两条路径共用的校验，
确保"清空"和"改绑"的模式限制不会各写一份、迟早不一致（B.35 §6 的同一条教训）。

**4. 试跑的失败原因在界面上被丢掉了（`client.ts` 的坑，已修）**

后端按错误码映射 HTTP 状态（`4006` → **502**，见 `ErrorCodeHttpStatusMapper`）。
axios 因此抛出 `Request failed with status code 502`，而真正有用的那句
`模型 opencode:deepseek-v4-flash 未注册。已注册：[ollama:qwen3:7b]` 在**响应体里**被丢掉了。

试跑弹窗的设计意图恰好是"把后端原话显示在弹窗里"（上面 §1 的第 3 条：没有模型时错误信息本身很重要，
它会列出已注册的角色）——结果拿到的是那句毫无信息量的 HTTP 描述。

修在响应拦截器里（`web/src/api/client.ts`）：抛出的错误 `message` 用后端消息，`status`/`code` 保留在
错误对象上，需要区分 404/409 的调用方不必回头去翻 response。这个坑同时**挡住了 E2E**——
脚本原本靠错误文案判断"是不是环境缺模型"（决定报跳过还是失败），拿到 HTTP 描述时只能误判。

**5. E2E 脚本（`web/scripts/verify-agents.mjs`）：覆盖生命周期，且会诚实地跳过**

11 条腿，跑的是"用户能不能把流程走通"，与后端契约测试回答的问题不同：

① 内置项可见且显示"全部知识库" → ② 新建落草稿（+ 按钮映射断言）→ ③ 配置提示词并绑库（卡片跟着变）→
④ 复制出的副本是草稿 → ⑤ 发布 → ⑥ 对话页选择器列出它（截图）→ ⑦ 不可选用提示含"草稿需先发布" →
⑧ 试跑 → ⑨ 停用 → ⑩ 停用后不再出现在选择器里 → ⑪ 清理本轮创建的两张卡片。

几处刻意的设计：

- **退出码三态**：`0` 全过 / `1` 真失败 / `2` 有跳过。没有可用模型时试跑跑不了（`4006`），
  这与"Agent 配置是否正确"无关，报跳过而不是失败——否则每台没配 key 的机器都会看到一条假失败。
  配好 key 后这条腿就会真的执行（本轮后来就是这样拿到退出码 0 的）；
- **跳过前先验证错误形状**：若错误文案是 `Request failed with status code`（§4 那个坑回来了），
  判**失败**而不是跳过——那说明后端原因没有透出，属真问题；
- **每次跑用新 code**（时间戳）：跑完会删，但中途失败也不会与上一轮残留撞名（`code` 唯一，撞了就 5007）；
- **按 code 精确定位卡片**（`x` 不能匹配到 `x-copy`），多弹窗场景只挑 `.el-dialog:visible`。

**6. 验证（如实记录）**

| 层次 | 内容 | 判定 |
| --- | --- | --- |
| Contract（HTTP） | `ConversationAgentBindingTest` 8 项（原 7 + 新 1）：新增 `resetAgentReturnsToBuiltin` —— `resetAgent:true` 后 `agentId` 消失、`resetAgent`+`agentId` 同传 1001、PLAIN 模式 `resetAgent` 1001 | PASSED |
| 全量回归 | `mvn -B test`：**335 运行 / 317 通过 / 18 跳过（7 个 live 类，环境变量门控）/ 0 失败 / 0 错误**，BUILD SUCCESS（nexus-common 14 / nexus-ai 35 / nexus-knowledge 122 / nexus-app 164） | PASSED |
| 前端构建 | `npm run build`（vue-tsc + vite） | PASSED |
| E2E 浏览器（真机栈：standalone + 演示数据 + Edge） | `verify-agents.mjs` 腿 ①–⑪ **全部执行、全部通过**，**退出码 0（无跳过）**：试跑腿真的让真模型跑了 4 次 `knowledge_search` 并返回带来源的回答；截图 `agents-list.png` / `agent-picker.png` / `agents-test.png` | PASSED |
| 真机（真实模型 + 真工具） | 内置默认 Agent 与新建 Agent 各跑一次试跑：真模型（`opencode:deepseek-v4-flash`）+ 真工具（检索到演示笔记内容、回答带来源） | PASSED |
| ES / pgvector / MySQL 真机 | Docker（WSL2 后端）不可用 | **UNAVAILABLE** |
| Vue 单测 | 前端无单测框架（历来靠 `npm run build` + 桩脚本 + E2E 三层） | **UNAVAILABLE**（不是本轮新缺） |

**口径**：上表 E2E 与真机两行是**同一台机器、同一次会话里**从"无模型"升级到"有模型"后重跑的
（第一次跑只有腿 ⑧ 跳过、退出码 2，同时抓到了上面的示例问题缺陷）。因此：

- 本机**没有** ES / pgvector，但 `knowledge_search` 在精确检索不可用时**降级为关键词匹配**
  并如实说明（工具结果里写着"精确检索不可用，已降级为关键词匹配（1 条）"）——
  所以这次"真工具"是真的工具链路（含真实调用与痕迹），**不是**真的 ES/pgvector 检索。
  首次因无模型而 SKIPPED 的那次记录不删，两轮都在这里，读者可以看到覆盖面是怎么变化的。
- 头部选择器对"正绑着的 Agent 已被停用/退回草稿"这一情形（`headerAgentOptions` +
  `boundAgentLabel`）**E2E 仍未覆盖**：它要造出"绑了再停用再看头部"的状态，脚本没走这一步。
  该分支只有 **PASSED（`npm run build` 类型检查 + 构建）**，不算 E2E 通过。

**7. 未做（有意留下）**

- **模型角色**：界面只读显示（`服务端默认` / 已配的角色名），不可选——可选值要由服务端的模型注册表决定，
  而"列出角色"的接口还没有。属 Model Config 增量（届时 `modelRole` 下拉才变成真的）；
- **`agent_tool` / Tool Definition**（§十九，P0 下一项）：做了之后 `AgentRuntime.configure`
  里那句写死的 `ToolCallbacks.from(knowledgeSearchTool)` 才会换成按 Definition 组装；
- **Agent 的 usage/成本展示**：试跑结果目前只有工具痕迹与回答，没有 token 用量（`AgentAnswer` 没带）；
- **移动端布局**：卡片网格是 `auto-fill minmax(360px, 1fr)`，窄屏下退化成单列但未专门适配。

---

### B.37 真机栈（`local` profile）首次跑通：两个后端死路 + 一张"看不见的卡片"

**背景**：Phase 4.2 收尾时 Docker（WSL2 后端）还不可用，所以 ES / pgvector / MySQL 那一层一直记着
**UNAVAILABLE**，RAG 腿在 E2E 里只能跳过。用户把 Docker 起来后，第一次用 `local` profile 跑真机栈
（MySQL 3307 / ES 9200 + analysis-ik / pgvector 5433 / Redis / RabbitMQ）。

结果：**同一台机器、同一套代码，只换 profile，就逼出三个缺陷**——两个"只要走真链路就必现"的后端死路，
一个真机才看得见的前端缺陷。这三个都不是 4.2 引入的，而是此前被"无 ES + 无真模型"的环境掩盖着。

**1. `local` 下所有流式对话直接失败：ChatMemory 的方言与执行取到了两个不同的数据源（已修）**

现象：`local` 下任何流式对话都在第一轮就失败，同步接口却正常。

```
bad SQL grammar [SELECT content, type, `timestamp` FROM SPRING_AI_CHAT_MEMORY …]
Caused by: org.postgresql.util.PSQLException: ERROR: syntax error at or near "FROM"
```

方言按 **MySQL** 生成（反引号），SQL 却发到 **Postgres** 执行。根因在 Spring AI 自动配置那个 Bean 的
签名（`javap` 读出来）：

```java
JdbcChatMemoryRepository jdbcChatMemoryRepository(JdbcTemplate jdbcTemplate, DataSource dataSource)
// 内部：builder().jdbcTemplate(jdbcTemplate).dialect(JdbcChatMemoryRepositoryDialect.from(dataSource))
```

它是**分别注入两个 bean** 的——本项目有两个数据源（业务 MySQL + 向量 Postgres），而上下文里唯一的
`JdbcTemplate` 是 `vectorJdbcTemplate`（Postgres），`@Primary` 的则是业务 DataSource，于是两者错位。

Spring AI 其实启动时就警告过，只是夹在 INFO 里：

```
WARN JdbcChatMemoryRepository$Builder - Explicitly set dialect MysqlChatMemoryRepositoryDialect will be
     used instead of detected dialect PostgresChatMemoryRepositoryDialect from datasource
```

**为什么只有 `local` 中招**：`standalone` / `test` 里 `nexus.vector.enabled=false` 关掉了第二个数据源，
只剩一个 `JdbcTemplate`，两者天然一致——测试全绿，问题藏到最后一刻。修法是 `ChatMemoryConfig`
里显式声明仓库，方言与执行都从**同一个** DataSource 推导（见该类的 javadoc），并显式
`@Qualifier("dataSource")` 而不是靠 `@Primary` 兜底——本次故障的成因正是"靠上下文里的唯一候选去猜"。

**教训**：`PrimaryDataSourceConfig` 里那条既定规则（**多数据源场景不要依赖自动配置**）又一次适用；
另外"启动时的 WARN 不是噪音"，它当时就把答案写出来了。

**2. `local` 下工具调用里的嵌入模型永久失效：虚拟线程的 TCCL 看不见 fat jar 里的资源（已修）**

Agent 腿的流式对话超时，日志里是一条看着莫名其妙的链：

```
ToolExecutionException
Caused by: ExceptionInInitializerError
Caused by: IllegalStateException: the embedding dimensions properties file must exist
    at AbstractEmbeddingModel.loadKnownModelDimensions(AbstractEmbeddingModel.java:80)
    at AbstractEmbeddingModel.<clinit>(AbstractEmbeddingModel.java:47)
    at EmbeddingClientFactory.buildOllamaEmbeddingModel(EmbeddingClientFactory.java:98)
```

而**同一个 JVM** 里 `POST /api/knowledge-bases/{id}/embed`（Tomcat 请求线程）刚刚成功过——
所以不是"资源不存在"。加探针实测（构建时临时往 `create()` 里打一行，测完删除）：

```
PROBE thread=VirtualThread[#152,loomBoundedElastic-14]/runnable@ForkJoinPool-1-worker-2
      isVirtual=true
      ambientTCCL=jdk.internal.loader.ClassLoaders$AppClassLoader@6bc7c054      ambientResolves=false
      appLoader  =org.springframework.boot.loader.launch.LaunchedClassLoader@1a6c5a9e appResolves=true
```

链条完全对上了：

1. `AbstractEmbeddingModel` 的**静态初始化**要读
   `new ClassPathResource("/embedding/embedding-model-dimensions.properties")`（`javap` 读了它的
   `<clinit>`），而 `ClassPathResource` 内部走 `ClassUtils.getDefaultClassLoader()`，**优先取 TCCL**；
2. Boot 的 fat jar 里这个资源在 `BOOT-INF/lib/spring-ai-model-2.0.1.jar` **内部**，只有
   `LaunchedClassLoader` 看得见。实测：`ClassLoader.getSystemResource(…)` 对该路径返回 `null`
   （同一 classpath 下 `BOOT-INF/lib/spring-ai-model-2.0.1.jar` 本身可见）;
3. `spring.threads.virtual.enabled: true` 让 Reactor 的 `boundedElastic` 变成**虚拟线程**调度器
   （`reactor-core-3.8.7` 的 `META-INF/versions/21/BoundedElasticSchedulerSupplier` 里就是
   `Thread.ofVirtual().name("loomBoundedElastic-",1)…factory()`，日志里的线程名正是 `loomBoundedElastic-14`），
   而工具调用就发生在 `FluxSubscribeOn → BoundedElasticThreadPerTaskScheduler$SchedulerTask.run` 上；
   实测该虚拟线程的 TCCL 是 JDK 的 `AppClassLoader`，**不是** Boot 的 LaunchedClassLoader；
4. 于是静态初始化失败。**而它是一次性且全局的**：类初始化失败后整个 JVM 里该类永久不可用，
   之后连 Tomcat 线程上的"向量化"接口也一起变成
   `NoClassDefFoundError: Could not initialize class org.springframework.ai.ollama.OllamaEmbeddingModel`
   （这也是"为什么第一次是 IllegalStateException、再点就是 NoClassDefFoundError"的原因）。

顺带用 `jshell` 把 JDK 语义测清楚了（避免写成"猜"）：虚拟线程**可以**改 TCCL；`Thread.ofVirtual()`
直接 `start()` 会继承调用方 TCCL；而用 builder `factory()` 造线程时，
`inheritInheritableThreadLocals(false)` 会让它落到**系统类加载器**——也就是说这条链路的 TCCL 取决于
"谁先造出第一个线程"，本身就不该被依赖。

**修法**：不再赌"第一个请求从哪条线程进来"，在 `EmbeddingClientFactory` 构建模型期间显式把 TCCL
换成应用类加载器（`buildWithApplicationClassLoader`，try/finally 还原）。**没有**去改
`Schedulers.boundedElastic()`：那会影响所有流式链路与三条 profile 的既有行为，爆炸半径远大于问题本身。

**3. RAG 的"检索到 N 个片段"在真机上只活 41ms（前端已修；彻底修法见 §7）**

`verify-agent-chat.mjs` 的 RAG 腿（**首次真正执行**，此前因无 ES 一直跳过）超时，但后端日志显示
retrieval 事件正常发出、回答也落库了。在页面里装了个 5ms 轮询的记录器，量到的生命周期是：

```
streaming START @64ms    card APPEAR @6389ms (answerLen=43)    streaming END @6389ms    card GONE @6430ms
```

**41 毫秒**。原因两件事叠在一起：本地服务 + 本地模型下整段回答几乎一口气到达，所有 SSE 帧在同一个
tick 里被派发（Vue 只渲染一次，卡片和整段回答同时出现）；紧接着 `send()` 的 `finally` 会用服务端数据
"校正"一次列表，而**命中片段没有落库**（`toItems` 的注释里写着这个缺口），于是卡片刚画出来就被覆盖掉。
用户等于从没见过它——这条腿存在的意义正是抓这种"接口全绿、用户啥也看不到"的情况。

修法：本次流的命中片段按 `assistantMessageId`（meta 事件里就有）贴回校正后的那条消息，
至少保证**本次会话内回看得到**；刷新页面仍会丢，那是同一个已知缺口，不是新问题。

**4. `verify-agents.mjs` 腿⑩ 是一条假失败（测试自身的竞态，已修测试）**

腿⑩ 报的是"下拉项没出现"，很容易被当成产品缺陷。实际是脚本自己的问题：它点完"对话"直接去点
`.composer-options` 里的选择器，而页面挂载后会**自动选中最近一次会话**，一经选中
`.composer-options` 就被头部选择器取代——点开的下拉随 DOM 被换掉而消失。同一脚本的腿⑥ 之所以没事，
是因为它在前面多点了 `.rail .new-chat`。修法是让腿⑩ 也先点"新对话"（这同时会把 `pickedByUser` 置位，
自动选中不再覆盖——用的是应用自己的既定机制），**没有改产品代码**。

**5. 两个流程陷阱（不是代码问题，但会白烧一整轮）**

- **fat jar 会静默不更新**：后端 JVM 正在从 `nexus-app-*.jar` 启动时，Windows 下该文件被占用，
  `mvn -B package` 仍然打印 `BUILD SUCCESS` 与 `Replacing main artifact …`，但产物**根本没变**——
  于是"改了代码、跑起来还是老行为"。可靠的判据有三条：`spring-boot:repackage` 的**耗时**
  （真：14.7–17.6s；假：2.0s）、jar 的 mtime/大小、以及把 `BOOT-INF/lib/nexus-ai-*.jar` 拆出来
  `grep` 新加的字符串。正确流程：**先停后端 → package → 验证产物真的变了 → 再启动**。
  （本轮早先还见过它的另一个形态：`Unable to rename …jar to …jar.original`，直接构建失败。）
- **`web/.demo-token` 是 30 分钟 TTL**（`exp-iat=1800`）：一轮长验证跑到后半程会突然 401，
  表现是页面报"未认证或凭证缺失"而不是断言失败。重新 `POST /api/auth/login`
  （`demo` / `Demo@12345`，见 `verify-login.mjs`）刷新即可。

**6. 验证（如实记录，按层分开，不合并口径）**

| 层次 | 内容 | 判定 |
| --- | --- | --- |
| 全量回归 | `mvn -B test`：**335 运行 / 317 通过 / 18 跳过 / 0 失败 / 0 错误**，BUILD SUCCESS（nexus-common 14 / nexus-ai 35 / nexus-knowledge 122 / nexus-app 164）——与 B.36 §6 同一数字，说明本记录两处修法**没有回归** | PASSED |
| Live 真机测试（此前全部 SKIPPED） | `AI_VECTOR_LIVE=true AI_SEARCH_LIVE=true AI_EMBEDDING_LIVE=true`：`LiveEmbeddingTest` 2 + `LiveModelCallTest` 2 + `LiveChunkIndexTest` 5 + `LiveVectorStoreTest` 4 = **13 项全过**；另 `ChatStreamLiveTest` 3 项全过（真实模型流式） | PASSED |
| 真机检索（ES + pgvector + 真嵌入） | 直接读 SSE 原始帧：`event:retrieval` 在第一个 `delta` **之前**到达，`count=4`、`hits[].source="hybrid"`（BM25 与向量两路都命中，例：`缓存击穿 > 互斥锁方案`）、`contextTokens=108`；知识库向量化 4 切片、ES 建索引 4 切片均实测成功 | PASSED |
| E2E 浏览器 `verify-agent-chat.mjs`（真机栈 + Edge） | **Agent 腿**（工具卡片 → 检索 5 条 → 228 字回答 → 刷新后痕迹仍在）与 **RAG 腿**（检索卡片 4 片段且在回答之前 → 46 字回答）**全部执行、退出码 0**——RAG 腿是**首次真正执行**（此前 SKIPPED） | PASSED |
| E2E 浏览器 `verify-stream.mjs` | 首次真正执行（此前整体 SKIPPED）：逐字增长 + 引用 4 条 + 生成期间出现过"检索到 4 个片段"，退出码 0 | PASSED |
| E2E 浏览器 `verify-agents.mjs` | 11 条腿全部执行、退出码 0（含腿⑧ 真模型试跑：用了 2 次工具） | PASSED |
| 其余界面验证 | `verify-login.mjs` / `verify-frontend.mjs` 退出码 0；`verify-retrieval-ui.mjs` 退出码 0（**桩**：手写 SSE 帧验渲染契约，不证明后端真的发这套帧，也不证明真机检索可用——后两件事本表上面两行各自单独证明） | PASSED |
| 前端构建 | `npm run build`（`vue-tsc --noEmit` + vite） | PASSED |
| Docker 部署形态（容器里跑应用 / nginx 提供前端） | 本轮只把 **Docker 当基础设施**用（MySQL/ES/PG/Redis/MQ），应用仍是本机 `java -jar` + Vite dev server | **UNAVAILABLE**（未验，别把它算进上面任何一行） |
| Vue 单测 | 前端无单测框架（历来靠 `npm run build` + 桩脚本 + E2E 三层） | **UNAVAILABLE**（不是本轮新缺） |
| 头部选择器"绑了再停用"分支 | 见 B.36 §6 最后一条，**E2E 仍未覆盖** | 仅构建/类型检查 PASSED |

**口径**：上表把「后端回归」「live 真机测试」「E2E 浏览器」「真机检索」「前端构建」**分开列**，
是因为它们证明的是不同的事（B.36 §6 的同一条要求）：live 测试证明适配器对真实 ES/pgvector 正确，
E2E 证明界面在真机上真的把过程显示给用户，两者互相不能替代。
另外 `verify-frontend.mjs` 原来那句结束语写着"（standalone，无 Docker）"——它其实是跑在**已启动的**
后端上、与 profile 无关，本轮改成如实描述（依赖已启动的后端与演示数据）。

截图留档：`docs/screenshots/agent-chat-rag.png`（RAG 腿成功现场：「检索到 4 个片段」列出 4 条命中，
回答带 `[1]` 引用）、`agent-chat-failure.png`（失败现场：问题已发出、回答区一片空白——
§1/§2 两类死路在界面上都是这个形状，截图本身分不出是哪一次）、
`agents-failure.png`（§4 腿⑩ 的假失败现场）。

**7. 未做（有意留下）**

- **命中片段落库**：把 `retrieval` 与工具痕迹一样写进消息（`chat_message` 已有 tool 痕迹的位置），
  就能删掉前端 §3 那段"贴回来"的特例，也能让刷新后可回放。属下一个增量，不在本轮；
- **Tool Definition（§十九，P0 下一项）**：`AgentRuntime.configure` 里写死的
  `ToolCallbacks.from(knowledgeSearchTool)` 仍未换成按 Definition 组装；
  → **2026-10-01 已做**：4.3 落地为 `ToolCatalog` + `ToolRegistry` + `AgentToolFactory`，
  见 **B.38**（该条描述的时点状态保留不改，只补这条回链）；
- **Docker 部署形态**：`deploy/` 里的 Dockerfile / nginx 仍未实际验证（本轮 Docker 只跑基础设施）。

---

### B.38 Phase 4.3 实现记录（Tool Definition：注册表 + 执行壳，消灭第二份硬编码）

**0. 本增量是什么**

B.35/B.36 让 Agent 变成了资源，但"工具有哪些、谁能用、装哪些"仍写死在代码里：
`AgentRuntime.configure` 里那句 `ToolCallbacks.from(knowledgeSearchTool)`，以及 `McpEndpoint.tools/list`
里手抄的一份 `knowledge_search` 名字 / 描述 / schema。本轮把工具变成平台资源：

| 层次 | 落地物 |
| --- | --- |
| 表 | **V13**：`tool`（用户覆盖行）+ `agent_tool`（Agent 白名单） |
| 代码侧清单 | `ToolCatalog`（从 `@Tool` 方法反射出名字 / 描述 / schema，**唯一的描述来源**） |
| 用户态 | `ToolRegistry`（"有哪些工具、这个用户能不能用、这个 Agent 装哪些"的唯一答案） |
| 装配 | `AgentToolFactory`（取代写死的 `ToolCallbacks.from(...)`） |
| 执行壳 | `ExecutingToolCallback`（计数 / 日志 / 把失败变成观察结果回灌） |
| 接口 | `GET /api/tools`、`PUT /api/tools/{code}`；`PUT/POST /api/agents` 新增 `toolCodes` |
| MCP | `tools/list` 改为从注册表生成；`tools/call` 走同一个壳 |

内置工具从 1 个变 2 个（新增 `calculator`）。

**1. 消灭的是"两份硬编码"，不是设计稿写的"三份"（核对过）**

§5.3 原文说工具注册有两份硬编码（`AgentService.java:47` 与 `ChatStreamServiceImpl.java:142`）。
实际核对：`ChatStreamServiceImpl` 自 4.1 起已委托 `AgentRuntime.configure`，
那里剩下的只是**一个没人用的 import**（本轮删掉）。真正写死工具的地方是两处——
`configure` 与 `McpEndpoint`。

这不是抠字眼：**要消灭的是 MCP 那一份**。它手抄了 `knowledge_search` 的名字、描述与 JSON Schema，
还直接调 `knowledgeSearchTool.search(...)` 绕开整套装配——实现改了描述不改，
MCP 客户端与模型看到的就是过期信息。而漂移的症状是"模型偶尔不调用工具，且没有任何报错"。

修法不是"让两份保持一致"，而是**让第二份不存在**：`ToolCatalog` 从 `@Tool` 方法反射，
`AgentRuntime` 与 `McpEndpoint` 都读它。

> **回链（B.41，4.4）**："描述只有一个来源"这条不变，但 `knowledge_search` 的**描述内容**后来改了：
> 它当时还搭着一句策略（"不要凭模型自身的知识回答与知识库相关的问题"），而策略按 B.41 收敛到了
> `AgentPrompts` 的策略块一处，工具的描述只保留"能做什么、返回什么"。改描述**只动代码**，
> 库里没有第二份要跟着改——这正是本轮把描述收敛到 `@Tool` 方法换来的好处（对比：若描述还在 MCP 里手抄，
> 这次要改两处，且漏一处不会有任何报错）。

**2. 覆盖行：没有行 = 用代码侧默认（启用）——与 §5.3 "按需补行"的设计相反**

设计稿写的是"内置工具按需为每个用户补一行"（与 `AgentService.resolveDefault` 补内置 Agent 同一种做法）。
**本轮刻意反过来**：`tool` 表的行是**用户覆盖**，没有行就用代码侧默认（启用）。

理由：补行方案下，"这个用户有什么工具"在**第一次访问之前无解**——新用户、清库、迁到新环境之后，
工具会**凭空消失**，而症状是"Agent 突然不检索了"（静默降级）。反过来之后，
工具的存在性由代码保证，数据库只记录**用户改过的那部分**（今天只有"停用"）。

代价是 `tool` 表与 `agent` 表**不能照抄同一套模式**：内置 Agent 是真实数据行（可改可删），
内置工具不是。这一点写进了 `ToolDefinition.builtin` 的注释，免得后来的人照着 `resolveDefault`
给工具也写一个补行方法。

**3. `toolCodes` 空 = 不限制（与 `kbIds` 一致）；且"空列表 ≠ 一个工具都不给"**

`AgentDefinition.toolCodes` 的语义**刻意**与 `kbIds` 对齐：空 = 不限制（装该用户全部启用中的工具），
有值 = 白名单。两条减项的优先级是**用户停用优先于白名单**——被用户停掉的工具，
不该因为某个 Agent 的白名单里写了它就复活。

`PUT /api/agents/{id}` 里"空列表"表示**回到不限制**，不是"一个都不给"。后者会把一次手滑
变成"Agent 变哑巴"，而且症状同样是"回答突然不用资料了"。真要停用某个工具，去 `PUT /api/tools/{code}`。

> **副作用（有意，但必须实测）**：4.3 之后**没绑过工具的既有 Agent 会多出一个 `calculator`**——
> 因为"无行 = 全部"。这是产品意图（工具平台化后新工具默认可用），但它改了既有 Agent 的模型输入，
> 所以 §9.7 把"检索路径没有因此改变"单列为一条要回归的事。

**4. 一张只有 4 个业务列的表**：`name` / `description` / `category` / `json_schema` / `source` 一概不建

理由与 V13 文件头、`database.md` §6.0.1 是同一份，这里只记一句话：
它们唯一的真实来源是 `@Tool` 方法，读路径也只能用代码侧那一份，
库里那份是**永远不会被读的死数据**，只会在评审时误导人。
与 V12 不建 `max_iterations` / `timeout_ms` / `memory_config` 是同一条规则（B.35 §7）。

同理，`agent_tool.enabled` 与 `permission` / `approval_required` / `timeout_ms` / `rate_limit`
四列**本轮不建**：今天没有任何一处会读它们。**加列与接上拦截点必须在同一次改动里。**

**5. 执行壳必须同时实现两个 `call` 重载（实测过的坑）**

`ExecutingToolCallback` 包住原始回调做三件事：计数、日志、把失败变成明确的观察结果回灌。

它**必须**同时实现 `call(String)` 与 `call(String, ToolContext)`：接口里带 `ToolContext` 的那个
默认实现只打一行日志就转调无参版本（`javap -c` 读出来的），而 `MethodToolCallback` 恰恰靠这个重载
把 `ToolContext` 注入进工具方法。只实现无参那个，工具就再也读不到"当前用户"——
**症状是"Agent 突然不检索了，一条报错都没有"**，与 B.30 修过的是同一类坑。

失败**回灌而不外抛**：工具抛异常往外走，整轮对话断在这里，用户看到的是"回答失败"；
回灌一句"工具 X 失败：<原因>，请换一种方式或如实告知用户"之后，模型完全可能换个算法
或如实说明（§六十七 要的"可降级"就落在这一句上）。

`McpEndpoint.tools/call` 走**同一个壳**，但用 `invoke()` 取 `Outcome`——因为 MCP 响应里有
`isError` 字段，把失败也报成 `isError:false` 就是对外说假话。

**6. 4.3 新引入的一类不一致：提示词点名了一个这次没装的工具**

在此之前不可能发生：提示词里写的那个工具**永远会被装上**（就写死在装配处）。
现在工具可被用户停用、被白名单收窄，于是出现新组合——内置默认 Agent 的提示词仍写着
"必须先调用 `knowledge_search` 检索"，而这次一个检索工具都没装。
模型会照提示词去用一个看不见的工具，**表现与"检索了没命中"一模一样**。

`ToolRegistry.warnIfPromptNamesMissingTools` 的做法是**只报警不拦截**（计数 `tool.prompt.mismatch`）：
提示词是用户的数据，无权代改；"自动补上工具"又会让停用开关重新变成假旋钮。
真正的解法是把可用工具**生成进**提示词（而不是靠一句写死的名字），
那会改到每一个既有 Agent 的模型输入，需要单独一轮验证——先如实留痕，不夹带。

同类的第二个计数是 `agent.tools.empty`：装配结果是 0 个工具时，模型退化成"只凭自身知识回答"，
对话一切正常——静默降级的经典形状（§1.11 第 10 条）。

**7. 两个内置工具的选择理由**

`knowledge_search` 必须继续可用（新路径下逐条回归老行为）；第二个选 `calculator` 是因为它
**确定性、无网络、无副作用**——能证明"多工具装配"这条缝是真的，又不引入新的失败模式
（对比之下 `http_request` 的第一版会是一个 SSRF 面）。

求值器是手写的递归下降解析器，**不用 `ScriptEngine` / `eval`**：那等于把一个语言运行时
交给模型。数值用 `BigDecimal` 而不是 `double`——`0.1+0.2` 出 `0.30000000000000004`
会被模型照抄进回答里，用户看到的就是"算错了"。

**8. 有意后置（附理由，不是漏掉）**

| 后置项 | 理由 |
| --- | --- |
| `rag_answer` 作为工具 | RAG 模式已有一整套（会话绑库 / 引用 / `retrieval` 事件），暴露成工具要处理"工具的引用怎么进消息"，值得单独一个增量 |
| `http_request` / `web_search` | 先做等于把它们做成**不设防的**（SSRF、出站白名单、超时、限流、以及"调用外部服务要先问用户"）。与 §二十/§二十一 的权限与审批是同一件事的两面，P1 一起做 |
| `permission` / `approval_required` / `timeout_ms` / `rate_limit` | 今天没有任何一处会读它们，**加列与接上拦截点必须同一次做** |
| `agent_tool.enabled` | 白名单的"在不在里面"已经表达了用不用；一个恒为 1、没有写入点的标志位只会让人以为它能关 |
| `tool_invocation` 审计表 | 属 §二十二 审计增量（与 `agent_run` / `agent_run_step` 一起） |
| 工具目录页（前端） | 与 4.1→4.2 同一节奏：4.3 只做后端，界面属 4.4 |

**9. 顺带清掉的两处"注释说 A、代码做 B"（本轮发现）**

都不是功能缺陷，但**留着一个描述已废弃设计的注释比缺注释更糟**：

1. `ToolDefinition` 的类注释还写着"由 ToolCatalog 从实现反射生成、**在补齐时对齐到库里**"，
   `jsonSchema` 字段上写着"反射生成后**落库**"——但 V13 明确不建这些列，代码也从没写过它们，
   **注释描述的是一版被否掉的设计**。已改为"既不落库也不缓存（没有第二份，就没有漂移）"；
2. `ToolSource.parse()` / `ToolCategory.parse()` **没有任何调用方**（`tool` 表既没有 `source`
   也没有 `category` 列），但报错文案写着"可选值：BUILTIN / MCP"，读起来像存在一个接受它们的接口。
   已删除两个方法并注明：**别照抄 `AgentStatus` / `KnowledgeBaseStatus` 那几个枚举**——
   那些值是从数据库列里读出来的、必须能解析；这两个不是。

**10. 验证状态（如实）**

| 层次 | 结果 |
| --- | --- |
| 编译 | 4 模块 BUILD SUCCESS（JDK 21） |
| 全量测试 | **358 运行 / 340 通过 / 0 失败 / 0 错误 / 18 跳过**（7 个 live 类），BUILD SUCCESS；nexus-app 184、nexus-knowledge 122、nexus-ai 38、nexus-common 14 |
| 新增测试 | **23 条**（`ToolResourceTest` 10 / `CalculatorToolTest` 6 / `ExecutingToolCallbackTest` 4 / `AgentDefinitionTest` 6→9） |
| 前端构建 | 本轮未改前端 → **未执行**（4.3 只做后端） |
| 真机栈（`local`） | **未跑**：Docker Desktop 未运行。4.3 改了 Agent 的装配路径（既有 Agent 会多出 `calculator`），**这一条必须在真机上回归**，见 §9.7 |
| E2E 浏览器 | **不适用**：本轮无界面（工具目录页属 4.4） |

> **上面这张表是写下时的状态，保留不改。同日稍后已补跑真机栈**（用户选择"Docker 起来，在真机上验"），
> 结果与**两个由 4.3 引入的真实回归**记在 **B.39**（`topK`）与 **B.40**（工具痕迹）；
> §9.7 那一行同步补了回链。

---

### B.39 4.3 真机验证：`topK` 的"可选"从来没被说出口（反射 schema 在说谎）

**1. 为什么非要在真机上跑**

4.3 之前，"模型能不能检索"这条主链路只有两条路：模型链路和 MCP 直调。改装配的那**一个**动作
（`ToolCallbacks.from(knowledgeSearchTool)` → `ToolCatalog` + 按 Definition 组装）同时经过这两条路，
而测试环境里 `knowledge_search` 永远走的是"检索不可用 → 降级 → 0 条"，**失败与成功长得一样**。
更关键的是：4.3 把 MCP 那句手写的 `arguments.path("topK").asInt(5)` 删了（那是第二份 schema，
删得对），却没意识到**它同时是一个 bug 的掩体**。

**2. 抓到的回归（真实输出）**

在真机栈（MySQL 8.4 + ES 9.4.5/ik + pgvector + bge-m3）上，用 MCP 直调路径（不经过模型）：

```
POST /mcp  {"jsonrpc":"2.0","id":4,"method":"tools/call",
            "params":{"name":"knowledge_search","arguments":{"query":"缓存击穿"}}}
```

HTTP 200，`result.isError: true`，正文：

```
工具 knowledge_search 执行失败：java.lang.NullPointerException:
Cannot invoke "java.lang.Number.intValue()" because the return value is null
```

漏传 `topK` 就把工具打挂了。**4.3 之前不会**——因为那句被删掉的 `asInt(5)` 兜了默认值。

**3. 两层真相，第二层比第一层难看到**

1. **NPE**：`int topK` 是基本类型，参数缺省时 JSON 的"没有这个字段"绑进去得到 `null`，拆箱即炸。
   改成包装类型 `Integer topK` + `topK == null || topK <= 0 ? 默认值 : topK` 即修。
   （把 `null` 和 `<= 0` 归为同一种"没指定"：传 0 或负数的人想要的也不是"返回 0 条"。）

2. **但 NPE 只是症状。真正的缺陷是 schema 在说谎。** 修完 NPE 后再看 `tools/list`，
   反射出来的 `required` 是 **`["query","topK"]`**——而 `topK` 自己的 `description` 写着
   "默认 5，最大 10"。**同一个 schema 里，一处说必填、一处说可以不给。**
   MCP 客户端据 `required` 判定"漏传就是错"，于是这个参数在**外部契约上**从来不是可选的。

   查证方式：把 fat jar 里的 `spring-ai-model-2.0.1.jar` 抽出来，`javap -v` 读
   `ToolParam` 注解的 `required()`，默认值 = **`true`**。也就是说——**"不写 `required` 就是必填"**，
   与 `@ToolParam` 这个名字给人的直觉相反。补上 `required = false` 后才与描述对齐：

   | | 修复前 | 修复后 |
   | --- | --- | --- |
   | `tools/list` 的 `required` | `["query","topK"]` | **`["query"]`** |
   | 漏传 `topK` | NPE（`isError: true`） | **"检索到 5 条结果"** |
   | 传 `topK = 2` | 2 条 | **2 条** |

**4. 为什么之前两轮都没发现（这个形状值得记住）**

| 路径 | 为什么盖住了 |
| --- | --- |
| MCP 直调 | 端点自己写了 `arguments.path("topK").asInt(5)`，**永远**显式传参，走不到缺省分支。删掉它是 4.3 的正确动作，也正是它拆掉了掩体 |
| 模型链路 | 模型**照 schema 传参**——schema 说 `topK` 必填，模型就一定会给。**一个错误的 schema 会让唯一能暴露它的调用方主动绕开它** |

第二行是这件事的一般形态：**契约错了，最听话的调用方是最后一个发现的人**。
所以"反射出来的 schema"不能只断言"字段存在"（B.38 的测试原来只断言了 `properties.query` 存在——
`exists()` 对 `required` 的内容一无所知），必须断言**必填集恰好是什么**。

**5. 已补的回归测试**

`ToolResourceTest.mcpToolsComeFromTheRegistry`（不新增测试方法，把断言加在已有的 MCP 那条上）：

- `inputSchema.required.length() == 1` 且 `required[0] == "query"`（`topK` 的 `properties` 仍要存在）；
- 一条 `tools/call` 只给 `query`：`isError == false`，且正文**不含** `NullPointerException` / `执行失败`。

两条一起才拦得住：只断言 `required` 内容，改回 `int` 也不会红；只断言调用不报错，
`required` 继续撒谎也没人管。

**6. 真机栈上逐条复核通过（本轮）**

| 项 | 结果 |
| --- | --- |
| V13 迁移 | 在真 MySQL 8.4 上应用成功（`tool` / `agent_tool` 建出） |
| 覆盖行写入 | `PUT /api/tools/calculator {"enabled":false}` → `tool` 表落一行该用户的覆盖行；再切回 `true` 幂等 |
| 目录反映覆盖 | `GET /api/tools` 的 `enabled` 随覆盖行变化，**代码侧默认值未被改写** |
| 装配生效 | 停用后该用户装配结果少了对应工具；`agent_tool` 绑定 / 解绑 / 清理（删 Agent 后无残留）均正确 |
| 错误码 | 不认识的 code → **5011 / HTTP 404**，报文里列出可用值 |
| 执行壳计数 | `/api/observability/summary` 的 `tool.call.succeeded` 随调用增长（本轮实测 = 2） |
| 检索本体未被 4.3 破坏 | 在真 ES + pgvector + bge-m3 上，`knowledge_search` 正常返回真实片段（`topK` 缺省 5 条、`topK=2` 两条） |
| 模型驱动装配 | 写本表时**未验证**：`AI_CHAT_API_KEY` 未设置，日志为 `模型 opencode:deepseek-v4-flash 未注册。已注册：[ollama:qwen3:7b]`。`AgentRuntime.configure` 要先拿到 `ChatClient` 才会走到工具装配 ｜ **同日稍后已跑通**：key 用的是 shell 里那个 `oc_` 开头、被误命名为 `ANTHROPIC_API_KEY` 的值（`oc_` = opencode）。装配行 `装配 2 个工具：[knowledge_search, calculator]`，两个工具都真调了，见 **B.40** |

**7. 现场清理（不留测试残留）**

验证过程写入的覆盖行（demo 用户的 `calculator`）与临时 Agent 已删除：`tool` 覆盖行 = 0 条、
`agent_tool` = 0 条，回到验证前的状态——**用真机验过的东西，不能在用户的数据里留下半个**。

---

### B.40 4.3 的第二个回归：工具痕迹是工具**自己**报的，于是 calculator 在痕迹里不存在

**1. 怎么撞上的**

B.39 那次真机跑模型腿时，顺手看了日志，发现同一行回答里两句话对不上：

```
ExecutingToolCallback - 工具 calculator 执行成功，耗时 0 ms
AgentRuntime          - Agent 回答 … → 工具调用 0 次，耗时 1651 ms
```

工具执行成功了，日志说调用了 0 次。用一个纯算术问题（模型只调 calculator、不调检索）复现，
稳定成立。

**2. 根因：痕迹的**责任方**放错了层**

过程痕迹（`onCall` / `onResult`）不是由执行壳收的，而是**各工具自己**调
`AgentContext.call/record` 上报的。`KnowledgeSearchTool` 报，`CalculatorTool` 不报。
`ExecutingToolCallback` 压根不知道工具有没有报过。于是"这个工具忘了报"的表现是：

| 层 | 症状 |
| --- | --- |
| `AgentRuntime` 日志 | "工具调用 N 次"少算 |
| `AgentAnswer.toolCalls` | 少一条；`usedTools = !trace.isEmpty()` 变成 **false**（工具明明跑了） |
| 流式对话 SSE | 没有 `tool_call`、没有 `tool_result` —— 界面上一张卡片都没有 |
| `EvalService` | 按 `toolCalls().size()` 记评测，记成 0 |

**3. 为什么 4.3 之前不可能出现**

4.3 之前只有 `knowledge_search` 一个工具，而它自己报，所以 `trace.size()` 与真实调用数
**恒等**。这个洞一直都在，只是没有任何输入能让它显形。第二个工具一登记，它立刻显形。

这与 B.39 是同一个形状，值得单列出来：**4.3 做对了事，同时照亮了底下早就错的东西。**
两处的共同点是"原来只有一条路 / 一个工具，所以某个恒等关系看起来像不变量"。

**4. 修法：不靠"记得报"，靠把兜底责任挪到壳上**

先说被否掉的两个方案：

- **"在 `CalculatorTool` 里补两行 `AgentContext.record`"**——能修好今天，但下一个工具的作者
  仍然要"记得"，而忘记的症状是**静默少报**（不是报错）。靠约定维持不变量，等于没有不变量。
- **"壳在 ToolContext 里装一个计数包装器，数到 0 就兜底"**——能自动判断，但要往**同一个请求
  共享的** ToolContext map 里临时塞状态。`AgentContext` 的类注释里已经记着一次"线程相关的想当然"
  导致的静默失效（ThreadLocal 那次），不值得再赌一次框架的执行线程模型。

采用的方式：**登记时就声明清楚这个工具会不会自报**（`ToolCatalog.Declaration.selfReports`），
壳对 `false` 的工具在调用前后各补一条通用痕迹。

```java
boolean backstop = !tool.selfReports();
if (backstop) AgentContext.call(toolContext, tool.code(), toolInput);
… 执行 …
if (backstop) AgentContext.record(toolContext, new ToolCallRecord(tool.code(), toolInput, clip(result)));
```

两个细节：

- **通用痕迹的 `input` 用模型给的原始 JSON，壳不去解析它**——解析等于又造一份 schema。
  代价是 `input` 的形态在两类工具间不一致（检索是自然语言 query，计算是 `{"expression":"…"}`），
  这是如实换来的；
- **失败也要留痕**（摘要写"执行失败：<原因>"）。否则界面上是"没有这一步"，而不是"这一步没做成"。

**5. 这个标志位凭什么不会变成下一个"假旋钮"**

`selfReports` 是一个**代码里声明的布尔值，读错了会静默少报**——听起来正是 §六十七 要防的东西。
所以它必须配一个**不依赖它自己**的断言：

> **不变量：每一次成功的工具调用，恰好产生一条痕迹。**

`ToolResourceTest#everyToolLeavesExactlyOneTrace` 对**注册表里的每一个工具**跑一遍真实调用
（走执行壳 + 真 listener），断言 `trace` 恰好 1 条、且 `trace[0].tool()` 就是它自己。
它不关心"谁来报"，只钉结果——所以标志位写错、或者将来有人新增工具时忘了管这件事，都会红。

**这个守卫本身也验过**：把 `calculator` 的 `selfReports` 故意改成 `true`（= 谎称自己会上报），
测试立刻失败：

```
[calculator 成功执行后必须恰好留下一条痕迹]
Expected size: 1 but was: 0
```

同一时刻日志里仍写着"工具 calculator 执行成功"——与线上那个 bug 一字不差。
改回 `false` 后恢复绿。

**6. 真机复核**

修复后在同一栈上重跑那个纯算术问题：

```
ExecutingToolCallback - 工具 calculator 执行成功，耗时 12 ms
AgentRuntime          - Agent 回答 … → 工具调用 1 次，耗时 3574 ms
```

1 对 1。全量测试 **359 运行 / 341 通过 / 0 失败 / 0 错误 / 18 跳过**（比 4.3 基线 +1 条）。

**7. 顺带说明：`AgentAnswer.usedTools` 的语义因此对齐了**

它的注释写的是"是否真的发生了工具调用"，而修复前它在"只调了 calculator"时是 `false`——
注释与实现对不上。现在它名副其实。**没有改它的定义**，只是让它真的成立。

---

### B.41 Phase 4.4 实现记录（没有知识库 ≠ 拒绝服务：把回答策略收成一处）

> 编号说明：这里的 4.4 是**平台化**序列（与 B.35 的 4.1、B.36 的 4.2、B.38 的 4.3 同一串），
> 与 `roadmap.md` 的 Phase 4.4（学习路线生成）**不是同一件事**——后者的 4.1~4.3 也另有所指。

**1. 怎么撞上的**

用户报的是一句产品级的话：**"当前若没有知识库，发出问题会回答没有对应知识，无法回答"**。

一个刚注册、**0 个知识库**的账号，问「什么是缓存击穿？该怎么解决？」，实测回答：

```
我检索了你的知识库（尝试了"缓存击穿是什么 怎么解决"、"缓存穿透 缓存雪崩 热点key 互斥锁"、
"Redis 缓存 高并发"等关键词），均**没有找到相关资料**。

根据规则，我不能凭自己的知识来回答知识库相关的问题，所以这里只能如实说明：
**你的笔记中目前没有关于"缓存击穿"及其解决方法的记录**。
```

同一请求的日志：

```
AgentRuntime - Agent 回答 userId=2105483932428976130 … → 工具调用 3 次，耗时 4120 ms
```

三件事同时发生，每一件单独看都有道理，合起来是一次产品事故：

| 现象 | 单独看 |
| --- | --- |
| 模型把 3 次工具调用全烧在**空库**上换关键词 | "换关键词重试"是提示词规则 4 授权的 |
| 白花 4.1 秒 | 3 次都是真检索，只是没有库可搜 |
| 最后拒绝回答 | 这是提示词规则 2/3 规定的**唯一合法动作** |

**2. 根因：同一条策略写在三处**

"检索不到就不许用自身知识"这句话，当时在代码里存在于**三个**地方：

| # | 位置 | 原文 |
| --- | --- | --- |
| 1 | `AgentPrompts.AGENT_SYSTEM` 规则 2 | `检索不到就如实说"没有找到相关资料"` |
| 2 | `AgentPrompts.AGENT_SYSTEM` 规则 3 | `不要用你自己的既有知识回答知识库相关的问题` |
| 3 | `KnowledgeSearchTool` 的 `take == 0` 回灌句 | `（没有检索到相关结果。若问题确实与笔记无关，请如实说明。）` |
| 4 | `knowledge_search` 的 `@Tool` 描述 | `不要凭模型自身的知识回答与知识库相关的问题` |

（第 4 处是**写代码时才发现**的：它还会随 MCP 的 `tools/list` 发给外部客户端——
即"这条策略"比想象的传播得更远。）

三处**互相印证**。模型看到的不是"倾向拒绝"，而是"拒绝是唯一正确动作"。

**3. 要保住的到底是什么**

翻 `AgentContext` 的类注释，能读到这套设计当初要防的事故：**模型把自己的知识当成"你的笔记里写了"
讲出来**。为此付出的代价是"干脆不答"——在**有笔记**的账号上，这是一笔划算的买卖。

但它落在了**最该被照顾的人**身上：刚注册、还没导入任何笔记的新用户，第一次提问就吃一个拒绝。
而这个人恰恰是最可能流失的。

真正该成为不变量的不是"没有资料就不答"，而是：

> **绝不把模型自身的知识说成是用户笔记里的内容。**

这句话既能挡住幻觉（原设计的目标），又不需要在无资料时罢工（新用户的问题）。

**4. 单源化：谁说什么**

修法不是"再改一条提示词"——那只是把三处里的一处改成第四种说法。修法是**把策略收成一处**，
其余各处各归其位：

| 角色 | 归谁 | 为什么 |
| --- | --- | --- |
| **策略**（没有可引用资料时答不答） | `AgentPrompts` 的策略块，**请求期**按 `answerPolicy` 追加 | 机器控制的文本、用户不可编辑；只有这一处决定行为 |
| **用户提示词** | `agent.system_prompt` 那一列 | 用户数据。它表达边界，不承载策略 |
| **观察**（搜了什么、有没有东西） | `KnowledgeSearchTool` 的返回值 | 工具只陈述事实，不再指挥模型怎么回答 |

`AGENT_SYSTEM` 因此变成**策略中立**的：工具优先、引用纪律、**反归属**、反注入、工具预算、中文。
被删掉的两句（规则 2 的后半、规则 3 的全部）从此只出现在策略块里。

两条策略块：`KB_PREFERRED`（默认，可用自身知识但必须注明"不是来自你的笔记"）与
`KB_ONLY`（4.4 之前的旧行为，现在是**显式**选择）。两块**都**禁止把自身知识标注成"来源：笔记"——
区别只在"没有资料时答不答"。

组装点只有一个：`AgentPrompts.systemPromptFor(definition)`，被 `AgentRuntime.configure` 调用。
两条执行链路（`AgentRuntime.ask` 与 `ChatStreamServiceImpl` 的流式）本来就都走 `configure`
（已核对），所以**改一行同时覆盖两条链路**。

**5. 为什么在请求期拼，不写回数据库**

因为 `ToolRegistry` 要读 `definition.systemPrompt().contains(code)` 来发现"提示词点名了、
却没装配的工具"（B.38 的 `tool.prompt.mismatch`）。若把策略块写进 `system_prompt` 那一列：

- 那条检查会把策略块里出现的工具名算成"用户点名"；
- 用户在界面上看到的提示词，与库里那一列不再是同一个东西。

保持 `definition.systemPrompt()` = **用户原文**，策略另存一列，界面分别展示。

**6. 工具只报事实**

`take == 0` 时原来只有两种说法，把几件**性质完全不同**的事混成了一句"没有检索到相关结果"。
现在按早就算好的事实分流：

| 事实 | 条件 | 工具的说法 |
| --- | --- | --- |
| 用户一个库都没有 | `scoped == null && knowledgeBases.isEmpty()` | `你还没有任何知识库，没有可检索的资料。` |
| 绑定的库都不见了 | `scoped != null && knowledgeBases.isEmpty()` | `本 Agent 绑定的知识库都已不存在，没有可检索的资料。` |
| 库在但全不可用 | `!knowledgeBases.isEmpty() && searchable.isEmpty()` | `你的知识库当前都不可用，…已跳过：…` |
| 搜了没命中 | `!searchable.isEmpty()` | `在<范围>里检索了"<query>"，没有匹配的资料。` |

前三句追加一句**事实**（不是策略）：`不要再用不同关键词重复调用本工具——换词也不会有结果。`
这是"3 次调用全烧在空库上"的直接对策：模型当时有权重试，是因为**没人告诉它没东西可搜**。
（实测下来它是**降低而非消除**重试，3 次 → 1~2 次；这句话到底值多少见第 11 条，那里有采样数据。）

留痕摘要同步改成有事可读的一句：`没有知识库，0 条结果` / `绑定的知识库均已不存在，0 条结果` /
`知识库全部不可用，0 条结果（跳过 N 个）` / `0 条结果（无匹配）`。
摘要随消息落库，所以刷新页面后仍在——前端"回答是不是真用了笔记"就靠它。

**7. `answer_policy` 落库（V14）**

```sql
ALTER TABLE agent
    ADD COLUMN answer_policy VARCHAR(16) NOT NULL DEFAULT 'KB_PREFERRED' …AFTER model_role;
```

三条决定：

- **默认 `KB_PREFERRED`**：不这么设，新用户的问题原样保留（那正是要修的）；
- **不写 DML**：整个 migration 目录**没有 DML 先例**（全是 DDL）；而且一条中文长字面量的
  `UPDATE … SET system_prompt = '…'` 会把提示词变成**第三个**真源（另两处是 Java 常量与库里的行）。
  存量改写交给 Java 侧（见第 8 条）；
- **`AnswerPolicy` 放在 `nexus-ai`**：被 `AgentDefinition` 持有逼出来的（依赖方向 `app → ai`
  不能倒挂）。它也是 `nexus-ai` 里**第一个带 `parse` 的枚举**——`ToolCategory` / `ToolSource`
  故意没有，因为它们不从 DB 列解析。这点写进了类注释，免得后来者以为漏了。

**8. 存量升级：只升"还是机器播种的那份文本"的行**

改了 `AGENT_SYSTEM` 救不了**已经存在的**行——库里存着 4.4 之前的旧文案，而旧文案与新的默认策略
**直接矛盾**（一个说"不要用自身知识"，一个是"可以用但要注明"）。

做法是复用 `resolveDefault` 已有的"内置行就地纠正"位置（它本来就在修 `status` 被改坏的行），
加一条分支：若内置行的 `system_prompt` 非空、且 `strip()` 后等于 `AGENT_SYSTEM_LEGACY.strip()`，
就地改写成新的中立体。`AGENT_SYSTEM_LEGACY` 是**历史指纹**，只被这条分支读，不是第二份真源。

- **幂等**：改写后的文本不可能再等于旧指纹，第二次进不来；
- **两边都 `strip()`**：`createBuiltin` 存的是带尾换行的文本块，而 `applyEditableFields` 保存时 `trim` 过；
- **用户改过的一个字都不动**：策略块末尾有**让位条款**（"用户自定义提示词若更严格，以那段为准"）——
  我们无法解析用户写的中文，只能声明让位。这也正是存量"严格型"自定义 Agent 不必改写提示词的原因。

> **代价如实说**：提示词写得**中立**的存量自定义 Agent，行为会从"拒绝"变成"标注后回答"。
> 这正是要修的那件事，而且用户随时可以在选择器里改回 `KB_ONLY`。
> 反过来（不升级存量、只升级内置行）会让"内置 Agent 说可以答、用户自己复制的 Agent 继续拒绝"，
> 那是更坏的不可解释状态。

**9. RAG 与 AGENT 的契约**有意**不同**

| 模式 | 没有可引用资料时 | 谁拦的 |
| --- | --- | --- |
| `RAG` | **拒绝**：`资料中没有相关内容（知识库尚未导入或未建立索引）。` | 服务端短路，不进模型 |
| `AGENT` | 按 `answer_policy`：`KB_PREFERRED` 标注后回答 / `KB_ONLY` 拒绝 | 提示词策略块 |

RAG 不改，因为用户建 RAG 会话时**显式选了库**——契约就是"问我的资料"。而且服务端本来就拦着：
`ChatStreamServiceImpl.requireKnowledgeBaseId` 在没有绑定时直接报
`该会话（RAG 模式）尚未绑定知识库，请先绑定后再提问`，零知识库的用户根本进不了 RAG。
**AGENT 模式因此是"想用自身知识又保留笔记优先"的那条路，RAG 是"这次只查资料"。**

（真机上观察到的一条顺序事实：模型解析（4006）排在 RAG 的绑库守卫**之前**，
注释写明是刻意的——配置问题必须在产生副作用之前失败。所以模型未注册时，看到的是 4006 而不是
那句绑库提示。守卫本身由 `ChatRagModeTest` 钉着。）

**10. 测试**

新增 18 条（全量 **377 运行 / 359 通过 / 0 失败 / 0 错误 / 18 跳过**，4.3 基线 359/341/18）：

| 测试 | 钉住的不变量 |
| --- | --- |
| `AgentPromptsTest`（新，4 项） | 默认提示词**不含**回落规则；策略块恰好追加一次；两条策略只在"答不答"上不同；旧指纹 ≠ 现文本 |
| `AnswerPolicyTest`（新，3 项） | 默认 `KB_PREFERRED`；宽容解析；非法值 → 1001 且提示可选值 |
| `AgentPolicyWiringTest`（新，3 项） | **模型边界**：`AgentDefinition.answerPolicy` → 真正发给模型的那条系统提示；用户提示词原样保留且策略块**只追加一次** |
| `KnowledgeSearchToolScopeTest`（+4） | 四种"没有可引用资料"各自的说法；**四种输出都不含策略句**；`agent.search.empty` 有增量 |
| `AgentResourceTest`（+4） | 新建落默认；PUT 可改且非法值 1001；复制跟着复制；**存量升级只认没改过的行** |

其中最有价值的是两类**去重型**断言：`doesNotContain("不要用你自己的知识回答")`（钉"策略不在观察里"）
与 `countOccurrences(policyBlock) == 1`（钉"策略只追加一次"）。它们防的正是这次事故的形状——
**同一条规则在多处各说一遍，每一处都"没错"，合起来互斥。**

存量升级那条用两个用户各插一行（一个播种旧文本、一个用户提示词）——`resolveDefault` 按 code
`LIMIT 1` 取行，同一用户放不下两种情形。

**`AgentPolicyWiringTest` 是为"模型腿跑不了"专门补的**（见下一条）：它用桩 `ChatModel` 捕获
发出去的那个 `Prompt`，断言的对象因此不是"我们的函数返回了什么"，而是"**模型到底看见了什么**"。
它不需要 API key，在任何环境都能跑——这正是它存在的意义。

> 写它的时候被自己绊了一下，值得记：我最初断言"反归属那句一定在"，结果红了。
> 因为那句（`绝不要把你自己的知识说成是用户笔记里的内容`）住在**默认提示词**里，
> 而用户一旦写了自定义提示词，它就不在链路里——**能兜住任何提示词的只有策略块自己那份**
> （措辞不同：`绝不把自己的知识说成是笔记里的内容`）。
> 于是断言改成"策略块那份必须在"，并另加一条钉默认路径。
> 反归属在**两处**出现不是"多份真源"：默认提示词是**用户可改的数据**，策略块是**不可改的地板**，
> 前者可能被用户删掉，后者必须独立成立。

**11. 真机验证（`local` profile）**

已验证（Flyway 与存量升级）：

- Flyway `Migrating schema nexus to version "14 - agent answer policy"` → `now at version v14`；
- 存量行的 `answer_policy` 全部被 DDL 默认回填成 `KB_PREFERRED`（含三个既有账号的内置行）；
- **旧指纹在本库确实命中**：三个 4.4 之前建的内置行 `system_prompt` 与 `AGENT_SYSTEM_LEGACY`
  逐字节相等（266 字符），而本次新建的行是新文本（278 字符）——升级分支的前置条件是真的；
- 在真行上跑了一次升级：写入旧文本 → `GET /api/agents` → 变回 278 字符的新文本、旧策略句消失；
  再跑一次不变（幂等）；换成用户自定义提示词后**原样保留**（25 字符未动）。

模型腿（`local` profile + 真网关 `opencode:deepseek-v4-flash` + 零知识库账号）：

| 策略 | 工具调用 | 回答是否自述"不是来自你的笔记" | 结论 |
| --- | --- | --- | --- |
| `KB_PREFERRED` | **2 / 2 / 1**（三次同题独立采样） | **3/3** 都自述（界面上另跑六次，6/6 也自述） | 旧的拒绝行为消失 |
| `KB_ONLY` | 1（n=1） | — | 回到旧行为：`根据规则，我不能用自己的知识来代替你笔记中的内容作答` |

KB_PREFERRED 的一次真实回答开头：

```
以下内容不是来自你的笔记（你的知识库目前为空），是我根据自己的知识为你解答的：
## 什么是缓存击穿 …
```

`/api/observability/summary` 里 `agent.search.empty` 有值（与调用次数一致），`tool.call.succeeded` 同步。
`PUT {"answerPolicy":"KB_ONLY"}` → 200 回读一致；`{"answerPolicy":"FREE_STYLE"}` → **HTTP 400 / 1001**，
且库里那一行未被改动（没有半写入）。

> **一处预期落空，如实记下**：本轮原本预期"工具调用从 3 降到 1"。
> 实测是 **1~2**（接口层三次采样 2/2/1；界面上又独立跑了六次，2/2/2/2/1/1——两层落在同一个
> 分布上，**九次里没有一次是 3**），不是稳定 1——`换词也不会有结果` 那句**降低但没有消除**重试。
> 它与旧行为的差别在于：旧行为是**稳定 3 次**（把预算烧光才收手），现在是"通常 2 次就收"。
> **不打算为这个数字再调措辞**：三次样本里已经有一次回到 1，说明模型处在边界上；
> 照着单一样本微调提示词正是本项目反对的"靠约定维持不变量"，要改也得先有更大样本。
> 这一条留给以后想优化的人当起点。
>
> 另外，`KB_ONLY` 那次只调了 1 次（n=1，不足以当结论）：可能是"反正不能用自身知识，
> 搜一次就够"的合理推理。记下来供参考，不当因果说。

界面腿（浏览器，demo 账号 + 真实模型）：两个 E2E 脚本都跑了，都通过（退出码 0）。

- `verify-agents.mjs`：① 管理页 → ② 新建草稿 → ③ 配置（提示词 + **回答策略选 `KB_ONLY` 并回读**
  + 绑库）→ ④ 复制 → ⑤ 发布 → ⑥ 选择器里出现 → ⑦ 草稿被列入"不能选用"并说明原因 →
  ⑧ 试跑（**用了 2 次工具**，两次运行分别问出"缓存击穿怎么解决"/"缓存击穿 解决方案"）→
  ⑨ 停用 → ⑩ 选择器里消失 → ⑪ 删除，连跑两次均绿。
  第 ③ 步是本轮新加的（钉"选择器不是假旋钮"），它这轮**真的执行到了**：
  卡片上的「只依据笔记」文案来自保存后的服务端响应，所以"存下去"与"读回来"是同一句断言。
- `verify-agent-chat.mjs`：Agent 腿（工具卡片 2 条痕迹、`检索完成：5 条结果`、回答 381 字、
  刷新后痕迹仍在）+ RAG 腿（`检索到 4 个片段`且先于回答）都通过。**RAG 腿未受影响**，
  与第 9 条"契约有意相反"一致。

> **⑥ 的第一次运行是红的，红的原因在脚本自己身上——如实记下。**
> 报错是"已发布的 Agent 没有出现在对话页的选择器里"，但产品是好的，**选择器一直在按状态过滤**：
> 用接口和浏览器各测了一遍，`/api/agents` 每次都返回 PUBLISHED 的那一行、下拉里也渲染得出来，
> 而且每次进"对话"页都**真的重新请求**了一次（连进三次共 6 次响应，无缓存复用）。
> 真正的问题是断言等了**错的东西**：下拉里那个「内置默认 Agent」是模板里写死的静态项，
> 列表数据没回来时它照样在，于是"等第一个选项出现"立刻满足，断言读到一个**空**列表。
> 同一个空列表还会让第 ⑩ 步（"停用后不再出现"）**假通过**——那是比假失败更坏的一种绿。
> 修法不是加 `waitForTimeout`：`openAgentPicker()` 在点导航**之前**挂 `waitForResponse('/api/agents?')`
> （列表请求随页面挂载发出，不随下拉打开），把响应体一并取回，断言于是各钉一半——
> **数据里有这一行且状态如实**（`PUBLISHED`/`DISABLED`）+ **渲染里与该状态一致**
> （条数与数据里 PUBLISHED 的条数相等）。故意不写"等 1 秒"：那只是把竞态概率调低，
> 而这次要修的正是"测试比产品先开口"。
>
> 记下来的第二条理由：这条失败**从未在文档里出现过**，因为它只活了一轮就跑掉了——
> 一个只在慢机器上偶尔红的断言，与一个真的产品缺陷，在下一个人眼里长得一模一样。
> 现在它被判为脚本缺陷并已修掉，但如果以后有人看到 ⑥ 变红，先怀疑这里的等待逻辑，别先怀疑选择器。

**零知识库那一屏（用户报的就是这一幕，两个 E2E 脚本都跑不到它）**

`verify-agent-chat.mjs` 与 `verify-agents.mjs` 都用 demo 账号，而 demo 有知识库——
"零库的新用户看到什么"这条分支**两个脚本都覆盖不到**。原先打算"手工看一眼，如实记下"，
后来改成真跑：**新增 `web/scripts/verify-agent-no-kb.mjs`**，每次自己注册一个 `probe-nokb-*`
账号（并用同一个 token 查 `/api/knowledge-bases` 确认它真的是 0 个库），拿它的 token 走界面。
每次运行、退出码 0，五步全过；三屏都留下了截图。

- **提问前**（`docs/screenshots/agent-no-kb-hint.png`）：提示出现在输入框上方——
  "还没有知识库：现在提问，Agent 会用自己的知识回答，并注明内容不是来自你的笔记。"
  前置条件是先自己证明的（同一个 token 查 `/api/knowledge-bases` 得 `total=0`），
  否则那句话可能是为别的理由冒出来的。
- **提问后**（`docs/screenshots/agent-no-kb-answer.png`）：问题就是用户报的那句
  （"什么是缓存击穿？该怎么解决？"），结果三件事都对上了——
  ① 工具痕迹 1~2 条（界面层六次独立运行：2/2/2/2/1/1），摘要写着 **`没有知识库，0 条结果`**
  （F1 措辞 + 新摘要第一次在界面上被看见，也是"未命中"这件事对用户的唯一可见证据，
  它随消息落库、刷新后仍在——第 ④ 步就是刷新后再读一次摘要）；
  ② 回答开头是 **"以下内容不是来自你的笔记（你的知识库目前为空），是我基于自身知识的回答："**；
  ③ 旧的拒绝措辞（`没有找到相关资料` / `不能凭自己的知识`）**一次都没出现**。
- **另一种策略的提示**（`docs/screenshots/agent-no-kb-hint-kb-only.png`）：零库账号手里只有内置
  Agent（`KB_PREFERRED`），所以脚本**在进页面之前**先给这个账号建一个 `KB_ONLY` 的 Agent 并发布，
  再在界面上选中它——提示随之换成
  "还没有知识库，而当前选用的 Agent 是「只依据笔记」：提问会如实回答'没有在笔记中找到可引用的资料'。"
  同一个空知识库、两种策略，用户该期待的结果相反，这句话正是本改动的用户可见面。
  断言两条：出现了 `只依据笔记`，且**没有**同时出现"会用自己的知识回答"（两句一起出现
  = 界面在同时承诺两件相反的事）。
- 这一屏是**界面层**的复现：此前"旧拒绝消失"只有接口级证据（第 11 条那张表），
  现在同一句话在浏览器里、在用户报的那个账号状态下，也被验了一遍。

> **写脚本时又踩了同一个坑，值得记**：第 ⑤ 步的第一版把"建 `KB_ONLY` Agent"写在页面里，
> 结果下拉里等一个**永远不会出现**的选项直到超时。原因和第 ⑥ 条那个竞态是同一个事实的两面——
> **对话页的 Agent 列表只在挂载时拉一次**，页内新建的 Agent 在那个列表里不存在。
> 这不是产品缺陷（用户建完 Agent 会走"对话"页，那是一次挂载，列表是新的），
> 但写测试的人很容易在这里把自己绊倒：**"我刚建的东西为什么不在列表里"这个问题，
> 在页面上和在脚本里答案一样——因为列表是那一刻拍的快照。**

**12. 有意没做**

| 没做 | 理由 |
| --- | --- |
| 服务端 `grounded` 标志 | 那是工具痕迹里已存在信息的第二份拷贝；而"模型有没有真用检索到的资料"在服务端**判不准**（模型可能检索到却不用，也可能答对了却没用上）。判定靠痕迹摘要 + 回答里的自我声明 |
| 把策略块写进库里的 `system_prompt` | 见第 5 条（会破坏 `tool.prompt.mismatch`，并让界面与库不一致） |
| 让工具在 `take == 0` 时**不返回**（直接短路掉模型） | 那等于把"答不答"的决定权从策略挪回工具——正是本轮要拆开的东西 |
| `answer_policy` 的其他取值（如"允许但不要标注"） | 没有第三种需求，且"不要标注"会让第 3 条那个真不变量失守 |

---

### B.42 Phase 5.1 实现记录（`/mcp`：一条免认证的自证身份路径，产品化审计的 P0）

> 编号说明：这条属于**产品化**这条线（`docs/productization-gap-analysis.md`），
> 与 B.35/B.36/B.38/B.41 那条 4.x 实现串并行。它是审计里**唯一的 P0**。

**1. 问题**

`POST /mcp` 在 `SecurityConfig.PUBLIC_ENDPOINTS` 里——**不需要任何凭据**。调用方用
`X-Nexus-User: <任意用户 id>` 声明自己是谁，服务端照单全收：

```java
// 修复前 McpEndpoint.java
private Long resolveUser(String nexusUser) {
    if (nexusUser == null || nexusUser.isBlank())
        throw new IllegalArgumentException("缺少 X-Nexus-User 请求头（个人本机信任模型）");
    return Long.valueOf(nexusUser.trim());
}
```

这条路径上挂着两个工具，都真能跑：

| 工具 | 用这个 userId 做什么 |
| --- | --- |
| `knowledge_search` | 检索**该用户名下全部知识库**的正文片段 |
| `qa_ask` | 用该用户的默认 Agent 跑一次完整问答（读他的笔记、花他的模型额度） |

**2. 为什么它是 P0，而其余 18 条只是 P1**

全应用有 43 处 `CurrentUser.requireId()`、越权一律返回 404 而不是 403、
向量检索的 `kb_id` 过滤写在 SQL 里——这套隔离是全项目做得最扎实的地方（见审计 §1.9）。
而 `/mcp` 把它**整条绕开**：不需要凭据，只要知道或枚举出一个雪花 id
（或从任何日志里读到），就能读走那个人的全部笔记正文并替他花钱。

隔离的口径是"用户 A 不能读用户 B 的任何数据"。**存在一条零成本路径可以直接读**时，
其它地方做得再对也不成立——所以它排在所有功能缺口之前。

**3. 一个重要的事实：这个问题早就被记录过，只是没被修**

`docs/platform-phase3-baseline.md:301` 已经写着：

> `X-Nexus-User`；`.env.example:77` 的 `MCP_INTERNAL_API_KEY` **从未被代码引用**。
> 任何人可达 `/mcp` 即可读任意用户知识库。

同一句话在 `.env.example` 里还留着一个**从未被任何代码读过**的 `MCP_INTERNAL_API_KEY`——
一个看起来像"这条路径其实有 API Key 保护"的死配置。**记下来了 ≠ 修了**：
它当时被归进"如实声明"的清单，而不是待办。这次审计的用处就是把这类"已知但未处理"
从叙述里拽进 P0 清单，并**动手**。

**4. 修法：删掉第二个身份来源，而不是"两者都接受"**

```
/mcp 移出 PUBLIC_ENDPOINTS  →  Long userId = CurrentUser.requireId();  →  X-Nexus-User 整条删除
```

三种可选方案里选了最彻底的一种。**为什么不兼容旧头**（"有 JWT 用 JWT，没有就用头"）：
那会留下两个身份来源，而"身份只有一个来源"正是这个项目一路守的不变量——
保留它等于保留一条**只需要一个头就能绕过全部隔离**的路径，只是多了一行注释。
**想只在本机用就把服务绑到 127.0.0.1：隔离靠网络边界，不靠请求头。**

代价如实说：MCP 客户端的配置要带一个 token（这是所有正规 MCP server 的做法），
本机 MCP 客户端不再"零配置可用"。

**5. 顺手修掉的第二处：兜底不再回传异常原文**

原来 `catch (Exception ex)` 把 `ex.getMessage()` 直接写进 JSON-RPC 的 `error.message`。
现在与 `GlobalExceptionHandler` 同一口径：日志里留堆栈 + `requestId`，
响应体只给 `服务内部错误（requestId=…）`。异常消息里可能带 SQL 片段、内部类名、下游地址，
对客户端毫无用处，对攻击者是免费的侦察。

**6. 先把问题钉成测试，再修**（审计 §6.2 的自认边界就在这里关掉）

审计当时明说：「MCP 越权是读代码 + 读 Security 配置得出的结论，我没有实际发一个跨用户请求去验证」。
所以这次先写 `McpEndpointAuthTest`（5 条），**然后把旧实现临时放回去跑一遍**，
让"这条测试确实能钉住旧实现"变成可复现的事实，而不是信念：

| 用例 | 旧实现下的结果 |
| --- | --- |
| 无凭据 `POST /mcp` → 401/2001 | **`Status expected:<401> but was:<500>`** |
| 只带 `X-Nexus-User` 头 → 401 | **`expected:<401> but was:<200>`**（头自证身份，直接放行） |
| A 的 token + 伪造 `X-Nexus-User: <B>` → 只能看到 A 的事实 | **失败**：工具输出是 `在全部知识库检索了「缓存击穿」，没有匹配的资料`——身份被头改写成了 **B**，工具去查了**受害者**的知识库 |
| 零知识库的调用方 → `你还没有任何知识库` | **失败**：同样被改写成 B |
| 正向对照：带 token 的 `tools/list` | 旧实现下 500（它根本不看 Authorization） |

**5/5 全红**，恢复实现后 **5/5 全绿**。第 3 条那次失败就是这次要修的那件事本身：
**同一个请求，决定"检索谁的数据"的是请求头，而不是令牌。**

断言为什么这么选：test profile 下 ES 与 pgvector 都是 Disabled 实现，"检索到没有内容"是环境噪声，
不能用来判断身份。所以断言的是**只有 A 才可能产生的那句话**——A 名下有一个被停用的知识库，
工具会点名"已跳过：<库名>（已禁用）"。B 的库名一个字都不该出现。身份若被头改写，
A 的库名不可能出现在输出里。

**7. 影响面：一条既有测试的调用方式**

`ToolResourceTest.mcpToolsComeFromTheRegistry` 原来用 `X-Nexus-User: 1` 调 `/mcp`，
新实现下变成 401。**改的是调用者的身份，不是断言**——它验的是"能力清单来自注册表、执行壳唯一"
（B.38/B.39/B.40 的三个回归都钉在这条用例上），那些不变量一个字没动。
身份契约由 `McpEndpointAuthTest` 单独负责。

**8. 有意没做**

| 没做 | 理由 |
| --- | --- |
| 保留 `X-Nexus-User` 作兼容 | 见第 4 条：会留下第二个身份来源，等于没修 |
| `nexus.mcp.enabled` 开关（默认关） | 有了 JWT 这条，开关只是多一个能被配错的旋钮；"想只在单机用"用网络绑定解决更直白 |
| 让"用户停用某工具"在这条直调路径上生效 | 那属于**权限**档（P1），需要连 `tools/call` 的拒绝一起做。只在 `tools/list` 里藏起来而 `call` 仍能调，是更坏的谎——保持如实声明（`McpEndpoint` 类注释第 2 条） |

**9. 测试与文档**

- 新增 `McpEndpointAuthTest` 5 条；`ToolResourceTest` 3 处调用改为 Bearer。
- 全量 **382 运行 / 0 失败 / 0 错误 / 18 跳过**（4.4 基线 377），`BUILD SUCCESS`。
- `.env.example` 删掉从未被引用的 `MCP_INTERNAL_API_KEY`（死配置比没有配置更糟：
  它让人以为这条路径受 API Key 保护）。
- `docs/api.md` §7 与 `docs/deployment.md` §4 的口径从"用头指定用户"改成"带 JWT"。

**10. 真机复验（`local` profile：MySQL + ES + pgvector + 真模型 key）**

迁移到真机的动机：审计 §6.2 那条自认边界说的正是"没有实际发过跨用户请求"。
两个探针账号 + 各自的库（**A 的库停用、B 的库启用**，这样两个人的输出必然落在不同分支）：

| 请求 | 实测输出 |
| --- | --- |
| 无凭据 `tools/list` | `HTTP 401` / `{"code":2001,"message":"未认证或凭证缺失"}` |
| 只带 `X-Nexus-User: 1` | 同上（**旧利用方式彻底失效**） |
| **A 的 token + 伪造 `X-Nexus-User: <B>`** | `你的知识库当前都不可用…已跳过：「liveA-disabled-kb」已禁用。` |
| A 的 token，不带伪造头 | **与上面逐字节相同** |
| B 的 token（对照） | `在全部知识库检索了「缓存击穿」，没有匹配的资料。` |

第 3、4 行相同 = 请求头对身份**零影响**；第 3、5 行不同 = 两种身份确实产生可区分的输出，
而 A 的输出里**从不出现 B 的库名**。带 token 的 `tools/list` 返回
`['knowledge_search', 'calculator', 'qa_ask']`（正向对照）。

**两次作废的尝试也记下来**（否则下次还会踩）：

1. 第一次把中文 JSON 直接写在 Git Bash 的命令行里 —— 控制台是 GBK，`curl` 发出去的字节不是
   UTF-8，后端回 `1002 请求体格式错误`。**这不是产品 bug，是我的工具用法错了**；
   改成先用 Python 以 UTF-8 写文件、再 `--data-binary @文件`。
2. 第二次"看起来通过了"，但**是空断言**：那次 `tools/call` 里两个账号其实都**没有任何知识库**
   （建库的请求同样被编码问题打挂了）。两个空账号的输出本来就一样 ——
   这种验证在身份改错时也会绿。**空断言比失败更危险**，所以给 B 建了启用库、给 A 建了停用库重跑。

**11. 顺带发现的一个环境事实（与代码无关，但会影响下一个人）**

`mvn package` 的 `repackage` 在 Windows 上会失败：

```
Unable to rename 'nexus-app-0.0.1-SNAPSHOT.jar' to '…jar.original'
```

原因不是构建配置，而是**有一个 `java -jar nexus-app/target/nexus-app-0.0.1-SNAPSHOT.jar
--spring.profiles.active=local` 在跑**——JVM 锁住了那个文件（本次是本轮真机验证留下的进程）。
**跑构建前先停掉本机后端**；`mvn clean` 同样会卡在删不掉这个 jar 上。

---

### B.43 Phase 5.2 实现记录（错误处理：一个假旋钮、一本判定表，和一次把范围扩大的真机验证）

> 编号说明：同 B.42，属**产品化**这条线。对应审计里的 P1-5 / P1-7 / P1-10 / P1-11 / P1-13。

**1. 起点：四类"错报"**

| # | 缺陷 | 症状 |
| --- | --- | --- |
| P1-5 | 上传超限没有处理器 | 落到兜底 → HTTP 500「系统内部错误（requestId=…）」；文件太大被打成服务端 BUG |
| P1-7 | 同步 RAG 把模型异常**当成回答**返回 | HTTP 200 + `"生成回答失败：" + ex.getMessage()`，而且这条"回答"会落进 `kb_qa_record` |
| P1-10 | 工具轮数超限被报成 `4001 模型调用失败` | `ErrorCode 5002` 从未被抛出 = 死码；用户会去换模型、查额度，而正确动作是把问题拆小 |
| P1-11 | 工具循环上限"配了但没人读" | 见下面第 2 节——**这条最初差点被修成一个假旋钮** |

**2. P1-11：一个假旋钮，以及为什么不能就这么写上**

审计的建议原话是"把 `spring.ai.tools.limits.*` 显式写进 yml 并写明理由"。
在动手前核对了一遍框架源码（`javap` 看 `OpenAiChatModel$Builder`、`DefaultToolCallingManager`），
结论是**那么做等于什么都没做**：

- `OpenAiChatModel.Builder.build()` 在没传 `toolCallingManager` 时**自己造一个默认的**，
  整个过程**不读** `spring.ai.tools.limits.*`——那两个 key 由 `OpenAiChatAutoConfiguration` 消费；
- 而本项目 `application.yml` 里写着 `spring.ai.model.chat: "none"`，模型全部由
  `AiModelRegistry` 手工构建（ADR-003：同时要对话模型、Agent 模型、裁判模型）。
  **自动配置不运行 → 那两个 key 无人读取。**

改一个没人读的配置，比不配更糟：它让人**以为**已经配好了（这次差点就这么交付）。

所以上限被搬进本项目自己的配置，并**显式传给每一个模型**：

| 位置 | 内容 |
| --- | --- |
| `application.yml` | `ai.tools.max-calls-per-tool-default: 40` / `max-total-tool-calls: 150`，注释写明"这是框架默认值"并标注 ⚠️ 别用 `spring.ai.tools.limits.*` |
| `AiModelProperties.ToolLimits` | 承载两个值的嵌套类，注释解释"为什么不是框架的那两个 key" |
| `AiModelRegistry` | 构造一个 `ToolCallingManager`，两个模型构建器都 `.toolCallingManager(它)`，启动日志打出来 |

`toolCallingManager(…)` 在 2.0.1 里标了 `@Deprecated(forRemoval=true)`，但**框架自己的**
`OpenAiChatAutoConfiguration` 也在调它。不用它就只能接受框架内置的默认值（那份默认值读不到本项目的
配置），因此沿用到正式替代 API 出现，并在注释里写明将来的替代路径。

超限行为固定 `THROW`，不做成开关：另一个选项 `RETURN_ERROR_RESPONSE` 会让模型"带着没有结果的
工具调用继续作答"——一个看起来正常、其实没检索到东西的回答，正是本项目一路在防的静默降级。

**怎么证明它不是第二个假旋钮**：`ToolCallLimitTest` 把上限调到 **1**，然后真的发起第 2 次工具调用，
断言抛出的异常带着工具名与上限；另有一条 `configuredDefaultsMatchFrameworkDefaults` 把本项目的默认值
钉在 `DefaultToolCallingManager.DEFAULT_*` 与字面量 40/150 上——框架改默认值时它会先报出来。

**3. 归因收敛到一处：`FailureClassifier`**

判定表（顺序本身是契约）与每个入口的表现写在 `docs/api.md` §1.2「失败归因表」，这里只记决策：

- **顺序从具体到笼统**：框架的轮数上限 → 业务异常 → 鉴权被拒 → 超时 → 未知。
  业务异常排在类名匹配**之前**，因为它是"已经判定过的事实"，不能被后面的猜测覆盖
  （有一条测试专门钉这个：被 `BusinessException(FORBIDDEN)` 包住的 `*Unauthorized*` 仍报 403）。
- **沿 cause 链上溯**：Spring AI 会包 `RetryExhausted → WebClientRequest → ConnectException`，
  只看最外层等于永远落到"未知"桶。
- **新增 `4007 MODEL_AUTH_FAILED`**，而不是复用 4001/4006。三件事各是一个决定：
  - **不复用 4001**：4001 的文案是"请稍后重试"，而密钥过期重试一万次都是 401——
    对一半人给出错误建议，比不给建议更费时间；
  - **不复用 4006**：4006 是"这个角色压根没接模型"（改配置），4007 是"接了但密钥被拒"（换密钥），
    动作不同；
  - **HTTP 用 502 不用 401**：401 是"你（调用方）没通过认证"，而这里是我们作为网关调上游被拒。
    返回 401 会诱导前端去清 Token、跳登录页——一个完全错误的自救动作。
  - 识别按类名（`*Unauthorized*` / `*Authentication*`…），理由与既有的超时判定相同：这些异常来自
    不同 SDK，逐一 import 会把项目绑死在某个供应商上。**刻意不匹配 `AccessDenied`**：
    在 Spring Security 里那是"我们的接口被越权访问"，与"上游拒绝我们的密钥"是两件相反的事。

**4. 真机验证把这一期的范围扩大了（这是本条最该被记住的部分）**

按上面的改动改完后，用一把**无效 Key** 起真机栈（`--spring.profiles.active=local`），
拿同一个故障打各条链路，发现审计里没写、改动也没覆盖到的两处：

| 入口 | 修复前 | 修复后 |
| --- | --- | --- |
| `POST /api/agent/ask` | **500 / 9999「系统内部错误」** | 502 / 4007 |
| `POST /api/chat/conversations/{id}/messages` | 502 / 4001 + **上游原文**（`401: Invalid API key. (base-url=https://…)`） | 502 / 4007 |
| `POST /api/chat/stream` | 4001 / `retryable=true` | 4007 / `retryable=false` |
| `chat_message.error_msg`（落库） | 上游原文，且会被 `GET /api/chat/messages/{id}` 读回来再露一次 | 给用户的那句话；原文只留日志，靠 `requestId` 关联 |

- `AgentRuntime.ask` 是**四个非流式入口的唯一收口**（`/api/agent/ask`、Agent 试跑、评测集执行、
  MCP 的 `agent_ask`），而它此前一个 `catch` 都没有：同一个"密钥无效"，流式对话回 4001、
  这四个入口回 500。现在两边共用 `FailureClassifier`，所以报的是同一个码同一句话。
- `ChatServiceImpl` 的 `"模型调用失败：" + ex.getMessage()` 既泄露上游原文、又把它存进
  `error_msg`。改成存 `failure.message()`：**这不是换个地方泄露，是关掉这条泄露路径**——
  `error_msg` 会经 messages 接口回到客户端。
- 这两处**不在**审计的清单里，是"跑一次真实的失败"才暴露的。审计是从代码读出来的，
  而"某条链路根本没接上"这类缺陷只有让它真的失败一次才看得见。

**5. 与审计措辞的两处偏离（如实记录）**

1. **P1-7 记的是"失败落 FAILED"，实际做的是"抛异常 + 不落档"**。理由是 `kb_qa_record`
   **没有 status 列**，写进去就是一条"看起来正常的回答"。`finish()` 的既有注释也写着
   "只记录成功的问答"。因此生成失败时 `generated` 为 null，压根没有"半条回答"可记。
2. **上传超限的验证分两层，因为 MockMvc 测不了它**：体积上限由 **Servlet 容器**在解析 multipart
   时判，MockMvc 用的是模拟请求、不经过容器解析。因此：
   - `ErrorContractTest`（探针控制器 + MockMvc）：证明"接到这个异常会返回 413/3006"；
   - `UploadSizeLimitHttpTest`（真实 Tomcat + 真实 multipart，上限调成 8KB/16KB）：
     证明"容器真的会抛这个异常"。载荷只有 64KB：容器拒绝后会尝试吞掉剩余请求体好把响应写回去，
     Tomcat 的 `maxSwallowSize` 默认 2MB——按线上真实体积去测，连接会在响应写出前被中断，
     那测的就变成"Tomcat 怎么处理半截请求"了。

**6. 真机实测（`local` profile）**

1. 无效 Key 起服务：三条链路分别返回上表"修复后"的结果，三个入口**一致**。
2. `chat_message.error_msg` 落库内容 = 给用户的那句话；`GET /api/chat/messages/{id}` 读回来的也是它。
3. **201MB 真实上传**（`max-file-size: 200MB`）→ `413` + `{"code":3006,"message":"文件超出大小限制
   （单文件上限 200MB，单次请求上限 220MB）"}`。文案里是**配置里读出来的值**，不是写死的 200MB：
   写死的数字会在改配置后变成谎言，而这类谎言没人会去测。
4. `mvn test` 406 通过 / 0 失败 / 0 错误 / 18 跳过（基线 382，新增 24 条）；`mvn package` 成功。

**7. 这一期明确没做的**

- **工具级超时**：审计提过，但当前没有会阻塞的外部工具，先不写一个没有调用方的超时旋钮
  （B.38 的取舍一致）。
- **前端没有体积预检**：用户选了一个 300MB 的文件，要等传完才在服务端被拒（现在至少能看懂原因）。
- **`retryable` 前端还没消费**：`error` 帧里的这个字段目前没有渲染方，前端仍只显示 `message`。
  要做成"不可重试就不给重试按钮"，属于前端那一期。
- **`maxSwallowSize` 那条边界未实测**：远超上限的上传（如 1GB）是否会在响应写出前被容器断连，
  只有推理没有实测，因此不作为结论。真要做，正确做法是前端预检 + 后端限制双管。

---

### B.44 Phase 5.3 实现记录（知识库闭环补完：单文件上传 / 删文档 / 失败原因可见）

> 编号说明：同 B.42/B.43，属**产品化**这条线。对应审计里的 P1-1 / P1-2 / P1-3 / P1-4。

**1. 动手前被推翻的三条审计结论**

审计写在 `productization-gap-analysis.md` 里的方案有三处经不起推敲，改了做法：

| 审计原话 | 实际 | 因此怎么改 |
| --- | --- | --- |
| P1-4："给 `DocumentSummary` 补 `errorMsg`" | **那是个死字段。** `DocumentStatus` 全仓只在 `applyMetadata` 被写成 `READY` / `errorMsg=null`，**没有任何路径写 `FAILED`**。逐文件的失败原因落在 `kb_import_task_item.message` | 不补字段。补的是那张表的**读接口**——它此前只有写入方，没有任何人读 |
| P1-2："删文档时清理 chunk / 向量 / ES" | **存量路径今天就在漏，而且不止一条。** `DocumentPersistServiceImpl.delete` 不清向量 / ES / 关联边；`KnowledgeBaseServiceImpl.delete` 更彻底——连 `kb_chunk` 都没删（`documentMapper.delete` 走 `@TableLogic` 逻辑删除，注释却写着"物理删除"），向量与 ES 也不碰，代码里留着一条 TODO 自认 | 只修新端点 = "修了一个，漏了仨"。库级那条一并堵上 |
| （审计没提到） | **孤儿还有第四条路径：重新切分。** 覆盖已有文档时切片拿到新 id，而向量按 `chunk_id` 存 → 旧向量全部成为孤儿。`ChunkEmbeddingService.deleteVectors` 的 javadoc 早就写着"文档被删除或**重新切分**时调用"，但从来没有人从这个方向调用过 | 在 `save()` 的更新分支也加一次外部清理 |

第三条约等于"照着自己的注释读了一遍才发现注释和代码不一致"——javadoc 描述的是**意图**，
不是事实，两者不一致时只有一种可能是对的。

**2. 单文件上传（P1-1）：复用，不新建**

新增一条流水线很容易，但两条流水线会以"其中一条漏了新字段"的方式分叉。
所以上传走的是**同一条**异步导入链路，差异收敛到两处：

- `taskType = UPLOAD` + `kb_import_task.source_name`（迁移 V15，见 `database.md`）；
- **不做删除检测**。`VaultSyncPlanner` 早就在类注释里留了口子：
  `detectDeletions=false` 是给单文件上传用的，否则"这次清单里只有这一个文件"
  会被执行器理解成"库里其余文档都该删"——一个能一键清空知识库的 bug。

顺带处理了两件容易被忽略的事：

- **校验放进服务层**（`ParserRegistry.requireParser` + `requireUploadable`），不只是 Controller。
  否则换个调用方（MCP 工具、批量导入）绕过 HTTP，用户拿到的是含糊的"导入失败"
  而不是 `3005` + "当前支持哪些格式"。
- **绕开压缩包的 20MB 单条目上限**：那是给 ZIP 里来路不明的条目设的防线，
  而直接上传是用户显式点名的文件，多一道 20MB 的闸只会让合法的 25MB PDF 被拒。
  改用压缩包整体上限（200MB），与 Spring 的 `max-file-size` 对齐。

**3. `sourceType` 从硬编码改成参数（新功能引出的数据失真）**

`DocumentPersistServiceImpl.applyMetadata` 写死 `sourceType = OBSIDIAN`，
于是 `KnowledgeSourceType.UPLOAD` 这个枚举值**从来不可达**。
在只有 ZIP 导入的年代这只是无害的冗余；加了单文件上传之后，
用户手工传的文件会被标成 Obsidian 导入，而 `KnowledgeSourceType` 的注释写明
"区分来源是为了判断这次的清单是否覆盖了库里的全部来源"——
那个判断会把用户手工传的文件当成同步范围内的东西。

因此把 `sourceType` 穿透 `save` / `updateMetadataOnly` / `relocate` 三个签名：
Vault 路径传 `OBSIDIAN`，上传路径传 `UPLOAD`。
**是"新功能把旧硬编码变成 bug"的典型**——不是这个改动引入了问题，是它让问题变得可达。

**4. 删除（P1-2）：fail-closed，以及顺序为什么不对称**

删除要动六处数据，少动哪一处都不会报错，只会留下看不见的残留。完整清单见
`database.md`「删除会动哪些表」。这里只记两条决策：

**(a) 外部清理在前，且失败即整体失败。**
向量 / ES 不在 MySQL 事务里、无法回滚，所以顺序只能是"先外部、后 MySQL"：

| 顺序 | 外部失败时会怎样 |
| --- | --- |
| **先外部，后 MySQL**（采用） | 事务回滚 → 文档原封不动，用户重试即可 |
| 先 MySQL，后外部 | 文档没了但向量还在 → 检索命中却取不出正文，且**没有任何入口能修** |

两种失败的代价不对称，所以选择后者不可接受。测试 `purgeFailureAbortsTheWholeDelete`
用 `doThrow` 模拟向量库不可用，断言 5xx、文档 / 切片 / 文件 / 关联边全部原封不动，
并额外断言索引那一步 `never()` 被调用——**顺序本身就是保证**。

**(b) 磁盘文件在删库时放最后，在删单篇时不是。**
文件孤儿只是浪费磁盘；而"文件已删、事务又回滚了"是真的数据丢失。
删库把文件删除放在最后一步，删单篇时它在外部清理之后、事务之内（无害），
顺序跟着代价走，而不是跟着"看起来整齐"走。

**(c) 入链保留是刻意的。** 只删以本文档为源的出链；
别人指向它的边会退化成"待创建的引用"（`resolved=false`），这正是 Obsidian 语义——
删掉一篇笔记时，别的笔记里对它的引用不该消失，那正是"这里本该有篇笔记"的线索。

**5. 墓碑陷阱：一个"加个按钮就能一键复现"的老问题**

`uk_doc_kb_path (kb_id, source_path, deleted)` 把 `deleted` 放进了唯一键，
本意是允许"删了再传同名文件"。但 `deleted` 是 `@TableLogic Integer`，
**第二次**逻辑删除同一路径会写出第二行 `deleted=1`，撞唯一键：

```
传 note.md → 删 → 再传 note.md → 再删     ← 报 "Duplicate entry ... for key 'uk_doc_kb_path'"
```

这句话和用户的操作毫无关系，用户看到的会是一个莫名其妙的 500。
此前只有"重复导入 + 删文件"才碰得到；加了删除按钮就是一键复现。
修法是逻辑删除前先用原生 SQL 清掉同路径旧墓碑
（原生 `@Delete` 绕过 `@TableLogic`，仓库里已有原生 `@Select` 先例）。
`DocumentDeleteTest.deletingTheSamePathTwiceDoesNotCollide` 就是这条的回归。

**6. 失败原因可见（P1-3 / P1-4）：补一个只会写不会读的表，和一段死代码**

`kb_import_task_item` 从建立起来就只有写入方。于是"失败 3 个"是一个没有下文的数字，
用户只能翻服务端日志——而这张表存在的**全部意义**就是回答"为什么这篇没进来"
（它自己的类注释就是这么写的）。新增 `GET /api/import-tasks/{id}/items`：

- `action` 可选过滤（大小写不敏感），`limit` 默认 100、**收敛到 1–500**——
  一个 Vault 上千行明细，不能让一次查询全拖回来；
- 归属校验与 `GET /api/import-tasks/{id}` 同一道，别人的任务一律 `3008`，不泄露文件清单。

顺带发现并堵上一个更隐蔽的洞：**扫描期跳过的条目从来没有被记录过**。
执行器只做了 `counters.skipped += scan.skipped().size()`，没有 `recordItem`——
于是 `ImportAction.SKIPPED` 与 `Counters` 里的 `case SKIPPED` 一直是**死代码**。
后果是用户最常问的那一类问题（"我的 `.pptx` 为什么没进来"）恰恰是明细答不了的。
现在每条都带上原因。

**一处刻意保留的不对称**：整包同步时"内容未变化"的文件**只计数、不逐条落明细**。
第一版实现把 `plan.unchanged()` 全部落库，直接撞上了既有测试
`VaultIncrementalSyncTest.noChangeImportIsZeroWrite`：

```java
assertThat(importTaskItemMapper.selectCount(...))
        .as("跳过的文件不写明细，否则一次重导会插入上千行")
        .isEqualTo(itemsBefore);
```

那条测试是对的先例，**没有改它**，而是把行为收窄到"单文件上传（恰好一行）"才落明细，
并在界面上把这个差异讲清楚（`task.skippedCount` 可能大于明细条数）。
单文件上传的"内容未变化"则如实给出一条 `SKIPPED` + 原因——
因为"传了却什么都没发生"必须有解释来源。

界面上，判据用**任务上的计数**而不是明细条数
（`!task.failedCount && !task.skippedCount`），否则整包同步的正常结果会被显示成"有跳过但查不到明细"。

**7. 与既有约定/测试的交互**

- **构造器循环依赖**：`KnowledgeBaseServiceImpl` 需要清向量与索引，
  但 `ChunkEmbeddingServiceImpl` / `ChunkIndexServiceImpl` 都构造器注入了 `KnowledgeBaseService`
  ——注回去 Spring Boot 3 会在启动时直接拒绝。因此库级清理注入的是**端口**
  `VectorStore` / `ChunkIndex`（它们没有反向依赖），这条取舍写在了类的 javadoc 里。
- **测试 profile 下"无孤儿"证明不了**：`DisabledVectorStore` / `DisabledChunkIndex`
  是**静默 no-op**（不报错，查什么都是空的），所以用真实 Bean 的"删完是空的"断言毫无意义。
  改用 `@MockitoBean` 替换 `ChunkEmbeddingService` / `ChunkIndexService` 并 `verify`
  传入的 documentId；真实存储的 `countByKnowledgeBase == 0` 留给真机/live 门控用例。
- **`refreshKnowledgeBaseStats` 的 O(全库切片) 问题**（本阶段顺带修的一个审计里没有的问题）：
  它原本把整个知识库的 chunk 行读进内存求 token 和。单文档删除会走它，
  大库上这就是 O(全库切片)。改成一条 `COUNT` + 一条 `SUM(token_count)` 聚合。
  **范围说明**：这是探索中发现的，审计里没有。

**8. 测试与构建**

- 新增 4 个测试类 20 条：`DocumentUploadTest`（5）、`DocumentDeleteTest`（5）、
  `KnowledgeBaseDeletePurgeTest`（3）、`ImportTaskItemApiTest`（6），
  `mvn test` **426 通过 / 0 失败 / 0 错误 / 18 跳过**（基线 406，恰好 +20）；
- `mvn package` 成功；`cd web && npm run build` 成功；
- 新增 `web/scripts/verify-doc-lifecycle.mjs`（上传 → 失败原因 → 删除 → 列表消失四段）。

**9. 真机验收：已执行，全绿**

本节初稿写的是"没有执行"（当时 Docker / 前后端都没起）。**同日补跑完毕**，结论如下。

*环境*：`docker compose`（MySQL 3307 / PostgreSQL+pgvector 5433 / Redis / ES 9200 + analysis-ik /
RabbitMQ）+ `--profile ai-local`（Ollama，`bge-m3`）+ 后端 `--spring.profiles.active=local` +
前端 `npm run dev`。验收脚本 `acceptance_all.py`（一次性，在 `target/verify-fixtures/` 下，不进仓库）。

| 验收项（计划原文） | 结果 |
| --- | --- |
| 迁移在真实 MySQL 上落地 | `Successfully applied 1 migration to schema 'nexus', now at version v15` |
| PDF / DOCX / TXT / MD **各上传一次** | 4/4 终态 `DONE`，每个 `created=1 failed=0`；查库 `source_type='UPLOAD'` 4 篇（这是"接口没暴露 ≠ 没写对"的核对，`DocumentSummary` 不含该字段） |
| 向量化 + 建索引 | 真 Ollama `bge-m3` 嵌入、真 ES 写入 |
| 删除前基线 | 每篇**恰好 1 向量 + 1 ES 文档 + 1 切片**，库内向量合计 4（先证基线非空，否则"删完是 0"是空断言） |
| **删后向量 / ES 无残留** | 逐篇删除后：向量 0、ES 0、切片 0、标签 0、**出链 0**、文档行 `deleted=1`、磁盘原件已消失 |
| 整库口径 | 库内向量 0、ES 0；文档列表空；`doc_count=0 / chunk_count=0` |
| 入链语义 | 先删被引用的 `验收纯文本.txt`，`验收笔记.md` 里那条 `[[验收纯文本]]` **仍在**，而它自己的出链已清空 |
| 墓碑回归（本阶段新暴露的路径） | 同一路径"传 → 删 → 再传 → 再删"两轮均成功（`uk_doc_kb_path` 不再被撞） |
| 删库清理（本阶段顺带补的漏） | 库级向量 / ES / 切片全部归零 |
| 界面 E2E | 新增 `verify-doc-lifecycle.mjs` **退出 0**（含三张截图）；既有 `verify-frontend.mjs` 复跑**退出 0** |

`verify-agents.mjs` 退出 **2 = 设计内的跳过**（该脚本把"本机没有可用模型"定义为跳过而非失败）：
后端未设 `AI_CHAT_API_KEY` 时只注册了 `ollama:qwen3:7b`，而默认对话模型是
`opencode:deepseek-v4-flash`，于是试跑那一段报"模型未注册"。与本阶段改动无关。

*脚本本身踩到的四个坑*（都会让验收**假装通过**，记在这里以免下次重犯）：

1. **pgvector 与 MySQL 是两个库**。`docker exec ... psql` 不指定库名时查询落在一张不存在的表上、
   返回空串，`int('')` 变成 0 —— 于是"删除后向量是 0"这类断言**全部通过却是空断言**。
   第一版就这么骗过了自己，直到手工核对"4 行明明在"才发现。
2. **ES 可见性延迟**：`refresh_interval` 默认 1s，删完立刻查会读到删除前状态。断言前必须
   `_refresh` 并留重试窗口。
3. **中文经 `mysql -e` 传下去会被按 latin1 解读**，`target like '%…%'` 于是永远不匹配；
   改用 `--default-character-set=utf8mb4`，并把中文比对挪到 Python 侧做。
4. **`Content-Type: application/json` 不带 `charset=utf-8` 时 Spring 按 ISO-8859-1 解码**，
   中文库名直接变成 `1002 请求体格式错误`——建库接口的第一次调用就是这么失败的。

**10. 这一期明确没做 / 没验到的**

- **没有"批量删除"入口**：一次删多篇只能逐个点，接口也只支持单篇。
- **`kb_attachment` 不在删除清理范围内**：第一版只登记展示，还没有写入路径，因此暂无孤儿可清；
  将来做多模态附件上传时这条要跟着补（已记为 P2-16）。

---

### B.45 Phase 5.4 实现记录（Agent 闭环补完：温度 / 工具白名单 / 重命名 / 出处）

> 编号说明：同 B.42/B.43/B.44，属**产品化**这条线。对应审计里的 P1-8 / P1-9 / P1-12 / P1-14。

**1. 动手时范围扩大了：非流式入口根本不看会话模式**

P1-14 要的是"消息上记 agentId"。加字段之前先去非流式链路 (`ChatServiceImpl.ask`) 找落库点，
结果发现它**完全不读 `conversation.mode`**——只读 `model_role` 就调模型。于是同一个会话
走不同入口会得到两种答案，而且**都不报错**：

| 会话模式 | 调 `POST /api/chat/messages`（非流式）原本的行为 | 为什么危险 |
| --- | --- | --- |
| `AGENT` | 不用该 Agent 的提示词、不用它的工具，等于裸模型回答 | 用户选的是"严谨的笔记助手"，拿到的是别的东西，**没有任何提示** |
| `RAG` | 回答里**没有任何笔记内容**，看起来却完全正常 | 最危险的一种：用户以为问的是自己的笔记 |

这两条都不是"功能没做完"，是**同一个会话在不同入口下行为不同**——审计的模块表里没有这一格，
因为它只有把两条链路并排读才看得见。修法：

- 抽 `ConversationAgentResolver.resolve(agentService, userId, conversation, mode)`，
  流式与非流式**共用一处**解析（含"会话没选 Agent → 用内置默认那一行"）。分成两份实现的话，
  下一次给 Agent 加能力只会改一处，症状是"流式能用、非流式不生效"，同样不报错；
- 非流式 AGENT 走 `AgentRuntime.configure`，与流式**同一份装配**（提示词 + 工具 + ToolContext）；
- 非流式 RAG **显式报 `8003`** 并指向 `/api/chat/stream`，且**在任何副作用之前**拒绝
  （不落用户消息、不调模型）。它用的是"列出**能**服务的模式"而不是"列出不能的"——
  将来加了新模式（工作流模式）默认落到报错，而不是默默按 PLAIN 跑出个不对的答案。

RAG 之所以是报错而不是顺手补上检索：那需要在非流式下把流式那套"检索 → 无结果短路 →
提示词组装"再实现一遍，而 RAG 会话绑多个库时"用哪个库"（P1-6）本身还没定。
现在接上来等于把两个未决问题焊在一起，等 5.5 定了再说。

**2. P1-8 温度：它不是假旋钮，区别在于"读它的那一行"一起落地了**

审计对 Agent 配置提过三条候选：`temperature` / `max_iterations` / `timeout_ms`。
后两条被明确否掉（`database.md` 6.0 第 3 条），理由是**没有任何机制会读它们**——
存下来但没人读的配置比没有更糟，用户会以为配了就生效。

temperature 的落地**带着读它的那一行**：`AgentRuntime.configure` 里
`request.options(ChatOptions.builder().temperature(...))`。这是两条执行链路
（一次性 ask 与流式对话）唯一的公共装配点，所以"这个 Agent 跑多热"只有一个地方说了算。

四个刻意的决定：

1. **NULL 与 0 不合并**（迁移 V16，见 `database.md` 6.0.3）：NULL = 跟随全局
   `ai.defaults.temperature`，0 = 显式要求贪心解码。用 0 当"未配置"的哨兵，
   等于让"我想要 0"这件事永远表达不出来。响应里也**原样返回 null**，不回填全局值——
   回填了用户就分不清"我配过"和"系统给的"；
2. **用请求级 options，不重建 ChatModel**。`ChatClient` 的合并语义是"从模型默认选项
   `mutate()` 出发、逐字段被请求级非空值覆盖"（`DefaultChatClientUtils.toPrompt` → `combineWith`），
   所以只写 temperature，`model` / `baseUrl` / `apiKey` 一个都不会丢。
   也因此必须用**厂商中立**的 `ChatOptions.builder()`——`ChatClientFactory` 的类注释
   禁止业务代码出现 `OpenAiChatOptions`，那会让换厂商时这里先炸；
3. **范围 0–2，取两家的并集**：Anthropic 系允许 0–1，OpenAI 允许 0–2。取交集会把 OpenAI 上
   合法的 1.5 挡在门外。校验在服务层（越界 `1001`，文案带范围与当前值），**不靠列的宽度**——
   `DECIMAL(3,2)` 是第二道防线，服务层那条才是给出可读错误的那条；
4. **生效范围写在界面上**：只作用于**由 Agent 驱动的调用**（AGENT 模式会话与试跑）；
   PLAIN / RAG 会话与 `/api/knowledge/**/ask` 不走 `configure`，仍用全局默认。
   这是生效范围的诚实边界，不是遗漏。

**3. P1-9 工具白名单：后端早就有，缺的一直只有界面**

`toolCodes` 从 4.3 起就在 `CreateAgentRequest` / `UpdateAgentRequest` 上，装配逻辑
（`replaceTools`）也在。但 `web/src/api/agents.ts` 的 `AgentPayload` **连这个字段都没有**，
`AgentsView.vue` 全文无 `tool`（审计用 grep 确认过）——能力对用户等于不存在。

5.4 补的是三件事，缺一件都会变成"假旋钮"：

- `AgentPayload.toolCodes` + 编辑弹窗多选 + 卡片回显（**存下去了 + 读回来了**两条腿）；
- **"空 = 全部工具"这句话写在界面上**。它与直觉相反（空选择框看起来像"一个都不给"），
  但与 `kbIds` 是**同一套语义**——同一页面里两套相反约定才是真的难懂；
- **已停用的工具标"（已停用）"**。装配取的是"启用中的 ∩ 白名单"，所以白名单里留着一个停用的
  code 不会让它复活；不标出来，用户会以为它还在用，白排查一轮。

顺带修正了 `api.md` 里的一处过期：`GET /api/tools` 被列在"6.1 规划中（尚未实现）"，
而它从 4.3 起就在（`ToolController`），5.4 只是接上前端。`PUT /api/agents/{id}/tools`
则**不存在也不会做**——白名单就是 Agent 的一个字段。

**4. P1-14 出处：两列同名，语义不同**

迁移 V17 给 `chat_message` 加 `agent_id`（见 `database.md`）。要点：

| 列 | 语义 |
| --- | --- |
| `chat_conversation.agent_id` | **活指针**：会话现在用哪个 Agent；可改、可为 NULL |
| `chat_message.agent_id` | **历史事实**：这条回答当时跑的是哪一行；插入即定 |

- **落的是解析后的具体 id**，不是照抄会话上那个可能为 NULL 的值：会话没选 Agent 时，
  消息上写的是**内置默认那一行的真实 id**。照抄 NULL 的话，"这是谁答的"永远没有答案，
  而接口一切正常，没人会报 bug；
- **只记助手消息，用户消息恒为 NULL**：用户消息不是任何 Agent 产出的；给它也写 id 会让
  "按 agent_id 聚合"把每轮对话重复计两次。落库点是 `appendAssistantMessage` 的**插入参数**
  （不可被 update 覆盖）；
- **索引带 `user_id` 前缀**（`idx_msg_user_agent (user_id, agent_id, id)`）而不是裸 `agent_id`：
  这是多租户表，孤立的 `agent_id` 索引只会被跨租户查询用上——它的存在本身就会**诱导**
  后来者写出漏 `user_id` 的查询。`user_id` 打头正是为了接住下面的聚合查询；
- **失败的消息也记出处**：用户问"这条回答是哪个 Agent 跑的"时，失败的那条同样需要一个答案。

字段与读它的机制**同一次落地**（`GET /api/observability/agents`，见 `api.md` 10.0），
不留"加了列没人读"的中间态——`LEFT JOIN agent` 让被删掉的 Agent 的历史用量仍留在报表里，
由 `deleted` 标注；`failed` 单独给出来，因为"用了 100 次"和"用了 100 次、80 次失败"
是两句完全不同的话。

**5. P1-12 重命名会话：接口从 Phase 1 就在，界面一直没有**

`renameConversation` 在 `web/src/api/chat.ts` 里定义了整个 Phase 1–5.3，全仓**无任何调用**。
用户唯一的改名方式就是再开一个会话——而标题是拿首条提问自动生成的，
"问了一句就把话题带偏"是常事，改不了名等于这个自动标题变成了枷锁。

实现上没有新接口，只有入口（侧栏 hover 显形的铅笔图标）。两点值得记：

- 服务端会把 `title_auto` 置 0，所以改过名之后的新提问**不会再被自动标题覆盖**——
  "用户改的就该留住"。E2E 因此专门断言"刷新后旧标题**不该**回来"；
- 改名后**重新拉一次列表**而不是只改本地那一项：标题归一化（trim，将来可能加长度截断）
  发生在服务端，在客户端照着猜一遍迟早会和它对不上。

**6. 测试与验证**

`mvn -B test`（JDK 21）：**440 用例 / 0 失败 / 18 跳过**（跳过全是"未配真实模型/外部栈"的
Live 类，与本期无关）。较 5.3 的 426 **净增 14 条**：

| 测试 | 条数 | 钉住的东西 |
| --- | --- | --- |
| `AgentTemperatureWiringTest`（新，`nexus-app`） | 4 | 温度**真的交到模型边界**了吗——桩模型带非空默认温度 0.7，"留空 = 跟随默认"这条断言才有证据；顺带断言合并没把 `model` 冲掉 |
| `ChatAgentProvenanceTest`（新，`nexus-app`） | 5 | 两条链路的出处：流式/非流式都记**解析后**的 id、会话不选 Agent 时记内置默认的真实 id、用户消息恒 NULL、RAG 非流式报 8003 且不留半条消息、PLAIN 不把默认 Agent 盖上去 |
| `AgentResourceTest`（扩） | +3 | 温度能存能回读、不传 = 不改、**null 与 0 是两个不同的值**、越界报 1001（含范围文案）、复制时温度跟着复制 |
| `AgentDefinitionTest`（扩，`nexus-ai`） | +2 | `temperature` 可空、0 不被当成"没配" |

其余改动（`ChatAgentModeTest` / `ChatConversationFlowTest` / `AgentPolicyWiringTest` /
`ToolResourceTest` / `AgentRuntimeFailureTest`）是 `appendAssistantMessage` 多了 `agentId`
参数后的**机械跟进**，断言一个字没改。

E2E 脚本已扩：`verify-agents.mjs` 加"工具勾选 + 温度回填**后从列表重新拉回来**"
（只改前端状态过不了），`verify-agent-chat.mjs` 加第三条腿"重命名 → **刷新后名字仍在**"
（这层只有浏览器能证明：接口没被调用、500、服务端归一化成别的样子，界面上看着都一样）。

**7. 真机验收：已执行（2026-10-02 10:09–10:15），全部退出码 0**

*环境*：`docker compose --profile ai-local`（MySQL 3307 / PostgreSQL+pgvector 5433 / Redis /
ES 9200 + analysis-ik / RabbitMQ / Ollama）+ 后端 `--spring.profiles.active=local`（`clean package`
现打包的 jar，不是上一次的产物）+ `npm run dev`。脚本用系统 Edge，真模型 `deepseek-v4-flash`。

| 验收项 | 结果 |
| --- | --- |
| V16 / V17 在真实 MySQL 落地 | `flyway_schema_history` 有 16/17 两行 `success=1`；`agent.temperature` = `decimal(3,2) NULL`、`chat_message.agent_id` = `bigint NULL`；`idx_msg_user_agent` 三段为 `(user_id, agent_id, id)`——**结构与设计逐字一致** |
| 迁移在真实 PG（向量库）上 | `Successfully validated 1 migration`，`Schema "public" is up to date` |
| 工具白名单回读（P1-9） | `verify-agents.mjs` ③：勾选后卡片显示「知识库检索、计算器」（数据来自保存后**重新拉取**的列表，不是弹窗本地状态） |
| 温度回读（P1-8） | 同一条腿：填 `0.2` 后卡片显示「0.2」（不是 0.7，所以区分得出"真存了"与"恰好是默认值"）；新建未配时显示「服务端默认」 |
| 温度真的到模型（P1-8） | ⑧ 试跑完成，**用了 2 次工具**——真实模型调用走通 |
| 重命名（P1-12） | `verify-agent-chat.mjs` ⑥⑦：重命名 → 刷新 → 名字仍在 → 该会话仍可正常打开；查库确认 `title_auto=0`、标题已是新值 |
| 出处（P1-14） | 查库：AGENT 会话的助手消息 `agent_id=2105291121586946050（默认助手）`（**会话没选 Agent，落的仍是解析后的内置默认行**）、用户消息恒 NULL；RAG 会话的助手消息 `agent_id=NULL`（RAG 不由 Agent 驱动） |
| 聚合端点 | `GET /api/observability/agents` → `[{agentId, code:"builtin.default", name:"默认助手", deleted:false, messages:3, failed:0, lastUsedAt}]`；**不带 token → 401** |
| 非流式 AGENT（本期扩大范围的那条） | `POST /chat/conversations/{id}/messages` → `200`，助手消息带 agent_id、真实 Token（2597）、`model=deepseek-v4-flash` |
| 非流式 RAG | → **`409` + `8003`**，message = "该会话是 RAG 模式，非流式接口无法完成检索与引用。请改用流式接口 POST /api/chat/stream"；**该会话消息总数 0**（证明在任何副作用之前拒绝） |
| 其余 6 个脚本回归 | `verify-login` / `verify-frontend` / `verify-stream` / `verify-retrieval-ui` / `verify-agent-no-kb` / `verify-doc-lifecycle` **全部退出 0** |

*这次跑出来的两个真问题*（都属"脚本在骗人"，不是产品缺陷，但都要留档）：

1. **重命名那条断言第一版是错的，把一次成功的重命名判成了失败。**
   它写的是"刷新后侧栏里不该再有旧标题"，而侧栏是**全部会话**的列表——自动标题来自首条提问，
   演示账号反复跑同一句"我的笔记里缓存击穿怎么解决？"，历史上留下的会话必然重名。
   于是"别的同时名会话还在"被当成了"这一个的旧标题回来了"。改成**数同名数量是否减少一个**
   （改前 N 个 → 改后 N-1 个），才是"这一个会话的旧标题没有回来"。
   这与 B.44 §9 记的四个坑同类，但方向相反：那几个是**假装通过**，这个是**假装失败**——
   后者更危险，因为它会让人去改一段本来正确的产品代码。
2. **中文经 curl / Python 过 Windows GBK 控制台会失败**：`curl -d '{"title":"中文…"}'` 报
   `1002 请求体格式错误`（与 B.44 §9 第 3、4 条同源），探针脚本的 `print` 也会
   `UnicodeEncodeError`。真机探针因此改成**全程 ASCII 输出 + 结果写 UTF-8 文件**，
   建会话用 ASCII 标题。**这是 shell 的编码问题，不是接口的**——浏览器链路（前端 E2E）全程正常。

**8. 这一期明确没做 / 没验到的**

- **温度没有"改回跟随全局默认"的传法**：局部更新接口里 `null` 已被"不改"占用，
  与 `kbIds`/`toolCodes` 不同（那两个把"空"复用成了"不限制"，因为那是它们的正常状态）。
  想清空只能新建 Agent，界面上写明了；
- **非流式 RAG 未接**（报 8003 而非补检索，理由见 §1）；
- **`verify-doc-lifecycle.mjs` 结尾那句提醒仍然成立**：向量与 ES 的"无残留"界面看不出来，
  要靠查库——本期没重跑那条（5.3 已验过，且本期没动删除链路）。

---

### B.46 Phase 5.5 实现记录（多知识库 RAG：把三道人工闸门拆掉）

> 编号说明：同 B.42–B.45，属**产品化**这条线。对应审计里的 P1-6。

**0. 本增量是什么**

一个 RAG 会话只能绑一个知识库。这一期让它能绑多个：提问时**逐库检索、跨库归并**，
答案的每一段引用能看出自哪个库。上限 10 个。

**1. 动手前的三个探索结论（它们改变了这一期的形状）**

| 结论 | 证据 |
| --- | --- |
| **检索层早就是多库的** | `RetrievalQuery.knowledgeBaseIds` 是 `List<Long>`；三个 Retriever 都已在 for 循环里逐库召回并调 `RecallSupport.merge`。缺的只是**没人喂过长度 > 1 的列表**——那段多库分支此前是死代码 |
| **数据库不需要任何迁移** | `chat_conversation.kb_ids` 从 V2 起就是 JSON 列；Agent 侧早有关联表 `agent_knowledge_base`（V12）。审计里问的"要不要新建 `agent_kb_relation`"——**它已经存在**，不需要 |
| **卡点全在上游，是三道人工闸门** | 写入口拒绝 > 1（`ConversationServiceImpl.resolveKbIds`，原 1001"暂只支持绑定一个"）、读出口取 `.get(0)`（`ChatStreamServiceImpl.requireKnowledgeBaseId`）、门面签名是单库（`ChunkSearchService.search` 吃一个 id）。再加 `SearchHit` 丢掉了 `knowledgeBaseId` |

所以这一期是**接线**，不是重建：没有新框架、没有换向量库、没有拆服务、没有重写 Agent Loop，
也没有 V18。

**2. `topK` 的语义变了——这是本期最容易写错、且错了不报错的一处**

`RetrievalQueryFactory.resolveTopK` 的优先级是**调用方显式指定 > 知识库配置**
（Phase 3.2 · B.32 的落点）。所以如果把调用方的 `topK` 传进"逐库构造"，
每个库都会拿到同一个召回深度，**`retrieval_top_k` 整列静默失效**——功能看着完全正常。

定下的口径（与 Agent 工具 `KnowledgeSearchTool` 原本的做法一致）：

- 每个库按**自己的** `retrievalTopK` / `similarityThreshold` 召回（逐库构造时 `requestedTopK = null`）；
- 归并后按**全局** `topK` 截断（请求值，没传则 `nexus.retrieval.top-k` = 5）——
  **不随库数放大**，所以多绑库不会让上下文变长、也不会让回答变慢（代价是检索本身变慢，见 §5）。

`ChunkSearchServiceMultiKbTest.eachKnowledgeBaseRecallsAtItsOwnTopK` 把这条钉在
"传给 ES 的召回深度"这个**具体数字**上（2 与 6 两个库各自 × 3 的深度），
而不是"有没有读配置"——只有数字能证明参数一路走到了基础设施。

**3. 为什么新方法叫 `searchMany` / `prepareMany`，而不是重载 `search`/`prepare`**

- `ChunkSearchServiceLegacyReference` 是重构前行为的**可执行定义**（B.31），它构造 `SearchHit`
  时用的还是 8 参形态。给它留一个**兼容构造器**（第 9 个分量 `knowledgeBaseId` = null）即可，
  那个文件**一行不改**；
- 真加一个同名重载会让裸 `any()` 的 Mockito 桩变成**歧义调用**（`any()` 同时可绑 `Long` 与
  `List<Long>`），于是一批与本改动无关的桩要跟着改，而"测试报错看着像功能坏了"是最难查的一类；
- 换个名字从根上避开 Java 重载解析与 Mockito 歧义，代价只是多一个动词。

**4. 单库那条路一步都没挪**

单库路径被 `ChunkSearchServiceParityTest` 逐字段钉着（改造前后必须完全一致）。
因此：

- `prepare` 仍调单库 `search`，只有**两个及以上**才分派到 `searchMany`
  （`RagAnswerServiceImpl.prepareRetrieved` 按 `knowledgeBases.size() == 1` 分派）。
  让单库绕道 `searchMany` 等于把它挪出护栏，还会白多一次归属校验的 SQL；
- 一致性断言加 `.ignoringFields("knowledgeBaseId")`，理由写在同一处：该字段没有 legacy 对应物，
  无可比对；**其余每个字段仍逐字段严格比对**（包括顺序）。

**5. 三个诚实的边界**

1. **成本随库数线性增长，而且是串行的**：每库 = 1 次 ES + 1 次 pgvector + 1 次查询嵌入 + 回表。
   上限 10 是**写死在代码里的常量**（`MAX_BOUND_KNOWLEDGE_BASES`），刻意不做配置项：
   它表达的是"这条链路的设计承受能力"，做成旋钮会让人以为调大就能多绑，而真正的瓶颈
   （串行放大首字延迟）不会因此改变。要让它可调，前提是先把逐库检索并行化；
2. **查询向量被重复计算**：预检保证所有库用同一个全局嵌入配置，所以同一个 query 的向量
   对每个库**逐字节相同**，却被嵌入 N 次（`EmbeddingClient.embed` 无缓存）。
   这是**既有**缺陷（Agent 工具路径今天就这样），多库让它 3–5× 可见。本期**不修**——
   修它要给 `RetrievalQuery` 加 `queryVector`，属核心 record 变更。将来若做，顺序应是：
   先预检 → 再单次嵌入 → 再考虑把多库真正下沉到 retriever 层；
3. **跨库分数可比性是有条件的**：`RecallSupport.merge` 纯按 `score` 排。
   向量分同源可比（所有库必须等于同一个全局嵌入配置）；**BM25 分不跨索引可比**
   （term statistics / IDF / 平均长度都不同）；RRF 分依赖各库候选深度，只是近似。
   在 `HYBRID`（RAG 与 Agent 实际用的唯一模式）下可接受，对 `BM25`/`VECTOR` 单路是真实失真。
   这段判断写在 `RecallSupport.merge` 的 javadoc 里，并顺手改掉了它原来那句读起来像
   "库之间互相校验"的表述——实际是**每库与同一个全局配置比对**。

**6. 一处重复、一处闲置（本期接受，写下来备用）**

- 门面 `searchMany` 自己实现了"循环 + 归并"，与 retriever 层已有的那份重复。原因是
  **下推 `kb_id IN (...)` 表达不了"每库各自的 retrieval_top_k"**（`RetrievalQuery` 只有一个
  `topK` 字段），而且改下推要动 `PgVectorStore` / `ElasticsearchChunkIndex` 并重做 B.31 的
  "行为不变"证明。将来若要让多库真正下沉，需把 per-KB 设置收进 `RetrievalQuery`
  （如 `List<KnowledgeBaseSettings>`），届时把两处合并成一处；
- 因此 `RetrievalQuery.knowledgeBaseIds` 的多元素能力在本路径上**仍然闲置**
  （每次仍传单元素），retriever 层那段多库循环依旧不可达。这是有意的取舍。

**7. 逐库降级策略没有合并进 Agent 工具**

`KnowledgeSearchTool` 有意逐库 try/catch 降级（降到 SQL LIKE、跳过不可用的库并**如实上报**，
含悬空绑定），而门面选择**不吞异常**（保持"响亮失败"）。两者分工不同：门面返回
`List<SearchHit>`，装不下"每个库失败了什么"，强行合并会让 Agent 的行为倒退。
门面这边补的是**让失败能定位**：嵌入模型不一致时把 4006 的文案改成带出**是哪个库**，
归属失败时 3001 带出**是哪个 id**——多库下"某个库不存在"这种不带标识的报错没法排查。

**8. 接口契约变了：`retrieval` 事件的知识库维度是列表**

```json
{"knowledgeBaseIds":["5001","5002"], "count":2,
 "hits":[{"chunkId":"9001","documentId":"100","knowledgeBaseId":"5001", "...":"..."}], "...":"..."}
```

原先的 `knowledgeBaseId`（单值）换成 `knowledgeBaseIds`，且每条命中带 `knowledgeBaseId`——
会话绑了两个库之后，"检索到 5 个片段"不再能回答最要紧的问题：**资料是从哪来的**。

- 两条流式链路**共用同一个构造器**（B.37 就定下的规矩），所以改这一处两边同时生效。
  知识库问答流式（`/api/knowledge-bases/{id}/ask/stream`）是固定单库的路径，
  改成传 `List.of(knowledgeBaseId)` 而不是保留一个 `of(Long)` 委托重载——
  一个形状、一个构造器，比两个入口产出同一种形状更好维护；
- 前端有**两份** `StreamRetrieval` 类型声明（`api/chat.ts` 与 `api/knowledge.ts`），
  两份都改了。这是历史遗留，已在类型注释里注明；
- `verify-retrieval-ui.mjs` 的两帧手写 SSE 桩也**必须**跟着改：它的断言只读
  `count`/`hits`/标签，**桩漂移了也不会变红**。这一期顺手把第一帧改成"两个库、每条命中
  各带来源"，让桩本身也能证明来源标注这件事。

**9. 用户数据隔离的四条老规矩一条没松**

库归属仍走 SQL 层的 `user_id` 条件（`requireAllOwned` 一条 `eq(userId) + in(id)` 查询），
不接受前端传 `userId`，不存在的库与别人的库返回**完全相同**的 3001（不泄露存在性），
区别只在文案里的 id。新增一条：**任意一个库不合格就整批失败，不做部分成功**——
"绑定成功、检索却少查了一个库"是这里最坏的结果，因为它不报错。

**10. 一个测试设计陷阱（值得单独记）**

`ContextAssembler.isDuplicate` **只按 content 去重、不看库**。同一段文字导入两个库会塌成一条
引用，于是"每个库都有贡献"这条验收可能在夹具共享文本时**假绿**。
本期的 E2E 夹具因此各写各的独有内容。要不要把去重键改成 `(kbId, content)`？
本期**不动**——多库下"同一段话在两个库里都有"本来就该只引用一次（引用是给人看的，
不是给库看的），真正的重复问题属于"跨库去重"，留给有评测数据之后。

**11. 验证结果**

- `mvn -B test`：**462 / 0 失败 / 18 跳过**（5.4 基线 440，净增 22）：
  `requireAllOwned` 8 + `searchMany` 7 + `prepareMany` 5 + `ChatRagModeTest` 净增 2
  （原"绑多个被拒"那条**反转**成"绑多个被接受"，另加"超上限"与"其一越权"两条）。
  单库一致性（`ChunkSearchServiceParityTest`）在改动后仍逐字段通过；
- 真机 E2E（新增 `web/scripts/verify-multi-kb.mjs`，local profile + ES + pgvector + 真模型）：
  两个库各导入一份内容不同的文档 → 界面上**多选**绑定两个库（建会话请求体带两个 id）→
  提问只有第二个库能回答的问题，检索卡片显示"检索到 2 个片段 / 2 个知识库"且**两条命中各自
  标出了来源库**，回答引用了第二个库的独有事实（代号 `NX-7741`、回源超时 `220 毫秒`）→
  越权（把别人的库混进列表）返回 404 / 3001 **且文案指名那个 id**；
- 既有 8 个 `verify-*.mjs` 单库回归全退出 0；
- `flyway_schema_history` 不变：**本期无迁移**。

**12. 这一期明确没做**

- **非流式 RAG 仍报 8003**（5.4 的决定），不改：多库的会话链路走 `chat_message`，
  不经过 `RagAnswerServiceImpl.finish`，所以今天不冲突。但 `RagAnswer.RagCitation` 没有 kbId、
  `kb_qa_record.kb_id` 是单值——**若将来做非流式多库，这两处必须一起改**；
- `retrieval` 事件仍不落库（刷新后检索卡片消失，已知缺口，见 B.37）；
- 查询向量单次嵌入、逐库检索并行化、跨库去重/去噪、真 query rewrite、每库独立权重——
  都留给有评测数据之后；
- Agent 侧一行没动：`AgentDefinition.kbIds` 与 `KnowledgeSearchTool` 的逐库扇出从 4.1/4.3 起
  就是这样，本期只补了 `likeSingle` 构造 `SearchHit` 时要传 kbId（编译所需）。

---

### B.47 Phase 5.6 实现记录（用户安全体系：四个功能，和一条"无声失效"的框架陷阱）

**做了什么**（四个需求，逐条对应）：

1. **修改密码**：`PUT /api/users/me/password`，BCrypt 验证原密码 → 更新哈希 →
   `token_version + 1`（一条 `LambdaUpdateWrapper` 同时改两列，`updateById` 表达不了 `+1`）。
2. **Token 安全**：只出设计（ADR-021），代码零改动——用户明确说"不要直接大改"。
   `docs/productization-gap-analysis.md` 里登记为**仍存在的缺口**。
3. **登录失败限制**：`LoginAttemptLimiter`，`nexus:auth:lf:{username}`（计数）+ `nexus:auth:ll:{username}`
   （冻结标记），5 次 / 15 分钟窗口 / 冻结 15 分钟，fail-open。注册入口另做 IP 限流（10 次/小时）。
4. **限流 + 每日配额**：`RateLimitQuotaFilter`（一个收口点）+ `QuotaService` + `UsageMeteringAdvisor`。

**三个值得记下来的判断**

**① ★ 记账点选 Advisor，不选"包一层 ChatModel"**。第一直觉是写一个 `ChatModel` 装饰器，
在方法入口读 `SecurityContext` 拿 userId——最干净。**但它是错的**：
反编译 `DefaultChatClient$DefaultStreamResponseSpec` 可见 `chatResponse()` 返回
`Flux.deferContextual(...)`，真正的 `advisorChain.nextStream()` 跑在 `boundedElastic` 线程上，
请求线程的 `SecurityContext` 在那里是空的。而 `ChatClientRequest.context()` 是**装配期被闭包捕获的普通对象**，
订阅线程可见（框架自己的 `MessageChatMemoryAdvisor` 取 `CONVERSATION_ID` 就是这个机制）。
换用 Advisor 之后还有第二个收益：调用方拿到的仍然只是 `String`（`.content()`），
**不需要为了记账去改它们的取值方式**——advisor 看的是 `ChatResponse`。

**② ★★ 一个 `getOrder()` 恰好等于 `Ordered.LOWEST_PRECEDENCE` 的 Advisor 会被静默跳过**（本次最大的坑）。

这不是推测，是实测（2026-10-02）。`UsageMeteringAdvisor` 一开始把 `ORDER` 设成
`Ordered.LOWEST_PRECEDENCE`（"链条最内侧，贴近终端"听起来完全合理），测试却报
`Expecting actual: []`——**记账一笔都没有**。一个临时的探针（注册一串只在 `adviseCall` 里打日志的
Advisor，顺序分别取 MIN / MIN+100 / MIN+200 / MIN+1000 / 0 / MAX-1 / MAX）打印出：

```
MIN(order=-2147483648): ENTERED
MIN+100: ENTERED      …… MIN+1000: ENTERED …… ZERO: ENTERED …… MAX-1(order=2147483646): ENTERED
MAX: model called     ← 没有 ENTERED：被静默跳过
```

根因：框架的 `ChatModelCallAdvisor.getOrder()` / `ChatModelStreamAdvisor.getOrder()` **就是**
`Integer.MAX_VALUE`（`= Ordered.LOWEST_PRECEDENCE`），而它们是在 `buildAdvisorChain()` 里
**追加**到已排序列表末尾的。同序时终端胜出，我们的顾问**永远不被调用**——
**不报错、不打日志、模型照调、回答照回**，只是记账永远是 0。

这种失效的形状正是本项目反复吃亏的那一种：**错了也不报错**。所以修法不只是把常量改成
`Ordered.LOWEST_PRECEDENCE - 100`，还加了一条回归断言把它钉死
（`orderMustNotCollideWithTheChainTerminal`：断言顾问的 order **严格小于** `LOWEST_PRECEDENCE`），
靠注释提醒是不够的——注释不会失败。

**③ 静默少计要留下痕迹**。每个调用点都要手动加一行 `.advisors(a -> a.param(UserContextKeys.USER_ID, userId))`，
**忘了加不会报错**。所以 advisor 拿不到 userId 时**不静默丢弃**：计入
`quota.usage.unattributed` 并打带模型名的日志。同理，`LlmReranker`（每次都跑的系统调用）
被显式标成 `UserContextKeys.SYSTEM_CALL` 而彻底跳过——否则那个"缺陷计数"会被正常流量永久打满，
等于没有。

**一份诚实的偏差说明**：实施计划里写的是"**不改** `LlmReranker`"。实际改了——加了一行
`SYSTEM_CALL` 标记。这不是顺手扩大范围，而是计划里那条"不改"会**破坏上面第 ③ 条的告警价值**：
不标记的话，重排每次检索都记一笔 unattributed，那个计数就永远是满的。代码注释里写了原因。

**一个测试侧的教训**：`RagStreamRetrievalEventTest` 里有一串 mock 出来的 ChatClient 链
（`prompt() → system() → user() → stream()`）。生产代码在这条链上多插了一步 `advisors(...)` 之后，
测试报的是 `NullPointerException: ... 因为 advisors(...) 的返回值为 null`——
读起来像是**生产代码写错了**，实际是 Mockito 对未打桩的方法返回 null、桩没跟上。
修法是在桩里补一行 `when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec)`。
记下来是因为这种报错会把人往错误的方向带：fluent mock 链上的每一步都是一份契约，
生产代码每多一步，桩就得跟一步。

**一处自己写的通配，靠真机数字才露出来**：配额路径清单初稿写的是 `/api/chat/**`——看起来
"把所有 chat 入口都罩住"很稳妥，实际把**读历史消息、列出会话、删除会话、停止生成**
一起算进了当日额度。它不会报错，只会让用户在自己完全不花钱的操作上被扣额度，
并且「今日 N/200 次」不再等于"问了几次"。露馅的是真机 E2E：**刚登录、只进了一次对话页
就显示「今日 1/200 次」**——那一格数字本身就是证据。现在收窄成
`/api/chat/stream` + `/api/chat/conversations/*/messages`，并加了一条反证测试
（`nonModelChatPathsAreNotCharged`：限额置 0 时那三条路径仍放行）。
**教训与 B.47 §① 同源**：配置里的通配和 Advisor 的 order 一样，越界了也不会响，
只能靠"数字对不对"去发现——所以能显示的计数要显示出来，别只写进日志。

**文档也修了**：`docs/api.md` §2 此前把 `/auth/refresh`、`/auth/logout`、`PUT /users/me`、
`PUT /users/me/password`、`GET /admin/users` 都写成"已实现"，实际**全都不存在**；
§12 描述的限流体系同样是**零代码**（`7001`/`7002` 定义好了、映射好了、没有抛出点）。
本期把其中两条做成真的，其余移进"规划中"表里并注明原因。**文档不实比功能缺失更贵**：
它会让"照文档接一下就有了"的判断落空，而且落空发生在集成之后。

**验证结果**

- `mvn -B test`：**541 / 0 失败 / 18 跳过**（5.5 基线 462，**净增 79，全部是本期新增的类**——
  逐类相加 78，再加收窄配额路径后补的那条反证测试 1：`UsageMeteringAdvisor` 9、`QuotaService` 9、`UpdateProfileTest` 9、
  `UserAuthCache` 8、`PasswordChangeFlowTest` 7、`TokenVersionValidator` 7、`LoginAttemptLimiter` 6、
  `RateLimitQuota` 6（5 + 反证 1）、`LoginRateLimit` 4、`RateLimiter` 4、`QuotaEndpoint` 3、`RegisterRateLimit` 3、
  `QuotaExhausted` 2、`SecurityStoreDegradation` 2）。跳过数与 5.5 相同（18），
  即**没有新出现被环境能力挡掉的用例**。这一步同时证明 `standalone` / `test` 不依赖 Redis：
  它们用 `InMemorySecurityStore`。
- `mvn -B package -DskipTests` 与 `cd web && npm run build`（含 `vue-tsc --noEmit`）均成功。
  前端侧新增 `vue-tsc` 能过的类型（`Quota`、`UserProfile`）不是"编译通过"的装饰——
  `client.get<never, T>()` 的第二个泛型参数写错会在这里直接失败。

**真机验收（2026-10-02，全程不使用 Docker）**

环境：`java -jar` + `--spring.profiles.active=standalone`（H2 内存库）+ Vite dev server；
Redis 用本机原生实例（`E:\lky\Redis`，3.0.504，非 Docker 发布），用命令行
`--nexus.security.redis-enabled=true` 把存储切到 `RedisSecurityStore`。**两条路径都真跑过。**

① **standalone + 内存实现**：`/actuator/health` = **UP** —— 证明引了
`spring-boot-starter-data-redis` 之后健康指示器确实关掉了，6379 没开也不会 DOWN；
`verify-security.mjs` **退出 0**（8 条腿全过，含第 ⑦ 条"用改密前的旧 token 打接口 → 401"）。
② **standalone + 真 Redis**：同样**退出 0**；`redis-cli KEYS 'nexus:*'` 看得到四类键，值都对：

```
nexus:auth:tv:{userId}              = 1    ttl 265     ← 改密后版本号确实 +1
nexus:q:req:{userId}:{yyyyMMdd}     = 1    ttl 32800
nexus:rl:u:{userId}:{yyyyMMddHHmm}  = 3    ttl 96
nexus:rl:ip:{ip}:{yyyyMMddHH}       = 1    ttl 7172
```

③ **登录失败冻结**：注册后连错 5 次密码（每次 `401 / 2006`）→ **第 6 次拿正确密码**返回
`429 {"code":7001,"message":"登录失败次数过多，账号已被暂时锁定，请 15 分钟后再试"}`；
`nexus:auth:lf:*` = 5、`nexus:auth:ll:*` = 1。这条同时证明**检查发生在校验密码之前**。
④ **用户级限流**：同一用户一分钟内打到第 21 次 → `429 / 7001 请求过于频繁（每分钟上限 20 次）`，
响应头 `Retry-After: 60`，`Content-Type: application/json`（**不是** `text/event-stream`）——
第 ④ 条与"开流前拒绝"是同一个证据的两面。
⑤ **每日配额**：把上限压到 2 再跑 → 第 3 次 `403 / 7002`
「今日问答次数已用尽（上限 2 次/天），将于次日 00:00 重置」。**与第 ⑥ 条的"服务不可用"文案可区分**，
这正是当初把两种 7002 分开写的理由。
⑥ **降级语义**（把 Redis 停掉之后逐条打）：

| 关注点 | 实测 |
| --- | --- |
| 注册（IP 限流器） | `200`（fail-open） |
| 登录（失败限制器 + 令牌版本） | `200`（fail-open；令牌版本回落查库） |
| 模型入口 `POST /api/chat/stream` | `403 {"code":7002,"message":"配额服务暂时不可用，请稍后再试"}`（fail-closed） |
| 改密码（仅限速） | `200`（fail-open） |
| 改密后的旧令牌打 `GET /users/me` | `401 / 2001`（回落查库读到了新版本） |

**两个被真机验证逼出来的问题**

**① ★ 注册限流会误伤开发环境自己**。`test` profile 早就因为这个坑放宽过（`application-test.yml`
的注释里写着"必须放宽"），但 `standalone` / `local` **没有**——而 E2E 脚本里有好几个会自己注册
临时账号（`verify-security.mjs`、`verify-agent-no-kb.mjs` 各一个，外加手工造账号）。
实测同源连续注册到**第 9 次**开始 `429 / 7001 注册过于频繁（每小时上限 10 次）`；
更糟的是**症状会跑到别处**：注册失败 → 紧接着那次登录自然 401，于是"限流"看起来像"认证坏了"。
两个开发 profile 现在把 `per-hour-per-ip` 默认放宽到 1000（`${NEXUS_REGISTER_PER_HOUR:1000}`），
生产默认仍是 `application.yml` 里的 10（未覆盖任何 profile 时生效）——
要在这两个 profile 上验真实阈值就显式设 `NEXUS_REGISTER_PER_HOUR=10`。
**改完复验**：同样连续注册 12 次，**12 次全部 200**（改之前同样这 12 次会在第 9 次断掉）。
**放宽的是开发环境的默认值，不是阈值本身**：`RegisterRateLimitTest` 照旧压到 2 验真实行为。

**② `spring-boot:repackage` 报 "Unable to rename '…jar' to '…jar.original'"**。
不是插件的问题：上一轮验证留下的 `java -jar … --spring.profiles.active=local` 进程
还开着，Windows 上**进程占用中的 jar 不能改名**。这个错误的措辞（"Unable to rename"）
完全不提"文件被占用"，容易让人去查插件配置或磁盘权限。顺带一提，这次它**被 `| tail` 藏了退出码**
（外层 shell 看到的是 `tail` 的 0），和 B.46 里记的是同一个坑——
所以凡是用管道截断输出的构建，都必须显式回显 `${PIPESTATUS[0]}`。

**本期没复跑的那部分，如实记着**：8 个既有 `verify-*.mjs`（`verify-agent-chat` / `verify-stream` /
`verify-multi-kb` 等）**本轮没有复跑**——它们要 Elasticsearch、pgvector 与真实模型，
即用户本期明确要求暂停的 Docker 基础设施栈。**这不等于它们没被影响**：本期新增的
用户级限流（20 次/分钟）恰好作用在这些脚本密集调用的模型入口上，
所以第 ④ 条改用"直接用 curl 把 21 次打满"来验，而不是靠脚本替我们撞。
下期（5.7 容器化）跑通 Docker 后应把它们全部复跑一遍。

**已知边界**（完整列表见 ADR-020 与 `productization-gap-analysis.md`）：
refresh token / 登出仍未实现；配额可被 Redis 重启或淘汰绕过；`status` 改动不立即吊销令牌；
登录冻结可被定向刷锁；记账是尽力而为（模型已调用无法回滚，供应商不回传 usage 时记不到）。

---

### B.48 Phase 5.5.1 实现记录（多库检索：并行化，与"隔离了但不能让它消失"）

**0. 本增量是什么**

需求方提的"多知识库 RAG"共 7 条要求。**先逐条核对，5 条在 5.5（B.46）里就已经是真的**：
多库检索（不是 `kbIds.get(0)`）、管线形状、权限（`requireAllOwned` 单条 SQL 过滤）、
Agent 复用已有字段（`agent_knowledge_base` 在 V12 就有）、单库/双库/跨用户/空库测试。
所以本期**没有再造一个平行的多库实现**——那会变成同一件事的两份代码，
而 `ChunkSearchServiceParityTest`（连同冻结基线 `ChunkSearchServiceLegacyReference`）当初就是为了防这个。

本期真正补的是两条：

1. **没有并行**：`ChunkSearchServiceImpl` 与 `KnowledgeSearchTool` 都是串行 `for`，
   3 个库 = 3 次（ES + 嵌入 + pgvector + 回表）**尾延迟相加**；
2. **`searchMany` 没有逐库失败隔离**：任一库抛异常 → 整次检索 4005。
   （`KnowledgeSearchTool` 里**本来就有**逐库 try/catch + LIKE 兜底，缺的只有 RAG 那条路。）

**1. ★ 为什么"只加隔离"是倒退——这一条决定了本期的形状**

今天的失败是**响亮的**：一个库垮了，整次检索报 4005，用户知道"这次没搜成"。
只加隔离不加可见性，它就变成"某个库静默地什么都没贡献"：卡片照样显示"检索到 5 个片段"，
而模型的提示词里也没有任何线索——于是它把"这个库没被搜过"说成"你的笔记里没有"。
**用户看到一句斩钉截铁的假否定，而这句话在系统提示词第 3 条下还显得完全合规。**
这正是本项目从头到尾在防的那个 bug（"明明有资料却答不知道"）。

所以隔离**必须同时可见**，而且要在四个地方都可见，缺一层就在那一层变成假话：

| 层 | 改了什么 | 丢了会怎样 |
| --- | --- | --- |
| `PreparedAnswer.failures` | record 加第 7 个字段（保留 6 参兼容构造器） | 上游只知道"命中了 5 条" |
| 提示词（`RagAnswerServiceImpl.userMessage`） | 资料块**之前**加"【检索范围不完整】…其中可能就有答案…不要说\"资料中没有\"" | 模型给出用户可见的假否定 |
| SSE `retrieval` 事件 | `payload.failedKnowledgeBases = [{knowledgeBaseId, knowledgeBaseName, reason}]` | 界面无从显示"这次少搜了谁" |
| 空结果文案 | 有失败时用 `RagAnswerService.RETRIEVAL_FAILED_ANSWER`，不再用 `EMPTY_ANSWER` | "资料中没有相关内容（知识库尚未导入或未建立索引）"——**一句与事实不符的断言** |

前端那一层还要再分一次：`empty` 标签的措辞按"有几个库这次真的搜成了"改口
（`emptyLabel`：全挂 → "知识库本次都没有检索成功"，部分挂 → "其余知识库没有命中"，
全成 → 才说"知识库里没有命中"）。后端已经把这条区别写进提示词，界面若一律说
"知识库里没有命中"，同一件事就有了三种口径，而用户看到的恰好是最笃定的那一种。

**2. 三个真陷阱（都是"错了不报错"的那种）**

**① TCCL 不跨线程，而且失败会永久毒化。** 虚拟线程上的 `Thread.currentThread().getContextClassLoader()`
是 JDK 的 `AppClassLoader`，不是 Boot 的 `LaunchedClassLoader`。`OllamaEmbeddingModel` 的静态初始化
需要后者，**且失败一次就在整个 JVM 里永久失效**（`EmbeddingClientFactory:100-130` 记着这件事）。
所以每个任务都要显式设回调用线程的 TCCL。MDC 同理（`RequestIdHolder` 明写不跨线程），
但 nexus-knowledge 不能 import app 层的 `RequestIdHolder`（模块方向），改用 SLF4J 的
`MDC.getCopyOfContextMap()` / `setContextMap` / `clear`——没有 requestId 的日志会让
"哪个请求慢"彻底查不出来。

**② `List.copyOf` 拒绝 null，而"失败位 = null"是契约。** 返回值的语义是
"与输入等长、失败的位是 null"，用 `List.copyOf` 会直接 NPE（第一版就是这么红的）。
改成 `freeze()`（`Collections.unmodifiableList(new ArrayList<>(...))`），
**保留 null 又保留不可变性**。单库那条路原本写的是 `List.of(apply(...))`，同样是 NPE 陷阱。

**③ SSE 监听器不是线程安全的——所以任务里绝不 `record`。**
`AgentContext.collector` 的 sink 是普通 `ArrayList`；流式那条路的监听器更直接，
`ChatStreamServiceImpl.StreamSession` 当场推 `SseEmitter.send`，而那个**不是**线程安全的。
因此扇出的任务体只**返回值**，所有痕迹由调用线程按输入顺序回放——
顺序也就顺带确定了（`KnowledgeSearchTool` 的 `all.sort(...)` 是稳定排序，
按完成顺序装配会让**同分条目的次序随网络抖动而变**，同一份资料在两次相同提问里
以不同顺序出现在引用编号里）。

**④ 失败的库必须占位，不能剔除。** `RecallSupport.merge` 有一条 `size() == 1` 的快速路径
（直接返回、不做 `limit` 截断）。把一个失败的库从列表里删掉，"3 个库"就成了"1 个库"，
**全局截断行为被静默改变**。所以失败位是 `List.of()` 占位。

**3. 两条口径属于"决定"而非"实现"**（完整论证见 ADR-022）

- **单库不切线程**：库数 == 1 就在调用线程直接跑。这不是优化，是**契约**——
  单库路径的线程、异常、顺序必须与改造前逐字段一致，由 `ChunkSearchServiceParityTest` 钉着；
- **归属错误与嵌入不一致不被隔离**：`requireAllOwned` 的 404/3001 与
  `precheckEmbeddingModels` 的 4006 仍在扇出**之前**整批失败。把越权探测变成"部分成功"
  比报错更糟；"某个库的嵌入模型和别的库不一样"是配置错误，隔离它只会让用户拿到一堆无法解释的缺料。

**4. 测试：567 个，0 失败（104 个类 / 18 skip）**

本增量新增 26 条：

| 类 | 条数 | 钉住什么 |
| --- | --- | --- |
| `MultiKnowledgeRetrieverTest` | 12 | 单库**不切线程**（断言执行线程 `isSameAs(caller)`）；双库真并行（并发峰值 ≥ 2）；**按输入顺序装配**（用 `CountDownLatch` 让后提交的库先完成）；一个失败被隔离且计数 +1；**全部失败抛原异常**（`isSameAs`）；优先抛 `BusinessException`；超时记 failure 而不是记成空结果；**调用线程被中断时响亮失败**（见 §5）；MDC 与 TCCL 跨线程可见 |
| `RagAnswerServiceRetrievalFailureTest` | 5 | failures 一路走到 `userMessage`；空结果**仍带 failures**；"检索范围不完整"出现在【资料】**之前**；无失败时一个字都不提；**单库恒无 failures** |
| `KnowledgeSearchToolFanoutTest` | 3 | 一个库不可用 → 其余库照常贡献 + 降级计数分开；LIKE 兜底仍留在任务体内（用真插一行 `kb_chunk` 验）；**痕迹全部在调用线程回放** |
| `ChunkSearchServiceMultiKbTest` | +5 | 部分失败 → 部分结果 + 计数；全部失败 → 抛；归属错误不被隔离 |
| `RagStreamRetrievalEventTest` | +1 | `retrieval` 事件里带 `failedKnowledgeBases`（逐字段钉住 `knowledgeBaseId` / `knowledgeBaseName` / `reason` 三个名字——前端读的就是它们），且空结果文案随之改变 |

**界面这一层也验了，而且是真的跑过一次**（不是只写了脚本）：
`standalone`（H2，**不需要 Docker**）起后端 + `seed-demo.mjs` 播种 + Vite dev server，
`node web/scripts/verify-retrieval-ui.mjs` **退出码 0**。这个脚本原来只有两条腿
（命中两段 / 一段都没命中），本期补到**五条**——其中三条专打新界面：

| 腿 | 帧里的事实 | 断言 |
| --- | --- | --- |
| ③ 其中一个库检索失败 | `count=1`，1 个失败库 | 卡片显示"其中 1 个知识库检索失败"；**悬浮提示给出库名与原因**（"内部 FAQ：ES 连接被拒绝"）；不出现"知识库里没有命中" |
| ④ 全部库检索失败 | `count=0`、`empty=true`、2 个失败库 | 空结果标签改口为"知识库**本次都没有检索成功**"，**不出现**"知识库里没有命中"；回答是 `RETRIEVAL_FAILED_ANSWER` |
| ⑤ 一个库没命中、一个没搜成 | `count=0`、1 个失败库 | 标签说"**其余**知识库没有命中"——三条分支到此齐了 |

腿④与腿②是一对：同样的 `count=0`、同样显示"检索到 0 个片段"，**只有失败库的有无**不同，
而措辞必须不同。这一对同时钉住了服务端与界面两侧的同一句话，避免"后端改了口径、
前端还在说老话"——那正是本期最想防的那类不一致。

两条实测踩到的脚本陷阱也记一下：**①** 卡片会被服务端校正抹掉（命中片段未落库，已知缺口），
所以断言必须在校正落地**之前**跑完（`holdReload` 先别放行）——首轮就是在这儿挂在
"元素被替换"上重试到超时；**②** `hover()` 会被会话列表刷新时的 `el-loading-mask`
挡住指针事件（重试 30 秒也等不到提示框），改用 `dispatchEvent('mouseenter')` 直接派发，
验的还是同一件事（Vue 收到 mouseenter → el-tooltip 渲染）。

**两组证伪跑**（改坏生产代码、确认测试真的会红，再改回）：

1. 把扇出强制串行（`size() >= 1` 走单库分支）→ **8/11 红**，说明并行不是"碰巧测不出来"；
2. 把失败位从 `List.of()` 占位改成 `.filter(Objects::nonNull)`（即"看起来更干净"的那种写法）
   → **恰好 1 条红**（`failingKnowledgeBaseStillOccupiesItsSlotInMerge`，20 条而不是 3 条），
   证明占位这个决定确实在防一次静默的截断行为变化；
3. 把工具里的痕迹回放挪到新线程（`join` 之后条数不变、只是线程不同）→
   **恰好 1 条红**（线程身份断言）。这一条特别值得记：并发写在测试里**常常照样通过**
   （`ArrayList` 的并发 `add` 多数时候不炸，只是偶尔丢一条），所以那条断言看的是
   **线程身份**而不是内容——否则它会在最关键的时候给人虚假的安全感；
4. 把"调用线程被中断"的处理改回第一版的 `return List.of()` → **恰好 1 条红**（见下）。

**5. 复读时抓到的一个真缺陷：中断被吞掉，变成"每个库都没命中"**

第一版的 `runAll` 是这么写的：

```java
} catch (InterruptedException ex) {
    Thread.currentThread().interrupt();
    return List.of();          // ← 错在这里
}
```

`invokeAll` 在中途被中断时会取消所有未完成的任务并抛 `InterruptedException`。
吞掉它、返回空列表，形式上"没有异常"，实际上交出去的是一份**与契约不符的 `ordered`**
（契约是"与输入等长同序"）。调用方会怎么读它：

- `ChunkSearchServiceImpl` 把缺失的库当成"这个库没命中" → 归并出 0 条 → **"资料中没有相关内容"**；
- `KnowledgeSearchTool` 直接 `fanOut.ordered().get(i)` → `IndexOutOfBoundsException`。

也就是说，**这一期要防的那句假话，从我自己新写的代码里又长了出来**，而且只在
"SSE 断开 / 应用停机"这种路径上出现——普通的测试和手工点页面都不会撞到它。
修法是让它抛 `4005`：中断也**不该**被当成"隔离某个库"，因为 `invokeAll` 已经取消了未完成的任务，
此刻"哪些库成功了"根本无从得知，给部分结果就是不诚实的。
（顺带：`fanOut` 里那个 `get()` 的 `InterruptedException` 分支现实中到不了——
`invokeAll` 返回时任务已全部结束，`get()` 不会阻塞。留着只是因为它是受检异常，注释里写明了。）

这个缺陷能抓出来只因为**复读了 diff**，没有别的机制：它没有测试、没有日志、没有真机路径。

**6. 诚实边界**

- **验到了什么、没验到什么，分三层说清楚**（合并成一句"验证通过"就是骗人）：
  1. **契约层**（已验）：`RagStreamRetrievalEventTest` 用 MockMvc 抓真实响应体，
     逐字段钉住 `retrieval` 事件里的失败明细与空结果文案；
  2. **界面层**（已验）：`verify-retrieval-ui.mjs` 五条腿在**真后端 + 真前端**上跑，退出码 0
     ——但它喂的是**桩 SSE 帧**，所以它证明的是"给定这套帧，界面渲染正确"，
     **不证明**后端真的发这套帧（那是第 1 层的事）；
  3. **真机检索层**（**未验**）：真 ES + pgvector + 一个**真的**挂掉的知识库，
     这一层需要 Docker 基础设施栈，本轮按要求暂停 → 留到 5.7。
- **8 个 `verify-*.mjs` 仍未复跑**（`verify-agent-chat` / `verify-stream` / `verify-multi-kb` 等）。
  **这不等于它们没被影响**：`KnowledgeSearchTool` 的执行路径改了（串行 → 扇出 + 回放），
  真机上"多库 + 一个库不可用"的观感必须在 5.7 容器化后实测一次。
- **扇出把下游并发乘最多 10×**（绑库上限）。虚拟线程本身便宜，但 ES / pgvector / 嵌入的真实压力
  上升了。`retrieval.kb.failed` 计数 + WARN 点名让"部分失败"可巡检，不至于变成无人察觉的常态化降级。
- **超时是尽力取消**（与既有 `TimeLimitedCall` 同一措辞）：`cancel(true)` 能中断等待，
  外部 HTTP 能否真正停下取决于客户端。
- 部分成功时回答建立在**不完整**的资料上——这正是第 1 节那张表里提示词那一层必须存在的原因。

---

### B.49 Phase 5.7 实现记录（容器化：一条命令得到完整系统，和四个只有整栈起来才看得见的问题）

**0. 一句话**

把"跑起来"从"先装 JDK、再起五个基础设施容器、再手敲 `java -jar`"收敛成
`docker compose up -d`。**业务代码一行没改**——本期动的是编排，不是逻辑。

**1. 做了什么**

| 文件 | 内容 |
| --- | --- |
| `deploy/backend/Dockerfile` | **单阶段**运行镜像 `eclipse-temurin:21-jre-jammy`：只把宿主机已产出的 jar 拷进去（镜像内不构建），非 root（uid 1001），`ENTRYPOINT ["java","-XX:MaxRAMPercentage=60","-XX:+ExitOnOutOfMemoryError","-jar","/app/app.jar"]` |
| `web/Dockerfile` | `node:22-alpine` 里 `npm ci && npm run build`（含 `vue-tsc --noEmit`，类型错就让镜像构建失败）→ 产物进 `nginx:1.29-alpine` |
| `web/nginx.conf` | `/` → Vue（`try_files` SPA 回退）、`/api` → `backend:8080`、`/api/chat/stream` 关缓冲、`/mcp`、静态探针 `/healthz` |
| `deploy/docker-compose.yml` | 补 `backend` / `frontend` 两个服务；`backend.ports: []`；六个依赖全 `condition: service_healthy`；`stop_grace_period: 30s`；`backend-data` 卷 |
| `deploy/.env.example` | 端口/口令/密钥清单；两个必填项留空 |
| `.dockerignore` / `web/.dockerignore` | 别把 `target/`、`node_modules/`、`.git/` 塞进构建上下文 |

**2. 四个设计取舍（都不是"顺手这么写"）**

1. **镜像里没有开发环境**。运行时镜像只有 JRE；jar 在宿主机用 JDK 21 打包。
   代价是"打包 JDK 版本错"会以 `class file version` 的形式暴露（已写进 `deployment.md` 常见问题），
   收益是镜像小、镜像内不可构建、不存在"镜像里的 Maven 用了另一套依赖"这类问题。
2. **backend 不发布端口**。只有 nginx 需要访问它。"调试走
   `docker compose exec backend curl localhost:8080/actuator/health`" 是一句可执行的解释，
   而"端口也开了但你别用"不是。
3. **secret 的 fail-closed 前移到编排层**。`JWT_SECRET: ${JWT_SECRET:?…}`：
   缺失时 `docker compose config` 直接报错退出，**连容器都不创建**。
   为什么不能只靠应用侧校验——`application-local.yml` 里那个开发默认密钥会把它兜住，
   于是"忘配密钥"的表现是**启动成功、密钥是仓库里人尽皆知的那一个**。
   这是 B.42 那类"自证身份"问题的镜像：**默认值兜住的不是方便，是漏洞**。
4. **frontend 刻意没有 `depends_on: backend`**。nginx 启动不需要后端在；
   `resolver 127.0.0.11` + 变量 upstream 让后端重建后自愈（先 502，起来即正常）。
   加 `depends_on` 只会让"后端暂时不健康"拖住整个前端。

**3. 真机验收（2026-10-02，Docker Desktop 29.5.2 / Compose v5.1.3，WSL2，容器可用 7.569 GiB）**

| 项 | 结果 |
| --- | --- |
| `docker compose config` | 通过；把两个必填项置空后**报错退出**（REAL ENVIRONMENT） |
| `docker compose build` / `up -d` | 成功；**8/8 服务 healthy**（冷启动 21s 返回、33s 全绿） |
| 11 个 `verify-*.mjs`（**经 nginx :8088**） | **11/11 EXIT=0** |
| 18 个 live 测试（真 ES / 真 pgvector / 真 Ollama / 真模型） | **全部真跑并通过**，此前是 skipped |
| `restart` → `down` → `up -d` | 7 项数据逐项存活（MySQL 行数 / 13 条向量 / 13 条 ES 文档 / RabbitMQ durable 队列 / bge-m3 / `backend-data` 696K） |
| `verify-multi-kb-failure.mjs`（新增） | EXIT=0：A ✅ / B ❌ / C ✅、`retrieval.kb.failed` 4→5、界面「其中 1 个知识库检索失败」并悬浮点名 |

**5.5.1 留的那条真机腿，本期怎么补的**

`verify-multi-kb-failure.mjs` 用**真实 ES 里一条真实的坏文档**制造单库故障：
写一条 `_id` 非数字、`knowledge_base_id` 指向 KB-B 的文档，真实检索代码在
`Long.valueOf(hit.id())` 处抛 `NumberFormatException`——**只在 KB-B 的扇出任务里抛出**。
没有替换 Bean、没有打桩、没有改业务代码；注入的是真实基础设施里的真实坏数据。
（另一条路"停掉 ES"不行：ES 是全局共享依赖，停掉会让**所有**库一起失败，
那验的是"全挂"而不是"隔离"。）

**4. 五个只有整栈起来才看得见的问题**（详见 `productization-gap-analysis.md` §5.7 修正表）

1. **一个 LIVE 测试会清空共享 ES 索引**：`LiveChunkIndexTest.recreateIndexSwitchesAlias`
   的断言本身没错（重建后确实为空），代价是它清的是**开发栈正在用的那个索引**。
   BM25 静默失效、向量腿照常召回——症状是"少了一路召回"，不是"搜不到"。
   **本期只记录，不改**（测试卫生，超出范围）；恢复方式写进 `deployment.md`。
2. **`recreateIndex()` 不是同一秒幂等的**：物理索引名 `yyyyMMddHHmmss` 秒级，
   同一秒两次重建 → `resource_already_exists_exception` → 接口 500，
   且**切片已被标成 `INDEXED` 而索引是空的**（`/index` 报 `total: 0`）。
   又是一个"错了但看起来正常"。**本期只记录**（Known Gap）。
3. **E2E 脚本的 `API_BASE` 约定不一致**（`verify-security` 要根地址、其余要 `…/api`；
   默认 `WEB_BASE` 5173/8088 混用）。容器化后 backend 不发布端口，
   11 个脚本全部秒挂在 `ERR_CONNECTION_REFUSED`——**看起来像部署坏了，其实是脚本的默认值过期**。
   按 §十二 的要求**没有改脚本**，改成显式传环境变量。
4. **`verify-retrieval-ui.mjs` 的失败根因在数据、不在代码**：82 个 E2E 残留会话 + `pageSize=50`
   + 新会话 `last_message_at` 为 NULL + **MySQL 在 `DESC` 下把 NULL 排最后**
   → 会话落到第 2 页 → 消息列表接口没被调用 → 检索卡片没被清掉。
   清掉残留（82 → 32）后 12 条腿全绿，脚本与业务代码都没动。
5. **一个前端竞态，经过 nginx 之后从"偶发"变成"必现"**：`listMessages` 的响应晚于
   「新对话」点击回来，无条件写 `items` → 界面写着"新对话"、内容是上一条会话的消息。
   **本期修**：`AgentView.vue` 的 `messageLoadSeq`（每次加载编号，只有最后一次允许写）。
   这条很有意思——它提示**部署形态的延迟变化会把潜伏的竞态从偶发放大成必现**，
   而它只有容器化后才撞得上。

**5. 诚实边界**

- **未验**：生产形态（ES 安全模块仍关闭、无 TLS、密钥仍是环境变量注入而非外置密钥管理、
  单实例无边车）——属 Phase 11；**WebSocket 未验，因为项目不用**（流式走 SSE）。
- **未做**（update.txt §十九 明确禁止本期顺手做）：Retrieval Event 持久化（P2-4）
  ——并行之后它更要紧了，但按要求**只登记为 Known Gap**。
- §2.13 那些耗时/内存数字是**观察值，不作为性能指标**。
- **`fanout-timeout=10s` 本期未改**：三库检索段实测 978–3781 ms，仍在同一量级并留有余量；
  按要求"不要为了追求更快而盲目修改"。

---

### B.50 Phase 5.9 实现记录（最终完善：七个优先级，和两处"写了却从不生效"的声明）

> 编号说明：同 B.42–B.49，属**产品化**这条线。对应需求方 `docs/update.txt` 的第四～十节。

**0. 一句话**

这一轮没有新功能，也没有新架构：七件事全是"补上已经声称存在、实际不成立的那一半"。
其中最锋利的两件（MCP 工具权限、JWT `iss`）形状完全一样——
**代码里写着这个开关/声明，运行时却没有任何一处真的读它**。

**1. 七件事（按需求方给的优先级）**

| # | 问题 | 修法 | 关键文件 |
| --- | --- | --- | --- |
| 四 | 重建 KB-A 的 ES 索引会把 KB-B/C 的文档一起删掉，而 B/C 的切片状态仍是 `DONE`，于是它们的 BM25 **永久静默失效** | 每个知识库一份物理索引 + 一个**自己的别名**（`<base-alias>_<kbId>`）；物理名尾部再加一个 `AtomicLong` 序号，兜住"同一秒重建两次"的重名 | `ElasticsearchChunkIndex` |
| 五 | 会话流式（RAG 模式 / Agent 模式）的引用**不落库**，"刷新聊天页面引用就没了" | 复用现有 `RagCitation`，把 `Citations.build` 的结果写进 `chat_message.metadata.citations`；**不新建表、不新建引用系统** | `Citations` / `RagAnswer` / `ChatMessageServiceImpl` |
| 六 | 只有"降级计数"，没有 LLM/对话/检索的运行指标 | `ObservabilityRegistry` 接上 Micrometer（**唯一上报口、两种读法**：同一批点既进 `/api/observability/summary` 也进 `/actuator/metrics`）；新增 `llm.calls` / `llm.tokens` / `llm.latency` / `chat.requests` / `chat.latency` / `retrieval.requests` / `agent.executions` / `errors` | `ObservabilityRegistry` / `UsageMeteringAdvisor` / `AppConfig` |
| 七 | `Live*` 联调测试连的是开发者**此刻正在运行**的那套业务容器，最坏的一次把共用的 ES 索引清空了 | 引入 Testcontainers（`LiveInfrastructure`）：MySQL / ES / pgvector 各起各的一次性容器，随机端口，用完销毁；`mvn test` 的默认行为**不变**（仍由环境变量开关决定跑不跑） | `nexus-app/.../support/LiveInfrastructure` |
| 八 | MCP 直调路径**完全没有**用户工具权限判定：界面上关掉的工具，`tools/list` 照旧列出、`tools/call` 照旧执行 | `tools/list` 与 `tools/call` 同走 `ToolRegistry.enabledFor`；被停用 → JSON-RPC `error`（自定义 `-32003` + 业务码 `5004`），**不是** `result.isError` | `McpEndpoint` / `ToolRegistry` |
| 九 | JWT 的 `iss` **只写不验** | `JwtValidators.createDefaultWithIssuer(...)`，与 timestamp、令牌版本一起挂在同一个 `DelegatingOAuth2TokenValidator` 上 | `SecurityConfig` |
| 十 | `CreateConversationRequest` 的 javadoc 还写着"检索侧只支持单库，绑多个会被拒绝" | 改成与 5.5 之后的实现一致（可绑多个、上限 10、逐个校验归属；建会话时允许为空、提问时不允许） | 同左 |

**2. 本期真正的主题：两处"声明写了，没人读"**

八和九是同一类问题的两个样本，值得单独记一笔：

- **MCP 工具权限**：界面上那个开关**看起来生效了**——Agent 装配确实按它过滤，
  关掉检索工具后 Agent 真的不再检索。但同名的工具仍然可以从 `/mcp` 调起来，
  还照样读同一个用户的知识库。**没有报错、没有日志、没有任何一处会提示你**。
  这就是本项目一路在防的"半个生效的开关"，它的杀伤力恰恰来自"另一条路径看起来是好的"。
- **JWT `iss`**：不算可利用的漏洞（对称密钥已经决定了只有本应用签得出令牌），
  但"写了一个声明却从不验证"会让后来的人**以为这条链路已经校验过它**。
  将来出现第二个签发方、或别的组件共用同一个密钥时，这一条就是唯一能区分"谁签的"的东西；
  而那时才发现它一直没生效，代价要大得多。

两条的修法都不是"加功能"，而是**让已经有名字的东西真的成立**。

**3. 两处刻意保持的"不同"（不是遗漏）**

1. **`-32003` 与 `-32601` 不合并**。"名字写错了"（去查文档）和"你的开关生效了"（去界面打开）
   是两件不同的事，合成一句话会把人引到错的地方。JSON-RPC 2.0 没有"无权限"这一档
   （`-32000..-32099` 是实现自定义区间），所以自定义了 `-32003` 并同时给出业务码。
2. **引用与工具痕迹共用 `chat_message.metadata` 一列**，不各占一列：它们都是"这条回答的附属事实"，
   列数不该随附属事实的个数增长；而两列会让"只更新了一半"成为可能
   （读到的痕迹是这一轮的、引用是上一轮的）。

**4. Agent 多轮召回的引用：为什么必须"分组"算**

Agent 模式下模型可能调多次 `knowledge_search`，而工具是无状态的——它**不知道自己是不是本轮第二次调用**，
所以每次调用的 `[n]` 都从 1 开始。若把所有召回拉平后再去回答正文里比对编号，
第二次调用的第 1 条会被拿去比对第一次调用的 `[1]`，结论正好反过来。
因此 `Citations.merge` 按**组**计算引用、按 `chunkId` 去重、最后统一重编号；
同一段出现在多个编号上时"是否被引用"取**并集**（宁可少标一次"未引用"，
也不要指着一份确实摆在模型面前、且正文引用过的资料说它没被用上——后者是错的断言）。

**5. 假完整盘点（对应 §十三）**

全仓搜了 `TODO / FIXME / UnsupportedOperation / return null / mock / 暂不支持 / 未实现` 等标记，
逐个判定后的结论是：**没有"其实没实现却看起来实现了"的残留**。剩下的分三类，全部如实留在
`productization-gap-analysis.md`：①有意设计（如 `DisabledChunkIndex` / `DisabledVectorStore`
在开关关闭时**明确抛错**而不是静默返回空）；②已知 limitation（如单实例无 TLS、无 refresh token）；
③需求方明确要求**本期不做**的（如 Retrieval Event 持久化）。

**6. 诚实边界**

- **未做**（§十四 明确列出）：stream cancellation 跨实例、refresh token、HttpOnly Cookie、TLS、
  Prometheus/Grafana、K8s、微服务、完整 MCP server。这一轮**一条都没有顺手做**。
- **未验**：生产形态的运维属性（密钥外置管理、TLS、多实例）——属 Phase 11。
- **`fanout-timeout` 仍未调**：没有性能证据支持改它，按要求不动。
- 指标是**观察值**，不宣称任何性能指标。

**7. 测试**

新增 7 个测试类（共 27 条用例）+ 1 个测试基础设施类：
`McpToolPermissionTest`(4)、`JwtIssuerValidationTest`(1)、`ActuatorMetricsTest`(3)、
`CitationsTest`(8)、`ChunkIndexServiceRecreateTest`(3)、`ElasticsearchChunkIndexNamingTest`(3)、
`ObservabilityRegistryTest`(5)、`LiveInfrastructure`（Testcontainers 装配）。

其中两条的写法值得单独说：
- `JwtIssuerValidationTest` 用**同一套声明签三支令牌、只有 `iss` 不同**（正确 / 错误 / 缺失）。
  只测"错误 iss → 401"是不够的——那个 401 也可能出自手工令牌的别处（漏了 `ver`、`sub` 指向的用户不存在），
  那样用例照样绿，却什么也没证明。**已实测**：去掉 `createDefaultWithIssuer` 后，
  错误 `iss` 那一步确实从 401 变成 200（即用例真的在看着这件事）。
- `McpToolPermissionTest` 成对断言"列不出来 **且** 调不动"，并在停用**之前**先断言它列得出来——
  否则"看不到"可能只是因为一开始就没有。

`JAVA_HOME=jdk-21 mvn -B test`：**343 条用例全绿**（21 条 Live 测试按环境变量跳过）。
`web && npm run build` 退出码 0。

---

### B.51 Phase 5.9 真机验证：两处"只在某一个入口才不成立"的声明（2026-10-03）

> 编号说明：同 B.37 / B.39 / B.40，属**真机验证**这条线——都是整栈起来、按真实用户链路走一遍才暴露出来的，
> 与 B.50（按需求方清单实现）形成对照：这一节里的两件事，**没有任何一条是需求方要求的功能**。

**0. 一句话**

回归跑了全部 12 个 E2E 脚本并逐项复核 §十一 的 A–G，挖出两个缺陷。
形状相同：**声明在大部分路径上成立，恰好在一个一等入口上不成立**——
一处以 500 告终（响亮，但只有真的点过那个按钮才会响），一处是"少记了一部分数字"（完全静默）。

**1. 缺陷一：`/index/recreate` 对"还没有索引的知识库"一律 500**

| | |
| --- | --- |
| 症状 | `POST /api/knowledge-bases/{id}/index/recreate` → `9999 系统内部错误` |
| 真机日志 | `ElasticsearchException: [es/indices.get_alias] failed: [http_status_404] alias [nexus_chunk_2105290932692271106] missing`，栈顶 `currentIndexBehindAlias(ElasticsearchChunkIndex.java:395)` ← `recreateIndex:132` |
| 根因 | `currentIndexBehindAlias` 直接 `getAlias`，没有先 `existsAlias`。别名不存在时 ES 抛的是 `ElasticsearchException`——**既不是空响应，也不继承 `IOException`**，于是它绕过 `recreateIndex` 的 `catch (IOException)` 冒成 9999；同时让 `oldIndex == null`（"别名还不存在，直接建别名"）那个分支**成了死代码** |
| 为什么现在才出现 | 全局唯一别名时代，别名只要被写过一次就一直在，而重建总发生在写过之后——这条路径根本到不了。5.9 改成每知识库一个别名以后，"某个库还没有索引"变成**常态**：新建的空库、以及升级前入库的所有知识库都是这个状态 |

修法是三行：先 `existsAlias`，不存在返回 `null`——那半个分支本来就在等这个返回值。

**证据（红 → 绿，两个层次都实测过）**

1. **真机**：修复前两个演示知识库都返回 9999；修复后返回 `{"code":0}`，紧接着 `POST /index`
   把 4 条 / 9 条切片灌回各自的按库索引，`mode=bm25` 从 **0 命中恢复到 1 / 3**
   （升级前的切片只存在于旧的全局索引里，按库别名下一条都搜不到——这正是 5.9 要修的那个静默失效，
   在两个演示库上**真的发生过**）。
2. **联调用例**：新增 `LiveChunkIndexTest.recreateIndexWorksWhenNoIndexExistsYet`（自带 ES 容器）。
   去掉 `existsAlias` 前置判断后它报错，报的**就是真机那一句**：
   `[http_status_404] alias [nexus_chunk_999100001] missing` @ `currentIndexBehindAlias`；加回去变绿。
   `LiveChunkIndexTest` 9/9 真跑通过。

**为什么单测抓不到它**：`ChunkIndexServiceRecreateTest` 把 `ChunkIndex` 端口整个 mock 掉了——
它验的是"作用域、状态重置顺序、归属拒绝"，而故障在**端口实现里**（ES 客户端的异常类型）。
这也正是 B.50 第七件（联调测试自带容器）的价值：没有那一步，这条只能靠"读代码"发现。

**遗留（有意不做）**：`recreateIndex` 先 `createIndex` 再切换别名，切换阶段失败会留下一个
**没有别名的空索引**。这次把现场那一个（`..._20261003104647_13`）手工删了，但没加"失败即清理"的逻辑——
那属于失败路径上的新行为，超出"修一个 500"的范围。记在这里，不假装它不存在。

**2. 缺陷二：`retrieval.requests` 漏掉整个知识库问答入口**

| | |
| --- | --- |
| 症状 | 问了两次，`nexus.llm.calls` 记到 4，而 `/actuator/metrics/nexus.retrieval.requests` **404——指标根本不存在**；`/api/observability/summary` 里也只有 `llm.calls` |
| 根因 | `RETRIEVAL_REQUESTS` 只在 `RagAnswerServiceImpl.prepareRetrieved` 自增，而同步问答 `ask()` 是**自带检索的独立路径**（它不经过 `prepare` / `prepareMany`）——于是"从知识库问答页发起的检索**一次都不算**" |
| 为什么危险 | 这个指标的失效形态是"少了一部分"，看起来像"流量低"：没有报错、没有日志，与"这个服务确实没流量"完全无法区分 |

修法：`ask()` 在自己的检索调用前计数一次，判据与 `prepareRetrieved` 保持一致
（空问题已在上面早退，走到那里就是"真的搜了"）。

**证据（红 → 绿）**：新增 `RagAnswerServiceRetrievalMetricTest`（4 条）。
去掉那一行自增后，两条同步问答用例变红（`expected: 1L`，实际 0），
而"`prepare` 照旧计一次"与"空问题不计数"两条**照旧绿**——说明这是**补上**，不是把计数搬了个地方。

**3. 这一轮刻意没改的东西（边界，不是遗漏）**

- **Agent 的检索不计入 `retrieval.requests`**：Agent 走 `knowledge_search` 工具，
  已经由 `agent.search.*`（命中 / 空 / 降级 / 不可用）与 `tool.call.*` 计数。
  把它并进 RAG 的检索次数，会把"用户问了一次"和"Agent 自己搜了三次"混成同一个数。
- **`/search` 调试端点不计**：它服务于"调参 / 对照"，与"有没有人为回答而检索"不是同一件事。

**4. 这一轮的验证覆盖**

- 12 个 E2E 脚本 **12/12 退出码 0**；
- §11A（**真机上看 ES 索引与别名**）：重建 KB-A 后，KB-B 的别名、9 条文档、3 条 BM25 命中全程不变；
- §11B：同步 RAG / 流式多库 / Agent + Tool Calling 三条链路的引用在**重载之后**仍在，
  字段齐全（`knowledgeBaseId` / `documentId` / `chunkId` / `documentTitle` / `headingPath` / `snippet` / `score`）；
- §11C：`nexus.llm.calls` / `nexus.llm.tokens` / `nexus.llm.latency` 有真实数值，且与 summary 的同一个数对得上；
- §11D：`Live*` 测试跑在自己的容器里（日志里可见 `mysql:8.4.11` 与 `nexus-elasticsearch:9.4.5` 各自拉起、随机端口）；
- §11E：停用工具后 `tools/list` 不再列出、`tools/call` 返回 JSON-RPC `-32003`（业务码 5004），
  重新启用后同一个调用正常返回——拒绝确实来自那个开关；
- §11F：同一套声明签三支令牌，只有 `iss` 不同：正确 → 200，错误 / 缺失 → 401。

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
