# React 前端 Layout 报告（Phase R2）

> 阶段目标：**只做 Layout / Navigation / Workspace Shell**。
> 未实现：登录业务、知识库业务、Chat 业务、Agent 业务、RAG、Streaming、Tool Calling。
> 上游：`docs/frontend-information-architecture.md`（Phase B，产品基线）→
> `web-react/`（R0 骨架 / R1 Design System）→ **本文件**。

---

## 0. 结论先行

| 项 | 结果 |
| --- | --- |
| `npm run build` | ✅ 通过 |
| `verify-layout.mjs` | ✅ **10 组全过** |
| 浏览器真实验证 | ✅ console error = 0 / page error = 0 |
| 产物体积 | JS 352.7 kB / CSS 27.2 kB（R1 为 339.9 / 25.9） |
| 业务页面 | ✅ **一个都没做** |
| `web/`（Vue）· 后端 · E2E | ✅ **零改动** |

**必须说清的一件事**：交付物是**布局与外壳**，不包含任何业务。
占位页（`PlaceholderPage`）仍在 `pages/` 里，那是 R0 留下的，R5 起逐个替换。

---

## 1. Layout Architecture

### 结构

```text
<WorkspaceLayout>                     data-testid="workspace-shell"
├── <AppHeader>                       data-testid="app-header"
│   ├── 移动端菜单按钮（<lg 隐藏）
│   └── 页面标题 / 面包屑
├── <AppSidebar>                      data-testid="workspace-sidebar"
│   ├── 品牌区
│   ├── 导航（知识库 / 对话 / Agent）
│   └── 折叠开关 + <UserMenu>
├── <main>                            data-testid="workspace-main"
│   └── <Outlet />
└── <Sheet>（仅窄屏）抽屉版侧栏
```

### 三条硬边界

| 边界 | 怎么保证的 |
| --- | --- |
| Layout 不调业务 API | `WorkspaceLayout.tsx` 的 import 只有布局组件与 `ui/sheet`，**没有 `api/`**。这条可以直接 grep 验证 |
| Layout 不知道用户是谁 | `UserMenu` 接收不到任何用户信息，账号区是 `footer` 插槽注入的 |
| Layout 不实现业务 Loading | 只有 `<Outlet />`，没有全局 loading。任务书 §20 由页面自己决定用 `StateLoading` 的哪种粒度 |

### 为什么 Header 横跨顶部（而不是嵌在主区）

任务书 §5 的结构图如此。这不只是审美：**Header 横跨意味着窄窗口下它始终可见**，
而侧栏可以被 Sheet 收进浮层——用户在 1024px 下仍看得见自己在哪、能切换页面。

---

## 2. Router Architecture

### Layout Route

```text
/login  → AuthLayout → LoginPage
/       → WorkspaceLayout → ┬ /          KnowledgeBaseListPage
                            ├ /kb/:id    KnowledgeBaseDetailPage
                            ├ /agent     ChatPage
                            └ /agents    AgentsPage
*       → NotFoundPage
```

Layout 承担外壳，子路由只提供内容——**页面上不该再各自画一遍侧栏与 Header**。
这是 `Outlet` 存在的全部意义。

`routes` 数组显式标注 `RouteObject[]`：不标注时 TS 会从字面量推断出联合类型，
后面 `push({ path, element })` 这种「无 children 的独立路由」推不进去（实测报错）。

### 路由表只有这 5 条

一级导航固定为**知识库 / 对话 / Agent**，不加首页 / Dashboard / Settings（任务书 §3、
Phase B §3）。`/` 仍然是知识库列表——**当前产品没有首页概念**，造一个只会让同一个答案多一次点击。

---

## 3. Sidebar

### 折叠与持久化

