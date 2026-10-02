# Prisdvl-Nexus

> Personal Knowledge Base & Agent Platform
> 个人知识库 + AI Agent 智能工作平台

一个以**个人知识库为核心数据源**、基于 **Spring AI** 构建的 AI 应用：文档解析 → 结构化 Chunk → Embedding → 混合检索 → RAG，让大模型真正"读懂"你自己的 Obsidian / Markdown 笔记，并给出**可点击、可追溯的知识来源**；再通过 Tool Calling → Agent，把 AI 从"回答问题"升级为"基于个人知识执行任务"。

它不是一个聊天机器人 demo，而是一个**跑得起来的完整系统**：8 个容器、4 个 Maven 模块、567 个测试、12 个 E2E 脚本，以及在真实 ES / pgvector / MySQL 上验收过的行为。

---

## 1. 项目简介

### 解决什么问题

| 问题 | 本项目的能力 |
| --- | --- |
| 通用 LLM 不知道我笔记本里写了什么 | **RAG**：导入 Obsidian Vault / MD / TXT / PDF / DOCX / ZIP，结构化切分 + 向量检索 + BM25 混合检索 + 重排 |
| 模型经常"编"我的笔记里没有的内容 | **检索前置 + 强制引用**；RAG 路径零命中时**不再调用模型**，直接返回固定文案 |
| 笔记数量大，重复导入很慢、很贵 | **SHA-256 增量同步**：未变更跳过、变更重建 |
| 想让 AI 帮我查资料，而不只是聊天 | **Tool Calling + Agent**：模型自己决定检索什么，检索过程实时可见、工具痕迹随消息落库 |
| 多个知识库要一起查 | **多库检索**：逐库并行召回 → 跨库归并 → 全局重排；**单库失败不影响其它库**，且失败会被点名 |

### 为什么值得看

三个技术上的硬骨头，本项目都给了可运行的答案，而不是设计稿：

1. **混合检索怎么融**——ES 的 BM25 名次与 pgvector 的余弦相似度不可比，用 **RRF**（倒数排名融合，k=60）取值而不是加权求和；融合后再过一次 LLM 重排。
2. **多库检索怎么做**——虚拟线程扇出 + **按输入顺序装配**（不是完成顺序）+ 逐库失败隔离。其中"按输入顺序"是被两个真实陷阱逼出来的：稳定排序会让同分条目次序随完成时序抖动，而检索事件要推 SSE 帧、**绝不能并发写监听器**。
3. **零命中为什么不调模型**——这是产品语义问题不是性能问题：让模型在"没检索到"的情况下自由发挥，就是幻觉的开始。但这条规则**只对 RAG 路径成立**，Agent 模式下检索是一个工具，零命中会变成一条工具观测。详见 [docs/rag.md](docs/rag.md) §5。

---

## 2. 核心能力

> 状态标记：✅ 已实现且有测试 / ◐ 部分实现（边界见 §14）

| 域 | 能力 | 状态 |
| --- | --- | --- |
| **知识导入** | MD / TXT / PDF（PDFBox）/ DOCX（POI）/ ZIP / Obsidian Vault 解析 | ✅ |
| | SHA-256 增量同步、异步导入（RabbitMQ + DLQ）、失败原因逐条透出、重试 | ✅ |
| | ZIP Slip / Zip Bomb 防护（词法拒绝 + 三层字节上限） | ✅ |
| **切片** | 标题感知切分、Token 计数、切片状态（向量 / 索引）可查 | ✅ |
| **检索** | 向量检索（pgvector，HNSW，`vector(1024)`） | ✅ |
| | 关键词检索（Elasticsearch + IK 中文分词，字段加权 BM25） | ✅ |
| | 混合检索（RRF 融合） | ✅ |
| | LLM 重排（5s 超时降级） | ✅ |
| | 元数据过滤下推（`tags` / `folder` / `documentId` → ES `filter` / pgvector `WHERE`） | ✅ |
| **RAG** | 同步问答 + SSE 流式问答，`[n]` 引用解析，引用落库 | ✅ |
| | 多知识库检索（并行扇出、逐库失败隔离、失败点名） | ✅ |
| **Agent** | 配置化 Agent（Definition / Runtime 分离，落库 `agent` 表） | ✅ |
| | 工具白名单、采样温度、回答策略（`KB_PREFERRED` / `KB_ONLY`）、绑定多知识库 | ✅ |
| | Tool Trace 随消息落库、刷新后仍在 | ✅ |
| | 流式执行 + 随时停止 | ✅ |
| **MCP** | 进程内 JSON-RPC 端点 `POST /mcp`（init / ping / tools-list / tools-call），JWT 鉴权 | ✅ |
| **用户与安全** | 注册 / 登录 / JWT / BCrypt / RBAC | ✅ |
| | 令牌吊销（`token_version`）、登录失败冻结、限流、每日配额 | ✅ |
| | 改密码 / 改资料 / 额度只读接口 | ✅ |
| **可观测** | 自建计数器 + 2 个只读接口（`/api/observability/summary`、`/agents`） | ◐ |
| **部署** | 8 服务 docker compose 整栈、nginx 入口、健康检查、数据卷持久化 | ✅ |
| **前端** | Vue 3 控制台：登录 / 知识库 / 文档与切片 / 检索 / 对话（Agent + RAG）/ Agent 管理 / 账号设置 | ✅ |

