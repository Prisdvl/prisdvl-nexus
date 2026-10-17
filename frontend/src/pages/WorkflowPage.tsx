import { useMemo, useState } from 'react'
import { BookOpen, Bot, CheckCircle2, Circle, GitBranch, Maximize2, Play, Plus, Save, Sparkles, Terminal, ZoomIn, ZoomOut } from 'lucide-react'
import LatticeLoader from '@/bits/LatticeLoader/LatticeLoader'
import { StateEmpty } from '@/components/Skeleton'
import { toast } from '@/lib/toast'
import { useLocale } from '@/lib/locale'
import '@/styles/plugins.css'
import '@/styles/workflow.css'

type NodeType = 'trigger' | 'knowledge' | 'agent' | 'tool' | 'condition' | 'output'
interface FlowNode { id: string; type: NodeType; title: string; desc: string; x: number; y: number }

const COPY = {
  zh: {
    eyebrow: 'Workflows', title: '工作流', sub: '把触发器、知识检索、Agent、工具与输出编排成可复用流程。',
    save: '保存', run: '运行', running: '运行中…', palette: '节点', inspector: '配置', empty: '从左侧添加一个节点开始编排。',
    types: {
      trigger: { title: '触发器', desc: '手动、定时或事件触发' }, knowledge: { title: '知识检索', desc: '按问题检索知识库切片' },
      agent: { title: 'Agent', desc: '调用配置好的 Agent' }, tool: { title: '工具', desc: '执行搜索、代码、表格等工具' },
      condition: { title: '条件分支', desc: '按结果决定后续路径' }, output: { title: '输出', desc: '生成回答、报告或文件' },
    } as Record<NodeType, { title: string; desc: string }>,
    fields: { name: '节点名称', schedule: '触发方式', kb: '知识库', topK: 'Top-K', model: '模型', instructions: '指令', tool: '工具', expression: '条件表达式', format: '输出格式' },
    log: '运行日志', saved: '工作流已保存', runDone: '运行完成', step: (n: string) => `执行 ${n}`,
  },
  en: {
    eyebrow: 'Workflows', title: 'Workflows', sub: 'Compose triggers, retrieval, agents, tools and outputs into reusable flows.',
    save: 'Save', run: 'Run', running: 'Running…', palette: 'Nodes', inspector: 'Inspector', empty: 'Add a node from the left to start building.',
    types: {
      trigger: { title: 'Trigger', desc: 'Manual, scheduled or event based' }, knowledge: { title: 'Knowledge', desc: 'Retrieve chunks for a question' },
      agent: { title: 'Agent', desc: 'Call a configured agent' }, tool: { title: 'Tool', desc: 'Run search, code, sheets and more' },
      condition: { title: 'Condition', desc: 'Branch on the result' }, output: { title: 'Output', desc: 'Produce an answer, report or file' },
    } as Record<NodeType, { title: string; desc: string }>,
    fields: { name: 'Node name', schedule: 'Trigger', kb: 'Knowledge base', topK: 'Top-K', model: 'Model', instructions: 'Instructions', tool: 'Tool', expression: 'Expression', format: 'Output format' },
    log: 'Run log', saved: 'Workflow saved', runDone: 'Run finished', step: (n: string) => `Running ${n}`,
  },
} as const

const ICONS: Record<NodeType, typeof Bot> = { trigger: Sparkles, knowledge: BookOpen, agent: Bot, tool: Terminal, condition: GitBranch, output: CheckCircle2 }
const INITIAL: FlowNode[] = [
  { id: 'n1', type: 'trigger', title: '手动触发', desc: '点击运行', x: 60, y: 150 },
  { id: 'n2', type: 'knowledge', title: '知识检索', desc: 'Top-K 6', x: 280, y: 70 },
  { id: 'n3', type: 'agent', title: '面试助手', desc: 'deepseek-v4-flash', x: 500, y: 150 },
  { id: 'n4', type: 'tool', title: '计算器', desc: '复杂度评估', x: 720, y: 60 },
  { id: 'n5', type: 'condition', title: '命中充分？', desc: 'score > 0.7', x: 720, y: 250 },
  { id: 'n6', type: 'output', title: '回答', desc: 'Markdown', x: 930, y: 150 },
]
const EDGES = [['n1', 'n2'], ['n2', 'n3'], ['n3', 'n4'], ['n3', 'n5'], ['n4', 'n6'], ['n5', 'n6']] as const

