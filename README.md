# Prisdvl-Nexus

> Personal Knowledge Base & Agent Platform
> 个人知识库 + AI Agent 智能工作平台

以**个人知识库为核心数据源**、基于 **Spring AI** 构建的 AI 应用：通过文档解析 → 结构化 Chunk → Embedding → Hybrid Retrieval → RAG，让大模型真正"读懂"你自己的 Obsidian / Markdown 笔记，并给出可点击的知识来源；再通过 Tool Calling → Agent → MCP → Workflow，把 AI 从"回答问题"升级为"基于个人知识执行任务"；最后用 Evaluation 与 Observability 把 AI 应用纳入工程化管理。

## 1. 这个项目解决什么问题

| 问题 | 本项目的能力 |
| --- | --- |
| 通用 LLM 不知道我笔记本里写了什么 | RAG：导入 Obsidian Vault，结构化切分 + 向量检索 + BM25 混合检索 + 重排 |
| 模型经常"编"我的笔记里没有的内容 | 检索前置 + 强制引用 + 无依据时显式声明"知识库中未找到依据" |
| 笔记数量大，重复导入很慢、很贵 | SHA-256 增量同步：未变更跳过、变更重建、删除清理 |
| 想让 AI 帮我做分析/计划，而不只是聊天 | Tool Calling → Agent（权限 + 二次确认 + 审计）→ Workflow |
| AI 效果好坏无法量化 | Evaluation：检索命中率、引用准确率、答案忠实度等真实指标 |
| Token 花在哪、慢在哪、错在哪不知道 | Observability：Token / 延迟 / 首 Token / 检索数 / 工具调用 / 成本 |

## 2. 能力全景（目标态）

```
知识导入         知识检索            AI 交互             工程化
─────────       ─────────          ────────           ──────────
MD / TXT        Vector Search      Chat (SSE)         Evaluation
PDF / DOCX      BM25               RAG + Citation     Observability
ZIP / Vault     Hybrid + Rerank    Tool Calling       Prompt 版本管理
增量同步         Metadata Filter    Agent + MCP        权限 + 审计
                                   Workflow
```

## 3. 技术栈

| 层 | 选型 |
| --- | --- |
| 语言 / 构建 | Java 21、Maven 3.9（多模块 reactor） |
| 框架 | Spring Boot 4.1.1、Spring AI 2.0.1（BOM 管理版本） |
| AI 能力 | ChatClient / ChatMemory / Tool Calling / MCP / VectorStore / Advisors / Observation |
| 业务库 | MySQL 8.4.11 LTS + MyBatis-Plus 3.5.17 + Flyway 12.4.0 |
| 向量库 | PostgreSQL 18 + pgvector 0.8.6（HNSW，`vector(1024)`） |
| 关键词检索 | Elasticsearch 9.4.5 + analysis-ik 9.4.5（中文分词，BM25） |
| 缓存 / 队列 | Redis 8.10.2、RabbitMQ 4.3.6（异步导入 / 评测 / 报告） |
| 前端 | Vue 3.5.43 + TypeScript 7.0.2 + Vite 8.3.1 + Element Plus 2.14.6 + Pinia 4.0.3 |
| 部署 | Docker / docker compose、Nginx |

> 版本以**官方文档与 Maven Central 实测为准**，不照抄旧教程。选型与版本决策记录在 [docs/decisions.md](docs/decisions.md)。

## 4. 仓库结构（目标态）

```
prisdvl-nexus/                 (root pom, packaging=pom)
├── nexus-common/                   Result / ErrorCode / 异常 / 通用工具（无 Web 依赖）
├── nexus-knowledge/                知识域核心：解析 / 切分 / 嵌入 / 检索 / 引用 / 图谱
├── nexus-app/                      Spring Boot 应用（模块化单体）：auth chat agent tool workflow evaluation observability
├── nexus-mcp-server/               (Phase 7) 独立 MCP Server 进程，复用 nexus-knowledge
├── frontend/                     Vue 3 + TS（独立 npm 工程，不做 Maven 模块）
├── docs/                         架构 / 数据库 / API / RAG / Agent / MCP / 评测 / 观测 / 部署 / 面试
├── deploy/                       docker-compose、Dockerfile、nginx 配置、初始化脚本
└── README.md
```

