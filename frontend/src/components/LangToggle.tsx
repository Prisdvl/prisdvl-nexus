import { motion, useReducedMotion } from 'motion/react'
import UseAnimations from 'react-useanimations'
import globe from 'react-useanimations/lib/globe'
import { appCopy } from '@/lib/appCopy'
import { useLocale } from '@/lib/locale'

interface Props {
  className?: string
  size?: number
}

/**
 * 全站唯一的语言切换入口（只保留落地页导航栏一处）。
 *
 * 图标统一走 react-useanimations 的 `globe`（hover 循环播放），
 * 按钮本身只承载图标：不铺「中文 / English」这类字面量，也不随语言宽度跳动。
 * 未登录页面（登录、注册）不再提供切换——语言跟随浏览器，少一处噪音。
 */
export default function LangToggle({ className, size = 18 }: Props) {
  const { locale, toggle } = useLocale()
  const t = appCopy(locale)
  const reduce = useReducedMotion()
  const label = t.nav.switchLang

  return (
    <motion.button
      className={`lang-toggle${className ? ` ${className}` : ''}`}
      type="button"
      aria-label={label}
      title={label}
      lang={locale === 'en' ? 'zh-CN' : 'en'}
      onClick={toggle}
      whileHover={reduce ? undefined : { scale: 1.05 }}
      whileTap={reduce ? undefined : { scale: 0.92 }}
      transition={reduce ? { duration: 0 } : { type: 'spring', stiffness: 420, damping: 26 }}
    >
      <motion.span
        className="lang-globe"
        aria-hidden="true"
        whileHover={reduce ? undefined : { rotate: [0, -12, 10, 0], scale: 1.08 }}
        whileTap={reduce ? undefined : { scale: 0.9 }}
        transition={reduce ? { duration: 0 } : { type: 'spring', stiffness: 320, damping: 16 }}
      >
        <UseAnimations animation={globe} size={size} strokeColor="currentColor" interaction="hover" />
      </motion.span>
    </motion.button>
  )
}
