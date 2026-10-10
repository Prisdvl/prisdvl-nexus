# P1-1 接后端改动清单（施工单）

> **状态**：设计稿，**未改任何业务代码**
> **前置**：已完成 P0-2，实测契约见 `docs/api-observed-standalone.md`
> **本文件定位**：把「接后端要动哪些地方」摸清楚并排出顺序，供后续逐刀落地。

---

## 0. 结论先行

**「接后端」不是加一个请求层，是改 4 个store + 1 个类型体系 + 1 个路由时序。**

按当前勘察，工作量分布：

| 区块 | 难度| 说明 |
|---|---|---|
| 雪花 id `number → string` | 中 | 类型层面简单，但会波及 6 个文件 + 3 个 store 的签名 |
| `lib/auth.ts` 异步化 | **高** | 破坏"同步可得、不闪登录页"这个现有性质，是最容易引入回退的地方 |
| 会话 store 换数据源 | 中 | `sessions.ts` 注释说"只换 loadPersisted/persist"——**这句话低估了工作量** |
| CORS / proxy | 低 | 加 vite proxy 即可，但生产要另配 |
| 相对时间 `updated` | 低| 后端给 ISO，前端要自己算相对时间 |

**建议顺序**：CORS proxy → 类型改造 → auth 异步化 → 会话列表接入。
每步都能独立验收，且第一步就能让"前后端真的连上"变成可见事实。

---

## 1. 耦合点实测清单（grep 出来的，不是推测）

### 1.1 引用 `@/data` 的文件（12 个）
```
components/chat/ContextChips.tsx
components/chat/MessageNavigator.tsx
components/chat/MessagePreviewPopover.tsx
components/chat/ReferencePanel.tsx
components/chat/ResultCard.tsx
components/DocPreview.tsx
components/Sidebar.tsx
lib/sessions.ts
pages/AgentsPage.tsx
pages/ChatPage.tsx
pages/KnowledgeBaseDetailPage.tsx
pages/KnowledgeBaseListPage.tsx
pages/WorkbenchPage.tsx
```
→种子数据是**全局共享**的，改类型会同时波及全部页面。

### 1.2 引用 `lib/sessions` 的文件（3 个）
```
components/SettingsDialog.tsx     → sessionStore（「重置演示数据」）
components/Sidebar.tsx           → sessionStore + useSessions
pages/ChatPage.tsx               → sessionStore + useSessions
```

### 1.3 引用 `lib/auth` 的文件（4 个）
```
App.tsx                  → useAuth().login()（LoginRoute 里 login() 后立刻 navigate）
components/RequireAuth.tsx → useAuth().isAuthed   ← 同步判定，决定重定向
components/Sidebar.tsx:58→ useAuth().logout()（第 434 行调用）
```
> `lib/sessions.ts` 也在 grep 命中里，但**只是文件头注释**提到了 `lib/auth`
> （"和lib/auth、lib/toast 一致的模块 store"），**不是真实依赖**。

---

## 2. 改动项01：雪花 id 类型（`number → string`）

### 现状
- `data.ts:134``SessionItem.id: number`
- `data.ts:85``ChatMsg.id: number`
- `data.ts:11` `KBS[].id: number`（知识库也是number）
- `lib/projects.ts:11` `Project.id: number`

### ⚠️ 三处会在改成 string 后**静默出错**的地方

**（1）`nextId()` 生成器与真实 id 混在同一命名空间**
```ts
// lib/sessions.ts:53-54
let idSeq = Date.now()
const nextId = () => (idSeq += 1)   // → 1757... 这样的数字
```
种子数据的 id 是 `1, 2, 3`；`nextId()` 产出的是 13 位时间戳。
接后端后真实 id 是 `"2108423642637959170"`。
**三种形态共存于同一字段** → `branchFrom()`（`sessions.ts:144`）产出的分支会话 id
会被当成后端 id 拿去调API，直接 404 或查不到。

**决策点**：本地新建的乐观态会话，需要一个能与后端 id 区分的前缀，例如 `tmp_${n}`，
并且在 PATCH/DELETE 前判断「是不是临时 id」来决定走本地还是走后端。

