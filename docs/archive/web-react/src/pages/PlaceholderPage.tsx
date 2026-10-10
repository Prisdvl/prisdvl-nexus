import type { ReactElement } from 'react'

/**
 * R0 阶段的页面占位。
 *
 * 存在的唯一理由：让路由表在骨架阶段就是**真的**（能点、能跳、刷新不 404），
 * 而不是等 R5 才开始第一次跑通导航。
 *
 * 两条自我约束：
 *
 * 1. **不放业务逻辑。** 任何 `useState` / API 调用都不该出现在这里——
 *    页面一旦有状态，它就开始长成下一个巨型组件，而那时没人拦得住。
 * 2. **明确标注归属 Phase。** 骨架阶段的占位页最容易被遗忘，
 *    写清楚"这个页面归 R 几"，比留个空白 div 强。
 */
interface PlaceholderPageProps {
  /** 面包屑用的产品名，与导航标签一致。 */
  title: string
  /** 路由路径，标出来便于对照任务书 §十一。 */
  path: string
  /** 由哪个 Phase 实现。 */
  phase: string
  /** 这个页面最终负责什么（一句话，来自 Phase B 的页面职责表）。 */
  responsibility: string
}

export function PlaceholderPage({
  title,
  path,
  phase,
  responsibility
}: PlaceholderPageProps): ReactElement {
  return (
    <section className="mx-auto flex max-w-2xl flex-col gap-3 p-8" data-testid="r0-placeholder">
      <p className="text-muted-foreground text-xs tracking-wide uppercase">R0 骨架占位</p>
      <h1 className="text-2xl font-semibold">{title}</h1>
      <dl className="grid grid-cols-[max-content_1fr] gap-x-4 gap-y-1 text-sm">
        <dt className="text-muted-foreground">路由</dt>
        <dd className="font-mono">{path}</dd>
        <dt className="text-muted-foreground">计划实现</dt>
        <dd>Phase {phase}</dd>
        <dt className="text-muted-foreground">页面职责</dt>
        <dd>{responsibility}</dd>
      </dl>
    </section>
  )
}
