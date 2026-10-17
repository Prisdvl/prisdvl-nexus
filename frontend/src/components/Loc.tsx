import type { CSSProperties, ReactNode } from 'react'
import { motion, useReducedMotion } from 'motion/react'

interface Props {
  children: ReactNode
  className?: string
  style?: CSSProperties
  /** 块级形态（默认 inline-block，随文本流） */
  block?: boolean
}

/**
 * 语言切换时带弹性的文本：以当前文案为 key，语言一切即轻微弹入。
 * 用于导航、标题、按钮等希望在中英切换时有反馈的静态文案。
 */
export default function Loc({ children, className, style, block = false }: Props) {
  const reduce = useReducedMotion()
  return (
    <motion.span
      key={String(children)}
      className={className}
      style={{ display: block ? 'block' : 'inline-block', ...style }}
      initial={reduce ? false : { opacity: 0, y: 6, scale: 0.96 }}
      animate={{ opacity: 1, y: 0, scale: 1 }}
      transition={reduce
        ? { duration: 0 }
        : { type: 'spring', stiffness: 480, damping: 32, mass: 0.7 }}
    >
      {children}
    </motion.span>
  )
}
