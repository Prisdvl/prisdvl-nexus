# Final v1.0 Cleanup Report

> 项目：**prisdvl-nexus v1.0**　分支：`platform-phase3`　报告时间：2026-10-03 18:5x
> 依据：`docs/update.txt`（16 节任务书）§十三 最终真实验收 / §十六 报告格式
> 详细证据：`docs/deployment.md` §9.4–§9.7　`docs/future-improvements.md` 第 10–17 条

---

## 1. Cleanup Utility

**形态：独立 CLI（boot-jar-as-CLI），不是 HTTP 接口。**

| 组成 | 路径 | 说明 |
| --- | --- | --- |
| 配置 | `nexus-app/.../tools/cleanup/CleanupProperties.java` | 前缀白名单 / 保护账号 / `execute` 默认 false |
| 计划与执行 | `.../TestDataCleanupService.java` | 两阶段：`plan()` 只读 → `purge(plan)` 写入 |
| 入口 | `.../CleanupRunner.java` | `ApplicationRunner`，退出码 0/1/2 |
| Profile | `nexus-app/src/main/resources/application-cleanup.yml` | `web-application-type: none` |
| 包装脚本 | `scripts/cleanup-test-data.ps1` | 探测 JDK 21、`-am clean package`、传 `--nexus.cleanup.execute` |

```bash
# dry-run（默认，只读，绝对不删）
java -jar nexus-app/target/nexus-app-0.0.1-SNAPSHOT.jar --spring.profiles.active=local,cleanup
# 真实执行（必须显式二次确认）
... --nexus.cleanup.execute=true
```

**为什么不是接口**：清理对象是**凭证已丢失**的一次性测试账号——走 HTTP 就得先有 token，
恰恰是这些账号没有的东西。因此它必须越过账号体系直接作用于存储。为了不让这种越权能力
变成产品的一部分：**没有任何 Controller / 只在 `cleanup` profile 装配 / nginx 不暴露 /
默认只读 / 真删必须显式传参**。全仓确认：未新增任何形如 `/api/admin/delete-user` 的端点。

**退出码语义**：`0` 成功｜`1` 部分账号失败（逐账号列出 账号+原因）｜`2` 致命（保护账号被前缀命中即 fail-closed 中止）。

## 2. 清理规则

**按用户名前缀白名单识别，不使用黑名单。**

```
ua-, probe, smoke, fmt-, sec-check-, multi-kb-other-, err, keyok-, kbtest_,
vault_, sync, async_, uicheck_, nokb_probe, policy-check-, mcp-live-, upload-limit-
```

- 17 条前缀**不是拍脑袋写的**：逐条对照仓库里所有 `verify-*.mjs` 与历史验收脚本
  实际使用的用户名生成规则得出，并另有 `CleanupPropertiesTest` 把**实测残留的 78 个账号名
  逐字写进测试**做回归（`hasSize(78)` 守卫）。
- **空前缀被显式忽略**：`startsWith("")` 会匹配全库，等于把作用域放大到全表。
- 白名单而非黑名单是刻意的失败模式选择：**不确定时偏向"少删"**。

## 3. 保护规则

| 机制 | 说明 |
| --- | --- |
| 硬保护账号 | `Prisdvl`（真实数据）、`demo`（演示数据）——`plan()` 第一步就 `continue` 跳过 |
| **fail-closed 守卫** | `assertProtectedNotMatched()`：若任一保护账号被前缀命中（例如把前缀误配成 `Pris`），**抛异常整体中止**（退出码 2），而不是静默跳过 |
| 非白名单一律不动 | 不匹配任何前缀的账号永远不进候选，`zhangsan` / `neo` 等实测不在计划里 |
| 规则不明则停 | 候选账号清单在 dry-run 中**全量打印**（用户名 + 六类资源计数），由人复核后才执行 |

## 4. Dry Run 结果

`plan()` 严格只读 —— **dry-run 是"结构上不可能写数据"，而不是"靠一个 if 保证不写"**
（集成测试 `planIsStructurallyReadOnly` 断言跑完 plan 后账号/库/文档/切片全部原样）。