---

## 3. 系统架构

```
┌──────────────┐
│   浏览器      │
└──────┬───────┘
       │  http://localhost:8088
┌──────▼───────────────────────────────────────────────┐
│  Nginx (web/nginx.conf, 前端容器内)                    │
│  · SPA fallback   · /api/ 反代   · /mcp 反代           │
│  · SSE 不缓冲 (proxy_buffering off, 3600s)            │
└──────┬───────────────────────────────────────────────┘
       │  仅此一个入口对外；backend 不发布端口
┌──────▼───────────────────────────────────────────────┐
│  nexus-app   (Spring Boot 4.1.1 / JDK 21)            │
│  auth · user · knowledge · chat · agent · tool        │
│  observability · mcp          ← Controller 层          │
└──────┬───────────────────────────────────────────────┘
       │
┌──────▼───────────────────────────────────────────────┐
│  nexus-ai        模型接入 / ChatClient / ChatMemory    │
│                  ToolCallingManager / Agent Runtime   │
│                  Retriever 端口与策略族 / Embedding    │
└──────┬───────────────────────────────────────────────┘
       │
┌──────▼───────────────────────────────────────────────┐
│  nexus-knowledge  解析 / 切片 / 向量库 / ES 索引       │
│                   检索实现 / 引用 / 多库扇出           │
└──────┬───────────────────────────────────────────────┘
       │
┌──────▼───────────────────────────────────────────────┐
│  nexus-common     Result / ErrorCode / 异常 / 工具     │
└──────────────────────────────────────────────────────┘

数据与中间件：
  MySQL 8.4       业务库（用户 / 知识库 / 文档 / 切片 / 会话 / Agent）
  PostgreSQL 18   pgvector 0.8.6 向量库（HNSW, vector(1024)）
  Elasticsearch   + analysis-ik，关键词检索（BM25）
  Redis 8        限流 / 配额 / 令牌版本 / 登录冻结
  RabbitMQ 4     异步导入（含 DLX/DLQ）
  Ollama         本地模型（可选，`--profile ai-local`）
```

模块依赖是**严格线性**的：`nexus-common ← nexus-ai ← nexus-knowledge ← nexus-app`，无环。

**为什么是模块化单体而不是微服务**——完整论证见 [docs/architecture.md](docs/architecture.md) §1.3，一句话版本：本项目的四个模块之间是**调用关系密集、数据强一致、由同一个团队同一个人维护**，拆成微服务只会把一次本地方法调用变成一次网络调用 + 分布式事务 + 一套服务发现，换不到任何东西；而模块化的收益（依赖方向可强制、领域边界清晰、未来真要拆时有现成接缝）已经在**编译期**拿到了。MCP 端点就是这条原则的产物：它按 MCP 协议独立成端点，但**留在 nexus-app 进程内**复用了鉴权与 Spring 上下文，而不是新起一个 `nexus-mcp-server` 进程。

> 详细架构（分层规则、模型可替换、数据流、引用实现）见 [docs/architecture.md](docs/architecture.md)。

---

## 4. 核心链路

### 4.1 Knowledge Pipeline（文档导入）

```
File ──► Parser ──► Document ──► Chunk ──► Embedding ──► Vector Store
```

