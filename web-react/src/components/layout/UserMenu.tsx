import { useState, type ReactElement } from 'react'
import { useNavigate } from 'react-router'
import { Button } from '@/components/ui/button'
import { Sheet } from '@/components/ui/sheet'
import { cn } from '@/lib/utils'
import { useAuth } from '@/hooks/useAuth'
import { performLogout } from '@/hooks/useLogout'

/**
 * 账号入口 —— 用户信息来自**真实的 `currentUser`**（R3 任务书 §19）。
 *
 * ## 数据流（任务书 §8 要求的完整链路）
 * ```text
 * 后端 UserInfoResponse(id, username, nickname, email, roles)
 *   → api/types/auth.ts 的 UserInfo
 *   → authStore.currentUser（来源：GET /users/me 或登录响应的 user）
 *   → useAuth().currentUser
 *   → 本组件
 * ```
 *
 * ## 展示名的优先级，以及为什么不能靠 fallback 掩盖问题
 * ```text
 * nickname（用户自己设的）→ username（登录名）→ 邮箱前缀 → '?'
 * ```
 * Vue 侧出过一次「欢迎回来 undefined」：`AuthTokenResponse` 顶层没有 `username`，
 * 代码却去读它，于是渲染出 "undefined"。**那次事故的教训不是"加个 ??"**——
 * 那是把类型错误藏起来。真正的修法是让类型如实反映后端形状（已做，见 `api/types/auth.ts`），
 * 于是写错的地方**编译期就报错**（本次开发中它真的报了一次）。
 * 这里的 fallback 只针对"后端确实返回了空 nickname"这一种合法情况。
 */
function displayName(nickname: string | null, username: string, email: string | null): string {
  if (nickname && nickname.trim()) return nickname.trim()
  if (username && username.trim()) return username.trim()
  if (email && email.trim()) return email.trim().split('@')[0] ?? email
  return '?'
}

/** 头像里的首字母。**优先 nickname**（用户认得出来），其次 username。 */
function initialOf(nickname: string | null, username: string): string {
  const source = (nickname && nickname.trim()) || (username && username.trim()) || '?'
  return source.slice(0, 1).toUpperCase()
}

export function UserMenu(): ReactElement {
  const { currentUser } = useAuth()
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)

  // 兜底：工作区是受保护路由，正常到这里一定有 user。
  // 但**不为空就不显示**会让菜单整个消失（用户找不到登出入口），所以给一个占位名。
  const nickname = currentUser?.nickname ?? null
  const username = currentUser?.username ?? ''
  const email = currentUser?.email ?? null
  const name = displayName(nickname, username, email)
  const initial = initialOf(nickname, username)

  const onLogout = () => {
    setOpen(false)
    // 顺序：先清数据与存储，再跳页。见 performLogout 的注释。
    performLogout()
    navigate('/login', { replace: true })
  }

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        aria-label="账号菜单"
        aria-haspopup="dialog"
        data-testid="user-menu"
        // 折叠侧栏时（w-16）这一行只有 64px 宽，文字必然被挤掉。
        // 用容器查询式的做法成本太高，这里让外层 overflow 裁掉，
        // 并且**保留头像可见**——它是被识别的主要依据。
        className={cn(
          'flex w-full items-center gap-2.5 overflow-hidden rounded-[var(--nx-radius-md)] p-2',
          'text-left transition-colors duration-[var(--nx-dur-fast)]',
          'hover:bg-[var(--nx-color-surface-muted)]'
        )}
      >
        <span
          aria-hidden="true"
          data-testid="user-menu-initial"
          className="grid h-7 w-7 shrink-0 place-items-center rounded-full bg-[var(--nx-color-surface-muted)] text-[var(--nx-text-sm)] font-[var(--nx-weight-medium)]"
        >
          {initial}
        </span>
        <span className="min-w-0 flex-1">
          <span
            data-testid="user-menu-name"
            className="block truncate text-[var(--nx-text-sm)] font-[var(--nx-weight-medium)]"
          >
            {name}
          </span>
          {email ? (
            <span
              data-testid="user-menu-email"
              className="block truncate text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]"
            >
              {email}
            </span>
          ) : null}
        </span>
      </button>

      <Sheet
        open={open}
        onClose={() => setOpen(false)}
        title="账号"
        side="bottom"
        footer={
          <>
            <Button variant="ghost" onClick={() => setOpen(false)}>
              取消
            </Button>
            <Button variant="danger" onClick={onLogout} data-testid="user-menu-logout">
              退出登录
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-1">
          <Row
            label="登录名"
            value={username}
            testId="user-menu-username"
          />
          <Row
            label="邮箱"
            value={email ?? '未设置'}
            testId="user-menu-profile"
          />
          {/*
            角色直接列出来：它是后端 `roles` 的真实内容。
            不做"管理员"之类的翻译——后端给什么就显示什么，
            翻译层会在后端加角色时静默失效。
          */}
          {currentUser?.roles?.length ? (
            <Row
              label="角色"
              value={currentUser.roles.join('、')}
              testId="user-menu-roles"
            />
          ) : null}
          <Row
            label="个人资料"
            value="R5 实现"
            testId="user-menu-profile-edit"
            onClick={() => setOpen(false)}
          />
          <Row
            label="修改密码"
            value="R5 实现"
            testId="user-menu-password"
            onClick={() => setOpen(false)}
          />
        </div>
      </Sheet>
    </>
  )
}

function Row({
  label,
  value,
  testId,
  onClick
}: {
  label: string
  value: string
  testId: string
  onClick?: () => void
}): ReactElement {
  const content = (
    <>
      <span className="text-[var(--nx-text-sm)] font-[var(--nx-weight-medium)]">{label}</span>
      <span className="truncate text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">
        {value}
      </span>
    </>
  )
  return onClick ? (
    <button
      type="button"
      onClick={onClick}
      data-testid={testId}
      className="flex w-full items-center justify-between gap-3 rounded-[var(--nx-radius-md)] px-3 py-2.5 text-left transition-colors duration-[var(--nx-dur-fast)] hover:bg-[var(--nx-color-surface-muted)]"
    >
      {content}
    </button>
  ) : (
    <div
      data-testid={testId}
      className="flex items-center justify-between gap-3 rounded-[var(--nx-radius-md)] px-3 py-2.5"
    >
      {content}
    </div>
  )
}
