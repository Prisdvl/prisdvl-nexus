# 前端 Design System（Phase C）
> 状态：**历史（Vue 3 时代 Phase C 设计系统）**。

> ⚠️ **历史文档（Vue 时代，已归档）**：本文描述的是**已被取代的 Vue 3 前端**（原 `web/`，
> 现归档在 `docs/archive/web-vue/`）。当前产品前端是 React 19 工程 `frontend/`：
> 设计令牌 `frontend/src/styles/tokens.css`、通用组件 `frontend/src/components/`、
> 动效积木 `frontend/src/bits/`、页面 `frontend/src/pages/`、后端契约层 `frontend/src/lib/api.ts`。
> 本文里的 `web/src/...` 路径、Element Plus 选择器、`AgentView` / `KnowledgeBaseDetailView` 等
> 组件名**只对归档副本有效**，不能当作当前实现的规范。

> 阶段定位：**建立规则与公共积木**，不重写两个巨型页面的业务。
> 上游：`docs/archive/reports/frontend-architecture-audit.md`（Phase A）→
> `docs/frontend-information-architecture.md`（Phase B）→ **本文件**。
> 边界遵守：未新增页面 / 未删除路由 / 未加 Home / 未删 Evaluation /
> 未动后端 API 与 SSE 协议 / 未重写 `AgentView` 与 `KnowledgeBaseDetailView` 的业务。

---

## 0. 结论先行

Phase C 的十块工作，**完成 8 块**：

| 块 | 状态 | 产出 |
| --- | --- | --- |
| A. Design Tokens | ✅ | `styles/semantic.css`（语义层，132 个 token 补齐语义名/字号/z-index/交互态） |
| B. 通用状态组件 | ✅ | `StateEmpty` / `StateError` / `StateLoading` / `StatusBadge` + `status.ts` |
| C. ConfirmDialog | ✅ | `ConfirmDialog.vue` + `useConfirm.ts`；**7 处**手写 confirm 已迁移 |
| D. Layout 基础 | ❌ 未做 | 见 §11 剩余工作 |
| E. Pinia | ✅ | `authStore`（含 token 迁入）/ `knowledgeStore` / `agentStore` |
| F. Composables | ✅ | `useAsyncState` / `useImportTask`（+ `useConfirm`） |
| G. SSE 类型统一 | ✅ | `types/sse.ts` 为唯一真实来源；两个 api 文件残留重复定义 **0** |
| H. API / 错误处理 | ✅ | 消除双 toast；`ApiError` 带 `code`；6 处静默失败点补 catch |
| I. Markdown 样式 | ✅ | `styles/markdown.css`；从两视图删除 12 条重复 |
| J. 基础组件 | ❌ 未做 | 见 §11 剩余工作 |

**必须说清的三条边界**，否则这份文档会被当成"重构已完成"：

1. **视图层尚未使用新 store。** 验收项"知识库/Agent/工具不再多处重复维护"**未达成**——
   store 建好了，但三个视图仍在各自拉取。把它们接上 store 属于**页面迁移**，是 Phase D/F/G 的事，
   Phase C 明确禁止大改视图。
2. **E2E 全量未跑。** Docker Desktop 未运行，整栈不在。部分关键路径已单独验证（见 §12）。
3. **Layout 与基础组件未建。** 它们是纯积木，不影响本阶段已交付的 8 块。

---

## 1. Design Tokens

### 做法：分层，不换皮

现有 `tokens.css` 的 51 个变量是**基础层（primitive）**，被四个视图的 4559 行 CSS 直接引用。
改名 = 全量改样式 = 一次无法靠读代码判断对错的视觉回归。

所以本阶段**只新增 `styles/semantic.css`（语义层），一个字都没改 tokens.css**：

```text
tokens.css     基础层：具体色值 / 间距值 / 圆角值      （不动）
semantic.css   语义层：这个值"为什么被用"              （新增）
```

迁移因此可以逐个组件推进：任何时候都能停下来，不需要一次性重写四页 CSS。

