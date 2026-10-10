import { Link, useRouteError } from 'react-router'
import { AlertTriangle, RotateCcw, Home } from 'lucide-react'

/**
 * 路由级错误边界。
 *
 * 之前没有 errorElement：任何渲染期异常都会落到 React Router 的**默认英文错误页**
 * （"Unexpected Application Error!" + 堆栈），对用户来说是白屏级体验、也没法自助恢复。
 * 这里给出：发生了什么（人话）+ 两个明确的恢复动作（重试 / 回工作台）+ 可折叠的技术细节。
 */
export default function AppError() {
  const error = useRouteError() as Error | undefined
  const message = error?.message ?? '未知错误'

  return (
    <div className="page" style={{ maxWidth: 640, margin: '0 auto', paddingTop: 80 }}>
      <div className="panel" style={{ padding: 24, textAlign: 'center' }}>
        <span
          style={{
            display: 'inline-grid', placeItems: 'center',
            width: 48, height: 48, borderRadius: 14,
            background: 'var(--danger-container)', color: 'var(--danger)', marginBottom: 14,
          }}
        >
          <AlertTriangle size={22} />
        </span>
        <h1 className="page-title" style={{ fontSize: 20 }}>这个页面出错了</h1>
        <p className="page-sub" style={{ marginTop: 8 }}>
          界面遇到了未预期的问题，你的会话数据已保存在本地，刷新不会丢。
        </p>

        <div style={{ display: 'flex', gap: 10, justifyContent: 'center', marginTop: 20 }}>
          <button className="btn btn-primary" type="button" onClick={() => window.location.reload()}>
            <RotateCcw size={15} /> 重新加载
          </button>
          <Link className="btn btn-ghost" to="/app/workbench" onClick={() => { /* 回工作台重新开始 */ }}>
            <Home size={15} /> 回到工作台
          </Link>
        </div>

        <details style={{ marginTop: 22, textAlign: 'left' }}>
          <summary style={{ cursor: 'pointer', fontSize: 12.5, color: 'var(--text-3)' }}>技术细节（反馈问题时请附上）</summary>
          <pre
            style={{
              marginTop: 10, padding: 12, overflowX: 'auto',
              border: '1px solid var(--border)', borderRadius: 10,
              background: 'var(--surface-muted)', fontSize: 12, lineHeight: 1.6, color: 'var(--text-2)',
            }}
          >
{message}
          </pre>
        </details>
      </div>
    </div>
  )
}
