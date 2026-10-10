# P1-1 四个决策点的依据（决策前必读）

> **状态**：设计稿，**未改任何业务代码**
> **目的**：把 4 个待拍板问题的真实约束挖出来，让老大基于事实而不是直觉决定。
> **所有结论标注了来源**：`实测` = 本轮 curl/H2 跑出来的；`源码` = 读文件确认的；`推断` = 逻辑推导，未直接验到。

---

## 决策 1：token 存哪

### 结论先行
**后端没有 refresh token 机制**（`grep refresh --include=*.java nexus-app/src/main nexus-ai/src/main` → **零命中**，`源码`）。
这意味着「安全方案」在当前后端下**无法实现**——不是"暂不建议"，是"没有那个东西可用"。

### 后端现状（源码）

`application.yml:130-137`
```yaml
nexus:
  jwt:
    secret: ${JWT_SECRET:}
    issuer: prisdvl-nexus
    access-token-ttl: ${JWT_ACCESS_TOKEN_TTL:30m}
    token-version-cache-ttl: ${JWT_TOKEN_VERSION_CACHE_TTL:5m}
```

实测登录响应：`{"tokenType":"Bearer","expiresIn":1800}`（`实测`）→ **30 分钟**。

**唯一签发入口**（`AuthServiceImpl.login`，源码）：
```java
return new AuthTokenResponse(issuedToken.token(), "Bearer", issuedToken.expiresIn(), toUserInfo(user, roles));
```
只有access token，**没有第二个字段**可以放 refresh token。

### 三个选项的真实后果

| 选项 | 30 分钟到时的体验 | 实现成本 | 风险 |
|---|---|---|---|
| **A. localStorage** | 跳登录页，重新输密码 | 最低 | token 可被 XSS 读取 |
| B. 仅内存变量 | 同上，**且刷新页面必然掉登录** | 中 | 体验最差 |
| C. localStorage + refresh token | 无感续期 | **高，且后端要改** | 需要新增签发/校验/存储三处 |

### 选 A 还是 C，取决于一个项目定位问题

C 要动后端（新增 refresh token 列或表、签发端点、校验过滤器、失效策略）。
按 HANDOFF §9 的约束「**不要过度设计**」「一次最多实现一个完整的小功能」——
为一个**个人项目**做C 属于过度。

但A 的代价要说清楚：
- 30 分钟无操作后要重新登录。个人使用场景下可接受。
- 真正的风险是 XSS。但这个项目**没有第三方脚本、没有用户生成内容渲染**
  （`data.ts` 是种子数据），XSS 面很小。

### 我的建议
**选 A（localStorage）**，理由：
1. C 要改后端，违反"最小改动"且超出 P1-1 范围
2. 项目无 XSS 攻击面
3. 30 分钟 TTL 本身就是合理的安全设计，不是妥协

**但有一个必须做的配套动作**：`JWT_ACCESS_TOKEN_TTL` 是环境变量可调的。
如果老大觉得 30 分钟太短（比如做长文写作），可以调成 `2h`，**前端不用改任何代码**。

---

## 决策 2：消息加载模型

### 结论先行
后端**没有**聚合端点，且会话列表**结构上就不返回消息**（`实测`：列表响应里只有 `messageCount`，没有 messages 字段）。
所以只有两个选项，**没有第三条路**。

### 后端现状（实测 + 源码）

会话列表响应（`实测`）：
```json
{ "id":"2108423642637959170", "title":"...", "mode":"PLAIN",
  "modelRole":"chat.default", "messageCount":0, "lastMessageAt":null,
  "pinned":false, "kbIds":[], "agentId":null,
  "createdAt":"...", "updatedAt":"..." }
```
**没有 messages 字段**。消息在独立端点：
```
GET /api/chat/messages/{conversationId}?pageNum&pageSize
```

前端模型（源码 `data.ts:133-143`）：
```ts
export interface SessionItem {
  id: number; title: string; agent: string; kbs: string[]
  updated: string; pinned?: boolean; projectId?: number
  messages: ChatMsg[]        // ← 消息内嵌在会话里
}
```

### 两个选项

| 选项 | 做法 | 代价 |
|---|---|---|
| **A. 懒加载**（建议） | 侧栏只拉会话列表；进会话时再 GET 消息 | 进会话多一次请求（~几十ms，本地环境）；会话预览需要单独处理 |
| B. 后端加聚合端点 | 新增 `GET /api/chat/conversations?withMessages=true` | 改后端+ N+1 查询问题 + 响应体膨胀 |

### A 的隐藏成本（这是我要提醒的重点）

懒加载会让「**侧栏显示最后一条消息预览**」这个常见需求变得麻烦——
现在 `SessionItem.messages` 直接就有最后一条，A 之后需要额外请求或后端加 `lastMessagePreview` 字段。

**先问老大**：侧栏现在**有**显示消息预览吗？
我查了 `Sidebar.tsx`，**没有**——侧栏只显示标题和时间。
所以 A 方案**没有额外成本**，这个顾虑不成立。

