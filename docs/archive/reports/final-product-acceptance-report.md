# prisdvl-nexus Final Product Acceptance Report

- 执行日期：2026-10-03（20:00–21:00）
- 执行依据：《prisdvl-nexus 最终产品完善总提示词》（77 节），方法为 §3「先发现，再修复」
- 验收环境：`http://localhost:8088`（nginx 网关 + Vue 静态产物 + Spring Boot + 8 容器）
- 分支 / HEAD：`platform-phase3` / `f2a18dc`
- Git：**未 commit、未 push、未 tag**

---

## 1. 执行范围与授权边界

老大在审计后通过选项确认了两项决定，本报告的全部动作均在其范围内：

| 决定项 | 答复 | 本报告对应动作 |
| --- | --- | --- |
| 施工范围 | **全做 5 项** | §34 + §6 + §7 + §13 + `verify-stream` 改自持账号 |
| 工具级残留（ES 索引壳 / 软删墓碑 / 记忆残留） | **维持现状 + 文档记录** | 只量化、只归档，**不动代码** |

全程遵守的硬边界：

- `Prisdvl`（**真实用户数据**）**未触碰**——清理后被单独核验，逐项存活；
- `demo` 与其演示数据保留：37 会话 / 2 知识库 / 31 问答留档，与基线逐项一致；
- **未新增** Admin HTTP API，**未新增 Phase**；
- 未使用裸 `DELETE FROM kb_knowledge_base`（全部走既有级联）；
- **未用"文案修改"掩盖任何真实 Bug**——4 项修复中 3 项是真实代码/结构改动，1 项是竞品措辞；
- 未改 `docs/update.txt`；
- 未 commit / push / tag。

---

## 2. 审计结论：先发现（任务书声称的问题，逐条实测后才动手）

任务书基于静态阅读写成。逐条复核后分为两类——**照单全收去改会把正确的代码改坏**。

### 2.1 复核确认「已经是正确实现」：11 项（无需改动）

| 声称的问题 | 复核方式 | 结论 |
| --- | --- | --- |
| §8 / §57 空知识库 0 命中仍调 LLM | 读三条路径源码 | **已正确**：`RagAnswerServiceImpl.ask()` / `prepareRetrieved()` / `ChatStreamServiceImpl`（`ragMode && prepared.empty()`）与 `RagStreamServiceImpl` 全部短路 → `finishWithoutModel`，**不调模型** |
| §11 新用户注册带 demo 污染 | 读 `AuthServiceImpl` | **无种子数据**：注册只建 user + role + 个人 workspace |
| §14 前端展示后端不支持的格式 | 读 `ParserRegistry.extensions()` | **一致**：md / txt / pdf / docx（`.doc` 明确不支持） |
| §17 文件选择器取消 → 永久 loading | 读两处实现 | **已正确**：均 `if (!files.length) return`，无提前置位 |
| §19 Vault 全量同步无警示 | 读确认弹窗 | **已有显式警示**（本轮顺带统一了措辞，见 UA-021） |
| §20 「欢迎回来 undefined」 | 读 `LoginView` / `stores/auth` | **已修**（前序会话） |
| §23 Chat sidebar 不可收缩 | 读实现 | **已正确**：可收缩 + `localStorage` 记忆 |
| §27–29 DRAFT Agent 给错误码 | 读 `AgentService` | **已是人话**：`AGENT_NOT_PUBLISHED` 覆盖为"还是草稿（DRAFT），先测试再发布后才能用于对话" |
| §35 容器卷为空、读不到原文 | 进容器核对 | **不成立**：`/app/data/kb` 173 个库目录 / 92 个文件；**活跃文档 6/6 原文逐条存在** |
| §44 Testcontainers 拖累默认测试 | 读 5 个 `Live*Test` | **已门控**：全部 `@EnabledIfEnvironmentVariable`，默认跳过 |
| §48 危险操作无确认 | 全仓排查 | **覆盖 6 处**：Agent 删除/停用、会话删除、Vault 同步、文档删除、知识库删除 |

### 2.2 确认为真实缺口：4 项（本轮已修）

见第 3 节。

---

## 3. 已修复项 —— PASS / FAIL 矩阵

