import { useState, type FormEvent } from 'react'
import { Bot, Check, Copy, Pencil, Play, Plus, Trash2, X } from 'lucide-react'
import PixelCard from '@/bits/PixelCard/PixelCard'
import SpringCheck from '@/bits/SpringCheck/SpringCheck'
import { useParticleColors } from '@/lib/palette'
import Stepper, { Step } from '@/bits/Stepper/Stepper'
import StatusMark from '@/bits/StatusMark/StatusMark'
import Modal from '@/components/Modal'
import { StateEmpty } from '@/components/Skeleton'
import { toast } from '@/lib/toast'
import { AGENTS, type AgentItem } from '@/data'

const PIXEL = {
  terracotta: '#fde8dc,#f7cbb2,#d97a52',
  turquoise: '#e0f6f8,#b3ebf0,#2fb3c9',
  lime: '#f0f9dd,#d9f2a8,#8cc63f',
}

export default function AgentsPage() {
  /* 粒子色与知识库卡片统一：跟随当前配色与明暗 */
  const particleColors = useParticleColors()
  const [agents, setAgents] = useState<AgentItem[]>(AGENTS)
  const [wizard, setWizard] = useState(false)
  const [testAgent, setTestAgent] = useState<AgentItem>(AGENTS[0])
  const [testIn, setTestIn] = useState('')
  const [testing, setTesting] = useState(false)
  const [testOut, setTestOut] = useState('')
  const [testFailed, setTestFailed] = useState(false)

  const [quickFail, setQuickFail] = useState(false)

  const runTest = (e: FormEvent) => {
    e.preventDefault()
    if (!testIn.trim() || testing) return
    setTesting(true)
    setTestOut('')
    setTestFailed(false)
    setTimeout(() => {
      setTesting(false)
      if (quickFail) {
        setTestFailed(true)
        toast.error('Agent 执行失败', '工具 code_runner 超时（原型演示失败态）')
        return
      }
      setTestOut(`Agent「${testAgent.name}」：已结合知识库「${testAgent.kb}」作答。\n\n这是原型演示的输出：真实实现中会展示 Tool Calling 轨迹（knowledge_search → code_runner）与逐步推理过程。`)
      toast.success('Agent 执行完成', `${testAgent.name} · 3 次工具调用`)
    }, 2200)
  }

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <p className="eyebrow" style={{ margin: 0 }}>Agent</p>
          <h1 className="page-title">我的 Agent</h1>
          <p className="page-sub">把「知识和工具」打包成一个会干活的身份</p>
        </div>
        <button className="btn btn-primary" type="button" onClick={() => setWizard(true)}><Plus size={16} /> 新建 Agent</button>
      </div>

      {agents.length === 0 && (
        <div className="panel">
          <StateEmpty
            icon={<Bot size={20} />}
            title="还没有 Agent"
            desc="Agent 会带着你绑定的知识库与工具干活：面试陪练、代码审查、简历润色都可以做成一个 Agent。"
            action={<button className="btn btn-primary btn-sm" type="button" onClick={() => setWizard(true)}><Plus size={14} /> 新建 Agent</button>}
          />
        </div>
      )}

      {agents.length > 0 && <div className="agent-grid">
        {agents.map((a, i) => (
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
                <button className="btn btn-primary btn-sm" type="button" onClick={() => { setTestAgent(a); setTestOut('') }}><Play size={13} /> 测试</button>
                <button className="btn btn-ghost btn-sm" type="button" aria-label="编辑"><Pencil size={13} /></button>
                <button
                  className="btn btn-ghost btn-sm"
                  type="button"
                  aria-label="复制"
                  onClick={() => {
                    const copy = { ...a, id: Date.now(), name: `${a.name} 副本` }
                    setAgents((list) => {
                      const out = [...list]
                      out.splice(out.findIndex((x) => x.id === a.id) + 1, 0, copy)
                      return out
                    })
                    toast.success('已复制 Agent', copy.name)
                  }}
                >
                  <Copy size={13} />
                </button>
                <button
                  className="btn btn-danger btn-sm"
                  type="button"
                  aria-label="删除"
                  onClick={() => {
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
                  }}
                >
                  <Trash2 size={13} />
                </button>
              </div>
            </div>
          </PixelCard>
        ))}
      </div>}

      <div className="panel panel-pad" style={{ marginTop: 24 }}>
        <h2 className="col-title" style={{ marginTop: 0 }}>测试台 · {testAgent.name}</h2>
        <form onSubmit={runTest} style={{ display: 'flex', gap: 10, marginBottom: 12 }}>
          <input className="input" value={testIn} onChange={(e) => setTestIn(e.target.value)} placeholder="输入一条指令，例如：帮我出一道 JVM 调优题并讲解"  aria-label="测试指令" />
          <button className="btn btn-primary" type="submit" disabled={!testIn.trim() || testing}>
            <Play size={14} /> 运行
          </button>
        </form>
        {testing && (
          <StatusMark status="running" size={18} label="Agent 正在执行（工具调用中）" fontSize={14} />
        )}
        {!testing && testOut && (
          <StatusMark status="done" size={18} label="执行完成" fontSize={14} strike={false} />
        )}
        {testFailed && !testing && (
          <StatusMark status="failed" size={18} label="执行失败：工具超时" fontSize={14} strike={false} />
        )}
        {testOut && (
          <div style={{ marginTop: 12, padding: 14, border: '1px solid var(--border)', borderRadius: 12, fontSize: 13.5, whiteSpace: 'pre-wrap', lineHeight: 1.7, background: 'var(--surface)' }}>
            {testOut}
          </div>
        )}
      </div>

      <Modal open={wizard} title="新建 Agent" onClose={() => setWizard(false)} width={580}>
        <>
            <Stepper
              backButtonText="上一步"
              nextButtonText="下一步"
              onFinalStepCompleted={() => {
                setWizard(false)
                const created = { ...AGENTS[0], id: Date.now(), name: '新 Agent' }
                setAgents((list) => [created, ...list])
                setTestAgent(created)
                toast.success('Agent 已创建', '已绑定知识库与 knowledge_search 工具')
              }}
            >
              <Step>
                <div style={{ padding: '10px 0 6px' }}>
                  <div className="field">
                    <label>名称</label>
                    <input className="input" placeholder="例如：代码审查员"  aria-label="Agent 名称" />
                  </div>
                  <div className="field">
                    <label>模型</label>
                    <select className="input" defaultValue="deepseek-v4-flash">
                      <option value="deepseek-v4-flash">deepseek-v4-flash（推荐）</option>
                      <option value="glm-5.3">glm-5.3</option>
                      <option value="kimi-k2.6">kimi-k2.6</option>
                    </select>
                  </div>
                  <div className="field">
                    <label>描述</label>
                    <textarea className="input" rows={3} placeholder="这个 Agent 擅长做什么、以什么语气回答"  aria-label="Agent 描述" />
                  </div>
                </div>
              </Step>
              <Step>
                <div style={{ padding: '10px 0 6px' }}>
                  <div className="field">
                    <label>绑定知识库</label>
                    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
                      {['我的 Java 笔记', 'Redis 源码阅读', '面经合集'].map((kb, i) => (
                        <button type="button" key={kb} className="chip" style={i === 0 ? { borderColor: 'var(--primary)', color: 'var(--primary)', background: 'var(--primary-container)' } : undefined}>
                          <Check size={12} /> {kb}
                        </button>
                      ))}
                    </div>
                  </div>
                  <div className="field">
                    <label>工具权限</label>
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                      {['knowledge_search（知识库检索）', 'calculator（数学计算）', 'code_runner（沙箱执行）'].map((t) => (
                        <SpringCheck
                          key={t}
                          label={t}
                          defaultChecked={t.startsWith('knowledge')}
                          ariaLabel={`启用工具 ${t}`}
                          color="var(--text-2)"
                          fillColor="var(--primary)"
                          checkColor="var(--on-primary-strong)"
                          boxSize={22}
                          boxRadius={7}
                          fontSize={13.5}
                          strike="none"
                          doneOpacity={0.7}
                        />
                      ))}
                    </div>
                  </div>
                </div>
              </Step>
              <Step>
                <div style={{ padding: '10px 0 6px' }}>
                  <p style={{ margin: 0 }}>确认创建：Agent 将获得知识库检索权限与所选工具。创建后即可在对话页头部选择这个 Agent。</p>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 10, fontSize: 13, color: 'var(--success)' }}>
                    <Check size={15} /> Tool Registry 已配置（knowledge_search / calculator）
                  </div>
                </div>
              </Step>
            </Stepper>
        </>
      </Modal>
    </div>
  )
}