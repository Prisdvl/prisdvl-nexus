/**
 * 热力图的数据转换层。
 *
 * 现状（重要，别把它当成成品的数据契约）：后端**没有**「每天消耗量」的接口。
 * 目前能拿到的真实时间序列只有 `GET /api/stats/summary` 的 `heat`（近 30 天，
 * 口径是**每日会话消息条数**，不是 token 消耗）。所以：
 * - 有真实值的日子用真实条数；
 * - 没有的日子补 0，而不是随手编数据 —— 图上「空」就是真的没有记录；
 * - mock/离线模式用确定性伪随机生成，保证同一会话里刷新不变。
 *
 * 等后端补上按天聚合（数据已在 chat_message 里，`total_tokens` + `created_at`），
 * 只需要把 `summary` 换成新接口的返回，本文件之外不用改。
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
 * 生成 365 天演示数据：工作日为主、周末稀疏、带缓慢的季节起伏，
 * 让热力图看起来像真实使用轨迹而不是均匀噪声。
 */
export function mockUsageDays(count = 365, end = new Date()): HeatmapDay[] {
  return fillDateRange(end, count).map((date, index) => {
    const weekday = new Date(date).getDay()
    const weekend = weekday === 0 || weekday === 6
    const r = seeded(date)
    /* 越靠近今天越活跃（最近三个月权重高），模拟「刚开始用 + 逐步养成」 */
    const recency = 0.35 + 0.65 * (index / Math.max(1, count - 1))
    const base = weekend ? 6 : 22
    const value = Math.round(base * recency * (0.35 + r * 1.5))
    return { date, value: r < 0.12 ? 0 : value }
  })
}