| 编号 | 级别 | 位置 | 问题 | 修法 | 回归验证 | 结论 |
| --- | --- | --- | --- | --- | --- | --- |
| **UA-018** | **P1** | `ConversationServiceImpl.delete()` | 删会话**不清** `SPRING_AI_CHAT_MEMORY`。记忆是"这轮发给模型多少历史"的唯一来源，id 复用会**凭空记得旧会话** | 走 `chatMemory.clear(ChatMemoryKeys.of(id))` 抽象层清记忆（不在 SQL 重拼 key 前缀） | 新增 `ConversationDeleteClearsMemoryTest` **4/4 PASS** + **运行实例探针 PASS**（植入→删→归零） | **PASS** |
| **UA-019** | P2 | `AgentsView.vue` | 页面把"提示词 + 模型 + 参数"这类**实现结构**讲给用户听 | 改写为按用途描述（"各自的说话方式 / 能查的资料范围 / 能用的工具"） | R3/R4 两轮 `verify-agents` + `verify-agent-chat` PASS | **PASS** |
| **UA-020** | P2 | `AgentView.vue` → `AgentsView.vue` | 评测集入口出现在**普通对话页**（对用户是噪音） | 入口迁到「Agent 管理」页，并补知识库选择器（原实现静默取 `kbs[0]`）；后端 `/eval/kb/{kbId}` 与 API 层原样保留 | 构建旁证：该页 chunk 22.17 kB → **19.79 kB**；R3/R4 双轮 PASS | **PASS** |
| **UA-021** | P2 | `KnowledgeBaseDetailView.vue` | 详情页入口仍叫「上传 Vault」，与列表页「导入资料」不一致 | 统一为「导入文件」/「导入资料包」（后者带整包同步提示） | R3/R4 两轮 `verify-doc-lifecycle` PASS（含 3 处按文案定位的选择器连带修正） | **PASS** |
| **§43** | — | `verify-stream.mjs` | 借 demo 令牌提问，每轮向**受保护账号**写 1 条 `kb_qa_record`，且无回收通道 | 改为**完全自持**：自建账号 + 自建库 + 自埋独有事实；断言 4 → 6 条 | 单独复跑 **EXIT=0**（六条断言全过）+ R3/R4 双轮 PASS | **PASS** |

> UA-018 为什么值得单列：它的危害不是"多几行无主数据"，而是一条**跨会话记忆泄漏路径**。
> 修复必须走 `ChatMemory` 抽象层，因为 key 的构造规则只在 `ChatMemoryKeys` 一处——
> 在删除逻辑里重拼一遍字符串，等于把"key 长什么样"复制成两份，迟早分叉。

---

## 4. 问题统计

| 分类 | 数量 | 说明 |
| --- | --- | --- |
| **P0** | **0** | 无数据丢失 / 越权 / 永久 Loading / 跨用户污染 |
| **核心 P1** | **0** | UA-018 为本轮唯一 P1，已修并双重复核 |
| P2（本轮修复） | 3 | UA-019 / UA-020 / UA-021 |
| 复核确认"已是正确实现" | 11 | 见 2.1 |
| 新增归档（Known Gaps） | 3 | `future-improvements` 第 18 / 19 / 20 条 |
| 工具级残留（老大裁定维持现状） | 3 | 软删墓碑 / ES 索引壳 / 记忆残留 |

---

## 5. 未修复项（Known Gaps）—— 全部为工具级，无一条阻塞用户完整性

| # | 项 | 量化 | 为什么不修 |
| --- | --- | --- | --- |
| 18 | `verify-stream` 重写后带入一个 `addInitScript` 参数误用 | 已修，归档为教训 | 失败位置极具误导性（抛在浏览器启动后、第一条断言前，前五步日志全是成功），值得留档 |
| 19 | `verify-retrieval-ui` 第⑤步固定 20s 超时在 CPU 争用下不够 | 串行 PASS / 并发 FAIL / 隔离 3 连 PASS | 官方 runner 是**串行**的，不触发；正解是改为事件驱动（已写明） |
| 20 | 清理工具回收不到"挂在已软删会话下"的记忆行 | **341 行** | 用户不可见；且 UA-018 修复后**已冻结不再增长**（四轮 E2E + 清理后总数一行未增）。最小修法已写在文档里，**未实施** |
| 16 | 软删墓碑累积 | `kb_document` 137 / `chat_conversation` 305 | 软删设计的固有代价，改物理删会牺牲可回溯性 |
| 9.7 | ES 物理索引壳不回收 | **116 索引 / 115 别名**，仅 3 个指向活跃库 | 属产品删除路径的既有行为，非清理工具职责 |

---

## 6. Cleanup（测试数据清理验证）

清理工具**在容器内运行**：实例的存储根是 `/app/data`（命名卷 `backend-data`），
宿主侧 `D:\develop\Prisma\data` 是另一份——在宿主跑会是
"数据库级联成功、**磁盘原文一个都没删**"的**静默失败**（工具有警告、但退出码仍为 0）。

**dry-run 先行，账目逐项一致：**

