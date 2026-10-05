import { useEffect, useState, type ReactElement } from 'react'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { changePassword, updateProfile } from '@/api/user'
import { useAuth } from '@/hooks/useAuth'
import { performLogout } from '@/hooks/useLogout'
import { useNavigate } from 'react-router'
import { getErrorMessage } from '@/lib/errors'

/**
 * 个人资料。半更新语义：**留空 = 不改，清空要显式点按钮**。
 *
 * ## 一个必须写进界面的约束（第一版做错了，被 E2E 抓出来）
 *
 * 后端 `PUT /users/me` 是半更新：不传的字段不改，**传空串才是清空**。
 * 第一版把"留空"当成"改了"，于是用户把昵称删掉再保存就**真的被清空**了，
 * 而组件注释写的是"留空则保持不变"——注释与实现矛盾，实测 `nickname=null` 才暴露。
 *
 * 根因不是编码疏漏，而是**界面上只有一个"留空"，却要表达两种意图**（不改 / 清空）。
 * 修法是显式区分：留空 = 不改；清空 = 点「清空昵称 / 清空邮箱」。
 * 于是"不改"成为默认意图，清空是一次有意识的动作。
 */
export function ProfileDialog({
  open,
  onClose
}: {
  open: boolean
  onClose: () => void
}): ReactElement {
  const { currentUser, refreshProfile } = useAuth()
  const [nickname, setNickname] = useState('')
  const [email, setEmail] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // 每次打开都用最新的 currentUser 回填，避免显示上一次编辑的残值
  useEffect(() => {
    if (open) {
      setNickname(currentUser?.nickname ?? '')
      setEmail(currentUser?.email ?? '')
      setError(null)
    }
  }, [open, currentUser])

  const submit = async (payload: { nickname?: string; email?: string }) => {
    setSaving(true)
    setError(null)
    try {
      await updateProfile(payload)
      await refreshProfile()
      onClose()
    } catch (e) {
      setError(getErrorMessage(e))
    } finally {
      setSaving(false)
    }
  }

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title="个人资料"
      footer={
        <>
          <Button variant="ghost" onClick={onClose} data-testid="profile-cancel">
            取消
          </Button>
          <Button
            variant="primary"
            loading={saving}
            data-testid="profile-save"
            onClick={() => {
              /*
               * 半更新语义的界面表达（这里踩过一次，注释与实现不符被 E2E 抓出来）：
               *
               * 后端 PUT /users/me 的语义是「不传 = 不改，传空串 = 清空」。
               * 第一版实现是"留空就算改"，于是用户把昵称删掉再保存 → 传 '' → **真的被清空**，
               * 而组件注释却写着"留空则保持不变"——两者矛盾，E2E 实测 nickname=null 才暴露。
               *
               * 根因：界面上只有一个"留空"，却要表达两种意图（不改 / 清空），无法区分。
               * 正确做法是**显式区分**：
               *   留空（trim 后为 ''）      → 视为"不改"，不放进 payload；
               *   要清空                    → 点「清空昵称」，它显式传 ''。
               * 这样"不改"永远是默认意图，清空是一次有意识的动作。
               */
              const payload: { nickname?: string; email?: string } = {}
              const nextNickname = nickname.trim()
              const nextEmail = email.trim()
              const currentNickname = currentUser?.nickname ?? ''
              const currentEmail = currentUser?.email ?? ''
              if (nextNickname && nextNickname !== currentNickname) payload.nickname = nextNickname
              if (nextEmail && nextEmail !== currentEmail) payload.email = nextEmail
              if (Object.keys(payload).length === 0) {
                onClose()
                return
              }
              void submit(payload)
            }}
          >
            保存
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        <Field label="登录名">
          <input
            value={currentUser?.username ?? ''}
            disabled
            data-testid="profile-username"
            className="w-full rounded-[var(--nx-radius-md)] border border-[var(--nx-color-border)] bg-[var(--nx-color-surface-muted)] px-3 py-2 text-[var(--nx-text-sm)]"
          />
        </Field>
        <Field label="昵称">
          <input
            value={nickname}
            onChange={(e) => setNickname(e.target.value)}
            placeholder="留空则保持不变"
            data-testid="profile-nickname"
            className="w-full rounded-[var(--nx-radius-md)] border border-[var(--nx-color-border)] px-3 py-2 text-[var(--nx-text-sm)]"
          />
        </Field>
        <Field label="邮箱">
          <input
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="留空则保持不变"
            data-testid="profile-email"
            className="w-full rounded-[var(--nx-radius-md)] border border-[var(--nx-color-border)] px-3 py-2 text-[var(--nx-text-sm)]"
          />
        </Field>
        {error ? (
          <p className="m-0 text-[var(--nx-text-xs)] text-[var(--nx-color-danger)]" data-testid="profile-error">
            {error}
          </p>
        ) : null}
        {/*
          清空是**显式动作**：留空只能表达"不改"，要清掉必须点这里。
          两个按钮都改同一个输入框的值，提交时由"是否为空"决定意图——
          这样就不存在"我以为不改、结果被清空"的歧义。
        */}
        <div className="flex gap-2">
          <Button
            variant="ghost"
            data-testid="profile-clear-nickname"
            onClick={() => void submit({ nickname: '' })}
          >
            清空昵称
          </Button>
          <Button
            variant="ghost"
            data-testid="profile-clear-email"
            onClick={() => void submit({ email: '' })}
          >
            清空邮箱
          </Button>
        </div>
        <p className="m-0 text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">
          留空表示不修改；要清空请用上面的按钮（后端把"不传"当作不改、把空串当作清空）。
        </p>
      </div>
    </Dialog>
  )
}

