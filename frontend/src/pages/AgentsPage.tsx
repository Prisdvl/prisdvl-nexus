import { useEffect, useState, type FormEvent } from 'react'
import { Bot, Check, Copy, Pencil, Play, Plus, RefreshCw, Trash2 } from 'lucide-react'
import PixelCard from '@/bits/PixelCard/PixelCard'
import StatusMark from '@/bits/StatusMark/StatusMark'
import Stepper, { Step } from '@/bits/Stepper/Stepper'
import Modal from '@/components/Modal'
import { StateEmpty } from '@/components/Skeleton'
import { toast } from '@/lib/toast'
import { useParticleColors } from '@/lib/palette'
import { AGENTS, type AgentItem } from '@/data'
import { api, USE_MOCK, errMessage, type ToolInfo } from '@/lib/api'
import { fetchAgents, fetchKbs } from '@/lib/backendData'
import type { KbItem } from '@/data'

export default function AgentsPage() {
  /* 粒子色与知识库卡片统一：跟随当前配色与明暗 */
  const particleColors = useParticleColors()
  const [agents, setAgents] = useState<AgentItem[]>(USE_MOCK ? AGENTS : [])
  const [loadingAgents, setLoadingAgents] = useState(!USE_MOCK)
  const [agentsError, setAgentsError] = useState('')
  const [newName, setNewName] = useState('')
  const [newDesc, setNewDesc] = useState('')
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
    try {
      if (USE_MOCK) {
        await new Promise((resolve) => setTimeout(resolve, 700))
        setTestOut(`Agent「${testAgent.name}」：演示回答（mock 模式）`)
        toast.info('已完成本地演示', '当前为 mock 模式，没有调用后端')
        return
      }
      const result = await api.agents.testChat(testAgent.id, testIn.trim())
      setTestOut(result.answer)
      toast.success('Agent 执行完成', result.usedTools ? `调用工具 ${result.toolCalls?.length ?? 0} 次` : '本次未调用工具')
    } catch (e) {
      setTestFailed(true)
      const msg = errMessage(e)
      setTestOut(msg)
      toast.error('Agent 执行失败', msg)
    } finally {
      setTesting(false)
    }
  }

  const removeAgent = async (a: AgentItem) => {
    if (USE_MOCK) {
      const idx = agents.findIndex((x) => x.id === a.id)
      setAgents((list) => list.filter((x) => x.id !== a.id))
      toast.undo('已删除 Agent', a.name, () => {
        setAgents((list) => {
          const out = [...list]
          out.splice(Math.min(idx, out.length), 0, a)
          return out
        })
        toast.success('已恢复 Agent', a.name)
      })
      return
    }
    /* 后端删除不可恢复 → 明确二次确认 */
    if (!window.confirm(`确定删除 Agent「${a.name}」吗？此操作不可恢复。`)) return
    try {
      await api.agents.remove(a.id)
      setAgents(await fetchAgents())
      if (testAgent?.id === a.id) setTestAgent(null)
      toast.success('Agent 已删除', a.name)
    } catch (e) {
      toast.error('删除失败', errMessage(e))
    }
  }

  const duplicateAgent = async (a: AgentItem) => {
    if (USE_MOCK) {
      const copy = { ...a, id: String(Date.now()), name: `${a.name} 副本` }
      setAgents((list) => {
        const out = [...list]
        out.splice(out.findIndex((x) => x.id === a.id) + 1, 0, copy)
        return out
      })
      toast.success('已复制 Agent', copy.name)
      return
    }
    try {
      /* 复制走服务端 duplicate：副本回到 DRAFT，code 由服务端生成，避免前端伪造标识 */
      await api.agents.duplicate(a.id)
      setAgents(await fetchAgents())
      toast.success('已复制 Agent', `${a.name} 副本（草稿）`)
    } catch (e) {
      toast.error('复制失败', errMessage(e))
    }
  }

  const openEdit = (a: AgentItem) => {
    setEditing(a)
    setEditName(a.name)
    setEditDesc(a.desc)
  }

  const saveEdit = async () => {
    if (!editing || savingEdit) return
    const name = editName.trim()
    if (!name) { toast.error('名称不能为空'); return }
    if (USE_MOCK) {
      setAgents((list) => list.map((x) => (x.id === editing.id ? { ...x, name, desc: editDesc.trim() } : x)))
      setEditing(null)
      toast.success('已保存修改', name)
      return
    }
    setSavingEdit(true)
    try {
      await api.agents.update(editing.id, { name, description: editDesc.trim() || null })
      setAgents(await fetchAgents())
      setEditing(null)
      toast.success('已保存修改', name)
    } catch (e) {
      toast.error('保存失败', errMessage(e))
    } finally {
      setSavingEdit(false)
    }
  }

  const toggleIn = (list: string[], key: string, set: (v: string[]) => void) => {
    set(list.includes(key) ? list.filter((x) => x !== key) : [...list, key])
  }

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <p className="eyebrow" style={{ margin: 0 }}>Agent</p>
          <h1 className="page-title">我的 Agent</h1>
          <p className="page-sub">把「知识和工具」打包成一个会干活的身份</p>
        </div>
        <button className="btn btn-primary" type="button" onClick={openWizard}><Plus size={16} /> 新建 Agent</button>
      </div>

      {loadingAgents && <p className="hint" role="status">正在加载 Agent…</p>}
      {!loadingAgents && agentsError && (
        <div className="panel panel-pad">
          <p className="form-error active" role="alert" style={{ marginTop: 0 }}>Agent 列表加载失败：{agentsError}</p>
          <button className="btn btn-ghost btn-sm" type="button" onClick={() => void loadAgents()}><RefreshCw size={13} /> 重试</button>
        </div>
      )}
      {!loadingAgents && !agentsError && agents.length === 0 && (
        <div className="panel">
          <StateEmpty
            icon={<Bot size={20} />}
            title="还没有 Agent"
            desc="Agent 会带着你绑定的知识库与工具干活：面试陪练、代码审查、简历润色都可以做成一个 Agent。"
            action={<button className="btn btn-primary btn-sm" type="button" onClick={openWizard}><Plus size={14} /> 新建 Agent</button>}
          />
        </div>
      )}

      {agents.length > 0 && <div className="agent-grid">
        {agents.map((a) => (
          <PixelCard key={a.id} gap={8} speed={45} colors={particleColors}>
            <div className="agent-inner">
              <div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <Bot size={15} style={{ color: 'var(--primary)' }} />
                  <h2 className="nm">{a.name}</h2>
                </div>
                <p className="ds">{a.desc}</p>
                <div className="tags">
                  <span className="chip" style={{ fontSize: 11 }}>{a.model}</span>
                  {a.tools.map((t) => <span className="badge" data-tone="accent" key={t}>{t}</span>)}
                  <span className="badge">绑定 {a.kb}</span>
                </div>
              </div>
              <div className="acts">
                <button className="btn btn-primary btn-sm" type="button" onClick={() => { setTestAgent(a); setTestOut(''); setTestFailed(false) }}><Play size={13} /> 测试</button>
                <button className="btn btn-ghost btn-sm" type="button" aria-label="编辑" title="编辑名称与描述" onClick={() => openEdit(a)}><Pencil size={13} /></button>
                <button
                  className="btn btn-ghost btn-sm"
                  type="button"
                  aria-label="复制"
                  title="复制为草稿副本"
                  onClick={() => void duplicateAgent(a)}
                >
                  <Copy size={13} />
                </button>
                <button
                  className="btn btn-danger btn-sm"
                  type="button"
                  aria-label="删除"
                  title="删除 Agent"
                  onClick={() => void removeAgent(a)}
                >
                  <Trash2 size={13} />
                </button>
              </div>
            </div>
          </PixelCard>
        ))}
      </div>}

      <div className="panel panel-pad" style={{ marginTop: 24 }}>
        <h2 className="col-title" style={{ marginTop: 0 }}>测试台{testAgent ? ` · ${testAgent.name}` : ''}</h2>
        {!testAgent ? (
          <p className="hint">从上方选择一个 Agent 后在这里试跑；草稿状态也可以测试。</p>
        ) : (
          <>
            <form onSubmit={runTest} style={{ display: 'flex', gap: 10, marginBottom: 12 }}>
              <input className="input" value={testIn} onChange={(e) => setTestIn(e.target.value)} placeholder="输入一条指令，例如：帮我出一道 JVM 调优题并讲解" aria-label="测试指令" />
              <button className="btn btn-primary" type="submit" disabled={!testIn.trim() || testing}>
                <Play size={14} /> 运行
              </button>
            </form>
            {testing && (
              <StatusMark status="running" size={18} label="Agent 正在执行（工具调用中）" fontSize={14} />
            )}
            {!testing && testOut && !testFailed && (
              <StatusMark status="done" size={18} label="执行完成" fontSize={14} strike={false} />
            )}
            {testFailed && !testing && (
              <StatusMark status="failed" size={18} label="执行失败" fontSize={14} strike={false} />
            )}
            {testOut && (
              <div style={{ marginTop: 12, padding: 14, border: '1px solid var(--border)', borderRadius: 12, fontSize: 13.5, whiteSpace: 'pre-wrap', lineHeight: 1.7, background: 'var(--surface)' }}>
                {testOut}
              </div>
            )}
          </>
        )}
      </div>

      <Modal
        open={wizard}
        title="新建 Agent"
        onClose={() => { if (!busy) setWizard(false) }}
        shouldBlockClose={() => busy}
        width={580}
        footer={busy ? <span className="hint">正在创建…</span> : undefined}
      >
        <Stepper
          key={wizKey}
          initialStep={wizStep}
          onStepChange={setWizStep}
          backButtonText="上一步"
          nextButtonText="下一步"
          finalButtonText="创建 Agent"
          nextButtonProps={busy ? { disabled: true } : undefined}
          onFinalStepCompleted={async () => {
            const name = newName.trim()
            if (!name) { toast.error('请先填写名称'); resetWizard(1); return }
            if (USE_MOCK) {
              const created = { ...AGENTS[0], id: String(Date.now()), name, desc: newDesc.trim() }
              setAgents((list) => [created, ...list])
              setTestAgent(created)
              setWizard(false)
              toast.success('Agent 已创建', 'mock 模式：未调用后端')
              return
            }
            setBusy(true)
            try {
              /* code 是稳定标识：前端生成合法的小写短码，服务端校验唯一性 */
              const created = await api.agents.create({
                code: `agent-${Date.now().toString(36)}`,
                name,
                description: newDesc.trim() || null,
                /* 空数组 = 不限制（检索全部知识库 / 装配全部启用工具），语义见后端 DTO 注释 */
                kbIds: wizKbIds,
                toolCodes: wizTools,
              })
              const rows = await fetchAgents()
              setAgents(rows)
              const createdItem = rows.find((r) => r.id === created.id)
              if (createdItem) setTestAgent(createdItem)
              setWizard(false)
              toast.success('Agent 已创建', '初始状态为草稿，可先在测试台验证再发布')
            } catch (e) {
              toast.error('创建失败', errMessage(e))
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
                <label htmlFor="agent-name">名称</label>
                <input id="agent-name" className="input" placeholder="例如：代码审查员" aria-label="Agent 名称" value={newName} onChange={(e) => setNewName(e.target.value)} maxLength={128} />
              </div>
              <div className="field">
                <label htmlFor="agent-desc">描述</label>
                <textarea id="agent-desc" className="input" rows={3} maxLength={512} placeholder="这个 Agent 擅长做什么、以什么语气回答" aria-label="Agent 描述" value={newDesc} onChange={(e) => setNewDesc(e.target.value)} />
              </div>
              <p className="hint">模型角色由系统配置统一管理，当前版本不在创建向导中选择。</p>
            </div>
          </Step>
          <Step>
            <div style={{ padding: '10px 0 6px' }}>
              <div className="field">
                <label>绑定知识库{!USE_MOCK && '（不选 = 不限制，可检索你的全部知识库）'}</label>
                {!USE_MOCK && kbChoices.length === 0 && <p className="hint">还没有可用的知识库；不选即表示不限制。</p>}
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
                <label>工具权限{!USE_MOCK && '（不选 = 不限制，装配全部已启用工具）'}</label>
                {!USE_MOCK && toolChoices.length === 0 && <p className="hint">没有获取到工具目录；不选即表示不限制。</p>}
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
            </div>
          </Step>
          <Step>
            <div style={{ padding: '10px 0 6px' }}>
              <p style={{ margin: 0 }}>确认创建：创建后可在测试台先验证，再发布到会话中选用。</p>
              <ul style={{ margin: '10px 0 0', paddingLeft: 18, fontSize: 13, color: 'var(--text-2)', lineHeight: 1.9 }}>
                <li>名称：{newName.trim() || '（未填写）'}</li>
                <li>知识库：{USE_MOCK ? '演示选择' : (wizKbIds.length ? `限定 ${wizKbIds.length} 个` : '不限制')}</li>
                <li>工具：{USE_MOCK ? '演示选择' : (wizTools.length ? wizTools.join('、') : '不限制')}</li>
              </ul>
            </div>
          </Step>
        </Stepper>
      </Modal>

      <Modal
        open={editing !== null}
        title="编辑 Agent"
        onClose={() => { if (!savingEdit) setEditing(null) }}
        shouldBlockClose={() => savingEdit}
        width={480}
        footer={
          <>
            <button className="btn btn-ghost" type="button" onClick={() => setEditing(null)} disabled={savingEdit}>取消</button>
            <button className="btn btn-primary" type="button" onClick={() => void saveEdit()} disabled={savingEdit}>{savingEdit ? '保存中…' : '保存'}</button>
          </>
        }
      >
        <div className="field">
          <label htmlFor="agent-edit-name">名称</label>
          <input id="agent-edit-name" className="input" value={editName} onChange={(e) => setEditName(e.target.value)} maxLength={128} />
        </div>
        <div className="field">
          <label htmlFor="agent-edit-desc">描述</label>
          <textarea id="agent-edit-desc" className="input" rows={3} value={editDesc} onChange={(e) => setEditDesc(e.target.value)} maxLength={512} />
        </div>
      </Modal>
    </div>
  )
}
