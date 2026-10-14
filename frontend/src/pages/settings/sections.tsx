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
import { api, errMessage, USE_MOCK, type AuthUser, type UserQuota, type PageResult, type KnowledgeBase, type DocumentDetail, type Conversation, type BackendMessage, type BackendAgent, type UserLlmConfig } from '@/lib/api'
import { useAuth } from '@/lib/auth'
import { ACCOUNT_PREFS_KEY, readAccountPreferences } from '@/lib/preferences'

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
      ? Promise.resolve(local ? JSON.parse(local) as AuthUser : { id: 'mock-user', username: 'demo', nickname: '演示用户', email: null, roles: ['USER'] })
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
      const updated = USE_MOCK
        ? { ...(user as AuthUser), nickname: nickname || null, email: email || null }
        : await api.users.updateProfile({ nickname, email })
      if (USE_MOCK) localStorage.setItem('nx-mock-profile', JSON.stringify(updated))
      setUser(updated); setNickname(updated.nickname ?? ''); setEmail(updated.email ?? '')
      toast.success('资料已保存', USE_MOCK ? '仅保存在本机演示数据' : '已同步到账号')
    } catch (e) { const message = errMessage(e); setError(message); toast.error('保存失败', message) }
    finally { setSaving(false) }
  }
  return <section className="panel panel-pad">
    <SectionHead title="公开资料" desc="修改昵称与邮箱；用户名是登录凭证，不可更改。" />
    {loading ? <p className="hint" role="status">正在加载账号资料…</p> : <form onSubmit={save}>
      {error && <p className="form-error active" role="alert">{error}</p>}
      <div className="field"><label htmlFor="ac-username">用户名</label><input id="ac-username" className="input" value={user?.username ?? ''} disabled /><p className="hint">用户名不可修改，作为登录凭证</p></div>
      <div className="field"><label htmlFor="ac-nick">昵称</label><input id="ac-nick" className="input" placeholder="展示名称" value={nickname} maxLength={64} onChange={(e) => setNickname(e.target.value)} /></div>
      <div className="field"><label htmlFor="ac-email">邮箱（可选）</label><input id="ac-email" className="input" type="email" placeholder="用于找回密码等" value={email} maxLength={128} onChange={(e) => setEmail(e.target.value)} /><p className="hint">留空会清除邮箱；邮箱验证码服务端尚未实现。</p></div>
      <button className="btn btn-primary" type="submit" disabled={saving}><Save size={15} /> {saving ? '保存中…' : '保存'}</button>
    </form>}
  </section>
}
/* ---------------- 个人 · 模型 Key（自定义对话模型凭据） ---------------- */
export function ModelKeySection() {
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
      toast.success(enabled ? '已启用自定义模型' : '已保存配置（未启用）',
        enabled ? '你的对话 / RAG / Agent 将走自己的模型与额度' : '可随时打开开关启用')
    } catch (e2) {
      const message = errMessage(e2); setError(message); toast.error('保存失败', message)
    } finally { setSaving(false) }
  }

  return <section className="panel panel-pad">
    <SectionHead title="模型 Key" desc="填入你自己的 OpenAI 兼容端点与 API Key；启用后，你的对话、知识库问答与 Agent 将走自己的模型与额度，不再消耗服务端的全局配置。" />
    {loading ? <p className="hint" role="status">正在读取配置…</p> : <form onSubmit={save}>
      {error && <p className="form-error active" role="alert">{error}</p>}
      <div className="field">
        <label htmlFor="mk-base">Base URL</label>
        <input id="mk-base" className="input" placeholder="https://api.deepseek.com" value={baseUrl}
          onChange={(e) => setBaseUrl(e.target.value)} maxLength={500} />
        <p className="hint">任意 OpenAI 兼容端点：DeepSeek、百炼兼容模式、SiliconFlow、自建 vLLM 等。</p>
      </div>
      <div className="field">
        <label htmlFor="mk-key">API Key</label>
        <input id="mk-key" className="input" type="password" autoComplete="off"
          placeholder={cfg?.hasApiKey ? `已设置（${cfg.apiKeyMasked}），留空保持不变` : 'sk-…'}
          value={apiKey} onChange={(e) => setApiKey(e.target.value)} maxLength={500} />
        <p className="hint">Key 仅保存在你的账号下，回显一律掩码；留空提交表示不改动已存的 Key。</p>
      </div>
      <div className="field">
        <label htmlFor="mk-model">模型名</label>
        <input id="mk-model" className="input" placeholder="deepseek-chat" value={modelName}
          onChange={(e) => setModelName(e.target.value)} maxLength={200} />
        <p className="hint">如 deepseek-chat / qwen-plus / glm-4-flash；需与所选端点的模型名一致。</p>
      </div>
      <div className="settings-inline" style={{ margin: '4px 0 16px' }}>
        <div>
          <strong style={{ fontSize: 13.5 }}>启用自定义模型</strong>
          <p className="hint" style={{ margin: '4px 0 0' }}>关闭时回落服务端全局模型；开启需三项填齐。</p>
        </div>
        <SquishSwitch checked={enabled} onChange={setEnabled} ariaLabel="启用自定义模型" />
      </div>
      <button className="btn btn-primary" type="submit" disabled={saving}>
        <Save size={15} /> {saving ? '保存中…' : '保存配置'}
      </button>
      <p className="hint" style={{ marginTop: 12, marginBottom: 0 }}>
        嵌入模型（知识库向量化）仍使用服务端全局配置，不在此处切换——维度在建库时已冻结。
      </p>
    </form>}
  </section>
}

