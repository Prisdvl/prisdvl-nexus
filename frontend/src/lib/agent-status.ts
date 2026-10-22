/**
 * 后端 Agent 状态 → 界面两态（草稿 / 已发布）。
 *
 * 为什么单独成文件：这块逻辑曾经漏在 `toAgentItem` 里——后端明明返回 `DRAFT`，
 * 映射时没带 `status`，卡片上的「草稿」徽标与「发布」按钮就永远不渲染，
 * 用户只会在建会话时撞 5008（「还是草稿，先发布」），却看不到「发布」在哪。
 * 抽成纯函数就能被 node:test 直接盯住，不必起后端和浏览器。
 */
export type AgentStatus = 'DRAFT' | 'PUBLISHED'

/**
 * 只有后端明确说 DRAFT 才算草稿；其它（含 null / 未知枚举）按已发布显示，
 * 宁可少显示一个徽标，也不要给已发布的 Agent 挂上「发布」按钮。
 */
export function agentStatus(raw: string | null | undefined): AgentStatus {
  return (raw ?? '').trim().toUpperCase() === 'DRAFT' ? 'DRAFT' : 'PUBLISHED'
}
