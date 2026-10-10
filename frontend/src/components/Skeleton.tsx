import type { ReactNode } from 'react'

/** 骨架屏：内容未就绪时占位，避免"空白 + 突然跳出" */
export function Skeleton({ w = '100%', h = 14, radius = 8, style }: { w?: number | string; h?: number; radius?: number; style?: React.CSSProperties }) {
  return <span className="sk" style={{ width: w, height: h, borderRadius: radius, ...style }} aria-hidden="true" />
}

/** 常见组合：列表行骨架 */
export function SkeletonRows({ rows = 4 }: { rows?: number }) {
  return (
    <div className="sk-rows" aria-hidden="true">
      {Array.from({ length: rows }, (_, i) => (
        <div className="sk-row" key={i}>
          <Skeleton w={36} h={36} radius={10} />
          <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 6 }}>
            <Skeleton w={`${58 - i * 6}%`} h={13} />
            <Skeleton w={`${34 - i * 3}%`} h={11} />
          </div>
        </div>
      ))}
    </div>
  )
}

/** 空状态：图标 + 说明 + 可选动作 */
export function StateEmpty({
  icon,
  title,
  desc,
  action,
}: {
  icon?: ReactNode
  title: string
  desc?: string
  action?: ReactNode
}) {
  return (
    <div className="state-empty">
      {icon && <span className="se-ic" aria-hidden="true">{icon}</span>}
      <p className="se-title">{title}</p>
      {desc && <p className="se-desc">{desc}</p>}
      {action && <div className="se-action">{action}</div>}
    </div>
  )
}