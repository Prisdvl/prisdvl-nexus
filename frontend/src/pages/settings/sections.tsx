import { useEffect, useState, type FormEvent } from 'react'
import { Download, KeyRound, Laptop, LogOut, MailCheck, Save, ShieldAlert } from 'lucide-react'
import Counter from '@/bits/Counter/Counter'
import CodeSlots from '@/bits/CodeSlots/CodeSlots'
import RubberSegment from '@/bits/RubberSegment/RubberSegment'
import SpringCheck from '@/bits/SpringCheck/SpringCheck'
import SquishSwitch from '@/bits/SquishSwitch/SquishSwitch'
import { feedbackStore, useFeedback } from '@/lib/feedback'
import { toolPolicy } from '@/lib/toolPolicy'
import { download, toCSV } from '@/lib/download'
import StatusMark from '@/bits/StatusMark/StatusMark'
import Modal from '@/components/Modal'
import { AppearanceControls } from '@/components/Appearance'
import { toast } from '@/lib/toast'
import { api, errMessage, USE_MOCK, type AuthUser, type UserQuota, type PageResult, type KnowledgeBase, type DocumentDetail, type Conversation, type BackendMessage, type BackendAgent, type UserLlmConfig, type UserEmbeddingConfig, type UserSession, type UserStorage, type TotpSetup, type TotpStatus, type UserNotifyChannel, type NotifyChannelType, type NotifyChannelInput } from '@/lib/api'
import { useAuth } from '@/lib/auth'
import { LANDING_KEYS, readAccountPreferences, writeAccountPreferences, type LandingKey } from '@/lib/preferences'
import ActivityHeatmap, { type HeatmapDay } from '@/components/ui/activity-heatmap'
import { mockUsageDays, toHeatmapDays } from '@/components/ui/activity-heatmap-utils/usage-heatmap'
import { Skeleton } from '@/components/Skeleton'
import { writeLocale, normalizeLocale, useLocale } from '@/lib/locale'
import { fmtBytes } from '@/lib/bytes'
import { sectionsCopy } from '@/lib/sectionsCopy'
import { appCopy } from '@/lib/appCopy'
import { pagesCopy } from '@/lib/pagesCopy'
import Loc from '@/components/Loc'

async function fetchAllPages<T>(load: (page: number) => Promise<PageResult<T>>): Promise<T[]> {
  const first = await load(1)
  const rows = [...first.list]
  for (let page = 2; page <= first.pages; page++) rows.push(...(await load(page)).list)
  return rows
}
/** 每个分区统一的标题 + 说明（GitHub 设置页也是这个结构：大标题 + 一句说明 + 内容） */
export function SectionHead({ title, desc }: { title: string; desc: string }) {
  return (
    <header className="settings-head">
      <h2>{title}</h2>
      <p>{desc}</p>
    </header>
  )
}

/* ---------------- 个人 · 公开资料 ---------------- */
export function ProfileSection() {
  const { locale } = useLocale()
  const s = sectionsCopy(locale)
  const [user, setUser] = useState<AuthUser | null>(null)
  const [nickname, setNickname] = useState('')
  const [email, setEmail] = useState('')
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  useEffect(() => {
    let alive = true
      const local = localStorage.getItem('nx-mock-profile')
      const request = USE_MOCK
      ? Promise.resolve(local ? JSON.parse(local) as AuthUser : { id: 'mock-user', username: 'demo', nickname: '演示用户', email: null, emailVerified: false, roles: ['USER'] })
      : api.auth.me()
    request.then((u) => { if (alive) { setUser(u); setNickname(u.nickname ?? ''); setEmail(u.email ?? '') } })
      .catch((e) => { if (alive) setError(errMessage(e)) }).finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [])
  const save = async (e: FormEvent) => {
    e.preventDefault()
    if (!user || saving) return
    setSaving(true); setError('')
    try {
      const nextEmail = email.trim()
      if (!nextEmail) {
        setError(s.profile.errEmailEmpty)
        return
      }
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(nextEmail)) {
        setError(s.profile.errEmailFormat)
        return
      }
      const prevNorm = (user.email ?? '').trim().toLowerCase()
      const nextNorm = nextEmail.toLowerCase()
      const emailChanged = prevNorm !== nextNorm
      const updated = USE_MOCK
        ? { ...(user as AuthUser), nickname: nickname || null, email: nextEmail, emailVerified: emailChanged ? false : user.emailVerified }
        : await api.users.updateProfile({ nickname, email: nextEmail })
      if (USE_MOCK) localStorage.setItem('nx-mock-profile', JSON.stringify(updated))
      setUser(updated); setNickname(updated.nickname ?? ''); setEmail(updated.email ?? '')
      toast.success(s.profile.savedTitle, USE_MOCK ? s.profile.savedMock : (emailChanged ? s.profile.savedEmailChanged : s.profile.savedSynced))
    } catch (e) { const message = errMessage(e); setError(message); toast.error(s.profile.saveFailTitle, message) }
    finally { setSaving(false) }
  }
  return <section className="panel panel-pad">
    <SectionHead title={s.profile.title} desc={s.profile.desc} />
    {loading ? <p className="hint" role="status"><Loc>{s.profile.loading}</Loc></p> : <form onSubmit={save}>
      {error && <p className="form-error active" role="alert">{error}</p>}
      <div className="field"><label htmlFor="ac-username"><Loc>{s.profile.usernameLabel}</Loc></label><input id="ac-username" className="input" value={user?.username ?? ''} disabled /><p className="hint"><Loc>{s.profile.usernameHint}</Loc></p></div>
      <div className="field"><label htmlFor="ac-nick"><Loc>{s.profile.nicknameLabel}</Loc></label><input id="ac-nick" className="input" placeholder={s.profile.nicknamePh} value={nickname} maxLength={64} onChange={(e) => setNickname(e.target.value)} /></div>
      <div className="field"><label htmlFor="ac-email"><Loc>{s.profile.emailLabel}</Loc></label><input id="ac-email" className="input" type="email" placeholder={s.profile.emailPh} value={email} maxLength={128} required onChange={(e) => setEmail(e.target.value)} /><p className="hint"><Loc>{s.profile.emailHint(user?.email ? (user.emailVerified ? s.profile.statusVerified : s.profile.statusUnverified) : s.profile.statusUnbound)}</Loc></p></div>
      <button className="btn btn-primary" type="submit" disabled={saving}><Save size={15} /> {saving ? <Loc>{s.profile.saving}</Loc> : <Loc>{s.profile.save}</Loc>}</button>
    </form>}
  </section>
}
/* ---------------- 个人 · 模型 Key（自定义对话模型凭据） ---------------- */
export function ModelKeySection() {
  const { locale } = useLocale()
  const s = sectionsCopy(locale)
  const [cfg, setCfg] = useState<UserLlmConfig | null>(null)
  const [baseUrl, setBaseUrl] = useState('')
  const [apiKey, setApiKey] = useState('')
  const [modelName, setModelName] = useState('')
  const [enabled, setEnabled] = useState(false)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    let alive = true
    if (USE_MOCK) {
      setCfg({ baseUrl: '', modelName: '', enabled: false, hasApiKey: false, apiKeyMasked: null })
      setLoading(false)
      return () => { alive = false }
    }
    api.users.llmConfig()
      .then((c) => {
        if (!alive) return
        setCfg(c)
        setBaseUrl(c.baseUrl ?? '')
        setModelName(c.modelName ?? '')
        setEnabled(c.enabled)
      })
      .catch((e) => { if (alive) setError(errMessage(e)) })
      .finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [])

  const save = async (e: FormEvent) => {
    e.preventDefault()
    if (saving) return
    setSaving(true); setError('')
    try {
      const updated = await api.users.saveLlmConfig({
        baseUrl: baseUrl.trim() || null,
        apiKey: apiKey.trim(),
        modelName: modelName.trim() || null,
        enabled,
      })
      setCfg(updated)
      // 保存成功后清空输入框里的 key：服务端已存，下次留空即"保持不变"
      setApiKey('')
      toast.success(enabled ? s.modelKey.enabledTitle : s.modelKey.savedTitle,
        enabled ? s.modelKey.enabledBody : s.modelKey.savedBody)
    } catch (e2) {
      const message = errMessage(e2); setError(message); toast.error(s.modelKey.saveFailTitle, message)
    } finally { setSaving(false) }
  }

  return <section className="panel panel-pad">
    <SectionHead title={s.modelKey.title} desc={s.modelKey.desc} />
    {loading ? <p className="hint" role="status"><Loc>{s.modelKey.loading}</Loc></p> : <form onSubmit={save}>
      {error && <p className="form-error active" role="alert">{error}</p>}
      <div className="field">
        <label htmlFor="mk-base"><Loc>{s.modelKey.baseLabel}</Loc></label>
        <input id="mk-base" className="input" placeholder="https://api.deepseek.com" value={baseUrl}
          onChange={(e) => setBaseUrl(e.target.value)} maxLength={500} />
        <p className="hint"><Loc>{s.modelKey.baseHint}</Loc></p>
      </div>
      <div className="field">
        <label htmlFor="mk-key"><Loc>{s.modelKey.keyLabel}</Loc></label>
        <input id="mk-key" className="input" type="password" autoComplete="off"
          placeholder={cfg?.hasApiKey ? s.modelKey.keyPhSet(cfg.apiKeyMasked) : 'sk-…'}
          value={apiKey} onChange={(e) => setApiKey(e.target.value)} maxLength={320} />
        <p className="hint"><Loc>{s.modelKey.keyHint}</Loc></p>
      </div>
      <div className="field">
        <label htmlFor="mk-model"><Loc>{s.modelKey.modelLabel}</Loc></label>
        <input id="mk-model" className="input" placeholder="deepseek-chat" value={modelName}
          onChange={(e) => setModelName(e.target.value)} maxLength={200} />
        <p className="hint"><Loc>{s.modelKey.modelHint}</Loc></p>
      </div>
      <div className="settings-inline" style={{ margin: '4px 0 16px' }}>
        <div>
          <strong style={{ fontSize: 13.5 }}><Loc>{s.modelKey.enableLabel}</Loc></strong>
          <p className="hint" style={{ margin: '4px 0 0' }}><Loc>{s.modelKey.enableHint}</Loc></p>
        </div>
        <SquishSwitch checked={enabled} onChange={setEnabled} ariaLabel={s.modelKey.enableAria} />
      </div>
      <button className="btn btn-primary" type="submit" disabled={saving}>
        <Save size={15} /> {saving ? <Loc>{s.modelKey.saving}</Loc> : <Loc>{s.modelKey.save}</Loc>}
      </button>
      <p className="hint" style={{ marginTop: 12, marginBottom: 0 }}>
        <Loc>{s.modelKey.embedHint}</Loc>
      </p>
    </form>}
  </section>
}

