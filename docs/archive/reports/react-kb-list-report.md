# React 前端知识库列表页报告（Phase R6）

> 阶段目标：交付**第一个业务页面**，同时检验 R0~R4 建立的基础设施是否真的可用。
> 范围：知识库列表 / 创建 / 删除 / 空态 / 错误态。**不含**导入资料（R7）、文档列表（R7）、问答（R8）。
> 上游：R0 骨架 → R1 Design System → R2 Layout → R3 API/鉴权 → R4 Hooks → R4b SSE → **本文件**。

---

## 0. 结论先行

| 项 | 结果 |
| --- | --- |
| `npm run build` | ✅ |
| `scripts/verify-kb.mjs`（新增） | ✅ **PASS 17 / FAIL 0** |
| 全回归 | ✅ layout 退出码 0 · auth PASS 25 · sse PASS 27 |
| 抓到 | **2 个真实 bug**（都是 R0~R4 埋的，只有走真实用户路径才暴露） |

---

## 1. 交付物

| 文件 | 内容 |
| --- | --- |
| `components/knowledge/KnowledgeBaseCard.tsx` | 卡片：名称 / 启用状态 / 文档数 / 切片数 / Token / 更新时间 |
| `pages/KnowledgeBaseListPage.tsx` | 列表 + 创建弹窗 + 删除（含危险确认） |
| `api/knowledge.ts` | 补 `create` / `delete`（R3 只有 `list`） |
| `stores/knowledgeStore.ts` | 补 `create` / `remove` |
| `scripts/verify-kb.mjs` | 17 项验证，**会真建一个库再删掉**，含残留清理兜底 |

---

## 2. 一个必须先讲的概念纠正

勘察时发现：**知识库的 status 与导入任务的 status 是两件事**，而 Vue 侧把它们混在一起。

```text
后端 KnowledgeBaseStatus 只有两个值：ENABLED / DISABLED   ← 知识库自身（启用/停用）
后端 ImportTask 的状态：QUEUED / RUNNING / DONE / PARTIAL / FAILED / CANCELLED   ← 导入任务
```

Vue 侧的 `statusText(kb)` 读的是 `importTasks[kb.id].status`——
也就是卡片上"处理状态"那一栏显示的**其实是最近一次导入任务的状态**，
不是知识库自身状态；新用户还没导入过时它干脆返回空字符串。

**R6 的处理**：卡片只显示 `ENABLED` / `DISABLED`（"启用中" / "已停用"），
导入任务状态留给 R7 单独建 store。`knowledgeStore` 里写了注释说明这个区分。

---

## 3. 三态严格分离

| 状态 | 判据 | 组件 |
| --- | --- | --- |
| 加载中 | `useAsyncState` 的 `state === 'loading'` | `StateLoading variant="list"` |
| 失败 | `state === 'error'` | `StateError` + 重试 |
| 成功但为空 | `state === 'success' && list.length === 0` | `StateEmpty` + 创建 |
| 有数据 | `state === 'success' && list.length > 0` | 卡片网格 |

最后两行的判据里 **`state === 'success'` 是必需的**。
Vue 侧的缺陷正是"加载失败 → toast 消失 → 落到 `length === 0` → 显示成『你还没有知识库』"，
把"不知道"说成了"没有"。这里失败会**停**在 `StateError`，永远不退化成空态。

**这也是 `useAsyncState` 的第一个真实用例**——R4 写的四态抽象，
第一次被业务页面检验。

---

## 4. 卡片只显示 L1 + L2（L4 默认隐藏）

**显示**：名称 · 启用状态 · 文档数 / 切片数 / Token · 描述 · 更新时间。

**刻意不显示**（后端**都在返回**，写进卡片只需几行）：

```text
embeddingDim  chunkSize  chunkOverlap  embeddingProvider
embeddingModel  chunkStrategy  retrievalTopK  similarityThreshold
```

它们回答的是"系统怎么配置的"，而不是"我的资料怎么样了"。
Phase B §12 明确它们属 L4；要查就放详情页的「高级」区（R10）。
验证脚本会检查页面正文里**不出现**这些词。

### 数字为 null 的处理

`docCount` / `chunkCount` / `totalTokens` 在后端是包装类型，新建库可能为 null。
**显示 `-` 而不是 `0`**——`0` 意味着"确实没有"，`null` 意味着"还没统计"。
两者混为一谈会让用户以为资料丢了。

---

## 5. 抓到的两个真实 bug

### ① 提交按钮在 `<form>` 之外，创建功能完全失效

Dialog 的 `footer` 与 `children` 是**兄弟节点**，不是父子。
我把 `type="submit"` 的按钮放在 footer 里，它**根本不在 `<form>` 内**，
点它不会触发 submit——创建按钮毫无反应。

