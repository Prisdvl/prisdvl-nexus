import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { X } from 'lucide-react'
import '@/styles/tour.css'
import { appCopy } from '@/lib/appCopy'
import { useLocale } from '@/lib/locale'
import Loc from '@/components/Loc'

type TourCopy = ReturnType<typeof appCopy>['tour'] & { navKb: string; navChat: string }

/** 首次登录的引导是否已看过 */
const DONE_KEY = 'nx-proto-tour-done'
/** 重播引导的事件名（设置弹层里用） */
export const TOUR_START_EVENT = 'nx-tour:start'
const CARD_W = 300
const GAP = 14
const PAD = 6

type Place = 'right' | 'left' | 'top' | 'bottom'

interface Step {
  id: string
  title: string
  desc: string
  place: Place
  /** 运行时解析目标：找不到就跳过这一步（例如侧栏收起、或不在对话页） */
  find: () => HTMLElement | null
}

const navItem = (label: string) =>
  Array.from(document.querySelectorAll<HTMLElement>('.sb-nav .rubber-segment__item'))
    .find((el) => el.textContent?.trim() === label) ?? null

/**
 * 首次登录的分步引导（coach marks）。
 *
 * 两个刻意的取舍：
 *  1) 用 **Portal + position: fixed** —— 侧栏与对话页的祖先容器都是 overflow:hidden，
 *     就地渲染的高亮框会被裁掉（这个坑在本项目已踩过多次）。
 *  2) 遮罩 **pointer-events: none**（只有说明卡片可点）：引导不该阻断用户操作，
 *     也避免把"正常功能测试"挡在外面。
 */
/**
 * 引导步骤按语言生成：标题 / 描述 / 锚点标签都随 locale 变。
 * 若写成模块级常量，切换语言后卡片仍是旧文案，且 navItem() 会找不到已改名的导航项。
 */
function buildSteps(t: TourCopy): Step[] {
  return [
  {
    id: 'new',
    title: t.s1title,
    desc: t.s1desc,
    place: 'right',
    find: () => document.querySelector<HTMLElement>('.sb-row--quick'),
  },
  {
    id: 'kb',
    title: t.s2title,
    desc: t.s2desc,
    place: 'right',
    find: () => navItem(t.navKb),
  },
  {
    id: 'chat',
    title: t.s3title,
    desc: t.s3desc,
    place: 'right',
    find: () => navItem(t.navChat),
  },
  {
    id: 'composer',
    title: t.s4title,
    desc: t.s4desc,
    place: 'top',
    find: () => document.querySelector<HTMLElement>('.prompt-bar__field'),
  },
  {
    id: 'settings',
    title: t.s5title,
    desc: t.s5desc,
    place: 'right',
    find: () => document.querySelector<HTMLElement>('.sb-row--settings'),
  },
  ]
}

