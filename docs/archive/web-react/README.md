# web-react（React 前端）

`prisdvl-nexus` 的 **React 前端**。与 `web/`（Vue）并存，指向同一套后端 API。

- 任务书：`docs/frontend-architecture-audit.md`（Phase A）→
  `docs/frontend-information-architecture.md`（Phase B，设计基线）→
  `docs/frontend-refactor-progress.md`（逐阶段进度）
- 当前阶段：**R0（项目骨架）已完成**，R1 起见进度记录

## 为什么不覆盖 `web/`

`web/` 是**已经能跑的生产前端**。迁移过程必须保证旧版可回退、新版可验证，
所以两条前端并存：

```text
backend
   ├── web        Vue（生产，:8088）
   └── web-react  React（开发，:5174）
```

React 通过验收后才切换 nginx 入口；`web/` 是否删除是那之后的事。

## 技术栈

| 能力 | 选型 | 版本 |
| --- | --- | --- |
| 框架 | React | 19.3.0 |
| 语言 | TypeScript | 6.0.3（`strict: true`） |
| 构建 | Vite | 8.3.2 |
| 路由 | React Router（**hash 模式**） | 8.4.0 |
| 样式 | Tailwind CSS（v4 CSS-first） | 4.3.3 |
| 基础组件 | shadcn/ui（`base-nova` 预设，Base UI） | CLI 4.21.1 |
| 图标 | lucide-react | 1.51.0 |
| 状态 | Zustand | 5.0.15（**R4 才实际使用**） |
| E2E | playwright-core（复用系统 Edge，不下载浏览器） | 1.63.0 |

> **版本不是抄来的，是逐个核对过的。** 两个刻意的选择：
> - **TypeScript 用 6.0.3 而不是 7.0.2。** TS 7 是 Go 重写的新编译器，shadcn CLI 与生态未验证。
>   实测 `baseUrl` 选项在 TS 6 就已废弃（TS 7 将停止支持）——这条路径的代价已经能看见了。
> - **从 `react-router` 导入，不再用 `react-router-dom`。** v7 起两个包已合并，
>   装 `react-router-dom` 反而多一层过时的 re-export。

## 命令

```bash
npm run dev      # 开发服务器 :5174，/api 与 /mcp 代理到 localhost:8080
npm run build    # tsc -b && vite build → dist/
npm run preview  # 预览构建产物
npm run lint     # oxlint
```

构建产物是 `dist/`，与 Vue 版一致——将来写 Dockerfile（Phase R12）时，
nginx 侧的静态资源路径与缓存策略不必改。

## 目录

```text
src/
├── app/router/     路由表（hash，5 条 + 404）
├── layouts/        WorkspaceLayout（外壳）
├── pages/          5 个页面（R5~R9 逐步实现）
├── components/     ui / common / layout / knowledge / chat / agent
├── stores/         authStore / knowledgeStore / agentStore（R4）
├── hooks/          useAsyncState / useImportTask / useChatStream …（R4）
├── api/            client + 各域 + types/（R3）
├── lib/            cn 等工具
└── styles/         globals.css / tokens.css / markdown.css
```

R0 阶段 `stores/`、`hooks/`、`api/`、`components/*` 是**空目录**——
它们在阶段表里各自有归属的 Phase，提前放占位文件只会让"这个目录为什么是空的"
变成一个需要回答的问题。

## 与 Vue 版共用的契约

| 契约 | 说明 |
| --- | --- |
| **testid** | 沿用 Phase 0 的命名（`app-sidebar` / `nav-knowledge` / `nav-chat` / `nav-agents` …）。E2E 不允许退回 `has-text("中文")` |
| **后端 API** | 路径、请求体、响应包装 `{code,message,data}` 全部不变。SSE 协议不变 |
| **路由** | 5 条路径不变；新增 404 兜底（Vue 版没有，属纯增量修复） |

## 已知的坑

- **`baseUrl` 已被 TS 6 废弃。** shadcn CLI 有时会往 `tsconfig` 加它，加回来要删，
  否则 `tsc` 直接报 TS5101（构建失败）。
- **shadcn CLI 在本仓库报 workspace 错误**：`Could not load the workspace config in
  D:\develop\Prisma\web`——它把同级的 `web/`（Vue）当成了 workspace 成员。
  `components.json` 已正确生成，但 `npx shadcn add` 暂时装不了组件。
  绕过方式与替代方案见进度记录的 R0 条目。
- **React Bits registry 可用性待验证。** `https://reactbits.dev/r/{name}.json` 已登记进
  `components.json`，但 2026-10-04 实测该地址对已知 slug 返回 **HTML 而非 JSON**。
  R1 真正安装组件时必须先确认；官方另有 "Manual copy & paste" 路径可作退路。