### 我的建议
**选 A（懒加载）**。理由：
1. 不动后端，符合最小改动
2. 侧栏不需要消息预览（已核实），所以A 不带来额外成本
3. 响应体更小，前端内存里的会话列表更轻

**注意**：选 A 后，`SessionItem.messages` 从「必填数组」变成「进会话时才加载」，
`ChatPage` 里所有直接访问 `active.messages` 的地方（实测约 15+ 处）
都要处理"还没加载完"的状态。**这是 P1-1 里工作量被低估的一项。**

---

## 决策 3：archived 归属

### 结论先行
⚠️ **我之前那条建议是错的，这里推翻。**

我原来说「archived 前端本地维护，避免动后端」。读完源码后发现：
**前端 archive/restore 的语义与后端 DELETE 完全不兼容**，不是"归属"问题，是**能不能对应**的问题。

### 前端的归档语义（源码）

`Sidebar.tsx` 三个位置：
```
:90    list = list.filter((s) => !archived.includes(s.id))       // 归档 = 从列表隐藏
:155   archiveSession(s.id); toast.info('会话已归档', '可在「已归档」中找回')
:371   sessions.filter((s) => archived.includes(s.id))           // 「已归档」区可恢复
:374   restoreSession(s.id); toast.success('会话已恢复', s.title)
```
**数据仍在 store 里，只是被过滤掉。** 可完整恢复。

### 后端的删除语义（源码 `ConversationServiceImpl.delete:198-213`）

```java
public void delete(Long userId, Long conversationId) {
    requireOwned(userId, conversationId);
    // 会话走逻辑删除（@TableLogic 自动改写为 UPDATE deleted=1）
    conversationMapper.deleteById(conversationId);
    // 消息物理删除：否则消息表会随会话删除不断堆积无主数据
    int removedMessages = messageMapper.delete(...);
    // 会话记忆必须一起清（key = "conv:{id}"）
    chatMemory.clear(ChatMemoryKeys.of(conversationId));
}
```

DELETE 做三件事，且**注释明确说明了为什么不可省**：
1. 会话 `deleted=1`（逻辑删）
2. **消息物理删除**（不可恢复）
3. **会话记忆清空**——注释写道：
   > 不清它不只是"多留几行无主数据"：记忆是"这次发给模型多少历史"的唯一来源，
   > 一旦该 id 重新被读到，新会话就会凭空记得旧会话说过的话。

### 语义冲突（硬冲突，无法绕过）

| | 前端 | 后端 |
|---|---|---|
| 归档动作 | 隐藏，可恢复 | — |
| 删除动作 | 从 store 移除（可 `replaceAll` 撤销） | 级联物理删除消息 + 清记忆 |
| 「已归档」区 | 有，找回按钮 | 无此概念 |
| 重新访问已删 id | 快照还在，渲染出来 | 消息没了，记忆也污染了 |

**最严重的问题**：如果前端把「归档」实现成调 `DELETE`，
用户点「恢复」后，后端已经**物理删掉了消息**——
前端只能显示一个空会话。而更糟的是记忆已被清，但 id 可能被复用给新会话。

### 修正后的三个选项

| 选项 | 做法 | 代价 |
|---|---|---|
| **A. 前端只做「隐藏」**（建议，但语义要改名） | 前端本地标记 archived，**不调 DELETE**；真正的 DELETE 需要用户二次确认 | 数据不会真删，与后端 DELETE 语义脱节 |
| B. 后端加 `archived` 字段 | 新增迁移 + 实体字段 + 列表按 archived 过滤 | 要动后端，但语义正确 |
| C. 前端区分两个动作：「归档」（本地隐藏）+「删除」（调 DELETE，不提供恢复） | 语义最诚实，改动适中 | UI 要改：删除要加不可撤销提示 |

### 我的建议
**选 C**，修正我之前的判断。理由：
1. A 会让用户以为数据还在，实际上永远不会被清理——**假删除**比不提供更糟
2. B 动后端，但P1-1 的定位是不动后端
3. C 保持诚实：**「归档」是本地视图（可撤销），「删除」是真删（不可撤销）**

C 的 UI 成本很低：`SwipeRow` 已经有 `archive` 动作（`Sidebar.tsx:132`），
加一个 delete 动作 + 二次确认即可。

---

## 决策 4：agent vs agentId

### 结论先行
我原来说「存 id」，理由是"名字会变"。读完源码发现**更根本的原因**：
**前端的 `agent` 是自由文本，不是从后端选出来的。**

### 前端现状（源码）

`ChatPage.tsx:537-539`：
```tsx
value={active.agent}
onChange={(name) => patch(active.id, (s) => ({ ...s, agent: name }))}
//↑ 用户直接改名字，改完就写进 store
```

种子数据的取值（`data.ts`）：`'面试助手'` / `'默认助手'` ——**只有两个值，纯演示字符串**。
后端库里**没有任何 Agent 种子数据**（`grep "INSERT INTO agent"` 全部迁移 → **零命中**，`实测`）。