/**
 * 修改密码。
 *
 * ## 成功后为什么必须主动登出
 *
 * 后端改密码时会**吊销该用户全部已签发令牌**，包括发起本次请求的这一支。
 * 所以成功后当前令牌已经无效了。若什么都不做，用户会在下一次请求时撞上 401，
 * 看到"登录已过期"——与他刚做的事对不上，看起来像 bug。
 * 这里显式登出并跳登录页，把因果关系讲清楚。
 */
export function ChangePasswordDialog({
  open,
  onClose
}: {
  open: boolean
  onClose: () => void
}): ReactElement {
  const navigate = useNavigate()
  const [oldPassword, setOldPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (open) {
      setOldPassword('')
      setNewPassword('')
      setConfirmPassword('')
      setError(null)
    }
  }, [open])

  const submit = async () => {
    if (newPassword.length < 8) {
      setError('新密码至少 8 位')
      return
    }
    if (newPassword !== confirmPassword) {
      setError('两次输入的新密码不一致')
      return
    }
    setSaving(true)
    setError(null)
    try {
      await changePassword(oldPassword, newPassword)
      // 令牌已被吊销：主动清理并跳登录页（见类注释）
      performLogout()
      navigate('/login', { replace: true })
    } catch (e) {
      setError(getErrorMessage(e))
      setSaving(false)
    }
  }

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title="修改密码"
      footer={
        <>
          <Button variant="ghost" onClick={onClose} data-testid="password-cancel">
            取消
          </Button>
          <Button variant="primary" loading={saving} data-testid="password-save" onClick={() => void submit()}>
            确认修改
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        <Field label="当前密码">
          <input
            type="password"
            value={oldPassword}
            onChange={(e) => setOldPassword(e.target.value)}
            data-testid="password-old"
            className="w-full rounded-[var(--nx-radius-md)] border border-[var(--nx-color-border)] px-3 py-2 text-[var(--nx-text-sm)]"
          />
        </Field>
        <Field label="新密码">
          <input
            type="password"
            value={newPassword}
            onChange={(e) => setNewPassword(e.target.value)}
            placeholder="至少 8 位"
            data-testid="password-new"
            className="w-full rounded-[var(--nx-radius-md)] border border-[var(--nx-color-border)] px-3 py-2 text-[var(--nx-text-sm)]"
          />
        </Field>
        <Field label="确认新密码">
          <input
            type="password"
            value={confirmPassword}
            onChange={(e) => setConfirmPassword(e.target.value)}
            data-testid="password-confirm"
            className="w-full rounded-[var(--nx-radius-md)] border border-[var(--nx-color-border)] px-3 py-2 text-[var(--nx-text-sm)]"
          />
        </Field>
        {error ? (
          <p className="m-0 text-[var(--nx-text-xs)] text-[var(--nx-color-danger)]" data-testid="password-error">
            {error}
          </p>
        ) : null}
        <p className="m-0 text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">
          修改成功后需要重新登录。
        </p>
      </div>
    </Dialog>
  )
}

function Field({ label, children }: { label: string; children: React.ReactNode }): ReactElement {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">{label}</span>
      {children}
    </label>
  )
}