### 补齐的缺口（Phase C 实测审计）

| 类别 | 现状 | 本文件的补法 |
| --- | --- | --- |
| 品牌 | 只有 `accent-50…600`，**没有 `primary`** | `primary` / `primary-hover` / `primary-soft` / `on-primary` |
| 状态色 | 有 `ok` / `warn`，任务书要 `success` / `warning` | 语义名**别名并存**，指向同值——不改 `ok`/`warn`（4559 行 CSS 引用着） |
| 文字 | `text-strong` / `text` / `text-muted` / `text-faint` | `text-primary` / `text-secondary` / `text-muted` / `text-inverse` |
| 表面 | `surface-*` 四层 | 补 `background` / `background-raised` / `background-sunken` |
| 圆角 | `sm` / `input` / `card` / `overlay` / `chip` | 补标准名 `radius-xs` / `md` / `lg` / `xl` / `full` |
| **字号** | **完全没有**（h1/h2/h3 直接写 px，散在各处） | `--text-xs` … `--text-3xl` 七档 + 三档行高 + 三档字重 |
| **z-index** | **完全没有**（`.grid-backdrop` 硬编码 `z-index: 0`） | 七档命名层级，每档留 10 的空位 |
| 动效 | 已完整 | 补 `transition-fast/base/transform` 组合别名 |
| **交互态** | 散落各视图的 `:hover` / `:disabled` | `--focus-ring` / `--disabled-opacity` / `--disabled-cursor` |

字号与 z-index 是这一层里**最有价值的两项**：前者消掉了散落各处的魔法数字，
后者让"侧栏 / 下拉 / 遮罩 / 弹窗 / toast / tooltip"的层叠关系第一次变成可查的。

---

## 2. 状态组件

### 2.1 四态的判别式只有一条

> **服务端有没有说成功。**

| 态 | 判据 | 必含 | 禁止 |
| --- | --- | --- | --- |
| `loading` | 请求在途 | 骨架/遮罩 + `aria-busy` | 空白页 |
| `empty` | 成功，数据为 0 | 一句"还没有…" + **一个下一步动作** | 出现"加载失败"字样 |
| `error` | 没成功 | 一句"…失败了" + **[重试]** + 真实原因 | 出现"没有数据"字样 |
| `success` | 有数据 | 内容 | — |

**这条规范由组件 API 兜住，不靠自觉**：`StateEmpty` 在开发模式下会对
"没给 `action` 插槽"发警告。

### 2.2 四个组件

| 组件 | 关键设计 |
| --- | --- |
| `StateEmpty` | 强制 `action`；`variant` = page/card/inline；开发期缺 action 时警告 |
| `StateError` | 强制 `retry`（`retryable=false` 才不显示）；`role="alert"`；描述可放后端原话 |
| `StateLoading` | **四种粒度**：`page` / `section` / `list`（骨架屏）/ `inline` |
| `StatusBadge` | 词表来自 `status.ts`，页面不再各写一张映射表 |

### 2.3 `StatusBadge` 与 `status.ts`：中文词表的唯一真相

改造前状态词表有**两套**，散在两个视图：

```text
KnowledgeBaseListView:199   QUEUED→排队中  RUNNING→处理中  DONE→已完成 …
AgentsView:82               DRAFT→草稿  PUBLISHED→已发布  DISABLED→已停用
```

两份的后果不是"多写了几个字"，而是**同一个状态在两个页面可能显示不同，且没人说得清以哪个为准**。
现在合并到 `status.ts` 的 `STATUS_META`，改文案只改一处。

两个设计细节：

- **认不出的值回落到 `failed` 而不是 `done`。** 未知状态**不能**被显示成"已完成"——
  那会让用户以为成功了，而它可能根本没跑完。要看原始值用 `unknownStatusLabel()`。
- **检索标签不在这里。** 「已重排」「预算截断 N 段」是 L3/L4，语义上也和"任务状态"不是一回事。
  硬塞进来会让这张表失去意义。

---

## 3. ConfirmDialog