export default function OnboardingTour() {
  const { locale } = useLocale()
  const copy = appCopy(locale)
  const t = useMemo<TourCopy>(() => ({ ...copy.tour, navKb: copy.nav.kb, navChat: copy.nav.chat }), [copy])
  const steps = useMemo(() => buildSteps(t), [t])
  const [open, setOpen] = useState(false)
  const [index, setIndex] = useState(0)
  /* 目标与卡片位置（滚动/缩放时重算） */
  const [box, setBox] = useState<{ target: DOMRect; card: { left: number; top: number } } | null>(null)
  const cardRef = useRef<HTMLDivElement>(null)

  const finish = useCallback((persist = true) => {
    if (persist) localStorage.setItem(DONE_KEY, '1')
    setOpen(false)
    setIndex(0)
  }, [])

  /* 首次进入：延迟一点，等布局稳定再显示 */
  useEffect(() => {
    if (localStorage.getItem(DONE_KEY) === '1') return
    const t = setTimeout(() => setOpen(true), 700)
    return () => clearTimeout(t)
  }, [])

  /* 设置弹层里的「重新查看引导」 */
  useEffect(() => {
    const onStart = () => {
      localStorage.removeItem(DONE_KEY)
      setIndex(0)
      setOpen(true)
    }
    window.addEventListener(TOUR_START_EVENT, onStart)
    return () => window.removeEventListener(TOUR_START_EVENT, onStart)
  }, [])

  /* 当前步骤（跳过找不到目标的步骤） */
  const step = useMemo(() => {
    if (!open) return null
    for (let i = index; i < steps.length; i += 1) {
      if (steps[i].find()) return { ...steps[i], at: i }
    }
    return null
  }, [open, index, steps])

  /* 计算高亮框与卡片位置 */
  useEffect(() => {
    if (!open || !step) return
    const compute = () => {
      const el = step.find()
      if (!el) return
      const r = el.getBoundingClientRect()
      const cardH = cardRef.current?.offsetHeight ?? 150
      let left = 0
      let top = 0
      if (step.place === 'right') { left = r.right + GAP; top = r.top + r.height / 2 - cardH / 2 }
      else if (step.place === 'left') { left = r.left - GAP - CARD_W; top = r.top + r.height / 2 - cardH / 2 }
      else if (step.place === 'top') { left = r.left + r.width / 2 - CARD_W / 2; top = r.top - GAP - cardH }
      else { left = r.left + r.width / 2 - CARD_W / 2; top = r.bottom + GAP }
      /* 收敛到视口内 */
      left = Math.min(Math.max(left, 12), window.innerWidth - CARD_W - 12)
      top = Math.min(Math.max(top, 12), window.innerHeight - cardH - 12)
      setBox({ target: r, card: { left, top } })
    }
    compute()
    window.addEventListener('scroll', compute, true)
    window.addEventListener('resize', compute)
    const ro = new ResizeObserver(compute)
    ro.observe(document.body)
    return () => {
      window.removeEventListener('scroll', compute, true)
      window.removeEventListener('resize', compute)
      ro.disconnect()
    }
  }, [open, step])

  /* 键盘：Esc 跳过、左右方向键翻页 */
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') finish()
      if (e.key === 'ArrowRight') setIndex((i) => Math.min(i + 1, steps.length - 1))
      if (e.key === 'ArrowLeft') setIndex((i) => Math.max(i - 1, 0))
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [open, finish, steps])

  /* 到最后一步后"下一步"就是完成 */
  useEffect(() => {
    if (open && index >= steps.length - 1) setIndex(steps.length - 1)
  }, [open, index, steps])

  if (!open || !step) return null
  const isLast = step.at === steps.length - 1

  return createPortal(
    <div className="tour" role="dialog" aria-modal="false" aria-label={t.aria}>
      {box && (
        <>
          {/* 高亮框：靠超大 box-shadow 把四周压暗，中心保持透明 */}
          <div
            className="tour__spotlight"
            style={{
              left: box.target.left - PAD,
              top: box.target.top - PAD,
              width: box.target.width + PAD * 2,
              height: box.target.height + PAD * 2,
            }}
          />
          <div
            className="tour__card"
            ref={cardRef}
            style={{ left: box.card.left, top: box.card.top, width: CARD_W }}
          >
            <div className="tour__head">
              <span className="tour__badge">{step.at + 1} / {steps.length}</span>
              <button className="tour__close" type="button" aria-label={t.closeAria} onClick={() => finish()}>
                <X size={14} />
              </button>
            </div>
            <h3 className="tour__title"><Loc>{step.title}</Loc></h3>
            <p className="tour__desc"><Loc>{step.desc}</Loc></p>
            <div className="tour__actions">
              <button className="btn btn-ghost btn-sm" type="button" onClick={() => finish()}><Loc>{t.skip}</Loc></button>
              <span className="tour__spacer" />
              <button
                className="btn btn-ghost btn-sm"
                type="button"
                disabled={index === 0}
                onClick={() => setIndex((i) => Math.max(i - 1, 0))}
              >
                <Loc>{t.prev}</Loc>
              </button>
              <button
                className="btn btn-primary btn-sm"
                type="button"
                onClick={() => (isLast ? finish() : setIndex((i) => i + 1))}
              >
                <Loc>{isLast ? t.done : t.next}</Loc>
              </button>
            </div>
          </div>
        </>
      )}
    </div>,
    document.body
  )
}
