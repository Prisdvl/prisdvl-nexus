# Prisdvl-Nexus 交接文档
> ⚠️ **状态：已被仓库根的 `CODEX_HANDOFF.md`（2026-10-09）取代。**
> 本文写于 2026-10-08，其中**"前端尚未接后端"的判断已经过时**：当前 `frontend/` 默认走真实 API
> —— `frontend/src/lib/api.ts` 的 `USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'`（默认 false，
> 只有显式设 `VITE_USE_MOCK=true` 才回落到种子数据），登录令牌存 `localStorage['nx-token']`，
> 会话列表在登录后由 `frontend/src/lib/sync.ts` 调 `GET /api/chat/conversations` 拉取。
> 本文件仅作阶段性记录保留，**当前现状以 `CODEX_HANDOFF.md` 与 `README.md` 为准**。

> 面向：接手的下一个 Agent
> 更新时间：2026-10-08
> 本文档里的**每条结论都标注了"已验证 / 未验证"**。未验证的事不要当成事实继续推理。

---

## 0. 一句话现状

后端（Spring Boot 4.1.1 + Spring AI 2.0.1，模块化单体，4 模块 / 约 3.7 万行 Java）**代码完整、可编译**；
前端（React 19 + Vite，原 `prototype-ui`，现改名 `frontend/`）是**设计原型，数据全在 localStorage，尚未接后端**。
本轮刚做完三件重构：**后端能力裁剪、docs 归档、三套前端合并成一套**。
**最大的未完成项就是"前端接后端"** —— 后端接口早已齐全（含 SSE 流式与停止），前端还在演。 ⚠️ **（此句已过时：前端已接后端，见文首横幅。）**

---

## 1. 项目定位（决定取舍的标准）

个人 → 平台的 AI Agent 项目，作者的**简历级核心项目**（Java 后端背景的本科生）。
因此所有决策优先看两条：
1. **真的能跑起来**（不是演示稿）；
2. **能讲清楚为什么这么做**（每条设计决策都写进 `docs/decisions.md`，现有 90+ 条）。

---

## 2. 环境与技术栈（都是实测过的）

| 项 | 值 | 备注 |
|---|---|---|
| JDK | **`D:\develop\jdk-21`**（21.0.12.1 LTS） | 跑 Maven 必须 `$env:JAVA_HOME='D:\develop\jdk-21'`，否则报 `不支持发行版本 21` |
| Maven | `D:\develop\Maven\apache-maven-3.9.9\bin\mvn.cmd` | 本地仓库 `D:\develop\Maven\repository`；可加 `-o` 离线跑 |
| 后端 | Spring Boot **4.1.1** / Spring AI **2.0.1** / MyBatis-Plus 3.5.17 / Flyway / Jackson 3 | Java 21 虚拟线程已开 |
| 前端 | React **19.3** / Vite **8.3.2** / TS ~6.0.2 / react-router 8.4（**hash 路由**）/ 纯 CSS 变量（无 Tailwind） | 图表用 CSS 画，不引图表库 |
| 前端 dev | `cd frontend; npm run dev` → **http://localhost:5175/** | ⚠️ 只绑 localhost/IPv6，`127.0.0.1:5175` **打不开** |
| 数据库 | MySQL 8.4（**3307**）/ PostgreSQL+pgvector（**5433**）/ ES 9.4.5+IK（**9200**）/ Redis / RabbitMQ | 全在 `deploy/docker-compose.yml` |
| **Docker** | ⚠️ **当前未运行**（daemon 连不上） | 所以整栈 E2E 现在做不了 |

### 后端 profile（关键）

| profile | 依赖 | 现状 |
|---|---|---|
| `local` | MySQL 3307 + Redis + ES + pgvector + RabbitMQ | ❌ **现在跑不了**：Docker 没起；且上次启动死在 `Migration checksum mismatch for migration version 15`（有人改过已应用的迁移，需要 `flyway repair`） |
| **`standalone`** | **H2 内存库**（`jdbc:h2:mem:nexus_standalone;MODE=MySQL`）+ Flyway 跑**同一套** MySQL 方言迁移；向量/ES/RabbitMQ/Redis 全关 | ✅ **无 Docker 也能把后端跑起来** —— 接后端联调应该走这条 |
| `test` | H2 | 用于单测 |

