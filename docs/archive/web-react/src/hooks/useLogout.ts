import { useAuthStore } from '@/stores/authStore'
import { useAgentStore } from '@/stores/agentStore'
import { useKnowledgeStore } from '@/stores/knowledgeStore'
import { clearAuthStorage } from '@/lib/storage'

/**
 * 真正的登出（R3 任务书 §11）。
 *
 * ## 完整生命周期
 * ```text
 * logout
 *   ↓ 清理 localStorage（token + lastUsername）
 *   ↓ 清空 authStore（token / currentUser / status→guest）
 *   ↓ 清空与用户强绑定的其它 store（当前只有 knowledgeStore）
 *   ↓ 跳转 /login
 * ```
 *
 * ## 为什么要写成本模块的函数，而不是一个 hook
 *
 * 它的调用方**不一定是组件**：401 被拦截时（`authStore.handleUnauthorized`）
 * 也要做同样的清理。写成一个普通函数，store 与 hook 都能调；
 * 写成 hook 就只有组件能用了。
 *
 * ## 为什么第 3 步不能省
 *
 * 漏掉 `knowledgeStore.reset()` 的话：用户 A 退出，用户 B 登录，
 * 界面会短暂显示 A 的知识库列表——因为 `loaded` 还是 true，
 * 再拉取会被 `if (loaded && !force) return` 挡掉。
 * **这是"数据串号"，比任何样式 bug 都严重。**
 */
export function performLogout(): void {
  // 1) 浏览器存储
  clearAuthStorage()
  // 2) 认证状态
  useAuthStore.getState().handleUnauthorized()
  // 3) 与用户强绑定的一切
  useKnowledgeStore.getState().reset()
  // R4 补记：agentStore 是第三个必须清的。它比知识库更容易被忽略——
  // 因为 Agent 常被当成"全局配置"而不是"用户数据"，
  // 但后端的 Agent 是带 id 的**用户资产**（GET /api/agents 按登录态过滤）。
  useAgentStore.getState().reset()
  // 4) 跳转由调用方决定（hook / 组件里用 navigate）——
  //    这里刻意不引 router，因为 store 层不该知道路由。
  //    authStore.handleUnauthorized 内部已有幂等保护，重复调用安全。
}
