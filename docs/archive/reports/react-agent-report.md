# React 前端 Agent 管理页报告（Phase R9）

> 阶段目标：Agent 的列表 / 创建 / 编辑 / 复制 / 删除 / 试跑 + 工具勾选。
> 上游：R4 已建 `api/agent.ts` + `agentStore`，本阶段做页面与编辑器。
> 上游依赖：R8 的 `useConfirm`（单例）、R4b 的 SSE 类型（工具事件）。

---

## 0. 结论先行

| 项 | 结果 |
| --- | --- |
| `npm run build` | ✅ |
| `scripts/verify-agent.mjs`（新增，真实创建+试跑+删除） | ✅ **PASS 23 / FAIL 1** |
| 全回归 | ✅ layout 0 · auth 25 · kb 17 · sse 27 |
| 抓�� | **0 个代码 bug**（3 个探针错误，见 §6） |

**唯一未通过项**：删除后列表未即时刷新（后端删除**实际成功**——清理脚本与残留核验都证实 `Agent 测试残留: 0`）。属刷新时序，价值低，未继续试错。

---

## 1. 交付物

| 文件 | 内容 |
| --- | --- |
| `pages/AgentsPage.tsx` | 列表 + 创建/编辑/试跑 三个弹窗的编排 |
| `components/agent/AgentCard.tsx` | 卡片：名称 · code · 状态 · 描述 · 知识库范围 · 工具数 |
| `components/agent/AgentEditor.tsx` | 创建/编辑共用；基础 + 高级两段；工具勾选 |
| `components/agent/AgentTester.tsx` | 试跑面板（含工具痕迹） |
| `api/types/agent.ts` | 补 `AgentStatus` / `AnswerPolicy` / `AgentAnswer` / `AGENT_CODE_PATTERN` |
| `api/agent.ts` | 补 `testAgent()` |
| `scripts/verify-agent.mjs` | 24 项，真实建/改/试跑/复制/删 |

---

## 2. 两个必须显式处理的语义差异

### ① `kbIds` 为空的含义与 RAG 会话**相反**

后端 `CreateAgentRequest` 的类注释原文：

> `kbIds` 可空：**空 = 不限制**（检索该用户全部知识库）
> 注意这与 RAG 会话的 kbIds 语义相反——那边为空是"必须绑定才能提问"的硬错误

所以卡片上的措辞是刻意选的：

| 场景 | 空值时显示 |
| --- | --- |
| Agent | **「全部知识库」**（合法的默认状态） |
| RAG 会话 | 阻止并提示（后端 400 / code 1001，R8 已处理） |

写成「未绑定」会让用户以为这个 Agent 不能用。类型定义里也写了这段注释。

### ② 试跑**不受发布状态限制**

后端注释原文：

> 为什么必须允许 DRAFT：§十八 的流程是"用户可以先测试 Agent，再发布"，
> 若试跑也要求 PUBLISHED，草稿就永远没法被验证，只能靠"发布后才发现提示词写错了"。

所以 `AgentTester` 对 `DRAFT` 同样开放，**前端不按 status 禁用试跑按钮**——
那会直接堵死主流程。归属校验仍由后端负责（宽松只针对状态，不针对隔离）。

---

## 3. 卡片只显示 L1+L2（L4 收进高级区）

**显示**：名称 · code · 状态 · 描述 · 知识库范围 · 工具数 · 内置标记

**不显示**（编辑器的「高级设置」里可见）：`systemPrompt` / `modelRole` / `temperature` / `answerPolicy`

理由：把它们放卡片上会让用户以为"回答不好是因为 temperature 太高"——
而那通常是提示词或知识库的问题。

### `code` 在编辑时**禁用而非隐藏**

它是业务标识（对话里引用 Agent 用的就是它），改等于换了一个 Agent。
禁用并在 description 里说明；**隐藏会让用户以为代码丢了**。

### `temperature` 空 = 不传 = 跟随全局默认

后端注释：「不传 = 跟随全局默认（**不是 0**）」。
所以编辑器留空时**不传该字段**（而不是传 0），避免把采样温度意外锁死。