> `application-standalone.yml` 的文件头写明了它的用途与依据（含"11 个迁移成功、v12"的实测记录），**这是不依赖 Docker 的唯一入口**。

---

## 3. 目录结构（本轮重构后的现状）

```
D:\develop\Prisma
├── nexus-common/      15 个 Java 文件 /   723 行    Result / ErrorCode / 加密 / 观测注册表
├── nexus-ai/          31 个           / 2,774 行    模型接入 / ChatClient / ChatMemory / RAG 端口
├── nexus-knowledge/  132 个           / 11,890 行   解析 / 切片 / 向量 / ES 索引
├── nexus-app/        228 个           / 21,998 行   Controller / Service / 安全 / 工具 / MCP
├── frontend/          ← 唯一前端（原 prototype-ui 改名而来，React 19 + Vite）
├── deploy/            docker-compose.yml（8 容器）+ backend/elasticsearch Dockerfile + postgres init
├── docs/              13 份核心文档（见 §7）+ prototypes/shots（界面证据）+ archive/（过程产物）
├── data/              72 个演示用 md
└── scripts/           seed-demo.mjs / smoke.ps1 / cleanup-test-data.ps1 / demo-vault.zip
```

**依赖方向严格线性、无环**：`nexus-common ← nexus-ai ← nexus-knowledge ← nexus-app`。

### 本轮删掉/移走的东西（都在 git 里，可 `git restore`）

| 动作 | 内容 | 原因 |
|---|---|---|
| 删 | 根目录 5 个日志（2.5 MB）、`data-not-exist/`、`nexus-app/data/staging/` | 运行产物 / 空目录 |
| **删后端** | `app/eval/`（2 类）、`app/observe/`（5 类）+ 对应测试 | 原型无对应界面、无外部调用方（详见 `docs/decisions.md` **D.11**） |
| 留 | `app/mcp/`（1 类，`POST /mcp`）、`app/workflow/`（3 类） | MCP 是**对外能力**（外部 LLM 客户端连知识库，文档完整）；workflow 作者说"后续更新" |
| 归档 | 20 份阶段性报告 + 67 张旧截图 + `update.txt` → `docs/archive/` | 过程产物，不删（保留决策证据链），但要从顶层移走 |
| 归档 | `web/`（Vue 3）→ `docs/archive/web-vue/`；`web-react/`（迁移中间态）→ `docs/archive/web-react/` | 三套前端合并为一套（`node_modules` 已删，只留源码） |
| 改名 | `prototype-ui/` → **`frontend/`** | 作者原话："这名字太草率了"；`package.json` name 也从 `prisdvl-nexus-ui-prototype` 改成 `nexus-frontend` |

---

## 4. 已实现的能力清单

### 后端：16 个控制器 / **49 个端点**（源码实测）

| 域 | 端点 | 控制器 |
|---|---|---|
| 认证 / 用户 | 2 + 4 | `AuthController`、`UserController` |
| 对话 | **1 + 5 + 1 + 2** | `ChatController`、`ChatConversationController`、`ChatMessageController`、**`ChatStreamController`（`POST /api/chat/stream` SSE 流式 + `POST /api/chat/stream/{requestId}/stop` 停止）** |
| Agent / 工具 | 1 + 7 + 2 | `AgentController`、`AgentResourceController`、`ToolController` |
| 知识库 | 5 + 6 + 1 + 5 + 2 + 3 | `KnowledgeBase` / `Document` / `Embedding` / `Import` / **`Rag`** / `Search` |
| MCP | 1（`POST /mcp`） | `McpEndpoint`（进程内 JSON-RPC：init / ping / tools-list / tools-call，JWT 鉴权） |
| 工作流 | 1 | `WorkflowController`（作者说后续更新） |

