# React 前端 Zustand + Hooks 报告（Phase R4）

> 阶段目标：把 R3 的基础设施**深化**为可复用的数据流与命令式交互。
> 未实现：任何业务页面（Knowledge / Chat / Agent 的界面）。
> 上游：R0 骨架 → R1 Design System → R2 Layout → R3 API/鉴权 → **本文件**。

---

## 0. 结论先行

| 项 | 结果 |
| --- | --- |
| `npm run build` | ✅ 通过 |
| `verify-layout.mjs`（R2 回归） | ✅ 退出码 0 |
| `verify-auth.mjs`（R3 回归） | ✅ PASS 25 / FAIL 0 / SKIP 0 |
| 新增 | `agentStore` + Agent/Tool API 与类型 + `useAsyncState` + `useConfirm` |
| **刻意未做** | `useChatStream` / `useConversation` / `useFileUpload` / `useImportTask` —— 见 §6 |

**R4 没有独立任务书**，范围由后端 API 的真实可用性决定，见 §2。

---

## 1. 实际目录变化

```text
web-react/src/
├── api/
│   ├── agent.ts                 新增：7 个端点
│   └── types/agent.ts           新增：Agent / Tool / 请求体（逐字段抄后端）
├── stores/
│   └── agentStore.ts            新增：第三个 store
├── hooks/
│   ├── useAsyncState.ts         新增：通用异步数据流（四态 + 竞态 + abort）
│   └── useConfirm.tsx           新增：命令式确认 + ConfirmHost
├── hooks/useLogout.ts           修改：补 agentStore.reset()
└── layouts/WorkspaceLayout.tsx  修改：挂 ConfirmHost
```

---

## 2. 范围是怎么定的（先勘察，后动手）

任务书 §65 列了 7 个 hook。我按**后端 API 是否已存在**逐个核对：

| Hook | 依赖的后端能力 | 决定 |
| --- | --- | --- |
| `useAsyncState` | 无（纯前端） | ✅ 做 |
| `useConfirm` | 无（复用 R1 的 `ConfirmDialog`） | ✅ 做 |
| `agentStore` | `AgentResourceController` + `ToolController` **齐全** | ✅ 做 |
| `useChatStream` | SSE 协议存在（`/api/chat/stream` 等），但**无消费方** | ⏭ 下一轮（见 §6） |
| `useConversation` | 端点存在，但会话列表 UI 属 R8 | ⏸ 随 R8 |
| `useFileUpload` | 端点存在，但导入 UI 属 R7 | ⏸ 随 R7 |
| `useImportTask` | `/api/import-tasks/{taskId}` 存在，导入 UI 属 R7 | ⏸ 随 R7 |

**判据**：现在写后三个 hook，等于**为不存在的调用点写空壳**——
它们既无页面消费，也无法验证，而且会在真正实现时发现签名不对、白写一遍。

---

## 3. Agent / Tool 类型与 API

### 逐字段抄后端，不发明字段

```text
AgentResponse  record(Long id, String code, String name, String description,
                     String systemPrompt, String modelRole, String answerPolicy,
                     Double temperature, String status, Boolean builtin,
                     List<Long> kbIds, List<String> toolCodes,
                     LocalDateTime createdAt, LocalDateTime updatedAt)
ToolResponse   record(String code, String name, String description, String source,
                     String category, boolean enabled, boolean builtin)
```

### `id` 与 `code` 是两个标识

后端**同时**给了 `id`（主键，路径参数用它）与 `code`（业务编码，可读）。

任务书 §36 要求「统一 `id / name / agentId / agentName`，不要前后端各叫各的」。
本项目的做法是：**完全照搬后端字段名，不发明 `agentName`**。
Vue 侧出过 `agentName` / `agentId` 手工映射混乱（Phase A §3.1），
根因就是前端自造字段——保持一致即可。

**前端以 `id` 为主键**（URL、React key），`code` 仅作显示。
`toolCodes` 存的是工具**编码**（对应 `Tool.code`），不是 id——这点在类型注释里写明了。

### 端点

```text
GET    /api/agents                      → PageResult<AgentResponse>
POST   /api/agents                      → AgentResponse
GET    /api/agents/{agentId}            → AgentResponse
PUT    /api/agents/{agentId}            → AgentResponse
POST   /api/agents/{agentId}/duplicate  → AgentResponse
DELETE /api/agents/{agentId}            → Void
GET    /api/tools                       → ToolResponse[]
```

**未做**：`test-chat`（试跑）与状态切换——它们需要页面上下文（R9）。

---

## 4. agentStore

### 为什么 Agent 值得一个全局 store

判据来自 Phase B §14.2（跨页面共享且单一真相 → Store）。
Agent 清单在三处出现：Agents 列表页、Chat 顶部的 Agent 选择器、
知识库详情页的"用哪个 Agent 提问"。三处各拉一次 = 三份可能不一致的副本。

### 写操作**并入列表**而不是重新拉

```ts
const created = await createAgentApi(payload)
set({ list: [created, ...get().list] })   // 不是 fetchList({ force: true })
```

省一个往返，且用户立刻能在列表里看到自己刚建的。
**风险**：若后端在 create 时做了归一化（trim、默认值填充），返回的 `created` 已经是完整对象，
所以直接用它是安全的——**这一点依赖"后端返回完整实体"这个契约**，
Vue 侧一直是这么用的（`createAgent` 的返回值直接进列表）。

### 明确不进来的

| 东西 | 归属 | 原因 |
| --- | --- | --- |
| `editingAgent` / 表单草稿 | R9 页面局部 | 弹窗关闭即销毁 |
| `testChatResult` | R9 页面局部 | 一次性回显，可能很大 |
| 侧栏折叠 | 组件 + localStorage | 与 Agent 无关 |

