/**
 * 语言切换的整页交叉淡入（View Transitions API）。
 *
 * 为什么不只靠 <Loc> 的文字弹簧：那只能让每个被包裹的片段自己弹进来，
 * 页面里仍有大量文本是直接读字典渲染的，切换瞬间会整体重排（中文比英文短，
 * 折行位置全变），观感是「文字跳了一下」。这里用 root 快照让新旧两帧交叉淡入，
 * 重排被藏在淡入淡出里。
 *
 * 与主题切换的圆形扩散互不干扰：扩散靠 `--theme-x/--theme-y/--theme-radius` +
 * `[data-theme-switching]` 之外的 root 动画，这里只在切换期间挂
 * `[data-locale-switching]`，结束后立刻摘掉。
 */

type TransitionLike = { finished: Promise<void> }

let busy = false

/**
 * 让出一轮事件循环：React 的状态更新在微任务里批量落地，等一个宏任务就够了。
 *
 * 这里**不能**用 requestAnimationFrame 等：View Transition 的 update 回调执行期间
 * 渲染被冻结，rAF 不会触发，回调永远不 settle，浏览器最终以
 * 「Transition was aborted because of timeout in DOM update」中断过渡（实测 4s）。
 */
function nextTask(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

export function runLocaleTransition(apply: () => void): void {
  if (typeof document === 'undefined') {
    apply()
    return
  }
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  const doc = document as Document & {
    startViewTransition?: (cb: () => void | Promise<void>) => TransitionLike
  }

  /* 没有 API 或用户要求减动效：直接换，不做任何过渡 */
  if (reduce || typeof doc.startViewTransition !== 'function' || busy) {
    apply()
    return
  }

  const root = document.documentElement
  busy = true
  root.dataset.localeSwitching = ''
  const transition = doc.startViewTransition(async () => {
    apply()
    await nextTask()
  })
  transition.finished
    .catch(() => {})
    .finally(() => {
      delete root.dataset.localeSwitching
      busy = false
    })
}
