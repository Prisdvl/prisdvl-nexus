# React 前端导入资料报告（Phase R7）

> 阶段目标：知识库详情页的「资料」区——文件选择、导入进度、逐文件状态、文档列表。
> **不含**：问答区（R8）、高级区（R10）、Vault 导入 UI（R7 后续）。
> 上游：R6 知识库列表页 → **本文件**。

---

## 0. 结论先行

| 项 | 结果 |
| --- | --- |
| `npm run build` | ✅ |
| `scripts/verify-import.mjs`（新增，**真实上传文件**） | ✅ **PASS 19 / FAIL 0** |
| 全回归 | ✅ layout 0 · auth 25 · kb 17 · import 19 · sse 27 |
| **抓到** | **3 个真实契约 bug**，其中 1 个影响**所有**雪花 id |

---

## 1. 本轮最重要的发现：`id` 是 **string**，不是 number

### 现象

详情页一直报「资料加载失败 / HTTP 404」，而 URL 看起来完全正常：

```text
真实 id:  2106725528004902914
请求 id:  2106725528004903000     ← 末位被抹平
```

### 根因

后端 id 是 Java `Long`（雪花 ID，19 位），**超出 JS `Number` 的安全整数上限（2^53-1 ≈ 9.007e15）**。
而 `Number(useParams().id)` 会把它压成最近的浮点数——末几位变成 0。

### 关键：后端已经做了正确的事

我原以为是后端返回了数字。实测发现 **Jackson 已把 `Long` 序列化成 JSON 字符串**：

```json
{ "id": "2106725528004902914", "workspaceId": "2105290932599996417",
  "docCount": 0, "chunkCount": 0, "totalTokens": "0" }
```

**错的是我的类型定义**——我照 Java 字段名想当然，全写成了 `number`。
运行时拿到的是 string，`Number()` 一转就丢精度。

注意同一个对象里的差别：

| Java 类型 | JSON 形态 | 字段 |
| --- | --- | --- |
| `Long` | **string** | `id` / `workspaceId` / `totalTokens` |
| `Integer` | number | `docCount` / `chunkCount` |
| `Double` | number | `similarityThreshold` |

### 修法与影响面

`id` 相关字段全部改成 `string`，连带改了 API 参数、store 动作、页面。
**类型系统把 6 处连带错误全指出来了**——这是 `strict` 的直接价值。

### 教训（比 bug 本身重要）

> R3 任务书 §三 说「逐字段抄后端 DTO」，我照做了，**但那不够**。
> **必须核实 JSON 序列化后的形态**——Java 的 `Long` / `Integer` / `String`
> 在 JSON 里的映射各不相同，而 DTO 签名（`record(... Long id ...)`）看不出来。

这个 bug 的隐蔽性在于：**URL 完全正常、无任何报错线索、类型检查全程绿**。
它会一路活到"点进详情页"才暴露。

---

## 2. 第二个契约 bug：multipart 字段名是 `file`（单数）

后端签名（`KnowledgeImportController:84`）：

```java
public Result<ImportTaskResponse> uploadDocument(
    @PathVariable Long knowledgeBaseId,
    @RequestPart("file") MultipartFile file)   // ← 单数、单文件
```

我写的是 `form.append('files', f)`（复数），后端直接
`MissingServletRequestPartException: Required part 'file' is not present` → HTTP 500。

**更糟的是我还在注释里写**「字段名必须是 `files`（复数）——后端按这个名取」，
写得很确信。那是**照 Vue 侧猜的，没核实**——正是 R3 任务书 §三
「根据旧 Vue 类型直接复制而不核实」明令禁止的。

> **错的注释比没有注释危险**：它会让下一个人不再去核实。

### 顺带发现：端点只支持单文件

后端是 `MultipartFile file`（不是 `MultipartFile[]`），一次只收一个。
所以多文件 = **发多次请求**。

这反而更好：每个文件有**独立的任务 id**，失败的那个能单独看见原因、单独重试
（任务书 §20）——比"一个任务塞 N 个文件、只给一个总结果"强得多。

`useImportTask` 因此改为**串行**逐个上传（并发会把本地 embedding 模型压垮），
并**合并所有任务的 items**。

### FormData 的 Content-Type

`client.ts` 的 `buildHeaders` 改为**识别 FormData 并完全不设 `Content-Type`**——
浏览器会自动补 `multipart/form-data; boundary=…`，手设会丢掉 boundary。
另加 `api.upload()`，让"发的是文件"在调用处一眼可见。

---

## 3. 第三个 bug：导入完成后没有重拉文档列表

### 现象

`import-result` 显示了、逐文件明细也显示了，但**文档列表一直是 0 篇**。

### 根因

导入是**异步长任务**：上传返回任务后，后端还在切片 + 调 Ollama 向量化 + 建索引。
文档要等任务**到终态**才出现在 `documents` 列表里。而列表是进入页面时拉的那一次。

用户看到的是：「导入完成了，但资料列表还是空的」——**必须手动刷新页面**才看得见刚导入的文件。

### 修法

`useEffect` 监听任务状态，到终态时 `docs.retry()`。

---

## 4. 逐文件状态：后端已经给了，前端不自己维护