/* ---------------- 个人 · 账号偏好 ---------------- */
export function PrefsSection() {
  const [initial] = useState(readAccountPreferences)
  const [lang, setLang] = useState(initial.language)
  const [tz, setTz] = useState(initial.timezone)
  const [landing, setLanding] = useState<string>(initial.landing)
  const save = () => {
    localStorage.setItem(ACCOUNT_PREFS_KEY, JSON.stringify({ language: lang, timezone: tz, landing }))
    toast.success('偏好已保存', '保存在此浏览器')
  }
  return <section className="panel panel-pad">
    <SectionHead title="账号偏好" desc="默认落地页、语言与时区偏好保存在此浏览器。" />
    <div className="field"><label htmlFor="ac-lang">界面语言</label><select id="ac-lang" className="input" value={lang} onChange={(e) => setLang(e.target.value)}><option>简体中文</option><option disabled>English（暂不可用）</option></select></div>
    <div className="field"><label htmlFor="ac-tz">时区</label><select id="ac-tz" className="input" value={tz} onChange={(e) => setTz(e.target.value)}><option>Asia/Shanghai</option><option>UTC</option></select><p className="hint">时间文本当前按浏览器本地时区格式化，此值仅保存为偏好。</p></div>
    <div className="field"><label htmlFor="ac-landing">登录后默认打开</label><select id="ac-landing" className="input" value={landing} onChange={(e) => setLanding(e.target.value)}><option>工作台</option><option>对话</option><option>知识库</option></select></div>
    <button className="btn btn-primary" type="button" onClick={save}><Save size={15} /> 保存偏好</button>
  </section>
}
/* ---------------- 个人 · 外观 ---------------- */
export function AppearanceSection() {
  return (
    <section className="panel panel-pad">
      <SectionHead title="外观" desc="主题默认跟随系统；配色只影响强调色与背景氛围。" />
      <AppearanceControls />
      <p className="hint" style={{ marginTop: 14, marginBottom: 0 }}>
        外观设置只保存在本地（localStorage），不会同步到服务端。
      </p>
    </section>
  )
}