### 为什么要自己建，而不是包一层 `ElMessageBox`

两个硬需求是它给不了的：

1. **`loading`**：删除/停用点了确认后服务端要跑一会儿。`ElMessageBox` 的按钮不能异步等待——
   弹窗立刻消失，用户在这段时间里完全可以再点一次，于是发了两个请求。
2. **`danger` 让确认按钮变红**：危险操作的确认按钮不该和"保存"长得一样。

### 与 E2E 的契约（Phase 0 留下的）

Phase 0 给 8 处确认框加了 `customClass`（`nexus-confirm-*`），E2E 按它们点确认。
**探测器的实测结论**：

```text
OK  .nexus-confirm-logout 已进入 DOM
    根节点 = DIV · confirm-body nexus-confirm-logout
    位于 .el-dialog 内 = true
    confirm-submit 存在 | 文案 = 退出
    取消后卡片数 2 -> 2 (未误删)
    取消后错误 toast 数 = 0
PASS ConfirmDialog 契约全部满足
```

实现上刻意**不用 `#footer` 插槽**，把按钮放进内容根 div 里：`el-dialog` 是
`inheritAttrs: false` 且没有 `class` prop，写在它身上的 class 落到哪一处**不受控**。
自己排版就没有这个风险。

### 迁移中发现的两个真实问题

**① E2E 定位失效（我自己引入的）。** 危险操作用 `type="danger"` 后按钮 class 变成
`el-button--danger`，Phase 0 的 `.nexus-confirm-logout .el-button--primary` **匹配不到**。

修法不是迁就框架 class 名，而是改用组件自己的 `data-testid="confirm-submit"`——
这正是 Phase C §十七 说的"同一个用户动作仍要有同一个稳定锚点"。改了三处 E2E。

**② 点确认没反应（我自己引入的）。** `App.vue` 里同时监听了 `@update:visible` 并调
`settle(false)`，而 `ConfirmDialog` 先 emit `update:visible(false)`——它**抢先**把结果消费成"取消"，
随后 emit 的 `confirm` 已经无 resolver 可用。修法：不监听 `update:visible`。

两个都是探测脚本抓到的，不跑就发现不了。

### 顺带修掉的既有缺陷

`KnowledgeBaseListView.remove`、`AgentsView.toggleStatus/remove` **没有 `try/catch`**：
用户点取消时 `ElMessageBox` 会 reject，于是函数变成 rejected promise、`@click` 上产生未处理 rejection。
新的 `confirm()` 取消时返回 `false` 不抛，迁移时一并修掉。

### 为什么 `prompt` 保留 `ElMessageBox`

8 处里只有 1 处是 `prompt`（重命名会话），它**需要输入框**。
`ConfirmDialog` 不提供输入能力——那是有意的取舍，不是遗漏。

---

## 4. Store

### 4.1 token 从两个文件收到一个

改造前令牌与资料**分居两处**：`api/client.ts` 的 `tokenRef` 管令牌，
`stores/auth.ts` 的 `reactive({...})` 管资料。两处各自读 localStorage、各自判断"登着没有"。

现在令牌在 `authStore.token`，`client.ts` 只保留同名函数转发，**不再持有任何令牌状态**。
`stores/auth.ts` 末尾保留了 `saveSession` / `logout` / `displayName` / `isLoggedIn`
的函数式调用——**内部全部转发到 store，没有第二份状态**，
所以 `LoginView` / `App.vue` / `router` **一行都没改**。

### 4.2 `status` 是三态，不是两态

```ts
'unknown'  // 还没判定 —— 此时**不该**被当成游客踢回登录页
'authed'
'guest'
```

改造前只有"有令牌 / 没令牌"两种，profile 还没拉回来就被当成游客——
这正是"登录初始化竞态"的来源。

### 4.3 两个业务 store 的边界

