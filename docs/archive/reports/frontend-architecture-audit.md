# Frontend Architecture Audit

- 审计对象：`web/src/`（24 个源文件，8206 行）
- 审计性质：**只读**。本文件不含任何代码改动，对应重构任务书 §五（Phase A）
- 审计方式：全部结论均来自实际读码 / 实测统计，**未依据报告或印象下判断**
- 审计日期：2026-10-03

---

## 0. 先修正任务书中与事实不符的前提

任务书 §二「当前技术」列出 **Pinia**，§四十 又要求「检查 Pinia。当前只允许 `auth`」。
实测：**`package.json` 里没有 Pinia，全仓也没有任何 `pinia` 引用。**
`stores/auth.ts` 是一个普通 `reactive` 对象，并在注释里明确写了理由：

> 刻意不引入 Pinia：整个前端只有"当前用户"这一份全局状态，一个 reactive 对象就够；
> 为它加一个状态管理库属于为了架构而架构。

**结论**：§四十 的前提不成立。是否引入 Pinia 应作为本次重构的**独立决策**，
而不是"检查现有 Pinia 用法"。这个决策的判定标准应当是"是否真的出现了跨页面共享状态"——
按第 4 节的实测，答案是**会出现**（知识库 / 会话 / Agent 在三处页面之间被反复拉取），
因此引入状态管理是**有依据的**，但依据不是"任务书说要用"。

同理修正：§四十八 要求"增加 Component / Interaction tests"。
实测 devDependencies 里**没有 vitest，也没有 @vue/test-utils**，且无任何测试配置。
这意味着该项**需要新增测试框架**（新依赖 + 配置 + 脚本），属独立工作量，不是"补几个用例"。

---

## 1. 页面

| 路由 | 视图 | 行数 | script | template | style |
| --- | --- | --- | --- | --- | --- |
| `/login` | `LoginView.vue` | 227 | 87 | 58 | 81 |
| `/` | `KnowledgeBaseListView.vue` | 503 | 341 | 91 | 71 |
| `/kb/:id` | `KnowledgeBaseDetailView.vue` | **1855** | **610** | 426 | **819** |
| `/agent` | `AgentView.vue` | **1691** | **780** | 370 | 541 |
| `/agents` | `AgentsView.vue` | **1013** | 369 | 339 | 305 |
| — | `App.vue`（外壳） | 340 | 85 | 60 | 192 |

**规模分布**：5 个视图合计 **5289 行 = 全部前端代码的 64.4%**；
其中三个巨型视图 **4559 行 = 55.6%**。

**一个必须记住的数字**：`KnowledgeBaseDetailView.vue` 的 **819 行 CSS 占该文件 44%**。
样式不是一个附带的尾部，它本身就是巨型组件的主要体积来源之一。

---

## 2. 路由

`router/index.ts`（53 行）：hash 路由，5 条记录，1 个全局前置守卫。

| 项 | 现状 | 判定 |
| --- | --- | --- |
| 未登录拦截 | `!to.meta.public && !isLoggedIn()` → 跳 `login` 并带 `?redirect=` | ✅ 正确 |
| `redirect` 消费 | `LoginView:80` `route.query.redirect \|\| '/'` | ✅ 正确 |
| 重定向死循环 | 不存在（`login` 标记 `meta.public`） | ✅ 正确 |
| **404 / 未匹配路由** | **无 `pathMatch` 兜底记录** | **P2 缺口** |
| `meta.title` | 5 条都有定义，但**全仓无任何地方消费它** | **P2 缺口**（`document.title` 永不更新） |

> 未匹配 hash 路由（例如 `#/typo`）目前不会跳 404，Vue Router 只会打印一条警告，
> 页面区域**渲染为空白**。§四十二 要求"非法路由 → 404 / home"。

---

## 3. API

### 3.1 文件与职责

| 文件 | 行数 | 内容 | 判定 |
| --- | --- | --- | --- |
| `api/knowledge.ts` | **481** | 知识库 + 文档 + 切片 + 检索 + 问答 **+ 认证（`register` / `login` / `me`）** | **Refactor**：认证不属于 knowledge |
| `api/chat.ts` | 308 | 会话 + 消息 + 流式对话 + 停止 | 合理 |
| `api/agents.ts` | 126 | Agent CRUD + 状态 + 测试对话 | 合理 |
| `api/client.ts` | 123 | axios 实例 + 拦截器 + token | 见 3.3 |
| `api/user.ts` | 67 | 资料 / 改密 / 配额 | 合理 |
| `api/agent.ts` | 46 | **只有评测集**（`EvalCase` / `runEval`） | **Refactor**：与 `agents.ts` 命名几乎相同、职责却是另一件事 |
| `api/tools.ts` | 28 | 工具列表 | 合理 |

