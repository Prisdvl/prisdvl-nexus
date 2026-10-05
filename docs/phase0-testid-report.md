# Phase 0 报告 · data-testid 注入 + E2E 选择器迁移

> 任务书：《prisdvl-nexus 前端产品级重构总提示词》§五 Phase 0。
> 边界：只处理测试稳定性，不做 UI 视觉重构；不改后端；不改用户可见文案；不 commit / push。
> 本报告是 Phase 0 的**唯一详细产出**；跨 Phase 的简短进度记录在 `docs/frontend-refactor-progress.md`。

---

## 0. 结论先行

Phase 0 的目标是**把 E2E 的元素定位从"中文文案"换成稳定锚点**，并让这个契约可长期守住。
结论四条：

1. **132 个 `data-testid` 已注入并实测落位**（含 1 处动态绑定，合计 133，与容器产物内出现次数逐个对齐）。
2. **15 个脚本、283 处选择器已迁移**；迁移后 `has-text(` 与中文选择器**双双归零**。
3. **用户可见文案零改动**——不是"看着没改"，而是把 111 处替换反向回放、重建出改动前的
   8 个文件后整文件比对可见文本序列，**违规 0**。
4. **E2E 尚未"全量通过"**：17 个脚本中 14 个已单跑验证为绿，2 个被 demo 账号当日 token
   额度挡住（服务端 403，非本轮问题），需等 2026-10-04 00:00 额度重置后补跑。
   **这一条不写成"通过"。**

---

## 1. 动手之前先查清的一件事（否则整个 Phase 0 是空的）

`data-testid` 写在 Element Plus 组件上**不一定进 DOM**。逐个查了 `node_modules/element-plus/es`
的编译产物，结论直接决定了锚点该挂在哪里：

| 组件 | `inheritAttrs` | 结论 |
| --- | --- | --- |
| `el-input` | `false`，但把 `attrs` 显式 spread 到内层 `<input>` / `<textarea>` | ✅ 写在组件上可用 |
| `el-button` / `el-select` / `el-radio-group` / `el-radio-button` / `el-tabs` / `el-empty` / `el-switch` | 未设（默认继承） | ✅ 落在根元素 |
| **`el-dialog`** | **`false`，且根为 `Teleport`** | ❌ **写了也不会进 DOM** |
| `el-dropdown-item` | `false` | ❌ 改用 `popper-class` |
| `el-tabs` 页签头 | — | 自动生成 `id="tab-{name}"` / `aria-controls="pane-{name}"` → 可用 |

因此两处设计决定：

- **弹窗锚点一律挂内部的 `el-form` / 按钮**，不挂 `<el-dialog>`。
  例：新建知识库弹窗用 `kb-create-form`（内部 `el-form`）+ `kb-create-name`（内部 `el-input`）。
- **页签切换用 `#tab-ask` / `#tab-chunks`**（由 `name` 生成），不再按中文页签名点。

这一条如果不查就动手，会得到"132 个 testid 全写上了、但弹窗里一个都定位不到"的结果，
而且要等到 E2E 全跑完才发现。

---

## 2. 新增 testid 清单（132 个 + 1 处动态绑定）

### 登录 / 注册（5）
`auth-username`、`auth-password`、`auth-confirm-password`、`auth-submit`、`auth-switch-mode`

### 外壳与导航（5 + 1 动态）
`app-sidebar`、`app-nav`、`account-entry`、`account-name`、`logout`，
外加 `navItems[].testid` 动态绑定 → 实际产出 `nav-knowledge` / `nav-chat` / `nav-agents`

### 知识库列表（14）
`kb-ask-agent`、`kb-create-open`、`kb-create-open-empty`、`kb-empty`、
`kb-card`、`kb-open`、`kb-import-files`、`kb-import-more`、`kb-delete`、
`kb-create-form`、`kb-create-name`、`kb-create-desc`、`kb-create-cancel`、`kb-create-submit`

### 知识库详情（27）
`kb-detail-back`、`kb-detail-import-file`、`kb-detail-import-vault`、`kb-detail-embed`、
`kb-detail-index`、`kb-detail-recreate`、`kb-detail-workflow-run`、`kb-detail-tabs`、
`kb-doc-filter`、`kb-doc-item`、`kb-doc-delete`、
`kb-tab-content`、`kb-tab-chunks`、`kb-tab-search`、`kb-tab-ask`、
`kb-search-mode`、`kb-search-topk`、`kb-search-input`、`kb-search-submit`、`kb-search-hits`、
`kb-ask-input`、`kb-ask-submit`、`kb-ask-stream-switch`、`kb-ask-citations`、`kb-ask-history`、
`kb-links`、`kb-related`