| Store | 字段 | 消掉的重复 |
| --- | --- | --- |
| `knowledgeStore` | `list` / `loading` / `error` / `loaded` / `detailById` / `documentsByKbId` | 知识库列表**3 处**各自拉取 → 1 处 |
| `agentStore` | `list` / `loading` / `error` / `loaded` / `tools` / `usable` / `builtin` | Agent 列表 **2 处**、工具列表 **2 处** → 各 1 处 |

**刻意不进来的**（Phase C §九、§十）：

| 数据 | 归属 | 原因 |
| --- | --- | --- |
| 检索分页、当前选中文档、当前 tab、问答草稿 | View 局部 | 换一个页面还该不该看见它？不该 |
| 当前会话的消息、会话列表 | View 局部 | 进 Store 会让"退出后消息还在"变成 bug 而非特性 |
| 配置弹窗的表单草稿 | View 局部 | 弹窗关闭即销毁 |
| 试跑结果 | 不存 | 一次性回显，且可能很大 |

判别式就一条：**"换一个页面还该不该看见它"**。

两个 store 都带 `loaded` 标志，区分"没拉过"与"拉完了"——`loading === false`
有两种含义，只有前者才该发请求。

---

## 5. Composables

### `useAsyncState` —— 四态的代码落点

```ts
const { state, data, error, execute } = useAsyncState(() => listKnowledgeBases())
```

`state` 的取值与 `StateEmpty` / `StateError` / `StateLoading` **一一对应**，调用方不再自己判断。
两个关键行为：

- **失败时清掉旧数据**。留着它会让界面显示"成功时的旧内容"，而用户以为看到的是刚拉到的结果。
- **竞态防护**：快速切换筛选时先发的请求可能后回来覆盖新的结果，用自增序号丢弃过期响应
  （而不是 `AbortController`——大多数场景不需要真的中断请求）。

### `useImportTask` —— 收掉两处重复轮询

列表页与详情页各写了一遍轮询，两份的**终态判定、间隔、超时、失败处理**要人工保持一致。

```ts
const { task, polling, start, stop, refresh } = useImportTask()
```

要点：

- **终态是四个值**不是两个：`DONE` / `PARTIAL` / `FAILED` / `CANCELLED`。
  `PARTIAL`（部分成功）也是终态，此时该显示警告而不是继续转圈。
- `stop()` 之后仍在途的那次 `await` 返回时会被丢弃，否则组件卸载后还在写 ref。
- 超时**不算失败**，返回最后一次读到的，让调用方决定是继续等还是提示稍后刷新。
- 附带 `importStatusLabel` / `importIsPending`，让状态文案也只有一处。

---

## 6. SSE 类型

### 先纠正 Phase A 的一个说法

Phase A §3.2 记的是"类型被定义了两次，且已经分叉"。**实测后要修正**：
它们不是复制粘贴的重复，而是**两条链路真的发了不同的形状**。

| 事件 | 会话流 | 知识库问答流 | 结论 |
| --- | --- | --- | --- |
| `meta` | requestId + conversationId + userMessageId + assistantMessageId + modelRole + mode | requestId + citations + contextTokens + truncated + reranked + empty | **同名不同形**（后端事实） |
| `retrieval` | `RetrievalEvent.of(...)` | **同一个** `RetrievalEvent.of(...)` | **形状完全一致** |
| `done` | finishReason + stopped + citations | finishReason + error + errorCode + retryable + recordId + citations | 部分重叠 |

所以"合并成一个形状"会与后端不符并破坏功能；"保留两份"又回到分叉。
正解是**分层单一来源**：公共字段一份，链路专有部分显式 `extends`。

```text
types/sse.ts
  StreamRetrieval            唯一一份（两条链路共用，含 failedKnowledgeBases）
  StreamMetaCommon           requestId
    ├─ ChatStreamMeta
    └─ RagStreamMeta
  StreamDoneCommon           finishReason
    ├─ ChatStreamDone
    └─ RagStreamDone
  ChatCitation / RagCitation / RagCitationPreview
```

权威来源是后端三个文件（`SseEventNames.java` / `RetrievalEvent.java` /
`ChatStreamServiceImpl` / `RagStreamServiceImpl`），本文件的注释只是记录，改动前请先读它们。