**（2）`activeId ?? 0` 的空态兜底**
```ts
// lib/sessions.ts:49, 88, 168
let activeId = restored ? restored.activeId : (SESSIONS[0]?.id ?? 0)
if (activeId === id) activeId = sessions[0]?.id ?? 0
```
`0` 是 number 语义的"无"。改string 后必须改成 `null`/`undefined`，
否则 `''` 和 `'0'` 会在 `===` 比较里产生误判。

**（2b）id 比较全部是严格 `===`，这点是好消息**
实测 `ChatPage.tsx` 里 4 处id 比较（`m.id === editing.id`、`pendingConfirm.msgId === m.id`
等）全是 `===`，没有 `==` 或 `Number()` 转换。
→ 改成 string **不会**引入"1 == '1'"这类静默失配，改造是类型安全的。

**（3）localStorage 旧快照会被读成脏数据**
```ts
// lib/sessions.ts:29
const STORE_VERSION = 1
```
id 类型变了但 `version` 没变→ 老快照里的 `id: 1`（number）会被当string 用。
**必须把 `STORE_VERSION` 升到 2**，让旧快照直接被忽略。

### 实测的字段对照（详见 api-observed §5）

| 后端 | 前端 | 处理 |
|---|---|---|
| `id: string` | `id: number` | 改 string |
| `kbIds: string[]` | `kbs: string[]` | 改名 + 确认元素类型（⚠️非空值未实测） |
| `agentId: string \| null` | `agent: string` | **语义不同**，需决策 |
| `updatedAt: ISO string` | `updated: '2 小时前'` | 见改动项 04 |
| — | `projectId?: number` | 后端无对应，**需决策** |
| `messageCount` | `messages.length` | 可保留前端算，但需处理"消息分页" |
| `pinned: boolean` | `pinned?: boolean` | 后端必有值 → 改非可选 |

---

## 3. 改动项 02：`lib/auth.ts` 异步化（**最难，别第一个动**）

### 现状（40 行，全文）
```ts
let authed = localStorage.getItem(KEY) === '1'
export const authStore = {
  isAuthed: () => authed,
  login: () => { authed = true; localStorage.setItem(KEY, '1'); emit() },
  logout: () => { ... },
}
export function useAuth() {
  const isAuthed = useSyncExternalStore(subscribe, authStore.isAuthed, authStore.isAuthed)
  return { isAuthed, login: authStore.login, logout: authStore.logout }
}
```
文件头注释写明了设计意图：
> 状态是**同步可得**的，所以不存在"初始化期间不敢判断"的竞态；
> 但为了不闪登录页，路由守卫仍区分 `unknown`（尚未读）与 `guest/authed`

###接后端后必须处理

| 项 | 现状 | 接后端后 |
|---|---|---|
| 凭证 | `localStorage['nx-proto-auth'] = '1'` | `accessToken`（JWT，300 字符）+ `expiresAt` |
| 过期 | 不存在 | **`expiresIn=1800`（实测）→ 30 分钟必须处理** |
| 身份 | 无 | 登录响应直接带 `user`（实测），刷新页面要调 `/api/users/me` |
| 401 | 不存在 | 后端返回 `2001` → 清 token + 跳登录 |
| 守卫 | `RequireAuth` 同步 `if (!isAuthed)` | **需要 `unknown` 态**，否则刷新页面必然误跳登录页 |

### 关键风险（务必给老大点出来）
`RequireAuth` 现在是**同步返回 `<Navigate>` 或 `<Outlet>`**。
改成异步后必须引入第三态：
```
unknown → （读 localStorage 的 token + 校验过期）→ authed | guest
```
如果直接在`unknown` 时渲染 `<Navigate to="/login">`，**刷新页面必然闪一下登录页**——
这恰好是现有实现刻意避免的（`useSyncExternalStore` + 惰性读取消解 `unknown`）。

token 存哪也要决策：localStorage（XSS 可读，但刷新免登录）vs
内存 + refresh token（安全，但刷新要重新拿）。**这需要老大拍板。**

---

## 4. 改动项 03：会话 store 换数据源

### `sessions.ts` 注释低估了工作量
注释写：
> 接后端时**只需要替换 loadPersisted/persist 这两个函数**，store 的对外接口不变

