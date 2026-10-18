# 路线图：Phase 0 → Phase 11

> ## ⚠️ 先读这一段：编号体系有两套，同名不同义
>
> **本文的 Phase 0–11 是"功能阶段"编号**（Phase 5 = Tool Calling、Phase 6 = Agent、Phase 11 = 工程化）。
>
> 项目实际推进中还并行了一条 **"平台化"轨道**，它用的是**另一套编号**（平台化 1 / 2 / 3.1–3.4 / 4.1–4.2 / 5.3–5.8、以及 P1-1…P1-18 这样的条目号）。
> 例如"平台化 5.6"（用户与安全）、"平台化 5.7"（容器化）与本文的 **Phase 5（Tool Calling）毫无关系**——它们只是数字撞上了。
>
> 两套编号的对照、以及平台化轨每阶段的验收证据，见：
> - [decisions.md](decisions.md) 附录 B（逐阶段实测记录）
> - [archive/reports/productization-gap-analysis.md](archive/reports/productization-gap-analysis.md)（P1/P2/P3 分级与实施顺序；**历史报告**）
> - [../README.md](../README.md) §5 能力清单（**当前进度的权威来源**）与 §9 已知限制
>
> **本文状态**：文首「当前未完成待办」随代码复核更新；下方 Phase 0–11 大段是**功能阶段规划原文**（含早期 Vue 表述），**未全量改写**，只作历史对照。
> 因此**不要用 Phase 表判断"某个功能做没做"**——用 README §5 或直接看代码。

> 规则：**一次只做一个 Step**。每个 Step 的完成标准是"能跑 + 有测试 + 我知道为什么这么做"。
> 每个 Phase 结束必须产出：①更新的 `docs/` ②新增的测试 ③一段能背下来的面试回答。
>
> 另注：本文 Step 0.2 写的是"三个模块"（common / knowledge / app），**平台化阶段新增了 `nexus-ai`**，现为 4 个模块。

## 当前未完成待办（2026-10-14 复核）

> 这一节是**当前真正还欠着的事**，与下面 Phase 0–11「当初想做什么」不同。
> 已落地能力（多用户知识库、混合检索、Agent、MCP、Refresh Token / 邮箱验证、7 服务 Compose、最小 CI）见 README §5 / §8，不在此重复列「待做」。

