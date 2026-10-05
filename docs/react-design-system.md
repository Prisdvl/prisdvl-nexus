# React 前端 Design System（Phase R1）

> 阶段目标：**把装修材料准备好**（Design Tokens / UI 原语 / 通用组件），
> **不开始装修**（不实现 Knowledge / Chat / Agent / Login 等业务页面）。
> 上游：`docs/frontend-information-architecture.md`（Phase B，产品基线）→
> `web-react/`（R0 骨架）→ **本文件**。
> 进度：`docs/frontend-refactor-progress.md`。

---

## 0. 结论先行

| 项 | 结果 |
| --- | --- |
| `npm run build` | ✅ 通过（`tsc -b && vite build`） |
| 浏览器真实验证 | ✅ 8 组断言全过，**console error = 0** |
| 交付 | 12 个 UI 原语 + 6 个通用组件 + 令牌层 + Showcase |
| **React Bits** | ⚠️ **本阶段刻意未集成**，理由见 §6——不是"装不上"这么简单的结论 |
| 产物体积 | JS 339.9 kB / CSS 25.9 kB（对比 R0 的 315.94 / 10.96 kB） |

**必须先说清一件事**：本阶段产出的组件**没有被任何页面引用**，所以它们**不在生产包里**
（实测 `state-empty` / `confirm-submit` 等在 `dist` 中匹配数为 0）。
这是 R1 的必然状态——组件要到 R2+ 被页面引用才进包。
**真正证明它们可用的是浏览器验证，不是产物。**

---

## 1. 技术栈版本（逐个核对，不抄任务书）

| 包 | 版本 | 依据 |
| --- | --- | --- |
| react / react-dom | 19.3.0 | 任务书指定；`npm view` 实测 |
| typescript | **6.0.3**（非 7.0.2） | TS 7 是 Go 重写编译器，生态未验证。**实测 `baseUrl` 在 TS 6 已废弃**（TS 7 将停止支持）——代价已可见 |
| vite | 8.3.2 | `@vitejs/plugin-react@6.1.1` 的 peer 要求 `^8` |
| react-router | 8.4.0，**从 `react-router` 导入** | v7 起 `react-router-dom` 已合并，多装一层是过时 re-export |
| tailwindcss | 4.3.3（CSS-first） | 无 peer 约束；**不建 `tailwind.config.js`** |
| shadcn | 4.21.1，`base-nova` 预设 | `components.json` 已生成，但 **CLI 的 `add` 不可用**（§7） |
| zustand | 5.0.15 | 任务书指定；R4 才实际使用 |
| lucide-react | 1.51.0 | 图标统一入口（任务书 §24：只此一家） |
| clsx + tailwind-merge | 最新 | `cn()` 的依赖 |

---

## 2. Design Tokens

### 分层：两套变量，各有职责

界面上同时存在两套 CSS 变量，这不是重复：

```text
--background / --foreground / --primary …   shadcn + React Bits 的「接口名」
                                            由 globals.css 定义。registry 组件源码直接引用，
                                            **不能改名**——改了整个组件库编译失败

--nx-*                                     本项目的「语义令牌」
                                            业务代码只认这一套
```

中间由 `tokens.css` 末尾的**桥接层**连接：

```css
:root {
  --nx-color-text-secondary: ...;
  --background: var(--nx-color-background);
  --muted-foreground: var(--nx-color-text-secondary);
  /* ... */
}
```

**换主题时只改桥接那一层**，业务代码与 registry 组件都不动。

### 为什么用 `@theme inline` 而不是 `@theme`

Tailwind 4 的 `@theme` 要求值是**静态字面量**（它要在构建期算出工具类）。
我们要让工具类引用 CSS 变量（`bg-background` → `var(--background)`），
所以必须用 `@theme inline`——它让 Tailwind 把变量**透传**到工具类里，
值在运行时才解析。这是 shadcn + Tailwind 4 的标准接法。

### 8 组令牌

| 组 | 变量 |
| --- | --- |
| 色彩 | 表面 5（background / surface / surface-muted / border / border-strong）<br>文字 4（text / text-secondary / text-muted / text-inverse）<br>品牌 5（primary / primary-hover / primary-container / on-primary）<br>状态 6 × 2（success / warning / danger / info，各含 container） |
| 间距 | `--nx-space-1` … `16`（8 档）+ 3 个语义别名（component / panel / page） |
| 圆角 | sm / md / lg / xl（4 档） |
| 阴影 | sm / md / lg（3 档法：收边 / 浮起 / 浮层） |
| 字体 | `--nx-font-sans`（**不下载字体**）/ `--nx-font-mono`；字号 7 档；行高 3 档；字重 3 档 |
| 动效 | ease-out / ease-soft；dur-fast / dur-base；**只允许 transform 与 opacity** |
| z-index | 7 档，每档留 10 的空位（Vue 版**完全没有这一层**） |
| 焦点环 | `--nx-focus-ring` |