## 5. 文档索引

| 文档 | 内容 | 状态 |
| --- | --- | --- |
| [docs/architecture.md](docs/architecture.md) | 系统架构、模块划分、数据流（含 Mermaid） | ✅ STEP 1 |
| [docs/database.md](docs/database.md) | ER 图、核心表设计、索引、Redis/ES 结构 | ✅ STEP 1 |
| [docs/api.md](docs/api.md) | 统一返回、错误码、REST 与 SSE 协议 | ✅ STEP 1 |
| [docs/roadmap.md](docs/roadmap.md) | Phase 0–11 任务拆分 + 学习知识点 | ✅ STEP 1 |
| [docs/decisions.md](docs/decisions.md) | 架构决策记录（ADR-001 ~ ADR-017）+ 官方文档核对结果（附录 A ~ D，含实测验证记录） | ✅ 持续更新 |
| [docs/productization-gap-analysis.md](docs/productization-gap-analysis.md) | 产品化差距审计：已具备能力的清单 + 16 模块 Gap 表 + P0/P1/P2/P3 分级 + 实施顺序 | ✅ 2026-10-01（每阶段结束后更新状态） |
| docs/rag.md / agent.md / mcp.md / workflow.md / evaluation.md / observability.md / deployment.md / interview.md | 各阶段深化文档 | ⏳ 随对应 Phase 补齐 |

## 6. 当前进度

> ⚠️ 下面这张表停在早期，**不是**当前进度的权威来源。
> 逐项状态以 [docs/roadmap.md](docs/roadmap.md)（到 Phase 3 逐条标注）与
> [docs/decisions.md](docs/decisions.md) 附录 B 的实测记录为准；平台化升级这条线在
> [docs/platform-upgrade-phase1-analysis.md](docs/platform-upgrade-phase1-analysis.md)。

| Phase | 内容 | 状态 |
| --- | --- | --- |
| STEP 1 | 技术方案设计（不写业务代码） | ✅ 完成，待确认 |
| Phase 0 | 项目初始化（Git / Maven / Boot / Spring AI / Docker / MySQL / Redis / PG） | 🔄 进行中：0.1 ✅ 0.2 ✅ 0.3 ✅（MyBatis-Plus 已实测通过）0.5 ✅；0.4 / 0.6 待 Docker 就绪 |
| Phase 1 | AI Chat（ChatClient / SSE / Conversation / Message / Memory） | 🔄 进行中：1.1 ✅（注册/登录/JWT/RBAC，28 用例全绿）；1.2 起为模型接入 |
| Phase 2 | Knowledge Base（MD / TXT / PDF / DOCX / ZIP / Obsidian Vault） | ⏳ |
| Phase 3 | RAG（Chunk / Embedding / Vector / BM25 / Hybrid / Rerank / Citation） | ⏳ |
| Phase 4 | 个人 AI 知识助手（总结 / 关联 / 学习路线 / 知识缺口） | ⏳ |
| Phase 5 | Tool Calling | ⏳ |
| Phase 6 | Agent（Planner / Executor / 权限 / 审计 / 人工确认） | ⏳ |
| Phase 7 | MCP（Knowledge / Project / Study） | ⏳ |
| Phase 8 | Workflow（学习计划 / 知识分析 / 报告） | ⏳ |
| Phase 9 | Evaluation | ⏳ |
| Phase 10 | Observability | ⏳ |
| Phase 11 | 工程化（Docker / Nginx / 安全 / 限流 / CI） | ⏳ |