**`agent.ts` / `agents.ts` 并存**是一个真实的可维护性隐患：两个名字只差一个字母，
一次误引用（`@/api/agent` vs `@/api/agents`）在编译期**不会报错**（导出名不同），
在运行期才会表现为"找不到函数"。

### 3.2 【P1】SSE 类型被定义了两次，且已经分叉

两条链路各自声明了一整套流式类型，**同名不同形**：

| 接口 | `api/knowledge.ts`（问答页用） | `api/chat.ts`（会话页用） |
| --- | --- | --- |
| `StreamMeta` | `{citations, contextTokens, truncated, reranked, empty}` | `{requestId, conversationId, userMessageId, assistantMessageId, modelRole, mode}` |
| `StreamDone` | `{finishReason?, recordId?, citations?: RagCitation[], error?}` | `{finishReason?, stopped?, citations?: ChatCitation[]}` |
| `StreamRetrieval` | `hits` 内联展开 | `hits: StreamRetrievalHit[]` **+ `failedKnowledgeBases: StreamRetrievalFailure[]`** |

**逐行 diff 的结果是"不一致"，不是"一致"**：

```
< hits: Array<{ chunkId; documentId; knowledgeBaseId; documentTitle; headingPath; score; source }>
> hits: StreamRetrievalHit[]
> failedKnowledgeBases: StreamRetrievalFailure[]      ← knowledge.ts 完全没有这个字段
```

而 `knowledge.ts` 的注释仍然写着：

> 形状与会话流式（`api/chat.ts` 的 `StreamRetrieval`）**必须保持一致**：
> 服务端两条链路共用同一个构造器（`RetrievalEvent`），前端却有两份类型声明。
> 这是历史遗留——将来若合并成一个共享类型，这两处可以删掉一份。

**即：这条"必须保持一致"的约束已经失效，且没有任何机制会阻止它继续失效。**
服务端用同一个构造器产出事件，前端却靠**两份手写类型**去描述它——
`failedKnowledgeBases` 加上去的时候，只有一份被更新。

**当前是否有用户可见缺陷？** 核对后：**暂时没有**。
`KnowledgeBaseDetailView` 只消费 `count / contextTokens / truncated / reranked`，
不读 `empty` 也不读 `failedKnowledgeBases`。单库问答页在检索失败时，
后端会把说明写进回答正文（"有知识库暂时无法检索…"），用户仍能看到人话。

**但它是一个会静默失效的陷阱**：任何"让单库问答页也显示失败标签/空态"的需求，
都会因为类型里根本没有这个字段而**写不出来或写错**。
定级 **P1（数据契约）**，不是 Bug——这与上一轮"审计出 4 项真实缺口、其余是正确实现"的判定口径一致。

### 3.3 错误路径不对称（`client.ts`）

两条失败路径抛出的错误**携带的信息不同**：

| 失败类型 | 是否弹提示 | 抛出的错误 | 是否带 `status` / `code` |
| --- | --- | --- | --- |
| HTTP 4xx/5xx | 拦截器弹 `ElMessage.error(body.message)` | `ApiError`（显式挂 `status` / `code`） | ✅ 带 |
| HTTP 200 + `code !== 0` | 拦截器弹 `ElMessage.error(body.message)` | `new Error(body.message)` | ❌ **不带** |

同一份代码里对"失败"有两种形状。需要按状态码分支的调用方
（例如 `404` 表示"文档已不存在"）在业务码路径下拿不到 `status`。
定级 **P2**。

---

## 4. Store / 状态管理

`stores/auth.ts`（58 行）**是全部状态管理**。实测：

| 数据 | 当前位置 | 是否跨页面 |
| --- | --- | --- |
| 当前用户 / 昵称 / 角色 | `stores/auth.ts`（唯一全局） | ✅ 全局 |
| token | `api/client.ts` 的 `tokenRef`（响应式） | ✅ 全局 |
| 知识库列表 | `KnowledgeBaseListView`、`AgentView`、`AgentsView` **各自拉取** | ✅ **跨 3 页** |
| 会话列表 / 消息 | `AgentView` | 单页 |
| Agent 列表 | `AgentsView`、`AgentView` **各自拉取** | ✅ 跨 2 页 |
| 工具列表 | `AgentsView`、`AgentView` **各自拉取** | ✅ 跨 2 页 |