```text
候选账号 7 个：
  username                             kb   conv  agent   bAgt     ws   task     qa
  sec-check-mus9ogl4                    0      0      0      1      1      0      0
  ua-1791024399221                      0      0      0      1      1      1      0
  fmt-1791024428957                     0      0      0      0      1      5      0
  smoke-1791024442068                   0      0      0      0      1      2      1
  smoke-other-1791024442068             0      0      0      0      1      0      0
  multi-kb-other-1791024455989          0      0      0      0      1      0      0
  probe-nokb-mus9rnq0                   0      0      1      1      1      0      0

合计将删除：账号 7；内置Agent 3；知识库 0；导入任务 8；会话 0；工作区 7；问答记录 1；自建Agent 1
无主导入任务明细：0 行
```

**关键证据：第二轮完整 E2E 之后的残留，与第一轮形状完全一致**（同样 7 个账号 /
8 条导入任务 / 7 个工作区 / 4 个 Agent / 1 条问答）——每轮残留**有界且可复现**。

> 本轮修掉一个 dry-run 缺陷：原先 `AccountSnapshot` 只统计 `builtin = 0` 的自建 Agent，
> 而 `purgeBuiltinAgents` 默认 true，于是报"自建Agent 1"、**实际删掉 4 个**。
> 现已把内置 Agent 纳入计划（新增 `bAgt` 列 + `CleanupPlan.totalBuiltinAgents()` + summary 键），
> 并补回归测试 `planReportsBuiltinAgentsToo`。**报什么、删什么，逐项一致。**

## 5. 实际清理结果

**三轮真实执行，全部收口到退出码 0。**

| 轮次 | 时间 | 退出码 | 结果 |
| --- | --- | --- | --- |
| ① | 17:2x | **1** | 77/78 账号成功，`vault_48723` 触发 NPE → 暴露**缺陷 A** |
| ② | 17:3x | **0** | 补齐第 78 个账号；随后回收 69 行无主明细 → 暴露**缺陷 B** |
| ③ | 18:36 | **0** | 第二轮 EXECUTE：7 账号 / 4 Agent / 7 工作区 / 8 导入任务 |
| ④ | 18:50 | **0** | 第三轮（E2E 后）：7 账号 / 4 Agent / 7 工作区 / 8 导入任务（明细 8 条同时删除） |

**第一轮总账（MySQL，清理前 → 清理后）**

```text
sys_user 活跃   80 →  2     会话      61 → 37     工作区      73 →  2
知识库          13 →  3     消息     126 → 76     导入任务   186 → 137
文档            19 →  6     自建Agent 13 →  1     问答记录    35 → 28
切片            25 → 11     会话记忆 394 → 350
```

**第四轮（最终）后状态**：活跃账号 **2 个**（`demo` + `Prisdvl`），
`kb = 3`、可见文档 `6`、切片 `11`、可见会话 `39`、消息 `80`、可见 Agent `4`、工作区 `2`。

**安全网**：清理前已 `mysqldump` 全库导出（库仅 1.7 MB → dump 756 KB）：
`D:\develop\nexus-cleanup-backup\nexus-before-cleanup-20261003-171643.sql`。

## 6. 级联验证

**原则：一律复用既有业务级联，绝不裸删。** 全仓确认无 `DELETE FROM kb_knowledge_base`
这类语句；知识库删除一律走 `KnowledgeBaseService.delete`（切片 / 关联边 / 标签 /
pgvector 向量 / ES 索引 / 磁盘原文），会话走 `ConversationService.delete`，
Agent 走 `AgentService.delete`。

工具只补了三处**服务层确实没有**的能力：`kb_import_task` / `kb_qa_record` /
`SPRING_AI_CHAT_MEMORY`，以及"删账号 / 删工作区"（产品无此接口）。

**清理后孤儿扫描（10 类父子关系，物理层面）**

| 检查 | 结果 |
| --- | --- |
| 切片 / 文档 / 关联边 / 标签 / 消息 / 会话记忆 / Agent 绑定×2 / 导入明细 / 问答记录 的父行不存在 | **全部 0**（合计 0） |
| 已软删知识库名下的切片 | 0 |
| ES 物理索引中指向活跃知识库 | 3 / 71 |
| pgvector `kb_vector` | 11（与基线一致） |
| 磁盘原文 | 68 个文件（首轮 78 → 删掉 10 个） |

