# Future Improvements

本阶段（v1.0 验收）**刻意不做**的事项。它们不影响核心使用链路，
按"产品完善优先于技术堆砌"的原则归档在这里，而不是继续开 Phase。

判定标准：不构成 P0/P1，也不阻塞"新用户不看文档就能走完
注册 → 导入资料 → 问答 → 引用 → Agent → 历史"这条主线。

---

## 体验类

1. **UA-011　欢迎区示例只有一条**
   有知识库但无会话时，示例问题由首个知识库名生成一条。可扩展为按多个库分别生成
   （`《A》里讲了什么？` / `《B》和《A》有什么差别？`），但需要保证每条都真的能命中用户的资料，
   否则就是 UA-003 那个坑的变体（伪造的已有内容）。

2. **UA-012　全局导航侧栏不可收缩**
   本次只做了对话页的会话侧栏（UA-007）。左侧全局导航仍是固定 244px。
   若要做，应收敛到 App.vue 一处状态，避免两个侧栏各自记忆、行为不一致。

3. **UA-013　多文件上传的逐项进度**
   当前 `importTasks` 按知识库单槽，多选上传时显示"正在处理的那一个"。
   要做逐项明细需要把前端状态从 `Record<kbId, Task>` 改为 `Record<kbId, TaskItem[]>`，
   并让 UI 呈现一行一个文件（等待/上传中/解析中/完成/失败 + 重试）。
   后端 `GET /import-tasks/{id}/items` 已具备逐文件明细能力，主要是前端改造。

4. **响应式 1366×768 复核**
   本次验证覆盖桌面视口。窄屏（<900px）已有侧栏收成图标条的降级，
   但对话页 + 输入框 + 弹窗在 1366×768 下的实际留白尚未逐页截图确认。

## 工程类

5. **前端首包体积**
   `index-*.js` 约 1.13 MB（gzip 373 KB），`markdown-*.js` 约 1.06 MB（gzip 340 KB）。
   主要由 Element Plus 全量引入与 highlight.js 全语言包构成。
   收益明确但**不属于本次目标**，需要按需引入 + 语言子集化，涉及面较广。

6. **E2E 用例的清理策略**
   `verify-real-user-flow.mjs` 会创建一次性账号与知识库。当前依赖"唯一用户名"避免污染，
   未做运行后的数据回收。若要长期跑，应补一段按用户名前缀清理的收尾逻辑
   （注意：清理属于破坏性操作，必须在确认作用域后才执行）。

7. **`mvn test` 与 Docker 整栈的内存冲突**
   本地跑 `mvn test` 期间 Mockito/ByteBuddy 可能因内存压力 attach 失败，
   需要先 `docker compose stop`。属于环境编排问题，不是代码缺陷。

8. **UA-017　用户中止后后端误报"未预期异常"**
   前端 `stop()` 先调停止接口、再 abort 本地连接，服务端 `stopAndFinalize()`
   与客户端断开几乎同时发生；此时它仍会 `send(done 帧)` 写到已关闭的响应上，
   抛 `AsyncRequestNotUsableException`，被 `GlobalExceptionHandler` 记为 ERROR，
   并让"对话失败次数"指标 +1。**对用户无影响**（客户端已经走了），
   但会让日志与指标失真。最小修复：`send()` 内捕获 `IOException` /
   `AsyncRequestNotUsableException` 降级为 WARN，或推送前判断响应是否仍可用。
   本次未实施：为一条 P3 重建后端镜像不划算，且改动落在 SSH 推送主链路上。

9. **给"流式真的在流"补一条常驻回归**
   UA-016（对话页流式假死）此前无人发现，原因是既有脚本要么测 RAG 页
   （`verify-stream.mjs` 用 `streamedText.value +=` 天然正确），要么只断言**最终**内容出现
   （`verify-agent-chat.mjs`），**没有一个断言"对话页的文本在增长"**。
   建议把 `verify-stop.mjs` 里"等正文累计到阈值"的那一步抽成独立断言，
   或直接在 `verify-agent-chat.mjs` 增加：发送后采样两次 `.stream-text` 长度，要求递增。
   这类"时间维度的断言"，接口测试永远覆盖不到。