**实测下来不成立**。`sessionStore` 共 **17 个成员**，其中 **9 个**的签名里直接带 `id: number`：
```
setId(id: number)                      patch(id: number, fn)
patchMsg(sessionId: number, msgId: number, fn)
pushMsg(sessionId: number, msg: ChatMsg)
remove(id: number)                archive(id: number)              restore(id: number)
replaceAll(list, selectId?: number)      branchFrom(msgId: number): number | null
```
它们全都要改成 `string`。而这个 store 被 `Sidebar`、`ChatPage`、`SettingsDialog` 三处消费。

剩下5 个是只读访问器（`sessions()` / `activeId()` / `archived()` / `snapshot()` / `createRequests()`），
返回类型里也含`number`，同样要跟着改。
另有两个 id 生成入口 `nextId()` 和 `requestCreate()`。

### 还有一个结构差异（实测）
前端 `SessionItem.messages` 是**内嵌完整数组**；
后端会话列表**不返回消息**（只有 `messageCount`），消息要单独分页取：
```
GET /api/chat/messages/{conversationId}?pageNum&pageSize
```
→ 「侧栏一次拿到全部会话+消息」的模型不成立。
要么接受消息懒加载（进会话时才拉），要么后端加聚合端点。**需决策。**

### `archive` / `archived` 也有分歧
`sessions.ts:172` 的 `archived` 是**本地数组**；后端 `chat_conversation` 表**没有 archived 列**（实测表结构见下），`ConversationResponse` 也没有该字段。
注释自己写了「真实实现是会话上的 archived 字段」，但**后端现在没有这个字段**。
→ 要么后端加，要么前端继续本地维护（刷新丢失）。

实测的 `chat_conversation` 建表语句（`V2__chat.sql`）已确认的列：
```
id / user_id / title / title_auto / mode / kb_ids(JSON) / model_role
/ prompt_template_id / message_count / last_message_at / pinned / deleted
/ created_at / updated_at
```
注意 **`deleted` 是逻辑删除**——列表接口是否过滤软删行、`SessionItem` 是否需要感知，
需要单独确认（`CleanupProperties` 那套软删语义也与此相关）。

---

## 4b. ⚠️ 实测发现：两个比 id 更容易翻车的语义冲突

### （1）消息 `role` 枚举完全不同
| 前端 `ChatMsg.role` | 后端 `ChatMessageResponse.role` |
|---|---|
| `'user'` / `'bot'`（**2 个值，小写**） | `USER` / `ASSISTANT` / `SYSTEM` / `TOOL`（**4 个值，大写**） |

这不是改名，是**值域扩容**。`'bot'` 在后端**不存在**，最接近的是 `ASSISTANT`。
前端所有 `m.role === 'bot'` 判断（实测 `ChatPage.tsx:672` 等）都要改，
且 `TOOL` / `SYSTEM` 在现有 UI 里**没有对应展示位置**。

### （2）`ChatMsg` 字段数远少于后端
后端 `ChatMessageResponse` 实测是 **16 字段的 record**：
```
id / role / agentId / content / model / promptTokens / completionTokens
/ totalTokens / latencyMs / firstTokenMs / status / errorMsg / createdAt
/ toolCalls / citations
```
前端 `ChatMsg` 只有 7个：`id / role / text / cites / calls / context / result`。

有意思的对应关系：
- 后端 `firstTokenMs` ↔ 前端 `RunContext` 里那个**一直想填但填不上**的"真实首 token 时间"
  （HANDOFF §8 P1-2 提到过）→ **接后端后这个字段自动就有了**
- 后端 `citations` ↔ 前端 `cites`
- 后端 `toolCalls` ↔ 前端 `calls`
- 后端 `latencyMs` / `totalTokens` / `status` / `errorMsg` → **前端完全没有**
  - `status` 尤其重要：后端有状态机，前端的"已停止/超时"现在是本地模拟的
- 前端 `context`（知识库 N / 命中 N / 历史 N 轮）↔ 后端**没有对应字段**
  → 上下文胶囊的数据来源需要 SSE 事件，不能靠消息接口拿

**结论**：P1-1 只做「登录 + 会话列表」是对的。一旦碰消息，`role` 映射和状态机就是
必须先解决的问题，不能边做边猜。

---

## 5. 改动项 04：相对时间 `updated`

前端 `updated` 是**硬编码的中文相对时间**：
```ts
updated: '2 小时前'   // data.ts:165
```
渲染处（`ChatPage.tsx:531`）：
```tsx
{active.messages.length} 条消息 · {active.updated}
```
后端给的是 ISO 时间戳（实测 `2026-10-09T13:05:43.687`）。