**首轮 EXECUTE 暴露并修掉的工具级缺陷（缺陷 B）**：导入明细的删除条件写成了
`kb_id IN (SELECT id FROM kb_import_task WHERE user_id = ?)` —— 子查询取的是任务主键、
却拿去比 `kb_id` 列，两个 id 空间永不相等，**"清理明细"这一步从未生效**
（真机残留 69 行父任务已不存在的无主明细）。已改为 `task_id`，并新增
`reclaimOrphanImportTaskItems()` 做最终一致性回收（该步骤必须放在"候选账号为 0 就返回"**之前**，
否则某轮候选为 0 时这些谁都枚举不到的残留永远等不到回收——真机第二次才暴露这个顺序问题）。
**第四轮日志可见 `导入任务 5 条（明细 5 条）`，明细随父任务一起被删，修复生效。**

## 7. Prisdvl 保护验证

**清理前 → 清理后逐项对比（保护账号零变化）**

| 账号 | kb | 文档 | 切片 | 会话 | 消息 | Agent | 工作区 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `Prisdvl`（清理前） | 1 | 0 | 0 | 0 | 0 | 1 | 1 |
| `Prisdvl`（清理后） | 1 | 0 | 0 | 0 | 0 | 1 | 1 |

**更强的一条证据**：第四轮 EXECUTE 于 **18:36:23** 执行，而 `Prisdvl` 名下有 2 个会话
（标题「Java 里主要讲了什么？」，AGENT 模式）与 1 个已发布 Agent，创建时间是 **18:19–18:24**
—— 也就是**真实用户正在使用本系统**。它们**全部存活**。
这比"与 17:43 的旧基线比对"更有说服力：它证明工具尊重**基线之后新增的**真实数据，
而不只是"恰好没动到某个快照"。

> 注：最终盘点时 `Prisdvl` 为 kb=1 / 文档 0 / 切片 0 / 会话 2 / 消息 4 / Agent 2 / 工作区 1。
> 相比首轮基线多出的 2 会话 / 4 消息 / 1 Agent **全部来自真实用户 18:19–18:24 的手动操作**，
> 与清理工具无关；其中 2 个"默认助手 副本"是用户自己创建后又软删的草稿。

## 8. Demo 保护验证

| 维度 | 基线 | 首轮清理后 | 第二轮 E2E 后 | 最终 |
| --- | --- | --- | --- | --- |
| 知识库 | 2 | 2 | 2 | **2** |
| 文档 | 6 | 6 | 6 | **6** |
| 切片 | 11 | 11 | 11 | **11** |
| 会话 | 37 | 37 | 37 | **37** |
| 消息 | 76 | 76 | 76 | **76** |
| Agent | 2 | 2 | 2 | **2** |
| 工作区 | 1 | 1 | 1 | **1** |

**可见数据逐项零变化。** 但本轮**发现 demo 名下有三类不可见残留会随 E2E 增长**，
已定位并把其中最大一类修掉：

| 残留 | 每轮增长 | 处置 |
| --- | --- | --- |
| 会话（`chat_conversation`） | +2（agent-chat）+5~6（multi-kb-failure） | **已修**：跑前快照 + 差集删除 |
| `kb_import_task` / `_item` | +8~15 | **未修**：产品无删任务接口，且任务属 demo 不可例外清理 |
| `kb_qa_record` | +1（`verify-stream` 走知识库「问答」页） | **未修**：产品无删留档接口 |
| `SPRING_AI_CHAT_MEMORY` | +~20（会话被删但记忆行留下） | **未修**：根因是产品级级联缺口（见 §13） |

**会话泄漏的修复**（这是本轮最关键的一处 P1）：
`verify-agent-chat` 与 `verify-multi-kb-failure` 用 demo 令牌建会话却不收尾，
而 demo 受硬保护、`cleanup-test-data` 永远回收不到 —— 实测**一轮 E2E 让 demo 会话 37 → 45**。
修法：`web/scripts/lib/demo-session.mjs` 新增 `listConversationIds()` +
`deleteConversationsCreatedSince()`，**跑前快照 + 差集删除**（只删本轮新建的）。
**绝不能用"清空本账号全部会话"收尾**，那会把演示数据一起删掉，比残留更严重。
修复后重跑：agent-chat 删 2 / multi-kb-failure 删 6，快照恒为 37；
**第二轮完整 E2E 之后 demo 仍是 37 会话 / 76 消息**。