10. ~~**缺一个管理员级的数据清理通道（残留会累积的真正原因）**~~
    → **2026-10-03 已实现并已真机执行**（`Test Data Cleanup Utility`）：`nexus-app/tools/cleanup` 包
    （`@Profile("cleanup")` 的 CLI，非 HTTP 接口）+ `scripts/cleanup-test-data.ps1`。
    默认 dry-run；前缀白名单；`Prisdvl` / `demo` 硬保护且被命中即整体中止。
    补齐了三处既有级联缺口（`kb_import_task` / `kb_qa_record` / `SPRING_AI_CHAT_MEMORY`）。
    真机 EXECUTE 已执行：78 个测试账号 / 10 库 / 24 会话 / 13 自建 Agent 全部清掉，
    `Prisdvl` 与 `demo` 零变化，孤儿扫描全 0。执行过程还暴露并修掉了两个缺陷 ——
    既有 `KnowledgeBaseServiceImpl.delete` 的 NPE（产品级），以及本工具导入明细关联键写错（工具级）。
    详见 `docs/deployment.md` §9（含 §9.6 EXECUTE 实测）。
    以下为原始记录，保留以便追溯设计动机：

    缺一个管理员级的数据清理通道（残留会累积的真正原因）
    每个验收脚本只能删**自己那个账号**造的数据（靠自己的 token）。而每次跑
    `verify-real-user-flow.mjs` / `verify-security.mjs` / `verify-agent-no-kb.mjs`
    都会注册一个**一次性账号**，账号删不掉（无删号接口），它名下的资源也就无从回收——
    凭证一丢，API 就再也删不动了。累积现状（2026-10-03 实测）：
    78 个测试账号名下 10 个知识库 / 24 个会话 / 14 个自建 Agent。
    正解是做一个**管理员级清理能力**：按用户名前缀批量清理，并且**走完整级联**
    （`kb_document` / `kb_chunk` / 向量表 / ES 索引），而不是裸改 `deleted` 标记
    （裸改会留下孤儿向量与索引，比残留本身更糟）。在此之前，这些残留只能留着。

    补充（同日第二轮 EXECUTE）：又修掉两个缺陷 —— **E2E 往受保护账号 `demo` 里持续灌数据**
    （会话 37→45、另累积 30 条问答留档，且没有任何回收通道；见第 12 条）与
    **dry-run 漏报内置 Agent**（报"自建Agent 1"、实删 4 个；见第 13 条）。

11. ~~**验收脚本的地址默认值不统一（`API_BASE` 的口径坑）+ 令牌寿命**~~
    → **2026-10-03 已修**。三处互不兼容的口径（一个期望含 `/api`、一个不含、默认端口 8080 与
    8088 各一）统一为任务书建议的形式：**`API_BASE` / `WEB_BASE` 永远指向网关根**
    （默认 `http://localhost:8088`），路径里的 `/api` 由脚本自己拼。
    令牌则新增 `web/scripts/lib/demo-session.mjs` 的 `demoToken()`：每次**现登录换新令牌**
    （`demo / Demo@12345`，可用 `DEMO_USER`/`DEMO_PASSWORD` 覆盖），登录失败才回落到
    `web/.demo-token` —— 8 个脚本不再受那份 30 分钟寿命文件摆布。
    口径定义随后**上移到 `web/scripts/lib/e2e-config.mjs`**（任务书 §43 点名要求的公共模块），
    `demo-session.mjs` 改为从它重新导出，因此既有脚本一行都不用改；
    新脚本直接从 `e2e-config.mjs` 导入地址。
    以下为原始记录：
    `verify-security.mjs` 用 `${apiBase}/api/...`（默认 `http://localhost:8080`），
    `verify-agent-no-kb.mjs` 的默认值里**已含** `/api`。两者在 Docker 部署下都要显式覆盖，
    且覆盖值时一个要带 `/api`、一个不能带。直接跑只会得到 `fetch failed`——
    看起来像脚本坏了，实为地址口径。建议统一为"`API_BASE` 永远指向网关根、脚本自己拼路径"，
    或在脚本头部断言环境可达并打印清晰的手册式提示。
    另外 `.demo-token` 只有 **30 分钟**寿命（`access-token-ttl: 30m`），
    长脚本会跑着跑着就过期、表现为莫名超时。建议脚本启动时**自动刷新一次令牌**
    （用 `demo / Demo@12345` 换一个新的），而不是依赖外部预先写好的文件。