**关键契约**：主键是雪花 ID，**后端统一序列化为字符串**（ADR-017；`LongToStringJacksonComponent`，带 `@JacksonComponent`）。
→ **前端绝不能把 id 转成 Number 再传回去**（会精度丢失）。这是接后端时最先撞上的坑。

### 前端：设计原型（16 阶段质量门禁）

已落地的交互能力（都是本轮或上轮做的，每项都有 QA 断言）：

| 能力 | 说明 |
|---|---|
| 会话持久化 | localStorage，超预算时丢最旧的未置顶会话；设置里有「重置演示数据」 |
| 停止语义 | 停止 → 该轮收尾为「已停止」，未跑的工具标 `cancelled`；超时独立为 `timeout` 态 |
| 继续生成 | 停止后可续跑，不重复已完成部分 |
| 错误边界 | 路由级 `AppError`（这个页面出错了 / 重新加载 / 已保存在本地 / `<details>` 技术细节） |
| 反馈闭环 | 👍/👎 一次点击落库（11 个可归因字段）→ 设置里导出 JSON/CSV |
| **高风险工具确认卡** | `awaiting_confirm` 状态机：允许一次 / 始终允许 / 拒绝（拒绝=跳过，不中断整轮）；「始终允许」可在设置里撤销 |
| **上下文胶囊** | 回答上方：知识库 N / 命中 N 切片 / **引用 N 篇（可点开右栏）** / 历史 N 轮 / 是否联网；**历史被截断时用提醒色** |
| **结果卡** | 表格 / JSON / 图表（CSS 条形图）三形态，**复制成标准 CSV/JSON + 下载** |
| **busy 排队** | 忙时输入进队列（可见/可删/可清空），上一轮结束自动接着发；停止会清空队列 |
| 消息导航 | 右侧提问横线 + hover 预览（Portal + fixed） |
| 引用面板 | 右侧 380px：文档正文 / 命中切片 / 相关文档 |
| 新手引导 / 设置 | 5 步 coach mark（轮廓式高亮）；设置 10 个分区 + 搜索 + 重看引导 |

---

## 5. 本轮改动的验证状态（⚠️ 认真读这一节）

| 项 | 状态 |
|---|---|
| 后端编译（`mvn -o -DskipTests test-compile`，JDK 21） | ✅ **已验证 exit 0**（删 eval/observe 后、恢复 MCP 后各验一次） |
| `frontend/` 构建（`npm run build`） | ✅ **已验证 exit 0**（改名后 2.17s） |
| 前端 16 阶段门禁（改名后） | ✅ **已验证 exit 0**（14 个阶段各自打印"全部通过"，含用色/无障碍/路由/外观/设置/统一性/卡片粒子/文档预览/会话栏/新手引导/交互/功能级/响应式/截图） |
| 后端**运行期** E2E | ❌ **未验证**（Docker 未起；`local` 还卡在 Flyway checksum） |
| `frontend/Dockerfile` + `nginx.conf` 的镜像构建 | ❌ **未验证**（daemon 不在）—— 这两个文件是从 Vue 版适配来的，逻辑上可用但没跑过 |
| 前端 QA 之外的"真实接口"行为 | ❌ 未验证（前端根本没调后端） |

---

## 6. 已知问题与风险（按重要性）

1. **前端与后端完全没接**：前端所有数据来自 `src/data.ts` 种子 + localStorage。后端 49 个端点中前端实际调用了 **0 个**。
2. **主键类型不一致**：前端 `SessionItem.id` / `msgId` 是 `number`，后端返回 **字符串雪花 ID**。接后端必须改类型（这是主要工作量，不是小事）。
3. **Docker 未运行** → `local` profile 起不来；且历史上有 `Flyway checksum mismatch for version 15`，恢复 Docker 后需要先 `flyway repair` 或新增一条迁移。
4. **机器内存紧张**（实测可用 1.8–4.8 GB 波动）：会出现 `Thread failed to start`、构建进程以 `0xC0000409` 崩掉、Playwright 超时。跑门禁前后要清 stray `msedge`（曾同时残留 13 个）。
5. **文档曾超前于代码**：`api.md` 里评测写了 8 个端点、实际只实现 1 个（已在本轮加"已移除/从未实现"标注）。**接手后新增接口务必同步文档**。
6. **`workflow` 与 `mcp` 没有前端入口**：保留是刻意的（分别是"后续更新"和"对外能力"），不要在"没用到的都删掉"的口号下再删一次。
7. **多 Agent 已被作者明确搁置**（原话"先不做多agent"）。数据模型里**没有** `actor` 字段，UI 也没有角色徽标——不要自作主张加。

