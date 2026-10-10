# React 前端 SSE 类型与解析器报告（Phase R4b）

> 阶段目标：把 SSE 协议**统一成单一来源**，并让解析逻辑**可独立验证**。
> 未实现：`useChatStream` hook（属 R8）、任何业务页面。
> 上游：R3（`api/types/` 目录与"单一来源"约定）→ **本文件**。

---

## 0. 结论先行

| 项 | 结果 |
| --- | --- |
| `npm run build` | ✅ 通过 |
| `npm run verify:sse` | ✅ **PASS 27 / FAIL 0**（零依赖、零额度） |
| 新增 | `api/types/sse.ts`（224 行）、`lib/sse.ts`、`scripts/verify-sse.mjs` + alias 钩子 |
| 依赖变化 | **零**（用 Node 22 自带的 `--experimental-strip-types`） |

---

## 1. 为什么 SSE 要单独一轮

R3 建立了"单一来源"的约定，但没实现。当时的理由现在依然成立：

- 后端有**两条**链路，**同名事件的字段不同**；
- 仓促实现极易重蹈 Vue 侧"两条链路类型分叉"的覆辙；
- 而它**可以被独立验证**（用桩帧，不消耗 LLM 额度）。

这一轮把"类型统一"与"解析可测"两件事都做完了，`useChatStream` 留给 R8。

---

## 2. 两条链路的真实差异（从后端源码逐字段读出）

**这不是推测**，是从三个 Java 文件里提取的 `put("key", …)` 全集：

| 事件 | Chat（`/api/chat/stream`） | RAG（`/api/knowledge-bases/{id}/ask/stream`） |
| --- | --- | --- |
| `meta` | `requestId` `conversationId` `userMessageId` `assistantMessageId` `modelRole` `mode` | `requestId` `citations`（预览） |
| `retrieval` | **同一个 `RetrievalEvent.of`** | **同一个** |
| `delta` | `text` | `text` |
| `usage` | `promptTokens` `completionTokens` `totalTokens` | 不发 |
| `done` | `finishReason` **`stopped`** `citations` `resultSummary` `firstTokenMs` `latencyMs` `tool` | `finishReason` **`stop`** `citations` `recordId` `empty` |
| `error` | **`code`** **`message`** `retryable` | **`errorCode`** **`error`** `retryable` |
| `tool_call` / `tool_result` | 发 | 不发 |

### 三个最容易踩的坑

1. **`done.stopped`（Chat）vs `done.stop`（RAG）** —— 同义不同名。
2. **`error.code/message`（Chat）vs `error.errorCode/error`（RAG）**。
3. **RAG 的 `meta.citations` 与 `done.citations` 字段不同**：
   前者是 `RagCitationPreview`（只有 `knowledgeBaseId` / `documentId` / 两个名字），
   后者是 `RagCitation`（多 `index` / `snippet` / `score` / `referenced`）。
   **因为 meta 到达时回答还没开始**，那些字段那时不存在。
   Vue 侧曾把两者混用，直接渲染出 `undefined`。

### 怎么处理

**分层单一来源**：公共字段（`StreamRetrieval`、`StreamDelta`…）一份，
链路专有部分显式 `extends`。**强行合并成一个形状会与后端不符并破坏功能。**

判定链路靠**字段存在性**而非 URL：

```ts
export function isChatDone(d: ChatStreamDone | RagStreamDone): d is ChatStreamDone {
  return 'stopped' in d
}
```

比看 URL 可靠——URL 在不同环境下会变（网关前缀、版本），而 payload 字段是后端契约。

### `failedKnowledgeBases` 必须在类型里

Vue 侧 `api/knowledge.ts` 漏了这个字段，后果是**知识库问答页在"某个库检索失败"时
无法显示"这次少搜了谁"，界面假装全都搜过了**。类型里显式列出它，并写了注释说明后果。

### 后端已定义但未使用的事件

`citation` / `approval_required` 在 `SseEventNames` 里存在，但 grep 全仓**没有任何发送点**。
它们留在类型里（`ReservedEventName`）并注明"当前未使用"——**但不写解析分支**，
写了也永远不会命中，只会制造"看起来支持了"的错觉。

---

## 3. 解析器是移植，不是重造

Vue 侧已有 88 行的成熟实现（`web/src/utils/sse.ts`），R3 任务书 §44 明确
「不要重新造一个解析器」。本文件移植它，**解析规则一字未改**（那是 SSE 协议的要求，
不是业务选择），只做两处改进：

1. **错误接入 R3 的 `ApiError`** —— 不再抛裸 `Error`，上层能分清
   "开流前就失败（401 → 重新登录）"与"流中断了（网络）"。