**结论**：知识库 / Agent / 工具三份列表确实在多个页面之间被重复拉取，
引入状态管理（Pinia 或继续用 `reactive`）**有实测依据**。
但按 §四十 的告诫，**不应一次建四五个 store**——先建"知识库 + Agent"两个即可，
chat 的会话/消息只服务单页，不需要进全局。

---

## 5. 共享组件

`components/` 只有 **2 个文件**，且都是弹窗：

```
components/ProfileDialog.vue          212 行
components/ChangePasswordDialog.vue   155 行
```

**即：视图之下零组件化。** 四个视图用 grep 检查 `import ... from '@/components/` 的结果是
**空**——没有一个视图复用了任何本地组件（`App.vue` 除外，它挂了上面那两个弹窗）。

任务书 §八 列出的组件，**现状全部不存在**：

| 期望组件 | 现状 |
| --- | --- |
| `EmptyState` / `ErrorState` / `LoadingState` | 不存在。`el-empty` 只在 **2 处**出现（`KnowledgeBaseListView:227`、`AgentsView:387`） |
| `ConfirmDialog` | 不存在。**8 处**手写 `ElMessageBox.confirm`，选项各异 |
| `FileUploader` / `FileUploadList` / `UploadItem` | 不存在。上传逻辑内联在视图里 |
| `KnowledgeBaseCard` / `DocumentList` / `DocumentStatus` | 不存在 |
| `AgentCard` / `AgentSelector` / `KnowledgeBaseSelector` / `ToolSelector` | 不存在 |
| `ChatMessage` / `ChatComposer` / `CitationCard` / `ToolCallCard` / `RetrievalCard` | 不存在（全部内联在 `AgentView.vue` 与 `KnowledgeBaseDetailView.vue` 的模板里） |
| `StatusBadge` | 不存在（各处直接写 `el-tag` + 中文映射） |

---

## 6. 页面状态

### 6.1 已有状态词汇（各页各写一套）

| 视图 | 页面级 | 面板级 | 条目级 |
| --- | --- | --- | --- |
| `KnowledgeBaseDetailView` | `loading`（`v-loading` 整页） | `detailLoading`（阅读面板） | **`busy` 令牌**（`'delete:' + doc.id`） |
| `AgentView` | — | `loadingList`（会话列表）、`loadingMessages`（消息区） | — |
| `AgentsView` | `loading` | — | — |
| `KnowledgeBaseListView` | `loading` | — | — |

### 6.2 【正面，不要动】详情页的加载粒度是正确的

我一开始怀疑 `KnowledgeBaseDetailView:612` 的整页 `v-loading` 会让"删一个文档"把整页罩住
（§三十八 明确禁止）。**核实后这个怀疑不成立**：

- `loading`（163/174）：只在页面级数据加载时使用；
- `detailLoading`（183/211）：只罩阅读面板；
- 文档删除走的是 **`busy.value = 'delete:' + doc.id`**（501 行）——**按文档独立**。

**这是本仓里最好的一个 loading 粒度范例，应当推广成统一机制，而不是被重构掉。**

反面则是：**`busy` 令牌这个模式只用了一次**。其余操作要么靠按钮 `:loading`
（`KnowledgeBaseDetailView` 7 处 / `AgentsView` 4 处 / `KnowledgeBaseListView` 2 处 /
`LoginView` 1 处 / **`AgentView` 0 处**），要么什么都没有。

### 6.3 【P2】Empty 与 Error 混在一起

- 详情页的失败只有 `ElMessage.error('文档加载失败：…')`（209 行）——**一个 3 秒消失的 toast**；
- toast 消失后，面板落到 `v-if="filteredDocuments.length === 0"` 的
  `"没有匹配的文档"`、或 `"这篇文档还没有切片"`；
- 于是**"加载失败"最终呈现给用户的是"这里没有数据"**。

这正是 §四十六 点名要避免的混淆。缺的是"错误态 + [重试]"这套 UI。

---

## 7. 重复逻辑