**刻意不建的**：状态色的"浅一档"变体以外的花哨值、每种宽度的 token。
一旦出现只用一次的 token，它就不是设计系统而是垃圾场。

### 命名空间纪律

新增令牌一律 `--nx-` 前缀。理由：registry 组件源码里写的是 `bg-background`、
`text-muted-foreground` 这类字符串，**与我们的令牌名撞车会让主题改不动**。

---

## 3. UI 原语（`components/ui/`）

12 个。**全部不依赖 shadcn CLI，也不依赖 Base UI / Radix**——手写换来的是零额外运行时依赖。

| 组件 | 关键设计 |
| --- | --- |
| `button.tsx` | 五变体 × 三尺寸；**loading 时尺寸不变**（spinner 绝对定位叠在内容上，children 用 `invisible` 继续占位） |
| `Spinner.tsx` | 用 `border` 画圈而非 SVG——可直接 `currentColor` 跟随所在文字色 |
| `card.tsx` | **只管容器不管内容**。`interactive` 只加 hover 反馈，具体行为由业务卡片组合——否则三个继承它的卡片会各自带一份 |
| `form.tsx` | `Field` + `Input` / `Textarea` / `Select`。**`useId` 把 label / 控件 / 描述 / 错误四者串起来**，调用方不可能漏掉 `aria-describedby` |
| `Overlay.tsx` | 浮层底层：Portal、ESC、**焦点陷阱**、焦点归位、body 滚动锁、遮罩点击策略 |
| `dialog.tsx` | 居中模态。承载"必须拍板"的操作 |
| `sheet.tsx` | 侧边抽屉。承载"与主内容并存"的编辑 |
| `tabs.tsx` | `role="tablist"` + `aria-selected` + `aria-controls`；键盘 ←/→/Home/End |
| `tooltip.tsx` | 纯 CSS 定位（跟随光标需要持续重渲染，本项目不需要）。**明确不承担字段说明的职责** |
| `badge.tsx` | 只做容器与语义色，**不内置任何状态词**——那是 `StatusBadge` 的事 |
| `skeleton.tsx` | `Skeleton` / `SkeletonText` / `SkeletonRow` |

### 两个值得单独说的实现选择

**① Select 用原生 `<select>`，不用自绘下拉。**
原生在移动端会调起系统选择器（体验远好于自绘），键盘与读屏完全可用，零 JS。
代价是样式受限——对一个内部工具来说，这个交换是划算的。

**② Sheet 的宽度用内联 `style`，不拼 Tailwind 任意值类名。**
`` `max-w-[${width}]` `` 这种运行时拼出来的类名，**Tailwind 扫描器看不到完整字面量**，
生产构建会把它裁掉——表现为"宽度 mysteriously 不生效"且**不报错**。
这类 bug 极难从现象反推原因。

---

## 4. 通用组件（`components/common/`）

6 个。每一个都对应 Vue 侧（Phase C）踩过或审计出的具体问题。

### StateEmpty

对应 Phase B §13.2 的规范：**一句状态 + 一个下一步动作**。
没给 `action` 时在**开发模式**发出警告——设计规范由组件 API 兜住，不靠每个使用点自觉。

### StateError

`role="alert"`：读屏软件会**立刻**播报。错误状态不该安静地待在 DOM 里等人去找。

**与 StateEmpty 的界线只有一条：服务端有没有说成功。** 二者文案不允许互相越界。
Vue 侧违反过这条——加载失败只有一个 3 秒 toast，toast 消失后面板落到空态，
于是"加载失败"最终呈现为"这里没有数据"。

`retryable={false}` 时不渲染重试按钮（用于 404 / 403 这类"重试也没用"）。

### StateLoading

四种粒度：`page` / `section` / `list`（骨架屏）/ `inline`。
Vue 侧的教训是"哪些等待有反馈、哪些没有"完全看实现者心情；有了粒度就变成显式声明。

### StatusBadge + status.ts

**状态词表只有一份**（`components/common/status.ts`）。
Vue 侧改造前有**两份**（`KnowledgeBaseListView:199` 与 `AgentsView:82`），
同一个状态在两个页面可能显示不同。

两个设计细节：
- **认不出的值回落到 `failed` 而非 `done`。** 未知状态**不能**被显示成"已完成"——
  那会让用户以为成功了，而它可能根本没跑完。
- **`data-testid` 由调用方传**（任务书 §28），组件不硬编码。同一个组件在不同页面
  代表不同业务对象，写死的 testid 会让 E2E 分不清是"哪个知识库的状态"还是"哪个 Agent 的状态"。

