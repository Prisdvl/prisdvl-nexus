import { useEffect, useRef, useState } from 'react'

/** 单条横线的占位高度（与 CSS 里 .msg-nav__item 的 height 保持一致） */
const ITEM_H = 10
/** 行距上下限：提问多时自动收紧，保证所有横线都看得见 */
const GAP_MAX = 7
const GAP_MIN = 2

export interface RailItem {
  /** 消息 id（用于跳转与 active 判定）。字符串雪花 ID，不能转Number */
  id: string
  /** 提问序号（从 1 开始，展示在预览里） */
  index: number
}

interface Props {
  items: RailItem[]
  activeId: string | null
  onHover: (item: RailItem, el: HTMLElement | null) => void
  onJump: (id: string) => void
}

/**
 * 提问横线轨道（右贴边）。
 *
 * 只对应**我的提问**：一条横线 = 一个问题。
 * （助手回答不占横线，否则条数翻倍、噪音太大，也不是"跳转锚点"的意义所在。）
 * 低存在感：默认只是一条很短的 muted 细线，hover / 当前阅读位置才变长并过渡到强调色。
 */
export default function MessageRail({ items, activeId, onHover, onJump }: Props) {
  /* 用 ref 记录每个横线的 DOM，hover 时作为浮层定位基准 */
  const itemRefs = useRef<(HTMLButtonElement | null)[]>([])
  const navRef = useRef<HTMLElement>(null)
  const [gap, setGap] = useState(GAP_MAX)

  /*
   * 提问多的时候自动压缩行距，让全部横线都装进一屏；
   * 实在装不下（行距已到下限）就交给 CSS 的 overflow-y 滚动。
   */
  useEffect(() => {
    const nav = navRef.current
    if (!nav) return
    const compute = () => {
      const avail = nav.clientHeight
      const n = items.length
      if (avail <= 0 || n < 2) return
      const g = Math.floor((avail - n * ITEM_H) / (n - 1))
      setGap(Math.max(GAP_MIN, Math.min(GAP_MAX, g)))
    }
    compute()
    const ro = new ResizeObserver(compute)
    ro.observe(nav)
    return () => ro.disconnect()
  }, [items.length])

  return (
    <nav className="msg-nav" aria-label="提问导航" ref={navRef}>
      <ol className="msg-nav__list" style={{ '--rail-gap': `${gap}px` } as React.CSSProperties}>
        {items.map((item, i) => (
          <li key={item.id}>
            <button
              type="button"
              ref={(el) => { itemRefs.current[i] = el }}
              className="msg-nav__item"
              data-active={item.id === activeId ? '' : undefined}
              aria-label={`第 ${item.index} 个提问`}
              aria-current={item.id === activeId ? 'true' : undefined}
              onMouseEnter={(e) => onHover(item, e.currentTarget)}
              onFocus={(e) => onHover(item, e.currentTarget)}
              onMouseLeave={() => onHover(item, null)}
              onBlur={() => onHover(item, null)}
              onClick={() => onJump(item.id)}
            >
              <span className="msg-nav__line" aria-hidden="true" />
            </button>
          </li>
        ))}
      </ol>
    </nav>
  )
}