平台化升级（另一条轨道，与上面的 Phase 并行）：

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| Phase 1 | 架构分析（**未改代码**） | ✅ 完成（[docs/platform-upgrade-phase1-analysis.md](docs/platform-upgrade-phase1-analysis.md)） |
| Phase 2 | 领域模型：`workspace`（个人工作区）+ 知识库平台字段（icon/status/检索参数） | ✅ 完成（迁移 V9/V10；见 decisions.md B.30） |
| Phase 3.1 | Retriever 端口抽象：检索拆成策略实现族（Vector/Keyword/Hybrid/Reranked）+ 新旧行为一致性测试 | ✅ 完成（对外行为不变；见 decisions.md B.31） |
| Phase 3.2 | 检索参数真正可配置：知识库的 `retrieval_top_k` / `similarity_threshold` 生效 + 全局默认值收敛到 `nexus.retrieval.*` | ✅ 完成（阈值统一为 0.25；见 decisions.md B.32） |
| Phase 3.3 | 元数据过滤：`tags` / `folder` / `documentId` 下推到 ES `filter` 与 pgvector `WHERE`（向量路先解析成文档 id） | ✅ 完成（当时 ES/pgvector 真机 `UNAVAILABLE`；2026-09-30 真机栈已补验 `kb_id` 一路，见 decisions.md B.37 / baseline §9.6） |
| Phase 3.4 | RAG 模式的会话流式：`retrieval` 事件先于第一个字、会话绑定知识库（`kb_ids`）、多轮历史走 ChatMemory、前端"检索到 N 个片段" | ✅ 完成（当时 RAG 腿因本地无 ES 跳过；2026-09-30 真机栈首次真跑、退出码 0，见 decisions.md B.37） |
| Phase 4.1 | Agent 配置化（后端）：`agent` 表 + Definition/Runtime 分离 + `/api/agents` CRUD/复制/试跑 + 会话选 Agent + Agent 绑定知识库决定检索范围 | ✅ 后端完成（迁移 V12；见 decisions.md B.35） |
| Phase 4.2 | Agent 配置化（前端）：Agent 管理页（生命周期全流程）+ 对话页 Agent 选择器 + `resetAgent`（回到内置默认）+ E2E 脚本 | ✅ 完成（真机栈 E2E 全过；试跑因本机无模型 `SKIPPED`；见 decisions.md B.36） |
| 真机栈补验 | Docker 起来后用 `local`（真 MySQL/ES/pgvector/Redis/RabbitMQ）重跑：13 项 live + 3 项流式 live 全过、三条 E2E 脚本**无跳过退出码 0**；同时逼出两个后端死路与一个界面缺陷 | ✅ 完成（见 decisions.md B.37 / baseline §9.6） |
| Phase 3+ | RAG/Agent/Workflow/Application/Model 的抽象上移与用户可配置化 | ⏳ 见分析文档第 12 节 |

## 7. 本地环境要求

| 依赖 | 要求 | 当前机器实测 |
| --- | --- | --- |
| JDK | 21（LTS） | ✅ **便携版已就绪**：`D:\develop\jdk-21`（Temurin 21.0.12.1，未改系统 `JAVA_HOME`）。构建前请 `$env:JAVA_HOME='D:\develop\jdk-21'` |
| Maven | 3.9+ | ✅ 3.9.9（本地仓库 `D:\develop\Maven\repository`） |
| Docker + Compose | 运行中 | ⚠️ 客户端 29.5.2 / Compose v5.1.3 已装，但 **WSL2 后端不可用**：`HCS_E_HYPERV_NOT_INSTALLED`（需管理员启用虚拟机平台 + 安装 WSL2 内核并重启，见 [docs/architecture.md](docs/architecture.md) 第 19 节） |
| Node.js | 20.19+ 或 22.12+（Vite 8 要求） | ✅ v22.22.0 |
| 内存 | 建议 ≥ 16 GB（ES + MySQL + PG 同时跑） | ⚠️ 15.6 GB，ES 需按小堆配置（`-Xms512m`、单节点、关闭副本） |