| 项 | dry-run 预告 | EXECUTE 实际 | 一致 |
| --- | --- | --- | --- |
| 候选账号 | 34 | **34 已软删** | ✓ |
| 知识库 / 会话 | 0 / 0 | 0 / 0 | ✓ |
| 自建 Agent / 内置 Agent | 4 / 12 | 4 / 12 | ✓ |
| 工作区 / 导入任务 / 问答记录 | 34 / 37 / 8 | 34 / 37 / 8 | ✓ |

> 「知识库 0 / 会话 0」是一条**正面证据**：说明每个 E2E 脚本的 `finally` 自清理真的生效了。

**清理后核验：**

| 检查项 | 结果 |
| --- | --- |
| 活跃账号 | **2 个**：`demo`、`Prisdvl` |
| `demo`（演示数据，硬保护） | **37 会话 / 2 知识库 / 31 问答留档**——与基线逐项一致 |
| `Prisdvl`（**真实用户数据**） | 2 会话 / 1 知识库，**全部存活** |
| **13 类父子关系的物理孤儿** | **全部 0**（切片 / 文档 / 链接 / 标签 / Agent 绑定 ×2 / 工具绑定 / 导入明细 / 问答记录 / 消息 / 记忆 / 工作区 / 角色） |
| 活跃会话 / 活跃知识库 | 39 / 3（= `demo` 37+2 ＋ `Prisdvl` 2+1） |

**记忆表 415 行的构成**（本轮新查明，已归档第 20 条）：挂**活跃**会话 74 行 / 挂**已软删**会话 **341 行** /
父行物理不存在 **0**行。根因是清理工具的遍历条件为 `deleted = 0`（仅活跃会话），软删会话的记忆行结构性收不到。
定性为**冻结的历史沉积**（UA-018 修复后所有删除路径都会连带清记忆），非增长向量。

---

## 7. 测试（数字以实际 surefire 为准，不用旧数字）

```
mvn -B clean test         →  EXIT=0
TOTAL: 621 tests, 0 failures, 0 errors, 22 skipped   （119 个测试类）
```

| 模块 | 用例数 |
| --- | --- |
| `nexus-common` | 19 |
| `nexus-ai` | 66 |
| `nexus-knowledge` | 174 |
| `nexus-app` | **362** |
| **合计** | **621** |

数字变迁：原报告 **603** → 附注一 **617** → **现状 621**（+4 `ConversationDeleteClearsMemoryTest`）。

22 个 skipped 全部为需真实外部服务的 `Live*` 用例（`@EnabledIfEnvironmentVariable` 门控）：
`LiveChunkIndexTest` 9 / `LiveVectorStoreTest` 4 / `ChatStreamLiveTest` 3 / `LiveEmbeddingTest` 2 /
`LiveModelCallTest` 2 / `AgentLoopTest` 1 / `MultiTurnMemoryTest` 1。
**默认跳过，不影响质量门；Testcontainers 仅在 `support/LiveInfrastructure` 按需拉起。**

### E2E：四轮全量执行（§66–67：跑全部脚本、至少两轮、不只看退出码）

| 轮次 | 结果 | 失败项与定性 |
| --- | --- | --- |
| Round 1 | 15 PASS / **1 FAIL** / 0 SKIP | `verify-stream`：`addInitScript(fn, a, b)` **参数误用**（Playwright 只收一个参数）。**脚本自身缺陷**，非产品缺陷 |
| Round 2 | 15 PASS / **1 FAIL** / 0 SKIP | `verify-retrieval-ui`：第⑤步固定 20s 超时被吃光。**我并发跑另一个浏览器脚本引入的 CPU 争用**（官方 runner 是串行的） |
| **Round 3** | **16 PASS / 0 FAIL / 0 SKIP** | 修复后，串行 |
| **Round 4** | **16 PASS / 0 FAIL / 0 SKIP** | 修复后，串行 —— **连续两轮全绿** |

补充证据：`verify-retrieval-ui` 无并发条件下**隔离连跑 3 次全 PASS**；
`verify-stream` 修复后单独复跑 **EXIT=0**，六条断言全过。

> **这恰恰证明"至少两轮"这条要求是有牙齿的。** 单跑一轮，`addInitScript` 的误用会被误读为
> "产品在这一步挂了"——因为异常抛在浏览器启动之后、第一条断言之前，而前五步
> （注册 / 建库 / 导入 / 索引 / 前置检索）日志全是成功。**改动过的脚本必须放进完整轮次复跑。**

---

## 8. Docker 与运行实例

**质量门全 6 步 EXIT=0**：

```
docker compose stop   →  0
mvn -B clean test     →  0     （621 / 0 / 0 / 22）
mvn -B -q package -DskipTests  →  0
npm run build         →  0
docker compose build backend frontend  →  0
docker compose up -d  →  0
```