第一版还写了 `onClick={(e) => void onCreate(e as unknown as FormEvent)}`，
那是个**假 Event**（`preventDefault` 是 no-op），把"签名看起来对"和"能用"混为一谈。

**修法**：footer 按钮用 `onClick={() => void onCreate()}`；
form 保留 `onSubmit` 处理 Enter 键，并在 form 内补一个**隐藏的 submit 按钮**
（form 内没有任何 submit 控件时，部分浏览器按 Enter 不提交）。

> 这类 bug 编译器**发现不了**，`tsc` 全程绿。R0~R4 的问题都是"引用不存在 / 类型不匹配"，
> 这一类是"结构与交互耦合"——**只有真的去点那个按钮**才会暴露。

### ② `useConfirm` 的两个 hook 实例永不相连（R4 埋的）

R4 的 `useConfirm` 把 `pending` 放在 `useState` 里。看着能用，实则完全失效：

```tsx
const confirm = useConfirm()                    // 页面：实例 A
function ConfirmHost() { const confirm = useConfirm() }   // 宿主：实例 B
```

两处 `useConfirm()` 是**两个互不相干的 hook 实例**。页面 `confirm()` 设置的是 A 的 `pending`，
`ConfirmHost` 渲染的是 B 的 `pending`——**永远不相连**，于是"点删除没反应"。

**修法**：状态提到**模块级 Zustand store**，任何地方调用都是同一个实例。

这本来就该是应用级单例——确认框像 `window.confirm` 一样本来就该是全局的。
R4 写的时候把"hook"当成了"状态容器"，这是我的设计失误。

---

## 6. 探针自身的两处错误

| 错误 | 现象 | 修法 |
| --- | --- | --- |
| 只等页面容器出现就断言 | "卡片 0 张 / 空态 0"——其实**还在加载** | 改等 `kb-card` 或 `state-empty` 出现 |
| 选择器指向 form 内的 submit | 找不到（那个按钮在 form 外） | 改用 `kb-create-submit` |

---

## 7. 验证结果（17 项）

```text
① 页面与列表加载
   登录后进入列表页 / 加载出结果 / 没有错误态
② 卡片字段
   名称 / 启用状态「启用中」/ 文档 3 · 切片 4 · Token 150
③ L4 技术词**未出现**（9 个词全查）
④ 创建
   空名称被拦下 / 新库出现在列表 / 后端确实创建成功
⑤ 删除（危险确认）
   确认框写明「不可撤销」/ 写明会失去什么
   取消后知识库仍在（没有误删）
   确认后从列表消失 / 后端也已删除（0 条残留）
⑥ console error = 0 / page error = 0
结论：PASS 17 / FAIL 0
```

脚本带**残留清理兜底**：无论怎么失败，`finally` 都会删掉所有 `R6-verify-` 前缀的库。
本次实测结束时后端残留 0 条。

---

## 8. 基础设施的实际检验结果

R6 是第一次真正"用"前面建的东西。结果：

| R0~R4 建的 | R6 用得怎么样 |
| --- | --- |
| `useAsyncState` | ✅ 四态抽象直接可用，**不需要为列表页特殊处理** |
| `useConfirm` | ⚠️ **设计错了**（两个实例），已改成模块级单例 |
| `StateEmpty/Error/Loading` | ✅ 三态切换零额外代码 |
| `PageHeader` | ✅ 直接用，没有一个页面自己画 Header |
| `Card` / `Button` / `Field` / `Dialog` | ✅ 组合出来，没有第二套 |
| `knowledgeStore` | ✅ 补两个写操作即可，字段设计没改 |
| 令牌与鉴权 | ✅ 零改动，`/api` 链路与 401 处理都对 |

**除 `useConfirm` 外全部即用**——这说明 R0~R4 的抽象层次基本对了。

---

## 9. 边界

- `web/`（Vue）、`nexus-*`、旧 E2E、`deploy` **零改动**
- `docs/update.txt` 未碰
- **未 commit / push**
- 剩余占位页：`ChatPage` / `AgentsPage` / `KnowledgeBaseDetailPage`（R7~R9）

---

## 10. 下一阶段

**R7（导入资料）** 是自然的下一步：
- `KnowledgeDocumentController` 的端点已勘察（`/documents` `/documents/{id}` `/chunks` …）
- 需要建 `ImportTask` store（R4 已确认它与知识库 status 是两回事）
- `useImportTask` 轮询 hook（任务书 §65 列了，但当时判定"依赖 R7 页面"，现在可以做）
- 多文件独立状态（任务书 §20：不能用**一个** `isUploading` 管所有文件）

之后 R8（Chat，`api/types/sse.ts` + `lib/sse.ts` 已就绪）→ R9（Agent）。
