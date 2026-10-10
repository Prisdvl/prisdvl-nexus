# P1-1 实施记录（前端接后端·第一轮）

> **实施时间**：2026-10-09 14:00 – 15:55
> **范围**：严格按 `docs/frontend-integration-decisions.md` 的 4 项决策实现，**只改前端**，不新增/修改任何后端接口。
> **结果**：类型 0 / 构建 0 / 真实后端端到端 **20 通过 0 失败** / mock 门禁 15 阶段通过（1 阶段因已知取舍红）

---

## 0. 结论

四项决策全部落地，且**在真实后端上跑通了完整链路**（注册 → 登录 → 拉列表 → 懒加载消息 → 换 Agent → 归档/删除）。
过程中发现并修复 **4 个真实缺陷**，其中 3 个属于"看起来成功、实际没生效"的**假成功**类型
—— 这类 bug 不做端到端根本发现不了。

### 步骤状态

| 步骤 | 状态 | 验证方式 |
|---|---|---|
| 1 token + 拦截器 | ✅ | 端到端：登录拿令牌、token 落 localStorage、过期时间记录正确 |
| 2 消息懒加载 | ✅ | 端到端：`200 /api/chat/messages/2108462995376508929` |
| 3 归档删除拆分 | ✅ | 端到端：DELETE 200、二次确认对话框存在 |
| 4 agentId 迁移 | ⚠️ 部分 | 端到端：非空值切换可持久化；**清空被后端拒绝落库**（见 §6） |

---

## 1. 改动文件清单

### 新建（4）
| 文件 | 作用 |
|---|---|
| `src/lib/api.ts` | axios 实例 + 拦截器 + 令牌存取 + 端点封装（只调已有端点） |
| `src/lib/conversations.ts` | 后端 → UI 类型映射 + 相对时间换算 + 消息拉取 |
| `src/lib/agents.ts` | Agent 列表缓存（id → name），登出清空 |
| `src/lib/sync.ts` | 登录后拉会话列表（失败不阻断导航） |

### 修改（12）
`vite.config.ts`（dev proxy）、`src/lib/auth.ts`、`src/lib/sessions.ts`、`src/lib/feedback.ts`、
`src/data.ts`、`src/App.tsx`、`src/pages/AuthPage.tsx`、`src/pages/ChatPage.tsx`、
`src/components/Sidebar.tsx`、`src/components/chat/MessageRail.tsx`、
`src/components/chat/MessageNavigator.tsx`、`src/components/RequireAuth.tsx`（**实际未改**，见 §3）

### 新增脚本（1）
`scripts/e2e-p1-1.mjs` —— 真实后端端到端验证（一次性工具，非门禁）

### 依赖
`axios`（老大指定）。

---

## 2. 真实后端端到端验证结果（20/20 通过）

```
ok   注册进入邮箱验证步（真实 POST /auth/register）
ok   注册完成提示出现
ok   注册后停留在注册页（未自动登录跳转）
ok   真实登录成功（POST /auth/login 拿到令牌并进入应用）
ok   令牌已写入 localStorage — len=299
ok   过期时间已记录且在未来
ok   登录后自动拉会话列表 — 200 /api/chat/conversations?pageNum=1&pageSize=100
ok   侧栏会话列表已渲染 — 1 行
ok   进入会话时拉消息（懒加载） — 200 /api/chat/messages/2108462995376508929
ok   Agent 分段选项已渲染 — 2 项
ok   Agent 选项含默认助手
ok   存在 AGENT 模式的会话（换 Agent 的前提）
ok   切换 Agent 后选中项变化 — 端到端探针Agent → 默认助手
ok   切换 Agent 触发 PUT（真实写后端）
ok   删除流程需二次确认（对话框）
ok   登出前令牌在位
===== 20 通过 / 0 失败 =====
```

**proxy 有效性也已实测**：经 5175 打 `/api/auth/login` 返回 200，
证明后端零 CORS 的情况下 dev 代理方案可行。

---

## 3. 一个值得记的设计收益：不闪登录页

勘察阶段把 `lib/auth.ts` 异步化标记为**最高风险项**，理由是"如果 `unknown` 时直接渲染 `<Navigate to="/login">`，刷新页面必然闪一下登录页"。

**实际实现完全绕开了这个风险** —— 因为令牌存localStorage **同步可读**，`isAuthed` 仍是同步返回值，
`RequireAuth.tsx` 一行没改，「不闪登录页」被完整保留。

→ 结论：**危险的不是"异步"，而是"为了判断登录态而必须异步"**。
routing 门禁通过（exit 0）证实了这一点。

---

## 4. 发现并修复的 4 个真实缺陷

### 缺陷 1（严重）：新用户首次进入 → 整页白屏
**症状**：`TypeError: Cannot read properties of undefined (reading 'kbs')`，崩到 ErrorBoundary。

**根因**：`active = sessions.find(...) ?? sessions[0]`，新用户会话列表为空时是 `undefined`，
而 `promptSources` 等**在 return 之前求值的普通 const** 就读了 `active.kbs` ——
靠 JSX 前的 `if (!active) return` 守卫**挡不住**。

**修法**：模块级 `EMPTY_SESSION` 常量兜底 + `isEmptyList` 控制空态 UI。
不是加更多守卫，而是让字段访问永不为 undefined。

