/**
 * ActivityHeatmap —— 近 N 天的用量热力图（GitHub contribution 布局）。
 *
 * 三条硬约束决定了它的形状：
 * 1. **文案由 props 传入**：组件不读 locale、不 import 字典，i18n 升级时只改调用点。
 * 2. **高度固定**：格子数与详情条都固定，加载中与加载后几何一致，插进设置页不造成 CLS。
 * 3. **可达**：每格是原生 <button>，带 aria-label 与 aria-pressed；
 *    hover 只是补充，键盘 Tab + Enter 同样能选日。
 */
import { useMemo, useState } from 'react'
import { motion, useReducedMotion } from 'motion/react'
import { cellTransition, revealTransition, revealVariants } from './activity-heatmap-utils/motion-tokens'
import styles from './activity-heatmap.module.css'

export interface HeatmapDay {
  /** ISO 日期（YYYY-MM-DD） */
  date: string
  /** 当天的数值；0 表示空 */
  value: number
}

export interface HeatmapLabels {
  title: string
  /** 图例左端，如「少」 */
  less: string
  /** 图例右端，如「多」 */
  more: string
  /** 汇总行，如「近 365 天 · 87 天有用量」 */
  summary: (total: number, activeDays: number) => string
  /** 单格 aria-label / title，如「2026-10-20，12 次」 */
  dayLabel: (date: string, value: number) => string
  /** 详情条里的数值部分，如「12 次用量」 */
  dayValue: (value: number) => string
  /** 没有悬停也没选中时的占位提示 */
  hint: string
}

export interface ActivityHeatmapProps {
  days: HeatmapDay[]
  labels: HeatmapLabels
  /** 色阶档数，默认 4（0 级留给「无数据」） */
  levels?: number
  /** 格子边长（px），窄面板可传 9 */
  cellSize?: number
  className?: string
  /** 点选某天；再次点同一格回传 null 表示取消 */
  onSelect?: (day: HeatmapDay | null) => void
  /** 受控选中态（ISO 日期） */
  selected?: string | null
  /**
   * 星期标签（周一起 7 个，如 ['一','二',…]）。传了就画左侧轴，只标周一/三/五；
   * 不传则纯格子。文案由调用点给，组件不认语言。
   */
  weekdays?: string[]
}

/** 把日期串当本地日历日解析，避免 new Date('YYYY-MM-DD') 按 UTC 解析而错位一天 */
export function parseDay(iso: string): Date {
  const [y, m, d] = iso.split('-').map(Number)
  return new Date(y, (m ?? 1) - 1, d ?? 1)
}