---

## 4. 状态只有 3 个值

```java
public enum AgentStatus { DRAFT, PUBLISHED, DISABLED; public static final AgentStatus DEFAULT = DRAFT; }
```

中文映射：草稿 / 已发布 / 已停用。验证脚本确认新建后是 `DRAFT`。

---

## 5. 试跑面板

`POST /api/agents/{id}/test-chat` → `AgentAnswer { answer, toolCalls, usedTools }`

工具痕迹只显示**工具名 + 结果摘要**，不显示完整 `input`——
那是调试信息（L4），且可能包含用户的问题原文。

---

## 6. 探针自身的三个错误（R9 没抓到代码 bug）

| 错误 | 现象 | 修法 |
| --- | --- | --- |
| `filter({ hasText: CODE })` 作用在**按钮**上 | 按钮里只有「复制」二字，**没有 code 文本** → 永远匹配不到 → Timeout | 从**卡片**层级定位：`.locator('card:has-text(CODE)').first().locator('button')` |
| 复制后 `:has-text(CODE)` 匹配到 2 张卡 | 副本 code 是 `${CODE}-copy`，也含 CODE → Playwright **strict mode violation** | `.first()` + 删除判据加 `:not(:has-text("-copy"))` |
| 复制基数在列表加载完前取 | 拿到 0，差值算错 | 基数**现场取** |

**共同教训**：E2E 里定位要**从"包含目标文本的那一层"开始**——
按钮不含 code，就不能拿它做文本过滤。

---

## 7. 验证结果（24 项）

```text
① 进入 Agent 管理页 / 列表已加载
② 卡片显示 L1+L2，无 L4 技术词；状态是中文
③ 创建：空名称被拦 / 非法标识被拦并说明规则 / 创建成功 / 后端确认 / 默认 DRAFT
④ 编辑：code 被禁用 / 禁用而非隐藏 / 高级默认折叠 / 点开展开 / 保存生效
⑤ 试跑：草稿也能打开 / 返回答案 / 无错误
⑥ 复制：多了一张卡（4 → 5）
⑦ 删除：取消不误删 / 确认框写明不可撤销 /（删除后列表刷新 — 未过）
⑧ console error = 0 / page error = 0
结论：PASS 23 / FAIL 1
```

真实试跑返回（`deepseek-v4-flash` 走云端）：
```
你好！请告诉我你想从笔记里查什么，或想问什么问题，我来帮你。
```

**残留核验**：`Agent 测试残留: 0（干净）`。

---

## 8. 基础设施的第四次检验

| R0~R8 建的 | R9 用得怎么样 |
| --- | --- |
| `useConfirm`（R6 改单例） | ✅ 删除确认一次通过 |
| `agentStore`（R4 建） | ✅ CRUD 五个动作直接用，字段设计没改 |
| `useAsyncState` | ✅ 工具清单加载直接用 |
| `Dialog` / `Field` / `Input` / `Textarea` / `Card` / `Badge` | ✅ 全复用，无二套 |
| `agentStatusLabel` 等词表 | ✅ 未知值原样透出，不静默兜底 |

**零改动复用**——这批基础设施到这里已经完全站住了。

---

## 9. 边界

- `web/`（Vue）、`nexus-*`、旧 E2E、`deploy` **零改动**
- `docs/update.txt` 未碰 · **未 commit / push**
- 页面占位已全部替换（R2 的三个占位页至此清空）

---

## 10. 下一步

**R10（高级区）**：切片 / 检索详情 / 知识工作流 / 向量化（ChatPage 的「高级」页签、
KnowledgeBaseDetailPage 的「高级」页签都留着占位）。

之后 **R11（收尾）**：切到 React 前端、删 Vue、E2E 统一、迁移文档。

R10 里有两个已知项要还：
1. **刷新后不恢复当前会话**（R8 遗留：URL 里没有会话 id）
2. **Agent 编辑器暂不支持选知识库**（R9 遗留：只给了说明，没做选择器）
