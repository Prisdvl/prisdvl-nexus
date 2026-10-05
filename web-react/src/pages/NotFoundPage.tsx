import type { ReactElement } from 'react'
// React Router v7 起 react-router-dom 已合并进 react-router，
// 直接从 'react-router' 导入；装 'react-router-dom' 反而会拿到过时的 re-export 层。
import { Link } from 'react-router'

/**
 * 404（R0 新增）。
 *
 * Vue 版的 `router/index.ts` 里**没有**兜底记录，未匹配的 hash 只会渲染空白
 * （Phase A 审计 §2 记为 P2 缺口：任务书 §四十二 要求"非法路由 → 404 / home"）。
 *
 * React 版第一版就补上，因为这是一处**纯增量**的修复：不新增页面、不改产品结构，
 * 只是让"地址打错"有话可说。
 */
export function NotFoundPage(): ReactElement {
  return (
    <section
      className="mx-auto flex max-w-2xl flex-col items-start gap-4 p-8"
      data-testid="not-found"
    >
      <p className="text-muted-foreground text-xs tracking-wide uppercase">404</p>
      <h1 className="text-2xl font-semibold">这个地址不存在</h1>
      <p className="text-muted-foreground text-sm">
        可能是链接输错了，或者这个页面已经被移除。
      </p>
      <Link
        to="/"
        className="text-primary text-sm underline underline-offset-4"
        data-testid="not-found-home-link"
      >
        回到知识库
      </Link>
    </section>
  )
}
