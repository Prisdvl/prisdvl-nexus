import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { X } from 'lucide-react'
import '@/styles/tour.css'

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
const STEPS: Step[] = [
  {
    id: 'new',
    title: '从「新对话」开始',
    desc: '想聊就聊，不用先建文件夹。会话会自动收进左侧列表，随时回来接着问。',
    place: 'right',
    find: () => document.querySelector<HTMLElement>('.sb-row--quick'),
  },
  {
    id: 'kb',
    title: '先让知识库有料',
    desc: '上传 PDF / Markdown 后会被切分、向量化；对话里 @ 一下就能引用它作答。',
    place: 'right',
    find: () => navItem('知识库'),
  },
  {
    id: 'chat',
    title: '回答要能追溯',
    desc: '对话区右侧的横线是提问导航；回答上方的工具调用轨迹会显示检索了哪些库、读了哪篇文档。',
    place: 'right',
    find: () => navItem('对话'),
  },
  {
    id: 'composer',
    title: '输入框里的两个快捷键',
    desc: '输入 @ 引用数据源（知识库 / 上传文件 / 联网），输入 / 打开快捷命令。',
    place: 'top',
    find: () => document.querySelector<HTMLElement>('.prompt-bar__field'),
  },
  {
    id: 'settings',
    title: '外观与数据都在这里',
    desc: '主题、配色、通知、用量与数据导出，统一收在「设置」里。',
    place: 'right',
    find: () => document.querySelector<HTMLElement>('.sb-row--settings'),
  },
]

export default function OnboardingTour() {
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
    for (let i = index; i < STEPS.length; i += 1) {
      if (STEPS[i].find()) return { ...STEPS[i], at: i }
    }
    return null
  }, [open, index])

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
      if (e.key === 'ArrowRight') setIndex((i) => Math.min(i + 1, STEPS.length - 1))
      if (e.key === 'ArrowLeft') setIndex((i) => Math.max(i - 1, 0))
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [open, finish])

  /* 到最后一步后"下一步"就是完成 */
  useEffect(() => {
    if (open && index >= STEPS.length - 1) setIndex(STEPS.length - 1)
  }, [open, index])

  if (!open || !step) return null
  const isLast = step.at === STEPS.length - 1

  return createPortal(
    <div className="tour" role="dialog" aria-modal="false" aria-label="新手引导">
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
              <span className="tour__badge">{step.at + 1} / {STEPS.length}</span>
              <button className="tour__close" type="button" aria-label="关闭引导" onClick={() => finish()}>
                <X size={14} />
              </button>
            </div>
            <h3 className="tour__title">{step.title}</h3>
            <p className="tour__desc">{step.desc}</p>
            <div className="tour__actions">
              <button className="btn btn-ghost btn-sm" type="button" onClick={() => finish()}>跳过</button>
              <span className="tour__spacer" />
              <button
                className="btn btn-ghost btn-sm"
                type="button"
                disabled={index === 0}
                onClick={() => setIndex((i) => Math.max(i - 1, 0))}
              >
                上一步
              </button>
              <button
                className="btn btn-primary btn-sm"
                type="button"
                onClick={() => (isLast ? finish() : setIndex((i) => i + 1))}
              >
                {isLast ? '完成' : '下一步'}
              </button>
            </div>
          </div>
        </>
      )}
    </div>,
    document.body
  )
}