| # | 待办 | 现状（2026-10-14） | 优先级 |
|---|---|---|---|
| 1 | **用户中止后后端误报"未预期异常"** | ❌ 未修。`send()` 仍可能向已关闭的响应写帧，抛 `AsyncRequestNotUsableException`，被记为 ERROR 并使失败计数 +1。对用户无影响，但日志/指标失真 | P2 |
| 2 | **前端首包体积** | ❌ 未处理。2026-10-14 `npm run build`：`elk-*.js` **≈1.46 MB**（gzip 456 KB）、`index-*.js` **≈1.18 MB**（gzip 392 KB）。主因 mermaid/elk 与 markdown 链 | P3 |
| 3 | **导入任务逐文件进度 UI** | ❌ 后端 `GET /api/import-tasks/{id}/items` 已有；前端仍偏「按知识库单槽」 | P3 |
| 4 | **欢迎区示例问题只有一条** | ❌ 多库时应按库分别生成且须能命中真实资料 | P3 |
| 5 | **E2E / LIVE 进 CI + 数据回收自动化** | ⚠️ 最小 CI 已覆盖默认 `mvn test` + `npm run build` + `compose config`；LIVE/真浏览器 E2E 与「跑完自动回收」仍未进流水线 | P3 |
| 6 | **检索命中列表持久化** | ❌ 「检索到 N 个片段」刷新后消失（引用列表已落库，二者不同） | P2 |
| 7 | **审批闭环的两条边界** | ⚠️ P0 已完成（状态机 / SSE 事件 / `once\|always\|deny` / 授权撤销 / 审计）。仍欠：等待审批的注册表只在内存（进程重启后该运行标 `FAILED` 而不是恢复）、`GET /api/tool-invocations` 审计查询接口未开、`POST /api/agent/runs` 没有自己的流式形态（事件走 `/api/chat/stream` 的工具拦截点） | P2 |
| 8 | **嵌入 BYOK（用户自带嵌入模型与大模型 Key）** | 🚧 **部分完成**（2026-10-18）——P0 知识库列表展示编造值**已修**（改用接口真实 `embeddingModel`，编造的 Collection 列已删除）；**P1 凭证安全已完成**（`user_llm_config.api_key` 改为 AES-GCM 密文入库 + 历史明文升级，见文末同名章节）；P1 路线 A（1024 维白名单）、P2 多维度仍为计划 / 待评估。完整计划见文末同名章节「[嵌入 BYOK（用户自带嵌入模型与大模型 Key）实施计划](#嵌入-byok用户自带嵌入模型与大模型-key实施计划)」 | P1 |

**已不再需要的条目**（勿再捡回来）：

- ~~全局导航侧栏不可收缩~~ → React `Sidebar` 已支持折叠并持久化
- ~~首包体积由 Element Plus 导致~~ → `frontend/package.json` 无 `element-plus`
- ~~无 Refresh Token / 无服务端 logout~~ → Phase 4：`sys_user_session` + refresh 轮转 + 设备会话注销（见 `docs/security.md` §1.5）
- ~~无最小 CI~~ → `.github/workflows/ci.yml`（不含 LIVE/E2E）
- ~~Compose 仍依赖本机 Ollama 拉嵌入模型~~ → 嵌入改云端百炼 `text-embedding-v4`；编排为 7 服务
- ~~工具只有"启停"、没有权限分级与人工确认~~ → P0（迁移 V22）落地：`ai_tool_registry` 权限、`/api/agent/runs` 状态机、SSE `approval_required`、`ai_tool_grant` 可撤销授权、工具调用审计

> 「当前未完成待办」原由已删除的 `docs/future-improvements.md` 迁入（2026-10-10），2026-10-14 再按代码与构建产物修订。

---

## Phase 0 — 项目初始化

**目标**：一条命令起基础设施，一个命令跑起空应用，能连上 MySQL/Redis/PostgreSQL。

| Step | 任务 | 完成标准 |
| --- | --- | --- |
| 0.0 | 环境准备：安装 JDK 21、启动 Docker Desktop | `java -version` = 21；`docker ps` 正常 |
| 0.1 | Git 初始化 + `.gitignore` + `.env.example` + 目录骨架 | 首次提交，密钥不入库 |
| 0.2 | Maven 多模块骨架（root + nexus-common + nexus-knowledge + nexus-app） | `mvn -q clean package` 成功 |
| 0.3 | 依赖基线：Spring Boot 4.1.x + spring-ai-bom 2.0.1 + MyBatis + Flyway + Security + MySQL/PG/Redis/AMQP starter | `mvn dependency:tree` 无冲突、无 1.x 遗留坐标 |
| 0.4 | `deploy/docker-compose.yml`：MySQL、Redis、PostgreSQL(pgvector)、Elasticsearch(+IK)、RabbitMQ | `docker compose up -d` 全部 healthy |
| 0.5 | 统一返回 + 错误码 + 全局异常处理 + RequestId 过滤器 | 故意抛异常，返回结构符合规范 |
| 0.6 | Flyway 基线：`sys_*` 表 + PG 扩展与 `vector_store` 表 | 启动自动建表；`vector_store` 可查 |
| 0.7 | 健康检查与配置分环境（`application.yml` / `-local` / `-dev`） | `/actuator/health` 各组件 UP |
| 0.8 | 冒烟测试（Spring Boot Test） | `mvn test` 通过 |

**交付物**：可运行的空壳项目 + compose + 基线表结构 + 统一异常体系。

**学习知识点**：Maven 多模块与 reactor、Boot 4 starter 坐标变化、Spring Boot 自动配置原理、Flyway 迁移、Docker Compose healthcheck 与依赖顺序、pgvector 扩展安装。

**面试点**：为什么用多模块而不是单模块？→ 见 [decisions.md](decisions.md) ADR-001。

---

## Phase 1 — AI Chat（第一个 AI 闭环）

| Step | 任务 | 完成标准 |
| --- | --- | --- |
| 1.1 | 用户注册/登录/JWT/RBAC + Spring Security 配置 | ✅ **已完成**（28 用例全绿，含真实 HTTP 冒烟；见 decisions.md B.9） |
| 1.2 | **模型可替换层**：`AiModelProperties` + `AiModelRegistry` + `ChatClientFactory`（多 provider 多角色） | 改 yaml 从 DeepSeek 切到 Ollama，业务代码零改动 |
| 1.3 | 第一次真正的模型调用：`ChatClient` 同步 + 流式 | 控制台能打印流式输出 |
| 1.4 | 会话与消息持久化（`chat_conversation` / `chat_message`）+ CRUD | ✅ **已完成**（6 用例全绿，含多租户隔离与分页生效断言；见 decisions.md B.12） |
| 1.5 | ChatMemory：`MessageWindowChatMemory` + `JdbcChatMemoryRepository` 接入多轮对话 | ✅ **已完成**（真实调用验证"第二轮记住第一轮"；见 decisions.md B.13） |
| 1.6 | SSE 流式接口（自定义事件协议）+ 停止生成 | ✅ **已完成**（真实 HTTP 逐行验证；停止后落库 STOPPED；见 decisions.md B.14） |
| 1.7 | Conversation 与 Message 前端页面（Markdown 渲染 + 代码高亮） | 能真实对话 |
| 1.8 | 测试：Controller 集成测试（含 SSE）+ Memory 窗口测试 | `mvn test` 通过 |
| 1.9 | 文档与总结：`docs/observability.md` 初稿（埋点设计）、面试回答 | — |

**关键理解点**
- LLM 是**无状态**的：多轮对话靠把历史消息重新拼进请求。
- Chat Memory（发给模型的有界窗口）≠ Chat History（完整历史）。
- SSE 与 WebSocket 的区别、为什么这里用 SSE。
- 流式响应为什么必须记录"首 Token 延迟"。

**面试点**：Spring AI 的 `ChatClient` 和直接调 HTTP API 有什么区别？Advisor 机制解决了什么问题？

---

## Phase 2 — Knowledge Base（导入我的 Obsidian Vault）

| Step | 任务 |
| --- | --- |
| 2.1 | 知识库 CRUD + `kb_document` 表 + 原始文件存储 —— ✅ **已完成**（建库即冻结嵌入模型；租户隔离；见 decisions.md B.17） |
| 2.2 | 解析器接口 `DocumentParser` + TXT / Markdown 实现（Frontmatter、标题树、WikiLink、Markdown Link、代码块、图片引用）—— ✅ **已完成**（12 个解析器用例，含"代码块内不识别结构"等易错点） |
| 2.3 | PDF 解析（PDFBox）+ DOCX 解析（POI），统一输出"结构化正文 + 元数据" —— ✅ **已完成**（PDF 保留分页偏移供引用反查页码；DOCX 抽出真标题层级；见 decisions.md B.22） |
| 2.4 | 结构化 Chunk 策略（标题层级切分 + 段落递归 + 代码块保护 + offset 记录）—— ✅ **已完成**（13 个切分用例；见 decisions.md B.18） |
| 2.5 | ZIP / Obsidian Vault 导入（安全解压、目录识别、忽略 `.obsidian/`、附件处理）—— ✅ **已完成**（安全限额 + 端到端真实 MySQL 验证；见 decisions.md B.19） |
| 2.6 | SHA-256 增量同步（跳过/更新/删除/改名）+ `kb_import_task` 进度 —— ✅ **已完成**（含"零写入"与"保留向量"的可观测断言；见 decisions.md B.20） |
| 2.7 | RabbitMQ 异步化：上传立即返回，worker 解析切分（嵌入留到 Phase 3）—— ✅ **已完成**（90ms 返回 QUEUED；含幂等 CAS、死信队列、同步/异步可切换；见 decisions.md B.21） |
| 2.8 | 知识库页面：文档列表 + 文档详情（Markdown 渲染、标签、大纲、Chunk 预览）—— ✅ **已完成**（Vue 3 + Element Plus：登录/注册、知识库列表、上传与进度、向量化/建索引/重建索引、文档列表+过滤、**Markdown 正文渲染（代码高亮）+ 可跳转大纲**、切片状态、三模式检索面板） |
| 2.9 | 测试：解析器与切分的单元测试（含边界：空文件、超长段落、嵌套代码块、无 frontmatter）+ 用真实 vault 子集跑集成测试 |

**关键理解点**：为什么结构化切分优于定长切分；为什么文档身份是 `(kb_id, source_path)`；增量同步为什么必须用内容 hash 而不是时间戳。

**面试点**：导入 1000 篇笔记怎么保证不重复、不遗漏、可恢复？

---

## Phase 3 — RAG（本项目最重要的 AI 技术）

| Step | 任务 |
| --- | --- |
| 3.1 | Embedding 服务：批量嵌入 + 并发控制 + 失败重试 —— ✅ **已完成**（批量 + 按 content_hash 去重 + **维度自校验**；缓存放在 PostgreSQL `embedding_cache` 而不是 Redis，理由见 decisions.md B.23） |
| 3.2 | 向量写入 pgvector（HNSW 索引）+ 状态回填 —— ✅ **已完成**（独立数据源 + 独立 Flyway；`vector_id` 刻意不回填，因为 chunk_id 就是 pgvector 主键） |
| 3.3 | ES 索引与 BM25 写入（原生 Java Client + IK 分词 + 别名切换）—— ✅ **已完成**（IK 用 `_analyze` 直接验证；别名原子切换重建；见 decisions.md B.24） |
| 3.4 | 向量检索 + 过滤（强制 kb_id，避免跨库串数据）—— ✅ **已完成**（查询语句用同一个嵌入模型转成向量，模型一致性由 KnowledgeEmbeddingResolver 统一校验） |
| 3.5 | BM25 检索 + 过滤 —— ✅ **已完成**（BM25 与向量共用一套命中结构，便于融合） |
| 3.6 | 混合检索（RRF 融合）+ 去重 —— ✅ **已完成**（`RrfFusion` 纯函数 + 5 条单测；三种模式都可单独调用以便对比；见 decisions.md B.25） |
| 3.7 | 重排（`Reranker` 接口 + `LlmReranker` 实现，为外部重排 API 预留实现位）—— ✅ **已完成**（单批打分、失败自动降级为召回顺序） |
| 3.8 | 上下文组装（Token 预算、相邻块扩展、防注入分隔）—— ✅ **已完成**（去重 + 编号引用 + 预算丢弃并如实计数；`ContextAssemblerTest` 4 条） |
| 3.9 | RAG 提示词与引用生成 + 前端引用点击定位 —— ✅ **已完成（MVP）**（`POST /{id}/ask`：混合检索→重排→组装→生成→引用归位；回答里的 [n] 可点开原文小节）；待补：引用落库（`chat_message_citation`）与流式回答 |
| 3.10 | 降级策略（ES/重排/向量不可用时的行为）+ 检索调试接口 `/api/search/explain` |
| 3.11 | 测试：检索单测 + Testcontainers 集成测试（真实 PG/ES 里灌入固定小数据集，断言召回） |
| 3.12 | 文档：`docs/rag.md` + 参数调优记录（用真实笔记验证） |

**关键理解点**：Embedding 是什么、维度与语义空间、余弦相似度、HNSW 近似检索、BM25 与向量的互补性、RRF 为什么优于加权求和、重排为什么能提升精度、Chunk 大小如何影响召回、Metadata 过滤为什么必须下推到检索层。

**面试点**：RAG 全链路怎么设计？为什么混合检索？如何评估检索质量？（Phase 9 有实测数据）

---

## Phase 4 — Personal AI Knowledge Assistant

| Step | 任务 |
| --- | --- |
| 4.1 | 知识总结：按文档/目录/标签生成摘要（含引用） |
| 4.2 | 知识关联：WikiLink + Tag + 标题共现构建关联图（`kb_document_link`） |
| 4.3 | 知识搜索问答：让"我的笔记里哪里讲过 X"能被精确回答（多文档聚合） |
| 4.4 | 学习路线生成：基于标签/目录覆盖度 + 前置知识规则 |
| 4.5 | **知识缺口分析**（重点）：基于"实际存在的文档/标签/内部链接（含未解析 WikiLink）"给出覆盖与缺口清单，每条结论必须附证据（命中的文档路径或统计数据），禁止凭空断言 |
| 4.6 | 前端：知识分析结果页（覆盖度矩阵、缺口清单、证据链接） |
| 4.7 | 测试：缺口分析的规则测试（构造已知知识库，断言"覆盖/缺口"判定正确） |

**面试点**：AI 说"你缺少 Sentinel 知识"时，你怎么保证这不是幻觉？→ 用规则+证据双重校验，规则输出候选，模型只做表述，且必须附文档证据。

---

## Phase 5 — Tool Calling

| Step | 任务 |
| --- | --- |
| 5.1 | 工具注册表：`@Tool` Bean 扫描 + `ai_tool_registry` 元数据同步 + 权限标注 |
| 5.2 | 知识类工具：`searchKnowledge`、`getDocument`、`listDocuments`、`searchByTag`、`searchByFolder`、`getKnowledgeGraph` |
| 5.3 | 业务类工具：`queryProjectInfo`、`queryTask`、`createStudyPlan`、`generateReport`（含写操作标记） |
| 5.4 | 工具执行器：参数校验、超时、异常包装、审计落库 |
| 5.5 | 聊天中启用工具（框架自动 advisor 路径）+ 前端展示工具调用过程 |
| 5.6 | 测试：工具入参 Schema 测试、执行失败与超时测试 |

**关键理解点**：Tool Calling 的本质是"模型输出结构化调用意图，应用负责执行"（官方明确由应用执行）；工具描述质量直接决定选择正确率；工具过多会降低选择准确率。

---

## Phase 6 — Agent

| Step | 任务 |
| --- | --- |
| 6.1 | Agent 定义与配置（提示词、模型角色、最大步数、超时、工具白名单） |
| 6.2 | `Planner`：目标拆解与"是否需要检索/工具"判定 |
| 6.3 | `AgentExecutor`：**手动驱动工具循环**（关闭自动 advisor），含重试/超时/最大步数 |
| 6.4 | 权限体系：`READ` / `WRITE` / `DANGEROUS` 落库到 `ai_tool_registry`，执行前读库判定 —— ✅ **已完成**（P0，V22；权限单独一张注册表，见 `database.md` 6.0.4） |
| 6.5 | 人工确认：`WAITING_APPROVAL` 状态 + SSE `approval_required` + 恢复接口 —— ✅ **已完成**（P0；`/api/agent/runs` 状态机 + `once`/`always`/`deny` + `/api/users/me/tool-grants` 可撤销授权） |
| 6.6 | 审计：`ai_tool_invocation` + `ai_agent_run(_step)` 全轨迹 —— ✅ **已完成**（P0；入参与结果脱敏落库，`THINK` 只写阶段标记、不存隐藏推理） |
| 6.7 | 前端：Agent 页面（目标输入、步骤时间线、工具调用卡片、确认弹窗） |
| 6.8 | 测试：越权调用必须被拒；拒绝后模型能换方案；超过最大步数正确终止 |

**面试点**：Agent 和 Workflow 的区别？为什么要手动控制工具循环？（权限与人工确认需要插入点）

---

## Phase 7 — MCP

| Step | 任务 |
| --- | --- |
| 7.1 | 新建 `nexus-mcp-server` 模块（Streamable HTTP，只读工具） |
| 7.2 | 暴露 `searchKnowledge` / `getDocument` / `searchByTag` / `listKnowledgeBase` |
| 7.3 | app 侧 MCP Client 接入，工具并入 `ToolRegistry`（`source=MCP`，权限 READ） |
| 7.4 | 内部鉴权（API Key）+ 连接状态与工具刷新接口 |
| 7.5 | 前端 MCP 页面（Server 状态、工具清单、调用测试） |
| 7.6 | 用第三方 MCP 客户端（如 IDE 助手）验证能读到我的知识库 |
| 7.7 | 测试：MCP 端到端（容器内起 server，client 调用并断言返回） |

**面试点**：MCP 解决了什么问题？和直接写 Tool 有什么区别？→ 能力提供方与消费方解耦，一次实现多客户端复用。

---

## Phase 8 — Workflow

| Step | 任务 |
| --- | --- |
| 8.1 | 定义模型与存储（`wf_definition/node/edge`）+ 校验（无环、有 START/END） |
| 8.2 | `WorkflowEngine`：节点调度、上下文传递、变量解析 |
| 8.3 | 节点执行器：**实际实现 8 种**（`START`/`KNOWLEDGE_RETRIEVAL`/`LLM`/`AGENT`/`IF_ELSE`/`TEMPLATE`/`HUMAN_APPROVAL`/`END`）；设计稿里的 `AI`/`TOOL`/`CONDITION`/`PARALLEL` 未实现（`AI`≈`LLM`、`CONDITION`≈`IF_ELSE`） |
| 8.4 | 运行持久化与恢复（`wf_run` / `wf_node_run`，失败节点重试）—— ✅ **已实现**：`POST /api/workflow-runs/{runId}/retry` 用已成功节点输出回灌后续跑；**V24 起** `wf_run.retry_of_run_id` 记录「重试自哪次运行」（此前只能靠 `trigger_type=RETRY` 区分，无法溯源） |
| 8.5 | 预置工作流：知识缺口分析、7 天学习计划、知识报告 —— ✅ **已实现**（`GET /api/workflows/presets` + `POST /workflows/presets/{code}`），但为**用现有 8 种节点写出的顺序版**：设计稿里那套 `PARALLEL` 扇出与工具节点仍不存在 |
| 8.6 | 前端：工作流画布（可先用表单化编辑器，画布放最后）+ 运行轨迹 —— ✅ 画布、逐类型配置、运行轨迹与运行历史均已实现 |
| 8.7 | 测试：条件分支、并行收敛、人工确认恢复、失败重试 —— 人工确认与失败重试已有后端用例 + 浏览器 E2E；**并行收敛无对应实现**，条件分支仅单测 |

---

## Phase 9 — Evaluation

| Step | 任务 |
| --- | --- |
| 9.1 | 评测集与用例导入（JSON/YAML） |
| 9.2 | 批量执行器（异步 + 可中断 + 保存配置快照） |
| 9.3 | 检索类指标（HitRate@K / MRR / Recall@K / Context Precision）——纯规则，无模型 |
| 9.4 | 生成类指标（Answer Relevance / Faithfulness）——裁判模型（可选 Spring AI `RelevancyEvaluator` / `FactCheckingEvaluator`） |
| 9.5 | 引用类指标（Citation Accuracy）——规则校验 |
| 9.6 | 跨 run 对比页（prompt 版本 / chunk 参数 / topK / rerank 开闭） |
| 9.7 | 提示词版本管理接入（每次调用与评测都记录 `prompt_version_id`） |
| 9.8 | 用真实笔记跑一轮基线，产出**真实数字**写进文档与简历 |

**红线**：指标必须可追溯（保存检索结果、回答、裁判理由），不允许编造；模型不可用时该指标为 `null`。

---

## Phase 10 — Observability

| Step | 任务 |
| --- | --- |
| 10.1 | `obs_ai_request` 埋点（Token / 延迟 / 首 Token / 检索数 / 工具数 / 错误 / 成本） |
| 10.2 | Advisor/拦截器统一埋点，保证"任何一次模型调用都被记录" |
| 10.3 | Spring AI 自带 Micrometer 观测接入 Actuator（`gen_ai.*` 指标） |
| 10.4 | 定时聚合 `obs_daily_stat` |
| 10.5 | 前端看板：调用次数、Token、平均/首 Token 延迟、RAG 次数、工具次数、失败率、成本趋势 |
| 10.6 | 单次请求全链路详情页（检索了什么、调了什么工具、花了多少 Token） |

---

## Phase 11 — 工程化

| Step | 任务 |
| --- | --- |
| 11.1 | Dockerfile（多阶段构建，分层缓存）+ 前端镜像 + Nginx 配置 |
| 11.2 | 一键部署：`docker compose --profile full up -d` |
| 11.3 | 安全加固：限流、文件校验、Prompt Injection 防护、密钥管理、依赖漏洞扫描 |
| 11.4 | 测试体系：单元 + 集成（Testcontainers）+ API 回归；覆盖率报告 |
| 11.5 | 日志与可观测：结构化日志、MDC traceId、慢查询与慢请求 |
| 11.6 | CI/CD（GitHub Actions：`mvn verify` + 前端构建 + 镜像构建） |
| 11.7 | 文档收尾：README（含截图与架构图）、API 文档、部署文档、面试文档 |

---

## 每个 Phase 的"学习知识点"总览

| Phase | 后端知识点 | AI 知识点 |
| --- | --- | --- |
| 0 | Maven 多模块、Boot 4 变化、Flyway、Compose | — |
| 1 | Security + JWT、SSE、Redis 缓存 | LLM 无状态、ChatClient、Advisor、ChatMemory、流式与首 Token |
| 2 | 文件处理、异步 MQ、增量同步、hash | 文档解析、结构化切分、元数据设计 |
| 3 | ES Java Client、连接池、批量写入 | Embedding、向量检索、HNSW、BM25、Hybrid、RRF、Rerank、Citation |
| 4 | 规则引擎与证据链 | 摘要、知识关联、缺口分析的可信实现 |
| 5 | 反射/Schema 生成、超时与重试 | Tool Calling 原理与工具设计 |
| 6 | 状态机、权限模型、审计 | Agent 循环、Planner、规划与反思 |
| 7 | 进程拆分、服务间鉴权 | MCP 协议、能力解耦 |
| 8 | DAG 调度、并发收敛、可恢复执行 | 工作流与 Agent 的边界 |
| 9 | 异步批处理、指标统计 | RAG 评测指标、LLM-as-Judge 的可靠性边界 |
| 10 | 埋点、聚合、成本核算 | AI 可观测性（Token/延迟/检索/工具） |
| 11 | 容器化、CI/CD、限流、安全 | 生产化约束（配额、降级、防注入） |

---

## 第一阶段（Phase 0 + Phase 1）详细实施顺序

```
Phase 0  Step 0.0  环境：JDK 21 + Docker 启动
         Step 0.1  仓库骨架 + .gitignore + .env.example
         Step 0.2  Maven 多模块骨架（能构建）
         Step 0.3  依赖基线（Boot 4.1.1 + spring-ai-bom 2.0.1）
         Step 0.4  docker compose 基础设施（healthy）
         Step 0.5  统一返回/错误码/全局异常/RequestId
         Step 0.6  Flyway：sys_* + PG 扩展 + vector_store
         Step 0.7  配置分环境 + actuator 健康检查
         Step 0.8  冒烟测试 → 提交 tag v0.0.1-phase0

Phase 1  Step 1.1  认证与 JWT
         Step 1.2  模型可替换层（AiModelRegistry / ChatClientFactory）
         Step 1.3  最小可用模型调用（同步 + 流式）
         Step 1.4  会话与消息 CRUD
         Step 1.5  ChatMemory 多轮对话
         Step 1.6  SSE 流式 + 停止生成
         Step 1.7  前端 Chat 页面（Markdown + 代码高亮 + 流式渲染）
         Step 1.8  测试（Controller/SSE/Memory）
         Step 1.9  文档与面试总结 → 提交 tag v0.1.0-phase1
```

**Phase 0 的验收标准（缺一不可）**

```text
✓ mvn -q clean package 成功
✓ docker compose up -d 后 5 个基础组件 healthy
✓ 应用启动无警告级异常，/actuator/health = UP
✓ Flyway 迁移生成 sys_* 与 vector_store 表
✓ 任意异常都返回 {code, message, data} 且带 X-Request-Id
✓ mvn test 全绿
```

---

## 嵌入 BYOK（用户自带嵌入模型与大模型 Key）实施计划

> 追加于 2026-10-18，基于 `fa4e308` 的代码事实。
> **P0 / P1 / P2 全部为「计划中」或「待评估」，本节不含任何已完成的实施**；
> 下面的「现状」只陈述已完成的事实，用来判断依赖，不能当成本节工作的验收依据。

### 现状（已实现，仅作依赖依据）

| 事项 | 事实 | 证据 |
| --- | --- | --- |
| 聊天侧「用户自带 Key」 | **已实现**：`user_llm_config`（`base_url` / `api_key` / `model_name` / `enabled`，`user_id` 唯一） | `V19__user_llm_config.sql`、`UserChatCredentialsProvider.find(userId)`、`ChatClientFactory.get(role, userId)` |
| 嵌入侧选型 | **全局唯一**：`EmbeddingClientFactory.get(String role)` 没有 userId；建库时取全局 `embedding.default` | `EmbeddingClientFactory:59`、`KnowledgeBaseServiceImpl.resolveEmbedding()` |
| 知识库嵌入模型冻结 | **已实现**：建库写入 provider / model / dim，之后改名等操作不改这三列 | `KnowledgeBaseFlowTest` 的「建库即冻结」断言 |
| 检索期一致性校验 | **已实现**：`KnowledgeEmbeddingResolver.resolve(kb)` 拿全局角色与冻结值比对，不一致直接 `4006` 拒绝 | `KnowledgeEmbeddingResolver:35-52` |
| 向量列维度 | **写死** `vector(1024)`，迁移文件注释明确「换维度必须新建列/表并重建索引」 | `db/vector-migration/V1__vector_store.sql` |
| 凭证存储 | **已加密**（2026-10-18 起）：`user_llm_config.api_key` 是 `VARCHAR(500)`，存 AES-GCM 密文（`v1:` + base64(IV‖密文‖tag)）；在此之前它是**明文列**，接口只做掩码回显 | `CredentialCipher`、`UserLlmConfigService`、`application.yml` 的 `nexus.security.crypto.kek` |

### P0（已完成，2026-10-18）修复知识库列表展示的编造值

- **目标**：列表不再把编造的模型名当真实值展示（原来会出现已废弃的 `bge-m3`）。
- **原缺陷**：`KnowledgeBaseListPage` 用 `metaOf(i)` 按数组下标编造 `model` / `collection`，
  `EMBEDDINGS` 常量里含 `bge-m3`；而 `GET /api/knowledge-bases` 本来就返回 `embeddingModel`
  （后端 `KnowledgeBaseResponse` 字段，前端 `api.ts` 的 `KnowledgeBase.embeddingModel?` 早已声明）。
- **实施**：
  1. `frontend/src/data.ts`：`KbItem` 增加可选 `embeddingModel`。
  2. `frontend/src/lib/backendData.ts`：`toKbItem` 透传 `kb.embeddingModel ?? undefined`。
  3. `frontend/src/pages/KnowledgeBaseListPage.tsx`：删除 `EMBEDDINGS` / `COLLECTIONS` / `metaOf`，
     该列改为渲染 `kb.embeddingModel ?? '—'`。
  4. `frontend/scripts/verify-kb.mjs`：断言由「含 Embedding 与 Collection」改为
     「含 Embedding 模型列、且不存在 Collection 列」。
- **已定决策（原「未决决策」）**：`collection` 列**直接删除**——后端没有「集合 / 目录」概念，
  也没有对应字段。等后端真的引入该概念后再补；在此之前不展示任何占位或编造值。
- **附带改动**：`frontend/src/styles/kb-manage.css` 的窄屏断点原来隐藏第 2、3 列
  （Embedding + Collection）；删列后第 3 列变成「文档数」，故改为只隐藏第 2 列，
  避免窄屏把「文档数」一起藏掉。
- **验收证据**：`npm run build`（`tsc --noEmit && vite build`）exit 0；源码中已无
  `metaOf` / `EMBEDDINGS` / `COLLECTIONS`。
- **未覆盖**：mock 模式下演示数据没有 `embeddingModel` 字段，该列显示 `—`；
  「逐条与接口返回值一致」需要在有真实后端的环境里核对（本机 Docker 容器处于 Exited）。

### P1（计划中）嵌入 BYOK —— 路线 A：白名单 + 用户级配置

- **目标**：用户可以配置自己的嵌入 endpoint / key / model，但**首期只允许 1024 维**
  （与现有 `vector(1024)` 和冻结维度兼容），维度自由度留到 P2 评估。
- **依赖**：P0（能展示真实模型名才有意义）✅、P1 凭证安全（存 Key 前先定加密方案）✅ 2026-10-18 完成、
  现有的冻结比对逻辑（`KnowledgeEmbeddingResolver`）。
- **步骤**：
  1. 新增 `user_embedding_config`：`user_id` 唯一、`provider` / `model_name` / `dimensions` /
     `base_url` / `api_key` / `enabled`，形状对齐 `user_llm_config`。
  2. `EmbeddingClientFactory` 增加按用户解析的重载（如 `get(role, userId)`）；
     无用户配置或未启用时**回落全局角色**。
  3. `KnowledgeEmbeddingResolver.resolve(kb)` 扩成 `resolve(userId, kb)`：
     **保留「查询模型必须等于冻结模型」这条不变式，但比对对象从全局角色改为
     「该用户生效的嵌入配置（未配置则回落全局）」**，再在与冻结值一致的模型里优先用用户凭证。
  4. `(provider, model, dim)` 三元组白名单校验，首期只收经 LIVE 验证过的 1024 维模型
     （当前即 `bailian:text-embedding-v4`）。
- **验收标准**：
  - 用户 A 配置自己的 Key 后，检索只经 A 的凭证；用户 B 行为不变（隔离断言）。
  - 未配置 / 未启用的用户与今天完全一致（回落全局，零回归）。
  - 用户配置的模型 ≠ 知识库冻结模型时**仍然 4006 拒绝**——「用自己的 Key」不是绕过冻结的理由。
  - 用户把配置换成另一个模型后，旧知识库检索报错而不是静默错乱。
  - 现有默认套件（nexus-app 564 例；四模块合计 833）、LIVE 套件、浏览器 E2E 均不回退。
- **已定决策**（2026-10-18）：Key **不允许「仅本次会话」不落库**——只保留"加密落库"这一条路径，
  避免第二套凭证生命周期（会话态的内存失效与并发语义是另一块复杂度）。
- **仍待定**：用户配置与全局默认的优先级、管理员能否强制覆盖；白名单放配置项还是代码枚举。

### P1（已完成，2026-10-18）凭证安全：加密存储、密钥管理、脱敏、轮换

- **目标**：BYOK 落地前把 Key 的存储与生命周期做对。
- **原缺陷**：`user_llm_config.api_key` 明文入库，接口只做掩码回显——
  **掩码不等于加密**，拿到库文件/备份的人仍然直接读到可用 Key。
- **已定决策**（原「未决决策」）：**不引入外部 KMS**，用进程内 AES-GCM + KEK 环境变量
  （单机部署，引入 Vault/云 KMS 的运维成本大于收益）；**轮换不留旧密文**
  （同一用户只有一把 Key，换 Key 直接覆盖；留旧密文只会扩大泄露面）。
- **实施**：
  1. `nexus-app/.../security/CredentialCipher.java`（新增）：AES-GCM 加解密。
     密文格式 `v1:` + base64(12 字节随机 IV ‖ 密文 ‖ 16 字节 tag)；版本前缀供以后换算法/换 KEK 分流。
     明文上限 320（密文 467 < 列宽 500），越界在参数校验层就拒绝，而不是等 MySQL 截断。
  2. KEK 走 `nexus.security.crypto.kek`（环境变量 `NEXUS_SECURITY_CRYPTO_KEK`，base64 的 16/24/32 字节）。
     **未配置 ⇒ 写入 fail-closed（9999），不退化成存明文**；**配错 ⇒ 启动即失败**（不写解不开的密文）。
  3. 写入侧 `UserLlmConfigService.upsert` 加密；读取侧 `DbUserChatCredentialsProvider.find`
     只在构造客户端的这一刻解密，明文不落库、不回显、不进日志。
  4. 回显掩码打在**明文**上（`get()` 先解密再 `mask`）——打在密文上等于把密文片段当 Key 露出去。
  5. 历史明文行（加密前写下的）在读取/保存配置时就地升级为密文，幂等；
     **不**放在模型调用热路径上写库。
  6. 轮换：`ChatClientFactory` 的用户级缓存 key 本来就含凭据指纹
     （`ChatClientFactory.java:58-61`），换 Key 后指纹变、自动重建，无需跨组件失效通知；
     因此本轮**没有**新增缓存清理逻辑。
- **验收证据**：
  - `CredentialCipherTest`（纯单测）：可逆、随机 IV、换 KEK/篡改被拒、未配置 KEK 时加密报错、
    历史明文按原样读、超长拒绝、KEK 配错构造即失败。
  - `UserLlmConfigCryptoTest`（`@SpringBootTest` + H2）：库内为密文且 `≠ 明文`、能解回原文、
    接口响应体不含明文、留空 Key 保留旧密文、历史明文行升级且幂等、超长 → 400/1001。
- **本轮未覆盖（如实记录）**：
  - **KEK 自身仍是环境变量**：没有 KMS 托管、没有自动轮换脚本；换 KEK 会让既有密文全部解不开，
    需要用户重新保存一次「模型 Key」（代码里解密失败时给的就是这句提示）。
  - 「日志与异常信息过滤」只做到**本路径不打印任何 Key 或其片段**；
    全局的凭据脱敏仍依赖既有的 `AgentTraceGuards`，没有做成全局 AOP。
  - **停用/删除账号时清理凭据**未做（账号删除接口本身还不存在）。
  - 加密前写下的历史明文行在用户下次读写配置前，库里仍是明文（没有做启动期全表重写——
    应用启动不该改业务数据）。
- **对既有契约的影响**：`PUT /api/users/me/llm-config` 的 `apiKey` 上限从 500 收紧到 **320**
  （超限 400/1001）；`user_llm_config.api_key` 列宽不变（`VARCHAR(500)` 够用，故无新迁移）。

### P2（待评估）多维度路线 B：按维度分表 / 路由 / 迁移成本

- **目标**：允许 768 / 1536 等维度的模型。**先评估，评估通过再决定是否实施**。
- **依赖**：P1 路线 A 的落地经验、`db/vector-migration` 的独立迁移机制、HNSW 索引的内存成本。
- **评估项**：按维度分表（`kb_vector_<dim>`）还是同表加列；每种维度一套 HNSW 的成本；
  存量知识库是否需要一次性重建；查询期按 KB 冻结维度选表的路由；备份与监控的影响。
- **验收标准（仅评估阶段）**：产出一份**带数字**的成本/收益结论（索引内存、重建耗时、查询延迟），
  明确「值不值得做」；不是直接开工。
- **未决决策**：若结论是「不值得」，是否退化为「平台只支持 1024 维，用户只能换 Key / endpoint，不能换维度」。

### 回归验收（跨阶段，必须全绿）

| 场景 | 断言 |
| --- | --- |
| 用户隔离 | A 的凭证不会被 B 使用；跨用户知识库仍返回 404 |
| 模型不匹配 | 用户配置模型 ≠ 知识库冻结模型 → `4006`，且不发起检索 |
| 知识库冻结 | 改名 / 改描述不改 `embedding_*`；切全局配置不改变已有知识库 |
| 重建迁移 | 换模型必须显式重建索引（`/index/recreate`），期间旧索引仍可查 |
| 异常回滚 | 凭证解密失败或上游 401 时，知识库与向量不产生半写状态 |
| 既有能力 | RAG、混合检索、RRF、重排、多库扇出在「回落全局」路径上行为不变 |

### 明确约束（写死，不作为可讨论项）

1. 用户更换模型后，**不得**用新模型的向量去查旧索引；旧知识库必须显式重建，
   期间检索直接报错，不做静默降级。
2. 模型迁移只能显式触发（重建索引 / 新建知识库），不得隐式自动迁移。
3. **接口掩码回显 ≠ 数据库加密**：这两件事必须分别实现、分别验收。
   P1 凭证安全（2026-10-18）已让 `user_llm_config.api_key` 两件都做到；将来任何新增的凭据列照此办理，
   不得因为"接口已经掩码了"就认为落库形态也安全。
4. 路线 A 阶段遇到非 1024 维模型直接拒绝，不做静默截断或补零。

### 状态一览

| 阶段 | 状态 |
| --- | --- |
| 聊天侧 BYOK（`user_llm_config`） | **已实现** |
| 知识库嵌入模型冻结 + 检索期一致性校验 | **已实现** |
| P0 知识库列表展示修复 | **已完成**（2026-10-18） |
| P1 嵌入 BYOK（路线 A，1024 维白名单） | **计划中** |
| P1 凭证安全（加密 / 轮换 / 脱敏） | **已实现**（2026-10-18）；缺口：KEK 仍是环境变量、无 KMS、账号停用/删除不清凭据 |
| P2 多维度（路线 B） | **待评估** |
