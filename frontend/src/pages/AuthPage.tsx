import { useState, type FormEvent } from 'react'
import { ArrowLeft, LogIn, Sparkles } from 'lucide-react'
import CodeSlots from '@/bits/CodeSlots/CodeSlots'
import RubberSegment from '@/bits/RubberSegment/RubberSegment'
import SkipLink from '@/components/SkipLink'
import { toast } from '@/lib/toast'
import { errMessage } from '@/lib/api'
import { useLocation } from 'react-router'
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
}

export interface Credentials {
  username: string
  password: string
}

export interface RegisterCredentials extends Credentials {
  nickname?: string
  email?: string
}

export default function AuthPage({ theme, palette, onEnter, onRegister }: AuthPageProps) {
  const location = useLocation()
  const from = (location.state as { from?: string } | null)?.from
  const [tab, setTab] = useState<'login' | 'register'>('login')
  const [login, setLogin] = useState({ username: '', password: '' })
  const [reg, setReg] = useState({ username: '', password: '', password2: '', nickname: '', email: '' })
  const [errs, setErrs] = useState<Record<string, boolean>>({})
  const [formMsg, setFormMsg] = useState('')
  const [busy, setBusy] = useState(false)
  /* 校验时机：**失焦后才显示该字段错误**（输入时立即清除），提交时全量校验。
     这样用户不会刚点进表单就看到一片红。 */
  const [touched, setTouched] = useState<Record<string, boolean>>({})
  const show = (k: string, bad: boolean) => bad && (touched[k] || submitted)
  const [submitted, setSubmitted] = useState(false)
  /* 注册第二步：邮箱验证码（后端暂无接口 → 演示码 123456） */
  const [regStep, setRegStep] = useState<1 | 2>(1)
  const [codeStatus, setCodeStatus] = useState<'idle' | 'error' | 'success'>('idle')
  const DEMO_CODE = '123456'

  const submitLogin = async (e: FormEvent) => {
    e.preventDefault()
    const next = { username: !login.username.trim(), password: !login.password }
    setErrs(next)
    setFormMsg('')
    if (next.username || next.password) return
    if (busy) return
    setBusy(true)
    try {
      await onEnter(from, { username: login.username.trim(), password: login.password })
    } catch (err) {
      /* 后端错误直接展示：实测凭证错是 401 + code 2006「用户名或密码错误」，
         校验错是 400 + code 1001（message 带字段名前缀），连不上是网络错误。 */
      setFormMsg(errMessage(err))
    } finally {
      setBusy(false)
    }
  }

  /** 实时校验：不在提交时才计算，否则"失焦即报错"拿不到最新结果（这是之前的真 bug） */
  const live = {
    username: !/^[A-Za-z0-9_-]{3,32}$/.test(reg.username),
    password: reg.password.length < 8 || reg.password.length > 64,
    password2: reg.password2 !== reg.password,
    nickname: reg.nickname.length > 64,
    email: reg.email.length > 0 && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(reg.email),
  }

  const registerErrors = () => live

  const submitRegister = (e: FormEvent) => {
    e.preventDefault()
    setFormMsg('')
    const next = live
    setSubmitted(true)
    if (Object.values(next).some(Boolean)) {
      toast.error('表单还有问题', '请按提示修正后再继续')
      return
    }
    if (!reg.email) {
      toast.info('未填邮箱，跳过验证', '直接完成注册（原型演示）')
      finishRegister()
      return
    }
    setRegStep(2)
  }

  const finishRegister = async () => {
    if (!onRegister) return
    setBusy(true)
    try {
      await onRegister({
        username: reg.username.trim(),
        password: reg.password,
        nickname: reg.nickname.trim() || undefined,
        email: reg.email.trim() || undefined,
      })
      /* 注册成功后清空表单与校验痕迹：
         否则下一次进来 submitted 仍为 true，一输入就立刻报红（测试抓到的真问题）。

         ⚠️ 刻意**不切到登录标签、不导航**：注册页填完就跳走会让用户
         来不及确认"注册成功"，而且后端 register 只建号、不返令牌，
         下一步必然是回登录页输密码 —— 留在本页最符合流程预期。
         导航由 `onRegister` 的实现方决定（当前不导航）。 */
      toast.success('注册成功', '账号已创建，切到「登录」标签用刚才的密码登录')
      setReg({ username: '', password: '', password2: '', nickname: '', email: '' })
      setTouched({})
      setSubmitted(false)
      setRegStep(1)
    } catch (err) {
      setFormMsg(errMessage(err))
    } finally {
      setBusy(false)
    }
  }

  const verifyEmailCode = async (code: string) => {
    await new Promise((r) => setTimeout(r, 700))
    if (code === DEMO_CODE) {
      setCodeStatus('success')
      toast.success('邮箱验证通过', reg.email)
      setTimeout(finishRegister, 800)
    } else {
      setCodeStatus('error')
      toast.error('验证码不正确', '原型演示码为 123456')
      setTimeout(() => setCodeStatus('idle'), 900)
    }
  }

  const fillDemo = () => {
    setTab('login')
    setLogin({ username: 'demo', password: 'Demo@12345' })
    setErrs({})
    setFormMsg('')
  }

  /* 登录/注册切换：改用与侧栏同款的橡皮分段（选中态用容器色 + 强调色文字，保证 AA） */
  const switchTab = (label: string) => {
    const id = label === '注册' ? 'register' : 'login'
    if (id === tab) return
    setTab(id); setErrs({}); setFormMsg(''); setTouched({}); setSubmitted(false); setRegStep(1)
  }

  return (
    <div className="auth-wrap">
      <WavesBg theme={theme} palette={palette} hero />
      <SkipLink />

      <main id="main-content" tabIndex={-1}>
      <section className="auth-card" aria-label="登录或注册">
        {/* 页面需要唯一的 h1；视觉上由品牌区承担，这里给辅助技术一个明确标题 */}
        <h1 className="sr-only">{tab === 'login' ? '登录 Prisdvl Nexus' : '注册 Prisdvl Nexus 账号'}</h1>
        <BrandMark wordmark tagline />

        <div style={{ display: 'flex', justifyContent: 'center', marginBottom: 20 }}>
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
        </div>

        {tab === 'login' ? (
          <form onSubmit={submitLogin} noValidate>
            <div className={`form-error ${formMsg ? 'active' : ''}`} role="alert">{formMsg}</div>
            <div className={`field ${live.username ? 'has-error' : ''}`}>
              <label htmlFor="li-u">用户名</label>
              <input id="li-u" className="input" value={login.username} placeholder="请输入用户名"
                onChange={(e) => setLogin({ ...login, username: e.target.value })} />
              <p className="err">请输入用户名</p>
            </div>
            <div className={`field ${live.password ? 'has-error' : ''}`}>
              <label htmlFor="li-p">密码</label>
              <input id="li-p" className="input" type="password" value={login.password} placeholder="请输入密码"
                onChange={(e) => setLogin({ ...login, password: e.target.value })} />
              <p className="err">请输入密码</p>
            </div>
            <button className="btn btn-primary" style={{ width: '100%' }} type="submit" disabled={busy}>
              <LogIn size={16} /> {busy ? '登录中…' : '登录'}
            </button>
            <button className="btn btn-ghost" style={{ width: '100%' }} type="button" onClick={fillDemo}>
              <Sparkles size={15} /> 一键使用演示账号 <span className="kbd">demo</span>
            </button>
            <p className="switch-line">还没有账号？<button type="button" onClick={() => setTab('register')}>创建账号</button></p>
          </form>
        ) : (
          <form onSubmit={submitRegister} noValidate>
            <div className={`form-error ${formMsg ? 'active' : ''}`} role="alert">{formMsg}</div>
            <div className={`field ${show('username', live.username) ? 'has-error' : ''}`} data-touched={show('username', live.username) ? '' : undefined}>
              <label htmlFor="rg-u">用户名</label>
              <input id="rg-u" className="input" value={reg.username} onBlur={() => setTouched((s) => ({ ...s, username: true }))} placeholder="3–32 位字母、数字、下划线或连字符"
                onChange={(e) => setReg({ ...reg, username: e.target.value })} />
              <p className="err">用户名为 3–32 位字母、数字、下划线或连字符</p>
            </div>
            <div className={`field ${show('password', live.password) ? 'has-error' : ''}`} data-touched={show('password', live.password) ? '' : undefined}>
              <label htmlFor="rg-p">密码</label>
              <input id="rg-p" className="input" type="password" value={reg.password} onBlur={() => setTouched((s) => ({ ...s, password: true }))} placeholder="8–64 位"
                onChange={(e) => setReg({ ...reg, password: e.target.value })} />
              <p className="err">密码长度需在 8–64 位之间</p>
            </div>
            <div className={`field ${show('password2', live.password2) ? 'has-error' : ''}`} data-touched={show('password2', live.password2) ? '' : undefined}>
              <label htmlFor="rg-p2">确认密码</label>
              <input id="rg-p2" className="input" type="password" value={reg.password2} onBlur={() => setTouched((s) => ({ ...s, password2: true }))} placeholder="再次输入密码"
                onChange={(e) => setReg({ ...reg, password2: e.target.value })} />
              <p className="err">两次输入的密码不一致</p>
            </div>
            <div className={`field ${show('nickname', live.nickname) ? 'has-error' : ''}`} data-touched={show('nickname', live.nickname) ? '' : undefined}>
              <label htmlFor="rg-n">昵称 <span style={{ color: 'var(--text-3)', fontWeight: 400 }}>（可选）</span></label>
              <input id="rg-n" className="input" value={reg.nickname} onBlur={() => setTouched((s) => ({ ...s, nickname: true }))} placeholder="展示名称，不超过 64 位"
                onChange={(e) => setReg({ ...reg, nickname: e.target.value })} />
              <p className="err">昵称不能超过 64 位</p>
            </div>
            <div className={`field ${show('email', live.email) ? 'has-error' : ''}`} data-touched={show('email', live.email) ? '' : undefined}>
              <label htmlFor="rg-e">邮箱 <span style={{ color: 'var(--text-3)', fontWeight: 400 }}>（可选）</span></label>
              <input id="rg-e" className="input" type="email" value={reg.email} onBlur={() => setTouched((s) => ({ ...s, email: true }))} placeholder="用于找回密码等"
                onChange={(e) => setReg({ ...reg, email: e.target.value })} />
              <p className="err">邮箱格式不正确</p>
            </div>
            {regStep === 1 ? (
              <button className="btn btn-primary" style={{ width: '100%' }} type="submit" disabled={busy}>
                下一步{reg.email ? '：验证邮箱' : '：完成注册'}
              </button>
            ) : (
              <>
                <p style={{ margin: '0 0 10px', fontSize: 13.5, color: 'var(--text-2)' }}>
                  我们向 <strong>{reg.email}</strong> 发送了 6 位验证码
                </p>
                <p className="hint" style={{ marginTop: 0 }}>
                  原型说明：后端暂无验证码接口，演示码 <code style={{ padding: '1px 6px', border: '1px solid var(--border)', borderRadius: 5, background: 'var(--surface-muted)' }}>123456</code>
                </p>
                <div style={{ display: 'flex', justifyContent: 'center', padding: '10px 0 4px' }}>
                  <CodeSlots
                    length={6}
                    status={codeStatus}
                    autoFocus
                    accentColor="var(--primary)"
                    inkColor="var(--text-2)"
                    slotColor="var(--surface-muted)"
                    digitColor="var(--on-primary)"
                    dangerColor="var(--danger)"
                    onChange={() => { if (codeStatus !== 'idle') setCodeStatus('idle') }}
                    onComplete={verifyEmailCode}
                    ariaLabel="注册邮箱验证码"
                  />
                </div>
                <div style={{ display: 'flex', gap: 10 }}>
                  <button className="btn btn-ghost" type="button" style={{ flex: 1 }} onClick={() => setRegStep(1)}>
                    <ArrowLeft size={14} /> 返回修改
                  </button>
                  <button className="btn btn-ghost" type="button" style={{ flex: 1 }} onClick={() => toast.task('验证码已重发', '原型演示：码固定为 123456')}>
                    重新发送
                  </button>
                </div>
              </>
            )}
            <p className="switch-line">已有账号？<button type="button" onClick={() => setTab('login')}>去登录</button></p>
          </form>
        )}

        <p className="auth-foot">与真实注册接口契约一致（用户名 3–32 / 密码 8–64）</p>
      </section>
      </main>
    </div>
  )
}