/* ---------------- 个人 · 嵌入模型（BYOK：用户自带的嵌入端点与 Key） ---------------- */
export function EmbeddingKeySection() {
  const { locale } = useLocale()
  const s = sectionsCopy(locale)
  const [cfg, setCfg] = useState<UserEmbeddingConfig | null>(null)
  const [provider, setProvider] = useState('')
  const [modelName, setModelName] = useState('')
  const [baseUrl, setBaseUrl] = useState('')
  const [apiKey, setApiKey] = useState('')
  const [enabled, setEnabled] = useState(false)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')

  // 白名单来自后端（ai.byok.embedding.allowed-models），前端不硬编码一份副本——
  // 硬编码必然与后端漂移，而这里的白名单是"能不能写向量"的硬约束。
  const allowed = cfg?.allowedModels ?? []
  // CUSTOM_PROVIDER 是前端约定值：后端不要求 provider 属于白名单名单，
  // 它只要求「白名单外」时必须带 Base URL + Key 并探针通过（见 CustomEmbeddingProbe）。
  const CUSTOM_PROVIDER = 'custom'
  const providers = [
    ...Array.from(new Set(allowed.map((m) => m.split(':')[0]))),
    ...(cfg?.customEndpointAllowed ? [CUSTOM_PROVIDER] : []),
  ]
  const isCustom = provider === CUSTOM_PROVIDER
  const modelsOf = (p: string) => allowed.filter((m) => m.startsWith(`${p}:`)).map((m) => m.slice(p.length + 1))

  useEffect(() => {
    let alive = true
    if (USE_MOCK) {
      setCfg({
        provider: null, modelName: null, baseUrl: null, dimensions: 1024, enabled: false,
        hasApiKey: false, apiKeyMasked: null,
        allowedModels: ['bailian:text-embedding-v4'], requiredDimensions: 1024,
        customEndpointAllowed: true,
      })
      setProvider('bailian'); setModelName('text-embedding-v4')
      setLoading(false)
      return () => { alive = false }
    }
    api.users.embeddingConfig()
      .then((c) => {
        if (!alive) return
        setCfg(c)
        const firstProvider = c.provider ?? c.allowedModels[0]?.split(':')[0] ?? ''
        setProvider(firstProvider)
        setModelName(c.modelName ?? c.allowedModels.find((m) => m.startsWith(`${firstProvider}:`))?.slice(firstProvider.length + 1) ?? '')
        setBaseUrl(c.baseUrl ?? '')
        setEnabled(c.enabled)
      })
      .catch((e) => { if (alive) setError(errMessage(e)) })
      .finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [])

  // 换供应商时把模型名对齐到该供应商白名单里的第一项，避免提交出白名单外的组合；
  // 切到「自定义端点」时清空模型名，让用户自己填
  const onProviderChange = (next: string) => {
    setProvider(next)
    if (next === 'custom') { setModelName(''); return }
    if (!modelsOf(next).includes(modelName)) setModelName(modelsOf(next)[0] ?? '')
  }

  const save = async (e: FormEvent) => {
    e.preventDefault()
    if (saving) return
    setSaving(true); setError('')
    try {
      const updated = await api.users.saveEmbeddingConfig({
        provider: provider.trim() || null,
        modelName: modelName.trim() || null,
        baseUrl: baseUrl.trim() || null,
        apiKey: apiKey.trim(),
        dimensions: cfg?.requiredDimensions ?? null,
        enabled,
      })
      setCfg(updated)
      // 保存成功后清空输入框里的 key：服务端已加密存好，下次留空即"保持不变"
      setApiKey('')
      toast.success(enabled ? s.embeddingKey.enabledTitle : s.embeddingKey.savedTitle,
        enabled ? s.embeddingKey.enabledBody : s.embeddingKey.savedBody)
    } catch (e2) {
      const message = errMessage(e2); setError(message); toast.error(s.embeddingKey.saveFailTitle, message)
    } finally { setSaving(false) }
  }

  return <section className="panel panel-pad">
    <SectionHead title={s.embeddingKey.title} desc={s.embeddingKey.desc} />
    {loading ? <p className="hint" role="status"><Loc>{s.embeddingKey.loading}</Loc></p> : <form onSubmit={save}>
      {error && <p className="form-error active" role="alert">{error}</p>}
      <div className="field">
        <label htmlFor="ek-provider"><Loc>{s.embeddingKey.providerLabel}</Loc></label>
        <select id="ek-provider" className="input" value={provider} onChange={(e) => onProviderChange(e.target.value)}>
          {providers.map((p) => (
            <option key={p} value={p}>{p === 'custom' ? s.embeddingKey.customOption : p}</option>
          ))}
        </select>
        <p className="hint"><Loc>{isCustom ? s.embeddingKey.customHint : s.embeddingKey.providerHint}</Loc></p>
      </div>
      <div className="field">
        <label htmlFor={isCustom ? 'ek-model-text' : 'ek-model'}>
          <Loc>{isCustom ? s.embeddingKey.modelLabelCustom : s.embeddingKey.modelLabel}</Loc>
        </label>
        {isCustom
          ? <input id="ek-model-text" className="input" placeholder="text-embedding-3-small"
              value={modelName} onChange={(e) => setModelName(e.target.value)} maxLength={200} />
          : <select id="ek-model" className="input" value={modelName} onChange={(e) => setModelName(e.target.value)}>
              {modelsOf(provider).map((m) => <option key={m} value={m}>{m}</option>)}
            </select>}
        <p className="hint"><Loc>{s.embeddingKey.modelHint}</Loc></p>
      </div>
      <div className="field">
        <label htmlFor="ek-base"><Loc>{s.embeddingKey.baseLabel}</Loc></label>
        <input id="ek-base" className="input" placeholder="https://dashscope.aliyuncs.com/compatible-mode/v1"
          value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} maxLength={500} />
        <p className="hint"><Loc>{s.embeddingKey.baseHint}</Loc></p>
      </div>
      <div className="field">
        <label htmlFor="ek-key"><Loc>{s.embeddingKey.keyLabel}</Loc></label>
        <input id="ek-key" className="input" type="password" autoComplete="off"
          placeholder={cfg?.hasApiKey ? s.embeddingKey.keyPhSet(cfg.apiKeyMasked) : 'sk-…'}
          value={apiKey} onChange={(e) => setApiKey(e.target.value)} maxLength={320} />
        <p className="hint"><Loc>{s.embeddingKey.keyHint}</Loc></p>
      </div>
      {/* 维度由平台与向量列决定，不是用户可选项：只给结论，不给假输入框 */}
      <div className="field">
        <span className="field-label">{s.embeddingKey.dimLabel}</span>
        <p className="field-static" id="ek-dim-text"><Loc>{s.embeddingKey.dimHint(cfg?.requiredDimensions ?? 1024)}</Loc></p>
      </div>
      <div className="settings-inline" style={{ margin: '4px 0 16px' }}>
        <div>
          <strong style={{ fontSize: 13.5 }}><Loc>{s.embeddingKey.enableLabel}</Loc></strong>
          <p className="hint" style={{ margin: '4px 0 0' }}><Loc>{s.embeddingKey.enableHint}</Loc></p>
        </div>
        <SquishSwitch checked={enabled} onChange={setEnabled} ariaLabel={s.embeddingKey.enableAria} />
      </div>
      <button className="btn btn-primary" type="submit" disabled={saving}>
        <Save size={15} /> {saving ? <Loc>{s.embeddingKey.saving}</Loc> : <Loc>{s.embeddingKey.save}</Loc>}
      </button>
      <p className="hint" style={{ marginTop: 12, marginBottom: 0 }}>
        <Loc>{s.embeddingKey.frozenHint}</Loc>
      </p>
    </form>}
  </section>
}

