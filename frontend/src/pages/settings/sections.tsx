import { useEffect, useState } from 'react'
import { Download, KeyRound, Laptop, LogOut, MailCheck, Save, ShieldAlert } from 'lucide-react'
import Counter from '@/bits/Counter/Counter'
import CodeSlots from '@/bits/CodeSlots/CodeSlots'
import RubberSegment from '@/bits/RubberSegment/RubberSegment'
import SpringCheck from '@/bits/SpringCheck/SpringCheck'
import SquishSwitch from '@/bits/SquishSwitch/SquishSwitch'
import { feedbackStore, useFeedback } from '@/lib/feedback'
import { toolPolicy } from '@/lib/toolPolicy'
import { download } from '@/lib/download'
import StatusMark from '@/bits/StatusMark/StatusMark'
import Modal from '@/components/Modal'
import { AppearanceControls } from '@/components/Appearance'
import { toast } from '@/lib/toast'

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
  return (
    <section className="panel panel-pad">
      <SectionHead title="公开资料" desc="这些信息会展示在你的个人页与共享会话里。" />
      <div className="field">
        <label htmlFor="ac-username">用户名</label>
        <input id="ac-username" className="input" value="demo" disabled />
        <p className="hint">用户名不可修改，作为登录凭证</p>
      </div>
      <div className="field">
        <label htmlFor="ac-nick">昵称</label>
        <input id="ac-nick" className="input" placeholder="展示名称" />
      </div>
      <div className="field">
        <label htmlFor="ac-email">邮箱（可选）</label>
        <input id="ac-email" className="input" placeholder="用于找回密码等" />
      </div>
      <button className="btn btn-primary" type="button" onClick={() => toast.success('资料已保存', '昵称与邮箱已更新')}>
        <Save size={15} /> 保存
      </button>
    </section>
  )
}