### ConfirmDialog

不复用 `window.confirm`，三个实际理由：样式不可控、无法表达影响（"将删除 12 篇文档与 340 个切片"）、
**无法表达进行中**（请求在飞时弹窗已消失，用户可以再点一次，于是发了两个删除请求）。

`danger` 时强制要求 `description`，否则开发模式警告——只问"确定吗"等于没确认。

### PageHeader

`back` 是**插槽**而不是 `onBack` prop：三个页面的返回行为不一样
（详情回列表 / Sheet 关自己 / 导入后留原地），组件里写死 `navigate(-1)` 会让其中两个用错方式。

---

## 5. 可访问性

| 项 | 做法 |
| --- | --- |
| 焦点可见 | 全局 `:focus-visible` 用 `--nx-focus-ring`。**不用 `:focus`**——鼠标点击也显示会很脏 |
| 焦点陷阱 | `Overlay` 内 Tab 首尾循环；焦点在浮层外时拉回第一个 |
| 焦点归位 | 关闭浮层时把焦点还给触发元素（实测通过） |
| ESC | 浮层打开时 ESC 关闭 |
| 表单关联 | `Field` 用 `useId` 串起 label / `aria-describedby` / `aria-required` / `aria-invalid` |
| 错误播报 | `StateError` 用 `role="alert"` |
| 页签 | `role="tablist"` + `aria-selected` + `aria-controls`；键盘 ←/→/Home/End |
| 状态徽标 | 状态在 `data-value`（无中文无大写），E2E 定位不依赖文案 |
| 减少动效 | 全局 `prefers-reduced-motion` |
| 加载态 | `aria-busy` |
| 骨架屏 | `aria-hidden`（它是装饰，读屏不该读一串灰块） |

**浏览器实测通过**：焦点陷阱、Tab 循环、ESC 关闭、焦点归位、Tabs 键盘切换。

---

## 6. React Bits —— 本阶段**刻意未集成**

这不是"装不上"，是一组查证后的判断。

### 查证到的事实

| 事实 | 验证方式 |
| --- | --- |
| `https://reactbits.dev/r/{name}.json` 对已知 slug 返回 **HTML 而非 JSON** | `curl` 取 `silk-waves-tw` / `mono-waves-tw` / `dots-background-tw` / `animated-list-tw`，全部 200 + `text/html` |
| shadcn CLI 的 registry 装不了 | `npx shadcn add button` → workspace 错误（§7） |
| `raw.githubusercontent.com` **不可用**（000） | `curl` 超时 |
| `api.github.com` **可用** | 取到 `src/content/TextAnimations/BlurText/BlurText.jsx`（2891 B） |
| 免费组件库有 59 个背景 + 40 个动画 | GitHub API 目录列举 |
| `BlurText` 依赖 `motion/react` | 读源码的 import |

**注意**：我第一次探测时只看 `http_code` 就判"registry 可用"——
那正是任务书 §57 禁止的"只看状态码"，实际返回的是 5671 字节的 HTML。已用真实 slug 复测纠正。

### 不集成的三个理由

1. **59 个背景组件几乎全是全屏 canvas 动画**（Aurora / Galaxy / Beams / Ferrofluid…）。
   它们与任务书 §53「避免大面积渐变、粒子、发光、过多动画」和
   §8「长期使用」的产品定位**直接冲突**。任务书 §22 明确写了：
   "如果某个组件体积很大 / 动画过重 / 依赖很多 / 对产品没有价值，不要使用"。
2. **唯一轻量的 `BlurText` 依赖 `motion`**（Framer Motion 后继，约 30+ kB gzip ~12 kB）。
   为 R1 引入一整个动画库**不符合本阶段目标**——R1 是"把材料备好"，不是"开始装修"。
3. **Pro registry（Blocks / Application UI）需付费 license**，未提供，本阶段不涉及。

### 集成路径是否已验证

部分验证：源码能取到（GitHub API），Vite 能处理 `.jsx`（R0 已验证构建链），
唯一障碍是 `motion` 依赖。**因此这不是"路径不通"，而是"选型未到时机"。**

### R2 的建议路径

1. 若需要动画基础，**直接装 `motion`**，不用绕道 React Bits——
   后续 UI 微交互（列表项进入、对话框展开）本来也会用到它。
2. React Bits 的**微交互类**（`ClickSpark` / `AnimatedContent` / `FadeContent`）
   比背景类更契合本产品；背景类建议**一律不用**。
3. 若拿到 Pro license，registry 需按官方文档在 `components.json` 里注册
   `@reactbits-starter` / `@reactbits-pro` 两个命名空间（需 `Authorization` 头）。

