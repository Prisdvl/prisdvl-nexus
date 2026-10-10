import type { ReactNode } from 'react'
import { useLocation } from 'react-router'

/**
 * 路由切换过渡。
 *
 * 刻意**只用 opacity**：如果加 transform，祖先元素会建立 containing block，
 * 内部 `position: fixed` 的吸顶导航/浮层会改成相对它定位——吸顶就废了。
 * key 用 pathname + search：同一路由换 query（例如 ?doc=）也要重放动画。
 */
export default function RouteFade({ children }: { children: ReactNode }) {
  const { pathname, search } = useLocation()
  return (
    <div className="route-fade" key={`${pathname}${search}`}>
      {children}
    </div>
  )
}
