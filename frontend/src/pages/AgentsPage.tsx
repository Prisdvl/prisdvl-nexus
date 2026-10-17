import { useEffect, useState, type FormEvent } from 'react'
import {
  BarChart3, BookOpen, Bot, Briefcase, Check, Code2, Copy, FlaskConical, GraduationCap,
  Pencil, PenLine, Play, Plus, RefreshCw, Search, Sparkles, Trash2, Zap,
} from 'lucide-react'
import PixelCard from '@/bits/PixelCard/PixelCard'
import StatusMark from '@/bits/StatusMark/StatusMark'
import Stepper, { Step } from '@/bits/Stepper/Stepper'
import Modal from '@/components/Modal'
import { StateEmpty } from '@/components/Skeleton'
import ToolTrace from '@/components/chat/ToolTrace'
import CometDial from '@/bits/CometDial/CometDial'
import { toast } from '@/lib/toast'
import { useParticleColors } from '@/lib/palette'
import { AGENTS, type AgentItem, type ToolCall } from '@/data'
import { api, USE_MOCK, errMessage, type ToolInfo } from '@/lib/api'
import { fetchAgents, fetchKbs } from '@/lib/backendData'
import type { KbItem } from '@/data'
import { pagesCopy } from '@/lib/pagesCopy'
import { useLocale } from '@/lib/locale'
import Loc from '@/components/Loc'

/* 10 个可选角色图标（前端展示；后端暂无 icon 字段） */
const AGENT_ICONS: Record<string, typeof Bot> = {
  bot: Bot,
  search: Search,
  code: Code2,
  book: BookOpen,
  graduation: GraduationCap,
  briefcase: Briefcase,
  flask: FlaskConical,
  pen: PenLine,
  chart: BarChart3,
  zap: Zap,
}
const AGENT_ICON_IDS = Object.keys(AGENT_ICONS)