/* ---------------- 个人 · 账号偏好 ---------------- */
export function PrefsSection() {
  const { locale } = useLocale()
  const s = sectionsCopy(locale)
  const [initial] = useState(readAccountPreferences)
  const [lang, setLang] = useState(initial.language)
  const [tz, setTz] = useState(initial.timezone)
  const [landing, setLanding] = useState<LandingKey>(initial.landing)
  const p = pagesCopy(locale).common
  const landingLabel: Record<LandingKey, string> = {
    workbench: p.landingWorkbench,
    chat: p.landingChat,
    kb: p.landingKb,
  }
  const save = () => {
    writeAccountPreferences({ language: lang, timezone: tz, landing })
    writeLocale(normalizeLocale(lang))
    toast.success(s.prefs.savedTitle, s.prefs.savedBody)
  }
  return <section className="panel panel-pad">
    <SectionHead title={s.prefs.title} desc={s.prefs.desc} />
    <div className="field">
      <label htmlFor="ac-lang"><Loc>{s.prefs.langLabel}</Loc></label>
      {/* 语言切换与其它偏好项统一成下拉框；选中即生效（与保存按钮解耦，和原来的按钮行为一致） */}
      <select
        id="ac-lang"
        className="input"
        value={lang}
        onChange={(e) => { setLang(e.target.value); writeLocale(normalizeLocale(e.target.value)) }}
      >
        <option value="zh" lang="zh-CN">简体中文</option>
        <option value="en" lang="en">English</option>
      </select>
      <p className="hint"><Loc>{s.prefs.langHint}</Loc></p>
    </div>
    <div className="field"><label htmlFor="ac-tz"><Loc>{s.prefs.tzLabel}</Loc></label><select id="ac-tz" className="input" value={tz} onChange={(e) => setTz(e.target.value)}><option>Asia/Shanghai</option><option>UTC</option></select><p className="hint"><Loc>{s.prefs.tzHint}</Loc></p></div>
    {/* 值是与语言无关的 code；label 才跟语言走 —— 老版本存的中文由 readAccountPreferences 迁移 */}
    <div className="field"><label htmlFor="ac-landing"><Loc>{s.prefs.landingLabel}</Loc></label><select id="ac-landing" className="input" value={landing} onChange={(e) => setLanding(e.target.value as LandingKey)}>{LANDING_KEYS.map((k) => <option key={k} value={k}>{landingLabel[k]}</option>)}</select></div>
    <button className="btn btn-primary" type="button" onClick={save}><Save size={15} /> <Loc>{s.prefs.save}</Loc></button>
  </section>
}
/* ---------------- 个人 · 外观 ---------------- */
export function AppearanceSection() {
  const { locale } = useLocale()
  const s = sectionsCopy(locale)
  return (
    <section className="panel panel-pad">
      <SectionHead title={s.appearance.title} desc={s.appearance.desc} />
      <AppearanceControls />
      <p className="hint" style={{ marginTop: 14, marginBottom: 0 }}>
        <Loc>{s.appearance.footHint}</Loc>
      </p>
    </section>
  )
}