### 修掉的一个真实缺陷

改造前 `api/knowledge.ts` 的 `StreamRetrieval` **漏了 `failedKnowledgeBases`**，
而后端两条链路都发这个字段。缺它的直接后果：知识库问答页在"某个库检索失败"时
无法显示"这次少搜了谁"，**界面会假装全都搜过了**。

### 验证

`verify-retrieval-ui.mjs`（桩 SSE 帧，不消耗 LLM 额度）**五条腿全绿**：

```text
⑧ 部分失败卡片：其中 1 个知识库检索失败
⑨ 失败明细悬浮提示：内部 FAQ：ES 连接被拒绝      ← failedKnowledgeBases 生效
⑩ 全部失败卡片：标签 ["2 个知识库","其中 2 个知识库检索失败","知识库本次都没有检索成功"]
⑫ 部分失败且零命中：["2 个知识库","其中 1 个知识库检索失败","其余知识库没有命中"]
PASS
```

### 顺带暴露的两个文档问题

- `knowledge.ts` 原先的 `StreamMeta` 其实就是 RAG 的 `meta` 帧（字段与 `StreamRetrieval` 重合），
  被错命名成 Meta，导致"meta 里的 citations"和"done 里的 citations"共用一个类型——
  而它们的字段根本不同。
- `RagCitationPreview`（meta 帧的引用预览）没有 index / snippet / score / referenced：
  `meta` 到达时回答还没开始，这些字段**不存在**。

---

## 7. API 与错误处理

### 双 toast 的根因

```text
拦截器：ElMessage.error(body.message)              ← 弹服务端原话
catch： ElMessage.error('绑定失败：' + e.message)   ← e.message 就是 body.message
```

用户看到两条几乎一样的提示。实测 5 处重复。

### 方案（三步一起做，缺一步就出问题）

1. 拦截器**不再对业务错误弹 toast**，改为抛带 `code` 的 `ApiError`。
   401 与"无 HTTP 响应的网络错误"仍弹——它们要自动跳转或全局提示，页面无从判断。
2. 已有的 5 处 catch **保留**：它们提供"哪个操作失败"的上下文，比裸 message 有用。
3. **补上审计查出的 6 处无保护调用的 catch。** 缺这一步，移除拦截器 toast 会让那 6 处
   **静默失败**——用户点了没反应也不知道成没成。

第 3 点是这一步的关键。只做 1+2 会把"双 toast"换成"静默失败"，那更糟。

`ApiError` 从 interface 升级出两个助手（`toApiError` / `isValidationError`），
让"哪些错误不可重试"只有一处定义。

---

## 8. Markdown 样式

### 这不是"复制粘贴的重复"，是同源已分叉

Phase A §7 记的是"两个视图各 20 / 15 处"。实测后要修正：

| 规则 | Chat 页 | 详情页 | 为什么不同 |
| --- | --- | --- | --- |
| `h1/h2/h3` | `margin: 1.4em 0 0.5em` | `1.6em 0 0.5em` **+ `scroll-margin-top: 16px`** | 详情页支持大纲跳转，标题不能贴顶 |
| `blockquote` 左边框 | `--accent-300` | `--accent-400` | 视觉微调 |
| `table` / `th` / `td` / `img` | 无 | 有 | Chat 的回答里没有表格与图片 |

所以做法是**抽出两边逐字相同的 6 条 + 保留分叉**，而不是强行按成一样——
后者会毁掉大纲跳转（`scroll-margin-top` 没了，跳过去标题会贴在视口顶边）。

结果：`markdown.css` 6 条统一，从两视图删除 12 条，分叉的 16 条留在原处。

### 一个不做就会静默失效的坑

markdown.css 最初写成 `.markdown :deep(code)`。`:deep()` **只在 `<style scoped>` 里有意义**，
在全局样式里它是个无效选择器——**不报错，但一个元素都匹配不上**，排版会整体失效。
转换后才真正生效（已验证产物里是 `.markdown a{` / `.markdown p`，无 `data-v` 限定）。