**教训**：原型期种子数据永远有 4 条会话，这个 bug 绝不会在 mock 下出现。

### 缺陷 2：mock 模式下换 Agent / 删除会话会误调后端
**根因**：只用 `isTempId()` 判断"这条数据在不在后端"，
但 mock 的种子会话 id 是 `seed_*`，**不是 `tmp_`**。

**修法**：`if (USE_MOCK || isTempId(id))` 走本地。
→ **判断"数据在不在后端"不能只看 id 前缀，mock 是第三种情况。**

### 缺陷 3：换 Agent 的 `mode: 'AGENT'` 是静默无效
**症状**：PUT 返回 **200**，UI 显示已切换，但库里 `mode` 仍是 `PLAIN`、`agentId` 仍是 `null`。

**根因**（读后端源码确认）：`ConversationServiceImpl.update` **不接受 mode 字段**
（只认 title / pinned / kbIds / agentId / resetAgent），
且 `agentId` 只在会话**已经是** AGENT 模式时才允许设，否则报 1001
「只有 AGENT 会话可以选用 Agent」。

**修法**：去掉 `mode`，并对非 AGENT 会话给出明确提示，而不是发一个必被忽略的请求。

### 缺陷 4：清空 Agent（切回默认助手）也是假成功
**症状**：前端发 `{agentId: null}`，PUT 返回 200，UI 已 patch，**库里没变**。

**根因**：后端 `update` 的 `agentId == null` 语义是**"不改这个字段"**，不是"清空"。
`UpdateConversationRequest` 注释写明了：*"null 既是不改又是解绑分不清，所以清空必须有独立信号"*。

**修法**：前端改发 `{resetAgent: true}`。

⚠️ **但 `resetAgent` 同样未落库**（响应说已清空，后续查询仍是旧值）—— 详见 §6。

---

## 5. QA 门禁（mock 模式，dev server）

| 阶段 | 结果 |
|---|---|
| routing / interactions / unify | ✅ 0 |
| contrast / palette / a11y / theme / settings / cards / doc / tour / features / responsive / shots | ✅ 0 |
| **chat** | ⚠️ **1** —— 49 条断言通过，3 条失败 |

**chat 的 3 条失败全部是项目栏目**（按拍板决定已隐藏）：
```
FAIL 分区顺序是「项目 → 置顶 → 最近」 — 实际「置顶 / 最近」
FAIL 侧栏分区含「项目」且在置顶/最近之前
FAIL 项目栏目有创建入口
```
**不是 bug，是「projectId 先隐藏」这一决策的直接后果**。

### 三个环境限制（不掩饰）
1. `verify:all` 的 build 会清`dist/assets`（323 文件）触发**工作区批量删除保护** → 用 `--outDir` 绕过
2. `npm install axios` 改 lockfile 触发 vite 清 `.vite/deps`（546 文件）同样触发保护
3. **内存紧张导致 vite dev `memory allocation failed` 崩溃** → 需清理残留 node/msedge 后重启

---

## 6. ⚠️ 发现的后端问题（本轮未改，需老大决定）

### 6.1 `resetAgent` 清空 Agent 不落库
```bash
# PUT 返回 200且响应里 agentId = null
curl -X PUT /api/chat/conversations/{id} -d '{"resetAgent":true}'
# 但后续 GET 查出来agentId 仍是旧值 ← 写入没生效
```
怀疑是 MyBatis-Plus `updateById` 的 null 字段策略（`FieldStrategy.NOT_NULL` 会跳过 null）。
**对比**：设非空值（`agentId: "2108460926280208386"`）**可以**持久化。

**影响**：前端"切回默认助手"无法真正生效，现在是假成功（UI 变了、库没变）。
这直接冲击决策 4 的实现基础 —— 若后端不修，前端不该显示"已切换"，而应该报出来。

### 6.2 对已存在的 PLAIN 会话无法换 Agent
`mode` 只能在 `POST /api/chat/conversations` 建会话时指定，建好后不可改。
→ 后端**现有**的 PLAIN 会话都无法启用 Agent，只能新建。

**决策 4 的可行范围因此受限**：只能在"新建向导里就选 Agent"的新会话上换。

---

## 7. 待老大决策

| # | 事项 | 我的建议 |
|---|---|---|
| 1 | §6.1 的 `resetAgent` 不落库 | **要修后端**。否则前端只能"假装成功"或加 UI 兜底，两者都不好 |
| 2 | chat 门禁 3 条断言 | 改 `qa-chat.mjs` 删掉这 3 条（项目栏目是已知临时隐藏，不是被破坏） |
| 3 | §6.2 PLAIN 会话不能换 Agent | 前端已在非 AGENT 会话上给明确提示；若要支持现有会话，需后端允许改 mode |
| 4 | `.verify-dist` 目录 | 需确认后才能删（324 个文件触发批量删除保护） |

---

## 8. 临时文件状态

| 文件 | 状态 |
|---|---|
| `frontend/vite.verify.config.ts` | ✅ 已删 |
| `frontend/.vite-cache-verify/` | ✅ 已清 |
| `frontend/.verify-dist/` | ⚠️ **仍在**（324 文件，触发安全策略，需确认） |
| `frontend/scripts/e2e-p1-1.mjs` | 保留（端到端验证工具，有复用价值） |