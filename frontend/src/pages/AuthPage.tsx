import { useRef, useState, type FormEvent } from 'react'
import { AnimatePresence, motion, useReducedMotion } from 'motion/react'
import { ArrowLeft, LogIn } from 'lucide-react'
import RubberSegment from '@/bits/RubberSegment/RubberSegment'
import SkipLink from '@/components/SkipLink'
import { toast } from '@/lib/toast'
import { errMessage } from '@/lib/api'
import { useLocation, useNavigate } from 'react-router'
import BrandMark from '@/components/BrandMark'
import WavesBg from '@/components/WavesBg'
import type { PaletteName, ThemeMode } from '@/lib/types'

interface AuthPageProps {
  theme: ThemeMode
  palette: PaletteName
  /** 登录成功：把「来源页」交回路由层，由它决定跳哪。失败时把错误抛出，由本页展示。 */
  onEnter: (from: string | undefined, cred: Credentials) => Promise<void> | void
  /** 注册成功（后端 register 不返令牌，本流程内部会再登录一次） */
  onRegister?: (cred: RegisterCredentials) => Promise<void> | void
  /** 返回产品入口页，不触发全页刷新 */
  onGoHome: () => void
}

export interface Credentials {
  username: string
  password: string
}

export interface RegisterCredentials extends Credentials {
  nickname?: string
  email: string
}

const EASE: [number, number, number, number] = [0.22, 1, 0.36, 1]
/** 登录/注册切换：弹性弹簧，避免线性淡入淡出的生硬感 */
const TAB_SPRING = { type: 'spring' as const, stiffness: 420, damping: 28, mass: 0.72 }
const LAYOUT_SPRING = { type: 'spring' as const, stiffness: 340, damping: 32, mass: 0.8 }