| 项 | 做法 |
| --- | --- |
| 默认 | `expanded`（`collapsed = false`） |
| 存储键 | `nexus.sidebar.collapsed`（与 `nexus.token` / `nexus.username` 同前缀） |
| 读不到时 | **fallback 到 expanded**。宁可多占一点宽度，也不要用户莫名其妙看到一个只有图标的侧栏 |
| 写不进去时 | 静默忽略——本次会话内状态仍正确，只是刷新后丢失，不该因此报错 |
| 宽度 | 240px（展开）↔ 64px（收起），`transition-[width]` 过渡 |
| `data-collapsed` | 挂在侧栏根节点上，供 E2E 读取**真实状态**而不是推断 |

读写都包 `try/catch`：`localStorage` 在无痕模式、禁用 cookie、配额满时会抛（任务书 §28）。

### 收起后仍可识别

收起时导航项只剩图标，此时：

- **Tooltip**（`side="right"`）——鼠标悬停给名称
- **`<span className="sr-only">`** ——读屏用户的兜底

**只有图标没有解释的导航是典型的"用户不知道怎么回去"**（任务书 §7）。
E2E 明确验了 `sr-only` 存在。

折叠开关的 `aria-label` 会**跟着状态翻转**：「收起侧栏」↔「展开侧栏」，
并带 `aria-expanded`。E2E 验了这一条——aria-label 不翻转的话，
读屏用户按下按钮前完全不知道会发生什么。

### 激活态：一个真相源

这里踩过一个真实的坑，值得写清楚。

**第一版**用 `NavLink` 的 `end` 属性。`end` 只能精确匹配，于是 `/kb/12345` 下
「知识库」不高亮——用户会以为自己"离开了知识库"。
Vue 版是用 `match: ['knowledge-bases','knowledge-base-detail']` 显式列出两个路由名解决的。

**第二版**改成 `isNavActive(item.match, pathname)` 自己判定。样式对了
（`bg-primary-container` 生效），但 **`aria-current` 缺失**——因为 `NavLink`
自带另一套匹配逻辑，两套真相冲突时它说了算。

**最终版改用 `Link`**（不是 `NavLink`），激活判定完全由 `isNavActive` 负责。
两套逻辑并存时，只保留一套。

```ts
export function isNavActive(match: readonly string[], pathname: string): boolean {
  return match.some((p) => pathname === p || pathname.startsWith(`${p}/`))
}
```

**末尾的 `/` 不能省**：`'/agents'.startsWith('/agent')` 是 `true`，
会把「对话」和「Agent」同时点亮。

| 路径 | 知识库 | 对话 | Agent |
|---|---|---|---|
| `/` | ✅ | | |
| `/kb/12345` | ✅ | | |
| `/agent` | | ✅ | |
| `/agents` | | | ✅ |

---

## 4. Header

只显示**当前页面名**（一级页面）或**面包屑**（有层级时）+ 右侧 `actions` 插槽。

### 面包屑：只在有层级时出现

任务书 §11 说「不要为了看起来专业强行加」。判据很简单：**有父级才显示**。

| 页面 | 显示 |
|---|---|
| `/` `/agent` `/agents` | 只有标题 |
| `/kb/:id` | `知识库 / 知识库详情` |

### 标题不靠中文文案判断

`ROUTE_META` 是**路径 → 标题**的唯一映射。判据是路径相等，不是"当前标签等于『Agent 管理』"——
R0 已把导航标签从「Agent 管理」缩成「Agent」，靠文案判断的话这次改名就会让标题与高亮一起失效，
而且**不报错**。

整页始终只有**一个 `<h1>`**：一级页面在 Header 里，详情页走面包屑分支（用 `span`）。

---

## 5. 两个 Sidebar 的状态必须独立

任务书 §13 明确：Workspace 侧栏与未来的 Chat 侧栏是两套状态，
不能用一个 `sidebarCollapsed` 同时控制。

R2 的实现为将来做好了准备：