---

## 7. 质量门禁与常用命令

### 前端（在 `frontend/` 下）

```powershell
npm run dev            # http://localhost:5175/（只绑 localhost）
npm run build          # tsc --noEmit && vite build
npm run verify:all     # 15 个 QA 阶段 + build = 16 阶段，必须 exit 0
# 单阶段：verify:contrast / palette / a11y / routing / theme / settings / unify / cards /
#         doc / chat / tour / interactions / features / responsive / shots
```

**踩坑提示**：
- 门禁日志是 UTF-8，用 `[System.IO.File]::ReadAllText($p,[Text.Encoding]::UTF8)` 读；`Get-Content -Raw` 会显示乱码。
- `npm.ps1` 偶尔抛 `Object reference not set` → 用 `cmd /c "npm run verify:all > log 2>&1"`。
- 所有 QA/截图脚本都必须预置这两个 localStorage 键，否则会被人机引导或确认卡打断：
  `nx-proto-tour-done='1'`、`nx-proto-tool-policy='["js.sandbox"]'`。
- Playwright 用 `channel: 'msedge'`；跑之前清 stray msedge。
- 单个阶段失败先**单独复跑**再下结论（曾出现 `ERR_CONNECTION_CLOSED` 的假失败）。

### 后端（仓库根）

```powershell
$env:JAVA_HOME='D:\develop\jdk-21'
& 'D:\develop\Maven\apache-maven-3.9.9\bin\mvn.cmd' -o -q -DskipTests test-compile   # 编译 + 测试编译
& '...mvn.cmd' -o -q test                                                            # 全量测试（文档称 603 tests，本轮未复验）
# 无 Docker 启动（待验证）：
& '...mvn.cmd' -pl nexus-app -am spring-boot:run "-Dspring-boot.run.profiles=standalone"
```

---

## 8. 下一步任务计划（建议顺序，每项独立可验收）

### P0-1 复验改名后的前端门禁 ✅ **已完成**
- 结果：`cd frontend; npm run verify:all` → **exit 0**，14 个阶段全部通过。
- 意义：证明"三套前端合并 + 目录改名 + 新增 Dockerfile/nginx.conf"没有破坏任何既有行为。

### P0-2 让后端在无 Docker 下真的跑起来
- **做什么**：用 `standalone` profile 启动，`curl` 探一个只读接口（如登录 + 会话列表）。
- **验收**：进程起来 + 至少 1 个接口返回 200 且结构符合 `docs/api.md`。
- **前置**：确认 H2 下 Flyway 迁移能全绿（standalone 是新库，理论上不会有 checksum 问题）。

### P1-1 前端接后端（**最高价值的下一步**，务必分小步）
- **第一步只做一件事**：真实登录 + 拉会话列表，**其余仍走本地 store**。
- 必须同时处理：
  1. `id` 类型 `number → string`（雪花 ID，见 §4 的关键契约）；
  2. 请求层（base URL / JWT 头 / 401 跳登录 / 错误码映射）；
  3. CORS 或 dev proxy（后端 8080，前端 5175；`web/` 老前端当年是靠代理解决的）；
  4. 环境开关：默认真实 API 还是本地假数据（建议 `VITE_USE_MOCK`，让原型仍可独立跑）。
- **验收**：登录后侧栏会话列表来自后端；断网/后端未启动时给出明确错误而不是白屏。
- **产出**：一份"接后端改动清单"（哪些 store、哪些类型要改），供后续增量替换。