export default function AgentsPage() {
  const { locale } = useLocale()
  const g = pagesCopy(locale).agents
  /* 粒子色与知识库卡片统一：跟随当前配色与明暗 */
  const particleColors = useParticleColors()
  const [agents, setAgents] = useState<AgentItem[]>(USE_MOCK ? AGENTS : [])
  const [loadingAgents, setLoadingAgents] = useState(!USE_MOCK)
  const [agentsError, setAgentsError] = useState('')
  const [newName, setNewName] = useState('')
  const [newDesc, setNewDesc] = useState('')
  const [newPrompt, setNewPrompt] = useState('')
  const [wizCreativity, setWizCreativity] = useState(0.4)
  const [wizIcon, setWizIcon] = useState<string>('bot')
  const [wizTestIn, setWizTestIn] = useState('')
  const [wizTestOut, setWizTestOut] = useState('')
  const [wizTesting, setWizTesting] = useState(false)
  const [testCalls, setTestCalls] = useState<ToolCall[]>([])
  const [busy, setBusy] = useState(false)
  const [wizard, setWizard] = useState(false)
  /* 编辑既有 Agent（名称/描述；后端局部更新，null 字段不改） */
  const [editing, setEditing] = useState<AgentItem | null>(null)
  const [editName, setEditName] = useState('')
  const [editDesc, setEditDesc] = useState('')
  const [savingEdit, setSavingEdit] = useState(false)
  /* 向导第二步的真实可选项（真实模式打开向导时拉取；拉不到不阻塞创建） */
  const [kbChoices, setKbChoices] = useState<KbItem[]>(USE_MOCK ? [] : [])
  const [toolChoices, setToolChoices] = useState<ToolInfo[]>([])
  const [wizKbIds, setWizKbIds] = useState<string[]>([])
  const [wizTools, setWizTools] = useState<string[]>([])
  /* Stepper 完成不可异步阻止：创建失败时用 key 重挂载并回到确认步 */
  const [wizKey, setWizKey] = useState(0)
  const [wizStep, setWizStep] = useState(1)

  const resetWizard = (step = 1) => {
    setWizStep(step)
    setWizKey((k) => k + 1)
  }

  const loadAgents = async () => {
    if (USE_MOCK) return
    setLoadingAgents(true)
    setAgentsError('')
    try {
      setAgents(await fetchAgents())
    } catch (e) {
      setAgentsError(errMessage(e))
    } finally {
      setLoadingAgents(false)
    }
  }

  /* 真实数据：Agent 列表（mock 模式保留种子） */
  useEffect(() => { void loadAgents() }, [])

  const openWizard = () => {
    resetWizard(1)
    setWizard(true)
    setNewName('')
    setNewDesc('')
    setWizKbIds([])
    setWizTools([])
    setNewPrompt('')
    setWizCreativity(0.4)
    setWizIcon('bot')
    setWizTestIn('')
    setWizTestOut('')
    setWizTesting(false)
    if (!USE_MOCK) {
      fetchKbs().then(setKbChoices).catch(() => setKbChoices([]))
      api.tools.list().then((rows) => setToolChoices(rows.filter((t) => t.enabled))).catch(() => setToolChoices([]))
    }
  }

  const [testAgent, setTestAgent] = useState<AgentItem | null>(USE_MOCK ? AGENTS[0] : null)
  const [testIn, setTestIn] = useState('')
  const [testing, setTesting] = useState(false)
  const [testOut, setTestOut] = useState('')
  const [testFailed, setTestFailed] = useState(false)

  const runTest = async (e: FormEvent) => {
    e.preventDefault()
    if (!testAgent || !testIn.trim() || testing) return
    setTesting(true)
    setTestOut('')
    setTestFailed(false)
    const calls: ToolCall[] = [
      { id: `c-${Date.now()}-1`, icon: 'search', name: 'knowledge_search', argument: `{"q":"${testIn.trim().slice(0, 40)}"}`, status: 'running' },
      { id: `c-${Date.now()}-2`, icon: 'terminal', name: 'calculator', argument: '{"expr":"complexity"}' , status: 'idle' },
    ]
    setTestCalls(calls)
    try {
      if (USE_MOCK) {
        await new Promise((resolve) => setTimeout(resolve, 700))
        setTestOut(g.mockTestOut(testAgent.name))
        setTestCalls(calls.map((c) => ({ ...c, status: 'done' as const })))
        toast.info(g.mockTestTitle, g.mockTestBody)
        return
      }
      const result = await api.agents.testChat(testAgent.id, testIn.trim())
      setTestOut(result.answer)
      setTestCalls(calls.map((c) => ({ ...c, status: 'done' as const })))
      toast.success(g.testDoneTitle, result.usedTools ? g.testToolsUsed(result.toolCalls?.length ?? 0) : g.testNoTools)
    } catch (e) {
      setTestFailed(true)
      const msg = errMessage(e)
      setTestOut(msg)
      setTestCalls(calls.map((c, i) => ({ ...c, status: i === 0 ? 'done' as const : 'error' as const })))
      toast.error(g.testFailTitle, msg)
    } finally {
      setTesting(false)
    }
  }

  const removeAgent = async (a: AgentItem) => {
    if (USE_MOCK) {
      const idx = agents.findIndex((x) => x.id === a.id)
      setAgents((list) => list.filter((x) => x.id !== a.id))
      toast.undo(g.deletedTitle, a.name, () => {
        setAgents((list) => {
          const out = [...list]
          out.splice(Math.min(idx, out.length), 0, a)
          return out
        })
        toast.success(g.restoredTitle, a.name)
      })
      return
    }
    /* 后端删除不可恢复 → 明确二次确认 */
    if (!window.confirm(g.confirmDelete(a.name))) return
    try {
      await api.agents.remove(a.id)
      setAgents(await fetchAgents())
      if (testAgent?.id === a.id) setTestAgent(null)
      toast.success(g.deletedOkTitle, a.name)
    } catch (e) {
      toast.error(g.deleteFailTitle, errMessage(e))
    }
  }

  const duplicateAgent = async (a: AgentItem) => {
    if (USE_MOCK) {
      const copy = { ...a, id: String(Date.now()), name: g.copyName(a.name) }
      setAgents((list) => {
        const out = [...list]
        out.splice(out.findIndex((x) => x.id === a.id) + 1, 0, copy)
        return out
      })
      toast.success(g.copiedTitle, copy.name)
      return
    }
    try {
      /* 复制走服务端 duplicate：副本回到 DRAFT，code 由服务端生成，避免前端伪造标识 */
      await api.agents.duplicate(a.id)
      setAgents(await fetchAgents())
      toast.success(g.copiedTitle, g.copiedMockName(a.name))
    } catch (e) {
      toast.error(g.copyFailTitle, errMessage(e))
    }
  }

  const openEdit = (a: AgentItem) => {
    setEditing(a)
    setEditName(a.name)
    setEditDesc(a.desc)
  }

  /** 发布：草稿 → 已发布。后端 status 字段已支持，mock 时只改本地。 */
  const publishAgent = async (a: AgentItem) => {
    if (USE_MOCK) {
      setAgents((list) => list.map((x) => x.id === a.id ? { ...x, status: 'PUBLISHED' as const } : x))
      toast.success(g.publishDone, a.name)
      return
    }
    try {
      await api.agents.update(a.id, { status: 'PUBLISHED' })
      setAgents(await fetchAgents())
      toast.success(g.publishDone, a.name)
    } catch (e) {
      toast.error(g.saveFailTitle, errMessage(e))
    }
  }

  const saveEdit = async () => {
    if (!editing || savingEdit) return
    const name = editName.trim()
    if (!name) { toast.error(g.errNameEmpty); return }
    if (USE_MOCK) {
      setAgents((list) => list.map((x) => (x.id === editing.id ? { ...x, name, desc: editDesc.trim() } : x)))
      setEditing(null)
      toast.success(g.savedTitle, name)
      return
    }
    setSavingEdit(true)
    try {
      await api.agents.update(editing.id, { name, description: editDesc.trim() || null })
      setAgents(await fetchAgents())
      setEditing(null)
      toast.success(g.savedTitle, name)
    } catch (e) {
      toast.error(g.saveFailTitle, errMessage(e))
    } finally {
      setSavingEdit(false)
    }
  }

  const toggleIn = (list: string[], key: string, set: (v: string[]) => void) => {
    set(list.includes(key) ? list.filter((x) => x !== key) : [...list, key])
  }

  const wizardCompleteness = Math.round(
    ((newName.trim() ? 1 : 0) + (newDesc.trim() ? 1 : 0) + (newPrompt.trim() ? 1 : 0) + (wizKbIds.length ? 1 : 0) + (wizTools.length ? 1 : 0)) / 5 * 100,
  )

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <p className="eyebrow" style={{ margin: 0 }}>Agent</p>
          <h1 className="page-title"><Loc>{g.title}</Loc></h1>
          <p className="page-sub"><Loc>{g.sub}</Loc></p>
        </div>
        <button className="btn btn-primary" type="button" onClick={openWizard}><Plus size={16} /> <Loc>{g.newAgent}</Loc></button>
      </div>

      {loadingAgents && <p className="hint" role="status">{g.loading}</p>}
      {!loadingAgents && agentsError && (
        <div className="panel panel-pad">
          <p className="form-error active" role="alert" style={{ marginTop: 0 }}>{g.listFailPrefix} {agentsError}</p>
          <button className="btn btn-ghost btn-sm" type="button" onClick={() => void loadAgents()}><RefreshCw size={13} /> {g.retry}</button>
        </div>
      )}
      {!loadingAgents && !agentsError && agents.length === 0 && (
        <div className="panel">
          <StateEmpty
            icon={<Bot size={20} />}
            title={g.emptyTitle}
            desc={g.emptyDesc}
            action={<button className="btn btn-primary btn-sm" type="button" onClick={openWizard}><Plus size={14} /> {g.newAgent}</button>}
          />
        </div>
      )}

      {agents.length > 0 && <div className="agent-grid">
        {agents.map((a) => {
          const Icon = AGENT_ICONS[a.icon ?? 'bot'] ?? Bot
          return (
          <PixelCard key={a.id} gap={8} speed={45} colors={particleColors}>
            <div className="agent-inner">
              <div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <Icon size={15} style={{ color: 'var(--primary)' }} aria-hidden="true" />
                  <h2 className="nm">{a.name}</h2>
                  {a.status === 'DRAFT' && <span className="badge" data-tone="warning">{g.draftBadge}</span>}
                  {a.status === 'PUBLISHED' && <span className="badge" data-tone="success">{g.publishedBadge}</span>}
                </div>
                <p className="ds">{a.desc}</p>
                <div className="tags">
                  <span className="chip" style={{ fontSize: 11 }}>{a.model}</span>
                  {a.tools.slice(0, 2).map((t) => <span className="badge" data-tone="accent" key={t}>{t}</span>)}
                  {a.tools.length > 2 && <span className="badge">+{a.tools.length - 2}</span>}
                </div>
                <p className="agent-kb" title={`${g.boundKb(a.kb)}`}>{g.boundKb(a.kb)}</p>
              </div>
              <div className="acts">
                {a.status === 'DRAFT' && (
                  <button className="btn btn-ghost btn-sm" type="button" title={g.publishBtn} aria-label={`${g.publishBtn}: ${a.name}`} onClick={() => void publishAgent(a)}>
                    <Sparkles size={13} />
                  </button>
                )}
                <button className="btn btn-primary btn-sm" type="button" onClick={() => { setTestAgent(a); setTestOut(''); setTestFailed(false) }}><Play size={13} /> {g.test}</button>
                <button className="btn btn-ghost btn-sm" type="button" aria-label={g.editAria} title={g.editTitle} onClick={() => openEdit(a)}><Pencil size={13} /></button>
                <button
                  className="btn btn-ghost btn-sm"
                  type="button"
                  aria-label={g.duplicateAria}
                  title={g.duplicateTitle}
                  onClick={() => void duplicateAgent(a)}
                >
                  <Copy size={13} />
                </button>
                <button
                  className="btn btn-danger btn-sm"
                  type="button"
                  aria-label={g.deleteAria}
                  title={g.deleteTitle}
                  onClick={() => void removeAgent(a)}
                >
                  <Trash2 size={13} />
                </button>
              </div>
            </div>
          </PixelCard>
          )
        })}
      </div>}

      <div className="panel panel-pad bench-panel">
        <div className="bench-head">
          <h2 className="col-title" style={{ margin: 0 }}>{g.benchTitle}{testAgent ? ` · ${testAgent.name}` : ''}</h2>
          {testAgent && <span className="badge" data-tone="accent">{testAgent.model}</span>}
          <span style={{ flex: 1 }} />
          {(testOut || testCalls.length > 0) && (
            <button className="btn btn-ghost btn-sm" type="button" onClick={() => { setTestOut(''); setTestCalls([]); setTestFailed(false) }}>
              {locale === 'en' ? 'Clear' : '清空'}
            </button>
          )}
        </div>
        {!testAgent ? (
          <p className="hint">{g.benchHint}</p>
        ) : (
          <div className="bench-layout">
            <div className="bench-compose">
              <div className="bench-examples">
                <span className="bench-examples__label">{g.benchExamples}</span>
                {g.examplePrompts.map((p) => (
                  <button className="chip" type="button" key={p} onClick={() => setTestIn(p)}>{p}</button>
                ))}
              </div>
              <form className="bench-form" onSubmit={runTest}>
                <textarea
                  className="input"
                  rows={3}
                  value={testIn}
                  onChange={(e) => setTestIn(e.target.value)}
                  placeholder={g.benchPh}
                  aria-label={g.benchInputAria}
                  onKeyDown={(e) => { if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) { e.preventDefault(); e.currentTarget.form?.requestSubmit() } }}
                />
                <button className="btn btn-primary" type="submit" disabled={!testIn.trim() || testing}>
                  <Play size={14} /> {testing ? g.running : g.run}
                </button>
              </form>
              <p className="hint">{g.benchHint}</p>
            </div>
            <div className="bench-result">
              <div className="bench-result-head">
                <span>{g.benchTrace}</span>
                {testing && <StatusMark status="running" size={16} label={g.running} fontSize={12} />}
                {!testing && testOut && !testFailed && <StatusMark status="done" size={16} label={g.runDone} fontSize={12} strike={false} />}
                {testFailed && !testing && <StatusMark status="failed" size={16} label={g.runFailed} fontSize={12} strike={false} />}
              </div>
              <div className="bench-result-body">
                <div className="bench-trace">
                  {testCalls.length > 0 ? <ToolTrace calls={testCalls} /> : <p className="hint">{locale === 'en' ? 'Tool trace appears here after a run.' : '运行后在这里查看工具轨迹。'}</p>}
                </div>
                <div className="bench-answer">
                  {testOut ? <div className="bench-output">{testOut}</div> : <p className="hint">{g.testResultEmpty}</p>}
                </div>
              </div>
            </div>
          </div>
        )}
      </div>

      <Modal
        open={wizard}
        title={g.createTitle}
        onClose={() => { if (!busy) setWizard(false) }}
        shouldBlockClose={() => busy}
        width={580}
        footer={busy ? <span className="hint">{g.creating}</span> : undefined}
      >
        <Stepper
          key={wizKey}
          initialStep={wizStep}
          onStepChange={setWizStep}
          backButtonText={g.stepBack}
          nextButtonText={g.stepNext}
          finalButtonText={g.stepFinal}
          nextButtonProps={busy ? { disabled: true } : undefined}
          onFinalStepCompleted={async () => {
            const name = newName.trim()
            if (!name) { toast.error(g.errNameRequired); resetWizard(1); return }
            if (USE_MOCK) {
              const created = { ...AGENTS[0], id: String(Date.now()), name, desc: newDesc.trim(), icon: wizIcon, status: 'DRAFT' as const }
              setAgents((list) => [created, ...list])
              setTestAgent(created)
              setWizard(false)
              toast.success(g.createdTitle, g.createdMockBody)
              return
            }
            setBusy(true)
            try {
              /* code 是稳定标识：前端生成合法的小写短码，服务端校验唯一性 */
              const created = await api.agents.create({
                code: `agent-${Date.now().toString(36)}`,
                name,
                description: newDesc.trim() || null,
                systemPrompt: newPrompt.trim() || null,
                /* 空数组 = 不限制（检索全部知识库 / 装配全部启用工具），语义见后端 DTO 注释 */
                kbIds: wizKbIds,
                toolCodes: wizTools,
              })
              const rows = await fetchAgents()
              setAgents(rows)
              const createdItem = rows.find((r) => r.id === created.id)
              if (createdItem) setTestAgent(createdItem)
              setWizard(false)
              toast.success(g.createdTitle, g.createdRealBody)
            } catch (e) {
              toast.error(g.createFailTitle, errMessage(e))
              /* Stepper 此刻已进入"完成"态，重挂载回确认步让用户可修改后重试 */
              resetWizard(3)
            } finally {
              setBusy(false)
            }
          }}
        >
          <Step>
            <div style={{ padding: '10px 0 6px' }}>
              <div className="field">
                <label htmlFor="agent-name">{g.nameLabel}</label>
                <input id="agent-name" className="input" placeholder={g.namePh} aria-label={g.nameAria} value={newName} onChange={(e) => setNewName(e.target.value)} maxLength={128} />
              </div>
              <div className="field">
                <label htmlFor="agent-desc">{g.descLabel}</label>
                <textarea id="agent-desc" className="input" rows={3} maxLength={512} placeholder={g.descPh} aria-label={g.descAria} value={newDesc} onChange={(e) => setNewDesc(e.target.value)} />
              </div>
              <div className="field">
                <label>{g.iconLabel}</label>
                <div className="agent-icon-picker" role="radiogroup" aria-label={g.iconLabel}>
                  {AGENT_ICON_IDS.map((id) => {
                    const Icon = AGENT_ICONS[id]
                    return (
                    <button
                      key={id}
                      type="button"
                      role="radio"
                      aria-checked={wizIcon === id}
                      className="agent-icon-opt"
                      data-on={wizIcon === id ? '' : undefined}
                      onClick={() => setWizIcon(id)}
                    >
                      <Icon size={16} aria-hidden="true" />
                    </button>
                    )
                  })}
                </div>
              </div>
              <p className="hint">{g.draftHint}</p>
              <p className="hint">{g.modelRoleHint}</p>
            </div>
          </Step>
          <Step>
            <div style={{ padding: '10px 0 6px' }}>
              <div className="field">
                <label>{g.bindKbLabel}{!USE_MOCK && ` (${g.bindKbHint})`}</label>
                {!USE_MOCK && kbChoices.length === 0 && <p className="hint">{g.bindKbEmpty}</p>}
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
                  {(USE_MOCK ? ['我的 Java 笔记', 'Redis 源码阅读', '面经合集'] : kbChoices.map((kb) => kb.name)).map((label, i) => {
                    const key = USE_MOCK ? label : kbChoices[i].id
                    const on = wizKbIds.includes(key)
                    return (
                      <button
                        type="button"
                        key={key}
                        className="chip"
                        aria-pressed={on}
                        onClick={() => toggleIn(wizKbIds, key, setWizKbIds)}
                        style={on ? { borderColor: 'var(--primary)', color: 'var(--primary)', background: 'var(--primary-container)' } : undefined}
                      >
                        {on && <Check size={12} />} {label}
                      </button>
                    )
                  })}
                </div>
              </div>
              <div className="field">
                <label>{g.bindToolsLabel}{!USE_MOCK && ` (${g.bindToolsHint})`}</label>
                {!USE_MOCK && toolChoices.length === 0 && <p className="hint">{g.bindToolsEmpty}</p>}
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
                  {(USE_MOCK
                    ? ['knowledge_search（知识库检索）', 'calculator（数学计算）']
                    : toolChoices.map((t) => `${t.code}（${t.name}）`)
                  ).map((label, i) => {
                    const code = USE_MOCK ? label.split('（')[0] : toolChoices[i].code
                    const on = wizTools.includes(code)
                    return (
                      <button
                        type="button"
                        key={code}
                        className="chip"
                        aria-pressed={on}
                        onClick={() => toggleIn(wizTools, code, setWizTools)}
                        style={on ? { borderColor: 'var(--primary)', color: 'var(--primary)', background: 'var(--primary-container)' } : undefined}
                      >
                        {on && <Check size={12} />} {label}
                      </button>
                    )
                  })}
                </div>
              </div>
              <div className="field">
                <label htmlFor="agent-prompt">{g.instructionsLabel}</label>
                <textarea id="agent-prompt" className="input" rows={4} value={newPrompt} placeholder={g.instructionsPh} onChange={(e) => setNewPrompt(e.target.value)} />
                <p className="hint">{g.instructionsHint}</p>
              </div>
              <div className="field">
                <label>{g.creativityLabel}</label>
                <div className="agent-dial-row">
                  <CometDial
                    value={Math.round(wizCreativity * 100)}
                    min={0}
                    max={100}
                    step={5}
                    unit="%"
                    label={g.creativityLabel}
                    size={150}
                    sweep={300}
                    thickness={5}
                    onChange={(v) => setWizCreativity(v / 100)}
                  />
                  <p className="hint" style={{ maxWidth: 220 }}>{g.creativityHint}</p>
                </div>
              </div>
            </div>
          </Step>
          <Step>
            <div style={{ padding: '10px 0 6px' }}>
              <h4 className="wizard-subtitle">{g.testStepTitle}</h4>
              <div className="agent-dial-row">
                <CometDial value={wizardCompleteness} min={0} max={100} step={5} unit="%" label="完备度" size={130} sweep={300} thickness={5} />
                <p className="hint" style={{ maxWidth: 240 }}>
                  名称、描述、指令、知识库与工具五项配置齐全时达到 100%。
                </p>
              </div>
              <p className="hint" style={{ marginTop: 4 }}>{g.testStepHint}</p>
              <div className="wizard-test-row">
                <input
                  className="input"
                  value={wizTestIn}
                  placeholder={g.benchPh}
                  aria-label={g.benchInputAria}
                  onChange={(e) => setWizTestIn(e.target.value)}
                />
                <button
                  className="btn btn-ghost"
                  type="button"
                  disabled={!wizTestIn.trim() || wizTesting}
                  onClick={() => {
                    setWizTesting(true)
                    setWizTestOut('')
                    window.setTimeout(() => {
                      setWizTestOut(g.mockTestOut(newName.trim() || 'Agent'))
                      setWizTesting(false)
                    }, 700)
                  }}
                >
                  <Play size={14} /> {wizTesting ? g.testRunning : g.testRun}
                </button>
              </div>
              <div className="wizard-test-result">
                <span className="hint">{g.testResultTitle}</span>
                {wizTestOut ? <div className="bench-output">{wizTestOut}</div> : <p className="hint">{g.testResultEmpty}</p>}
              </div>
              <p style={{ margin: 0 }}>{g.confirmIntro}</p>
              <ul className="wizard-summary">
                <li>{g.sumName(newName.trim() || g.sumEmpty)}</li>
                <li>{g.sumKb(USE_MOCK ? g.sumDemo : (wizKbIds.length ? g.sumLimit(wizKbIds.length) : g.sumAll))}</li>
                <li>{g.sumTools(USE_MOCK ? g.sumDemo : (wizTools.length ? wizTools.join(' · ') : g.sumAll))}</li>
                <li>{g.draftHint}</li>
                <li>{g.instructionsLabel}: {newPrompt.trim() ? `${newPrompt.trim().slice(0, 40)}${newPrompt.trim().length > 40 ? '…' : ''}` : g.sumEmpty}</li>
              </ul>
            </div>
          </Step>
        </Stepper>
      </Modal>

      <Modal
        open={editing !== null}
        title={g.editTitleModal}
        onClose={() => { if (!savingEdit) setEditing(null) }}
        shouldBlockClose={() => savingEdit}
        width={480}
        footer={
          <>
            <button className="btn btn-ghost" type="button" onClick={() => setEditing(null)} disabled={savingEdit}>{g.cancel}</button>
            <button className="btn btn-primary" type="button" onClick={() => void saveEdit()} disabled={savingEdit}>{savingEdit ? g.saving : g.save}</button>
          </>
        }
      >
        <div className="field">
          <label htmlFor="agent-edit-name">{g.nameLabel}</label>
          <input id="agent-edit-name" className="input" value={editName} onChange={(e) => setEditName(e.target.value)} maxLength={128} />
        </div>
        <div className="field">
          <label htmlFor="agent-edit-desc">{g.descLabel}</label>
          <textarea id="agent-edit-desc" className="input" rows={3} value={editDesc} onChange={(e) => setEditDesc(e.target.value)} maxLength={512} />
        </div>
      </Modal>
    </div>
  )
}
