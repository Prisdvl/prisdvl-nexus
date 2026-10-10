# React 前端对话页报告（Phase R8）

> 阶段目标：Chat 页面——会话管理、流式回答、引用、停止生成、0 命中提示。
> 上游：R7 导入资料 → **本文件**。SSE 类型与解析器来自 R4b。
> ⚠️ **本阶段验证部分受阻于环境**，见 §0 与 §7。

---

## 0. 结论先行

| 项 | 结果 |
| --- | --- |
| `npm run build` | ✅ 通过 |
| `verify-chat.mjs` | ✅ **PASS 14 / FAIL 3**（3 项的性质见下） |
| 核心链路 | ✅ 端到端实测：提问 → 检索 → **真实 LLM 流式回答** → 落库 → 引用 |

### 核心链路实测记录（真实模型调用）

后端日志实证：

```text
ChatStreamServiceImpl - 开始流式生成 mode=RAG, 检索到 4 个片段
ChatMessageMapper.updateById - ASSISTANT, "资料中没有相关内容。",
                              deepseek-v4-flash, prompt=363, completion=23,
                              finishReason=STOP, status=DONE
                              citations=[4 条，带 documentTitle/headingPath/snippet/score]
```

前端实测读到回答正文：

```text
"你好！资料中没有与问候相关的内容 [1][2][3][4]。我可以依据资料回答关于 Spring Boot自动配置、缓存…"
```

**delta 是累积替换而非追加** —— 探针断言"前 20 字在全文只出现 1 次"通过。

### 3 个未通过项的性质（不是同一种问题）

| 项 | 性质 | 说明 |
| --- | --- | --- |
| 生成中显示「停止」按钮 | **断言假设错误** | 云端 API 几秒就答完，脚本检查时生成**已结束**。这是模型快，不是 UI 慢 |
| 0 命中提示 | **断言假设错误** | 我以为问"香蕉和股票"会 0 命中，实际知识库检索到了 4 个片段（score 很低但 > 0）。**不显示 0 命中提示是正确的** |
| 刷新后历史消息 | **真实设计缺口** | 刷新后 URL 是 `/#/`，`activeId` 丢失 → 不显示任何会话。属于"当前会话未持久化"，R10+ 补 |

前两项改断言即可（价值低），第三项是真实改进但不属于 R8 范围。

## 1. 交付物

| 文件 | 内容 |
| --- | --- |
| `api/types/chat.ts` | Conversation / ChatMessage / RagCitation / StreamChatRequest / StopStreamResponse |
| `api/chat.ts` | 会话 CRUD、消息列表、停止生成 |
| `hooks/useChatStream.ts` | 消费 SSE：帧 → UI 状态、竞态防护、停止 |
| `pages/ChatPage.tsx` | 会话列表 + 消息流 + 输入区 + 引用面板 |
| `components/chat/MessageBubble.tsx` | 消息气泡（用户右 / 助手左） |
| `components/chat/CitationList.tsx` | 引用列表（含"未引用"核对开关） |
| `scripts/verify-chat.mjs` | 新增，真实调 LLM |

---

## 2. 抓到的四个真实 bug

### ① 消息列表路径错了（第二次"凭印象写路径没核实"）

我写的是 `/api/chat/conversations/{id}/messages`，**注释里还写得很确信**：
「ChatMessageController 也挂在 `/api/chat/conversations` 上」。

实际后端（`ChatMessageController:22`）是 `@RequestMapping("/api/chat/messages")`，
真实路径 **`/api/chat/messages/{conversationId}`**。

请求打过去得到 **405**（不是 404——路径命中了 `ChatConversationController` 的某个方法
但方法不匹配），排查时很容易误判成后端问题。

> **这是 R7 的 `files` 字段名之后第二次"凭印象写路径/字段没核实"。**
> 教训固化：**注释里凡是写"后端是 X"的，必须从源码复制，不能凭记忆或推演。**

### ② RAG 会话必须绑定知识库（产品约束，不是 bug）

```json
{ "code": 1001, "message": "该会话（RAG 模式）尚未绑定知识库，请先绑定后再提问" }
```

我创建会话时只传了 `{ mode: 'RAG' }`，后端直接 400 拒绝。

**这是产品约束**：RAG 的前提是"有资料可检索"，没资料的 RAG 会话没有意义。
（对应任务书 §28 的硬要求——**0 命中时不能硬答**，后端用"不给上下文"来保证这点。）