## 8. 快速运行（不依赖 Docker）

`standalone` profile 用 H2 内存库跑通全部功能，**不需要 MySQL / Redis / Docker**：

```powershell
# 0) 准备（本机便携 JDK 21）
$env:JAVA_HOME = 'D:\develop\jdk-21'

# 1) 模型 Key（没有 Key 也能启动，只是问答会失败——实测是 4006 而不是 4001：
#    空 api-key 的 opencode provider 不进注册表，chat.default 因此解析不到，
#    错误信息里会列出"已注册：[ollama:qwen3:7b]"）
$env:AI_CHAT_API_KEY = (Get-Content '.secrets\ai-key.txt' -Raw).Trim()

# 2) 打包并启动（首次约 1-2 分钟）
mvn -B clean package -DskipTests
java -jar nexus-app\target\nexus-app-0.0.1-SNAPSHOT.jar --spring.profiles.active=standalone

# 3) 另开一个终端跑冒烟测试（注册→登录→建会话→问答→流式+停止→查历史）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke.ps1

# 4) 界面验证要用的演示数据（demo 用户 + 一个装着笔记的知识库 + web/.demo-token）
#    H2 内存库重启即清，所以每轮重启后都要跑一次
node scripts\seed-demo.mjs
```

启动后可用入口：

| 用途 | 地址 |
| --- | --- |
| 健康检查 | http://localhost:8080/actuator/health |
| H2 控制台（直接看表数据） | http://localhost:8080/h2-console （JDBC URL 填 `jdbc:h2:mem:nexus_standalone`，用户名 `sa`，密码空） |
| 注册 / 登录 | `POST /api/auth/register`、`POST /api/auth/login` |
| 会话 | `POST/GET/PUT/DELETE /api/chat/conversations`、`GET /api/chat/conversations/{id}` |
| Agent 管理（Phase 4.1/4.2） | 界面 `http://localhost:5173/#/agents`；接口 `GET/POST /api/agents`、`GET/PUT/DELETE /api/agents/{agentId}`、`POST /api/agents/{agentId}/duplicate`、`POST /api/agents/{agentId}/test-chat`（试跑，不要求已发布） |
| Agent 问答（用内置默认 Agent） | `POST /api/agent/ask` |
| 问答（非流式） | `POST /api/chat/conversations/{id}/messages` |
| 问答（SSE 流式） | `POST /api/chat/stream` |
| 停止生成 | `POST /api/chat/stream/{requestId}/stop` |
| 历史消息 | `GET /api/chat/messages/{conversationId}` |

> 数据存在内存里，**重启即清空**。要持久化就等 Docker 就绪后切到 `local` profile（MySQL）。

## 9. 完整运行（Docker + MySQL，推荐）

```powershell
# 1) 起基础设施（MySQL / PostgreSQL+pgvector / Redis / ES+IK / RabbitMQ）
cd deploy
docker compose up -d
docker compose ps            # 等 5 个服务都变成 (healthy)

# 2) 打包并用 local profile 启动（Flyway 会自动建表）
cd ..
$env:JAVA_HOME = 'D:\develop\jdk-21'
$env:AI_CHAT_API_KEY = (Get-Content '.secrets\ai-key.txt' -Raw).Trim()
mvn -B clean package -DskipTests
java -jar nexus-app\target\nexus-app-0.0.1-SNAPSHOT.jar --spring.profiles.active=local

# 3) 冒烟测试（同 standalone，只是数据落在 MySQL）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke.ps1
```

| 服务 | 地址 | 说明 |
| --- | --- | --- |
| MySQL | `localhost:3307` | 库 `nexus`，账号 `nexus` / `nexus_pwd`（**端口 3307 是因为本机原生 MySQL 占了 3306**） |
| PostgreSQL | `localhost:5433` | 库 `nexus_vector`，pgvector + hstore + uuid-ossp 已启用 |
| Elasticsearch | http://localhost:9200 | 9.4.5 + analysis-ik 中文分词 |
| RabbitMQ 控制台 | http://localhost:15672 | `nexus` / `nexus_pwd` |
| Redis | `localhost:6379` | |