**8 个容器全部 `Up` 且 `healthy`**；网关 `/` → 200；`/api/auth/login` → 200。

**部署漂移核验**（Dockerfile 只 `COPY` 预构建 jar，所以"`mvn package` 成功"≠"运行实例已更新"）：

| 证据 | 值 |
| --- | --- |
| 镜像构建时间 | **20:29**（晚于 jar） |
| 仓库产物 | `nexus-app-0.0.1-SNAPSHOT.jar` = **182,155,216 字节 / 20:28** |
| 容器内 `/app/app.jar` | **182,155,216 字节 / 20:28**（字节级一致） |

**本次无部署漂移。**

---

## 9. Git 安全状态

| 项 | 状态 |
| --- | --- |
| 分支 / HEAD | `platform-phase3` / `f2a18dc`（未变） |
| commit / push / tag | **均未执行** |
| `docs/update.txt` | `git status` 显示 ` M`，但 **mtime = 15:45:21**，早于本会话施工窗口（20:28 起）；内容是**任务书自身被替换**——由老大在 15:45 换上，**非本会话改动**，且**未做任何回退** |
| 破坏性 git 操作 | 未使用 `restore` / `checkout --` / `reset --hard` / `clean -fd` |
| 工作区 | 52 modified（含 E2E 自动重写的 30 个截图）＋ 22 untracked；**无本会话新建的仓库文件**（探针与 runner 脚本均落在 `/tmp`） |

---

## 10. 最终判断

**结论：达到 Final v1.0，可以作为真实软件长期使用。**

判断依据（三项，均可复核）：

1. **主线在"新用户零文档"条件下无阻断**：注册 → 空态 → 导入 → 可检索 → 问答 → 引用 → Agent → 历史，
   全程真实浏览器驱动、逐条 DOM 断言，**连续两轮 16/16 全绿**。
2. **P0 = 0、核心 P1 = 0**。本轮唯一 P1（UA-018）已修，且有**双重复核**——
   单元测试（4/4）＋ 运行实例探针（植入 → 删除 → 归零），后者能覆盖单元测试覆盖不到的部署漂移。
3. **数据边界干净**：真实用户 `Prisdvl` 全存活，演示账号 `demo` 与基线逐项一致，
   13 类父子关系物理孤儿全为 0，清理账目 dry-run 与 EXECUTE 逐项一致。

**边界条件（如实声明）**：

- 残留的 3 类工具级项（软删墓碑 / ES 索引壳 / 341 行记忆残留）**用户不可见**，
  其中记忆残留已冻结不再增长；**均未修，属老大本轮明确裁定的"维持现状 + 文档记录"**；
- 第 20 条的最小修法已写在 `docs/future-improvements.md`，**未实施**——它需要配套改
  `TestDataCleanupIntegrationTest` 才有回归网，属于下一轮的工作，不是"漏做"；
- E2E 在**并发**条件下会命中第 19 条（官方 runner 串行，不触发）。调试时需知悉。

---

## 最后一问：现在这个项目作为真实软件，是否已经可以长期正常使用？

**可以。**

不是"技术上跑得通"，而是逐条对照真实软件的三个判据：

| 判据 | 证据 |
| --- | --- |
| **新用户能自己走完主流程** | `verify-real-user-flow` 17/17：注册 → 干净空态 → 导入 → **立即可检索** → 重登仍在 |
| **出错时给人话、不给人看堆栈** | 草稿 Agent → "还是草稿，先发布"；零命中 → "资料中没有相关内容"；全部检索失败 → "有知识库暂时无法检索…请稍后重试" |
| **数据不会悄悄坏掉** | 引用刷新后仍在；历史会话完整恢复；`Prisdvl` 真实数据在全量清理后逐项存活 |

**长期使用**这一点上，唯一需要留着盯的不是产品，而是**测试与运维的口径**：
E2E 跑之前要确认实例是新构建的（部署漂移坑），清理要在容器内跑（存储根一致性），
E2E 不要并发跑。这三条都已写进文档，且都有工具侧的显式提示或警告。

```text
PASS：可以作为真实软件长期使用。
```

---

*报告依据：`docs/user-acceptance-issues.md`（UA-018 ~ UA-021）、`docs/future-improvements.md`（19 项归档）、
`docs/final-acceptance-report.md`（含附注二）、`docs/deployment.md` §9.8、
surefire 报告（`*-target/surefire-reports/TEST-*.xml`，2026-10-03）、
16 个 `web/scripts/verify-*.mjs` 四轮真机执行记录、`docker ps` 与容器内字节级核验。*