| 重复项 | 实测 | 建议 |
| --- | --- | --- |
| **SSE 类型定义** | 2 份（见 3.2），已分叉 | 提升为 `types/stream.ts` 单一来源 |
| **Markdown 排版样式** | `.markdown` 相关规则在 `KnowledgeBaseDetailView` **20 处**、`AgentView` **15 处**，`styles/` 下**一份都没有** | 抽到 `styles/markdown.css` |
| **跨视图重复类名** | `.card` 6 / `.conversation` 6 / `.hint` 5 / `.docs` 5 / `.citations` 5 / `.outline` 4 / `.facts` 4 / `.workflow` 3 / `.welcome` 3 | 提升为公共类或组件 |
| **确认弹窗** | 8 处手写，选项不同 | 统一为 `ConfirmDialog` |
| **每个视图自己写 `autoImport`/轮询** | 详情页与列表页各有一份导入轮询 | 抽 `useImportTask` composable |
| **知识库 / Agent / 工具列表拉取** | 3 处 / 2 处 / 2 处 | 抽 store |

---

## 8. 大组件（本次重构的主目标）

| 文件 | 行数 | ref | reactive | computed | watch | function | try/catch | 本地组件 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `KnowledgeBaseDetailView.vue` | **1855** | **36** | 0 | 6 | 1 | **31** | 13 | **0** |
| `AgentView.vue` | **1691** | 19 | 1 | 9 | 1 | 30 | 11 | **0** |
| `AgentsView.vue` | **1013** | 19 | 2 | 3 | 0 | 17 | 7 | **0** |
| `KnowledgeBaseListView.vue` | 503 | 7 | 2 | 0 | 0 | 10 | 4 | **0** |
| **合计** | **5062** | **81** | 5 | 18 | 2 | **88** | 35 | **0** |

`KnowledgeBaseDetailView.vue` 一个组件同时管理：
文档列表 / 文档详情 / 上传 / 导入任务轮询 / 工作流 / 切片 / 检索 / 问答 / 关联 / 相关推荐 /
左侧与右侧两个选中项 / 三个面板的独立 loading。

**它的自然分区（模板实测）**：

```
page-head（613）
import-result（658）           ← 导入结果
最近导入（660）                ← 文档列表
知识工作流（713）              ← workflow 步骤
el-tabs（766-1030）
 ├─ 正文     → 含 关联（804） + 相关推荐（853）
 ├─ 切片 N
 ├─ 检索
 └─ 问答
```

`AgentView.vue` 的自然分区：`rail`（会话侧栏，含折叠钮）→ `messages`（含 `retrieval` / `citations` / `tool` 三个内联块）→ `composer-options`（模式 / 知识库 / Agent 选择）→ `composer`。

---

## 9. 技术债务汇总

| # | 项 | 级别 | 证据 |
| --- | --- | --- | --- |
| 1 | 三个巨型视图 4559 行、**视图之下零组件化** | **Refactor（主目标）** | 第 1 / 8 节 |
| 2 | **SSE 类型两份且已分叉** | **P1** | 3.2 逐行 diff |
| 3 | **E2E 全面依赖中文文案**（79 `has-text` + 91 含中文选择器、**0 个 `data-testid`**） | **P1（重构前置）** | 第 11 节 |
| 4 | 缺 `layouts/`、`types/`；`composables/` 仅 1 个装饰性 `useCountUp` | Refactor | 第 5 节 |
| 5 | 认证接口放在 `api/knowledge.ts` | Refactor | 3.1 |
| 6 | `api/agent.ts` 与 `api/agents.ts` 命名近乎重复 | Refactor | 3.1 |
| 7 | `.markdown` 排版样式在两个视图各存一份 | Refactor | 第 7 节 |
| 8 | 错误路径不对称（业务码 reject 丢 `status`/`code`） | P2 | 3.3 |
| 9 | 无 404 兜底路由；`meta.title` 定义了却无人消费 | P2 | 第 2 节 |
| 10 | 无 `ErrorState`，失败最终表现为"空态" | P2 | 6.3 |
| 11 | 8 处手写确认弹窗，无统一组件 | P2 | 第 5 节 |
| 12 | 缺统一 `useLoading`，粒度靠各页自觉 | Refactor | 6.2 |
| 13 | 无组件/交互测试框架（vitest / @vue/test-utils 均缺） | Refactor | 第 0 节 |

---

## 10. 用户流程