export function isoOf(date: Date): string {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

/** 生成「截至 end 的连续 count 天」日期序列（含 end） */
export function fillDateRange(end: Date, count: number): string[] {
  const out: string[] = []
  const base = new Date(end.getFullYear(), end.getMonth(), end.getDate())
  for (let i = count - 1; i >= 0; i--) {
    const d = new Date(base)
    d.setDate(base.getDate() - i)
    out.push(isoOf(d))
  }
  return out
}

/** 数值 → 0..levels；0 表示无数据。开方压缩，否则重尾数据会让 90% 的格子停在 1 级 */
function levelOf(value: number, max: number, levels: number): number {
  if (value <= 0) return 0
  if (max <= 0) return 1
  const ratio = Math.sqrt(value / max)
  return Math.min(levels, Math.max(1, Math.ceil(ratio * levels)))
}

export default function ActivityHeatmap({
  days,
  labels,
  levels = 4,
  cellSize = 11,
  className,
  onSelect,
  selected = null,
  weekdays,
}: ActivityHeatmapProps) {
  const reduce = useReducedMotion()
  const [hover, setHover] = useState<HeatmapDay | null>(null)

  const { cells, total, activeDays, max } = useMemo(() => {
    const max = days.reduce((m, d) => Math.max(m, d.value), 0)
    const total = days.reduce((s, d) => s + d.value, 0)
    const activeDays = days.filter((d) => d.value > 0).length
    if (days.length === 0) return { cells: [], total, activeDays, max }

    /* 对齐到周一：首列前补空格，保证每行永远是同一个星期几 */
    const first = parseDay(days[0].date)
    const lead = (first.getDay() + 6) % 7
    const list: Array<{ key: string; day: HeatmapDay | null; level: number }> = []
    for (let i = 0; i < lead; i++) list.push({ key: `lead-${i}`, day: null, level: -1 })
    for (const d of days) list.push({ key: d.date, day: d, level: levelOf(d.value, max, levels) })
    /* 末尾补满整列，避免最后一列只排了半列格子导致高度跳动 */
    while (list.length % 7 !== 0) list.push({ key: `tail-${list.length}`, day: null, level: -1 })
    return { cells: list, total, activeDays, max }
  }, [days, levels])

  /* 详情条只反映悬停/聚焦的那天：选中态由调用点自己的明细面板展示，
     否则同一天会在条里和面板里各出现一次 */
  const shown = hover

  const pick = (day: HeatmapDay) => {
    setHover(null)
    onSelect?.(selected === day.date ? null : day)
  }

  return (
    <motion.div
      className={[styles.root, className].filter(Boolean).join(' ')}
      initial={reduce ? false : 'hidden'}
      whileInView={reduce ? undefined : 'show'}
      viewport={{ once: true, amount: 0.3 }}
      variants={revealVariants}
      transition={revealTransition()}
    >
      <div className={styles.head}>
        <h3 className={styles.title}>{labels.title}</h3>
        <span className={styles.summary}>{labels.summary(total, activeDays)}</span>
      </div>

      {/* 星期轴与网格并排：轴固定在左侧，横向滚动只发生在网格里 */}
      <div className={styles.plot} style={{ ['--cell' as string]: `${cellSize}px` }}>
        {weekdays && weekdays.length === 7 && (
          <div className={styles.weekdays} aria-hidden="true">
            {weekdays.map((label, i) => (
              /* 只标周一/三/五，其余行留空；七行高度必须与网格一致，否则会错位 */
              <span key={label} className={styles.weekday}>
                {i % 2 === 0 && i < 5 ? label : ''}
              </span>
            ))}
          </div>
        )}

        {/* role=group 而不是 grid：格子是 <button>，没有 row/gridcell 层级，
            声明成 grid 反而是无效的 ARIA 结构 */}
        <div className={styles.grid} role="group" aria-label={labels.title}>
          {cells.map((cell) =>
            cell.day ? (
              <motion.button
                key={cell.key}
                type="button"
                className={styles.cell}
                data-level={cell.level}
                aria-pressed={selected === cell.day.date}
                aria-label={labels.dayLabel(cell.day.date, cell.day.value)}
                title={labels.dayLabel(cell.day.date, cell.day.value)}
                onClick={() => pick(cell.day as HeatmapDay)}
                onMouseEnter={() => setHover(cell.day)}
                onMouseLeave={() => setHover(null)}
                onFocus={() => setHover(cell.day)}
                onBlur={() => setHover(null)}
                whileTap={reduce ? undefined : { scale: 0.86 }}
                transition={cellTransition}
              />
            ) : (
              <span key={cell.key} className={styles.blank} aria-hidden="true" />
            ),
          )}
        </div>
      </div>

      <div className={styles.legend}>
        <span>{labels.less}</span>
        <span className={styles.legendSwatches} aria-hidden="true">
          {Array.from({ length: levels + 1 }, (_, i) => (
            <span
              key={i}
              className={styles.legendSwatch}
              style={{
                background:
                  i === 0
                    ? 'var(--ui-selected)'
                    : `color-mix(in oklab, var(--primary) ${i === levels ? 100 : 24 + i * 20}%, var(--ui-selected))`,
              }}
            />
          ))}
        </span>
        <span>{labels.more}</span>
      </div>

      {/* 详情条常驻：只换内容不换高度，悬停与选中都不会推挤下面元素 */}
      <div className={styles.detail} aria-live="polite">
        {shown ? (
          <>
            <span className={styles.detailDay}>{shown.date}</span>
            <span className={styles.detailValue}>· {labels.dayValue(shown.value)}</span>
          </>
        ) : (
          <span className={styles.detailValue}>{labels.hint}</span>
        )}
      </div>
    </motion.div>
  )
}
