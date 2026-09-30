# prisdvl-nexus 平台化升级 · Phase 1 分析报告

> 状态：Phase 1（只分析，未修改任何代码）。依据为本会话对当前代码的长期验证事实 + 一次落地扫描；
> 个别"未在本会话逐列核实"的地方已明确标注，下一步以读迁移/实体源码为准。

---

## 1. 当前项目架构

- 聚合工程 `prisdvl-nexus`（POM 单）、四模块依赖链：`nexus-common → nexus-ai → nexus-knowledge → nexus-app`。
- **模块化单体**（modular monolith）：单进程、单 Spring Boot 应用（nexus-app 是唯一启动模块），
  逻辑边界靠 Maven 依赖方向 + 包边界保证，符合§五十一。
- 技术栈（均已验证可运行）：Spring Boot 4.1.1、Spring AI 2.0.1、MyBatis-Plus 3.5.17、Flyway、
  MySQL 8（主库）+ PostgreSQL/pgvector（向量库，独立 DataSource + 独立 Flyway）、
  Elasticsearch 9.4.5 + IK（BM25）、Redis、RabbitMQ（异步导入）、Ollama（bge-m3 嵌入）、
  OpenCode Zen 网关（chat，`deepseek-v4-flash`）。
- 配置：`application.yml`（local）/ `application-standalone.yml`（零依赖降级）/ `application-test.yml`（H2）。

## 2. 当前模块职责

| 模块 | 职责（现状） |
| --- | --- |
| nexus-common | `api/`(Result、PageResult、PageQuery、ErrorCode)、`exception/BusinessException`、`util/`(HashUtils、TimeLimitedCall)、`observe/ObservabilityRegistry`。**无业务代码**（已合规§七） |
| nexus-ai | `config/AiModelProperties`(角色注册表 + findModel)、`model/`(AiModelRegistry、ChatClientFactory、EmbeddingClient(Factory))——**已按"业务不直接依赖厂商 SDK"实现**（OpenAI-compatible/Ollama 走注册表） |
| nexus-knowledge | `parser/`(md/txt/pdf/docx + Registry)、`chunk/`(切分器 + 工厂 + Token 估算)、`imports/`(Vault 导入/同步规划/执行)、`storage/`、`vector/`(VectorStore 端口 + 记录)、`search/`(ChunkIndex 端口 + RRF)、`rag/`(Reranker/LlmReranker/ContextAssembler/RagAnswer*)、`service/`(KB、文档持久化、导入、嵌入、索引、检索、查询、关联、Embedding 解析)、`mapper/` |
| nexus-app | `auth/`(注册/登录/JWT/鉴权)、`chat/`(会话/消息/记忆/SSE 流式)、`knowledge/controller/`(KB/文档/导入/嵌入/检索/RAG/问答/关联/评测)、`agent/`(工具循环 + knowledge_search)、`workflow/`(顺序步骤引擎 + 知识工作流)、`eval/`、`mcp/`(MCP Server)、`observe/`(巡检)、`infra/`(数据源、ES、Rabbit、web/安全) |

规模（落地扫描）：common main 9 / ai 7 / knowledge 94 / app 95（≈205 个主代码文件，46 个测试文件）。

## 3. 当前已有业务

1. 用户：注册/登录、JWT、资源归属校验（`requireOwned`）；2. 知识库 CRUD + Vault/zip 导入
（同步或异步 MQ）+ 单文件解析（md/txt/pdf/docx）；3. 切分（标题/递归）+ 嵌入（bge-m3）+ pgvector；
4. Elasticsearch BM25 索引；5. 检索三模式（BM25/向量/RRF 混合）+ LLM 重排 + 上下文预算组装；
6. RAG 问答（检索→重排→组装→生成→引用归位）+ **SSE 流式** + 问答留档；7. 关联（出链/反向/相关推荐）；
8. Agent 工具循环（knowledge_search，混合→LIKE 降级）+ 前端痕迹可视化；9. 知识工作流（统计→向量化→建索引）
+ 步骤状态；10. 评测集（要点命中 + 引用有效性）+ 一键报告；11. MCP Server（tools/list|call）；
12. 观测计数（降级/失败可巡检）；13. 前端 4 视图（登录/列表/详情/Agent）+ 流式问答界面。