主流程（注册 → 导入 → 问答 → Agent → 历史）在后端层面**已完整且连续两轮 E2E 全绿**
（见 `docs/final-product-acceptance-report.md`）。前端的问题是**表达层**：

| 环节 | 现状 |
| --- | --- |
| 注册 | 确认密码 ✅ / 实时校验 ✅ / `undefined` 兜底 ✅ / **密码强度 ✗**（§二十七） |
| 登录 → 首页 | `redirect` 正确 ✅；外壳挂载不再需要 F5 ✅（`tokenRef` 修复） |
| 新用户空态 | 知识库列表页有正确的 `el-empty` 引导 ✅；**对话页**已按 UA-003 修掉伪造示例 ✅ |
| 导入资料 | 多格式 ✅；取消 = no-op ✅；**多文件逐项状态 ✗**（§二十二，仅显示"当前文件"） |
| 导入完成 → 可检索 | ✅ 已自动串接 workflow（UA-014） |
| 检索 0 命中 | **会话页**有专用卡片与"知识库里没有命中"标签 ✅；**知识库问答页**只有 `检索到 0 个片段` 标签，**无专用空态与 [导入资料]/[重新提问] 动作** → **P2 缺口**（§十六） |
| Citation | ✅ 刷新后仍在（UA-016 回归） |
| Agent 选择 / 回显 / 切换 | ✅ 已修 |
| 流式 | ✅ 实时增长（UA-016） |
| 停止 | ✅ 四场景通过 |
| 侧栏 | 会话侧栏可折叠 ✅（UA-007）；**全局导航不可折叠**（UA-012） |

---

## 11. 【P1，重构前置】E2E 的文案依赖是本次重构的最大阻塞项

实测 16 个 `verify-*.mjs`：

| 指标 | 数量 |
| --- | --- |
| `has-text(...)` 选择器 | **79** |
| 含中文字面量的选择器（`has-text` / `getByText` / `getByPlaceholder` / `getByLabel` / `name:`） | **91** |
| **`data-testid`** | **0** |

集中度最高的四个脚本：`verify-real-user-flow`（16 `has-text` / 13 含中文）、
`verify-agents`（14 / 14）、`verify-retrieval-ui`（13 / 18）、`verify-security`（8 / 8）。

**这不是理论风险，今天已经发生**：UA-021 只把按钮文案「上传 Vault」改成「导入资料包」，
就导致 `verify-doc-lifecycle.mjs` 里 **3 个选择器静默失效**（按文案定位，不报错，只是匹配不到）。

**结论**：在动任何 UI 文案之前，必须先完成 §五十 的 `data-testid` 注入。
否则重构过程中每一次文案调整都会击穿一批 E2E，而 E2E 恰恰是重构唯一的回归网。
**顺序不能颠倒。**

---

## 12. 交互冲突

| # | 冲突 | 证据 | 级别 |
| --- | --- | --- | --- |
| 1 | **一次失败弹两次提示** | 拦截器已经 `ElMessage.error(body.message)`，而 5 处 `catch` 又弹一次带前缀的：`AgentView:171`「绑定失败：…」、`AgentView:233`「切换失败：…」、`AgentsView:363`「评测失败：…」、`KnowledgeBaseDetailView:88`「工作流执行失败：…」、`KnowledgeBaseDetailView:209`「文档加载失败：…」 | **P2** |
| 2 | 提示调用点分散 | `ElMessage.*` 共 **46 处**，分布在 8 个文件（`AgentView` 11 / `KnowledgeBaseDetailView` 12 / `KnowledgeBaseListView` 10 / `AgentsView` 9 / `LoginView` 2 / 两个弹窗各 1）；`ElMessageBox.confirm` **8 处**全手写 | P2 |
| 3 | 全局导航与对话侧栏**两套交互语言** | 全局导航（`App.vue`）只靠 CSS 媒体查询在 <900px 自动收成 68px 图标条，**用户无法主动折叠**；对话侧栏（`AgentView`）有显式折叠钮。同一产品里两种行为 | P2 |
| 4 | 评测集仍在用户可达的顶层 | `AgentsView:654` 的「跑评测集」是页头按钮。§三十三 的目标位置是"Agent 管理 → 高级工具 → 评测" | P2 |

---

## 13. Bug 风险

按"已实测确认"与"需实测确认"分开列，**不把推测当结论**：

### 已确认的存在风险

