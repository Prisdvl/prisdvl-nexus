/**
 * E2E 公共地址配置（`verify-*.mjs` 共用）。
 *
 * 任务书 §43 要求把 `API_BASE` 统一到 `http://localhost:8088`
 * （`deploy/docker-compose.yml` 里**唯一对外暴露**的网关），并收进一个公共模块。
 *
 * 此前三个脚本各自解释 `API_BASE`：有的期望它**含** `/api`、有的期望**不含**、
 * 默认端口一个 8088 一个 8080。于是同一个覆盖值在一个脚本里对、在另一个里必然
 * `fetch failed` —— 而那个报错完全指向不了真正的原因。
 *
 * 约定（唯一一种解释）：
 * - `API_BASE` / `WEB_BASE` **永远指向网关根**，默认 `http://localhost:8088`；
 * - 路径里的 `/api` 由 {@link apiUrl} 拼；
 * - 本地 vite 开发用 `API_BASE=http://localhost:8080` 覆盖。
 *
 * `lib/demo-session.mjs` 从这里重新导出这三个符号，所以既有脚本无需改动。
 */

/** 网关根（容器入口）。不要在这里带 `/api`。 */
export const API_ROOT = process.env.API_BASE ?? 'http://localhost:8088'

/** 前端根（与 API 同源）。 */
export const WEB_ROOT = process.env.WEB_BASE ?? 'http://localhost:8088'

/** 由相对路径拼出完整的 API 地址（自动补 `/api` 前缀）。 */
export function apiUrl(path) {
  return `${API_ROOT}/api${path.startsWith('/') ? path : `/${path}`}`
}