### 对话页（32）
`chat-rail`、`chat-new`、`chat-conversation`、`chat-conversation-rename`、`chat-conversation-delete`、
`chat-rail-toggle`、`chat-messages`、`chat-message`、`chat-welcome-import`、`chat-sample`、
`chat-composer`、`chat-input`、`chat-send`、`chat-stop`、
`chat-composer-options`、`chat-draft-mode`、`chat-draft-mode-agent`、`chat-draft-mode-rag`、
`chat-draft-kb-select`、`chat-draft-agent-select`、`chat-kb-select`、`chat-agent-select`、`chat-agent-hint`、
`chat-retrieval`、`chat-retrieval-multi-kb`、`chat-retrieval-failed`、`chat-retrieval-empty`、
`chat-retrieval-reranked`、`chat-retrieval-truncated`、`chat-retrieval-hits`、
`chat-trace`、`chat-citations`

### Agent 管理（36）
`agent-eval-open`、`agent-create-open`、`agent-empty`、`agent-card`、
`agent-card-test`、`agent-card-config`、`agent-card-toggle-status`、`agent-card-duplicate`、`agent-card-delete`、
`agent-create-form`、`agent-create-code`、`agent-create-name`、`agent-create-desc`、
`agent-create-cancel`、`agent-create-submit`、
`agent-edit-form`、`agent-edit-name`、`agent-edit-desc`、`agent-edit-prompt`、
`agent-edit-policy`、`agent-edit-policy-kb-preferred`、`agent-edit-policy-kb-only`、
`agent-edit-kb-select`、`agent-edit-tool-select`、`agent-edit-temperature`、
`agent-edit-cancel`、`agent-edit-submit`、
`agent-test-panel`、`agent-test-question`、`agent-test-sample`、`agent-test-run`、`agent-test-close`、
`agent-eval-panel`、`agent-eval-kb-select`、`agent-eval-run`、`agent-eval-report`

### 账号 / 改密弹窗（12）
`profile-form`、`profile-nickname`、`profile-email`、`profile-save`、`profile-cancel`、`profile-change-password`、
`password-form`、`password-old`、`password-new`、`password-confirm`、`password-submit`、`password-cancel`

### 附：8 个数据属性（做精确区分，不只是定位）

| 属性 | 用途 | 替代了原来什么 |
| --- | --- | --- |
| `data-kb-name` | 精确指定知识库卡片 | `.card:has-text("<库名>")` |
| `data-agent-code` | 精确指定 Agent 卡片 | `.code` 文字过滤（`x` 会误配 `x-copy`） |
| `data-builtin` | 区分内置 / 非内置 Agent | `.filter({ hasText: '内置' })` |
| `data-doc-path` | 精确指定文档行 | `.docs li:has-text("<文件名>")` |
| `data-doc-name` | 按标题/文件名指定 | 同上 |
| `data-conv-id` | 精确指定会话 | `.rail .conversation.active` |
| `data-role` | 区分用户 / 助手消息 | `.message.from-agent` |
| **`data-empty-variant`** | `all-failed` / `partial` / `none` | 三句中文文案的区分 |

**最后一项值得单独说**：检索卡片的"零命中"标签有三种文案
（`知识库本次都没有检索成功` / `其余知识库没有命中` / `知识库里没有命中`），
对应**三种不同的事实**。`verify-retrieval-ui.mjs` 的五条腿正是在区分它们。
如果只把这三句都映射到同一个 testid，迁移就**削弱了断言**——
"另一个库没命中"会被"两个库都没搜成"蒙混过去。所以加了 `emptyVariant()` 输出稳定枚举。

---

## 3. 修改了哪些 E2E