- **Parser**：按格式分派（MD / TXT 直读，PDF 走 PDFBox，DOCX 走 POI，ZIP / Vault 走 `VaultPackageExtractor` 解包后逐文件分派）。
- **Chunk**：标题感知切分，带标题路径与 Token 数；切片上有独立的**向量状态**与**索引状态**——这是排查"为什么搜不到"的第一手信息。
- **Embedding → Vector Store**：写入 pgvector（`vector(1024)`，HNSW 余弦索引）。
- **异步**：导入任务走 RabbitMQ（`nexus.import.exchange`），队列配了 DLX/DLQ；`nexus.import.mode=sync` 可回退成同步。
- **增量**：SHA-256 比对，未变更跳过、变更重建。

> 详见 [docs/rag.md](docs/rag.md) §1。

### 4.2 Hybrid RAG（混合检索 → 重排）

```
query ──┬──► ES BM25 召回  ──┐
        │   (ik 分词, 字段加权) │
        └──► pgvector 召回 ──┴──► RRF 融合(k=60) ──► LLM 重排 ──► 上下文装配 ──► 生成
```

- 两路的**名次**而不是分数进入 RRF，因此 BM25 分与余弦相似度不可比的问题被绕开。
- 融合后再调 LLM 做重排（5s 超时则降级用融合结果）。

> 详见 [docs/rag.md](docs/rag.md) §2。

### 4.3 Multi-KB Retrieval（多库检索）

```
kb1 ──┐
kb2 ──┼──► 虚拟线程扇出 ──► 按输入顺序装配 ──► 跨库 RRF ──► 全局重排 ──► 上下文
kb3 ──┘        │
               └──► 失败库 → RetrievalFailure + retrieval.kb.failed 计数 + 界面点名
```

- 单库时**在调用线程直接执行**（不切线程），多库才扇出——单库路径行为零变化。
- 整体超时 `nexus.retrieval.fanout-timeout`（默认 10s）。
- 部分库失败 → 返回部分结果并**显式告知**（事件 + 提示词 + 前端标签「其中 N 个知识库检索失败」）；**全部失败 → 抛原异常**，不伪装成"检索到 0 条"。
- 归属校验失败（越权）与嵌入模型不一致**不被隔离**，仍整批失败——把越权探测变成"部分成功"比报错更糟。

> 详见 [docs/rag.md](docs/rag.md) §3 与 [docs/decisions.md](docs/decisions.md) B.48 / ADR-022。

### 4.4 Agent

```
Agent 配置 ──► Agent Runtime ──► Prompt 装配 ──► LLM
                 (绑定知识库 / 工具白名单 / 温度 / 回答策略)    │
                                     ┌───────────────────────┘
                                     ▼
                              Tool Calling ──► Tool Result ──► LLM ──► …
```

- 职责三分：`AgentDefinition` 是**配置**，`AgentRuntime` 是**一次执行**，`AgentService` 是**持久化与 CRUD 门面**。
- **循环不是手写的 while**——由 Spring AI 的 `ToolCallingManager` 驱动（模型 → 工具 → 模型）。
- 检索在 Agent 模式下是一个**工具**，绑定哪些知识库决定它的检索范围；空绑定 = 不限制。

> 详见 [docs/agent.md](docs/agent.md)。

### 4.5 Tool Calling

- 工具注册表 + **用户启用集合 ∩ Agent 白名单**双层裁剪；单轮工具调用有数量上限。
- 工具调用痕迹随消息落库（`chat_message.agent_id` 记录 Agent 出处），刷新后仍在。
- 已知缺口：MCP 直连 `tools/call` 绕过用户启用/停用开关。

### 4.6 Chat Streaming（SSE）

- 流式 SSE，检索事件**先于第一个字**到达（RAG 的"检索到 N 个片段"卡片因此能排在回答之前）。
- 支持随时 `POST /api/chat/stream/{requestId}/stop`；取消注册表**只在内存里**，多实例/重启即失效。
- 事件由**调用线程按序回放**，任务内绝不并发写监听器。

### 4.7 Observability