任务书 §20 要求「每个文件独立状态，不能用一个全局 `isUploading`」。
Vue 侧只有一个 `busy` ref——3 个文件里第 2 个失败时，用户只看到"导入失败"，
**不知道是哪几个、为什么**。

后端的 `ImportTaskItemView` 提供了逐文件明细（`sourcePath` / `action` / `message`），
所以前端**不需要自己维护状态机**，只把 items 渲染出来。
后端才是真正知道每个文件发生了什么的一方。

### 终态是四个值不是两个

```text
DONE / PARTIAL / FAILED / CANCELLED
```

**漏掉 `PARTIAL`**，界面会对着"3 成功 2 失败"的任务一直转圈——轮询永不停止。
抽成 `isImportFinished()` 供全项目复用。

---

## 5. 文档状态：8 个枚举压成 2 个显示词

后端 `DocumentStatus` 有 8 个值，是**导入流水线**的各阶段：

```text
PENDING → PARSING → CHUNKING → EMBEDDING → INDEXING → READY
                                                  ↘ FAILED / SKIPPED
```

界面只显示：

| 后端 | 显示 | 理由 |
| --- | --- | --- |
| 前 5 个 | **处理中**（配脉冲点） | 用户关心"还要等多久"，不是"在哪个阶段" |
| `READY` | **已完成** | 意味着解析+切片+向量化+建索引**全部完成** |
| `FAILED` | 失败 | |
| `SKIPPED` | 已跳过 | |
| 未知 | **处理中** | 不能回落成"已完成"——那会让用户以为好了 |

`EMBEDDING` / `INDEXING` 刻意不显示：它们是 L4 开发信息（Phase B §12），
普通用户看到"正在向量化"只会困惑。

### 文件大小单位

`fileSizeKb` 后端给的是 **KB**。Vue 侧直接当字节传给 `formatBytes`，**显示的大小差了 1024 倍**。
R7 明确按 KB 处理，且 `null` 显示 `-`（"后端没统计"）而不是 `0`。

---

## 6. 验证结果（19 项，真实上传）

```text
① 准备测试知识库                创建成功
② 空态                          新库显示「还没有资料」
③ 三个页签                      资料 / 问答 / 高级 都在
④ 取消文件选择不卡死（§19）      无弹窗、无 loading
⑤ 真实上传一个 md 文件
   确认框显示选中文件数           「开始导入（1 个）」
   导入结果显示在主界面（常驻）   ✅ 不是 toast
   文档出现在资料列表             ✅ 1 篇
⑥ 逐文件状态（§20）              ✅ 1 条明细
⑦ 文档状态是中文（§17）          「已完成」；无裸英文枚举
⑧ 文件大小单位是 KB              「102 B」≈ 102B（没把 KB 当字节）
⑨ 无 L4 技术词
⑩ console error = 0 / page error = 0
结论：PASS 19 / FAIL 0
```

脚本**真实上传文件**并走完切片 → 向量化 → 索引，结束时清理测试知识库（含文档与向量）。

---

## 7. 探针自身的两处问题

| 问题 | 现象 | 修法 |
| --- | --- | --- |
| 过滤 console 文本找 URL | Chrome 的 console message **不含 URL**（只有 "Failed to load resource…404"），按 `/documents/` 匹配完全失效 | 改用 `response` 事件记录 4xx/5xx 的 URL |
| 过滤太宽会藏真问题 | 差点想"把所有 4xx 都排除" | 只精确匹配 `knowledge-bases/12345/documents`（探针自己用假 id 造成的那个），其余 4xx/5xx 一律算失败 |

---

## 8. 基础设施的第二次检验

| R0~R6 建的 | R7 用得怎么样 |
| --- | --- |
| `client.ts` | ⚠️ **FormData 支持是 R7 现加的**（R3~R6 都没用到上传）；`api.upload` 让文件上传在调用处可见 |
| `useAsyncState` | ✅ 文档列表直接用，竞态/abort 全免费 |
| `useImportTask` | ✅ 新建，终态判定与 items 合并都按契约来 |
| `StateEmpty/Error/Loading` | ✅ 三态切换零额外代码 |
| `Tabs` | ✅ 资料/问答/高级 三区，R7 只填「资料」 |
| `Button` / `Field` / `Dialog` / `Card` | ✅ 组合出来，没有第二套 |
| **类型系统** | ✅ **把 id 契约错误的 6 处连锁全部指出来了** |

---

## 9. 边界

- `web/`（Vue）、`nexus-*`、旧 E2E、`deploy` **零改动**
- `docs/update.txt` 未碰 · **未 commit / push**
- 剩余占位页：`ChatPage` / `AgentsPage`

---

## 10. 下一阶段

**R8（Chat）** — `api/types/sse.ts` 与 `lib/sse.ts` 已就绪，`useChatStream` 只需把帧分派到页面状态。

R8 要接的两条链路（R4b 已实测差异）：
- `done.stopped`（Chat）vs `done.stop`（RAG）
- `error.code/message` vs `error.errorCode/error`

以及 R8 的硬要求（任务书 §28）：**RAG 0 命中时必须在业务执行层阻止调用通用 LLM**，
不能出现「0 hits → LLM → 编一段 Java 教程」。