| # | 风险 | 依据 |
| --- | --- | --- |
| 1 | 未匹配路由 → 空白页，无 404 | 路由表无 `pathMatch` |
| 2 | 业务码失败路径缺 `status`/`code`，调用方按状态码分支会拿到 `undefined` | `client.ts:90` |
| 3 | 单库问答页无法表达"检索失败"（类型缺字段），未来需求会静默写不出来 | 3.2 |
| 4 | 全文案定位的 E2E：改文案即断 | 第 11 节 |
| 5 | 加载失败最终显示为"没有数据"，用户无从判断该重试还是该导入 | 6.3 |

### 本轮**未**发现（说明句，用于避免重复排查）

- **`ConversationServiceImpl.delete` 的记忆级联**：已修并于今日在运行实例上取证（UA-018）。
- **空知识库 0 命中仍调 LLM**：三条路径全短路，**不调模型**。
- **流式不实时**：已修（UA-016）。
- **取消文件选择 → 死 loading**：已修，两处均为 no-op。
- **`欢迎回来 undefined`**：已修，且有兜底。
- **详情页 loading 粒度**：**正确**（见 6.2），不属于风险。

### 保留但不属本次范围（已有归档）

`docs/future-improvements.md` 第 1–20 条（软删墓碑 / ES 索引壳 / 记忆残留 / 首包体积 /
`verify-retrieval-ui` 的固定超时窗口等），本次不动。

---

## 14. 优先级总表

| 级别 | 数量 | 项 |
| --- | --- | --- |
| **P0** | **0** | 无数据丢失 / 越权 / 永久 Loading / 跨用户污染 |
| **P1** | **2** | ① SSE 类型两份且已分叉（数据契约）；② E2E 文案依赖 91 处、`data-testid` 为 0（**重构前置阻塞**） |
| **P2** | **6** | 双重提示（5 处）；无 404 与 `meta.title` 未消费；无 `ErrorState`；单库问答页无 0 命中空态；错误路径不对称；密码强度缺失 / 评测集仍在顶层 / 双套侧栏交互 |
| **Refactor** | **7** | 巨型组件与零组件化；缺 `layouts/`+`types/`；`.markdown` 样式重复；认证在 `knowledge.ts`；`agent.ts` 命名；无统一 loading；无组件测试框架 |

---

## 15. Phase A 结论与下一步

**结论：任务书对"症状"的判断基本准确，但有两处前提需要修正（Pinia 不存在、无组件测试框架），
且真正需要优先处理的是任务书里权重不高的一项——E2E 的文案依赖（§四十九/五十）。**

理由是硬的：本次重构会**大范围改文案与 DOM 结构**，而 91 处选择器靠中文文案定位。
若不先注入 `data-testid`，重构一旦开始，回归网就是**一起被击穿的**——
任务书 §六十三 自己也写了"不要修改测试断言来掩盖产品 Bug"，但按现状，
重构后不改断言就根本跑不起来。

**因此建议的实施顺序（对任务书 §五十六 的一处调整）**：

```
Phase A  只读审计                              ← 本文件，已完成
Phase 0  ★新增前置：data-testid 注入 + 选择器改造   ← 建议插在 B 之前
Phase B  信息架构设计（纯设计，不写代码）
Phase C  Design System（tokens / 状态词汇 / 变量）
Phase D  Layout 抽取（AppLayout / WorkspaceLayout）
Phase E  Auth
Phase F  Knowledge Base
Phase G  Chat
Phase H  Agent
Phase I  Evaluation / Developer Tools
Phase J  全量 E2E 两轮 + 真实浏览器验收
```

**Phase 0 为什么不能省**：它同时满足任务书 §五十（加 `data-testid`）与 §四十九
（选择器改用稳定定位），且是唯一能保证"后续每个 Phase 都能真实回归"的前提。
它本身也是**零产品风险**的改动——只加属性、不动 UI。

**下一步需要的决策（3 项）**：

1. **是否采纳插入 Phase 0**（testid 先行）？这是我对任务书顺序的唯一修改建议。
2. **状态管理选型**：引入 Pinia，还是沿用现有 `reactive` 手写 store？
   （实测确实存在跨页面重复拉取，两种都能解决；引入 Pinia 需加 1 个依赖。）
3. **`docs/frontend-refactor-report.md`（§六十四）是全部 Phase 结束后产出，
   还是每个 Phase 追加一节？**

在上述决策确定前，**不修改任何代码**（遵循任务书 §五 与 §四 原则 4）。