## 9. E2E 污染验证

**16 个 `verify-*.mjs` 两轮全量重跑，均 16 PASS / 0 FAIL。**

| 轮次 | 时间 | 结果 | 最慢项 |
| --- | --- | --- | --- |
| 第一轮 | 17:45–17:50 | **16 PASS / 0 FAIL** | `verify-multi-kb-failure` 68s |
| 第二轮（最终产物） | 18:46–18:49 | **16 PASS / 0 FAIL** | `verify-multi-kb-failure` 30s |

**逐项改造结果**：

| 任务书 §十二 要求 | 落地情况 |
| --- | --- |
| 唯一测试账号 | 各脚本自建带时间戳前缀账号（`ua-` / `smoke-` / `fmt-` / `sec-check-` / `probe-nokb-` / `multi-kb-other-`） |
| 结束自动清理 | `finally` 中删自己建的会话/知识库/Agent；本轮新增 3 个脚本的会话收尾 |
| `finally` 必执行 | 全部脚本的清理都在 `finally` 内，且在浏览器关闭之后、断言统计之前 |
| token 失效自动重登 | 8 个脚本改用 `lib/demo-session.mjs` 的 `demoToken()`，**每次现登录换新令牌**，不再依赖 30 分钟寿命的 `web/.demo-token` |
| `API_BASE` 统一 | 三处互不兼容的口径统一为"**永远指向网关根** `http://localhost:8088`，`/api` 由脚本自己拼" |
| 不依赖 demo 用户 | **部分**：`verify-agent-no-kb` / `verify-full-product` 完全自建；`verify-stream` **仍依赖 demo 的知识库**（见 §13），其余 demo 类脚本已能自清理 |
| 不依赖固定 DB 状态 | 断言基于"本次自建资源"与"相对增量"（如 `retrieval.kb.failed` 计数 `before → after`），不写死绝对条数 |
| 不依赖人工删除 | 账号级残留交给 `cleanup-test-data`；脚本不再留下"需要人手动收拾"的东西 |

**每轮残留形状可复现**（第一轮与第二轮 dry-run 输出一致），是"可无限重复运行"的直接证据。

## 10. 测试结果

```text
mvn -B clean test         → BUILD SUCCESS，EXIT=0
mvn -B package -DskipTests → EXIT=0（nexus-app-0.0.1-SNAPSHOT.jar，182,155,061 bytes）
cd web && npm run build    → EXIT=0（✓ built in 9.49s）
```

| 模块 | 用例 | failures | errors | skipped |
| --- | --- | --- | --- | --- |
| `nexus-common` | 19 | 0 | 0 | 0 |
| `nexus-ai` | 66 | 0 | 0 | 0 |
| `nexus-knowledge` | 174 | 0 | 0 | 0 |
| `nexus-app` | 358 | 0 | 0 | 22 |
| **合计** | **617** | **0** | **0** | **22** |

22 个 skipped 全部是需真实外部服务的 `Live*` 用例（设计如此）。

**清理工具专属测试 13 项全绿**：

| 测试类 | 数量 | 覆盖任务书 §十 的哪几类 |
| --- | --- | --- |
| `CleanupPropertiesTest` | 6 | ② 正常匹配 / ③ demo 不删 / ④ Prisdvl 不删 / ⑤ 非测试账号不删（+ 空前缀忽略 / 默认只读） |
| `TestDataCleanupIntegrationTest` | 7 | ① dry-run / ①b 账目完整 / ②⑥ 正常清理+完整级联 / ⑦ 幂等 / ③④⑤ 保护 / ⑧ 部分外部资源失败有明确报告 / ⑨ 无主明细回收 |

另有 1 项产品级回归：`KnowledgeBaseDeletePurgeTest.deletingKnowledgeBaseWithNullStoredPathSucceeds`。

集成测试跑在 `test` profile（H2 + MySQL 方言 Flyway 迁移，表结构与生产一致），
**跟着 `mvn test` 常驻回归，不需要 Docker**。

## 11. Docker 结果