/* ---------------- 访问与安全 · 密码与登录 ---------------- */
export function SecuritySection() {
  const { locale } = useLocale()
  const s = sectionsCopy(locale)
  const [allowedTools, setAllowedTools] = useState<string[]>(() => toolPolicy.list())
  const [oldPassword, setOldPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const { logout } = useAuth()
  const [pwOpen, setPwOpen] = useState(false)
  const changePassword = async (e: FormEvent) => {
    e.preventDefault()
    if (saving) return
    if (USE_MOCK) { setError(s.security.errMock); return }
    if (newPassword !== confirmPassword) { setError(s.security.errMismatch); return }
    if (newPassword.length < 8 || newPassword.length > 64) { setError(s.security.errLength); return }
    setSaving(true); setError('')
    try {
      await api.users.changePassword(oldPassword, newPassword)
      toast.success(s.security.okTitle, s.security.okBody)
      logout()
    } catch (e) { const message = errMessage(e); setError(message); toast.error(s.security.failTitle, message) }
    finally { setSaving(false) }
  }
  return <section className="panel panel-pad">
    <SectionHead title={s.security.title} desc={s.security.desc} />
    {/* 默认只给一行摘要 + 展开按钮：一进设置页就被三个密码框怼脸是反人类的 */}
    <div className="settings-inline" style={{ marginTop: 0 }}>
      <div>
        <strong style={{ fontSize: 13.5 }}><Loc>{s.security.pwLabel}</Loc></strong>
        <p className="hint" style={{ margin: '4px 0 0' }}><Loc>{s.security.pwHint}</Loc></p>
      </div>
      <button
        className="btn btn-ghost btn-sm"
        type="button"
        aria-expanded={pwOpen}
        aria-controls="ac-pw-form"
        onClick={() => setPwOpen((v) => !v)}
      >
        <KeyRound size={14} /> <Loc>{pwOpen ? s.security.pwCollapse : s.security.pwExpand}</Loc>
      </button>
    </div>
    {pwOpen && <form id="ac-pw-form" onSubmit={changePassword} style={{ marginTop: 14 }}>
      {error && <p className="form-error active" role="alert">{error}</p>}
      <div className="field"><label htmlFor="ac-cur-pw"><Loc>{s.security.curPwLabel}</Loc></label><input id="ac-cur-pw" className="input" type="password" autoComplete="current-password" value={oldPassword} onChange={(e) => setOldPassword(e.target.value)} required /></div>
      <div className="field"><label htmlFor="ac-new-pw"><Loc>{s.security.newPwLabel}</Loc></label><input id="ac-new-pw" className="input" type="password" autoComplete="new-password" minLength={8} maxLength={64} value={newPassword} onChange={(e) => setNewPassword(e.target.value)} required /><p className="hint"><Loc>{s.security.newPwHint}</Loc></p></div>
      <div className="field"><label htmlFor="ac-new-pw2"><Loc>{s.security.confirmPwLabel}</Loc></label><input id="ac-new-pw2" className="input" type="password" autoComplete="new-password" value={confirmPassword} onChange={(e) => setConfirmPassword(e.target.value)} required /></div>
      <button className="btn btn-primary" type="submit" disabled={USE_MOCK || saving || !oldPassword || !newPassword || !confirmPassword}><KeyRound size={15} /> {USE_MOCK ? <Loc>{s.security.submitDisabledMock}</Loc> : saving ? <Loc>{s.security.submitting}</Loc> : <Loc>{s.security.submit}</Loc>}</button>
    </form>}
    <hr className="divider" />
    <TwoFactorPanel s={s} />
    <hr className="divider" />
    <div className="export-card" style={{ marginTop: 0 }}><div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
      <span style={{ flex: 1, minWidth: 200 }}><strong style={{ fontSize: 13.5 }}><Loc>{s.security.toolsLabel}</Loc></strong><p className="hint" style={{ marginTop: 4 }}><Loc>{allowedTools.length === 0 ? s.security.toolsNone : s.security.toolsList(allowedTools)}</Loc></p></span>
      {allowedTools.map((name) => <button key={name} className="btn btn-ghost btn-sm" type="button" aria-label={s.security.revokeAria(name)} onClick={() => { toolPolicy.revoke(name); setAllowedTools(toolPolicy.list()); toast.info(s.security.revokedTitle, s.security.revokedBody(name)) }}><Loc>{s.security.revokeBtn(name)}</Loc></button>)}
      {allowedTools.length > 0 && <button className="btn btn-ghost btn-sm" type="button" aria-label={s.security.revokeAllAria} onClick={() => { toolPolicy.revokeAll(); setAllowedTools([]); toast.info(s.security.revokedAllTitle) }}><Loc>{s.security.revokeAllBtn}</Loc></button>}
    </div></div>
  </section>
}
/* ---------------- 访问与安全 · 两步验证（TOTP） ---------------- */

/**
 * 两步验证面板。
 *
 * <p>四段状态机，每一段都对应服务端的一个真实分支，不是纯视觉切换：
 * <ol>
 *   <li>未开启 → 点「开启」调 setup，拿到候选密钥（服务端此时<b>没有</b>把账号标成需要验证码）；</li>
 *   <li>待校验 → 用户把密钥加进验证器，回填 6 位码调 enable，服务端才置 enabled=1；</li>
 *   <li>刚开启 → 一次性展示 10 枚恢复码（服务端只存哈希，关掉面板就再也看不到）；</li>
 *   <li>已开启 → 只能关闭（换绑 = 先关再开），关闭要当前密码。</li>
 * </ol>
 *
 * <p>密钥/恢复码都<b>不</b>写进 localStorage 或 React 持久状态以外的任何地方：
 * 它们是等价于第二把钥匙的东西。
 */
function TwoFactorPanel({ s }: { s: ReturnType<typeof sectionsCopy> }) {
  const [status, setStatus] = useState<TotpStatus | null>(null)
  const [setup, setSetup] = useState<TotpSetup | null>(null)
  const [code, setCode] = useState('')
  const [recovery, setRecovery] = useState<string[] | null>(null)
  const [disableOpen, setDisableOpen] = useState(false)
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    if (USE_MOCK) return
    let alive = true
    api.users.totpStatus()
      .then((v) => { if (alive) setStatus(v) })
      /* 读不到状态不弹红条：设置页整页别因为这一个接口挂掉。
         退化成"未开启"，用户点按钮时才看到真正的错误。 */
      .catch(() => { if (alive) setStatus({ enabled: false, recoveryCodesLeft: 0, confirmedAt: null }) })
    return () => { alive = false }
  }, [])

  const run = async (fn: () => Promise<void>) => {
    if (busy) return
    setBusy(true)
    setError('')
    try { await fn() } catch (e) { setError(errMessage(e)) } finally { setBusy(false) }
  }

  const startSetup = () => run(async () => {
    const v = await api.users.setupTotp()
    setSetup(v)
    setRecovery(null)
    setCode('')
  })

  const confirmEnable = () => run(async () => {
    if (!/^\d{6}$/.test(code.trim())) { setError(s.security.totpErrCode); return }
    const v = await api.users.saveTotp(code.trim())
    setRecovery(v.recoveryCodes)
    setSetup(null)
    setCode('')
    setStatus({ enabled: true, recoveryCodesLeft: v.recoveryCodes.length, confirmedAt: new Date().toISOString() })
    toast.success(s.security.totpEnableOkTitle, s.security.totpEnableOkBody)
  })

  const closeDisable = (open: boolean) => {
    setDisableOpen(open)
    if (!open) { setPassword(''); setError('') }
  }

  const confirmDisable = () => run(async () => {
    if (!password) { setError(s.security.totpErrPassword); return }
    await api.users.disableTotp(password)
    setStatus({ enabled: false, recoveryCodesLeft: 0, confirmedAt: null })
    closeDisable(false)
    toast.success(s.security.totpDisableOkTitle, s.security.totpDisableOkBody)
  })

  const copy = (text: string) => {
    void navigator.clipboard?.writeText(text).then(() => toast.info(s.security.totpCopied, text.slice(0, 24)))
  }

  return <>
    <div className="settings-inline" style={{ marginTop: 0 }}>
      <div>
        <strong style={{ fontSize: 13.5 }}><Loc>{s.security.twoFactorLabel}</Loc></strong>{' '}
        <span className="badge" data-tone={status?.enabled ? 'success' : undefined}>
          {status?.enabled ? s.security.totpOn : s.security.totpOff}
        </span>
        <p className="hint" style={{ margin: '4px 0 0' }}><Loc>{s.security.twoFactorHint}</Loc></p>
        {status?.enabled && (
          <p className="hint" style={{ margin: '2px 0 0' }}>
            {s.security.totpRecoveryLeft(status.recoveryCodesLeft)}
            {status.confirmedAt ? ` · ${s.security.totpConfirmedAt(status.confirmedAt.slice(0, 10))}` : ''}
          </p>
        )}
      </div>
      <button
        className="btn btn-ghost btn-sm"
        type="button"
        disabled={busy || USE_MOCK || status === null}
        aria-expanded={setup !== null || disableOpen}
        onClick={() => (status?.enabled ? closeDisable(!disableOpen) : startSetup())}
      >
        <ShieldAlert size={14} />{' '}
        {status === null
          ? s.security.totpLoading
          : status.enabled
            ? s.security.totpDisableLabel
            : s.security.totpStart}
      </button>
    </div>

    {error && <p className="form-error active" role="alert">{error}</p>}

    {setup && (
      <div className="export-card" style={{ marginTop: 14 }}>
        <strong style={{ fontSize: 13.5 }}>{s.security.totpSetupTitle}</strong>
        <p className="hint" style={{ marginTop: 4 }}>{s.security.totpSecretHint}</p>
        <div className="field" style={{ marginTop: 10 }}>
          <label htmlFor="ac-totp-secret">{s.security.totpSecretLabel}</label>
          <div style={{ display: 'flex', gap: 8 }}>
            <input id="ac-totp-secret" className="input" readOnly value={setup.secret} onFocus={(e) => e.currentTarget.select()} />
            <button className="btn btn-ghost btn-sm" type="button" onClick={() => copy(setup.secret)}>{s.security.totpCopy}</button>
          </div>
        </div>
        <details style={{ marginTop: 8 }}>
          <summary className="hint" style={{ cursor: 'pointer' }}>{s.security.totpUriLabel}</summary>
          <p className="hint" style={{ wordBreak: 'break-all', marginTop: 6 }}>{setup.otpauthUri}</p>
        </details>
        <div className="field" style={{ marginTop: 10 }}>
          <label htmlFor="ac-totp-code">{s.security.totpCodeLabel}</label>
          <input
            id="ac-totp-code"
            className="input"
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={6}
            placeholder={s.security.totpCodePh}
            value={code}
            onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
          />
        </div>
        <div style={{ display: 'flex', gap: 8 }}>
          <button className="btn btn-primary btn-sm" type="button" disabled={busy || code.length !== 6} onClick={confirmEnable}>
            <ShieldAlert size={14} /> {s.security.totpConfirm}
          </button>
          <button className="btn btn-ghost btn-sm" type="button" onClick={() => { setSetup(null); setCode(''); setError('') }}>
            {s.security.totpCancel}
          </button>
        </div>
      </div>
    )}

    {recovery && (
      <div className="export-card" style={{ marginTop: 14, borderColor: 'var(--warning)' }}>
        <strong style={{ fontSize: 13.5 }}>{s.security.totpRecoveryTitle}</strong>
        <p className="hint" style={{ marginTop: 4 }}>{s.security.totpRecoveryHint}</p>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(130px, 1fr))', gap: 6, margin: '10px 0' }}>
          {recovery.map((c) => (
            <code key={c} style={{ fontFamily: 'var(--font-mono)', fontSize: 12.5, padding: '4px 6px', background: 'var(--surface-muted)', borderRadius: 6 }}>{c}</code>
          ))}
        </div>
        <p className="hint" style={{ marginBottom: 8 }}>{s.security.totpRecoveryOnce}</p>
        <div style={{ display: 'flex', gap: 8 }}>
          <button className="btn btn-ghost btn-sm" type="button" onClick={() => copy(recovery.join('\n'))}>{s.security.totpCopy}</button>
          <button className="btn btn-primary btn-sm" type="button" onClick={() => setRecovery(null)}>{s.security.totpRecoveryDone}</button>
        </div>
      </div>
    )}

    {disableOpen && (
      <div className="export-card" style={{ marginTop: 14 }}>
        <strong style={{ fontSize: 13.5 }}>{s.security.totpDisableLabel}</strong>
        <p className="hint" style={{ marginTop: 4 }}>{s.security.totpDisableHint}</p>
        <div className="field" style={{ marginTop: 10 }}>
          <label htmlFor="ac-totp-pw">{s.security.totpDisablePassword}</label>
          <input
            id="ac-totp-pw"
            className="input"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </div>
        <div style={{ display: 'flex', gap: 8 }}>
          <button className="btn btn-primary btn-sm" type="button" disabled={busy || !password} onClick={confirmDisable}>
            {s.security.totpDisableCta}
          </button>
          <button className="btn btn-ghost btn-sm" type="button" onClick={() => closeDisable(false)}>{s.security.totpCancel}</button>
        </div>
      </div>
    )}
  </>
}

/* ---------------- 访问与安全 · 邮箱验证 ---------------- */
const EMAIL_CODE_COOLDOWN_SEC = 60