12. ~~**E2E 把数据漏进受保护账号（demo）——会话部分**~~
    → **2026-10-03 已修**。三个脚本用 demo 令牌跑却在 demo 名下留数据，而 demo 受硬保护、
    `cleanup-test-data` 永远回收不到 —— 等于"测试跑得越多，保护账号越胖"，直接违反
    「重复执行不会无限污染数据库」。

    | 脚本 | 每轮泄漏 | 方式 | 处置 |
    | --- | --- | --- | --- |
    | `verify-agent-chat` | 2 个会话 | 界面驱动，`finally` 只关浏览器 | **已修** |
    | `verify-multi-kb-failure` | 5~6 个会话 | `ragAsk()` 每调一次建一个，从不删 | **已修** |
    | `verify-stream` | 1 条 `kb_qa_record` | 走知识库「问答」页，不建会话但写留档 | **未修** → 见第 17 条 |

    修法：`web/scripts/lib/demo-session.mjs` 新增 `listConversationIds()` +
    `deleteConversationsCreatedSince()`，**跑前快照 + 差集删除**。
    关键约束：**不能用"清空本账号全部会话"收尾** —— 那会把 demo 的演示数据一起删掉。
    重跑验证：agent-chat 删 2 / multi-kb-failure 删 6，快照恒为 37；
    第二轮完整 E2E 之后 demo 仍是 **37 会话 / 76 消息**（与基线一致）。

13. ~~**dry-run 漏报会被删的内置 Agent**~~
    → **2026-10-03 已修**。`AccountSnapshot` 只统计 `builtin = 0`，而 `purgeBuiltinAgents`
    默认 true —— dry-run 报"自建Agent 1"，实际删 4 个（1 自建 + 3 内置）。
    修法：快照加 `builtinAgents`、`CleanupPlan.totalBuiltinAgents()`、summary 增"内置Agent"、
    dry-run 表增 `bAgt` 列并加口径说明，补回归测试 `planReportsBuiltinAgentsToo`。

14. **ES 物理索引与别名不回收（"可重复运行"的残留增长向量之一）**
    删知识库走的是 `aliasFor(kbId)` 的 delete-by-query，**只清文档、不回收索引与别名**。
    实测：61 个物理索引 / 60 个别名，其中**仅 3 个指向活跃知识库**；
    一轮完整 E2E 新增约 **12~14 个空索引**。数据层面不脏（这些索引的 `docs.count` 为 0），
    但索引壳单调累积，长期跑会让 ES 集群状态膨胀。
    最小修复：`ElasticsearchChunkIndex.deleteByKnowledgeBase` 在 delete-by-query 之后
    追加"删除别名 + 删除物理索引"。**属于产品删除路径的行为变更，本次未实施**
    （任务书 §十一 明确"不要再扩大改动面"）。