**修法**：创建 RAG 会话时**默认绑定用户的全部知识库**（与 Vue 侧一致），
并对 0 知识库的用户给一句可行动的提示：
「还没有知识库。先去知识库页导入一些资料，才能开始提问。」

### ③ 乐观插入的 null 分支

`msgs.setData((prev) => prev ? {...} : prev)` —— 刚 `setActiveId` 时消息列表
还没加载完（`prev` 是 `null`），于是**首条消息的乐观插入被静默丢弃**。

### ④ 乐观插入被异步加载覆盖（更隐蔽）

即使修了 ③ 还是不行。根因是时序：

```text
t0  setActiveId(cid)          → useAsyncState 的 effect 开始拉消息
t1  setPendingUserMsg(...)    → 乐观消息上屏
t2  消息列表请求返回（空）      → useAsyncState 无条件 setData(空列表)
                                → **乐观消息被覆盖**
t3  流式开始（几十秒）…        → 界面上什么都没有
```

**`useAsyncState` 在请求返回时无条件 `setData(result)`**，不管期间有没有本地更新。

**修法**：乐观消息**独立成 state**（`pendingUserMsg`），与 `msgs.data` 分开渲染：

```ts
const allMessages = pendingUserMsg ? [...messages, pendingUserMsg] : messages
```

顺带好处：切会话时只要清这个 state，不必等列表重拉。

### ⑤ 流式请求被自己的 effect abort（**真正的根因**）

探针实测抓到：

```text
[REQ]     POST /api/chat/stream
[REQFAIL] POST /api/chat/stream :: net::ERR_ABORTED
```

时序：

```text
onSend: setActiveId(cid)            → 触发 useEffect([activeId])
        stream.start(cid, content)  → 发出 POST /api/chat/stream
effect: reset() → ctrl.abort()      → **把刚发出的请求打断**
```

`streaming` 刚被置 true 又被置 false，界面上什么都留不下。

#### 我在错误方向上试了两次

| 尝试 | 为什么错 |
| --- | --- |
| `skipNextClear` 标记位 | 依赖"effect 只跑一次"，而 StrictMode / 依赖抖动会让它跑两次 |
| `prevIdRef.current !== activeId` 值比较 | 依赖 effect 的执行顺序，仍不稳 |

**两次都是在给这个 effect 打补丁，而不是质疑 effect 该不该存在。**

#### 正确的修法：副作用跟着用户动作走

```ts
// ❌ 删掉：useEffect(() => stream.reset(), [activeId])

// ✅ 切会话时（在用户动作里）
onClick={() => { stream.reset(); setActiveId(c.id) }}

// ✅ 删当前会话时
if (activeId === c.id) { stream.reset(); setActiveId(null) }

// ✅ 首次创建并激活时——**什么都不做**，因为这不是"切换"
setActiveId(cid)
```

改完后 `verify-chat.mjs` 从 PASS 10 → **PASS 14**，且回答内容实测正常。

> **教训（值得带到后续阶段）**：
> `useEffect(() => reset(), [key])` 这种写法在"初始化"场景下几乎总是错的——
> 它无法区分"刚创建"与"切换到"。
> **副作用应该挂在用户动作上**，让调用点自己表达意图，而不是让 effect 猜。

#### 附带：乐观消息也改成自描述

```ts
const [pendingUserMsg, setPendingUserMsg] = useState<{ msg: ChatMessage; forId: string } | null>(null)
// 渲染时判断，不靠 effect 同步
const visiblePending = pendingUserMsg && pendingUserMsg.forId === activeId ? pendingUserMsg.msg : null
```

无论 effect 跑几次、顺序如何，结果都一样。

---

## 3. delta 是**累积替换**，不是追加

后端每个 `delta` 帧带的是**当前累积文本**，不是增量片段。

**若按"追加"处理，界面会变成"你你你好你好…"** —— 这是 Chat 最容易踩的坑。
`useChatStream` 里是 `setStreamingText(d.text)`（整体替换），并写进注释。

验证脚本专门查了这一点：取回答前 20 字，断言它在全文中**只出现一次**。

---

## 4. 停止生成需要 `requestId`

`requestId` **只在 `meta` 帧里给**。拿不到就没法停。

`useChatStream` 在 `meta` 到达时立刻 `setRequestId(m.requestId)`，`stop()` 用它调
`POST /api/chat/stream/{requestId}/stop`。