| 侧栏 | 状态 | 存储键 |
| --- | --- | --- |
| Workspace | `AppSidebar` 内部 `useState` | `nexus.sidebar.collapsed` |
| Chat（**R8 才实现**） | 独立的 key | `nexus.chat.sidebar.collapsed`（约定，尚未使用） |

现在只有 Workspace 一套，符合 R2 范围。**但 Chat 侧栏落地时不得复用同一个 key**——
否则用户在对话页收起会话列表，会连带把全局导航也收起。

---

## 6. Responsive

### 断点

| 宽度 | 侧栏 | 说明 |
| --- | --- | --- |
| ≥1024 | 完整侧栏（可展开/收起） | Desktop + Tablet |
| <900 | Sheet 抽屉 + Header 菜单键 | 手机 |

**为什么阈值是 900 而不是 1024**：1024 是 iPad 横屏，属 Tablet，
任务书 §14 明确「先解决 Desktop / Tablet」——那里放得下 240px 侧栏（主区仍有 784px）。
真正需要抽屉的是手机。

我最初的探针断言「1024 必须抽屉」，那是**断言写错了**，不是实现错——已按实际断点修正。

### 横向溢出：三条一起上

任务书 §15 特别点了 `width: 100vw` 的坑——**100vw 含滚动条宽度**，
在 flex 容器里会与侧栏叠加出横向滚动条。正确姿势：

```tsx
<div className="flex h-full min-w-0 flex-col overflow-hidden">   // 外层
  <div className="flex min-h-0 min-w-0 flex-1">                 // 中层
    <main className="min-w-0 flex-1 overflow-y-auto">             // 主区
```

**主区的 `min-w-0` 是最关键的一条**：缺它时，Chat 的长回答、代码块、长表格
会把整个 flex 容器撑宽，表现为整页横向滚动条。实测三种分辨率溢出均为 **0px**。

---

## 7. Accessibility

| 项 | 做法 | 实测 |
| --- | --- | --- |
| 侧栏 landmark | `<nav aria-label="主导航">` | ✅ |
| 折叠开关 | `aria-label` 随状态翻转 + `aria-expanded` | ✅ |
| 激活导航 | `aria-current="page"` | ✅ |
| 收起态图标 | Tooltip + `sr-only` 文本 | ✅ |
| 移动端菜单 | `aria-label="打开导航"` + `lg:hidden` | ✅ |
| 用户菜单 | 在 `role="dialog"` 内，焦点被困住，ESC 关闭并**归还焦点** | ✅ |
| 键盘 | Tab 可达导航项与折叠开关；Enter 激活导航 | ✅ |
| 面包屑 | 有父级时才渲染，`aria-current="page"` 标当前项 | ✅ |

---

## 8. State

| 状态 | 归属 | 持久化 |
| --- | --- | --- |
| 侧栏折叠 | `AppSidebar` 内部 | ✅ `nexus.sidebar.collapsed` |
| 移动端抽屉开合 | `WorkspaceLayout` 内部 | ❌（刷新即关，合理） |
| 用户菜单开合 | `UserMenu` 内部 | ❌（同上） |
| 当前路由 | React Router | URL |

**没有全局 store 参与**——R4 才建 Zustand。R2 刻意不引 `useAuthStore`：
现在建一个会让 R2 反向依赖 R4。

---

## 9. testid

全部 `data-testid`，**无一处用中文文案定位**。

| testid | 用途 |
| --- | --- |
| `workspace-shell` | 外壳根 |
| `workspace-sidebar` | 侧栏（带 `data-collapsed`） |
| `workspace-sidebar-toggle` | 折叠开关 |
| `workspace-nav-knowledge` / `-chat` / `-agent` | 三条导航 |
| `workspace-main` | 主区 |
| `app-header` / `app-header-title` / `app-header-menu` | 顶栏 |
| `user-menu` / `user-menu-profile` / `-password` / `-logout` | 账号区 |
| `auth-layout` | 登录页外壳 |
| `not-found` / `not-found-home-link` | 404 |