- 自建计数器（`ObservabilityRegistry`）+ 只读接口：`GET /api/observability/summary`、`GET /api/observability/agents`。
- 覆盖 token / 延迟 / 检索数 / 工具调用 / 失败库计数（`retrieval.kb.failed`）。
- **这是部分实现**：没有 Micrometer、没有 `/actuator/metrics`、没有 Prometheus，进程重启计数清零。

---

## 5. 技术栈

| 层 | 选型 |
| --- | --- |
| 语言 / 构建 | **Java 21**、Maven 3.9（4 模块 reactor） |
| 框架 | **Spring Boot 4.1.1**、**Spring AI 2.0.1**（BOM） |
| AI 能力 | ChatClient / ChatMemory / Tool Calling / Advisors / Observation |
| 业务库 | MySQL 8.4.11 LTS + MyBatis-Plus 3.5.17 + Flyway 12.4.0 |
| 向量库 | PostgreSQL 18 + pgvector 0.8.6（HNSW，`vector(1024)`） |
| 关键词检索 | Elasticsearch 9.4.5 + analysis-ik 9.4.5（BM25） |
| 缓存 / 队列 | Redis 8.10.2、RabbitMQ 4.3.6 |
| 文档解析 | PDFBox 3.0.7、Apache POI 5.5.1 |
| 前端 | Vue 3.5 + Vue Router 4.5 + TypeScript 5.7 + Vite 6 + Element Plus 2.9（依赖以 `web/package.json` 为准） |
| 部署 | Docker / docker compose、Nginx |

> 版本以 **`pom.xml` 与 `web/package.json` 为准**——这里是两者的摘要，不是另一份事实来源。
> 选型理由与版本决策记录在 [docs/decisions.md](docs/decisions.md)。
> 注意：前端**没有使用 Pinia**，状态直接在组件内管理。

---

## 6. 模块结构

```
prisdvl-nexus/                      (root pom, packaging=pom)
├── nexus-common/                   Result / ErrorCode / 异常 / 通用工具（无 Web 依赖）
├── nexus-ai/                       模型接入、ChatClient/ChatMemory、
│                                   ToolCallingManager、Agent Runtime、
│                                   Retriever 端口与策略族、Embedding、Rerank
├── nexus-knowledge/                解析 / 切片 / 向量库 / ES 索引 /
│                                   检索实现 / 引用 / 多库扇出
├── nexus-app/                      Spring Boot 可执行应用（唯一的可运行模块）
│                                   auth · user · knowledge · chat · agent ·
│                                   tool · observability · mcp
├── web/                            Vue 3 + TS 前端（独立 npm 工程，不是 Maven 模块）
│   ├── src/                        views / api / components
│   ├── scripts/                    E2E 与截图脚本（12 个 verify-*.mjs）
│   ├── nginx.conf                  生产入口配置
│   └── Dockerfile                  node 构建 → nginx 运行
├── deploy/
│   ├── docker-compose.yml          8 个服务的整栈编排
│   ├── .env.example                必填 secret 说明（缺了 compose 拒绝启动）
│   ├── backend/Dockerfile          应用镜像（JRE only）
│   ├── elasticsearch/Dockerfile    ES + IK 插件
│   └── postgres/init/              扩展初始化（vector 等）
├── scripts/                        seed-demo.mjs / smoke.ps1 / demo-vault.zip
├── docs/                           架构 / 数据库 / API / RAG / Agent / 安全 / 部署 / 决策
└── README.md
```

**依赖方向由编译期强制**：`nexus-app → nexus-knowledge → nexus-ai → nexus-common`，反向依赖编译不过。`nexus-common` 不依赖 Web，因此可以被任何模块复用而不引入 Web 栈。

> 注意：**不存在 `nexus-mcp-server` 模块**（`pom.xml` 里是注释掉的占位）。MCP 是 nexus-app 内的一个端点，理由见 §3。

---

## 7. 数据模型

| 域 | 表 |
| --- | --- |
| 账号权限 | `sys_user`、`sys_role`、`sys_user_role` |
| 知识库 | `kb_knowledge_base`、`kb_document`、`kb_chunk`、`kb_document_tag`、`kb_document_link` |
| 导入 | `kb_import_task`、`kb_import_task_item` |
| 问答留档 | `kb_qa_record`（含 `citations` 引用 JSON） |
| 会话 | `chat_conversation`、`chat_message`、`SPRING_AI_CHAT_MEMORY` |
| Agent | `agent`、`agent_knowledge_base`、`agent_tool`、`tool` |
| 平台 | `workspace` |
| 迁移 | `flyway_schema_history` |