export default function WorkflowPage() {
  const { locale } = useLocale()
  const c = COPY[locale]
  const [nodes, setNodes] = useState<FlowNode[]>(INITIAL)
  const [selected, setSelected] = useState('n3')
  const [zoom, setZoom] = useState(1)
  const [running, setRunning] = useState(false)
  const [done, setDone] = useState<string[]>([])
  const byId = useMemo(() => new Map(nodes.map((n) => [n.id, n])), [nodes])
  const selectedNode = nodes.find((n) => n.id === selected)

  const addNode = (type: NodeType) => {
    const id = `n${Date.now()}`
    setNodes((list) => [...list, { id, type, title: c.types[type].title, desc: c.types[type].desc, x: 80 + (list.length % 4) * 200, y: 330 }])
    setSelected(id)
  }
  const run = () => {
    if (running) return
    setRunning(true); setDone([])
    nodes.forEach((n, i) => window.setTimeout(() => setDone((prev) => [...prev, n.id]), 240 * (i + 1)))
    window.setTimeout(() => { setRunning(false); toast.success(c.runDone, c.title) }, 240 * (nodes.length + 1))
  }

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <p className="eyebrow" style={{ margin: 0 }}>{c.eyebrow}</p>
          <h1 className="page-title">{c.title}</h1>
          <p className="page-sub">{c.sub}</p>
        </div>
        <div className="wf-head-actions">
          <button className="btn btn-ghost" type="button" onClick={() => toast.success(c.saved, c.title)}><Save size={15} /> {c.save}</button>
          <button className="btn btn-primary" type="button" onClick={run} disabled={running}>
            {running ? <LatticeLoader status="working" label="" pattern="sweep" grid={3} cellSize={3.5} gap={1.5} showTimer={false} /> : <Play size={15} />}
            {running ? c.running : c.run}
          </button>
        </div>
      </div>
      <div className="wf-layout">
        <aside className="wf-palette">
          <h2 className="wf-panel-title">{c.palette}</h2>
          {(Object.keys(c.types) as NodeType[]).map((type) => {
            const Icon = ICONS[type]
            return (
              <button className="wf-palette-item" type="button" key={type} onClick={() => addNode(type)}>
                <span className="wf-palette-item__icon"><Icon size={15} aria-hidden="true" /></span>
                <span className="wf-palette-item__text"><b>{c.types[type].title}</b><small>{c.types[type].desc}</small></span>
                <Plus size={13} className="wf-palette-item__plus" aria-hidden="true" />
              </button>
            )
          })}
        </aside>
        <section className="wf-canvas-wrap">
          <div className="wf-canvas-toolbar">
            <button className="plugin-icon-btn" type="button" aria-label="zoom out" onClick={() => setZoom((z) => Math.max(0.6, +(z - 0.1).toFixed(2)))}><ZoomOut size={15} /></button>
            <span className="wf-zoom">{Math.round(zoom * 100)}%</span>
            <button className="plugin-icon-btn" type="button" aria-label="zoom in" onClick={() => setZoom((z) => Math.min(1.4, +(z + 0.1).toFixed(2)))}><ZoomIn size={15} /></button>
            <button className="plugin-icon-btn" type="button" aria-label="reset zoom" onClick={() => setZoom(1)}><Maximize2 size={14} /></button>
          </div>
          <div className="wf-canvas" role="application" aria-label={c.title}>
            <div className="wf-canvas__inner" style={{ transform: `scale(${zoom})` }}>
              <svg className="wf-edges" width={1060} height={420} aria-hidden="true">
                {EDGES.map(([from, to], i) => {
                  const a = byId.get(from); const b = byId.get(to)
                  if (!a || !b) return null
                  const x1 = a.x + 170; const y1 = a.y + 34; const x2 = b.x; const y2 = b.y + 34
                  return <path key={i} d={`M ${x1} ${y1} C ${x1 + 60} ${y1}, ${x2 - 60} ${y2}, ${x2} ${y2}`} className="wf-edge" />
                })}
              </svg>
              {nodes.map((node) => {
                const Icon = ICONS[node.type]
                const state = running ? (done.includes(node.id) ? 'done' : 'running') : 'idle'
                return (
                  <button className="wf-node" type="button" key={node.id} data-type={node.type} data-state={state} data-on={selected === node.id ? '' : undefined} style={{ left: node.x, top: node.y }} onClick={() => setSelected(node.id)}>
                    <span className="wf-node__icon"><Icon size={14} aria-hidden="true" /></span>
                    <span className="wf-node__text"><b>{node.title}</b><small>{node.desc}</small></span>
                    {state === 'running' && <LatticeLoader status="working" label="" pattern="sweep" grid={3} cellSize={3} gap={1} showTimer={false} />}
                    {state === 'done' && <CheckCircle2 size={13} className="wf-node__done" aria-hidden="true" />}
                  </button>
                )
              })}
            </div>
            {nodes.length === 0 && <StateEmpty icon={<GitBranch size={20} />} title={c.title} desc={c.empty} />}
          </div>
        </section>
        <aside className="wf-inspector">
          <h2 className="wf-panel-title">{c.inspector}</h2>
          {selectedNode ? (
            <>
              <div className="field"><label htmlFor="wf-name">{c.fields.name}</label>
                <input id="wf-name" className="input" value={selectedNode.title} onChange={(e) => setNodes((list) => list.map((n) => n.id === selectedNode.id ? { ...n, title: e.target.value } : n))} />
              </div>
              {selectedNode.type === 'trigger' && <div className="field"><label htmlFor="wf-trigger">{c.fields.schedule}</label><select id="wf-trigger" className="input"><option>手动</option><option>定时</option><option>事件</option></select></div>}
              {selectedNode.type === 'knowledge' && <><div className="field"><label htmlFor="wf-kb">{c.fields.kb}</label><select id="wf-kb" className="input"><option>我的 Java 笔记</option><option>面试八股合集</option></select></div><div className="field"><label htmlFor="wf-topk">{c.fields.topK}</label><input id="wf-topk" className="input" type="number" defaultValue={6} /></div></>}
              {selectedNode.type === 'agent' && <><div className="field"><label htmlFor="wf-model">{c.fields.model}</label><select id="wf-model" className="input"><option>deepseek-v4-flash</option><option>glm-5.3</option><option>kimi-k2.6</option></select></div><div className="field"><label htmlFor="wf-instr">{c.fields.instructions}</label><textarea id="wf-instr" className="input" rows={3} defaultValue="先检索知识库，再给出要点回答。" /></div></>}
              {selectedNode.type === 'tool' && <div className="field"><label htmlFor="wf-tool">{c.fields.tool}</label><select id="wf-tool" className="input"><option>knowledge_search</option><option>calculator</option><option>code_runner</option></select></div>}
              {selectedNode.type === 'condition' && <div className="field"><label htmlFor="wf-expr">{c.fields.expression}</label><input id="wf-expr" className="input" defaultValue="score > 0.7" /></div>}
              {selectedNode.type === 'output' && <div className="field"><label htmlFor="wf-format">{c.fields.format}</label><select id="wf-format" className="input"><option>Markdown</option><option>JSON</option><option>CSV</option></select></div>}
            </>
          ) : <p className="hint">{c.empty}</p>}
          <h2 className="wf-panel-title" style={{ marginTop: 18 }}>{c.log}</h2>
          <ol className="wf-log">
            {nodes.map((n) => (
              <li key={n.id} data-state={running ? (done.includes(n.id) ? 'done' : 'running') : 'idle'}>
                {done.includes(n.id) ? <CheckCircle2 size={13} aria-hidden="true" /> : <Circle size={11} aria-hidden="true" />}
                {c.step(n.title)}
              </li>
            ))}
          </ol>
        </aside>
      </div>
    </div>
  )
}
