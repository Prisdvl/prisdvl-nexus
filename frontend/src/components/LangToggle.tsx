import { motion, useReducedMotion } from 'motion/react'
import UseAnimations from 'react-useanimations'
import globe from 'react-useanimations/lib/globe'
import Loc from './Loc'
import { appCopy } from '@/lib/appCopy'
import { useLocale } from '@/lib/locale'

type Variant = 'icon' | 'row' | 'compact'

interface Props {
  /** row：图标 + 目标语言文字（侧栏底部）；icon：纯图标（移动顶栏）；compact：图标 + 短文字（落地页） */
  variant?: Variant
  className?: string
  size?: number
}

/**
 * 全站唯一的语言切换入口。
 *
 * 图标统一走 react-useanimations 的 `globe`（hover-loop 循环播放），
 * 与 Sidebar / 移动顶栏 / 落地页共用同一个资源，避免出现两套风格。
 *
 * 宽度会随语言文字变化，用 motion 的 layout 弹簧让这段位移平滑，
 * 而不是等 CSS 重排后突然跳一下；`useReducedMotion` 下直接跳变。
 */
export default function LangToggle({ variant = 'icon', className, size = 18 }: Props) {
  const { locale, toggle } = useLocale()
  const t = appCopy(locale)
  const reduce = useReducedMotion()
  const target = locale === 'en' ? '简体中文' : 'English'
  const label = t.nav.switchLang

  const icon = (
    <motion.span
      className="lang-globe"
      aria-hidden="true"
      whileHover={reduce ? undefined : { rotate: [0, -12, 10, 0], scale: 1.08 }}
      whileTap={reduce ? undefined : { scale: 0.9 }}
      transition={reduce ? { duration: 0 } : { type: 'spring', stiffness: 320, damping: 16 }}
    >
      <UseAnimations animation={globe} size={size} strokeColor="currentColor" interaction="hover" />
    </motion.span>
  )

  const labelNode = variant === 'icon'
    ? null
    : (
      <motion.span className="lang-label" layout={!reduce}>
        <Loc>{target}</Loc>
      </motion.span>
    )

  if (variant === 'row') {
    return (
      <motion.button
        className={`sb-row sb-row--lang${className ? ` ${className}` : ''}`}
        type="button"
        aria-label={label}
        title={label}
        lang={locale === 'en' ? 'zh-CN' : 'en'}
        onClick={toggle}
        whileTap={reduce ? undefined : { scale: 0.98 }}
        transition={{ type: 'spring', stiffness: 420, damping: 30 }}
      >
        {icon}
        {labelNode}
      </motion.button>
    )
  }

  return (
    <motion.button
      className={`lang-toggle lang-toggle--${variant}${className ? ` ${className}` : ''}`}
      type="button"
      aria-label={label}
      title={label}
      lang={locale === 'en' ? 'zh-CN' : 'en'}
      onClick={toggle}
      whileTap={reduce ? undefined : { scale: 0.9 }}
      transition={{ type: 'spring', stiffness: 420, damping: 28 }}
    >
      {icon}
      {labelNode}
    </motion.button>
  )
}