## 4. 当前 RAG 实现

调用链：`RagAnswerService.ask(kbId)` → `ChunkSearchService.search(kbId, q, topK, mode)`（ES BM25 / pgvector /
RRF 融合 `RrfFusion`）→ `LlmReranker`（失败降级）→ `ContextAssembler`（去重/编号/预算）→ 生成（资料为空不调模型）→
引用 `Citations` 解析 `[n]` → 流式 `RagStreamService`（meta→delta→done）。**已具备**：多路检索、重排、预算、防注入分隔
（资料作为 Context 且明确"不执行资料内指令"，§四十六/四十七基本满足）。**缺口**：检索参数写死在调用点、按 KB 配置的
topK/threshold 未持久化；Retriever 抽象未独立（检索逻辑在 ChunkSearchService）；Source 缺 fileName/page。

## 5. 当前 AI 实现

- Chat：`AiModelRegistry`（按 provider 注册 OPENAI_COMPATIBLE/OLLAMA）+ `ChatClientFactory.get(Role)`
  ——业务层无 `new OpenAiChatModel()`（§八已合规）；角色（CHAT_DEFAULT/JUDGE/EMBEDDING_DEFAULT）写在配置文件。
- 嵌入：`EmbeddingClient`（批量 32、首调维度自检、失败统一 4004）。
- Agent：`ToolCallbacks.from(KnowledgeSearchTool)` + ToolCallingManager 自动循环；守卫测试证明网关支持函数调用。
- 记忆：Spring AI ChatMemory（最近 N 条，Phase 1 已实现并落库）。

## 6. 当前用户体系

单表用户（V1 auth），JWT 无状态登录；**用户→资源直接归属**（各表 `user_id` 字段 + `requireOwned` 校验）。无角色
（仅 ADMIN 保留位）、**无 Workspace、无成员概念**——这是平台化最核心的差距（§三十七）。

## 7. 当前数据库结构（以迁移文件为准：V1–V8 + 向量 V1）

- `V1__auth_baseline`（用户/认证相关，精确列未在本会话逐列核对，需下一步读文件确认）
- `V2__chat`（会话/消息）、`V3__spring_ai_chat_memory`（记忆）、
- `V4__knowledge`（knowledge_base/kb_document/kb_document_tag）、`V5__chunk_and_import_task`（kb_chunk/导入任务）、
- `V6__document_stored_path`（文档落盘路径）、`V7__qa_record`（问答留档）、`V8__document_link`（关联边）、
- 向量侧 `V1__vector_store`（kb_vector/embedding_cache，HNSW）。
- 结构映射到目标（§四十九）：user ✓；workspace/workspace_member ✗；knowledge_base ✓（缺检索参数等列）；
document/chunk ✓（缺 metadata.page 等）；agent/agent_kb/agent_tool ✗（Agent 现为固定代码）；tool ✗；
workflow ✗（现有为"知识工作流"无 DAG 定义表）；application ✗；conversation/message ✓（可复用但需挂到 application）；
model_provider/model_config ✗；ai_execution ✗（现为分散的 qa_record/chat_message/观测计数）。

## 8. 当前可直接复用的代码

1. 认证/JWT/资源归属模型（改造方向：加 workspace 维度，不改机制）；
2. 会话/消息/记忆 + **SSE 流式基础设施**（事件名、Flux、取消注册——§四十直接复用）；
3. 导入/解析/切分流水线（ParserRegistry、分块器、Vault 导入、异步 MQ 消费）；
4. VectorStore / ChunkIndex 两个**端口抽象**（可扩展新实现）；
5. RRF 融合、重排（降级语义）、上下文组装（预算/去重/编号）、引用解析——RAG 核心全部复用；
6. Embedding 客户端与容量检查；7. Agent 工具循环 + 守卫测试模式（可平移到用户 Agent）；
8. WorkflowEngine 的状态机语义（顺序/中止/SKIPPED/计时——升级为 DAG 内核的基础概念）；
9. 评测、MCP、观测注册表——平台底座可直接升级为全局能力；
10. `Result/ErrorCode/BusinessException` 统一响应（若全局异常处理在 app 层，需确认覆盖范围）。

