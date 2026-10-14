import { useEffect, useRef, useState, type Ref } from 'react'

/** 单条横线占位（与 CSS min-height 一致）；压矮以便更密 */
const ITEM_H = 12
/** 行距：默认就紧，提问多时再收到下限 */
const GAP_MAX = 3
const GAP_MIN = 1

export interface RailItem {
  /** 消息 id（仅作辅助；高亮与跳转一律用 index） */
  id: string
  /** 提问序号（从 1 开始） */
  index: number
}

interface Props {
  items: RailItem[]
  /** 当前阅读提问序号（1-based）；null 表示尚未同步 */
  activeIndex: number | null
  /** 面板是否打开：驱动轨道「靠近」态与横线入场动画 */
  open: boolean
  /** 整条轨道的 DOM（浮层锚点：靠近轨道才出面板，位置固定） */
  railRef?: Ref<HTMLElement | null>
  onPanelEnter: () => void
  onPanelLeave: () => void
  onJump: (index: number) => void
}

/**
 * 提问横线轨道（右贴边）。
 *
 * 只对应**我的提问**：一条横线 = 一个问题。
 * 面板显隐由整条轨道的靠近触发，不按单根横线开关。
 */
export default function MessageRail({
  items,
  activeIndex,
  open,
  railRef,
  onPanelEnter,
  onPanelLeave,
  onJump,
}: Props) {
  const navRef = useRef<HTMLElement | null>(null)
  const [gap, setGap] = useState(GAP_MAX)

  const setNavRef = (el: HTMLElement | null) => {
    navRef.current = el
    if (typeof railRef === 'function') railRef(el)
    else if (railRef && 'current' in railRef) (railRef as { current: HTMLElement | null }).current = el
  }

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
    <nav
      className={`msg-nav${open ? ' is-open' : ''}`}
      data-open={open ? '' : undefined}
      aria-label="提问导航"
      ref={setNavRef}
      onMouseEnter={onPanelEnter}
      onMouseLeave={onPanelLeave}
      onFocusCapture={onPanelEnter}
      /*
       * 不要在 blur 时关面板：点击横线会 blur，relatedTarget 常为 null，
       * 会误触 leave；指针其实还在轨道上，关完后 mouseenter 不再触发。
       * 显隐只认指针进出（mouseenter / mouseleave）。
       */
    >
      <ol
        className="msg-nav__list"
        style={{
          '--rail-gap': `${gap}px`,
          '--rail-n': items.length,
        } as React.CSSProperties}
      >
        {items.map((item, i) => {
          const isActive = item.index === activeIndex
          return (
            <li key={`${item.id}-${item.index}`} style={{ '--i': i } as React.CSSProperties}>
              <button
                type="button"
                className={`msg-nav__item${isActive ? ' is-active' : ''}`}
                data-active={isActive ? '' : undefined}
                aria-label={`第 ${item.index} 个提问`}
                aria-current={isActive ? 'true' : undefined}
                tabIndex={isActive ? 0 : -1}
                /* 禁止点击抢焦点：否则 :focus-visible 会留下主色描边，看起来像多根高亮 */
                onMouseDown={(e) => e.preventDefault()}
                onClick={(e) => {
                  onJump(item.index)
                  ;(e.currentTarget as HTMLButtonElement).blur()
                }}
              >
                <span className="msg-nav__line" aria-hidden="true" />
              </button>
            </li>
          )
        })}
      </ol>
    </nav>
  )
}