### P1-2 真实流式输出（P1-1 的前置/并行）
- **做什么**：把"一次性蹦出整段回答"改成逐字流式，并记录**真实首 token 时间**（正好喂给已有的反馈字段 `firstProgressMs`）。
- **验收**：回答逐字出现；停止按钮能中断；耗时字段有值。
- 好处：SSE 消费端先写好，接后端时只需换数据源（`EventSource` / fetch stream）。

### P2-1 文档与架构整理
- 现状：README + 13 份核心文档已经不错；可补一张真正的架构图（现在 README 里是 ASCII 框图）。
- **验收**：新读者 10 分钟内能说清"一次提问经过哪些模块"。

### 明确**不做**（作者已表态，不要自作主张）
- ❌ 多 Agent / 角色协作（"先不做多agent"）
- ❌ 微服务化（模块化单体是刻意选择，见 `docs/architecture.md` §1.3）
- ❌ 工作流编排的完善（作者说"后续更新"，别动）
- ❌ 引入没有实际价值的技术（为了代码量而加的东西一律不做）

---

## 9. 硬性约束（作者反复强调过，违反会被打回）

1. **一次最多实现一个完整的小功能**（"不要一次性生成整个项目"）。
2. **先分析，再修改**；遇到不确定的 API 先验证再动手（"不要猜"）。
3. **优先保证项目真的能运行**，其次才是功能多。
4. **不要过度设计**（不要为了架构好看引入复杂度）。
5. **尽量让作者自己写核心代码** —— 关键实现要解释清楚"为什么"，而不是替他写完全部。
6. **不要把 Spring AI 当黑盒**：用到哪个能力就去看官方文档/源码，说清它的行为边界。
7. 发现问题要**提问 + 给数据**（实测值、日志、截图），不要含糊地说"应该没问题"。
8. 汇报用**证据**：退出码、断言数、截图路径、根因分析。

---

## 10. 接手后的第一件事（检查清单）

```powershell
# 1) 环境
$env:JAVA_HOME='D:\develop\jdk-21'
Get-Process msedge -ErrorAction SilentlyContinue | Stop-Process -Force   # 清残留浏览器
[math]::Round((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1MB,1)  # 看内存

# 2) 确认目录已重构
Test-Path frontend\Dockerfile          # 应为 True
Test-Path docs\archive\web-vue         # 应为 True（Vue 版已归档）
Test-Path prototype-ui, web, web-react # 应全部 False

# 3) 前端
cd frontend; npm run dev               # → http://localhost:5175/
npm run verify:all                     # 必须 exit 0

# 4) 后端编译
cd ..; & 'D:\develop\Maven\apache-maven-3.9.9\bin\mvn.cmd' -o -q -DskipTests test-compile

# 5) 看当前改动（本轮尚未提交，全部可回退）
git status --short | Measure-Object
```

### 关键文档索引
| 文档 | 用途 |
|---|---|
| `README.md` | 项目总览、快速开始、能力清单 |
| `docs/architecture.md` | 分层规则、模块边界、数据流（§1–§19 是最初设计稿，后面是实际实现与偏差说明） |
| `docs/api.md` | 接口契约、错误码、SSE 事件序列（已标注移除项） |
| `docs/database.md` | ER 图、字段、索引设计 |
| **`docs/decisions.md`** | **90+ 条决策记录（最有价值的一份，交接必读；最新一条 D.11 就是本轮的后端裁剪）** |
| `docs/security.md` / `deployment.md` | 安全机制 / 从零可执行的部署步骤 |
| `docs/agent.md` / `rag.md` | Agent 装配与工具 / 检索链路 |
| `docs/project-tutorial.md` | 按"想改 X 就动 Y"组织的落点索引 |
| `docs/archive/README.md` | 归档区说明（**别把归档里的东西当现行规范**） |

---

## 11. 当前 git 状态

- 最后一次提交：`a1b7894`（2026-10-06，docs(decisions): B.52 …）
- 工作区：**+10 修改 / 270 删除 / 6 新增**（全部是本轮重构，**尚未提交**）
- **所有删除都可 `git restore` 恢复** —— 这不是一次性不可逆操作。