**所以前端现在这个下拉框是假的**——选项来自硬编码，改名不落库。

**补充事实**：后端的"内置默认 Agent"是**懒创建**的（`AgentService:316-323`，源码）：
```java
AgentPO builtin = agentMapper.selectOne(... code = BUILTIN_CODE ...);
if (builtin == null) { builtin = createBuiltin(userId); }   // 首次使用时现场补齐
```
常量在 `AgentPrompts`：`BUILTIN_CODE = "builtin.default"`、`BUILTIN_NAME = "默认助手"`。

✅ **但前端不用担心空列表**——`AgentService.list` 在读路径上**显式调了** `resolveDefault`
（`AgentService.java:98`，源码），注释写明了原因：
> 如果只有执行路径会触发补齐，新用户的列表就会是空的——
> 于是"AGENT 模式永远有兜底"这句在列表里看不出来，用户只能靠猜。
> 补齐是幂等的（同一用户至多一行），所以放在读路径上不会写出脏数据。

而且 `list` 用 `orderByDesc(AgentPO::getBuiltin)` 把内置 Agent 置顶。
→ **`GET /api/agents` 对新用户保证至少有 1 条（内置默认），且它在第一位。**
前端只需处理 `agentId: null` → 显示"默认助手"，不需要额外的空态兜底。

### 后端现状（源码）

`AgentPO`：
```java
Long id / Long workspaceId / Long ownerId
String code / String name / String description
String systemPrompt / String modelRole / String answerPolicy
Double temperature / String status / Boolean builtin
```

`V12__agent.sql:31` 对 `builtin` 的注释：
> `1 = 系统内置（默认助手）：不可删除、不可停用，**可改名改提示词**`

**这条注释是决策的 decisive证据**：内置 Agent 连名字都允许改。
存名字 = 把一个可变值当主键用。

### 好消息：不需要后端配合，且空列表已被后端堵住

`AgentResourceController` **已经有列表端点**（`源码`）：
```
GET /api/agents（分页）  POST /api/agents  GET /api/agents/{id}
PUT /api/agents/{id}     POST /api/agents/{id}/duplicate   DELETE /api/agents/{id}
POST /api/agents/{id}/test-chat
```

**`GET /api/agents` 存在** → 存id 后，前端自己拉列表、自己映射名字。
不需要后端在会话响应里冗余 agentName。

而且这个列表**保证非空**且内置 Agent 置顶（见上文 `AgentService.list:98,104`）。

### 方案

| 选项 | 前端 `SessionItem` | 代价 |
|---|---|---|
| **A. 存 `agentId: string`**（建议） | `agentId: string \| null`，`null` = 用内置默认 | 需要一个 Agent 列表的 store + 名字映射；`null` 要显示"默认助手" |
| B. 存名字 + `GET /agents` 兜底 | 保留 `agent: string` | 改名后历史会话显示新旧名字混杂 |

### 我的建议
**选 A（存 id）**。理由：
1. `builtin` Agent **可改名**（源码注释证实）→ 名字不是稳定标识
2. 后端 `agentId` 就是 `null` 表示默认（`ConversationResponse` 实测 `agentId: null`），
   语义天然对齐——**前端也该用 `null` 表达"没选"**
3. `GET /api/agents` 已存在，不需要改后端

**顺带一个额外收益**：改用 id 后，「换 Agent」这个动作变成真实可用的功能——
现在前端改名只是改了个显示字符串，对后端毫无影响（幻觉功能）。
接后端后 `PUT /api/chat/conversations/{id}` 传 `agentId` 就是真切换。

---

## 汇总：我的四项建议与修正记录

| # | 我最初说 | 读完源码后 | 变化 |
|---|---|---|---|
| 1 | localStorage | **维持** localStorage | 无（已确认后端无 refresh token） |
| 2 | 懒加载 | **维持** 懒加载 | 无（已核实侧栏不需消息预览） |
| 3 | 前端本地维护 archived | ⚠️ **改为「归档本地隐藏 + 删除调后端」** | **推翻**：后端 DELETE 是级联物理删，不可恢复 |
| 4 | 存 id | **维持** 存 id，但理由更强 | 理由从"名字会变"升级为"前端 agent 根本是假下拉框" |

**只有第 3 项被推翻了**——这是本轮勘察最值得记住的一条：
**前端"归档可恢复"与后端"级联物理删"是两种产品语义，不能靠"放前端"糊过去。**

---

## 还需要老大确认的两个问题（不在原4 项内）

1. **侧栏要不要加消息预览？**（现在没有，懒加载方案下要加会多一次请求）
2. **`projectId` 项目栏目保留吗？** 后端 `V9__workspace` 是另一套模型，
   `ConversationResponse` 里**确实没有** project 相关字段。
   保留就得前端本地维护（刷新丢），删掉就是砍一个现有 UI 栏目。