15. **部署产物落后于仓库（运维动作，非代码缺陷）**
    实测：`nexus-backend:0.0.1` 镜像构建于 **11:24**，而 `KnowledgeBaseServiceImpl` 的 NPE 修复
    在 **17:19** —— 即运行中的实例仍带"删含 Vault 文档的知识库会 500"的缺陷。
    （前端镜像 14:06 晚于其全部源码，是最新的。）
    本次已按此重建后端镜像并重启，容器内 `app.jar` 时间戳与新产物一致，健康检查通过。
    **教训**：Dockerfile 只 `COPY` 预构建 jar，所以"`mvn package` 成功"**不等于**"运行实例已更新"；
    发布流程里应把 `docker compose build <service>` 与 `up -d` 显式串到构建之后。

16. **软删墓碑会累积（"可重复运行"的残留增长向量之二）**
    清理把账号/知识库/会话做**逻辑删除**（`deleted = 1`），于是库里留有不可见墓碑。
    清理后实测：`kb_document` 墓碑 **137** 行、`chat_conversation` 墓碑 **42** 行。
    用户不可见（所有查询都带 `deleted = 0`），也不构成孤儿（父行仍在），
    但会随重复运行单调增长。**属于软删设计的固有代价，不建议为测试便利改掉**
    （改物理删会牺牲可回溯性）；如需控制，应加"墓碑归档/清理"的定期作业，而不是改删除语义。

17. ~~**`verify-stream.mjs` 仍向 demo 写问答留档（E2E 最后一条残留泄漏）**~~
    → **2026-10-03 已修**。它原先借 demo 令牌、在 demo 的第一个知识库里提问，
    每轮写 1 条 `kb_qa_record`（实测 30 → 31）。demo 受硬保护，
    `cleanup-test-data` 永远回收不到这类数据。

    修法就是本条原本写下的"正解"——脚本改为**完全自持**：

    | 步骤 | 做法 |
    | --- | --- |
    | 账号 | 自注册 `probe-stream-<ts>`；前缀 `probe` 在 `nexus.cleanup.username-prefixes` 白名单内，**账号本身可被清理工具回收** |
    | 资料 | 自建知识库 → 上传含**独有事实** `NEXUS-STREAM-FACT-<ts>` 的 TXT → 等导入 `DONE` → 跑 `/workflow/run/{kbId}` 建索引 |
    | 前置断言 | 先证明"刚导入的独有事实可检索"——不成立就该先修这个，而不是把"检索坏了"记成"流式坏了" |
    | 浏览器 | 用新账号令牌打开该库的「问答」页（不再点 demo 的卡片） |
    | 收尾 | `finally` 删掉本轮知识库（走 API 级联，不做裸表删除） |

    断言从 4 条加到 6 条：新增「**回答正文里必须出现那份独有事实**」（否则"检索 + 生成"
    这条链路根本没真跑通）与「索引流水线全部 `SUCCESS`」；
    「检索标签先于正文出现」「文本持续增长」原样保留。

    至此 —— 第 12 条的三个脚本 + 本脚本 —— **E2E 已没有一条依赖 demo 数据的用例**。

18. **`verify-stream.mjs` 重写后带进一个静态 API 误用（已修）**
    重写时把 `context.addInitScript(fn, token, username)` 写成了两个参数。
    Playwright 的签名是 `addInitScript(script, arg)` —— **只接受一个参数**，
    多传会抛 `Too many arguments. If you need to pass more than 1 argument to the
    function wrap them in an object.`

    它值得归档，是因为**失败位置极具误导性**：异常抛在浏览器启动之后、第一条断言之前，
    日志里前四步（注册 / 建库 / 导入 / 索引 / 前置检索）全部打印成功，
    从现象上看像"产品在这一步挂了"，实际上产品那五步**走得完全正确**。
    同一仓库其余 11 个脚本用的都是正确的单对象写法，唯独新写的这个没有。

    两条通用教训：
    - **"脚本写完了"不等于"脚本能跑"**。本仓库既有的 `verify-stream` 就是因为没人复跑，
      带着 UA-003 的文案漂移失效了很久（见第 17 条）。改动过的脚本必须当轮复跑，
      且要跑**完整轮次**而不是只跑它自己。
    - **多参数一律包对象**，与既有 11 个脚本对齐。这是本仓库的既定写法，
      新脚本没有理由另立一种。