**8 个服务全部 healthy。**

```text
nexus-frontend       Up (healthy)   0.0.0.0:8088->80/tcp     ← 唯一对外入口
nexus-backend        Up (healthy)   8080/tcp（仅内网）
nexus-mysql          Up (healthy)   127.0.0.1:3307->3306/tcp
nexus-postgres       Up (healthy)   127.0.0.1:5433->5432/tcp
nexus-elasticsearch  Up (healthy)   127.0.0.1:9200->9200/tcp
nexus-redis          Up (healthy)   127.0.0.1:6379->6379/tcp
nexus-rabbitmq       Up (healthy)   127.0.0.1:5672/15672
nexus-ollama         Up (healthy)   127.0.0.1:11434->11434/tcp
```

网关 `GET /` = 200，`POST /api/auth/login` = 200。

**本轮顺带修掉一处部署漂移**：`nexus-backend:0.0.1` 镜像原先构建于 **11:24**，
而 `KnowledgeBaseServiceImpl` 的 NPE 修复在 **17:19** —— 即**运行中的实例仍带
"删含 Vault 文档的知识库会 500"的缺陷**（前端镜像 14:06 晚于其全部源码，是最新的）。
已重建后端镜像并重启，容器内 `/app/app.jar` 时间戳与新产物一致（18:35 / 182,155,061 bytes）。
教训写在 `docs/future-improvements.md` 第 15 条：Dockerfile 只 `COPY` 预构建 jar，
**"`mvn package` 成功"不等于"运行实例已更新"**。

## 12. Git 修改

工作区共 **68 项**改动（未提交）。分支 `platform-phase3`，最新提交 `f2a18dc`。

**新增（20 项，核心是清理能力）**

```text
nexus-app/src/main/java/com/lky/nexus/app/tools/     ← 清理工具三件套（CleanupProperties / TestDataCleanupService / CleanupRunner）
nexus-app/src/test/java/com/lky/nexus/app/tools/     ← CleanupPropertiesTest / TestDataCleanupIntegrationTest
nexus-app/src/main/resources/application-cleanup.yml ← cleanup profile（无 Web 容器）
scripts/cleanup-test-data.ps1                        ← CLI 包装脚本
web/scripts/lib/demo-session.mjs                     ← E2E 公共会话/地址工具（含差集收尾）
web/scripts/verify-import-formats.mjs / verify-real-user-flow.mjs / verify-responsive.mjs / verify-stop.mjs
docs/final-acceptance-report.md / docs/future-improvements.md / docs/user-acceptance-issues.md
docs/screenshots/（9 个新截图）
```

**修改（28 项源码/文档 + 20 项截图）**

| 类别 | 文件 |
| --- | --- |
| 产品代码（1） | `nexus-knowledge/.../KnowledgeBaseServiceImpl.java`（NPE 修复） |
| 测试（1） | `nexus-app/. .. /KnowledgeBaseDeletePurgeTest.java`（+1 回归） |
| E2E 脚本（12） | `verify-agent-chat` / `verify-agent-no-kb` / `verify-agents` / `verify-doc-lifecycle` / `verify-frontend` / `verify-full-product` / `verify-login` / `verify-multi-kb` / `verify-multi-kb-failure` / `verify-retrieval-ui` / `verify-security` / `verify-stream`（+ `debug-click` / `screenshot`） |
| 前端（8） | `api/agent.ts` / `api/knowledge.ts` / `stores/auth.ts` / `views/*.vue` ×5 |
| 文档（3） | `docs/deployment.md`（§9.4 / §9.6 / §9.7） / `docs/api.md` / `docs/update.txt` |
| 其他（1） | `.gitignore` |

`docs/update.txt` **未执行任何提交**；改动**尚未 commit**（等你的指令）。

## 13. Remaining Known Gaps

**本轮新发现 4 个缺陷：3 个已修，1 个明确未修。**

