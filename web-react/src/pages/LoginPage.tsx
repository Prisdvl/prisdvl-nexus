import { useState, type FormEvent, type ReactElement } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { Button } from '@/components/ui/button'
import { Field, Input } from '@/components/ui/form'
import { useAuth } from '@/hooks/useAuth'

/**
 * 登录页 —— R3 只做**登录基础功能**（R3 任务书 §10）。
 *
 * 明确不做：注册完整流程、密码强度规则、第三方登录、忘记密码。
 * 但**结构**留好了：注册只要再加一个 mode 切换 + 调 `register()` 再 `login()` 即可。
 */
export function LoginPage(): ReactElement {
  const { login, loggingIn, error, lastUsername, clearError } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()

  const [username, setUsername] = useState(lastUsername ?? '')
  const [password, setPassword] = useState('')
  const [fieldError, setFieldError] = useState<{ username?: string; password?: string }>({})

  const from = (location.state as { from?: string } | null)?.from

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault()
    clearError()

    // 前端校验只拦"明显空着"的情况，不重复后端的密码规则——
    // 两处规则不一致时，用户会以为什么都提示为准，反而更困惑。
    const next: typeof fieldError = {}
    if (!username.trim()) next.username = '请输入用户名'
    if (!password) next.password = '请输入密码'
    setFieldError(next)
    if (Object.keys(next).length > 0) return

    const ok = await login(username.trim(), password)
    if (!ok) return
    // 登录成功：回到来源页，或首页。
    // 用 replace 而不是 push —— 登录页不该留在历史里。
    navigate(from && from !== '/login' ? from : '/', { replace: true })
  }

  return (
    <div className="flex flex-col gap-6" data-testid="login-page">
      <header className="flex flex-col gap-1">
        <h1 className="m-0 text-[var(--nx-text-xl)] font-[var(--nx-weight-semibold)]">登录</h1>
        <p className="m-0 text-[var(--nx-text-sm)] text-[var(--nx-color-text-secondary)]">
          用你的账号进入知识库控制台。
        </p>
      </header>

      <form className="flex flex-col gap-4" onSubmit={onSubmit} noValidate>
        <Field label="用户名" required error={fieldError.username}>
          {(p) => (
            <Input
              {...p}
              name="username"
              autoComplete="username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              placeholder="请输入用户名"
              data-testid="login-username"
            />
          )}
        </Field>

        <Field label="密码" required error={fieldError.password}>
          {(p) => (
            <Input
              {...p}
              name="password"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="请输入密码"
              data-testid="login-password"
            />
          )}
        </Field>

        {/*
          登录失败原因用 role="alert"，读屏会立刻播报。
          它显示后端原话（如"用户名或密码错误"），不做二次加工——
          Vue 侧的「欢迎回来 undefined」正是"前端自己编的提示"掩盖了真实原因。
        */}
        {error ? (
          <p
            role="alert"
            data-testid="login-error"
            className="m-0 rounded-[var(--nx-radius-md)] bg-[var(--nx-color-danger-container)] px-3 py-2 text-[var(--nx-text-sm)] text-[var(--nx-color-danger)]"
          >
            {error}
          </p>
        ) : null}

        <Button type="submit" fullWidth loading={loggingIn} data-testid="login-submit">
          登录
        </Button>
      </form>
    </div>
  )
}
