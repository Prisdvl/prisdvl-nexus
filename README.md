# Prisma

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
prisma/                 (root pom, packaging=pom)
├── prisma-common/                   Result / ErrorCode / 异常 / 通用工具（无 Web 依赖）
├── prisma-knowledge/                知识域核心：解析 / 切分 / 嵌入 / 检索 / 引用 / 图谱
├── prisma-app/                      Spring Boot 应用（模块化单体）：auth chat agent tool workflow evaluation observability
├── prisma-mcp-server/               (Phase 7) 独立 MCP Server 进程，复用 prisma-knowledge
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
| docs/rag.md / agent.md / mcp.md / workflow.md / evaluation.md / observability.md / deployment.md / interview.md | 各阶段深化文档 | ⏳ 随对应 Phase 补齐 |

## 6. 当前进度

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

## 7. 本地环境要求

| 依赖 | 要求 | 当前机器实测 |
| --- | --- | --- |
| JDK | 21（LTS） | ✅ **便携版已就绪**：`D:\develop\jdk-21`（Temurin 21.0.12.1，未改系统 `JAVA_HOME`）。构建前请 `$env:JAVA_HOME='D:\develop\jdk-21'` |
| Maven | 3.9+ | ✅ 3.9.9（本地仓库 `D:\develop\Maven\repository`） |
| Docker + Compose | 运行中 | ⚠️ 客户端 29.5.2 / Compose v5.1.3 已装，但 **WSL2 后端不可用**：`HCS_E_HYPERV_NOT_INSTALLED`（需管理员启用虚拟机平台 + 安装 WSL2 内核并重启，见 [docs/architecture.md](docs/architecture.md) 第 19 节） |
| Node.js | 20.19+ 或 22.12+（Vite 8 要求） | ✅ v22.22.0 |
| 内存 | 建议 ≥ 16 GB（ES + MySQL + PG 同时跑） | ⚠️ 15.6 GB，ES 需按小堆配置（`-Xms512m`、单节点、关闭副本） |

## 8. 开发约定（重要）

1. **一次只做一个 Step**：需求 → 设计 → 实现 → 运行 → 测试 → 总结，完成后再进入下一步。
2. **分层不可跨越**：`Controller → Service → Domain/Mapper`；Controller 不碰数据库、不直接调 LLM。
3. **可运行优先**：不为"看起来高级"引入无实际价值的组件。
4. **每个核心模块必须有测试**：单元测试（切分、解析、过滤表达式）+ 集成测试（Testcontainers）。
5. **答案必须区分来源**：我的知识库内容 / 检索到的外部资料 / 模型自身知识 / 模型推测。
6. 每个 Phase 结束时更新 `docs/` 与面试问答，保证简历可直接讲。