- 迁移由 **Flyway** 管理（`nexus-app/src/main/resources/db/migration/`），当前 **V1–V18**。
- **没有 V11**：编号为某阶段预留后未使用，Flyway 不要求连续。
- **只增不改**：新增字段一律 `ADD COLUMN` 带默认值，不改名、不删除。
- 向量库有独立的迁移目录（`db/vector-migration/`），因为 pgvector 表不在 Flyway 的业务库管辖范围内。
- 所有外键都是**逻辑外键**（不建物理约束）。

> 完整 ER 图、字段说明与索引设计见 [docs/database.md](docs/database.md)。

---

## 8. 安全设计

| 机制 | 实现 |
| --- | --- |
| 认证 | JWT（HS256），claims 含 `sub/username/roles/ver/jti/iss/iat/exp` |
| 密码 | BCrypt 哈希 |
| 令牌吊销 | `token_version`；改密码后 bump 版本，**所有已签发令牌立即失效** |
| 登录失败冻结 | 连续失败达阈值后冻结一段时间（fail-open） |
| 限流 | 按用户与 IP 的固定窗口计数，返回 `Retry-After`（fail-open） |
| 每日配额 | 请求数 + token 数双限额，日历日键 + TTL（**fail-closed**） |
| 用户隔离 | `CurrentUser.requireId()` 取用户；`requireOwned/requireAllOwned` 把 `user_id` **写进 SQL 查询**，不是取回来再过滤 |
| 越权语义 | 返回 404 / 3001，**不返回 403**（不泄露资源是否存在） |
| 上传防护 | ZIP Slip 词法拒绝（`..` / 绝对路径 / `C:/`）后**整批中止**；Zip Bomb 三层上限（条目数 / 单文件 / 总量，按**解压后**字节判） |
| 部署 | `JWT_SECRET` / `AI_CHAT_API_KEY` 用 `${VAR:?…}` fail-closed——缺了 compose **拒绝启动**；backend 不发布端口 |

**未实现**（不要误以为有）：OAuth2 / 第三方登录、refresh token、服务端 logout、CSRF token、TLS、安全响应头、字段级加密、KMS/Vault。

> 逐条机制与证据见 [docs/security.md](docs/security.md)。

---

## 9. Docker 部署

一条命令得到完整系统（8 个服务）：

```bash
# 1) 先用 JDK 21 打出 jar（镜像里只运行、不构建）
JAVA_HOME=/d/develop/jdk-21 mvn -B package -DskipTests

# 2) 配置必填 secret
cd deploy
cp .env.example .env        # 填 JWT_SECRET 与 AI_CHAT_API_KEY；缺了 compose 会拒绝启动

# 3) 起整栈
docker compose build && docker compose up -d
docker compose ps           # 等 8 个服务全部 (healthy)

# 4) 浏览器打开
#    http://localhost:8088
```

8 个服务：`mysql`、`postgres-pgvector`、`redis`、`elasticsearch`、`rabbitmq`、`ollama`、`backend`、`frontend`。
容器里只有 JRE 与静态资源，**不需要在容器内装 JDK / Maven / Node**；`backend` **不发布端口**，只有 nginx 对外。

> 环境变量、端口、健康检查、初始化顺序、日志查看、数据持久化、启停与更新、常见问题，全部见
> [docs/deployment.md](docs/deployment.md) §2。**该文档的步骤是从零可执行的**，不是示意。

---

## 10. 本地开发

### 方式 A：`standalone`——完全不需要 Docker

用 H2 内存库跑通全部功能，**重启即清空**：

```powershell
$env:JAVA_HOME = 'D:\develop\jdk-21'
mvn -B clean package -DskipTests
java -jar nexus-app\target\nexus-app-0.0.1-SNAPSHOT.jar --spring.profiles.active=standalone

# 另开终端：冒烟测试（注册→登录→建会话→问答→流式+停止→查历史）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke.ps1

# 界面验证要用的演示数据（H2 重启后要重跑）
node scripts\seed-demo.mjs
```

### 方式 B：`local`——基础设施在容器、后端在宿主机

改代码频繁重启时更方便：

