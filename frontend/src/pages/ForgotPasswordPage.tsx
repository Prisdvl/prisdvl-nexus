import { useEffect, useState, type FormEvent } from 'react'
import { AnimatePresence, motion, useReducedMotion } from 'motion/react'
import { ArrowLeft, KeyRound } from 'lucide-react'
import CodeSlots from '@/bits/CodeSlots/CodeSlots'
import SkipLink from '@/components/SkipLink'
import BrandMark from '@/components/BrandMark'
import WavesBg from '@/components/WavesBg'
import { api, errMessage, USE_MOCK } from '@/lib/api'
import { toast } from '@/lib/toast'
import type { PaletteName, ThemeMode } from '@/lib/types'

interface ForgotPasswordPageProps {
  theme: ThemeMode
  palette: PaletteName
  onGoLogin: () => void
  onGoHome: () => void
}

const EMAIL_CODE_COOLDOWN_SEC = 60
const EASE: [number, number, number, number] = [0.22, 1, 0.36, 1]
const ACK_HINT = '如果该邮箱对应符合条件的账户，请查收邮件或稍后重试。'

const emailOk = (v: string) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v)

export default function ForgotPasswordPage({ theme, palette, onGoLogin, onGoHome }: ForgotPasswordPageProps) {
  const reduceMotion = useReducedMotion()
  const [email, setEmail] = useState('')
  const [code, setCode] = useState('')
  const [password, setPassword] = useState('')
  const [password2, setPassword2] = useState('')
  const [slotsStatus, setSlotsStatus] = useState<'idle' | 'error' | 'success'>('idle')
  const [formMsg, setFormMsg] = useState('')
  const [infoMsg, setInfoMsg] = useState('')
  const [sending, setSending] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [cooldownLeft, setCooldownLeft] = useState(0)
  const [done, setDone] = useState(false)
  const [touched, setTouched] = useState<Record<string, boolean>>({})
  const [submitted, setSubmitted] = useState(false)

  useEffect(() => {
    if (cooldownLeft <= 0) return
    const t = window.setTimeout(() => setCooldownLeft((s) => Math.max(0, s - 1)), 1000)
    return () => window.clearTimeout(t)
  }, [cooldownLeft])

  const errs = {
    email: !emailOk(email.trim()),
    password: password.length < 8 || password.length > 64,
    password2: password2 !== password,
    code: code.replace(/\D/g, '').length < 4,
  }
  const show = (k: keyof typeof errs) => errs[k] && (touched[k] || submitted)

  const soft = {
    duration: reduceMotion ? 0.01 : 0.48,
    ease: EASE,
  } as const

  const sendCode = async () => {
    setTouched((s) => ({ ...s, email: true }))
    setFormMsg('')
    if (errs.email || sending || cooldownLeft > 0) {
      if (errs.email) toast.error('表单还有问题', '请填写正确的邮箱')
      return
    }
    setSending(true)
    setSlotsStatus('idle')
    try {
      if (USE_MOCK) {
        await new Promise((r) => setTimeout(r, 300))
        setInfoMsg(ACK_HINT + '（演示模式不会真正发信）')
        toast.info('已提交重置请求', ACK_HINT)
      } else {
        const message = await api.auth.requestPasswordReset(email.trim())
        setInfoMsg(message || ACK_HINT)
        toast.info('已提交重置请求', message || ACK_HINT)
      }
      setCooldownLeft(EMAIL_CODE_COOLDOWN_SEC)
      setCode('')
    } catch (e) {
      const message = errMessage(e)
      setFormMsg(message)
      toast.error('发送失败', message)
      if (message.includes('频繁') || message.includes('后再试')) {
        setCooldownLeft(EMAIL_CODE_COOLDOWN_SEC)
      }
    } finally {
      setSending(false)
    }
  }

  const submitReset = async (e?: FormEvent, overrideCode?: string) => {
    e?.preventDefault()
    setSubmitted(true)
    setFormMsg('')
    const value = (overrideCode ?? code).replace(/\D/g, '')
    const bad =
      errs.email ||
      value.length < 4 ||
      password.length < 8 ||
      password.length > 64 ||
      password2 !== password
    if (bad) {
      toast.error('表单还有问题', '请检查邮箱、验证码与新密码')
      return
    }
    if (submitting) return
    setSubmitting(true)
    setSlotsStatus('idle')
    try {
      if (USE_MOCK) {
        await new Promise((r) => setTimeout(r, 300))
        setSlotsStatus('success')
        setDone(true)
        toast.success('密码已重置（演示）', '请返回登录页使用新密码登录')
      } else {
        const message = await api.auth.confirmPasswordReset({
          email: email.trim(),
          code: value,
          newPassword: password,
        })
        setSlotsStatus('success')
        setDone(true)
        toast.success('密码已重置', message || '请使用新密码登录')
      }
      setCode('')
      setPassword('')
      setPassword2('')
    } catch (err) {
      const message = errMessage(err)
      setFormMsg(message)
      setSlotsStatus('error')
      toast.error('重置失败', message)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="auth-wrap">
      <WavesBg theme={theme} palette={palette} hero />
      <SkipLink />
      <main id="main-content" tabIndex={-1} className="auth-main">
        <motion.section
          className="auth-card"
          aria-label="忘记密码"
          initial={reduceMotion ? false : { opacity: 0, y: 22, scale: 0.985 }}
          animate={{ opacity: 1, y: 0, scale: 1 }}
          transition={soft}
        >
          <motion.button
            className="btn btn-ghost btn-sm auth-back"
            type="button"
            onClick={onGoHome}
            whileHover={reduceMotion ? undefined : { x: -2 }}
            whileTap={reduceMotion ? undefined : { scale: 0.97 }}
          >
            <ArrowLeft size={14} /> 返回
          </motion.button>

          <h1 className="sr-only">忘记密码</h1>

          <div className="auth-brand">
            <BrandMark wordmark tagline />
          </div>

          <p className="hint" style={{ textAlign: 'center', margin: '0 0 18px' }}>
            通过已验证邮箱重置密码
          </p>

          <AnimatePresence mode="wait" initial={false}>
            {done ? (
              <motion.div
                key="done"
                className="auth-form"
                initial={reduceMotion ? false : { opacity: 0, y: 8 }}
                animate={{ opacity: 1, y: 0 }}
                transition={soft}
              >
                <p className="form-error active" role="status" style={{ color: 'var(--success, #15803d)' }}>
                  密码已重置，请使用新密码登录。
                </p>
                <button className="btn btn-primary btn-block" type="button" onClick={onGoLogin}>
                  <KeyRound size={16} /> 返回登录
                </button>
              </motion.div>
            ) : (
              <motion.form
                key="form"
                className="auth-form"
                onSubmit={(ev) => { void submitReset(ev) }}
                noValidate
                initial={reduceMotion ? false : { opacity: 0, y: 8 }}
                animate={{ opacity: 1, y: 0 }}
                transition={soft}
              >
                <div className={`form-error ${formMsg ? 'active' : ''}`} role="alert">{formMsg}</div>
                {infoMsg && !formMsg && (
                  <p className="hint" role="status" style={{ marginTop: 0 }}>{infoMsg}</p>
                )}

                <div className={`field ${show('email') ? 'has-error' : ''}`}>
                  <label htmlFor="fp-email">邮箱</label>
                  <input
                    id="fp-email"
                    className="input"
                    type="email"
                    value={email}
                    placeholder="请输入已验证的邮箱"
                    autoComplete="email"
                    aria-invalid={show('email')}
                    onBlur={() => setTouched((s) => ({ ...s, email: true }))}
                    onChange={(e) => setEmail(e.target.value)}
                  />
                  <p className="err">请输入正确的邮箱地址</p>
                </div>

                <div className="field">
                  <label>验证码</label>
                  <CodeSlots
                    length={6}
                    value={code}
                    onChange={(v) => {
                      setCode(v)
                      if (slotsStatus !== 'idle') setSlotsStatus('idle')
                    }}
                    status={slotsStatus}
                    disabled={submitting || sending || done}
                    ariaLabel="密码重置验证码"
                    accentColor="var(--accent, #0d9488)"
                    inkColor="var(--text-1, #18181b)"
                    slotColor="var(--surface-2, #f4f4f5)"
                    digitColor="var(--text-1, #18181b)"
                    dangerColor="var(--danger, #dc2626)"
                    slotSize={40}
                    gap={8}
                    radius={10}
                  />
                  <p className="hint">6 位数字；冷却与限流由服务端强制执行。</p>
                </div>

                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 10, marginBottom: 4 }}>
                  <button
                    className="btn btn-ghost"
                    type="button"
                    disabled={sending || cooldownLeft > 0}
                    onClick={() => { void sendCode() }}
                  >
                    {sending ? '发送中…' : cooldownLeft > 0 ? `${cooldownLeft}s 后可重发` : '发送验证码'}
                  </button>
                </div>

                <div className={`field ${show('password') ? 'has-error' : ''}`}>
                  <label htmlFor="fp-p">新密码</label>
                  <input
                    id="fp-p"
                    className="input"
                    type="password"
                    value={password}
                    placeholder="8–64 位"
                    autoComplete="new-password"
                    aria-invalid={show('password')}
                    onBlur={() => setTouched((s) => ({ ...s, password: true }))}
                    onChange={(e) => setPassword(e.target.value)}
                  />
                  <p className="err">密码长度为 8–64 位</p>
                </div>

                <div className={`field ${show('password2') ? 'has-error' : ''}`}>
                  <label htmlFor="fp-p2">确认新密码</label>
                  <input
                    id="fp-p2"
                    className="input"
                    type="password"
                    value={password2}
                    placeholder="再次输入新密码"
                    autoComplete="new-password"
                    aria-invalid={show('password2')}
                    onBlur={() => setTouched((s) => ({ ...s, password2: true }))}
                    onChange={(e) => setPassword2(e.target.value)}
                  />
                  <p className="err">两次输入的密码不一致</p>
                </div>

                <motion.button
                  className="btn btn-primary btn-block"
                  type="submit"
                  disabled={submitting}
                  whileHover={reduceMotion || submitting ? undefined : { y: -1 }}
                  whileTap={reduceMotion || submitting ? undefined : { scale: 0.985 }}
                >
                  <KeyRound size={16} /> {submitting ? '重置中…' : '重置密码'}
                </motion.button>

                <p className="switch-line">
                  想起密码了？
                  <button type="button" onClick={onGoLogin}>返回登录</button>
                </p>
              </motion.form>
            )}
          </AnimatePresence>
        </motion.section>
      </main>
    </div>
  )
}