/* ---------------- 个人 · 账号偏好 ---------------- */
export function PrefsSection() {
  const [lang, setLang] = useState('简体中文')
  const [tz, setTz] = useState('Asia/Shanghai')
  const [landing, setLanding] = useState('工作台')
  return (
    <section className="panel panel-pad">
      <SectionHead title="账号偏好" desc="语言、时区与登录后的默认落地页。" />
      <div className="field">
        <label htmlFor="ac-lang">界面语言</label>
        <select id="ac-lang" className="input" value={lang} onChange={(e) => setLang(e.target.value)}>
          <option>简体中文</option>
          <option>English</option>
        </select>
      </div>
      <div className="field">
        <label htmlFor="ac-tz">时区</label>
        <select id="ac-tz" className="input" value={tz} onChange={(e) => setTz(e.target.value)}>
          <option>Asia/Shanghai</option>
          <option>UTC</option>
        </select>
        <p className="hint">影响用量统计与消息时间的显示口径</p>
      </div>
      <div className="field">
        <label htmlFor="ac-landing">登录后默认打开</label>
        <select id="ac-landing" className="input" value={landing} onChange={(e) => setLanding(e.target.value)}>
          <option>工作台</option>
          <option>对话</option>
          <option>知识库</option>
        </select>
      </div>
      <button className="btn btn-primary" type="button" onClick={() => toast.success('偏好已保存', `${lang} · ${tz} · 默认打开${landing}`)}>
        <Save size={15} /> 保存偏好
      </button>
    </section>
  )
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
  /* 已"始终允许"的高风险工具（撤销后要立刻反映到 UI，所以放 state） */
  const [allowedTools, setAllowedTools] = useState<string[]>(() => toolPolicy.list())
  return (
    <section className="panel panel-pad">
      <SectionHead title="密码与登录" desc="修改密码后需要重新登录；两步验证可显著提高账号安全性。" />
      <div className="field">
        <label htmlFor="ac-cur-pw">当前密码</label>
        <input id="ac-cur-pw" className="input" type="password" placeholder="••••••••" />
      </div>
      <div className="field">
        <label htmlFor="ac-new-pw">新密码</label>
        <input id="ac-new-pw" className="input" type="password" placeholder="8–64 位" />
      </div>
      <div className="field">
        <label htmlFor="ac-new-pw2">确认新密码</label>
        <input id="ac-new-pw2" className="input" type="password" placeholder="再次输入" />
      </div>
      <button className="btn btn-ghost" type="button" onClick={() => toast.success('密码已修改', '请使用新密码重新登录（原型未接后端）')}>
        修改密码（成功后自动登出）
      </button>

      <hr className="divider" />
      <div className="settings-inline">
        <div>
          <strong style={{ fontSize: 13.5 }}>两步验证</strong>
          <p className="hint" style={{ margin: '4px 0 0' }}>登录时需要额外输入一次性验证码</p>
        </div>
        <SquishSwitch ariaLabel="两步验证" onChange={(v) => toast.info(v ? '已开启两步验证（原型演示）' : '已关闭两步验证', '真实实现需绑定 TOTP')} />
      </div>

      <hr className="divider" />
      {/* 工具授权：被"始终允许"的高风险工具必须能在这里收回，否则授权只增不减 = 信任漏洞 */}
      <div className="export-card" style={{ marginTop: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
          <span style={{ flex: 1, minWidth: 200 }}>
            <strong style={{ fontSize: 13.5 }}>工具授权（始终允许）</strong>
            <p className="hint" style={{ marginTop: 4 }}>
              {allowedTools.length === 0
                ? '暂无：高风险工具（如执行代码）每次都会先询问你。'
                : `以下工具不再询问，直接执行：${allowedTools.join('、')}`}
            </p>
          </span>
          {allowedTools.map((name) => (
            <button
              key={name}
              className="btn btn-ghost btn-sm"
              type="button"
              aria-label={`撤销 ${name} 的授权`}
              onClick={() => { toolPolicy.revoke(name); setAllowedTools(toolPolicy.list()); toast.info('已撤销授权', `${name} 下次会重新询问`) }}
            >
              撤销 {name}
            </button>
          ))}
          {allowedTools.length > 0 && (
            <button
              className="btn btn-ghost btn-sm"
              type="button"
              aria-label="撤销全部工具授权"
              onClick={() => { toolPolicy.revokeAll(); setAllowedTools([]); toast.info('已撤销全部工具授权') }}
            >
              全部撤销
            </button>
          )}
        </div>
      </div>
    </section>
  )
}

/* ---------------- 访问与安全 · 邮箱验证 ---------------- */
export function EmailSection() {
  /* 邮箱验证：后端**尚无**验证码接口，这里用固定演示码 123456；
     接后端时把 verifyCode() 换成 POST /api/auth/email/verify 即可，UI 不用动。 */
  const [open, setOpen] = useState(false)
  const [status, setStatus] = useState<'idle' | 'error' | 'success'>('idle')
  const [verified, setVerified] = useState(false)
  const DEMO_CODE = '123456'

  const verifyCode = async (code: string) => {
    await new Promise((r) => setTimeout(r, 700))
    if (code === DEMO_CODE) {
      setStatus('success')
      setVerified(true)
      toast.success('邮箱验证成功', 'demo@example.com')
      setTimeout(() => setOpen(false), 900)
    } else {
      setStatus('error')
      toast.error('验证码不正确', '原型演示码为 123456')
      setTimeout(() => setStatus('idle'), 1400)
    }
  }

  return (
    <section className="panel panel-pad">
      <SectionHead title="邮箱验证" desc="验证后可用于找回密码与接收重要通知。" />
      <div className="settings-inline">
        <StatusMark status={verified ? 'done' : 'pending'} size={16} label={verified ? '邮箱已验证' : '邮箱未验证'} fontSize={13} strike={false} />
        <button className="btn btn-ghost btn-sm" type="button" onClick={() => setOpen(true)}>
          <MailCheck size={13} /> {verified ? '重新验证' : '验证邮箱'}
        </button>
      </div>
      <p className="hint" style={{ marginTop: 14 }}>
        当前邮箱：<strong>demo@example.com</strong>
      </p>

      <Modal
        open={open}
        title="验证邮箱"
        onClose={() => { setOpen(false); setStatus('idle') }}
        width={460}
        footer={
          <>
            <button className="btn btn-ghost" type="button" onClick={() => toast.task('验证码已重发', '原型演示：未接后端，码固定为 123456')}>
              重新发送
            </button>
            <button className="btn btn-ghost" type="button" onClick={() => { setOpen(false); setStatus('idle') }}>
              取消
            </button>
          </>
        }
      >
        <p style={{ margin: '0 0 4px', fontSize: 13.5, color: 'var(--text-2)' }}>
          我们向 <strong>demo@example.com</strong> 发送了 6 位验证码，请填入下方。
        </p>
        <p className="hint" style={{ marginTop: 0 }}>
          原型说明：后端暂无验证码接口，演示码为 <code style={{ padding: '1px 6px', border: '1px solid var(--border)', borderRadius: 5, background: 'var(--surface-muted)' }}>123456</code>；
          输入其他任意 6 位数字会进入失败态（可看到回抽清空效果）。
        </p>
        <div style={{ display: 'flex', justifyContent: 'center', padding: '14px 0 4px' }}>
          <CodeSlots
            length={6}
            status={status}
            autoFocus
            accentColor="var(--primary)"
            inkColor="var(--text-2)"
            slotColor="var(--surface-muted)"
            digitColor="var(--on-primary)"
            dangerColor="var(--danger)"
            onChange={() => { if (status !== 'idle') setStatus('idle') }}
            onComplete={(code) => verifyCode(code)}
            ariaLabel="邮箱验证码"
          />
        </div>
      </Modal>
    </section>
  )
}

/* ---------------- 访问与安全 · 会话与设备 ---------------- */
const DEVICES = [
  { id: 1, name: 'MacBook Pro · 上海', current: true, time: '当前会话' },
  { id: 2, name: 'iPhone 15 · 上海', current: false, time: '2 天前' },
  { id: 3, name: 'Windows · 杭州', current: false, time: '3 周前' },
]

export function SessionsSection() {
  const [devices, setDevices] = useState(DEVICES)
  return (
    <section className="panel panel-pad">
      <SectionHead title="会话与设备" desc="这些设备最近登录过你的账号；发现陌生设备请及时退出并修改密码。" />
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        {devices.map((d) => (
          <div className="list-row" key={d.id}>
            <Laptop size={15} style={{ color: 'var(--text-3)' }} />
            <span style={{ fontSize: 13.5 }}>{d.name}</span>
            <span className="badge" data-tone={d.current ? 'success' : undefined} style={{ marginLeft: 'auto' }}>
              {d.current ? '当前设备' : d.time}
            </span>
            {!d.current && (
              <button
                className="btn btn-ghost btn-sm"
                type="button"
                onClick={() => {
                  setDevices((list) => list.filter((x) => x.id !== d.id))
                  toast.success('已退出该设备', d.name)
                }}
              >
                退出
              </button>
            )}
          </div>
        ))}
        {devices.length === 1 && <p className="hint" style={{ margin: '6px 0 0' }}>除当前设备外没有其他登录记录。</p>}
        <button
          className="btn btn-ghost btn-sm"
          type="button"
          style={{ alignSelf: 'flex-start' }}
          disabled={devices.length <= 1}
          onClick={() => { setDevices((l) => l.filter((d) => d.current)); toast.success('已退出其他全部设备', '真实实现会失效对应会话的令牌') }}
        >
          <LogOut size={13} /> 退出其他设备
        </button>
      </div>
    </section>
  )
}

/* ---------------- 数据与用量 · 用量配额 ---------------- */
export function UsageSection() {
  const [stored, setStored] = useState(0)
  const [calls, setCalls] = useState(0)
  useEffect(() => {
    const t = setTimeout(() => { setStored(2.4); setCalls(182) }, 100)
    return () => clearTimeout(t)
  }, [])
  return (
    <section className="panel panel-pad">
      <SectionHead title="用量配额" desc="按当前套餐统计的存储、调用与知识库用量。" />
      <div className="usage-row">
        <div className="top"><span>存储</span><span><strong>{stored} GB</strong> / 10 GB</span></div>
        <div className="track"><div className="fill" style={{ width: '24%' }} /></div>
      </div>
      <div className="usage-row">
        <div className="top"><span>LLM 调用（本月）</span><span><strong>{calls}</strong> / 500 次</span></div>
        <div className="track"><div className="fill" style={{ width: '36%' }} /></div>
      </div>
      <div className="usage-row">
        <div className="top"><span>知识库数量</span><span><strong><Counter value={3} places={[10, 1]} fontSize={13} fontWeight={600} /></strong> / 20</span></div>
        <div className="track"><div className="fill" style={{ width: '15%' }} /></div>
      </div>
      <p className="hint" style={{ margin: 0 }}>配额统计由 Nexus 用量中间件汇总（nexus.llm.calls / tokens / latency）</p>
    </section>
  )
}

/* ---------------- 数据与用量 · 数据导出 ---------------- */
export function ExportSection() {
  const [format, setFormat] = useState('Markdown')
  /* 反馈条收集到的数据（P0-3）——导出后就能做"检索差还是生成差"的归因 */
  const { entries } = useFeedback()
  return (
    <section className="panel panel-pad">
      <SectionHead title="数据导出" desc="把知识库、会话与 Agent 配置打包导出，便于备份或迁移。" />
      <div className="field">
        <label>导出格式</label>
        <RubberSegment
          items={['Markdown', 'JSON', 'CSV']}
          value={format}
          animateExternal
          onChange={setFormat}
          size="md"
          radius={10}
          inset={3}
          equalSlots
          trackColor="var(--surface-muted)"
          thumbColor="var(--primary-container)"
          textColor="var(--text-2)"
          activeTextColor="var(--primary-text)"
          aria-label="导出格式"
        />
        <p className="hint">
          {format === 'Markdown' && '按知识库分目录，保留文档标题层级，适合阅读与迁移。'}
          {format === 'JSON' && '包含文档元信息、切片与引用关系，适合二次处理。'}
          {format === 'CSV' && '只导出文档清单与统计字段，适合表格分析。'}
        </p>
      </div>
      <button
        className="btn btn-primary"
        type="button"
        onClick={() => toast.task(`正在打包导出（${format}）`, '原型演示：未接后端，真实实现会生成下载任务')}
      >
        <Download size={15} /> 导出全部数据
      </button>

      {/* 回答反馈：这是"改进闭环"的数据源，必须能导出，否则收上来也分析不了。
          刻意不用 .panel —— 设置分区有"一个分区恰好一个 panel"的结构约定（QA 也在守这条）。 */}
      <div className="export-card">
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
          <span style={{ flex: 1, minWidth: 220 }}>
            <strong style={{ fontSize: 13.5 }}>回答反馈</strong>
            <p className="hint" style={{ marginTop: 4 }}>
              已收集 <strong>{entries.length}</strong> 条（👍 {entries.filter((e) => e.vote === 'up').length} · 👎{' '}
              {entries.filter((e) => e.vote === 'down').length}）。字段含投票、原因、模型/档位、Agent、引用数、
              工具状态与耗时 —— 足以判断"是检索差还是生成差"。
            </p>
          </span>
          <button
            className="btn btn-ghost btn-sm"
            type="button"
            aria-label="导出反馈 JSON"
            onClick={() => download('nx-feedback.json', feedbackStore.toJSON())}
          >
            <Download size={13} /> JSON
          </button>
          <button
            className="btn btn-ghost btn-sm"
            type="button"
            aria-label="导出反馈 CSV"
            onClick={() => download('nx-feedback.csv', feedbackStore.toCSV())}
          >
            <Download size={13} /> CSV
          </button>
          <button
            className="btn btn-ghost btn-sm"
            type="button"
            aria-label="清空反馈数据"
            onClick={() => { feedbackStore.clear(); toast.info('已清空反馈数据', '原型级存储，仅保存在本地') }}
          >
            清空
          </button>
        </div>
      </div>
    </section>
  )
}

/* ---------------- 通知 · 通知偏好 ---------------- */
export function NotifySection() {
  const [done, setDone] = useState(true)
  const [cite, setCite] = useState(false)
  const [weekly, setWeekly] = useState(true)
  return (
    <section className="panel panel-pad">
      <SectionHead title="通知偏好" desc="选择哪些事件需要提醒你；原型里只保存开关状态，不发真实通知。" />
      <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
        <div className="settings-inline">
          <div>
            <strong style={{ fontSize: 13.5 }}>任务完成</strong>
            <p className="hint" style={{ margin: '4px 0 0' }}>导入、重建索引等后台任务结束时提醒</p>
          </div>
          <SquishSwitch checked={done} onChange={setDone} ariaLabel="任务完成通知" />
        </div>
        <hr className="divider" />
        <div className="settings-inline">
          <div>
            <strong style={{ fontSize: 13.5 }}>回答与引用更新</strong>
            <p className="hint" style={{ margin: '4px 0 0' }}>被引用文档更新后，相关会话收到提示</p>
          </div>
          <SquishSwitch checked={cite} onChange={setCite} ariaLabel="回答与引用更新通知" />
        </div>
        <hr className="divider" />
        <div className="settings-inline">
          <div>
            <strong style={{ fontSize: 13.5 }}>每周摘要</strong>
            <p className="hint" style={{ margin: '4px 0 0' }}>每周一发送用量、新增文档与高频问题摘要</p>
          </div>
          <SquishSwitch checked={weekly} onChange={setWeekly} ariaLabel="每周摘要通知" />
        </div>
      </div>
      <button
        className="btn btn-ghost"
        type="button"
        style={{ marginTop: 16 }}
        onClick={() => toast.success('通知偏好已保存', [done && '任务完成', cite && '引用更新', weekly && '每周摘要'].filter(Boolean).join(' · ') || '已全部关闭')}
      >
        <Save size={15} /> 保存通知偏好
      </button>
    </section>
  )
}

/* ---------------- 危险区 ---------------- */
export function DangerSection() {
  const [step, setStep] = useState<1 | 2>(1)
  const [confirmName, setConfirmName] = useState('')
  const [ack, setAck] = useState(false)
  const [msg, setMsg] = useState('')
  return (
    <section className="panel panel-pad panel-danger">
      <SectionHead title="注销账号" desc="不可恢复：将级联删除知识库、文档、会话、Agent 与全部对话记录。" />
      <h3 className="sr-only">
        <KeyRound size={14} /> 危险操作
      </h3>

      {step === 1 && (
        <div className="danger-steps">
          <div className="danger-item">
            <strong>第一步 · 输入用户名确认</strong>
            <p className="hint" style={{ margin: '6px 0 10px' }}>
              请输入 <code style={{ padding: '1px 6px', border: '1px solid var(--border)', borderRadius: 5, background: 'var(--surface-muted)' }}>demo</code> 以确认这是你要注销的账号。
            </p>
            <input
              className="input"
              value={confirmName}
              onChange={(e) => setConfirmName(e.target.value)}
              placeholder="demo"
              aria-label="输入用户名确认"
            />
            {/* 勾选控件换成 SpringCheck：勾上时会用弹簧动画把文字划掉，语义正好是"我确认了" */}
            <div style={{ marginTop: 12 }}>
              <SpringCheck
                label="我已了解：该操作不可撤销，且会同时删除名下全部数据"
                checked={ack}
                onChange={setAck}
                ariaLabel="我确认要注销账号"
                color="var(--danger)"
                fillColor="var(--danger)"
                checkColor="var(--on-primary-strong)"
                boxSize={24}
                boxRadius={8}
                fontSize={13.5}
                bounce={0.3}
                doneOpacity={0.5}
              />
            </div>
          </div>
          <button className="btn btn-danger" type="button" disabled={confirmName.trim() !== 'demo' || !ack} onClick={() => setStep(2)}>
            继续（{confirmName.trim() === 'demo' && ack ? '可继续' : '需完成上方确认'}）
          </button>
        </div>
      )}

      {step === 2 && (
        <div className="danger-steps">
          <div className="danger-final">
            <strong>第二步 · 最后确认</strong>
            <p style={{ margin: '6px 0 0' }}>点击下方按钮后，以下数据将被<strong>永久删除</strong>，无法恢复：</p>
            <ul className="danger-list">
              <li>3 个知识库 · 18 篇文档 · 索引与切片</li>
              <li>12 条会话 · 全部消息与记忆</li>
              <li>4 个 Agent（含默认助手配置）</li>
              <li>账号本身及其登录凭证</li>
            </ul>
          </div>
          <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
            <button
              className="btn btn-danger"
              type="button"
              onClick={() => {
                setStep(1); setConfirmName(''); setAck(false)
                setMsg('原型演示：注销流程已触发（真实实现会级联清理全部数据并登出）。')
                toast.error('账号注销已触发', '真实实现会级联清理全部数据并登出')
              }}
            >
              永久注销我的账号
            </button>
            <button className="btn btn-ghost" type="button" onClick={() => { setStep(1); setConfirmName(''); setAck(false) }}>
              取消
            </button>
          </div>
        </div>
      )}

      {msg && <p style={{ margin: '12px 0 0', fontSize: 12.5, color: 'var(--danger)' }}>{msg}</p>}
      <p className="hint" style={{ marginTop: 16, marginBottom: 0 }}>
        <ShieldAlert size={12} style={{ verticalAlign: -2 }} /> 真实实现里这一步会要求二次密码校验，并写入审计日志。
      </p>
    </section>
  )
}