```powershell
cd deploy
docker compose up -d mysql postgres elasticsearch redis rabbitmq ollama
docker compose ps            # 等 6 个服务 (healthy)

cd ..
$env:JAVA_HOME = 'D:\develop\jdk-21'
mvn -B clean package -DskipTests
java -jar nexus-app\target\nexus-app-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

| 服务 | 地址 |
| --- | --- |
| MySQL | `localhost:3307`（宿主机原生 MySQL 占了 3306，故映射到 3307） |
| PostgreSQL | `localhost:5433` |
| Elasticsearch | http://localhost:9200 |
| RabbitMQ 控制台 | http://localhost:15672 |
| Redis | `localhost:6379` |

### 前端

```powershell
cd web
npm install
npm run dev        # http://localhost:5173（/api 由 Vite 代理到后端 8080，不涉及 CORS）
npm run build      # vue-tsc 类型检查 + vite build
```

三个 profile 的分工：`local`（真实 MySQL，日常开发）、`standalone`（H2 内存，无 Docker 时应急）、`test`（H2，跑测试）。
**新增 profile 配置或迁移脚本后必须重新 `package`**，否则 jar 里还是旧的。

---

## 11. E2E 验证

12 个脚本（`web/scripts/`），复用系统 Edge/Chrome，不下载浏览器：

| 脚本 | 验证内容 |
| --- | --- |
| `verify-full-product.mjs` | **全链路冒烟**：注册 → 登录 → 建两个库 → 上传 → **等异步导入到终态** → 向量化 + 建索引 → RAG（断言引用） → 建 Agent + 绑两库 → 发布 → Agent Chat → **Tool Calling** → SSE 流式 → **Observability** → 越权隔离 → 真登录表单进界面。自建账号、自清理，不依赖 seed-demo |
| `verify-login.mjs` | 真实走登录表单，确认跳转与刷新后仍保持登录 |
| `verify-frontend.mjs` | 知识库列表 → 正文（Markdown / 大纲 / 关联面板）→ 切片 |
| `verify-doc-lifecycle.mjs` | 单文件上传 → 逐文件失败原因 → 删除文档 |
| `verify-stream.mjs` | 知识库问答流式：逐字增长 + 引用 + 生成期间的"检索到 N 个片段" |
| `verify-retrieval-ui.mjs` | 检索卡片渲染契约：**桩 SSE 帧**，五条腿（命中 / 零命中 / 一个库失败 / 全部失败 / 部分命中部分失败） |
| `verify-multi-kb.mjs` | 多知识库 RAG：自建两库导不同内容 → 多选绑定 → 只能由第二个库回答的问题 → 越权绑定被拒 |
| `verify-multi-kb-failure.mjs` | **需整栈 + 真 ES**：三库逐级覆盖 → 往真实 ES 写坏文档让 B **真失败** → 验 A✅/B❌/C✅、计数自增、界面点名 |
| `verify-agents.mjs` | Agent 管理页全生命周期（新建 → 配置 → 发布 → 选用 → 停用 → 删除，11 条腿） |
| `verify-agent-chat.mjs` | 对话页三条腿：Agent 工具卡片 + 重命名会话 + RAG 检索卡片 |
| `verify-agent-no-kb.mjs` | 零知识库新用户提问：提示 → 检索如实报空 → 回答注明不是来自笔记 |
| `verify-security.mjs` | 改资料 → 错原密码不踢下线（400 而非 401）→ 改密码后旧 token 401 → 新密码登录 |

**退出码语义（重要）**：`0` 全部通过 / `1` 真实失败 / **`2` 有跳过**。
跳过只在**环境能力缺失**时发生（例如 `standalone` 没有 ES），脚本会打印 `⚠ SKIPPED` 与原因——**跳过不等于通过**。

脚本自带清理（自建的知识库、文档、账号在结束时删除），不依赖 `seed-demo` 的固定数据库状态；`verify-agent-no-kb.mjs` 与 `verify-security.mjs` **故意不用演示账号**，每次运行自建 `probe-*` / `sec-check-*`。
**代价**：没有删号接口，因此每跑一次会留下一个这类账号——写在这里，免得以后当成残留 bug 去查。

> 另有 `screenshot.mjs` 生成文档截图；`debug-click.mjs` 是一个**遗留的调试脚本**，保留但不在验证流程内。

---

## 12. 测试情况

| 口径 | 数字 |
| --- | --- |
| 单元 + 集成测试 | **567 / 0 失败 / 0 错误 / 18 跳过**（104 个测试类） |
| 真机（容器化整栈） | 上面那 18 个 live 测试**全部真跑通过，0 跳过** |

那 18 个跳过项**不是被删掉的失败**：它们是需要真实 MySQL / ES / pgvector 的 live 测试，在无 Docker 的默认环境里被跳过，在整栈起来后全部执行并通过。

```bash
JAVA_HOME=/d/develop/jdk-21 mvn -B clean test        # 全量
cd web && npm run build                              # 前端类型检查 + 构建
```

> **跑测试前先 `docker compose stop`**。整栈 8 个容器占着内存时，Mockito 的 ByteBuddy 自附加
> （它要再 fork 一个 JVM）会失败，报 `does not supply a working agent attachment mechanism`——
> 这个报错看着像 JDK 有问题，**实际是内存不足**。跑完 `docker compose start` 恢复。

**证据分级**：本项目文档对每条结论标注验证层级——`UNIT`（单元测试）/ `INTEGRATION`（MockMvc 等集成）/ `E2E`（真浏览器）/ `LIVE`（真 MySQL/ES/pgvector）/ `REAL ENVIRONMENT`（整栈容器化）。
**凡是没真跑过的，就写 NOT VERIFIED**，不用"应该没问题"糊过去。

---

## 13. 项目演进

平台化升级是一条与功能 Phase 并行的轨道，每个阶段都有决策记录与验收证据（[docs/decisions.md](docs/decisions.md) 附录 B）：

| 阶段 | 内容 |
| --- | --- |
| 平台化 1 | 架构分析（**未改代码**） |
| 平台化 2 | `workspace` 领域模型 + 知识库平台字段（迁移 V9/V10） |
| 平台化 3.1–3.4 | Retriever 端口抽象（含新旧一致性测试）、检索参数真正可配置、元数据过滤下推、RAG 会话流式 |
| 平台化 4.1–4.2 | Agent 配置化（后端 CRUD + 前端管理页） |
| 平台化 5.3–5.5 | 知识库闭环（上传 / 删文档 / 失败原因）、Agent 闭环（温度 / 工具白名单 / 出处）、多知识库 RAG |
| 平台化 5.6 | 用户与安全：改密码 + 令牌吊销、登录失败冻结、限流与每日配额 |
| 平台化 5.5.1 | 多库检索并行化 + 逐库失败隔离 |
| 平台化 5.7 | 部署产品化：容器化整栈 + 真机验收（18 个 live 测试全部真跑、11 个 E2E 经 nginx 全绿） |
| 平台化 5.8 | 产品化收口与最终验收（本文档重写 + RAG/Agent/安全/架构/部署文档补齐 + 全链路冒烟） |

**当前状态**：功能 Phase 1–5 与平台化轨均已完成到 5.8，可运行的是一条完整链路（导入 → 检索 → RAG → Agent → 工具 → 引用 → 可观测），而**不是**一堆独立的演示端点。

---

## 14. 已知限制

诚实列出，不藏：

**架构级**
1. **Observability 是部分实现**——自建计数器，无 Micrometer / Prometheus，重启清零，无时间序列。
2. **Citation 是部分实现**——知识库问答路径完整落库引用；**多库 chat-stream 路径既不持久化引用、也不发最终引用列表**。
3. **没有 `citation` SSE 事件**（`docs/api.md` 里标注为"预留"，代码确认确未实现）。
4. **Agent 最大迭代没有显式旋钮**——终止条件由框架与工具数量上限决定。
5. **`answerPolicy` 只在提示词层强制**，不是代码级断言。
6. **`RerankedRetriever` 装饰器休眠**——重排在 `RagAnswerServiceImpl` 里直接调用，端口有了但没接线。
7. **MCP 直连 `tools/call` 绕过用户启用/停用开关**。
8. **流式取消注册表只在内存**，多实例 / 重启即失效。
9. **JWT 的 `iss` 只写不校验**；无 refresh token，logout = 客户端丢弃 token。

**工程级**
10. **ES 索引 `recreateIndex()` 不是同秒幂等的**——索引名精确到秒，同秒重建会撞 `resource_already_exists_exception` 并返回 500，而此时切片已被标记为 INDEXED（已知缺陷，本期**刻意不修**）。
11. **`LiveChunkIndexTest.recreateIndexSwitchesAlias` 会清空共享 ES 索引**（已知缺陷，本期刻意不修）。
12. **检索事件未持久化**——"检索到 N 个片段"卡片刷新后消失（P2-4，本期范围外）。
13. **无物理外键**，全部逻辑外键。
14. **无 Testcontainers**，真机回归靠人工跑 E2E。
15. **配额与限流在 Redis 不可用时降级为进程内实现**，多实例下可绕过。
16. **软删除 / 停用不会立即吊销已签发令牌**。
17. **token 存在 localStorage**，有 XSS 暴露面。

完整清单与分级见 [docs/productization-gap-analysis.md](docs/productization-gap-analysis.md)。

---

## 15. Roadmap

**下一步优先级**（依据 gap 分析，不是愿望清单）：

1. **引用与检索事件的持久化**——把多库 chat-stream 的引用补上，让卡片刷新后还在。
2. **Observability 接入 Micrometer**——替换自建计数器，接上 `/actuator/metrics`。
3. **安全加固**——TLS 终结、安全响应头、logout 与 refresh token、把 token 从 localStorage 挪走。
4. **Testcontainers**——把真机回归从"人工跑脚本"变成 CI 里可重复的一步。
5. **Phase 6+ 的功能扩展**——Workflow / Evaluation / 更完整的 MCP（当前 MCP 只有 4 个方法）。

> 长期阶段拆分（Phase 0–11）见 [docs/roadmap.md](docs/roadmap.md)。
> ⚠️ **该文档的 Phase 编号与上文"平台化 5.x"不是同一套编号**，两者同名不同义，读的时候注意区分。
> 产品化差距与分级（P1/P2/P3）见 [docs/productization-gap-analysis.md](docs/productization-gap-analysis.md)。

---

## 文档索引

| 文档 | 内容 |
| --- | --- |
| [docs/architecture.md](docs/architecture.md) | 系统架构、分层规则、模块化单体的理由、数据流 |
| [docs/rag.md](docs/rag.md) | 文档导入、混合检索、多库检索、元数据过滤、零命中行为、引用 |
| [docs/agent.md](docs/agent.md) | Agent 三类的职责、执行链路、工具白名单、状态、温度、流式与停止 |
| [docs/security.md](docs/security.md) | JWT / BCrypt / 令牌吊销 / 冻结 / 限流 / 配额 / 隔离 / 上传防护 |
| [docs/deployment.md](docs/deployment.md) | 部署与使用（含容器化整栈、从零可执行步骤、验收证据表） |
| [docs/database.md](docs/database.md) | ER 图、核心表设计、索引、Redis/ES 结构 |
| [docs/api.md](docs/api.md) | 统一返回、错误码、REST 与 SSE 协议（含"规划中未实现"的明确标注） |
| [docs/decisions.md](docs/decisions.md) | 架构决策记录（ADR）+ 每阶段实测记录 |
| [docs/productization-gap-analysis.md](docs/productization-gap-analysis.md) | 产品化差距审计与 P1/P2/P3 分级 |
| [docs/roadmap.md](docs/roadmap.md) | Phase 0–11 任务拆分（注意编号体系与平台化轨不同） |
| [docs/platform-upgrade-phase1-analysis.md](docs/platform-upgrade-phase1-analysis.md) | 平台化升级的架构分析 |
| [docs/platform-phase3-baseline.md](docs/platform-phase3-baseline.md) | 平台化 Phase 3 基线与真机验证记录 |

## 开发约定

1. **一次只做一个 Step**：需求 → 设计 → 实现 → 运行 → 测试 → 总结，完成后再进入下一步。
2. **分层不可跨越**：`Controller → Service → Domain/Mapper`；Controller 不碰数据库、不直接调 LLM。
3. **可运行优先**：不为"看起来高级"引入无实际价值的组件。
4. **每个核心模块必须有测试**。
5. **答案必须区分来源**：知识库内容 / 检索到的外部资料 / 模型自身知识 / 模型推测。
6. **文档与代码同一次提交**，测试数字用实测值，没跑过的写 NOT VERIFIED。
