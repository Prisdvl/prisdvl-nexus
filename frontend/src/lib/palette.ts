/**
 * 粒子/画布类组件的取色工具。
 *
 * 为什么不能直接传 `var(--primary)`：
 * PixelCard 是在 **canvas** 里 `ctx.fillStyle = color` 的，
 * canvas 不解析 CSS 变量，也不支持 color-mix。所以必须给出**具体颜色值**。
 *
 * 做法：从当前主题/配色的计算结果里读 token，再用 1×1 canvas 归一化成具体色，
 * 这样既不写死 hex 表（会与 token 漂移），又能拿到 canvas 可用的值。
 */
import { useMemo } from 'react'
import { useTheme } from '@/lib/theme'

/**
 * 把任意 CSS 颜色串归一化成 canvas 可用的**十六进制**。
 *
 * 必须是 hex：PixelCard 用 `colors.split(',')` 切分颜色列表，
 * 返回 `rgb(1, 2, 3)` 会被逗号切碎成 `rgb(1` / ` 2` / ` 3)`，
 * canvas 解析失败后 fillStyle 回退为默认黑色（实测粒子全黑）。
 */
function toConcreteColor(css: string): string {
  const cv = document.createElement('canvas')
  cv.width = 1
  cv.height = 1
  const ctx = cv.getContext('2d', { willReadFrequently: true })
  if (!ctx) return css
  ctx.clearRect(0, 0, 1, 1)
  ctx.fillStyle = 'rgba(0,0,0,0)'
  ctx.fillStyle = css
  ctx.fillRect(0, 0, 1, 1)
  const [r, g, b] = ctx.getImageData(0, 0, 1, 1).data
  const hex = (v: number) => v.toString(16).padStart(2, '0')
  return `#${hex(r)}${hex(g)}${hex(b)}`
}

/**
 * 卡片粒子的三档色（浅 → 中 → 深），始终跟随当前配色与明暗。
 * 依赖 theme/palette 变化重算，所以切换配色后粒子会立刻跟着变。
 */
export function useParticleColors(): string {
  const { resolved, palette } = useTheme()
  return useMemo(() => {
    const cs = getComputedStyle(document.documentElement)
    const read = (name: string) => toConcreteColor(cs.getPropertyValue(name).trim() || '#888')
    // --primary-container 是浅底、--primary 居中、--primary-strong 最深（暗色下三者整体提亮）
    return [read('--primary-container'), read('--primary'), read('--primary-strong')].join(',')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [resolved, palette])
}

/**
 * 把某个主题 token 解析成**具体 hex**。
 *
 * 有些组件（例如 SwipeRow）会用正则解析 hex 来计算前景墨色，
 * 传 `var(--x)` 会解析失败并回退成白色。这里统一从计算结果取值，
 * 并随主题/配色变化重算。
 */
export function useTokenColor(token: string): string {
  const { resolved, palette } = useTheme()
  return useMemo(() => {
    const cs = getComputedStyle(document.documentElement)
    return toConcreteColor(cs.getPropertyValue(token).trim() || '#888888')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, resolved, palette])
}