> 数据库结构与迁移由 **Flyway** 管理（`nexus-app/src/main/resources/db/migration/`）：
> V1 账号权限、V2 会话消息、V3 Spring AI 记忆表、V4 知识库与文档、V5 切片与导入任务、
> V6 原始文件路径、V7 问答留档、V8 文档关联、V9 工作区（平台化）、V10 知识库平台字段、
> V12 Agent 定义与绑定（**没有 V11**：编号为 Phase 4.1 预留后未用，Flyway 不要求连续）。
> **只增不改**：新增字段一律 `ADD COLUMN` 带默认值，不改名、不删除。
> 三个 profile 的分工：`local`（真实 MySQL，日常开发）、`standalone`（H2 内存，无 Docker 时应急）、`test`（H2，跑测试）。
> **新增 profile 配置或迁移脚本后必须重新 `package`**，否则 jar 里还是旧的。

## 10. 前端控制台（Vue 3 + Vite + Element Plus）

```powershell
# 先按上一节把后端跑起来，然后：
cd web
npm install
npm run dev            # http://localhost:5173（/api 由 Vite 代理到后端 8080，因此不涉及 CORS）
```

**已有功能**：注册/登录（JWT，刷新保持登录）、知识库列表与新建、上传 Obsidian Vault
（显示队列/处理中/完成进度）、向量化 / 建索引 / 重建索引、文档与切片浏览
（切片带标题路径、Token 数，以及**向量与索引状态**——排查"为什么搜不到"的第一手信息）、
关键词检索（每条命中显示来源标签与相关性得分），**Agent 对话**
（模型自己决定检索什么：检索过程实时可见、工具痕迹随消息落库、随时可停；RAG 会话的
**"检索到 N 个片段"卡片只在本次会话内保留**——命中片段还没随消息落库，刷新后会消失，
这是已知缺口而非渲染问题，见 decisions.md B.37），
以及 **Agent 管理**（`/agents`：新建 → 试跑 → 发布 → 在对话页选用 → 停用 → 删除；
配提示词与可检索的知识库；草稿不能选用，选择器里会说明原因）。

**界面验证（不下载浏览器，复用系统 Edge/Chrome）**：

```powershell
cd web
node scripts/screenshot.mjs        # 生成 docs/screenshots/{login,kb-list,kb-detail,kb-search}.png
node scripts/verify-login.mjs      # 真实走一遍登录表单，确认跳转与刷新后仍保持登录
node scripts/verify-frontend.mjs   # 知识库列表 → 正文（Markdown/大纲/关联面板）→ 切片
node scripts/verify-agent-chat.mjs # 对话页两条腿：Agent（工具卡片/真实摘要/刷新后痕迹仍在）+ RAG（"检索到 N 个片段"且先于回答）
node scripts/verify-stream.mjs     # 知识库问答流式：逐字增长 + 引用 + 生成期间的"检索到 N 个片段"
node scripts/verify-retrieval-ui.mjs # 检索卡片渲染契约：桩掉 /api/chat/stream，验"检索到 N 个片段"排在回答之前
node scripts/verify-agents.mjs     # Agent 管理页：新建→配置→发布→对话页可选→停用→不可选→删除（11 条腿）
node scripts/verify-agent-no-kb.mjs # 零知识库的新用户提问：提示 → 检索如实报空 → 回答注明不是来自笔记 → 痕迹刷新后仍在
```

> `verify-*.mjs` 都要求后端已按上一节跑起来，并且**已经种好演示数据**
> （`node scripts/seed-demo.mjs`，`standalone` 重启后要重跑）。
> 脚本会把截图写进 `docs/screenshots/`。
> 例外是 `verify-agent-no-kb.mjs`：它**故意不用演示账号**——它要的就是"一个库都没有的新账号"，
> 所以每次运行自己注册一个 `probe-nokb-*`，也不依赖 `seed-demo`。
> 没有删号接口，因此**每跑一次会留下一个这样的账号**（代价写在这里，免得以后当成残留 bug 去查）。