| 脚本 | 处数 | 主要改动 |
| --- | --- | --- |
| `verify-real-user-flow.mjs` | 31 | 导航/建库/弹窗/退出全改 testid；新增空态锚点断言 |
| `verify-agents.mjs` | 38 | `cardByCode`/`dialog` 两个辅助函数改锚点；5 个下拉改 popper-class |
| `verify-agent-chat.mjs` | 36 | 模式选择/输入/发送/会话/引用/工具痕迹全改 testid |
| `verify-retrieval-ui.mjs` | 35 | 5 个检索标签改 `data-testid` + `data-empty-variant`；悬浮提示改结构性等待 |
| `verify-security.mjs` | 22 | 改密三框改锚点（原先按 DOM 顺序 `nth(0/1/2)`）；`dialog()` 改锚点判别 |
| `verify-stop.mjs` | 18 | 发送/停止改 testid（原先 `.composer .send:has-text(...)`） |
| `verify-multi-kb.mjs` / `verify-multi-kb-failure.mjs` | 18 / 15 | 草稿态与页头选择器、下拉 popper-class、检索标签 |
| `verify-doc-lifecycle.mjs` | 17 | `uploadViaButton` 参数从**按钮文案**改为 **testid** |
| `verify-frontend.mjs` | 7 | 卡片/文档行/关联面板/页签全改锚点 |
| `verify-login.mjs` | 7 | 登录表单改 testid |
| `verify-agent-no-kb.mjs` | 14 | 同上 |
| `verify-responsive.mjs` | 7 | `mustSee` 三个页面判据改 testid |
| `verify-full-product.mjs` | 5 | 登录表单改 testid |
| **合计** | **283** | |
| `verify-import-formats.mjs` | 0 | 纯 API 脚本，不驱动浏览器，无需迁移 |
| `verify-testid-dom.mjs` | 新增 | testid 契约守卫 |

### 三处不只是"换选择器"的改动

1. **`uploadViaButton(buttonText, …)` → `uploadViaButton(buttonTestId, …)`**
   原签名让"入口按钮能不能拉起文件选择器"这条覆盖**绑死在按钮措辞**上。
   UA-005 就把「上传文件」改成「导入文件」——改字即静默失效。
2. **`dialog(title)` → `dialogByTestId(anchor)`（`verify-security` / `verify-agents`）**
   原实现按标题文案挑弹窗。除了脆弱，它还带一个已修的旧 bug：
   资料弹窗页脚有「修改密码」按钮，点它之后资料弹窗在关闭动画期间**仍是 `:visible`**，
   于是两个弹窗同时命中、Playwright 报 strict mode violation。
   改用内部锚点后，动画期不再是问题（`profile-form` 只在资料弹窗里）。
3. **`verify-agents` 草稿态状态按钮断言**
   原来数两个 `has-text` 计数（`发布` ×1、`停用` ×0），现在读同一个锚点的文案。
   计数式断言在"两个按钮都不在"时会给出 `0/0` 这种**说不清**的失败。

---

## 4. 改造前 · 后选择器统计

| 指标 | 改造前 | 改造后 |
| --- | --- | --- |
| `has-text(` 出现次数 | **79** | **0** |
| 含中文的选择器（`waitForSelector` / `locator` / `click` / `fill` 内） | **91 处** | **0** |
| `data-testid` | **0** | **132**（+1 动态绑定） |
| 依赖按钮/标题文案的定位 | 6 处弹窗 + 9 处按钮 + 2 处输入框 placeholder | 0 |
| 依赖中文页签名点页签 | 2 处（`.el-tabs__item:has-text("切片"/"问答")`） | 0（改用 `#tab-*`） |

改造前数据来自本轮动手前的实测（逐脚本 `has-text` 计数 + `grep -oP '\p{Han}'`）；
改造后由同一套命令复测。中文选择器统计必须用 `grep -P`——
`grep -E '[\u4e00-\u9fa5]'` 在 GNU grep 下**恒为 0**（不支持 `\uXXXX`），会给出假的"已清零"。

---

## 5. 零文案改动：机械证明

任务书的硬边界是"不修改用户可见文案"。这件事不该靠"我看着没改"，因此做成可复跑的证明：

1. 5 个注入器脚本里保存了全部 **111 处** `(原文, 新文)` 替换对。
2. 用 `ast` **静态解析**（不执行注入器，避免副作用）把这 111 处取回来，
   逆序回放，重建出**改动前的 8 个文件**。
3. 剥掉 `<script>` / `<style>` / 注释 / 标签，比对**可见文本片段序列**。

结果：**8 个文件全部重建成功，可见文本序列逐项一致，违规 0**。