| # | 缺陷 | 级别 | 状态 |
| --- | --- | --- | --- |
| A | `KnowledgeBaseServiceImpl.delete` 收集磁盘文件时的 **NPE**：只 select 一列且该列为 NULL（Vault 导入文档无落盘文件），MyBatis 把整行映射成 `null` 元素 → 方法引用 NPE。后果是**用户自己点"删除知识库"会 500** | **产品级 P1** | **已修** + 回归测试 |
| B | 工具导入明细关联键写成 `kb_id`（应为 `task_id`），该步**从未生效**，残留 69 行无主明细 | 工具级 | **已修** + 孤儿回收 + 测试 |
| C | E2E 往**受保护账号 demo** 持续灌会话（37 → 45/轮），且无任何回收通道 | 测试基建 P1 | **已修**（快照+差集，3 个脚本接入） |
| D | dry-run 漏报会被删的内置 Agent（报 1、实删 4） | 工具级 | **已修** + 测试 |

**未修（需你决策）**

1. **`ConversationServiceImpl.delete` 不清理 `SPRING_AI_CHAT_MEMORY`（产品级级联缺口）**
   —— **这是本轮最重要的未修项**。会话经 API 删除后，其记忆行永久留下。
   后果有两个：①**真实用户每删一个会话就留一份记忆行**（不可见，但单调增长）；
   ②清理工具只清"它自己删的那些会话"的记忆，**已被脚本/用户软删的会话清不到**。
   实测该表 415 行中，demo 392 行、`Prisdvl` 3 行、**10 个测试账号各留 2 行**（共 20）。
   同时也是 demo 记忆从 350 → 392 的根因。
   修法很小：在 `delete()` 里 `messageMapper.delete(...)` 之后追加一行清
   `SPRING_AI_CHAT_MEMORY WHERE conversation_id = 'conv:' + id`（工具里已有同款写法）。
   **未改的理由**：属产品公共删除路径的行为变更，超出本任务书"不再扩大改动面"的边界。
2. **`verify-stream.mjs` 仍向 demo 写问答留档**（1 行/轮）。
   已查明**全仓不存在删除 `kb_qa_record` 的接口**，只能裸 SQL 或给保护账号开例外，两条都不该做。
   正解是让它改用自建账号 + 自建知识库（顺便满足 §十二「不依赖 demo 用户」），
   属脚本级重构，未在本轮实施。详见 `docs/future-improvements.md` 第 17 条。
3. **`kb_import_task` / `kb_import_task_item` 在 demo 名下累积**（154 / 153 行，+8~15 轮）：
   产品无删任务接口，demo 不可例外清理。用户不可见。
4. **ES 物理索引与别名不回收**：删库只做 delete-by-query。实测 **71 个索引**，
   其中**仅 3 个指向活跃知识库**；一轮完整 E2E 新增约 **10~14 个空索引**。
   数据不脏（`docs.count` 为 0），但索引壳单调累积。最小修复是
   `ElasticsearchChunkIndex.deleteByKnowledgeBase` 追加"删别名 + 删物理索引"，
   **属产品删除路径行为变更，未实施**。
5. **软删墓碑累积**：`kb_document` 墓碑 137 行、`chat_conversation` 墓碑 42 行
   （`deleted = 1`，用户不可见，也不构成孤儿）。软删设计的固有代价，不建议为测试便利改掉。
6. **容器化部署读不到原始文件**：78 个原文在宿主 `./data/kb`，容器 `nexus_backend-data` 卷为空
   （检索不受影响，"下载原文"会失败）。
7. **测试/生产逻辑删除口径不一致**：`application-test.yml` 配了全局 `logic-delete-field`，
   生产没有；`WorkspacePO` 未标 `@TableLogic`，同一个 `deleteById` 在测试里写墓碑、生产里真删。
8. 前端 2 个 chunk > 500 kB（构建警告，非错误）。

## 14. 是否达到 Final v1.0

**是。** 按任务书 §十四 的五项停止条件逐条对照：

| 停止条件 | 实测结论 |
| --- | --- |
| `Prisdvl` 真实数据：保留 | ✅ 逐项零变化；**且真实用户 18:19–18:24 新建的数据也完整存活** |
| `demo` 数据：保留 | ✅ 知识库/文档/切片/会话/消息/Agent/工作区**逐项零变化** |
| 测试数据：可自动清理 | ✅ CLI 一条命令清干净：78 → 2 个活跃账号；`Prisdvl` / `demo` 不可误删 |
| E2E：可重复执行 | ✅ **两轮全量重跑 16 PASS / 0 FAIL**，且每轮残留形状完全一致 |
| 重复执行：不会无限污染数据库 | ✅ **可见数据不增长**（每轮清理后 `deleted = 0` 口径逐项回到基线）；⬜ **不可见残留仍会缓慢累积**（ES 索引壳 / demo 的导入任务与问答留档 / 会话记忆 / 软删墓碑），已在 §13 逐项量化 |