> **退出码**：`0` 全部通过 / `1` 真实失败 / `2` 有跳过。跳过只在**环境能力缺失**时发生
> ——`standalone` 没有 Elasticsearch，RAG 在检索这一步就返回 4005，
> 于是"检索到 N 个片段"相关的断言无法执行（`verify-agent-chat.mjs` 只跳过 RAG 腿，
> `verify-stream.mjs` 整体跳过）；`verify-agents.mjs` 只跳过试跑那条腿
> （**没配 `AI_CHAT_API_KEY` 时**后端报 4006 `模型 … 未注册。已注册：[…]`，其余 10 条腿照跑；
> 配好 key 后这条腿会真跑——本机实测 11 条腿全部执行、退出码 0）；
> `verify-agent-no-kb.mjs` 只跳过**提问**那条腿，"提问前那一屏提示"照验
> （那句话与模型无关，是前端读知识库数量算出来的）。
> 脚本会打印 `⚠ SKIPPED` 与原因，**跳过不等于通过**。
>
> **full 栈已经跑过**（Docker 起 MySQL/ES/pgvector/Redis/RabbitMQ，后端
> `--spring.profiles.active=local`，2026-09-30）：上面几个脚本的**跳过腿全部真跑、退出码 0**，
> 另有 13 项 live 真机测试 + 3 项流式 live 测试通过。结果与口径见
> `docs/platform-phase3-baseline.md` §9.6，过程与踩到的坑见 `docs/decisions.md` B.37。
> **注意 full 栈仍不等于"部署形态也验了"**：Docker 在这里只是基础设施，
> 应用本身还是本机 `java -jar` + Vite dev server；容器化部署（Dockerfile/nginx）**未验**。

> **`verify-retrieval-ui.mjs` 是桩验证，不是 E2E**：它把 `/api/chat/stream` 换成
> 手写的 SSE 帧（形状取自 `docs/api.md` §5.2），用来单独验"前端拿到这套帧渲染成什么样"
> ——这是本地无 ES 时唯一能验到那层 UI 的办法。它**不证明**后端真的发这套帧
> （那是 `RagStreamRetrievalEventTest` 的事），也**不证明**真机检索可用（那是 full 栈的事）。
> 三件事必须分开说，不能合并成一句"验证通过"。会话创建与消息列表仍走真接口。

> 这两个脚本的意义不只是截图：**"服务返回了 HTML"不等于"页面渲染正常"**。
> 本项目就是靠它发现了一个真实问题（前端 `@` 别名只配了 tsconfig、没配 Vite，
> 页面直接白屏报错），以及一个后端问题（新增接口后忘了重新打包，请求落到了静态资源处理器）。
> 真机栈上又抓到第三个（见 B.37 §3）：RAG 的"检索到 N 个片段"卡片在真机上**只存在 41ms**
> ——接口帧完全正确、单元测试全绿，只有真浏览器 + 真服务能看出用户其实从没看见它。

## 11. 开发约定（重要）

1. **一次只做一个 Step**：需求 → 设计 → 实现 → 运行 → 测试 → 总结，完成后再进入下一步。
2. **分层不可跨越**：`Controller → Service → Domain/Mapper`；Controller 不碰数据库、不直接调 LLM。
3. **可运行优先**：不为"看起来高级"引入无实际价值的组件。
4. **每个核心模块必须有测试**：单元测试（切分、解析、过滤表达式）+ 集成测试（Testcontainers）。
5. **答案必须区分来源**：我的知识库内容 / 检索到的外部资料 / 模型自身知识 / 模型推测。
6. 每个 Phase 结束时更新 `docs/` 与面试问答，保证简历可直接讲。
