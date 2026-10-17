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
- 无状态意味着**登出与吊销需要额外机制**。Access 侧：改密 / 重置靠 `token_version`（ADR-020）；单支 access 的 jti 黑名单仍未做（logout 不立刻废 access）。
- 短 access + 长会话：认证 **Phase 4 已落地** opaque refresh + rotation + `sys_user_session`（V21），见 `docs/api.md` §2.1。

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

## ADR-021 Token 从 localStorage 迁到 HttpOnly Cookie：**Cookie 迁移仍只出设计；Refresh 已另轨落地**

**背景**：用户要求"分析 localStorage 的 XSS / Token 泄露风险，给出 HttpOnly Cookie 升级方案，
**但不要直接大改**"。Cookie 迁移仍是**设计记录**；认证 Phase 4 已在**不改 Cookie 模型**的前提下
落地 refresh + 会话（见下「已落地」）。

**现状与风险**

- 令牌存在 `localStorage`（现键：`nx-token` / `nx-refresh-token` / `nx-token-expires-at`，
  `frontend/src/lib/api.ts`），**任意一次 XSS 即可读走 access 与 refresh**。
- 前端 Markdown 经消毒不等于没有 XSS（供应链 / 第三方脚本等）。
- **已落地（认证 Phase 4，非本 ADR 的 Cookie 阶段）**：
  - opaque refresh + rotation + `sys_user_session`（V21）；
  - `/api/auth/refresh`、`/api/auth/logout`、`/api/users/me/sessions*`；
  - 前端 401 → 单飞 refresh → 重放；改密 / 重置密码撤销全部 refresh。
- **仍未缓解的 XSS 面**：refresh 与 access 同在 localStorage；被盗后可在会话过期前持续换发
  （可用「退出全部设备」或改密止损）。HttpOnly Cookie 才是把凭证移出 JS 可读域的路径。

**Cookie 不是"白拿的安全"**——迁移必须一起处理三件事，否则是拿 XSS 换 CSRF：

1. `SecurityConfig` 关 CSRF 的**前提正是"不用 Cookie"**（ADR-018 第 2 条）。换成 Cookie 后
   `CookieCsrfTokenRepository` 必须加回来。
2. `HttpOnly` + `Secure` + `SameSite` 按部署形态定；跨源还需 `withCredentials` + CORS credentials。
3. 前端 401：已实现「调 `/auth/refresh` 再重放」；迁 Cookie 后要把读写从 localStorage 换成 Cookie 通道。

**分阶段迁移路径**（①–④ 仍未实施；⑤ 的 refresh 能力已由 Phase 4 用 DB 会话表完成）

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| ① | 登录时**同时**下发 `HttpOnly + Secure + SameSite=Lax` Cookie，保留 `Bearer` 头路径 | 未做 |
| ② | 后端 `BearerTokenResolver`（**请求头优先、Cookie 兜底**） | 未做 |
| ③ | 前端去掉 localStorage，改为 `withCredentials` | 未做 |
| ④ | 开 `CookieCsrfTokenRepository`，前端回填 `X-XSRF-TOKEN` | 未做 |
| ⑤ | 缩短 access TTL + refresh（原计划 `sys_refresh_token`） | **Refresh 已用 `sys_user_session` 落地**；缩短 access TTL / Cookie 化仍属后续 |

**诚实边界**：**localStorage 的 XSS 暴露面依然存在**（现含长寿命 refresh）。
Cookie 迁移未做完之前，不得宣称"令牌已不可被 JS 读取"。

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


---

## 附录（已拆分）

本文只保留 **ADR-001 ~ ADR-022** 的主体决策。
版本矩阵、实测验证记录与 API 核对已迁至 [decisions-appendix.md](decisions-appendix.md)，
章节编号（A / B.x / C / D.x）与拆分前完全一致。

> 引用「decisions.md B.44」时，请到 `decisions-appendix.md` 查 B.44。
> 拆分原因：附录占原文件约 91% 的体积，查询任何一条 ADR 都需加载 347 KB。

**未拆分内容**：ADR 主体保持原样，未改动任何一条决策的表述。