这个证明过程中我自己错了三次，都记在 `docs/frontend-refactor-progress.md` 里：
先按"行"丢弃属性行 → 9 处假违规；改成"只删属性子串" → 7 处假违规（全是空白差异）；
第三次改成整文件重建时，提取器又**静默漏掉 part2/3 的大多数规则**（首参是变量而非字面量），
"一致"一度只是部分证明。补上常量解析、以及允许属性值内含 `>` 的标签正则，才得到真结论。

---

## 6. E2E 结果

### 6.1 构建

`npm run build`（`vue-tsc --noEmit && vite build`）**通过**，产物已重建并部署到 `nexus-frontend`。
容器内 `data-testid` 出现次数 **133**，与源码 132 + 1 动态绑定**逐个对齐**，确认测的是新产物。

### 6.2 DOM 落点取证（`verify-testid-dom.mjs`）

逐页 dump `[data-testid]` 并断言关键锚点，**观测到 96 个 testid 真实落位**。
覆盖：登录页、注册模式确认框、外壳导航、知识库列表、新建库弹窗、知识库详情、
检索/问答页签、对话页、草稿态、侧栏折叠、Agent 管理、三个弹窗、评测集弹窗。

两处"落位 ≠ 可用"的验证：
- `el-radio-button` 的锚点必须落在**可点击的 label** 上 → 切 RAG 模式实测生效；
- `el-tabs` 的 `#tab-{name}` 确实按 `name` 生成 → `["tab-content","tab-chunks","tab-search","tab-ask"]`。

### 6.3 全量串行连跑（第一轮）

**8 PASS / 9 FAIL**。9 个失败逐个读日志后分成两类，**没有把任何一个推给环境**。

### 6.4 A 类：3 个是本轮迁移引入的缺陷（已修，单跑全绿）

| 脚本 | 症状 | 根因 |
| --- | --- | --- |
| `verify-security` | `pwdInputs is not defined` | 文件里有**第二处** `pwdInputs` 复用，我只迁了声明处 |
| `verify-agents` | 等 `.chat-draft-agent-select-popper .el-select-dropdown__item` 超时 | 配置弹窗的**知识库**选择器被我错配成 Agent 选择器的 popper-class（页面上多个 select 的 popper 同时存在） |
| `verify-frontend` | 等 `[data-doc-path*="SpringBoot"]` 超时 | **`:has-text()` 大小写不敏感、CSS `*=` 大小写敏感**。原来命中的是小写的 `java_springboot.md` |

第 3 条尤其值得记住：它是一处**照搬语义**造成的事故——把 Playwright 的匹配语义
当成了 CSS 的匹配语义。修法是老老实实用完整 `data-doc-path`，
并把"为什么不能用 `*=`"写进注释。

### 6.5 B 类：环境与额度

| 脚本 | 单跑 | 说明 |
| --- | --- | --- |
| `verify-real-user-flow` | ✅ PASS | 连跑时 `page.evaluate: Failed to fetch` |
| `verify-import-formats` | ✅ PASS | 连跑时 `fetch failed` |
| `verify-retrieval-ui` | ✅ PASS | 连跑时浏览器 `Target crashed`（GPU `AllocateRingBuffer` 失败） |
| `verify-stream` | ✅ PASS | 连跑时浏览器 `Target crashed`；它用自建 `probe-stream-*` 账号，不受 demo 额度影响 |
| `verify-multi-kb-failure` | ❌ 403 | demo 额度耗尽 |
| `verify-stop` | ❌ 超时 | **同因**：它只等 `status===200` 的流，服务端返回 403 → 报"超时" |

**额度实测**（`GET /api/users/me/quota`）：
`requestUsed=166/200`、`tokenUsed=201777/200000`、`resetAt=2026-10-04T00:00:00`。
直连 SSE 接口复现：`HTTP 403 {"code":7002,"message":"今日 token 额度已用尽…"}`。

这一条是**今天的验证工作自己消耗掉的**（含上一个任务书的四轮 E2E），不是代码问题，
也不是本轮迁移造成的。要补跑需等 00:00 之后。

### 6.6 达成度（不把部分通过写成通过）

| 完成条件 | 状态 |
| --- | --- |
| ① testid 覆盖核心用户操作 | ✅ 132 个，16 项优先清单全覆盖 |
| ② 核心 E2E 不再依赖中文文案作为主要定位 | ✅ `has-text` 与中文选择器均归零 |
| ③ 现有 E2E 全量通过 | ⚠️ **未完整达成**：14/17 单跑为绿，2 条被额度挡住 |
| ④ `npm run build` 通过 | ✅ |
| ⑤ 浏览器真实点击关键流程通过 | ✅ `verify-testid-dom.mjs` 真实点击 7 个页面/弹窗并断言 |
| ⑥ 输出 Phase 0 报告 | ✅ 本文件 |

