# React 前端高级区报告（Phase R10）

> 阶段目标：知识库高级区——切片列表（含向量/索引状态）、检索试验、索引重建。
> 上游：R7 导入资料 → **本文件**。
> ⚠️ **一处修复未经实测**，见 §0。

---

## 0. 结论先行

| 项 | 结果 |
| --- | --- |
| `npm run build` | ✅ 通过 |
| `scripts/verify-advanced.mjs`（新增） | ✅ **PASS 19 / FAIL 0** |
| 全回归 | ✅ layout 退出码 0 · auth 25 · kb 17 · sse 27 · advanced 19 |
| 抓到 | **1 个真实逻辑错误**（切片状态判据，见 §3） |

### 关于"未实测"标注

第一次跑出 `PASS 18 / FAIL 1` 后我修了 §3 那个错误判据，
但当时 Docker 停了（今天第 4 次），修复**未重跑**。
Docker 恢复后补跑确认：**PASS 19 / FAIL 0**，那处修复有效——
「已导入的切片向量/索引均正常（无失败徽标）」现在通过（向量徽标 0 / 索引徽标 0）。

## 1. 交付物

| 文件 | 内容 |
| --- | --- |
| `components/knowledge/AdvancedPanel.tsx` | 索引状态 + 检索试验 + 切片列表 |
| `api/types/knowledge.ts` | 补 `ChunkView` / `SearchHit` / 状态判据函数 |
| `api/knowledge.ts` | 补 `listChunks` / `searchChunks` / `recreateIndex` / `embedKnowledgeBase` |
| `pages/KnowledgeBaseDetailPage.tsx` | 「高级」页签接入 + 检索模式切换 |
| `scripts/verify-advanced.mjs` | 19 项，真实调检索接口 |

---

## 2. 高级区解决什么问题

导入成功只代表**文档与切片进了数据库**。要让检索命中还差两步：

```text
解析 → 切片 → 向量化（写向量库）→ 建 ES 索引
                       ↑                ↑
                  vectorStatus      esStatus
```

任一步失败，检索就命中不了，但用户在「资料」页**看不到任何异常**——
文档数、切片数都正常。Vue 侧改造前就是这个状态：
用户报"明明导入过了却搜不到"，而界面毫无线索。

所以高级区做三件事：

| 功能 | 作用 |
| --- | --- |
| **按文档看切片** | 定位是哪一步断了（向量未完成？ES 未索引？） |
| **检索试验**（hybrid / vector / es） | 判断是哪一路失灵 |
| **重建索引** | 修不好时的兜底（危险操作，必须确认） |

### 检索模式是最有用的开关

| 模式 | 搜不到说明 |
| --- | --- |
| `vector` | 向量这一路断了（换模型没重算？embedding 服务挂了？） |
| `es` | 关键词这一路断了（ES 没索引？） |
| `hybrid` | 两路都断，或查询词本身没匹配上 |

实测：`vector` 模式搜「缓存」命中 4 条，`source` 标记为 `vector`。

### 为什么默认折叠（Phase B §12）

99% 的用户不需要看切片。但出问题时**必须能看到**——
既不能藏起来（用户报障时无法自证），也不能默认摊开（淹没主要内容）。

---

## 3. 抓到的真实逻辑错误：切片状态判据

### 我第一版写的（错的）

```ts
// ❌ 以为「非 null = 异常」
export function isChunkNotReady(c: ChunkView): boolean {
  return c.vectorStatus !== null || c.esStatus !== null
}
```

### 后端实际返回的

```json
{ "chunkIndex": 0, "vectorStatus": "DONE", "esStatus": "DONE", "tokenCount": 23 }
```

**`DONE` 是成功，不是"未就绪"。**

于是刚导入完、检索也命中的文档，被标成
「有 1 个切片未完成向量化或索引」——**与事实完全相反，比不显示更糟**。

### 修法：看状态值，不看"有没有值"

```ts
const READY_STATES: ReadonlySet<string> = new Set(['DONE', 'SUCCESS'])
const PENDING_STATES: ReadonlySet<string> = new Set(['PENDING', 'QUEUED', 'RUNNING'])

export function isStateReady(s: string | null): boolean {
  if (s === null) return false      // 后端还没写这个字段 → 未就绪
  return READY_STATES.has(s)
}

export function isChunkNotReady(c: ChunkView): boolean {
  return !isStateReady(c.vectorStatus) || !isStateReady(c.esStatus)
}
```

徽标也改成**只在未就绪时显示**——满屏「向量 正常 索引 正常」是噪音，
异常才是要看的东西。

> **教训**：后端的状态字段**有值 ≠ 异常**。
> 我把"字段有值"当成了"状态异常"——这类错误**只有实测返回值才能发现**，
> 类型系统（都是 `string | null`）和编译器都发现不了。

---

## 4. L4 分层

**资料页**（L1/L2）：文档名 / 大小 / 切片数 / 状态。
**高级区**（L4，允许调试信息）：切片正文、token 数、`source`、`score`、检索模式。

`score` 在高级区**保留显示**（已格式化到 3 位）——这一页本来就是给排查的人看的，
藏起分数反而妨碍判断。验证脚本专门断言资料页不含检索调试信息。

---

## 5. 验证结果（19 项，全通）

```text
① 找到含文档的知识库「我的笔记」/ 高级区渲染 / 三个卡片存在
② 检索模式切换 hybrid·vector·es 三次全对
③ 检索试验：vector 模式搜「缓存」→ 4 命中，source=vector，内容无 undefined/NaN
④ 切片列表有内容，无 undefined/NaN
   已导入的切片向量/索引均正常（无失败徽标）— 向量徽标 0 / 索引徽标 0  ← §3 修复的验证
⑤ 重建索引按钮存在（本轮**不执行**，它会重写真实数据）
⑥ 资料页无检索调试信息（L4 分层正确）
⑦ console error = 0 / page error = 0
结论：PASS 19 / FAIL 0
```

## 6. 遗留（本轮明确不做）

| 项 | 原因 |
| --- | --- |
| Chat 页的「高级」页签 | 占位仍在。检索试验在知识库侧已够用 |
| 知识工作流 | 复杂度高，属独立能力（Phase B 列为可选） |
| 更换 embedding 模型 | 会让已有向量全部失效，属危险操作，需单独设计确认流程 |
| 执行「重建索引」 | 探针**故意不点**——它会重写真实数据 |
| R8 遗留：刷新后不恢复当前会话 | URL 里没有会话 id |
| R9 遗留：Agent 编辑器不支持选知识库 | 只给了说明文案 |

---

## 7. 边界

- `web/`（Vue）、`nexus-*`、旧 E2E、`deploy` **零改动**
- `docs/update.txt` 未碰 · **未 commit / push**

---

## 8. 下一步

**R11（收尾）**：切到 React 前端、删 Vue、E2E 统一、迁移文档。

同时建议先解决两件阻塞性事项：
1. **Docker 稳定性**——今天停了 4 次，R8/R10 的验证都被它打断过
2. **重跑 R10 的修复**（一次 `docker compose up -d` 即可）