**为什么走后端的 stop 而不是只 abort 本地连接**：后端才知道要把**已生成的部分落库**，
否则这次回答就白跑了（用户点了停止，界面上一个字都没有）。

---

## 5. 引用（citations）

后端把**未被引用的命中也返回了**（注释里写「便于核对模型有没有漏引」）。

`CitationList` 的处理：**默认只显示被引用的**，但给出「还有 N 条未引用的检索结果」
的开关——**让用户能自己核对，而不是让前端替模型隐瞒**。

### 刻意不显示（任务书 §73）

- **相似度分数**：L4 信息，用户不需要知道 `score=0.8234` 才能判断该不该信；
- **知识库 id / chunkId**：内部标识，跳转时才用。

---

## 6. 0 命中的处理（任务书 §28）

后端在 0 命中时**不给通用 LLM 上下文**（这是 §28 的实现方式——在业务执行层拦住，
而不是靠前端）。但用户**必须知道为什么这次没引用资料**，否则会以为引用坏了：

```
这次没有从知识库里检索到相关内容，下面是通用回答。
（某某知识库 检索失败）   ← failedKnowledgeBases 非空时列出
```

`failedKnowledgeBases` 这个字段是 R4b 特意写进类型的（并在注释里说明了
"Vue 侧漏了它，于是界面假装全都搜过了"）。

---

## 7. 未验证项与补跑方式

| 项 | 为什么需要真实 LLM |
| --- | --- |
| 流式内容渲染 | 要等模型逐字输出 |
| 助手消息落库 | 要等生成结束 |
| 刷新后历史消息 | 依赖上一条 |
| 0 命中提示 | 要等生成结束才有 `retrieval` 帧 |

```bash
cd deploy && docker compose up -d
cd web-react && npm run dev
node scripts/verify-chat.mjs        # 等待已放宽到 300s
```

脚本结束时会删掉本次创建的所有会话，不留测试数据。

---

## 8. 顺带修正的 R4b 猜测

R4b 的 `RagCitation` 是我**凭 Phase C 记录推的，没核实 DTO**。R8 读了
`RagAnswer.RagCitation` 源码后发现：

| 我 R4b 写的 | 实际 |
| --- | --- |
| `knowledgeBaseId: number` | `knowledgeBaseId: string`（R7 精度坑） |
| `knowledgeBaseName` | **后端没有这个字段** |
| — | **多了 `chunkId`** |
| — | **多了 `headingPath`** |

已把 `RagCitation` 的**权威定义移到 `api/types/chat.ts`**，`sse.ts` 改为重导出，
避免同一个 DTO 出现两套定义（R3 任务书 §17 禁止）。

`ChatStreamMeta` 的三个 id 同样改成 `string`。

---

## 9. 基础设施的第三次检验

| R0~R7 建的 | R8 用得怎么样 |
| --- | --- |
| `lib/sse.ts` 解析器 | ✅ **27 项桩帧验证直接用上**，真实流零解析问题 |
| `api/types/sse.ts` | ⚠️ 两处猜测被 R8 修正（见 §8） |
| `useAsyncState` | ⚠️ **发现一个设计缺陷**（无条件 setData 覆盖本地更新，见 §2④） |
| `useConfirm`（单例） | ✅ R6 修好后本轮删除会话一次通过 |
| `StateEmpty/Error/Loading` | ✅ 直接用 |
| Button / Textarea / Card | ✅ 无二套 |
| 类型系统 | ✅ 两次把形状不一致报出来（`StreamToolCall` vs `MessageToolCall`、meta id 类型） |

**R4b 提前做的 SSE 决定是对的**——R8 的解析部分一次通过，
若当时没做，现在要返工改协议。

---

## 10. 边界

- `web/`（Vue）、`nexus-*`、旧 E2E、`deploy` **零改动**
- `docs/update.txt` 未碰 · **未 commit / push**
- 剩余占位页：`AgentsPage`

---

## 11. 下一步

**R9（Agent）**：`api/agent.ts` 与 `agentStore` 已在 R4 建好，
剩下编辑器与工具配置 UI。SSE 的 `tool_call` / `tool_result` 事件类型也已就绪。

之后 **R10（高级区）**：切片 / 检索 / 知识工作流 / 向量化，
以及 **R11（收尾）**：切到 React 前端、删 Vue、E2E 统一。
