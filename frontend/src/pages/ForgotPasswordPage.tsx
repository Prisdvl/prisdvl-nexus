import { useEffect, useState, type FormEvent } from 'react'
import { AnimatePresence, motion, useReducedMotion } from 'motion/react'
import { ArrowLeft, KeyRound, Mail } from 'lucide-react'
import CodeSlots from '@/bits/CodeSlots/CodeSlots'
import SkipLink from '@/components/SkipLink'
import BrandMark from '@/components/BrandMark'
import WavesBg from '@/components/WavesBg'
import { api, errMessage, USE_MOCK } from '@/lib/api'
import { toast } from '@/lib/toast'
import type { PaletteName, ThemeMode } from '@/lib/types'
import { appCopy } from '@/lib/appCopy'
import { useLocale } from '@/lib/locale'
import Loc from '@/components/Loc'

interface ForgotPasswordPageProps {
  theme: ThemeMode
  palette: PaletteName
  onGoLogin: () => void
  onGoHome: () => void
}

const EMAIL_CODE_COOLDOWN_SEC = 60
const EASE: [number, number, number, number] = [0.22, 1, 0.36, 1]

const emailOk = (v: string) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v)

type Phase = 'email' | 'reset' | 'done'

export default function ForgotPasswordPage({ theme, palette, onGoLogin, onGoHome }: ForgotPasswordPageProps) {
  const reduceMotion = useReducedMotion()
  const { locale } = useLocale()
  const a = appCopy(locale).forgot
  const [phase, setPhase] = useState<Phase>('email')
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

  const reveal = {
    duration: reduceMotion ? 0.01 : 0.36,
    ease: EASE,
  } as const

  /** 改邮箱后回到第一步，避免用旧验证码对着新地址提交 */
  const onEmailChange = (value: string) => {
    setEmail(value)
    if (phase === 'reset') {
      setPhase('email')
      setCode('')
      setPassword('')
      setPassword2('')
      setSlotsStatus('idle')
      setInfoMsg('')
      setFormMsg('')
      setSubmitted(false)
    }
  }

  const sendCode = async () => {
    setTouched((s) => ({ ...s, email: true }))
    setFormMsg('')
    if (errs.email || sending || cooldownLeft > 0) {
      if (errs.email) toast.error(a.formInvalidTitle, a.emailRequiredBody)
      return
    }
    setSending(true)
    setSlotsStatus('idle')
    try {
      if (USE_MOCK) {
        await new Promise((r) => setTimeout(r, 300))
        setInfoMsg(a.ack + a.ackMock)
        toast.info(a.submittedTitle, a.ack)
      } else {
        const message = await api.auth.requestPasswordReset(email.trim())
        setInfoMsg(message || a.ack)
        toast.info(a.submittedTitle, message || a.ack)
      }
      setCooldownLeft(EMAIL_CODE_COOLDOWN_SEC)
      setCode('')
      setPhase('reset')
    } catch (e) {
      const message = errMessage(e)
      setFormMsg(message)
      toast.error(a.sendFailTitle, message)
      if (message.includes('频繁') || message.includes('后再试')) {
        setCooldownLeft(EMAIL_CODE_COOLDOWN_SEC)
      }
    } finally {
      setSending(false)
    }
  }

  const submitReset = async (e?: FormEvent, overrideCode?: string) => {
    e?.preventDefault()
    if (phase !== 'reset') {
      void sendCode()
      return
    }
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
      toast.error(a.formInvalidTitle, a.formInvalidBody)
      return
    }
    if (submitting) return
    setSubmitting(true)
    setSlotsStatus('idle')
    try {
      if (USE_MOCK) {
        await new Promise((r) => setTimeout(r, 300))
        setSlotsStatus('success')
        setPhase('done')
        toast.success(a.resetOkMockTitle, a.resetOkMockBody)
      } else {
        const message = await api.auth.confirmPasswordReset({
          email: email.trim(),
          code: value,
          newPassword: password,
        })
        setSlotsStatus('success')
        setPhase('done')
        toast.success(a.resetOkTitle, message || a.resetOkBody)
      }
      setCode('')
      setPassword('')
      setPassword2('')
    } catch (err) {
      const message = errMessage(err)
      setFormMsg(message)
      setSlotsStatus('error')
      toast.error(a.resetFailTitle, message)
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
          aria-label={a.title}
          layout={!reduceMotion}
          initial={reduceMotion ? false : { opacity: 0, y: 22, scale: 0.985 }}
          animate={{ opacity: 1, y: 0, scale: 1 }}
          transition={{
            ...soft,
            layout: reduceMotion
              ? { duration: 0 }
              : { type: 'spring', stiffness: 320, damping: 34, mass: 0.82 },
          }}
        >
          <motion.button
            className="btn btn-ghost btn-sm auth-back"
            type="button"
            onClick={onGoHome}
            whileHover={reduceMotion ? undefined : { x: -2 }}
            whileTap={reduceMotion ? undefined : { scale: 0.97 }}
          >
            <ArrowLeft size={14} /> <Loc>{a.back}</Loc>
          </motion.button>

          <h1 className="sr-only">{a.title}</h1>

          <div className="auth-brand">
            <BrandMark wordmark tagline />
          </div>

          {/* motion 14.0.0 的 AnimatePresence mode="wait" 有回归（退出永不启动、新分支不挂载），
              done/form 两态改为 key 直接切换，入场动画保留在各自分支上 */}
          {phase === 'done' ? (
              <motion.div
                key="done"
                className="auth-form"
                initial={reduceMotion ? false : { opacity: 0, y: 8 }}
                animate={{ opacity: 1, y: 0 }}
                exit={reduceMotion ? undefined : { opacity: 0, y: -6 }}
                transition={soft}
              >
                <p className="form-error active" role="status" style={{ color: 'var(--success, #15803d)' }}>
                  {a.doneBody}
                </p>
                <button className="btn btn-primary btn-block" type="button" onClick={onGoLogin}>
                  <KeyRound size={16} /> <Loc>{a.backToLogin}</Loc>
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
                exit={reduceMotion ? undefined : { opacity: 0, y: -6 }}
                transition={soft}
              >
                <AnimatePresence initial={false}>
                  {formMsg ? (
                    <motion.div
                      key="err"
                      className="form-error active"
                      role="alert"
                      initial={reduceMotion ? false : { opacity: 0, y: -6, height: 0 }}
                      animate={{ opacity: 1, y: 0, height: 'auto' }}
                      exit={reduceMotion ? undefined : { opacity: 0, y: -4, height: 0 }}
                      transition={reveal}
                    >
                      {formMsg}
                    </motion.div>
                  ) : infoMsg ? (
                    <motion.p
                      key="info"
                      className="hint"
                      role="status"
                      style={{ marginTop: 0 }}
                      initial={reduceMotion ? false : { opacity: 0, y: -4 }}
                      animate={{ opacity: 1, y: 0 }}
                      exit={reduceMotion ? undefined : { opacity: 0 }}
                      transition={reveal}
                    >
                      {infoMsg}
                    </motion.p>
                  ) : null}
                </AnimatePresence>

                <motion.div className={`field ${show('email') ? 'has-error' : ''}`} layout={!reduceMotion}>
                  <label htmlFor="fp-email">{a.emailRequired}</label>
                  <input
                    id="fp-email"
                    className="input"
                    type="email"
                    value={email}
                    placeholder={a.emailPh}
                    autoComplete="email"
                    aria-invalid={show('email')}
                    onBlur={() => setTouched((s) => ({ ...s, email: true }))}
                    onChange={(e) => onEmailChange(e.target.value)}
                  />
                  <p className="err">{a.errEmail}</p>
                </motion.div>

                <AnimatePresence mode="popLayout" initial={false}>
                  {phase === 'email' && (
                    <motion.button
                      key="send-cta"
                      className="btn btn-primary btn-block"
                      type="submit"
                      disabled={sending || cooldownLeft > 0}
                      layout={!reduceMotion}
                      initial={reduceMotion ? false : { opacity: 0, y: 10 }}
                      animate={{ opacity: 1, y: 0 }}
                      exit={reduceMotion ? undefined : { opacity: 0, y: -8, scale: 0.98 }}
                      transition={reveal}
                      whileHover={reduceMotion || sending ? undefined : { y: -1 }}
                      whileTap={reduceMotion || sending ? undefined : { scale: 0.985 }}
                    >
                      <Mail size={16} />
                      {sending ? a.sending : cooldownLeft > 0 ? a.resendIn(cooldownLeft) : a.sendCode}
                    </motion.button>
                  )}

                  {phase === 'reset' && (
                    <motion.div
                      key="reset-fields"
                      initial={reduceMotion ? false : { opacity: 0 }}
                      animate={{ opacity: 1 }}
                      exit={reduceMotion ? undefined : { opacity: 0, y: -6 }}
                      transition={reveal}
                    >
                      {(
                        [
                          {
                            key: 'code',
                            node: (
                              <div className="field">
                                <label>{a.codeLabel}</label>
                                <CodeSlots
                                  length={6}
                                  value={code}
                                  onChange={(v) => {
                                    setCode(v)
                                    if (slotsStatus !== 'idle') setSlotsStatus('idle')
                                  }}
                                  status={slotsStatus}
                                  disabled={submitting || sending}
                                  ariaLabel={a.codeAria}
                                  accentColor="var(--accent, #0d9488)"
                                  inkColor="var(--text-1, #18181b)"
                                  slotColor="var(--surface-2, #f4f4f5)"
                                  digitColor="var(--text-1, #18181b)"
                                  dangerColor="var(--danger, #dc2626)"
                                  slotSize={40}
                                  gap={8}
                                  radius={10}
                                />
                                <p className="hint">{a.codeHint}</p>
                              </div>
                            ),
                          },
                          {
                            key: 'resend',
                            node: (
                              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 10, marginBottom: 4 }}>
                                <button
                                  className="btn btn-ghost"
                                  type="button"
                                  disabled={sending || cooldownLeft > 0}
                                  onClick={() => { void sendCode() }}
                                >
                                  {sending ? a.sending : cooldownLeft > 0 ? a.resendIn(cooldownLeft) : a.resendCode}
                                </button>
                              </div>
                            ),
                          },
                          {
                            key: 'pw',
                            node: (
                              <div className={`field ${show('password') ? 'has-error' : ''}`}>
                                <label htmlFor="fp-p">{a.newPassword}</label>
                                <input
                                  id="fp-p"
                                  className="input"
                                  type="password"
                                  value={password}
                                  placeholder={a.newPasswordPh}
                                  autoComplete="new-password"
                                  aria-invalid={show('password')}
                                  onBlur={() => setTouched((s) => ({ ...s, password: true }))}
                                  onChange={(e) => setPassword(e.target.value)}
                                />
                                <p className="err">{a.errPasswordLen}</p>
                              </div>
                            ),
                          },
                          {
                            key: 'pw2',
                            node: (
                              <div className={`field ${show('password2') ? 'has-error' : ''}`}>
                                <label htmlFor="fp-p2">{a.confirmPassword}</label>
                                <input
                                  id="fp-p2"
                                  className="input"
                                  type="password"
                                  value={password2}
                                  placeholder={a.confirmPh}
                                  autoComplete="new-password"
                                  aria-invalid={show('password2')}
                                  onBlur={() => setTouched((s) => ({ ...s, password2: true }))}
                                  onChange={(e) => setPassword2(e.target.value)}
                                />
                                <p className="err">{a.errConfirm}</p>
                              </div>
                            ),
                          },
                          {
                            key: 'reset-cta',
                            node: (
                              <motion.button
                                className="btn btn-primary btn-block"
                                type="submit"
                                disabled={submitting}
                                whileHover={reduceMotion || submitting ? undefined : { y: -1 }}
                                whileTap={reduceMotion || submitting ? undefined : { scale: 0.985 }}
                              >
                                <KeyRound size={16} /> {submitting ? a.resetting : a.resetPassword}
                              </motion.button>
                            ),
                          },
                        ] as const
                      ).map((item, i) => (
                        <motion.div
                          key={item.key}
                          initial={reduceMotion ? false : { opacity: 0, y: 16 }}
                          animate={{ opacity: 1, y: 0 }}
                          transition={{
                            ...reveal,
                            delay: reduceMotion ? 0 : 0.04 + i * 0.075,
                          }}
                        >
                          {item.node}
                        </motion.div>
                      ))}
                    </motion.div>
                  )}
                </AnimatePresence>

                <p className="switch-line">
                  {a.remembered}
                  <button type="button" onClick={onGoLogin}>{a.backToLogin}</button>
                </p>
              </motion.form>
            )}
        </motion.section>
      </main>
    </div>
  )
}