### 工具清单失败不阻断页面

`fetchTools` 失败只记 error，不改 `list`。
理由与 `initialize()` 里"知识库不可用仍算登录"一致：
Agent 列表仍可用，只是编辑器里没有可勾选项。

---

## 5. 两个通用 hook

### useAsyncState

把"四态"收敛成一个 `state` 字段，与 R1 的 `StateEmpty` / `StateError` / `StateLoading` 一一对应。

```ts
const kb = useAsyncState((signal) => getKnowledgeBase(id, signal), [id])
```

三个关键行为：

| 行为 | 不做会怎样 |
| --- | --- |
| **失败时清掉旧数据** | 界面继续显示"成功时的旧内容"，而用户以为看到的是刚拉到的——**数据撒谎** |
| **竞态防护**（自增序号丢弃过期响应） | 快速切筛选时先发的请求后回来，覆盖新结果 |
| **卸载 / 参数变化时真 abort** | 只"忽略结果"的话，请求仍跑完、仍占连接与后端资源 |

依赖由调用方显式给出（`fn` 用闭包捕获参数），**不用**把参数塞进依赖数组自动推导——
那需要 `JSON.stringify` 参数，遇到函数/DOM 元素就失效，是个陷阱。

### useConfirm

五类互不相干的确认点（删库 / 删文档 / 停 Agent / 整包同步 / 重建索引）共用**一个**实例。

```tsx
const confirm = useConfirm()
if (!(await confirm({ title: '确认删除？', description: '…', danger: true }))) return
await remove(id)
```

两个设计点：

1. **取消返回 `false`，不 reject。** Vue 侧用 `ElMessageBox.confirm()` 时取消会 reject，
   于是每处都要 `try/catch` 包住才能避免未处理 promise 拒绝（Phase C 修过三处）。
2. **hook 不渲染 UI。** `useConfirm` 只持有状态与 resolver，
   渲染由 `ConfirmHost` 组件完成（挂在 `WorkspaceLayout`）。

---

## 6. 刻意未做的四个 hook

### `useChatStream` —— 下一轮做，值得做

**它不是"没依赖"，而是"该单独一轮做"**：

- 后端有**两条** SSE 链路（`/api/chat/stream` 与 `/api/knowledge-bases/{id}/ask/stream`），
  事件形状**不完全相同**（Phase C §6 已实测：两条链路的 `retrieval` 是同一个
  `RetrievalEvent.of`，但 `meta` / `done` 不同形）；
- 仓促实现极易重蹈 Vue 侧"两条链路类型分叉"的覆辙；
- 而它**可以被独立验证**——用桩 SSE 帧（Vue 侧 `verify-retrieval-ui` 就是这么做的，
  不消耗 LLM 额度）。

所以它值得一个独立阶段：`api/types/sse.ts`（单一来源）+ `lib/sse.ts`（解析器）
+ 桩帧验证脚本，然后才是 hook。

### 另三个随对应阶段做

`useConversation`（R8 会话列表）、`useFileUpload` / `useImportTask`（R7 导入）
的端点都已存在，但它们的 UI 属后续阶段。现在写出来的签名在真正实现时大概率要改，
不如等那时候一次做对。

---

## 7. 顺带修的一处数据串号隐患

`performLogout()` 原来只清 `authStore` + `knowledgeStore`。
R4 加了 `agentStore` 后**必须补上**，否则：

> 用户 A 退出 → 用户 B 登录 → 界面显示 A 的 Agent 列表
> （因为 `loaded` 还是 true，再拉取会被 `if (loaded && !force) return` 挡掉）。

Agent 比知识库更容易被漏掉，因为它常被当成"全局配置"而不是"用户数据"——
但后端的 Agent 是带 `id` 的**用户资产**（`GET /api/agents` 按登录态过滤）。

---

## 8. 测试结果

```text
npm run build              ✅ 通过
scripts/verify-layout.mjs  ✅ 退出码 0（R2 未被破坏）
scripts/verify-auth.mjs    ✅ PASS 25 / FAIL 0 / SKIP 0（R3 未被破坏）
```

**边界**：`web/`（Vue）、`nexus-*`、旧 E2E、`deploy` 零改动；`docs/update.txt` 未碰。

### 构建期抓到的三个类型错误

| 错误 | 原因 |
| --- | --- |
| `AgentQuery` 未导出 | 它定义在 `api/types/agent.ts`，却从 `@/api/agent` 导入 |
| `executeRef.current()` 参数不匹配 | `A` 是泛型，TS 无法假定"可以不传参数" |
| `confirm.host` 不存在 | 显式返回类型 `(options) => Promise<boolean>` **覆盖掉**了 `Object.assign` 的结果 |

第三个值得记：给函数标注返回类型时，**附加在函数对象上的方法会丢**。
现在 `ConfirmFn` 接口把它显式声明出来了。

---

## 9. 下一阶段建议

**R5（Auth 收尾）** 或 **R4b（SSE 类型 + 解析器）**。我的建议是**先做 R4b**：

- SSE 是 R8 的地基，越晚统一代价越高（Vue 侧已经付过一次"两条链路分叉"的代价）；
- 它可以用桩帧独立验证，不消耗 LLM 额度；
- 做完 R4b，R8 就能直接接上，不用回头改类型。

之后 R5（注册流程 / 个人资料 / 修改密码，R3 已留好 API 缺口）→ R6（知识库列表页，
`knowledgeStore` 已就绪，只差页面）。