export default function AuthPage({ theme, palette, onEnter, onRegister, onGoHome }: AuthPageProps) {
  const location = useLocation()
  const navigate = useNavigate()
  const from = (location.state as { from?: string } | null)?.from
  const reduceMotion = useReducedMotion()
  const [tab, setTab] = useState<'login' | 'register'>('login')
  /** 1 = 切到注册（内容从右弹入），-1 = 切到登录（从左弹入） */
  const tabDir = useRef(1)
  const [login, setLogin] = useState({ username: '', password: '' })
  const [reg, setReg] = useState({ username: '', password: '', password2: '', nickname: '', email: '' })
  const [formMsg, setFormMsg] = useState('')
  const [busy, setBusy] = useState(false)

  /**
   * 校验时机：**失焦或提交后**才显示该字段错误，输入过程中不报红。
   * 这样用户不会刚点进表单就看到一片红，也不会在改正途中一直被红框追着。
   */
  const [loginTouched, setLoginTouched] = useState<Record<string, boolean>>({})
  const [regTouched, setRegTouched] = useState<Record<string, boolean>>({})
  const [submitted, setSubmitted] = useState(false)

  /*
   * ⚠️ 登录与注册必须各自算各自的错误。
   * 之前登录表单的 has-error 直接绑了注册表单的校验器 `live.*`，
   * 于是「注册页的 username 为空」这条与登录毫无关系的状态，
   * 变成了登录框的 has-error —— 表现就是"明明填了值还在报请输入用户名"。
   * 现在两者完全独立：loginErrs 由 login 状态算，live 由 reg 状态算。
   */
  const loginErrs = {
    username: !login.username.trim(),
    password: !login.password,
  }

  /** 该字段此刻是否应该亮红：失焦过或提交过，且当前确实不合法 */
  const showLoginErr = (k: keyof typeof loginErrs) => loginErrs[k] && (loginTouched[k] || submitted)
  const show = (k: string, bad: boolean) => bad && (regTouched[k] || submitted)

  const submitLogin = async (e: FormEvent) => {
    e.preventDefault()
    setSubmitted(true)
    setFormMsg('')
    if (loginErrs.username || loginErrs.password) return
    if (busy) return
    setBusy(true)
    try {
      await onEnter(from, { username: login.username.trim(), password: login.password })
    } catch (err) {
      /* 后端错误直接展示：实测凭证错是 401 + code 2006「用户名或密码错误」，
         校验错是 400 + code 1001（message 带字段名前缀），连不上是网络错误。
         它走 formMsg（表单级红条），不会伪装成字段的必填错误。 */
      setFormMsg(errMessage(err))
    } finally {
      setBusy(false)
    }
  }

  /* 实时校验：不在提交时才计算，否则"失焦即报错"拿不到最新结果（这是之前的真 bug） */
  const live = {
    username: !/^[A-Za-z0-9_-]{3,32}$/.test(reg.username),
    password: reg.password.length < 8 || reg.password.length > 64,
    password2: reg.password2 !== reg.password,
    nickname: reg.nickname.length > 64,
    email: !reg.email.trim() || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(reg.email.trim()),
  }

  const submitRegister = (e: FormEvent) => {
    e.preventDefault()
    setFormMsg('')
    const next = live
    setSubmitted(true)
    if (Object.values(next).some(Boolean)) {
      toast.error('表单还有问题', '请按提示修正后再继续')
      return
    }
    void finishRegister()
  }

  const finishRegister = async () => {
    if (!onRegister) return
    setBusy(true)
    try {
      await onRegister({
        username: reg.username.trim(),
        password: reg.password,
        nickname: reg.nickname.trim() || undefined,
        email: reg.email.trim(),
      })
      /* 注册成功后清空表单与校验痕迹：
         否则下一次进来 submitted 仍为 true，一输入就立刻报红（测试抓到的真问题）。
         ⚠️ 刻意**不切到登录标签、不导航**：注册页填完就跳走会让用户
         来不及确认"注册成功"，而且后端 register 只建号、不返令牌，
         下一步必然是回登录页输密码 —— 留在本页最符合流程预期。
         导航由 `onRegister` 的实现方决定（当前不导航）。 */
      toast.success('注册成功', '账号已创建，切到「登录」标签用刚才的密码登录')
      setReg({ username: '', password: '', password2: '', nickname: '', email: '' })
      setRegTouched({})
      setSubmitted(false)
    } catch (err) {
      setFormMsg(errMessage(err))
    } finally {
      setBusy(false)
    }
  }

  const switchTab = (label: string) => {
    const id = label === '注册' ? 'register' : 'login'
    if (id === tab) return
    tabDir.current = id === 'register' ? 1 : -1
    setTab(id)
    setFormMsg('')
    setLoginTouched({})
    setRegTouched({})
    setSubmitted(false)
  }

  const soft = {
    duration: reduceMotion ? 0.01 : 0.48,
    ease: EASE,
  } as const

  const formMotion = reduceMotion
    ? {
        initial: false as const,
        animate: { opacity: 1, x: 0, scale: 1 },
        exit: undefined,
        transition: { duration: 0.01 },
      }
    : {
        initial: { opacity: 0, x: 28 * tabDir.current, scale: 0.985 },
        animate: { opacity: 1, x: 0, scale: 1 },
        exit: { opacity: 0, x: -22 * tabDir.current, scale: 0.985 },
        transition: TAB_SPRING,
      }

  return (
    <div className="auth-wrap">
      <WavesBg theme={theme} palette={palette} hero />
      <SkipLink />
      <main id="main-content" tabIndex={-1} className="auth-main">
        <motion.section
          className="auth-card"
          aria-label="登录或注册"
          layout
          initial={reduceMotion ? false : { opacity: 0, y: 22, scale: 0.985 }}
          animate={{ opacity: 1, y: 0, scale: 1 }}
          transition={{
            ...soft,
            layout: reduceMotion ? { duration: 0.01 } : LAYOUT_SPRING,
          }}
        >
          <motion.button
            className="btn btn-ghost btn-sm auth-back"
            type="button"
            onClick={onGoHome}
            initial={reduceMotion ? false : { opacity: 0, x: -6 }}
            animate={{ opacity: 1, x: 0 }}
            transition={{ ...soft, delay: reduceMotion ? 0 : 0.06 }}
            whileHover={reduceMotion ? undefined : { x: -2 }}
            whileTap={reduceMotion ? undefined : { scale: 0.97 }}
          >
            <ArrowLeft size={14} /> 返回
          </motion.button>

          <h1 className="sr-only">{tab === 'login' ? '登录 Prisdvl Nexus' : '注册 Prisdvl Nexus 账号'}</h1>

          <motion.div
            className="auth-brand"
            initial={reduceMotion ? false : { opacity: 0, y: 10 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ ...soft, delay: reduceMotion ? 0 : 0.1 }}
          >
            <BrandMark wordmark tagline />
          </motion.div>

          <motion.div
            className="auth-switch"
            initial={reduceMotion ? false : { opacity: 0, y: 10 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ ...soft, delay: reduceMotion ? 0 : 0.16 }}
          >
            <RubberSegment
              items={['登录', '注册']}
              value={tab === 'login' ? '登录' : '注册'}
              animateExternal
              onChange={switchTab}
              size="md"
              radius={10}
              inset={3}
              equalSlots
              trackColor="var(--surface-muted)"
              thumbColor="var(--primary-container)"
              textColor="var(--text-2)"
              activeTextColor="var(--primary-text)"
              aria-label="登录或注册"
            />
          </motion.div>

          <div className="auth-form-slot">
            <AnimatePresence mode="popLayout" initial={false}>
              {tab === 'login' ? (
                <motion.form
                  key="login"
                  className="auth-form"
                  onSubmit={submitLogin}
                  noValidate
                  {...formMotion}
                >
                  <div className={`form-error ${formMsg ? 'active' : ''}`} role="alert">{formMsg}</div>
                  <div className={`field ${showLoginErr('username') ? 'has-error' : ''}`}>
                    <label htmlFor="li-u">用户名</label>
                    <input
                      id="li-u"
                      className="input"
                      value={login.username}
                      placeholder="请输入用户名"
                      autoComplete="username"
                      aria-invalid={showLoginErr('username')}
                      aria-describedby={showLoginErr('username') ? 'li-u-err' : undefined}
                      onBlur={() => setLoginTouched((s) => ({ ...s, username: true }))}
                      onChange={(e) => setLogin({ ...login, username: e.target.value })}
                    />
                    <p className="err" id="li-u-err">请输入用户名</p>
                  </div>
                  <div className={`field ${showLoginErr('password') ? 'has-error' : ''}`}>
                    <label htmlFor="li-p">密码</label>
                    <input
                      id="li-p"
                      className="input"
                      type="password"
                      value={login.password}
                      placeholder="请输入密码"
                      autoComplete="current-password"
                      aria-invalid={showLoginErr('password')}
                      aria-describedby={showLoginErr('password') ? 'li-p-err' : undefined}
                      onBlur={() => setLoginTouched((s) => ({ ...s, password: true }))}
                      onChange={(e) => setLogin({ ...login, password: e.target.value })}
                    />
                    <p className="err" id="li-p-err">请输入密码</p>
                  </div>
                  <p className="switch-line" style={{ margin: '0 0 14px', textAlign: 'right' }}>
                    <button type="button" onClick={() => navigate('/forgot')}>忘记密码？</button>
                  </p>
                  <motion.button
                    className="btn btn-primary btn-block"
                    type="submit"
                    disabled={busy}
                    whileHover={reduceMotion || busy ? undefined : { y: -1 }}
                    whileTap={reduceMotion || busy ? undefined : { scale: 0.985 }}
                    transition={{ duration: 0.18, ease: EASE }}
                  >
                    <LogIn size={16} /> {busy ? '登录中…' : '登录'}
                  </motion.button>
                  <p className="switch-line">
                    还没有账号？
                    <button type="button" onClick={() => switchTab('注册')}>创建账号</button>
                  </p>
                </motion.form>
              ) : (
                <motion.form
                  key="register"
                  className="auth-form"
                  onSubmit={submitRegister}
                  noValidate
                  {...formMotion}
                >
                  <div className={`form-error ${formMsg ? 'active' : ''}`} role="alert">{formMsg}</div>
                  <div className={`field ${show('username', live.username) ? 'has-error' : ''}`}>
                    <label htmlFor="rg-u">用户名</label>
                    <input
                      id="rg-u"
                      className="input"
                      value={reg.username}
                      placeholder="3–32 位字母、数字、下划线或连字符"
                      autoComplete="username"
                      aria-invalid={show('username', live.username)}
                      onBlur={() => setRegTouched((s) => ({ ...s, username: true }))}
                      onChange={(e) => setReg({ ...reg, username: e.target.value })}
                    />
                    <p className="err">用户名为 3–32 位字母、数字、下划线或连字符</p>
                  </div>
                  <div className={`field ${show('password', live.password) ? 'has-error' : ''}`}>
                    <label htmlFor="rg-p">密码</label>
                    <input
                      id="rg-p"
                      className="input"
                      type="password"
                      value={reg.password}
                      placeholder="8–64 位"
                      autoComplete="new-password"
                      aria-invalid={show('password', live.password)}
                      onBlur={() => setRegTouched((s) => ({ ...s, password: true }))}
                      onChange={(e) => setReg({ ...reg, password: e.target.value })}
                    />
                    <p className="err">密码长度需在 8–64 位之间</p>
                  </div>
                  <div className={`field ${show('password2', live.password2) ? 'has-error' : ''}`}>
                    <label htmlFor="rg-p2">确认密码</label>
                    <input
                      id="rg-p2"
                      className="input"
                      type="password"
                      value={reg.password2}
                      placeholder="再次输入密码"
                      autoComplete="new-password"
                      aria-invalid={show('password2', live.password2)}
                      onBlur={() => setRegTouched((s) => ({ ...s, password2: true }))}
                      onChange={(e) => setReg({ ...reg, password2: e.target.value })}
                    />
                    <p className="err">两次输入的密码不一致</p>
                  </div>
                  <div className={`field ${show('nickname', live.nickname) ? 'has-error' : ''}`}>
                    <label htmlFor="rg-n">昵称 <span className="field-optional">（可选）</span></label>
                    <input
                      id="rg-n"
                      className="input"
                      value={reg.nickname}
                      placeholder="展示名称，不超过 64 位"
                      autoComplete="nickname"
                      aria-invalid={show('nickname', live.nickname)}
                      onBlur={() => setRegTouched((s) => ({ ...s, nickname: true }))}
                      onChange={(e) => setReg({ ...reg, nickname: e.target.value })}
                    />
                    <p className="err">昵称不能超过 64 位</p>
                  </div>
                  <div className={`field ${show('email', live.email) ? 'has-error' : ''}`}>
                    <label htmlFor="rg-e">邮箱</label>
                    <input
                      id="rg-e"
                      className="input"
                      type="email"
                      value={reg.email}
                      placeholder="用于验证与找回密码"
                      autoComplete="email"
                      required
                      aria-invalid={show('email', live.email)}
                      onBlur={() => setRegTouched((s) => ({ ...s, email: true }))}
                      onChange={(e) => setReg({ ...reg, email: e.target.value })}
                    />
                    <p className="err">{reg.email.trim() ? '邮箱格式不正确' : '请填写邮箱'}</p>
                  </div>
                  <motion.button
                    className="btn btn-primary btn-block"
                    type="submit"
                    disabled={busy}
                    whileHover={reduceMotion || busy ? undefined : { y: -1 }}
                    whileTap={reduceMotion || busy ? undefined : { scale: 0.985 }}
                    transition={{ duration: 0.18, ease: EASE }}
                  >
                    {busy ? '创建中…' : '创建账号'}
                  </motion.button>
                  <p className="switch-line">
                    已有账号？
                    <button type="button" onClick={() => switchTab('登录')}>去登录</button>
                  </p>
                </motion.form>
              )}
            </AnimatePresence>
          </div>

          <motion.p
            className="auth-foot"
            initial={reduceMotion ? false : { opacity: 0 }}
            animate={{ opacity: 1 }}
            transition={{ ...soft, delay: reduceMotion ? 0 : 0.28 }}
          >
            登录即表示同意平台使用条款与隐私政策
          </motion.p>
        </motion.section>
      </main>
    </div>
  )
}