---

## 9. data-testid 规范

Phase 0 的 132 个 testid 在本阶段**全部保留**。本阶段新增的：

| testid | 用途 |
| --- | --- |
| `confirm-dialog` / `confirm-submit` / `confirm-cancel` | 确认框 |
| `confirm-description` | 危险操作的后果说明（替代 `.el-message-box__message`） |
| `state-empty` / `state-error` / `state-error-retry` / `state-loading` | 四态 |
| `status-badge-{kind}` | 状态徽标 |

**原则**（Phase C §十七）：同一个用户动作要有同一个稳定锚点。
所以确认按钮用组件自己的 `data-testid`，而不是 `.el-button--primary`——
后者绑在 Element Plus 的实现细节上，`type="danger"` 一改就失效。

---

## 10. 组件规范

| 规则 | 说明 |
| --- | --- |
| 只负责 UI + emit + props + slots | `ConfirmDialog` 不能直接删知识库，它只 `emit confirm` |
| 不知道 store / API | 业务逻辑在 View / Store / composable |
| 必须有明确 TS 类型 | 不用 `any` / `Record<string, any>` |
| 规范由组件兜 | `StateEmpty` 缺 `action` 时开发期警告 |
| 图标用字符串映射 | `icon="folder"`，不必每个使用点自己 import |

---

## 11. 剩余工作（明确不在本阶段）

| 项 | 为什么没做 | 归属 |
| --- | --- | --- |
| **Layout 组件**（`AuthLayout` / `WorkspaceLayout`） | 建了不迁移等于死代码；迁移 `App.vue` 是页面迁移 | Phase D |
| **20 个基础组件** | 同上。且边界要在真实迁移中验证才靠谱 | Phase D 及之后 |
| **视图接 store** | 验收项 3/4/5 的实质是"页面迁移"，Phase C 禁止大改视图 | Phase D / F / G |
| **819 行 CSS 的归属** | 样式不因组件化而消失，需 token 层单独处理 | Phase C' 或 D |
| **Element Plus 控件封装** | 现有 400 行 `element.css` 已在做这件事 | Phase D |

---

## 12. 验证情况（必须如实记录）

| 项 | 状态 | 证据 |
| --- | --- | --- |
| `npm run build`（含 `vue-tsc`） | ✅ 通过 | 每一步都跑 |
| ConfirmDialog E2E 契约 | ✅ 通过 | 探测脚本：class 落位 / 确认 / 取消零误删 / 零错误 toast |
| SSE 类型统一后 RAG 链路 | ✅ 通过 | `verify-retrieval-ui` 五条腿全绿 |
| `markdown.css` 生效 | ✅ 通过 | 产物里为 `.markdown a{` / `.markdown p`（无 data-v 限定） |
| **E2E 全量 17 个脚本** | ❌ **未跑** | **Docker Desktop 未运行**，整栈（backend/ES/MySQL/Ollama）不在 |
| 浏览器真实验证 | ⚠️ 部分 | ConfirmDialog 与 RAG 两条路径已实测；其余待整栈恢复 |

**不能把"部分验证"写成"验证通过"。** 待 Docker 恢复后需补跑全量 E2E。

---

## 13. Phase D 的输入

| # | 输入 | 出处 |
| --- | --- | --- |
| 1 | Layout 组件（`AuthLayout` / `WorkspaceLayout` / `AppSidebar`）的职责边界 | §11 |
| 2 | 20 个基础组件的清单与 props 契约 | §2、§3、§10 |
| 3 | 三个 store 的字段与动作（已就绪，等页面接入） | §4 |
| 4 | 三个 composable（已就绪，等页面接入） | §5 |
| 5 | 四态判别式与组件对应关系 | §2.1 |
| 6 | L1–L4 层级判据 | Phase B §17.1 |
| 7 | testid 清单（132 + 本阶段新增 9） | §9 |
| 8 | 禁止出现清单（可转为 E2E 断言） | Phase B §18 |