19. **`verify-retrieval-ui.mjs` 第⑤步的固定超时窗口在 CPU 争用下不够（未修，已定性）**
    现象：同一份代码，**串行**跑 PASS（Round 1，20s），
    在**并发**跑另一个浏览器脚本时 FAIL（Round 2，`page.waitForFunction: Timeout 20000ms exceeded`），
    之后无并发连跑 3 次全 PASS。定性为**测试台在负载下的鲁棒性上限，不是产品缺陷**。

    根因在脚本自身的设计：第⑤步用"20 秒墙钟超时"去等一件由"8 秒人为挂起"（`RELOAD_HOLD_MS`）
    决定的 DOM 变化。两个数字都是固定值，而中间那段（应用自身的重新渲染）在 CPU 争用时会被拉长，
    于是余量被吃光。**用时间倒计时去同步一个确定性事件，本身就是错配。**

    正解（未做，因为官方 runner 是串行的，当前不触发）：把它改成事件驱动 ——
    在路由处理器里把"被挂起的那次校正响应已放行"变成一个可等待的计数，
    先等这个计数达标（确定性事件），再用一个短超时等 DOM 收敛。
    这样长耗时部分就落在断言超时之外了。

    边界条件：**不要在并发条件下跑 E2E**。官方 `run-2rounds.sh` 是严格串行的，
    这条路径不受影响；调试时若想同时跑两个浏览器脚本，需知道会命中本项。

20. **清理工具回收不到"挂在已软删会话下"的会话记忆行（工具级残留，未修）**
    背景：UA-018 修复前，删除会话**不清** `SPRING_AI_CHAT_MEMORY`，
    于是历史会话被软删后，它的记忆行就留在表里、谁也不指向。本次实测 **341 行**属此类。

    为什么清理工具也回收不到：它对记忆的处理是**按活跃会话逐条删**
    （`WHERE deleted = 0` 取 `convIds`，再对每个 id 删记忆）。软删会话不在这个集合里，
    所以这 341 行**结构性地**落在工具的覆盖范围之外。

    定性（决定了它不值得优先修）：
    - **用户不可见、无功能影响**：记忆按 key 读取，软删会话永远不会再被读到；
    - **不是增长向量**：UA-018 修复后所有删除路径都会连带清记忆，
      3000 行这笔存量是**冻结的历史沉积**（四轮完整 E2E + 清理后总数纹丝不动）。

    最小修法（**未做**，留给后续）：把记忆清理的遍历范围从"活跃会话"放宽到"名下**全部**会话"：

    ```java
    // 记忆清理覆盖"名下全部会话"，含历史软删——UA-018 之前"删会话不清记忆"
    // 留下的行只挂在软删会话下，只遍历活跃会话会把它们永久漏掉。
    // key 仍走 ChatMemoryKeys，**不要**在 SQL 里写 CONCAT('conv:', id)：
    // 那等于把 key 的构造规则复制成两份，迟早分叉。
    for (Long convId : ids("SELECT id FROM chat_conversation WHERE user_id = ?", userId)) {
        result.chatMemoryRows += update(
                "DELETE FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id = ?",
                ChatMemoryKeys.of(convId));
    }
    ```

    需要配套改 `TestDataCleanupIntegrationTest`（断言"软删会话的记忆也被清"），
    否则这条修法没有回归网。

    同类：第 16 条（软删墓碑）与 ES 索引壳。三者的共同形状是
    **"用户可见数据已归零，但存储层仍留有不可见的增长/存量"**——
    都不影响产品完整性，都不建议为测试便利改掉产品语义。





