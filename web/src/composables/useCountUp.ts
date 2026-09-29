import { ref, watch, type Ref } from 'vue'

/** 用户是否要求减少动效——动效必须尊重这个设置，不能靠"看起来没关系"。 */
function prefersReducedMotion(): boolean {
  return (
    typeof window !== 'undefined' &&
    window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true
  )
}

/**
 * 数字滚动（ReactBits CountUp 的等效实现）。
 *
 * <p>用途很窄：只给"概览指标"（文档数、切片数、Token 数）用。
 * 数字从 0 滚到目标值，比直接闪现更容易让眼睛注意到"这里有变化"，
 * 但它是一次性动画（不是循环），因此不会一直占用 GPU。
 *
 * @param target 目标值（响应式）
 * @param duration 时长（ms）
 */
export function useCountUp(target: Ref<number>, duration = 650) {
  const display = ref(prefersReducedMotion() ? target.value : 0)
  /** 值变化时给一个轻微放大，作为"更新了"的提示 */
  const pulsing = ref(false)

  watch(
    target,
    (value, previous) => {
      if (prefersReducedMotion()) {
        display.value = value
        return
      }
      const from = previous === undefined ? 0 : display.value
      const start = performance.now()
      const tick = (now: number) => {
        const progress = Math.min(1, (now - start) / duration)
        // easeOutCubic：先快后慢，收尾不突兀
        const eased = 1 - Math.pow(1 - progress, 3)
        display.value = Math.round(from + (value - from) * eased)
        if (progress < 1) {
          requestAnimationFrame(tick)
        } else {
          pulsing.value = true
          window.setTimeout(() => (pulsing.value = false), 320)
        }
      }
      requestAnimationFrame(tick)
    },
    { immediate: true }
  )

  return { display, pulsing }
}
