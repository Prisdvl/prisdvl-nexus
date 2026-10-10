/**
 * 跳到主内容。
 *
 * 为什么是 button 而不是 `<a href="#main-content">`：
 * 本项目用 createHashRouter，任何 `#xxx` 都会被路由当作一次导航
 * （跳到不存在的路由 → 404/重定向），所以不能改 hash。
 * 用按钮 + 编程式聚焦，既保留"键盘用户一键跳过导航"的能力，又不碰 URL。
 */
export default function SkipLink({ targetId = 'main-content' }: { targetId?: string }) {
  const jump = () => {
    const el = document.getElementById(targetId)
    if (!el) return
    el.scrollIntoView({ block: 'start' })
    el.focus({ preventScroll: true })
  }

  return (
    <button className="skip-link" type="button" onClick={jump}>
      跳到主内容
    </button>
  )
}