export function EmailSection() {
  const { locale } = useLocale()
  const s = sectionsCopy(locale)
  const [user, setUser] = useState<AuthUser | null>(null)
  const [loading, setLoading] = useState(true)
  const [code, setCode] = useState('')
  const [slotsStatus, setSlotsStatus] = useState<'idle' | 'error' | 'success'>('idle')
  const [sending, setSending] = useState(false)
  const [verifying, setVerifying] = useState(false)
  const [error, setError] = useState('')
  const [cooldownLeft, setCooldownLeft] = useState(0)

  useEffect(() => {
    let alive = true
    const local = localStorage.getItem('nx-mock-profile')
    const request = USE_MOCK
      ? Promise.resolve(local ? JSON.parse(local) as AuthUser : { id: 'mock-user', username: 'demo', nickname: '演示用户', email: 'demo@example.com', emailVerified: false, roles: ['USER'] })
      : api.auth.me()
    request.then((u) => { if (alive) setUser(u) })
      .catch((e) => { if (alive) setError(errMessage(e)) })
      .finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [])

  useEffect(() => {
    if (cooldownLeft <= 0) return
    const t = window.setTimeout(() => setCooldownLeft((s) => Math.max(0, s - 1)), 1000)
    return () => window.clearTimeout(t)
  }, [cooldownLeft])

  const sendCode = async () => {
    if (sending || cooldownLeft > 0 || !user?.email) return
    setSending(true); setError(''); setSlotsStatus('idle')
    try {
      if (USE_MOCK) {
        await new Promise((r) => setTimeout(r, 300))
        toast.success(s.email.sentMockTitle, s.email.sentMockBody)
      } else {
        await api.users.sendEmailVerification()
        toast.success(s.email.sentTitle, s.email.sentBody(user.email))
      }
      setCooldownLeft(EMAIL_CODE_COOLDOWN_SEC)
      setCode('')
    } catch (e) {
      const message = errMessage(e)
      setError(message)
      toast.error(s.email.sendFailTitle, message)
      // 服务端冷却：前端倒计时与提示对齐，但仍以服务端为准
      if (message.includes('频繁') || message.includes('后再试')) setCooldownLeft(EMAIL_CODE_COOLDOWN_SEC)
    } finally { setSending(false) }
  }

  const submitCode = async (override?: string) => {
    const value = (override ?? code).replace(/\D/g, '')
    if (verifying || value.length < 4) return
    setVerifying(true); setError(''); setSlotsStatus('idle')
    try {
      if (USE_MOCK) {
        await new Promise((r) => setTimeout(r, 300))
        const updated = { ...(user as AuthUser), emailVerified: true }
        localStorage.setItem('nx-mock-profile', JSON.stringify(updated))
        setUser(updated)
        setSlotsStatus('success')
        toast.success(s.email.verifiedMockTitle)
      } else {
        const updated = await api.users.verifyEmail(value)
        setUser(updated)
        setSlotsStatus('success')
        toast.success(s.email.verifiedTitle, updated.email ?? undefined)
      }
      setCode('')
    } catch (e) {
      const message = errMessage(e)
      setError(message)
      setSlotsStatus('error')
      toast.error(s.email.verifyFailTitle, message)
    } finally { setVerifying(false) }
  }

  return <section className="panel panel-pad">
    <SectionHead title={s.email.title} desc={s.email.desc} />
    {loading ? <p className="hint" role="status"><Loc>{s.email.loading}</Loc></p> : <>
      {error && <p className="form-error active" role="alert">{error}</p>}
      {!user?.email ? (
        <div className="settings-inline">
          <div>
            <strong style={{ fontSize: 13.5 }}><Loc>{s.email.noEmailLabel}</Loc></strong>
            <p className="hint" style={{ margin: '4px 0 0' }}><Loc>{s.email.noEmailHint}</Loc></p>
          </div>
        </div>
      ) : user.emailVerified ? (
        <div className="settings-inline">
          <div>
            <strong style={{ fontSize: 13.5 }}>{user.email}</strong>
            <p className="hint" style={{ margin: '4px 0 0' }}><Loc>{s.email.verifiedHint}</Loc></p>
          </div>
          <span className="badge" data-tone="success"><Loc>{s.email.verifiedBadge}</Loc></span>
        </div>
      ) : (
        <>
          <div className="settings-inline" style={{ marginBottom: 16 }}>
            <div>
              <strong style={{ fontSize: 13.5 }}>{user.email}</strong>
              <p className="hint" style={{ margin: '4px 0 0' }}><Loc>{s.email.unverifiedHint}</Loc></p>
            </div>
            <span className="badge" data-tone="warn"><Loc>{s.email.unverifiedBadge}</Loc></span>
          </div>
          <div className="field">
            <label><Loc>{s.email.codeLabel}</Loc></label>
            <CodeSlots
              length={6}
              value={code}
              onChange={(v) => { setCode(v); if (slotsStatus !== 'idle') setSlotsStatus('idle') }}
              onComplete={(v) => { void submitCode(v) }}
              status={slotsStatus}
              disabled={verifying || sending}
              ariaLabel={s.email.codeAria}
              accentColor="var(--accent, #0d9488)"
              inkColor="var(--text-1, #18181b)"
              slotColor="var(--surface-2, #f4f4f5)"
              digitColor="var(--text-1, #18181b)"
              dangerColor="var(--danger, #dc2626)"
              slotSize={40}
              gap={8}
              radius={10}
            />
            <p className="hint"><Loc>{s.email.codeHint}</Loc></p>
          </div>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 10, marginTop: 8 }}>
            <button
              className="btn btn-ghost"
              type="button"
              disabled={sending || cooldownLeft > 0}
              onClick={() => { void sendCode() }}
            >
              <MailCheck size={15} />
              {sending ? <Loc>{s.email.sending}</Loc> : cooldownLeft > 0 ? <Loc>{s.email.resendIn(cooldownLeft)}</Loc> : <Loc>{s.email.sendCode}</Loc>}
            </button>
            <button
              className="btn btn-primary"
              type="button"
              disabled={verifying || code.replace(/\D/g, '').length < 4}
              onClick={() => { void submitCode() }}
            >
              {verifying ? <Loc>{s.email.verifying}</Loc> : <Loc>{s.email.submitCode}</Loc>}
            </button>
          </div>
        </>
      )}
    </>}
  </section>
}
/* ---------------- 访问与安全 · 会话与设备 ---------------- */
export function SessionsSection() {
  const { locale } = useLocale()
  const s = sectionsCopy(locale)
  const { logout } = useAuth()
  const [sessions, setSessions] = useState<UserSession[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [busyId, setBusyId] = useState<string | null>(null)
  const [revokingAll, setRevokingAll] = useState(false)

  const reload = () => {
    if (USE_MOCK) {
      setSessions([{
        id: 'mock-current',
        device: '当前浏览器',
        ip: '127.0.0.1',
        lastUsedAt: new Date().toISOString(),
        createdAt: new Date().toISOString(),
        current: true,
      }])
      setLoading(false)
      return
    }
    setLoading(true)
    setError('')
    api.users.sessions()
      .then(setSessions)
      .catch((e) => setError(errMessage(e)))
      .finally(() => setLoading(false))
  }

  useEffect(() => { reload() }, [])

  const revokeOne = async (id: string, isCurrent: boolean) => {
    if (USE_MOCK || busyId) return
    setBusyId(id)
    setError('')
    try {
      await api.users.revokeSession(id)
      if (isCurrent) {
        await logout()
        return
      }
      toast.success(s.sessions.revokedTitle)
      reload()
    } catch (e) {
      setError(errMessage(e))
    } finally {
      setBusyId(null)
    }
  }

  const revokeAll = async () => {
    if (USE_MOCK || revokingAll) return
    setRevokingAll(true)
    setError('')
    try {
      await api.users.revokeAllSessions()
      toast.success(s.sessions.revokedAllTitle)
      await logout()
    } catch (e) {
      setError(errMessage(e))
    } finally {
      setRevokingAll(false)
    }
  }

  const fmt = (iso: string) => {
    try { return new Date(iso).toLocaleString() } catch { return iso }
  }

  return <section className="panel panel-pad">
    <SectionHead title={s.sessions.title} desc={s.sessions.desc} />
    {loading && <p className="hint" role="status"><Loc>{s.sessions.loading}</Loc></p>}
    {error && <p className="form-error active" role="alert">{error}</p>}
    {!loading && sessions.length === 0 && (
      <p className="hint"><Loc>{s.sessions.empty}</Loc></p>
    )}
    {sessions.map((row) => (
      <div className="list-row" key={row.id} style={{ gap: 10, alignItems: 'center' }}>
        <Laptop size={15} style={{ color: 'var(--text-3)', flexShrink: 0 }} />
        <div style={{ minWidth: 0, flex: 1 }}>
          <div style={{ fontSize: 13.5, display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
            <span>{row.device || s.sessions.deviceFallback}</span>
            {row.current && <span className="badge" data-tone="success"><Loc>{s.sessions.currentBadge}</Loc></span>}
          </div>
          <p className="hint" style={{ margin: '2px 0 0' }}>
            <Loc>{s.sessions.rowMeta(row.ip, fmt(row.lastUsedAt), fmt(row.createdAt))}</Loc>
          </p>
        </div>
        <button
          type="button"
          className="btn btn-ghost btn-sm"
          disabled={!!busyId || revokingAll}
          onClick={() => void revokeOne(row.id, row.current)}
        >
          {busyId === row.id ? <Loc>{s.sessions.revoking}</Loc> : <Loc>{s.sessions.revoke}</Loc>}
        </button>
      </div>
    ))}
    <div style={{ display: 'flex', gap: 8, marginTop: 12, flexWrap: 'wrap' }}>
      <button type="button" className="btn btn-ghost btn-sm" disabled={loading || !!busyId} onClick={reload}><Loc>{s.sessions.refresh}</Loc></button>
      <button
        type="button"
        className="btn btn-ghost btn-sm"
        disabled={USE_MOCK || revokingAll || !!busyId || sessions.length === 0}
        onClick={() => void revokeAll()}
      >
        {revokingAll ? <Loc>{s.sessions.revokingAll}</Loc> : <Loc>{s.sessions.revokeAll}</Loc>}
      </button>
    </div>
    <p className="hint" style={{ marginBottom: 0, marginTop: 10 }}>
      <Loc>{s.sessions.footHint}</Loc>
    </p>
  </section>
}
/* ---------------- 数据与用量 · 用量配额 ---------------- */

export function UsageSection() {
  const { locale } = useLocale()
  const s = sectionsCopy(locale)
  const [quota, setQuota] = useState<UserQuota | null>(null)
  const [kbCount, setKbCount] = useState<number | null>(null)
  const [storage, setStorage] = useState<UserStorage | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [heatDays, setHeatDays] = useState<HeatmapDay[]>([])
  const [pickedDay, setPickedDay] = useState<string | null>(null)
  useEffect(() => {
    let alive = true
    if (USE_MOCK) {
      setQuota({ requestUsed: 182, requestLimit: 500, tokenUsed: 48200, tokenLimit: 200000, resetAt: new Date(Date.now() + 86400000).toISOString() })
      setKbCount(6); setLoading(false)
      setStorage({ usedBytes: 268435456, documentCount: 34, knowledgeBaseCount: 6, totalLimitBytes: 5368709120, perKnowledgeBaseLimitBytes: 1073741824 })
      setHeatDays(mockUsageDays(365))
      return () => { alive = false }
    }
    Promise.all([api.users.quota(), api.knowledge.list(1, 1), api.users.storage()])
      .then(([q, k, st]) => { if (alive) { setQuota(q); setKbCount(k.total); setStorage(st) } })
      .catch((e) => { if (alive) setError(errMessage(e)) })
      .finally(() => { if (alive) setLoading(false) })
    /* 热力图数据单独拉：统计接口挂了不该影响配额展示（反过来说配额挂了也不该拖没热力图） */
    api.stats.summary()
      .then((summary) => { if (alive) setHeatDays(toHeatmapDays(summary.heat, 365)) })
      .catch(() => { if (alive) setHeatDays(toHeatmapDays([], 365)) })
    return () => { alive = false }
  }, [])
  const pct = (used: number, limit: number) => limit > 0 ? `${Math.min(100, Math.round(used / limit * 100))}%` : '0%'
  const heatLabels = {
    title: s.usage.heatTitle,
    less: s.usage.heatLess,
    more: s.usage.heatMore,
    summary: s.usage.heatSummary,
    dayLabel: s.usage.heatDayLabel,
    dayValue: s.usage.heatDayValue,
    hint: s.usage.heatHint,
  }
  return <section className="panel panel-pad">
    <SectionHead title={s.usage.title} desc={USE_MOCK ? s.usage.descMock : s.usage.descReal} />
    {loading && <p className="hint" role="status"><Loc>{s.usage.loading}</Loc></p>}
    {error && <p className="form-error active" role="alert">{error}</p>}
    {quota && <>
      <div className="usage-row"><div className="top"><span><Loc>{s.usage.reqLabel}</Loc></span><span><strong>{quota.requestUsed}</strong> / {quota.requestLimit}</span></div><div className="track"><div className="fill" style={{ width: pct(quota.requestUsed, quota.requestLimit) }} /></div></div>
      <div className="usage-row"><div className="top"><span><Loc>{s.usage.tokenLabel}</Loc></span><span><strong>{quota.tokenUsed.toLocaleString()}</strong> / {quota.tokenLimit.toLocaleString()}</span></div><div className="track"><div className="fill" style={{ width: pct(quota.tokenUsed, quota.tokenLimit) }} /></div></div>
      <p className="hint"><Loc>{s.usage.resetAt(new Date(quota.resetAt).toLocaleString())}</Loc></p>
    </>}
    {kbCount !== null && <div className="usage-row"><div className="top"><span><Loc>{s.usage.kbLabel}</Loc></span><span><strong>{kbCount}</strong></span></div></div>}
    {storage && <div className="usage-row">
      <div className="top">
        <span><Loc>{s.usage.storageLabel}</Loc></span>
        <span>
          <strong>{fmtBytes(storage.usedBytes)}</strong>
          {' / '}
          {storage.totalLimitBytes > 0 ? fmtBytes(storage.totalLimitBytes) : <Loc>{s.usage.storageUnlimited}</Loc>}
        </span>
      </div>
      {storage.totalLimitBytes > 0 && <div className="track"><div className="fill" style={{ width: pct(storage.usedBytes, storage.totalLimitBytes) }} /></div>}
      {storage.totalLimitBytes > 0 && storage.usedBytes >= storage.totalLimitBytes && (
        <p className="form-error active" role="alert" style={{ margin: '8px 0 0' }}><Loc>{s.usage.storageFull}</Loc></p>
      )}
      <p className="hint">
        {storage.perKnowledgeBaseLimitBytes > 0
          ? <Loc>{s.usage.storagePerKbHint(fmtBytes(storage.perKnowledgeBaseLimitBytes))}</Loc>
          : <Loc>{s.usage.storageUnlimited}</Loc>}
      </p>
    </div>}
    {/* 热力图：固定高度容器，数据没到时也有骨架高度，避免设置页 CLS */}
    <div className="usage-heat">
      {heatDays.length > 0
        ? <ActivityHeatmap days={heatDays} labels={heatLabels} selected={pickedDay} onSelect={(d) => setPickedDay(d?.date ?? null)} />
        : <div className="usage-heat__skeleton" role="status" aria-label={s.usage.heatTitle}>
            <Skeleton w="38%" h={13} />
            <Skeleton w="100%" h={97} radius={4} />
            <Skeleton w="26%" h={11} />
          </div>}
    </div>
    <p className="hint" style={{ marginBottom: 0 }}><Loc>{s.usage.heatFootHint}</Loc></p>
    <p className="hint" style={{ margin: '10px 0 0' }}><Loc>{s.usage.footHint}</Loc></p>
  </section>
}
/* ---------------- 数据与用量 · 数据导出 ---------------- */
export function ExportSection() {
  const { locale } = useLocale()
  const s = sectionsCopy(locale)
  const [format, setFormat] = useState('JSON')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const { entries } = useFeedback()

  const exportAll = async () => {
    if (busy || USE_MOCK) return
    setBusy(true); setError('')
    try {
      const [user, quota, kbs, agents, conversations] = await Promise.all([
        api.auth.me(), api.users.quota(),
        fetchAllPages((p) => api.knowledge.list(p, 100)),
        fetchAllPages((p) => api.agents.list(p, 100)),
        fetchAllPages((p) => api.conversations.list(p, 100)),
      ])
      const knowledge: { kb: KnowledgeBase; docs: DocumentDetail[] }[] = []
      for (const kb of kbs) {
        const docs = await fetchAllPages((p) => api.documents.list(kb.id, p, 100))
        const full: DocumentDetail[] = []
        for (const doc of docs) full.push(await api.documents.get(kb.id, doc.id))
        knowledge.push({ kb, docs: full })
      }
      const chats: { conversation: Conversation; messages: BackendMessage[] }[] = []
      for (const conversation of conversations) {
        const messages = await fetchAllPages((p) => api.messages.list(conversation.id, p, 100))
        chats.push({ conversation, messages })
      }
      const snapshot = { exportedAt: new Date().toISOString(), user, quota, knowledgeBases: knowledge, conversations: chats, agents, localFeedback: entries }
      const stamp = new Date().toISOString().slice(0, 10)
      if (format === 'JSON') {
        download('prisdvl-backup-' + stamp + '.json', JSON.stringify(snapshot, null, 2), 'application/json')
      } else if (format === 'CSV') {
        const rows: (string | number)[][] = [['账号', '', user.username, user.email ?? '', user.roles.join('|'), '']]
        for (const a of agents) rows.push(['Agent', '', a.name, a.status, a.modelRole ?? '', ''])
        for (const item of knowledge) for (const doc of item.docs) rows.push(['文档', item.kb.name, doc.fileName, doc.status, doc.chunkCount, doc.updatedAt])
        for (const item of chats) rows.push(['会话', '', item.conversation.title ?? '', item.conversation.mode, item.messages.length, item.conversation.updatedAt])
        download('prisdvl-backup-' + stamp + '.csv', toCSV(['类型','知识库','名称','状态/模式','切片/消息数','更新时间'], rows), 'text/csv;charset=utf-8')
      } else {
        const parts = ['# Prisdvl-Nexus 导出\n\n导出时间：' + snapshot.exportedAt + '\n账号：' + user.username + '\n']
        for (const item of knowledge) {
          parts.push('\n# 知识库：' + item.kb.name + '\n\n' + (item.kb.description ?? '') + '\n')
          for (const doc of item.docs) parts.push('\n## ' + (doc.title ?? doc.fileName) + '\n\n' + (doc.content ?? '（原始正文不可用）') + '\n')
        }
        parts.push('\n# Agent\n')
        for (const a of agents) parts.push('\n## ' + a.name + '\n\n状态：' + a.status + '\n\n' + (a.description ?? '') + '\n')
        parts.push('\n# 配额\n\n今日请求：' + quota.requestUsed + '/' + quota.requestLimit + '；Token：' + quota.tokenUsed + '/' + quota.tokenLimit + '\n')
        for (const item of chats) {
          parts.push('\n# 会话：' + (item.conversation.title ?? '未命名') + '\n\n')
          for (const msg of item.messages) parts.push('**' + msg.role + '**\n\n' + (msg.content ?? '') + '\n')
        }
        download('prisdvl-backup-' + stamp + '.md', parts.join('\n'), 'text/markdown;charset=utf-8')
      }
      toast.success(s.export.doneTitle, s.export.doneBody(knowledge.length, chats.length, agents.length))
    } catch (e) {
      const message = errMessage(e); setError(message); toast.error(s.export.failTitle, message)
    } finally { setBusy(false) }
  }

  return <section className="panel panel-pad">
    <SectionHead title={s.export.title} desc={s.export.desc} />
    {error && <p className="form-error active" role="alert">{error}</p>}
    <div className="field">
      <label><Loc>{s.export.formatLabel}</Loc></label>
      <RubberSegment items={['Markdown', 'JSON', 'CSV']} value={format} animateExternal onChange={setFormat}
        size="md" radius={10} inset={3} equalSlots trackColor="var(--surface-muted)" thumbColor="var(--primary-container)"
        textColor="var(--text-2)" activeTextColor="var(--primary-text)" aria-label={s.export.formatAria} />
      <p className="hint"><Loc>{format === 'Markdown' ? s.export.formatMarkdown : format === 'JSON' ? s.export.formatJson : s.export.formatCsv}</Loc></p>
    </div>
    <p className="hint"><Loc>{s.export.localHint}</Loc></p>
    <button className="btn btn-primary" type="button" disabled={busy || USE_MOCK} onClick={() => void exportAll()}>
      <Download size={15} /> {USE_MOCK ? <Loc>{s.export.submitMock}</Loc> : busy ? <Loc>{s.export.submitting}</Loc> : <Loc>{s.export.submit}</Loc>}
    </button>
    <div className="export-card">
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
        <span style={{ flex: 1, minWidth: 220 }}><strong style={{ fontSize: 13.5 }}><Loc>{s.export.feedbackLabel}</Loc></strong><p className="hint" style={{ marginTop: 4 }}><Loc>{s.export.feedbackCount(entries.length)}</Loc></p></span>
        <button className="btn btn-ghost btn-sm" type="button" aria-label={s.export.feedbackJsonAria} onClick={() => download('nx-feedback.json', feedbackStore.toJSON(), 'application/json')}><Download size={13} /> JSON</button>
        <button className="btn btn-ghost btn-sm" type="button" aria-label={s.export.feedbackCsvAria} onClick={() => download('nx-feedback.csv', feedbackStore.toCSV(), 'text/csv;charset=utf-8')}><Download size={13} /> CSV</button>
        <button className="btn btn-ghost btn-sm" type="button" aria-label={s.export.feedbackClearAria} onClick={() => { feedbackStore.clear(); toast.info(s.export.clearedTitle, s.export.clearedBody) }}><Loc>{s.export.clearBtn}</Loc></button>
      </div>
    </div>
  </section>
}
/* ---------------- 通知 · 通知偏好 ---------------- */
const NOTIFY_KEY = 'nx-notify-preferences'
function readNotifyPrefs() {
  try {
    const value = JSON.parse(localStorage.getItem(NOTIFY_KEY) ?? '{}') as { done?: boolean; cite?: boolean; weekly?: boolean }
    return { done: value.done ?? true, cite: value.cite ?? false, weekly: value.weekly ?? true }
  } catch { return { done: true, cite: false, weekly: true } }
}
export function NotifySection() {
  const { locale } = useLocale()
  const s = sectionsCopy(locale)
  const initial = readNotifyPrefs()
  const [done, setDone] = useState(initial.done)
  const [cite, setCite] = useState(initial.cite)
  const [weekly, setWeekly] = useState(initial.weekly)
  const save = () => {
    localStorage.setItem(NOTIFY_KEY, JSON.stringify({ done, cite, weekly }))
    toast.success(s.notify.savedTitle, s.notify.savedBody)
  }
  return <section className="panel panel-pad">
    <SectionHead title={s.notify.title} desc={s.notify.desc} />
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <div className="settings-inline"><div><strong style={{ fontSize: 13.5 }}><Loc>{s.notify.doneLabel}</Loc></strong><p className="hint" style={{ margin: '4px 0 0' }}><Loc>{s.notify.doneHint}</Loc></p></div><SquishSwitch checked={done} onChange={setDone} ariaLabel={s.notify.doneAria} /></div>
      <hr className="divider" />
      <div className="settings-inline"><div><strong style={{ fontSize: 13.5 }}><Loc>{s.notify.citeLabel}</Loc></strong><p className="hint" style={{ margin: '4px 0 0' }}><Loc>{s.notify.citeHint}</Loc></p></div><SquishSwitch checked={cite} onChange={setCite} ariaLabel={s.notify.citeAria} /></div>
      <hr className="divider" />
      <div className="settings-inline"><div><strong style={{ fontSize: 13.5 }}><Loc>{s.notify.weeklyLabel}</Loc></strong><p className="hint" style={{ margin: '4px 0 0' }}><Loc>{s.notify.weeklyHint}</Loc></p></div><SquishSwitch checked={weekly} onChange={setWeekly} ariaLabel={s.notify.weeklyAria} /></div>
    </div>
    <button className="btn btn-ghost" type="button" style={{ marginTop: 16 }} onClick={save}><Save size={15} /> <Loc>{s.notify.save}</Loc></button>
    <hr className="divider" />
    <NotifyChannels s={s} />
  </section>
}

/* ---------------- 通知偏好 · 通知通道（服务端，Webhook / 邮件） ---------------- */
function NotifyChannels({ s }: { s: ReturnType<typeof sectionsCopy> }) {
  const [channels, setChannels] = useState<UserNotifyChannel[] | null>(null)
  const [editing, setEditing] = useState<UserNotifyChannel | 'new' | null>(null)
  const [name, setName] = useState('')
  const [type, setType] = useState<NotifyChannelType>('WEBHOOK')
  const [target, setTarget] = useState('')
  const [secret, setSecret] = useState('')
  const [events, setEvents] = useState<string[]>(['import.done'])
  const [enabled, setEnabled] = useState(true)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState('')

  const load = () => {
    api.users.notifyChannels()
      .then((list) => setChannels(list))
      .catch(() => { setChannels([]) })
  }
  useEffect(() => { if (!USE_MOCK) load() }, [])

  const resetForm = () => {
    setName(''); setType('WEBHOOK'); setTarget(''); setSecret('')
    setEvents(['import.done']); setEnabled(true); setMsg('')
  }
  const startEdit = (ch: UserNotifyChannel | 'new') => {
    setEditing(ch)
    setMsg('')
    if (ch === 'new') { resetForm(); return }
    setName(ch.name); setType(ch.type); setTarget(ch.target); setSecret('')
    setEvents(ch.events); setEnabled(ch.enabled)
  }
  const toggleEvent = (code: string) => {
    setEvents((prev) => prev.includes(code) ? prev.filter((v) => v !== code) : [...prev, code])
  }
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setMsg('')
    const body: NotifyChannelInput = { name, type, target, events, enabled, secret: secret || null }
    try {
      if (editing === 'new') await api.users.createNotifyChannel(body)
      else if (editing) await api.users.updateNotifyChannel(editing.id, body)
      setEditing(null)
      resetForm()
      load()
    } catch (err) {
      setMsg(`${s.notify.saveFailed}：${errMessage(err)}`)
    } finally { setBusy(false) }
  }
  const sendTest = async (ch: UserNotifyChannel) => {
    setMsg('')
    try {
      const updated = await api.users.testNotifyChannel(ch.id)
      // 投递结果写在返回的通道上（lastStatus/lastError/lastAt）；不合并的话列表还会停在"还没有投递记录"
      setChannels((prev) => prev?.map((item) => (item.id === updated.id ? updated : item)) ?? prev)
      /* 接口在投递失败时仍然回 200（失败写进 lastStatus），所以不能只看是否抛错——
         否则对方的 Webhook 404 了，界面还在报"测试消息已发送"。 */
      if (updated.lastStatus === 'FAILED') toast.error(s.notify.testFailTitle, updated.lastError ?? '')
      else toast.success(s.notify.testOkTitle, ch.target)
    } catch (err) {
      toast.error(s.notify.testFailTitle, errMessage(err))
      load()
    }
  }
  const remove = async (ch: UserNotifyChannel) => {
    try {
      await api.users.deleteNotifyChannel(ch.id)
      toast.success(s.notify.deletedTitle, s.notify.deletedBody)
      if (editing && editing !== 'new' && editing.id === ch.id) setEditing(null)
      load()
    } catch (err) { toast.error(s.notify.saveFailed, errMessage(err)) }
  }
  const relTime = (when: string | null) => {
    if (!when) return s.notify.lastNever
    const d = new Date(when)
    return Number.isNaN(d.getTime()) ? s.notify.lastNever : d.toLocaleString()
  }

  return <div>
    <div className="settings-inline" style={{ marginTop: 8 }}>
      <div>
        <strong style={{ fontSize: 13.5 }}><Loc>{s.notify.channelsTitle}</Loc></strong>
        <p className="hint" style={{ margin: '4px 0 0' }}><Loc>{s.notify.channelsDesc}</Loc></p>
      </div>
      <button className="btn btn-sm" type="button" id="notify-add" onClick={() => startEdit('new')}><Loc>{s.notify.add}</Loc></button>
    </div>

    {channels === null && <p className="hint"><Loc>{s.notify.channelsEmpty}</Loc></p>}
    {channels !== null && channels.length === 0 && editing === null && <p className="hint"><Loc>{s.notify.channelsEmpty}</Loc></p>}

    {channels !== null && channels.length > 0 && <div style={{ marginTop: 10, display: "flex", flexDirection: "column" }}>
      {channels.map((ch) => <div className="settings-inline" key={ch.id} style={{ padding: '8px 0' }}>
        <div>
          <strong style={{ fontSize: 13 }}>{ch.name}</strong>
          <span className="badge" data-tone={ch.enabled ? 'success' : undefined} style={{ marginLeft: 8 }}>{ch.type === 'WEBHOOK' ? s.notify.typeWebhook : s.notify.typeEmail}</span>
          <p className="hint" style={{ margin: '4px 0 0', wordBreak: 'break-all' }}>{ch.target}</p>
          <p className="hint" style={{ margin: '2px 0 0' }}>
            {ch.lastStatus === 'OK' && <Loc>{s.notify.lastOk(relTime(ch.lastAt))}</Loc>}
            {ch.lastStatus === 'FAILED' && <Loc>{s.notify.lastFail(relTime(ch.lastAt), ch.lastError ?? '')}</Loc>}
            {!ch.lastStatus && <Loc>{s.notify.lastNever}</Loc>}
          </p>
        </div>
        <div style={{ display: 'flex', gap: 6 }}>
          <button className="btn btn-ghost btn-sm" type="button" onClick={() => sendTest(ch)}><Loc>{s.notify.testCta}</Loc></button>
          <button className="btn btn-ghost btn-sm" type="button" onClick={() => startEdit(ch)}><Loc>{s.notify.saveCta}</Loc></button>
          <button className="btn btn-ghost btn-sm" type="button" onClick={() => remove(ch)}><Loc>{s.notify.deleteCta}</Loc></button>
        </div>
      </div>)}
    </div>}

    {editing !== null && <form onSubmit={submit} style={{ marginTop: 12, display: 'flex', flexDirection: 'column', gap: 10 }}>
      <strong style={{ fontSize: 13 }}><Loc>{s.notify.addTitle}</Loc></strong>
      <div className="settings-inline">
        <label htmlFor="notify-name"><Loc>{s.notify.nameLabel}</Loc></label>
        <input id="notify-name" className="input" value={name} placeholder={s.notify.namePh} onChange={(e) => setName(e.target.value)} required maxLength={64} />
      </div>
      <div className="settings-inline">
        <label htmlFor="notify-type"><Loc>{s.notify.typeLabel}</Loc></label>
        <select id="notify-type" className="input" value={type} onChange={(e) => setType(e.target.value as NotifyChannelType)}>
          <option value="WEBHOOK">{s.notify.typeWebhook}</option>
          <option value="EMAIL">{s.notify.typeEmail}</option>
        </select>
      </div>
      <div className="settings-inline">
        <label htmlFor="notify-target"><Loc>{s.notify.targetLabel}</Loc></label>
        <input id="notify-target" className="input" value={target} placeholder={type === 'WEBHOOK' ? s.notify.targetPhWebhook : s.notify.targetPhEmail} onChange={(e) => setTarget(e.target.value)} required />
      </div>
      {type === 'WEBHOOK' && <div className="settings-inline">
        <label htmlFor="notify-secret"><Loc>{s.notify.secretLabel}</Loc></label>
        <div>
          <input id="notify-secret" className="input" type="password" value={secret} onChange={(e) => setSecret(e.target.value)} autoComplete="new-password" />
          <p className="hint" style={{ margin: '4px 0 0' }}><Loc>{s.notify.secretHint}</Loc></p>
          {editing !== 'new' && <p className="hint" style={{ margin: '2px 0 0' }}><Loc>{s.notify.secretKeepHint}</Loc></p>}
        </div>
      </div>}
      <div className="settings-inline">
        <label><Loc>{s.notify.eventsLabel}</Loc></label>
        <div style={{ display: 'flex', gap: 14 }}>
          <label style={{ display: "inline-flex", alignItems: "center", gap: 6, fontSize: 13 }}><input type="checkbox" id="notify-ev-done" checked={events.includes('import.done')} onChange={() => toggleEvent('import.done')} /> <Loc>{s.notify.eventImportDone}</Loc></label>
          <label style={{ display: "inline-flex", alignItems: "center", gap: 6, fontSize: 13 }}><input type="checkbox" id="notify-ev-failed" checked={events.includes('import.failed')} onChange={() => toggleEvent('import.failed')} /> <Loc>{s.notify.eventImportFailed}</Loc></label>
        </div>
      </div>
      <div className="settings-inline">
        <label htmlFor="notify-enabled"><Loc>{s.notify.enabledLabel}</Loc></label>
        <SquishSwitch checked={enabled} onChange={setEnabled} ariaLabel={s.notify.enabledLabel} />
      </div>
      {msg && <p className="form-error active">{msg}</p>}
      <div style={{ display: 'flex', gap: 8 }}>
        <button className="btn btn-primary btn-sm" type="submit" disabled={busy}><Loc>{s.notify.saveCta}</Loc></button>
        <button className="btn btn-ghost btn-sm" type="button" onClick={() => { setEditing(null); resetForm() }}><Loc>{s.notify.cancel}</Loc></button>
      </div>
    </form>}
  </div>
}
/* ---------------- 危险区 ---------------- */
export function DangerSection() {
  const { locale } = useLocale()
  const s = sectionsCopy(locale)
  return <section className="panel panel-pad panel-danger">
    <SectionHead title={s.danger.title} desc={s.danger.desc} />
    <div className="settings-inline"><div><strong style={{ fontSize: 13.5 }}><Loc>{s.danger.permanentLabel}</Loc></strong><p className="hint" style={{ margin: '4px 0 0' }}><Loc>{s.danger.permanentHint}</Loc></p></div><button className="btn btn-danger btn-sm" type="button" disabled aria-disabled="true"><Loc>{s.danger.unavailable}</Loc></button></div>
    <p className="hint" style={{ marginBottom: 0, marginTop: 12 }}><Loc>{s.danger.footHint}</Loc></p>
  </section>
}