---

## 7. 已知问题与绕过

### shadcn CLI 的 `add` 不可用

```
Could not load the workspace config in D:\develop\Prisma\web
```

它把同级的 `web/`（Vue 项目）当成了 workspace 成员。已确认 `web/components.json` **不存在**
（没有被写入）。降级到 4.20.0 无效。

**绕过**：本阶段的组件全部手写。不需要 CLI。

### `baseUrl` 已被 TS 6 废弃

shadcn CLI 有时会往 `tsconfig.app.json` 写 `baseUrl`，加回来会让 `tsc` 报 TS5101（构建失败）。
`paths` 现在可以不带 `baseUrl` 直接用相对路径。

### 组件尚未进生产包

见 §0。R2 起被页面引用后自然进包。

---

## 8. CSS 架构

```text
styles/globals.css   Tailwind 入口 + @theme inline 映射 + 基础重置
styles/tokens.css    设计令牌（--nx-*）+ shadcn 接口名桥接
styles/markdown.css  Markdown 正文排版
```

**约束（任务书 §26）**：不建 `PageA.module.css` / `PageB.module.css`。
业务页面阶段再决定是否需要局部样式。现在就把样式散进组件里，
等于把"哪些规则是全局的、哪些是局部的"这个问题提前变成一团浆糊。

`markdown.css` 沿用 Vue 侧的结论：**只放两边共有**的部分。
Vue 侧那套规则在两个视图各存一份且**已经分叉**（详情页的 `h1/h2/h3` 多了
`scroll-margin-top` 用于大纲跳转、引用左边框色不同、还多了表格与图片样式），
强行按成一样会毁掉大纲跳转。

---

## 9. 性能

| 阶段 | JS | CSS |
| --- | --- | --- |
| R0 | 315.94 kB（gzip 99.92） | 10.96 kB（gzip 3.24） |
| R1 | 339.9 kB | 25.9 kB |

**+24 kB JS / +15 kB CSS 的来源要分清**：

- 增量主要是 `Overlay` / `Tabs` / `Tooltip` 的实现与 tokens 的 `@theme inline` 映射；
- **但组件其实没进生产包**（`state-empty` 等匹配数为 0），
  所以这 24 kB 主要是路由/布局的引用图变化与 source map 差异，**不是"组件全打包进去了"**。
- 真正的体积影响要等 R2 页面引用后重新测量。**本阶段不据此下"性能达标"的结论。**

未引入的重量级依赖：没有 `motion`、没有 `three.js`、没有图表库、没有日期库。
`lucide-react` 已装但当前按需使用（未 import 任何图标）。

---

## 10. 组件使用规则

1. **通用组件不碰 store / API / router。** 业务逻辑在 View / Store / Hook。
2. **`data-testid` 由调用方传**，组件内部不硬编码业务 testid。
3. **状态词只在 `status.ts` 里。** 需要新状态时改那一个文件，不在页面里 `switch`。
4. **shadcn 组件是"我们的源码"**（本阶段是手写的），允许改，但要保持基础语义——
   改到调用方要靠内部实现才能用时，就该抽成新组件而不是改原语。
5. **令牌只增不删。** 删一个令牌前先 `grep` 全仓确认没有页面在用。
6. **组合优先于配置。** 给组件加 7 个布尔 props 让它切换形态，代价是以后每加一种形态
   都多一个分支；组合（`<Card><CardHeader/></Card>`）没有这个代价。

---

## 11. 验收

| 项 | 结果 |
| --- | --- |
| `npm run build` | ✅ |
| 12 个 UI 原语 + 6 个通用组件渲染 | ✅ 浏览器实测 |
| Button loading 尺寸不变 | ✅ 同按钮 74.0px → 74.0px，**差 0.00px** |
| Empty ≠ Error | ✅ 同一卡片内互斥 |
| 焦点陷阱 + Tab 循环 | ✅ 3 个可聚焦元素，末位 Tab 回到首位 |
| ESC 关闭 + 焦点归位 | ✅ |
| Sheet 点遮罩不误关（表单） | ✅ |
| Tabs 键盘 ←/→ | ✅ |
| 响应式 1920×1080 / 1366×768 / 1024×768 | ✅ 横向溢出均 0px |
| console error / page error | ✅ 均 0 |
| 业务页面 | ✅ **一个都没做**（R1 硬约束） |
| `web/`（Vue）与后端 | ✅ **未动** |

**Showcase 只在开发模式注册**（`import.meta.env.DEV` 守卫），
生产构建**不含**这条路由（实测 `dist` 中 `showcase` 匹配数为 0）。
R1 之后可整体删除 `src/dev/`。