## 9. 当前需要重构的代码

1. **KB 归属**：`user_id → workspace_id + owner_id`（走 ALTER/ADD COLUMN，禁止删表§四十九）；
2. **KnowledgeBase 字段**：补 icon/status 启用禁用、embeddingModel、retrievalTopK/similarityThreshold 持久化（§十一）；
3. **Document 资源模型**：从"导入产物"升级为"可管理资源"（upload→parse→chunk→embed 状态机、fileName/fileType、
   storageUrl、失败重试）；补 HTML/CSV/JSON 解析器（§十二）；
4. **Document Pipeline 异步化**：把"向量化/建索引"从手动步骤并入自动管道（复用 MQ/线程池§十三/§五十二）；
5. **Retriever 抽象**：把 ChunkSearchService 的检索拆成 Retrieval 端口实现族 + 可配置参数 + metadata filter（§十五~§十八）；
6. **Source 模型**：回答的 sources 补 fileName/page（metadata 补 page），前端"参考资料 + 第 N 页"（§十九）；
7. **Agent 用户化**：固定 system prompt/工具 → 用户可创建 Agent（prompt/模型/temperature/记忆/绑定 KB+Tool）（§二十）；
8. **Tool 体系**：抽出 ToolRegistry/ToolFactory/ToolExecutor + 用户 Tool 资源 + 权限/超时/限流/执行日志（§二十一~二十三）；
9. **Workflow 图模型**：顺序步骤 → DAG（nodes/edges JSON 定义 + NodeExecutor 族 + ExecutionContext 变量 + 条件/错误/超时/重试）（§二十四~二十九）；
10. **Application/Conversation 重构**：Conversation 挂到 Application、加入 Message 的 TOOL 角色与重新生成/标题生成（§三十~三十二）；
11. **模型配置持久化**：yml 注册表 → 用户 model_provider/model_config（API Key 加密存储，禁止明文回传/日志√§三十四）；
12. **AI Execution 记录**：统一 ai_execution（token/耗时/模型/状态）→ 前端执行面板（§三十八）；
13. 前端：4 页 → Dashboard 骨架（§四十一）。

## 10. 当前存在的架构问题

1. **无 Workspace 层**（多用户平台的根基缺失，所有资源都直挂 user_id）；
2. **Agent/Workflow/Tool 属于 app 层固定实现**，与目标"能力抽象在 nexus-ai、用户资源在 app"不符：
   - 依赖方向硬约束：`knowledge → ai`（knowledge 用 ai 的模型抽象），因此 **ai 不能依赖 knowledge**；
     目标架构（§五十）要求 Agent/Tool/Workflow 抽象在 ai、RAG 在 knowledge——解法：**ai 放抽象端口
     （ToolExecutor/WorkflowEngine/NodeExecutor/Retriever 态），knowledge 提供 RAG/检索实现，
     app 做组合根**（依赖注入接线）。这是本次升级最关键、必须先定调的一条；
3. **检索参数未配置化**（topK/threshold 未按 KB/Agent 分层可覆盖）；
4. **Document pipeline 半自动**（嵌入/索引手动触发，目标全自动）；
5. **执行记录分散**（qa_record/chat_message/观测计数三处，无统一 ai_execution + 节点级执行记录）；
6. **模型配置静态化**（配置文件注册表，无用户级 provider/config，无加密存储）；
7. Knowledge 模块 94 个文件且含 controller 层（`knowledge/controller/` 在 app，正确）；但知识库实体弱于目标模型；
8. 前端无平台骨架（Dashboard/侧栏/资源导航）。

## 11. 目标架构（在模块化单体内落实）

```
com.lky.nexus
 ├─ common   : Result/异常/工具/枚举/Redis 基建/安全公共能力（现状已接近，缺 Redis/安全搬移评估）
 ├─ ai       : chat / embedding / rag(抽象) / agent(抽象) / tool(抽象) / workflow(抽象) /
 │             model(Provider/Config/Adapter) / memory / prompt
 │             → 全部为接口/端口 + 无厂商依赖；具体 RAG 实现（Retriever 族）在 knowledge
 ├─ knowledge: controller(迁到 app 或保留? 见 §10-7 决策) / service / repository / entity / dto / vo /
 │             parser / splitter / embedding / retrieval(Retriever 实现) / vector / source
 └─ app      : user / workspace / auth / agent(用户资源+编排) / workflow(用户资源+接线) /
               application / conversation / message / model(用户资源) / execution / mcp / observe
```