**需要**：一个 `formatRelativeTime(iso: string): string` 工具函数。
⚠️ 注意后端**精度不一致**（POST 返回 7 位小数、列表返回 3 位，实测见 api-observed §5），
解析时要用 `new Date()` 而不是字符串切片。

---

## 6. 改动项 05：CORS / dev proxy

### 实测：两边都是空白
```bash
grep -rn "Cors\|allowedOrigin" --include="*.java" nexus-app/src/main   # → 无输出
```
`vite.config.ts` 无 `proxy` 配置，只有 `server: { port: 5175 }`。

### 推荐方案 A：vite dev proxy
```ts
server: {
  port: 5175,
  proxy: { '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true } }
}
```
前端 fetch 一律用**相对路径** `/api/...`。生产部署仍需 nginx 反代或后端开 CORS。

### base URL 必须可配置
实测坑2：`SERVER_PORT` 被宿主环境注入成 62504，**后端端口本身不可靠**。
→ `VITE_API_BASE` 环境变量，默认 `/api`（走proxy）。

---

## 7. 改动项 06：环境开关

`data.ts` 的种子数据要保留（§4 的16 阶段门禁全靠它）。
```ts
// vite-env.d.ts
interface ImportMetaEnv { readonly VITE_API_BASE?: string; readonly VITE_USE_MOCK?: string }
```
```ts
export const USE_MOCK = import.meta.env.VITE_USE_MOCK !== 'false'
```
`USE_MOCK=true` 时走现有 store，`false` 时走真实 API。
**QA 脚本（16 阶段）默认不设这个变量 → 自动走 mock，门禁不会因为后端没启动而全红。**

---

## 8. 落地顺序（每步独立可验收）

| 步 | 内容 | 验收 | 风险 |
|---|---|---|---|
| **1** | vite proxy + `VITE_API_BASE` | 浏览器 Network 面板能看到 `/api/**` 请求打到 8080 | 低 |
| **2** | 类型改 string + `STORE_VERSION=2` +临时 id 前缀 | `npm run build` exit 0；**16 阶段门禁仍全绿**（mock 路径不受影响） | 中 |
| **3** | `lib/auth.ts` 异步化 + `unknown` 态 | 刷新页面**不闪登录页**；token 过期自动跳登录 | **高** |
| **4** | 会话列表接后端 | 登录后侧栏列表来自 `/api/chat/conversations`；后端未启动时**明确报错不白屏** | 中 |

**第 3 步做完之前不要动第 4 步** —— auth 没稳就接数据源，401 错误会伪装成"列表加载失败"，很难定位。

---

## 9. 本阶段明确**不**做

-❌ 不改 SSE 流式（P1-2 单独一轮；standalone 下无模型可用，验不了）
- ❌ 不改知识库 / Agent 页面（P1-1 只做「登录 + 会话列表」）
- ❌ 不删 `data.ts` 种子（门禁依赖）
- ❌ 不动 `web/` `web-react/` 归档
- ❌ 不改后端任何代码（本阶段是前端接后端，不是后端改造）

---

## 10. 需要老大拍板的 4 个决策点

> **依据已补充完毕，见 `docs/frontend-integration-decisions.md`**（含每项的源码/实测依据与代价对比）。
> 下面是决策点的速查与我的建议。

| # | 决策 | 我的建议 | 一句话理由 |
|---|---|---|---|
| 1 | token 存哪 | **localStorage** | 后端**无refresh token**（grep 零命中），安全方案无法实现；30 分钟 TTL 可用 `JWT_ACCESS_TOKEN_TTL` 调，前端零改动 |
| 2 | 消息加载模型 | **懒加载** | 后端列表不返回消息；已核实侧栏**不显示消息预览**，所以懒加载无额外成本 |
| 3 | `archived` 怎么办 | ⚠️ **归档本地隐藏 + 删除调后端** | **原建议已推翻**：后端 DELETE 级联物理删消息 + 清记忆，与前端"归档可恢复"语义冲突 |
| 4 | `agent` vs `agentId` | **存 id** | 后端内置 Agent **可改名**（`V12` 注释证实）；且前端现在的下拉框是**假功能**（硬编码，改名不落库） |

另有2 个问题需确认：侧栏是否加消息预览、`projectId` 项目栏目是否保留。