2. **复用 `API_BASE` 前缀** —— 与 R3 修的那个 bug 保持一致。

### 为什么不用原生 EventSource

它**只支持 GET**且**无法携带 Authorization 头**。本项目两个流式端点都是 POST + JWT。
硬用只有两条路：token 塞 URL（**会写进访问日志与浏览器历史**），或加一次性票据机制。
前者是安全问题，后者是复杂度，都不值得。

---

## 4. 关键设计：解析逻辑抽成纯函数

解析的**难点全在分片**：一帧可能被 TCP 切成两半 arrives，一个中文字符可能被切成两个字节。
把它埋在 `postSse` 的 `for(;;)` 里，就只能靠"真的开一条流"来测——而那需要后端 + 令牌 + 额度。

于是抽成 `createSseParser()`，**纯函数、无依赖**：

```ts
const parser = createSseParser()
for (const chunk of chunks) {
  for (const frame of parser.push(chunk)) handle(frame)
}
parser.flush()   // 取流末尾不完整的那一帧
```

### 写这一段时修掉的一个死代码

第一版的 `push` 里有这么一段：

```ts
if (frames.length > 0) {
  eventName = frames[frames.length - 1].event || eventName   // ← 死代码
}
```

意图是"让下一帧省略 `event:` 时能沿用上一个事件名"。但 `toFrame` 内部有自己的局部
`let name = ''`，外部这个 `eventName` **从未被读取**——意图完全没生效。

正确做法：把 fallback 传进 `toFrame(raw, fallbackEvent)`，并在 `push` 里维护它。
SSE 规范允许省略事件名（表示沿用上一个），后端目前每帧都带，但解析器仍按规范处理——
**否则后端一省这行，前端就静默丢事件类型**。

---

## 5. 怎么在 Node 里跑 TypeScript 源码（零依赖）

验证脚本要**直接 import 源 `.ts`**，而不是 import `dist/`——
因为构建产物被压缩与 tree-shake 过，"分片逻辑还在不在、行为变没变"都看不出来，
而分片恰恰是 SSE 最容易错的地方。

Node 22 自带 `--experimental-strip-types`（擦除类型，零依赖）。但还差两件事，
由 `scripts/alias-hook.mjs`（`module.register` 钩子）补上：

| 缺什么 | 谁在管 | 现象 |
| --- | --- | --- |
| `@/x` 别名 | Vite | `Cannot find package '@/lib'` |
| 相对路径补扩展名 | Vite（`./types/common` → `.ts`） | `Cannot find module .../types/common` |

**为什么不把源码改成相对 import**——那是**为了让工具适应工具**，方向反了。

```bash
npm run verify:sse
# = node --experimental-strip-types --import ./scripts/register-alias.mjs scripts/verify-sse.mjs
```

---

## 6. 验证结果（27 项）

```text
① 基本帧解析            单帧 / event 名 / 含中文的 data
② 帧被 TCP 分片切开     不完整的块不产出帧；补齐后 1 帧且内容完整
③ 一次收到多帧          3 帧 + 顺序与名字正确
④ 心跳 / 非 JSON        `: ping` 与 `data: not-json` 被忽略，正常帧不受影响
⑤ CRLF 换行             \r\n\r\n 正确切帧
⑥ 省略 event:           沿用上一个事件名
⑦ 流末尾不完整帧        flush() 取���
⑧ 中文多字节被切开      在"缓"字中间切开，合成后内容未乱码
⑨ 两条链路的 done       isChatDone / isRagDone 互不误判（4 项）
⑩ 两条链路的 error      isChatError 不把 RAG 的 errorCode 误判为 chat
⑪ dispatchFrame        已注册事件被分派；未注册事件返回 false 不崩
结论：PASS 27 / FAIL 0
```

**这 27 项零依赖、零额度、可重复**——不需要后端、不需要浏览器、不消耗 LLM 额度。

---

## 7. 回归

```text
npm run build              ✅
npm run verify:sse         ✅ PASS 27 / FAIL 0
```

R2/R3 的 `verify-layout` / `verify-auth` 本轮未重跑（R4b 只新增了独立模块，
未触碰 Layout / auth 路径）；R8 接线时应一起跑。

**边界**：`web/`（Vue）、`nexus-*`、旧 E2E、`deploy` 零改动；`docs/update.txt` 未碰。

---

## 8. 下一阶段

**R5（Auth 收尾）** 或 **R6（知识库列表页）**。

R8（Chat）现在可以直接接上：`api/types/sse.ts` 与 `lib/sse.ts` 已就绪，
`useChatStream` 只需把 `SseFrame` 分派到页面状态——**不需要再动类型**。
这正是把 SSE 提前做的收益：R8 不用回头改协议。