**与 Vue 侧的命名差异**：Vue 用 `nav-knowledge` / `app-sidebar`，
React 用 `workspace-nav-knowledge` / `workspace-sidebar`。两套并存是有意的——
Phase R11 迁移 E2E 时统一到一套，现在改名会让 Vue 侧 132 个已稳定的 testid 白改一次。

---

## 10. Browser Verification

`scripts/verify-layout.mjs`（**已入库**，非临时探针），10 组：

```text
① 外壳结构          shell/header/sidebar/main/toggle/user-menu 全部渲染
② 一级导航          三条导航项存在
③ 导航高亮          4 条路径各自只点亮正确的项
④ Sidebar 折叠      240→64px；aria-label 翻转；localStorage 写入；
                    刷新后保持；切换路由后保持；收起态有 sr-only
⑤ Header 标题       4 条路径的标题正确
⑥ 404               not-found 渲染 + 返回链接 href=#/
⑦ 键盘              Tab 可达导航与折叠开关；Enter 激活导航
⑧ 用户菜单          三项渲染；在 dialog 内；ESC 关闭
⑨ 响应式            1920/1366/1024 横向溢出均 0px；侧栏与主区正常
⑩ 错误              console error = 0；page error = 0
```

三种分辨率实测：

| 分辨率 | 横向溢出 | 侧栏 | 主区 |
| --- | --- | --- | --- |
| 1920×1080 | 0px | 有 | 1680px |
| 1366×768 | 0px | 有 | 1126px |
| 1024×768 | 0px | 有 | 784px |

---

## 11. 本轮抓到的三个真实缺陷

都由 `verify-layout.mjs` 抓到，不是靠读代码看出来的：

1. **`/kb/12345` 下「知识库」不高亮**。根因是 `NavLink` 的 `end` 只做精确匹配；
   改成 `isNavActive` 后样式对了但 `aria-current` 仍缺失（NavLink 另一套逻辑覆盖）。
   **最终改用 `Link`，激活判定单一真相**。
2. **`routes.push({ path, element })` 推不进 TS 推断出的联合类型**。显式标注
   `RouteObject[]` 解决。
3. **探针自身的两个错误**（第五次验证工具出问题）：
   - 响应式断言写成「1024 必须抽屉」，与任务书 §14「先解决 Desktop/Tablet」矛盾 → 改成阈值 900；
   - 我一度用 `grep "a\|b"` 误判 python 替换失败（双引号里 `\|` 不生效），
     而实际替换是成功的。**验证工具出错时，先怀疑工具。**

另外一次环境教训：**HMR 没生效**。改了组件后 dev server 仍跑旧代码，
导致「代码逻辑正确但验证结果错误」。**验证结果与代码矛盾时，先怀疑 HMR。**

---

## 12. Remaining Issues

1. **业务页面仍是占位**。`pages/` 下 5 个都是 R0 留下的 `PlaceholderPage`，
   R5 起逐个替换。
2. **Chat 侧栏的存储键只是约定**（`nexus.chat.sidebar.collapsed`），R8 落地时不得与
   Workspace 侧栏共用。
3. **未登录只有"外壳"，没有守卫**。任务书 §18 明确 R2 不重写认证，R5 补。
   现状：`/login` 可直接访问，工作区也不检查登录态（占位页不调 API，所以看不出问题）。
4. **侧栏 240px 是写死的**，没有响应内容长度。内容极长时（如知识库名很长）可能截断。
5. **用户菜单是纯 placeholder**，头像硬编码为 `D`、用户名硬编码为 `demo`。R5 接 `authStore`。
6. **没有引入 React Bits**（延续 R1 决策：registry 不可用、背景类与"长期使用"定位冲突）。

---

## 13. 下一步

按任务书 §60 的顺序，下一步是 **R3（API + SSE 类型）**。
R2 到此 **STOP**。
