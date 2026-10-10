# 存档（docs/archive）

这里放的是**阶段性过程产物**，不是产品文档。保留原因：它们是"当时为什么这么决策"的证据链，
出问题时能回溯；但它们不代表当前状态，**不要当成规范读**。

| 目录 | 内容 | 为什么归档 |
|---|---|---|
| `reports/` | 20 份阶段性报告（final-*-report、phase0-testid-report、platform-phase3-baseline、productization-gap-analysis、frontend-refactor-progress、react-*-report 等） | 是一次性验收/迁移过程的记录；当前状态以 `docs/` 顶层的核心文档为准 |
| `screenshots/` | 旧版界面截图（Vue 阶段） | 界面已由 `frontend/` 取代；当前证据图在 `docs/prototypes/shots/` |
| `mockups/` | 早期设计稿 | 同上 |
| `web-vue/` | 原 Vue 3 前端（`nexus-web`，38 个源文件） | 已由 `frontend/`（React）取代；仅保留源码，`node_modules` 已删 |
| `web-react/` | Vue→React 迁移中间态（77 个源文件） | 迁移未完成即被 `frontend/` 取代；仅保留源码，`node_modules` 已删 |

**当前有效的文档**：`docs/` 顶层的 architecture / api / database / decisions / deployment / security / agent / rag / roadmap / project-tutorial / future-improvements。
（`frontend-design-system.md`、`frontend-information-architecture.md` 描述的是 **Vue 3 时代**的前端，已在文件顶部标注为历史；当前前端是 React 工程 `frontend/`。）

**当前的界面证据**：`docs/prototypes/shots/`（由 `frontend/` 的 `npm run verify:shots` 与截图脚本生成）。