**17 = 8（连跑通过）+ 3（修复后单跑通过）+ 4（环境类单跑通过）+ 2（额度挡住）**。

---

## 7. 剩余依赖文案的定位，以及保留原因

迁移后**没有任何一条"定位"依赖产品文案**。仍然出现中文的地方分三类，都不是脆弱定位：

| 类型 | 例子 | 为什么保留 |
| --- | --- | --- |
| **断言文案本身** | `if (emptyCount !== '检索到 0 个片段')` | 它验的就是这句话。把这类也改掉等于把回归保护拆了 |
| **按测试自建数据筛选** | `.agent-edit-kb-select-popper .el-select-dropdown__item` + `filter({ hasText: demoKb })` | 那是脚本自己建的库名/Agent 名/文件名，属**数据**不属产品文案。已加 popper-class / `:visible` 收窄作用域 |
| **DOM 上下文取样** | `document.querySelectorAll('.el-message')` | 结构类名，不随产品措辞变化 |

保留的**结构类名**（不属"文案依赖"，且改动它们会牵连样式）：`.el-message`、
`.el-dialog`（EP 的 teleport 容器）、`.el-tabs__item`、`.trace-*`、`.retrieval-count`、
`.ir-head`、`.link-group`、`.composer-hint`。

---

## 8. 本轮新增的一个脚本（超出"迁移"二字，请老大过目）

`web/scripts/verify-testid-dom.mjs` —— **testid 契约守卫**。

理由：迁移把脆弱性从"文案"换成了"testid 契约"。从今往后，重命名一个 testid
看起来毫无风险，却会让十几个脚本各自报"元素找不到"。这个脚本让契约变成可执行的：
逐页断言关键锚点真在 DOM 里。

它同时是一份**落点实证**——第 1 节那批 Element Plus 结论，本来只是"读源码得到的结论"，
现在有一条 8 秒就能重跑的回归测试守着。

一个刻意的设计：断言分**必须**与**条件**两档。条件渲染的锚点（`kb-empty` /
`agent-empty` / `kb-links` / `kb-related`）在 demo 账号下不成立，
此时脚本**如实打"○ 未取证"并指明由谁覆盖**，不假装通过。
（第一版把这类写成无条件，结果 3 个假失败——那正是这个脚本要根治的毛病。）

**若老大认为不该加这个脚本，删掉即可**，Phase 0 的其余产出不受影响。

---

## 9. 建议的下一步（不自行执行）

0. **【优先】把 `verify-stop` / `verify-multi-kb-failure` 从 demo 账号解耦。**
   本轮勘察确认：这两条对 demo 是**纯登录耦合、零数据依赖**
   （`verify-multi-kb-failure` 自建三个库，`verify-stop` 自建会话）。
   照 `verify-stream` 的现成样板改成自建 `probe-*` 账号即可，约 30 行/脚本。
   收益不只是"今天能跑完"：
   - 共享额度是**先到先得**，任何人跑完整套 E2E 都会把额度用光，后面的人拿到的是 403；
   - 更糟的是症状误导——`verify-stop` 把额度、鉴权、模型不可用三种原因
     全报成「等待流式响应超时」。
   本轮**未做**：超出 Phase 0 授权范围（Phase 0 只授权 testid 注入 + 选择器迁移）。
1. **00:00 后补跑** `verify-multi-kb-failure` 与 `verify-stop`，把完成条件 ③ 补齐
   （若采纳第 0 条，这两条不再依赖重置时机）。
2. **E2E 分组跑**：Docker 只分到 7.569 GiB，常态已占 4.4 GiB。建议 4～5 个一组、组间留间隔，
   而不是 17 个背靠背连跑（连跑时出现过 `fetch failed` 与浏览器崩溃）。
3. **`verify-stop.mjs` 的错误可观测性**：它把"额度耗尽 / 鉴权失败 / 模型不可用"
   三种原因都报成「等待流式响应超时」。本轮未改（超出 Phase 0 范围）。
4. **Phase B 之前**建议确认：是否把"demo 账号当日额度"纳入 E2E 的前置检查
   （像 Testcontainers 那样门控），否则任何需要 LLM 的脚本在额度耗尽时都会给出误导性报错。