数据模型按 §四十九（user/workspace/workspace_member/knowledge_base/document/document_chunk/
agent/agent_knowledge_base/agent_tool/tool/workflow/workflow_execution/node_execution/application/
conversation/message/model_provider/model_config/ai_execution）。迁移方式：**只增不改**（ADD COLUMN / CREATE）。

## 12. 详细改造路径（按用户给定 Phase 1–9，每步小增量、可编译可验证）

- **Phase 2 领域模型**：新增 Workspace（个人默认可自动创建）+ `kb/agent/tool/workflow/application` 的
  workspace_id/owner_id 列（ALTER）→ 所有权查询全部带 workspace 维度（§三十六）；
  升级 KnowledgeBase 字段（icon/status/检索参数）；Document 新状态模型与解析器扩展（HTML/CSV/JSON）。
- **Phase 3 RAG 升级**：`Retriever` 端口 + Vector/Keyword/Hybrid 实现（从现有 ChunkSearchService 拆出，保持行为不变），
  检索参数（topK/threshold/metadata filter）KB 持久化 + Agent/App 覆盖；Source 补 fileName/page；pipeline 并入自动异步链。
- **Phase 4 Agent/Tool**：`ToolExecutor/ToolRegistry/ToolFactory` 抽象（ai），实现 Calculator/HTTP/RAG 三类；
  用户 Agent 资源（prompt/模型/温度/记忆/绑定）+ 安全（参数校验/超时/限流/执行日志）。
- **Phase 5 Workflow**：`WorkflowDefinition(node[]/edge[] JSON)` + `NodeExecutor` 族（START/END/LLM/RAG/CONDITION/HTTP）
  + `ExecutionContext`（变量/条件/错误/超时/重试/日志）；复用现有 WorkflowEngine 的状态语义。
- **Phase 6 Application/Conversation/Memory**：Application 资源（CHAT/AGENT/RAG/WORKFLOW）绑定 Agent/KB/Workflow；
  Conversation 挂 application；Message 加 TOOL 角色、重新生成、标题生成。
- **Phase 7 Model**：model_provider/model_config 表 + API Key 加密存储；适配 OpenAI-compatible/DeepSeek/Ollama；
  业务层只碰 ChatService/EmbeddingService；统一 `ai_execution` 记录。
- **Phase 8 权限/安全/日志/监控**：workspace 成员角色（OWNER/ADMIN/EDITOR/VIEWER，先个人 workspace）；
  Prompt 注入分级（system>user>context，已被现有实现覆盖需补文档与测试）；SSE 工具/检索/工作流事件。
- **Phase 9 前端**：Dashboard 骨架 + KB/Agent/Workflow/Application 管理页 + 聊天界面升级（沿用 Vue3/Element Plus）。
- 每阶段结束：全量编译 + 目标测试 + 真实环境验证；文档按 §五十七 的 24 项逐步补全。

---

## 结论与建议

现有代码的"地基质量"高于一般项目：模块分层、端口抽象（VectorStore/ChunkIndex）、统一响应与异常、状态机
（WorkflowEngine/导入任务）、SSE 基建、AI 抽象（ChatClientFactory/EmbeddingClient）都是**可复用资产**；
RAG 链条（输入到引用）已基本完整。平台化最大的三个改造点是：**① Workspace 归属层（§十一/§三十六/§三十七）、
② 能力抽象上移 + app 组合根（§十-2 的依赖方向解法）、③ 用户可配置资源（Agent/Tool/Workflow/Application/Model）**。

Phase 1 到此为止，**未改一行代码**。下一步按规格进入 Phase 2（领域模型：Workspace + 归属列 + KB 字段升级），
从"新增 workspace 表 + 个人默认 workspace + 关键表 ADD COLUMN"这一唯一最小增量开始，编译、测试、验证后再继续。