/* ---------------- 访问与安全 · 密码与登录 ---------------- */
export function SecuritySection() {
  const [allowedTools, setAllowedTools] = useState<string[]>(() => toolPolicy.list())
  const [oldPassword, setOldPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const { logout } = useAuth()
  const changePassword = async (e: FormEvent) => {
    e.preventDefault()
    if (saving) return
    if (USE_MOCK) { setError('密码修改需要真实后端；mock 模式不更改账号密码。'); return }
    if (newPassword !== confirmPassword) { setError('两次输入的新密码不一致'); return }
    if (newPassword.length < 8 || newPassword.length > 64) { setError('新密码长度需在 8–64 之间'); return }
    setSaving(true); setError('')
    try {
      await api.users.changePassword(oldPassword, newPassword)
      toast.success('密码已修改', '所有登录令牌已吊销，请使用新密码重新登录')
      logout()
    } catch (e) { const message = errMessage(e); setError(message); toast.error('密码修改失败', message) }
    finally { setSaving(false) }
  }
  return <section className="panel panel-pad">
    <SectionHead title="密码与登录" desc="修改密码会吊销此账号的全部令牌；其他设备也需要重新登录。" />
    <form onSubmit={changePassword}>
      {error && <p className="form-error active" role="alert">{error}</p>}
      <div className="field"><label htmlFor="ac-cur-pw">当前密码</label><input id="ac-cur-pw" className="input" type="password" autoComplete="current-password" value={oldPassword} onChange={(e) => setOldPassword(e.target.value)} required /></div>
      <div className="field"><label htmlFor="ac-new-pw">新密码</label><input id="ac-new-pw" className="input" type="password" autoComplete="new-password" minLength={8} maxLength={64} value={newPassword} onChange={(e) => setNewPassword(e.target.value)} required /><p className="hint">8–64 位</p></div>
      <div className="field"><label htmlFor="ac-new-pw2">确认新密码</label><input id="ac-new-pw2" className="input" type="password" autoComplete="new-password" value={confirmPassword} onChange={(e) => setConfirmPassword(e.target.value)} required /></div>
      <button className="btn btn-primary" type="submit" disabled={USE_MOCK || saving || !oldPassword || !newPassword || !confirmPassword}><KeyRound size={15} /> {USE_MOCK ? '连接后端后可修改密码' : saving ? '提交中…' : '修改密码并重新登录'}</button>
    </form>
    <hr className="divider" />
    <div className="settings-inline"><div><strong style={{ fontSize: 13.5 }}>两步验证</strong><p className="hint" style={{ margin: '4px 0 0' }}>服务端尚未提供 TOTP 绑定接口，因此当前不可用。</p></div><button className="btn btn-ghost btn-sm" type="button" disabled aria-disabled="true">暂不可用</button></div>
    <hr className="divider" />
    <div className="export-card" style={{ marginTop: 0 }}><div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
      <span style={{ flex: 1, minWidth: 200 }}><strong style={{ fontSize: 13.5 }}>工具授权（始终允许）</strong><p className="hint" style={{ marginTop: 4 }}>{allowedTools.length === 0 ? '暂无：高风险工具每次都会先询问你。' : `以下工具不再询问：${allowedTools.join('、')}`}</p></span>
      {allowedTools.map((name) => <button key={name} className="btn btn-ghost btn-sm" type="button" aria-label={`撤销 ${name} 的授权`} onClick={() => { toolPolicy.revoke(name); setAllowedTools(toolPolicy.list()); toast.info('已撤销授权', `${name} 下次会重新询问`) }}>撤销 {name}</button>)}
      {allowedTools.length > 0 && <button className="btn btn-ghost btn-sm" type="button" aria-label="撤销全部工具授权" onClick={() => { toolPolicy.revokeAll(); setAllowedTools([]); toast.info('已撤销全部工具授权') }}>全部撤销</button>}
    </div></div>
  </section>
}
/* ---------------- 访问与安全 · 邮箱验证 ---------------- */
export function EmailSection() {
  return <section className="panel panel-pad">
    <SectionHead title="邮箱验证" desc="用于找回账号等流程。" />
    <div className="settings-inline"><div><strong style={{ fontSize: 13.5 }}>邮箱验证码</strong><p className="hint" style={{ margin: '4px 0 0' }}>服务端当前没有发送或校验邮箱验证码的接口；邮箱地址可在「公开资料」维护，但不能标记为已验证。</p></div><button className="btn btn-ghost btn-sm" type="button" disabled aria-disabled="true">暂不可用</button></div>
  </section>
}
/* ---------------- 访问与安全 · 会话与设备 ---------------- */
export function SessionsSection() {
  return <section className="panel panel-pad">
    <SectionHead title="会话与设备" desc="当前后端使用无状态 JWT；暂不提供设备会话列表或单设备远程登出。" />
    <div className="list-row"><Laptop size={15} style={{ color: 'var(--text-3)' }} /><span style={{ fontSize: 13.5 }}>当前浏览器会话</span><span className="badge" data-tone="success" style={{ marginLeft: 'auto' }}>当前设备</span></div>
    <p className="hint" style={{ marginBottom: 0, marginTop: 10 }}>如需让所有设备重新登录，请到「密码与登录」修改密码；后端会吊销该账号全部令牌。</p>
  </section>
}
/* ---------------- 数据与用量 · 用量配额 ---------------- */
export function UsageSection() {
  const [quota, setQuota] = useState<UserQuota | null>(null)
  const [kbCount, setKbCount] = useState<number | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  useEffect(() => {
    let alive = true
    if (USE_MOCK) {
      setQuota({ requestUsed: 182, requestLimit: 500, tokenUsed: 48200, tokenLimit: 200000, resetAt: new Date(Date.now() + 86400000).toISOString() })
      setKbCount(6); setLoading(false)
      return () => { alive = false }
    }
    Promise.all([api.users.quota(), api.knowledge.list(1, 1)])
      .then(([q, k]) => { if (alive) { setQuota(q); setKbCount(k.total) } })
      .catch((e) => { if (alive) setError(errMessage(e)) })
      .finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [])
  const pct = (used: number, limit: number) => limit > 0 ? `${Math.min(100, Math.round(used / limit * 100))}%` : '0%'
  return <section className="panel panel-pad">
    <SectionHead title="用量配额" desc={USE_MOCK ? "本地演示样本；真实模式读取服务端" : "当日请求与 Token 用量来自服务端配额接口。"} />
    {loading && <p className="hint" role="status">正在读取用量…</p>}
    {error && <p className="form-error active" role="alert">{error}</p>}
    {quota && <>
      <div className="usage-row"><div className="top"><span>模型请求（今日）</span><span><strong>{quota.requestUsed}</strong> / {quota.requestLimit}</span></div><div className="track"><div className="fill" style={{ width: pct(quota.requestUsed, quota.requestLimit) }} /></div></div>
      <div className="usage-row"><div className="top"><span>Token（今日）</span><span><strong>{quota.tokenUsed.toLocaleString()}</strong> / {quota.tokenLimit.toLocaleString()}</span></div><div className="track"><div className="fill" style={{ width: pct(quota.tokenUsed, quota.tokenLimit) }} /></div></div>
      <p className="hint">额度重置：{new Date(quota.resetAt).toLocaleString()}</p>
    </>}
    {kbCount !== null && <div className="usage-row"><div className="top"><span>知识库</span><span><strong>{kbCount}</strong></span></div></div>}
    <p className="hint" style={{ margin: 0 }}>文件存储容量没有服务端统计接口，因此不显示估算值。</p>
  </section>
}
/* ---------------- 数据与用量 · 数据导出 ---------------- */
export function ExportSection() {
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
      toast.success('导出已完成', knowledge.length + ' 个知识库 · ' + chats.length + ' 个会话 · ' + agents.length + ' 个 Agent')
    } catch (e) {
      const message = errMessage(e); setError(message); toast.error('导出失败', message)
    } finally { setBusy(false) }
  }

  return <section className="panel panel-pad">
    <SectionHead title="数据导出" desc="从后端读取当前账号的知识库正文、会话消息与 Agent，生成本地备份文件。" />
    {error && <p className="form-error active" role="alert">{error}</p>}
    <div className="field">
      <label>导出格式</label>
      <RubberSegment items={['Markdown', 'JSON', 'CSV']} value={format} animateExternal onChange={setFormat}
        size="md" radius={10} inset={3} equalSlots trackColor="var(--surface-muted)" thumbColor="var(--primary-container)"
        textColor="var(--text-2)" activeTextColor="var(--primary-text)" aria-label="导出格式" />
      <p className="hint">{format === 'Markdown' ? '文档正文与会话消息整理成可读归档。' : format === 'JSON' ? '完整结构化备份，包含资料、会话、消息与 Agent。' : '导出账号、知识库文档、会话和 Agent 清单。'}</p>
    </div>
    <p className="hint">导出文件保存在本机；其中包含私有文档与对话，请妥善保管。</p>
    <button className="btn btn-primary" type="button" disabled={busy || USE_MOCK} onClick={() => void exportAll()}>
      <Download size={15} /> {USE_MOCK ? '真实数据导出（mock 不可用）' : busy ? '正在读取并打包…' : '导出全部数据'}
    </button>
    <div className="export-card">
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
        <span style={{ flex: 1, minWidth: 220 }}><strong style={{ fontSize: 13.5 }}>回答反馈</strong><p className="hint" style={{ marginTop: 4 }}>本机保存 {entries.length} 条反馈（不上传到服务器）。</p></span>
        <button className="btn btn-ghost btn-sm" type="button" aria-label="导出反馈 JSON" onClick={() => download('nx-feedback.json', feedbackStore.toJSON(), 'application/json')}><Download size={13} /> JSON</button>
        <button className="btn btn-ghost btn-sm" type="button" aria-label="导出反馈 CSV" onClick={() => download('nx-feedback.csv', feedbackStore.toCSV(), 'text/csv;charset=utf-8')}><Download size={13} /> CSV</button>
        <button className="btn btn-ghost btn-sm" type="button" aria-label="清空反馈数据" onClick={() => { feedbackStore.clear(); toast.info('已清空反馈数据', '仅清除此浏览器保存的反馈') }}>清空</button>
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
  const initial = readNotifyPrefs()
  const [done, setDone] = useState(initial.done)
  const [cite, setCite] = useState(initial.cite)
  const [weekly, setWeekly] = useState(initial.weekly)
  const save = () => {
    localStorage.setItem(NOTIFY_KEY, JSON.stringify({ done, cite, weekly }))
    toast.success('偏好已保存到此浏览器', '通知发送服务尚未接入')
  }
  return <section className="panel panel-pad">
    <SectionHead title="通知偏好" desc="提醒选择保存在当前浏览器；邮件/推送发送服务尚未接入。" />
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <div className="settings-inline"><div><strong style={{ fontSize: 13.5 }}>任务完成</strong><p className="hint" style={{ margin: '4px 0 0' }}>后台任务完成提醒偏好（通知通道未启用）</p></div><SquishSwitch checked={done} onChange={setDone} ariaLabel="任务完成通知" /></div>
      <hr className="divider" />
      <div className="settings-inline"><div><strong style={{ fontSize: 13.5 }}>回答与引用更新</strong><p className="hint" style={{ margin: '4px 0 0' }}>引用更新提醒偏好（通知通道未启用）</p></div><SquishSwitch checked={cite} onChange={setCite} ariaLabel="回答与引用更新通知" /></div>
      <hr className="divider" />
      <div className="settings-inline"><div><strong style={{ fontSize: 13.5 }}>每周摘要</strong><p className="hint" style={{ margin: '4px 0 0' }}>每周摘要提醒偏好（通知通道未启用）</p></div><SquishSwitch checked={weekly} onChange={setWeekly} ariaLabel="每周摘要通知" /></div>
    </div>
    <button className="btn btn-ghost" type="button" style={{ marginTop: 16 }} onClick={save}><Save size={15} /> 保存偏好</button>
  </section>
}
/* ---------------- 危险区 ---------------- */
export function DangerSection() {
  return <section className="panel panel-pad panel-danger">
    <SectionHead title="注销账号" desc="账号注销会级联删除名下知识库、文档、会话与 Agent，且不可恢复。" />
    <div className="settings-inline"><div><strong style={{ fontSize: 13.5 }}>永久注销</strong><p className="hint" style={{ margin: '4px 0 0' }}>服务端目前没有账号注销接口。为避免误导，不提供会显示成功但不执行的操作。</p></div><button className="btn btn-danger btn-sm" type="button" disabled aria-disabled="true">暂不可用</button></div>
    <p className="hint" style={{ marginBottom: 0, marginTop: 12 }}>后续实现需包含密码二次校验、数据级联删除、令牌吊销与审计记录。</p>
  </section>
}