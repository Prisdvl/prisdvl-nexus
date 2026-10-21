/**
 * 热力图的数据转换层。
 *
 * 真实数据来自 `GET /api/users/me/stats/daily-tokens`（近 365 天，口径是
 * **`chat_message.total_tokens` 按自然日求和**，即"每天消耗了多少 Token"）。
 * 缺口只有两类，都要显式处理：
 * - 后端只返回有数据的日子（SQL 里 GROUP BY 的结果本身就是稀疏的），这里铺成连续日轴，
 *   没有的日子补 0 —— 图上"空"就是真的没用量，不是随机编出来的；
 * - mock/离线模式用确定性伪随机生成，保证同一会话里刷新不变。
 */
import type { HeatmapDay } from '@/components/ui/activity-heatmap'
import { fillDateRange } from '@/components/ui/activity-heatmap'

export interface UsageTrendPoint {
  /** 后端给的是 YYYY-MM-DD 或带时间的 ISO，这里只取日期部分 */
  day: string
  count: number
}

/** 把后端 heat/trend 点集铺到「截至今天、连续 count 天」的日轴上 */
export function toHeatmapDays(points: UsageTrendPoint[], count = 365, end = new Date()): HeatmapDay[] {
  const byDay = new Map(points.map((p) => [p.day.slice(0, 10), p.count]))
  return fillDateRange(end, count).map((date) => ({ date, value: byDay.get(date) ?? 0 }))
}

/**
 * `/users/me/stats/daily-tokens` 的返回 → 热力图日轴。
 *
 * 单独一层而不是让调用点自己 `map`：字段名从 `tokens` 换到热力图的 `value` 只发生在这里，
 * 以后接口再加字段（比如按模型拆分）也不用去改页面。
 */
export function fromDailyTokens(
  days: { day: string; tokens: number }[],
  count = 365,
  end = new Date(),
): HeatmapDay[] {
  return toHeatmapDays(days.map((d) => ({ day: d.day, count: d.tokens })), count, end)
}

/**
 * 确定性伪随机：LCG + 日期种子。
 * 用途仅限演示/离线模式——同一个日期永远得到同一个值，避免每次渲染热力图都在跳。
 */
function seeded(date: string): number {
  let h = 2166136261
  for (let i = 0; i < date.length; i++) {
    h ^= date.charCodeAt(i)
    h = Math.imul(h, 16777619)
  }
  return ((h >>> 0) % 1000) / 1000
}

/**
 * 生成 365 天演示数据（Token 量级，不是消息条数）：工作日为主、周末稀疏、带缓慢的季节起伏，
 * 让热力图看起来像真实使用轨迹而不是均匀噪声。数值刻意与真实量级同阶（几千 ~ 两万多/天），
 * 否则演示模式下"当日 Token"会显示成个位数，和旁边的额度条完全对不上。
 */
export function mockUsageDays(count = 365, end = new Date()): HeatmapDay[] {
  return fillDateRange(end, count).map((date, index) => {
    const weekday = new Date(date).getDay()
    const weekend = weekday === 0 || weekday === 6
    const r = seeded(date)
    /* 越靠近今天越活跃（最近三个月权重高），模拟「刚开始用 + 逐步养成」 */
    const recency = 0.35 + 0.65 * (index / Math.max(1, count - 1))
    const base = weekend ? 3_200 : 14_000
    const value = Math.round(base * recency * (0.35 + r * 1.5))
    return { date, value: r < 0.12 ? 0 : value }
  })
}