结论：**主线全部达成**，不再新增普通用户功能、不新开 Phase、不增加新基础设施。
唯一的保留意见写在下面第二个问题的回答里。

---

## 真实用户现在是否可以长期正常使用？

**可以，但需要重建一次镜像（本轮已做）。**

1. **正常使用链路完整**：注册 → 登录 → 建知识库 → 导入（含异步）→ 向量化 → 建索引 →
   RAG 问答 → 引用 → Agent（绑定/工具/温度/试跑/发布）→ 多库 → 流式 → 停止生成 → 历史回看，
   全部有真机 E2E 证据（16 个脚本，两轮全绿）。本轮真机观察到的
   "真实用户手动提问 → 会话落库 → Agent 副本发布"也一次走通。
2. **本轮修掉了一个直接影响真实用户的产品级缺陷**：含 Vault 导入文档的知识库
   （`stored_path` 为 NULL）**原先删不掉、点删除就 500**。已修并有回归测试。
3. **部署层面必须注意**：Dockerfile 只 `COPY` 预构建 jar，所以**"`mvn package` 成功"
   不等于"运行实例已更新"**。本轮已重建 `nexus-backend:0.0.1` 并重启
   （容器内 jar 18:35 / 182,155,061 bytes），运行实例确认带修复。**以后每次改后端代码，
   发布流程必须显式串上 `docker compose build <service>` + `up -d`。**
4. 仍存在的真实用户可感知限制：容器化部署下"下载原文"会失败（原文在宿主 `./data/kb`，
   容器卷为空），这是既存部署不一致，不影响检索与问答。

## 测试系统现在是否可以无限重复运行而不持续污染数据？

**可以 —— 在"用户可见数据"这个口径上完全成立；但在"存储行数"口径上仍有 4 类不可见残留会缓慢累积，需要你知道。**

**成立的部分（强证据）**：
- 两轮完整 E2E 各 16 PASS / 0 FAIL；
- 每轮结束后的 dry-run 残留**形状完全一致**（7 账号 / 8 导入任务 / 7 工作区 / 4 Agent / 1 问答）——
  说明每轮污染是**有界且可复现**的，不是滚雪球；
- 一条 `cleanup-test-data --execute` 就能把这批残留清干净，清理后
  `kb / 可见文档 / 切片 / 可见会话 / 可见 Agent / 工作区` **逐项回到基线**；
- `Prisdvl` 与 `demo` 的**可见数据**在全部四轮清理 + 两轮 E2E 之后**逐项零变化**；
- 本轮把最大的一处泄漏（E2E 往 demo 灌会话，37 → 45/轮）**彻底修掉**，
  修复后第二轮 E2E 跑完 demo 仍是 37 会话 / 76 消息。

**不成立的部分（不可见，但会单调增长）**：

| 残留 | 增长速率 | 性质 |
| --- | --- | --- |
| ES 物理索引壳 | +10~14 / 轮 | 产品删除路径既有行为（只 delete-by-query） |
| demo 名下 `kb_import_task` | +8~15 / 轮 | 产品无删任务接口，demo 不可例外清理 |
| `SPRING_AI_CHAT_MEMORY` | +~20 / 轮 | **产品级级联缺口**：会话删除不清记忆（§13 第 1 条） |
| demo 名下 `kb_qa_record` | +1 / 轮 | 产品无删留档接口；正解是让脚本不依赖 demo |
| 软删墓碑（文档/会话/账号/Agent） | 与创建量同阶 | 软删设计的固有代价 |

**一句话结论**：**"测试跑完不会留下任何用户可见的垃圾"这条已经真正做到并有两轮实测背书；
"存储行数永不增长"没有做到，且其中 `SPRING_AI_CHAT_MEMORY` 一条是产品缺陷
（真实用户删会话也会中招），修它只需一行代码，但要你点头才动。**
