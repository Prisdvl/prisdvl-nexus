/**
 * 主题切换圆形扩散（View Transitions API）。
 * 几何与缓动对齐 theme-toggle-diffusion 参考实现。
 */

type TransitionLike = { finished: Promise<void> }

let busy = false

function setRevealGeometry(origin: Element) {
  const rect = origin.getBoundingClientRect()
  const x = rect.left + rect.width / 2
  const y = rect.top + rect.height / 2
  const radius =
    Math.ceil(
      Math.max(
        Math.hypot(x, y),
        Math.hypot(window.innerWidth - x, y),
        Math.hypot(x, window.innerHeight - y),
        Math.hypot(window.innerWidth - x, window.innerHeight - y),
      ),
    ) + 2
  const root = document.documentElement
  root.style.setProperty('--theme-x', `${x}px`)
  root.style.setProperty('--theme-y', `${y}px`)
  root.style.setProperty('--theme-radius', `${radius}px`)
}

/** 从指定元素中心做圆形 clip-path 揭示；无 API / 减动效时直接应用。 */
export function runThemeTransition(origin: Element | null | undefined, apply: () => void): void {
  if (busy) return
  const reduce =
    typeof window !== 'undefined' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches

  if (reduce || !origin) {
    apply()
    return
  }

  setRevealGeometry(origin)

  const doc = document as Document & {
    startViewTransition?: (cb: () => void) => TransitionLike
  }

  if (typeof doc.startViewTransition !== 'function') {
    /* 无 View Transition 时用短淡入兜底，避免瞬间硬切 */
    const root = document.documentElement
    root.style.transition = 'background-color 0.35s ease, color 0.35s ease'
    apply()
    window.setTimeout(() => {
      root.style.transition = ''
    }, 380)
    return
  }

  busy = true
  const transition = doc.startViewTransition(apply)
  transition.finished.catch(() => {}).finally(() => {
    busy = false
  })
}
