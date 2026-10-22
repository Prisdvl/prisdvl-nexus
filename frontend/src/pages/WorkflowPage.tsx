import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  AlertTriangle, BookOpen, Bot, CheckCircle2, ChevronDown, Cpu, Flag, GitBranch, LayoutTemplate, Maximize2, Play, Plus,
  RefreshCw, Rocket, Save, ShieldCheck, Sparkles, Terminal, Trash2, UserCheck, ZoomIn, ZoomOut,
} from 'lucide-react'
import LatticeLoader from '@/bits/LatticeLoader/LatticeLoader'
import { StateEmpty } from '@/components/Skeleton'
import { toast } from '@/lib/toast'
import { useLocale } from '@/lib/locale'
import {
  api, errMessage,
  type BackendAgent, type KnowledgeBase, type Workflow, type WorkflowEdge,
  type WorkflowNode, type WorkflowNodeRun, type WorkflowPreset, type WorkflowRun, type WorkflowValidation,
} from '@/lib/api'
import {
  WF_NODE_TYPES, WF_UNARY_OPERATORS, canLink, defaultConfig, nextEdgeId, nextNodeId,
  nextPosition, positionOf, runState, seedGraph, withoutNode, type WfNodeType,
} from '@/lib/workflowGraph'
import Inspector from '@/pages/workflow/Inspector'
import PresetGallery from '@/pages/workflow/PresetGallery'
import RunPanels from '@/pages/workflow/RunPanels'
import { WORKFLOW_COPY, defaultInputs } from '@/pages/workflow/copy'
import '@/styles/plugins.css'
import '@/styles/workflow.css'

const ICONS: Record<WfNodeType, typeof Bot> = {
  START: Sparkles,
  KNOWLEDGE_RETRIEVAL: BookOpen,
  LLM: Cpu,
  AGENT: Bot,
  IF_ELSE: GitBranch,
  TEMPLATE: Terminal,
  HUMAN_APPROVAL: UserCheck,
  END: Flag,
}

type Busy = '' | 'list' | 'load' | 'create' | 'save' | 'validate' | 'publish' | 'run' | 'debug' | 'approve' | 'retry' | 'delete'
type LinkFrom = { source: string; handle: string | null }

/** 运行历史每页条数：面板不宽，10 条够看且不用滚动太久。 */
const RUNS_PAGE_SIZE = 10

export default function WorkflowPage() {
  const { locale } = useLocale()
  const c = WORKFLOW_COPY[locale]

  const [workflows, setWorkflows] = useState<Workflow[]>([])
  const [listState, setListState] = useState<'loading' | 'ready' | 'error'>('loading')
  const [listError, setListError] = useState('')
  const [current, setCurrent] = useState<Workflow | null>(null)
  const [nodes, setNodes] = useState<WorkflowNode[]>([])
  const [edges, setEdges] = useState<WorkflowEdge[]>([])
  const [selected, setSelected] = useState<string | null>(null)
  const [dirty, setDirty] = useState(false)
  const [busy, setBusy] = useState<Busy>('')
  const [validation, setValidation] = useState<WorkflowValidation | null>(null)
  const [run, setRun] = useState<WorkflowRun | null>(null)
  const [nodeRuns, setNodeRuns] = useState<WorkflowNodeRun[]>([])
  const [runError, setRunError] = useState('')
  /* 运行历史（真实接口）与「被选中那次运行」的轨迹 */
  const [runs, setRuns] = useState<WorkflowRun[]>([])
  const [runsTotal, setRunsTotal] = useState(0)
  const [runsPage, setRunsPage] = useState(1)
  const [runsLoading, setRunsLoading] = useState(false)
  const [runsError, setRunsError] = useState('')
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null)
  const [nodeRunsLoading, setNodeRunsLoading] = useState(false)
  const [traceError, setTraceError] = useState('')
  /* 预填示例问题：模板里的 {{ input.question }} 空着跑会渲染成空串（见 defaultInputs） */
  const [inputsText, setInputsText] = useState(() => defaultInputs(c))
  const [linkFrom, setLinkFrom] = useState<LinkFrom | null>(null)
  const [zoom, setZoom] = useState(1)
  const [kbs, setKbs] = useState<KnowledgeBase[]>([])
  const [agents, setAgents] = useState<BackendAgent[]>([])
  const [presets, setPresets] = useState<WorkflowPreset[]>([])
  const [newName, setNewName] = useState('')
  /** 工作流切换面板：列表里带状态与删除入口，比光秃秃的 select 更能管理 */
  const [pickerOpen, setPickerOpen] = useState(false)
  const pickerRef = useRef<HTMLDivElement>(null)
  /** 模板库弹窗：改掉原来的 select，卡片能看到流程长什么样再决定用哪个 */
  const [galleryOpen, setGalleryOpen] = useState(false)

  /** 把服务端返回的图灌进画布。 */
  const hydrate = useCallback((wf: Workflow) => {
    setCurrent(wf)
    setNodes(wf.nodes ?? [])
    setEdges(wf.edges ?? [])
    setSelected((wf.nodes ?? [])[0]?.id ?? null)
    setDirty(false)
    setValidation(null)
    setRun(null)
    setNodeRuns([])
    setRunError('')
    setLinkFrom(null)
  }, [])

  const open = useCallback(async (id: string) => {
    setBusy('load')
    try {
      hydrate(await api.workflows.get(id))
    } catch (e) {
      toast.error(c.loadFailed, errMessage(e))
    } finally {
      setBusy('')
    }
  }, [c.loadFailed, hydrate])

  /* 首屏：拉列表并自动打开最近更新的一个 */
  useEffect(() => {
    let alive = true
    ;(async () => {
      try {
        const page = await api.workflows.list(1, 50)
        if (!alive) return
        setWorkflows(page.list)
        setListState('ready')
        if (page.list.length > 0) {
          const wf = await api.workflows.get(page.list[0].id)
          if (alive) hydrate(wf)
        }
      } catch (e) {
        if (!alive) return
        setListError(errMessage(e))
        setListState('error')
      }
    })()
    return () => { alive = false }
  }, [hydrate])

  /* 检查器下拉需要真实可选项；拉不到不阻塞画布 */
  useEffect(() => {
    let alive = true
    ;(async () => {
      try {
        const page = await api.knowledge.list(1, 100)
        if (alive) setKbs(page.list)
      } catch { /* 知识库不可用时检索节点仍可后配 */ }
      try {
        const page = await api.agents.list(1, 100)
        if (alive) setAgents(page.list)
      } catch { /* Agent 不可用时节点会显示未配置 */ }
      try {
        const list = await api.workflows.presets()
        if (alive) setPresets(list)
      } catch { /* 模板清单拉不到时只是少一个入口，不影响已有工作流 */ }
    })()
    return () => { alive = false }
  }, [])

  /* 连线模式：Esc 退出 */
  useEffect(() => {
    if (!linkFrom) return
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setLinkFrom(null) }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [linkFrom])

  /* 工作流切换面板：点面板外面或按 Esc 关闭（select 的行为，不能点一下就永远挂着） */
  useEffect(() => {
    if (!pickerOpen) return
    const onDown = (e: MouseEvent) => {
      if (pickerRef.current && !pickerRef.current.contains(e.target as Node)) setPickerOpen(false)
    }
    const onEsc = (e: KeyboardEvent) => { if (e.key === 'Escape') setPickerOpen(false) }
    document.addEventListener('mousedown', onDown)
    window.addEventListener('keydown', onEsc)
    return () => {
      document.removeEventListener('mousedown', onDown)
      window.removeEventListener('keydown', onEsc)
    }
  }, [pickerOpen])

  /** 运行历史。返回本次取到的列表，便于「请求失败」后自动选中最新的那条。 */
  const loadRuns = useCallback(async (workflowId: string, page: number): Promise<WorkflowRun[]> => {
    setRunsLoading(true)
    setRunsError('')
    try {
      const data = await api.workflowRuns.list(workflowId, page, RUNS_PAGE_SIZE)
      setRuns(data.list)
      setRunsTotal(data.total)
      setRunsPage(data.pageNum)
      return data.list
    } catch (e) {
      // 列表拉不到是「请求失败」，与「运行失败」是两件事，单独报错、不冒充运行状态
      setRuns([])
      setRunsTotal(0)
      setRunsError(errMessage(e))
      return []
    } finally {
      setRunsLoading(false)
    }
  }, [])

  /** 读取某次运行的节点轨迹：成功与失败的运行都落库了，都能读。 */
  const loadTrace = useCallback(async (runId: string) => {
    setSelectedRunId(runId)
    setNodeRunsLoading(true)
    setTraceError('')
    try {
      setNodeRuns(await api.workflowRuns.nodes(runId))
    } catch (e) {
      setNodeRuns([])
      setTraceError(errMessage(e))
    } finally {
      setNodeRunsLoading(false)
    }
  }, [])

  /* 切换工作流：清掉上一个工作流的运行历史与轨迹，再拉第 1 页 */
  useEffect(() => {
    setRuns([])
    setRunsTotal(0)
    setRunsPage(1)
    setRunsError('')
    setSelectedRunId(null)
    setNodeRuns([])
    setTraceError('')
    if (current) void loadRuns(current.id, 1)
  }, [current?.id, loadRuns])

  const selectedNode = useMemo(() => nodes.find((n) => n.id === selected) ?? null, [nodes, selected])
  const runByNode = useMemo(() => {
    const map = new Map<string, WorkflowNodeRun>()
    nodeRuns.forEach((nr) => map.set(nr.nodeId, nr))
    return map
  }, [nodeRuns])
  const hasStart = nodes.some((n) => n.type === 'START')
  const executing = busy === 'run' || busy === 'debug'

  const agentName = useCallback((id: unknown) => {
    const found = agents.find((a) => a.id === String(id))
    return found ? found.name : String(id ?? '')
  }, [agents])

  /** 画布副标题：从 config 派生——数据模型里没有节点名字段，所以不伪造一个。 */
  const summaryOf = (node: WorkflowNode): string => {
    const cfg = node.config ?? {}
    const clip = (v: unknown, n = 34) => {
      const s = String(v ?? '').trim()
      return s.length > n ? `${s.slice(0, n)}…` : s
    }
    switch (node.type) {
      case 'START':
        return c.types.START.desc
      case 'END':
        return cfg.template ? clip(cfg.template) : c.types.END.desc
      case 'LLM':
        return cfg.userPrompt ? clip(cfg.userPrompt) : c.missing
      case 'AGENT':
        return cfg.agentId ? agentName(cfg.agentId) : c.missing
      case 'TEMPLATE':
        return cfg.template ? clip(cfg.template) : c.missing
      case 'KNOWLEDGE_RETRIEVAL': {
        const ids = Array.isArray(cfg.kbIds) ? cfg.kbIds : []
        const topK = cfg.topK == null ? '5' : String(cfg.topK)
        return ids.length === 0 ? c.missing : `${ids.length} ${c.fields.kb} · topK ${topK}`
      }
      case 'IF_ELSE': {
        const left = clip(cfg.left, 12)
        const right = WF_UNARY_OPERATORS.includes(cfg.operator as never) ? '' : ` ${clip(cfg.right, 10)}`
        return `${left} ${String(cfg.operator ?? '')}${right}`.trim() || c.missing
      }
      default:
        return ''
    }
  }

  /* ---------------- 画布编辑（纯本地，保存时才落库） ---------------- */

  const touch = () => { setDirty(true); setValidation(null) }

  const addNode = (type: WfNodeType) => {
    if (type === 'START' && hasStart) { toast.info(c.startTaken); return }
    const id = nextNodeId(nodes, type)
    setNodes((list) => [...list, { id, type, version: 1, position: nextPosition(list), config: defaultConfig(type) }])
    setSelected(id)
    touch()
  }

  const removeNode = (id: string) => {
    const next = withoutNode(nodes, edges, id)
    setNodes(next.nodes)
    setEdges(next.edges)
    if (selected === id) setSelected(next.nodes[0]?.id ?? null)
    setLinkFrom(null)
    touch()
  }

  const patchConfig = (id: string, patch: Record<string, unknown>) => {
    setNodes((list) => list.map((n) => (n.id === id ? { ...n, config: { ...n.config, ...patch } } : n)))
    touch()
  }

  const removeEdge = (id: string) => {
    setEdges((list) => list.filter((e) => e.id !== id))
    touch()
  }

  const completeLink = (target: string) => {
    if (!linkFrom) return
    const verdict = canLink(edges, linkFrom.source, target, linkFrom.handle)
    if (verdict === 'self') { toast.error(c.selfLoop); return }
    if (verdict === 'duplicate') { toast.error(c.duplicateEdge); return }
    setEdges((list) => [...list, {
      id: nextEdgeId(list), source: linkFrom.source, target, sourceHandle: linkFrom.handle,
    }])
    setLinkFrom(null)
    touch()
  }

  const onNodeClick = (id: string) => {
    if (linkFrom) { completeLink(id); return }
    setSelected(id)
  }

  /* ---------------- 服务端动作 ---------------- */

  const saveDraft = async (): Promise<Workflow | null> => {
    if (!current) return null
    setBusy('save')
    try {
      const wf = await api.workflows.saveDraft(current.id, { nodes, edges })
      setCurrent(wf)
      setDirty(false)
      setWorkflows((list) => list.map((w) => (w.id === wf.id ? { ...w, ...wf } : w)))
      toast.success(c.saved, c.savedDesc)
      return wf
    } catch (e) {
      toast.error(c.saveFailed, errMessage(e))
      return null
    } finally {
      setBusy('')
    }
  }

  const doValidate = async () => {
    if (!current) return
    if (dirty && !(await saveDraft())) return
    setBusy('validate')
    try {
      const v = await api.workflows.validate(current.id)
      setValidation(v)
      if (v.valid) toast.success(c.validOk)
      else toast.error(c.validFail, v.errors[0])
    } catch (e) {
      toast.error(c.validFail, errMessage(e))
    } finally {
      setBusy('')
    }
  }

  const doPublish = async () => {
    if (!current) return
    if (dirty && !(await saveDraft())) return
    setBusy('publish')
    try {
      const wf = await api.workflows.publish(current.id)
      setCurrent(wf)
      setWorkflows((list) => list.map((w) => (w.id === wf.id ? { ...w, ...wf } : w)))
      setDirty(false)
      toast.success(c.published, c.publishedDesc)
    } catch (e) {
      toast.error(c.publishFailed, errMessage(e))
    } finally {
      setBusy('')
    }
  }

  /** debug 跑草稿、run 跑已发布（versionId 选择在 WorkflowRuntimeService:63）。 */
  const doRun = async (mode: 'debug' | 'run') => {
    if (!current) return
    if (dirty && !(await saveDraft())) return
    let inputs: Record<string, unknown>
    try {
      const parsed: unknown = JSON.parse(inputsText || '{}')
      if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
        toast.error(c.inputsInvalid)
        return
      }
      inputs = parsed as Record<string, unknown>
    } catch {
      toast.error(c.inputsInvalid)
      return
    }
    setBusy(mode)
    setRunError('')
    setRun(null)
    setTraceError('')
    setSelectedRunId(null)
    setNodeRuns([])
    try {
      const r = mode === 'debug'
        ? await api.workflows.debug(current.id, inputs)
        : await api.workflows.run(current.id, inputs)
      // 请求成功：同步执行，返回即终态，直接展示这次运行并读它的轨迹
      setRun(r)
      await loadRuns(current.id, 1)
      void loadTrace(r.id)
      toast.success(mode === 'debug' ? c.debugDone : c.runDone, `${c.runMeta}: ${c.statuses[r.status] ?? r.status}`)
    } catch (e) {
      // 请求失败：错误体里没有 runId。但失败运行已落库，所以刷新历史并自动选中最新的那条，
      // 让用户仍然能读到失败轨迹（这是运行历史接口存在的意义）。
      setRunError(errMessage(e))
      const list = await loadRuns(current.id, 1)
      if (list.length > 0) void loadTrace(list[0].id)
      toast.error(c.runFailed, errMessage(e))
    } finally {
      setBusy('')
    }
  }

  const runsPages = Math.max(1, Math.ceil(runsTotal / RUNS_PAGE_SIZE))
  const goRunsPage = (page: number) => {
    if (!current || page < 1 || page > runsPages) return
    void loadRuns(current.id, page)
  }

  /** 人工审批：同意则从闸门之后续跑、拒绝则本次失败；两种都要刷新历史与轨迹（状态变了）。 */
  const doApprove = async (runId: string, decision: 'APPROVE' | 'REJECT') => {
    if (!current) return
    setBusy('approve')
    try {
      await api.workflowRuns.approve(runId, { decision })
      toast.success(decision === 'APPROVE' ? c.approvalDone : c.rejectDone)
      await loadRuns(current.id, runsPage)
      void loadTrace(runId)
    } catch (e) {
      toast.error(c.approvalFailed, errMessage(e))
    } finally {
      setBusy('')
    }
  }

  /** 从失败节点续跑；失败时拿不到新 runId，但新运行已落库 → 刷新历史并选中最新的那条。 */
  const doRetry = async (runId: string) => {
    if (!current) return
    setBusy('retry')
    try {
      const r = await api.workflowRuns.retry(runId)
      toast.success(c.retryDone, `${c.runMeta}: ${c.statuses[r.status] ?? r.status}`)
      await loadRuns(current.id, 1)
      void loadTrace(r.id)
    } catch (e) {
      toast.error(c.retryFailed, errMessage(e))
      const list = await loadRuns(current.id, 1)
      if (list.length > 0) void loadTrace(list[0].id)
    } finally {
      setBusy('')
    }
  }

  const doCreate = async () => {
    const name = newName.trim()
    if (!name) return
    setBusy('create')
    try {
      const wf = await api.workflows.create({ name })
      const saved = await api.workflows.saveDraft(wf.id, seedGraph())
      setWorkflows((list) => [saved, ...list])
      hydrate(saved)
      setNewName('')
      setListState('ready')
      toast.success(c.create, name)
    } catch (e) {
      toast.error(c.create, errMessage(e))
    } finally {
      setBusy('')
    }
  }

  /** 用内置模板新建：模板图已落成草稿，但检索节点还没选知识库，需要用户补配置才能发布。 */
  const createFromPreset = async (code: string) => {
    setBusy('create')
    try {
      const wf = await api.workflows.instantiatePreset(code)
      setWorkflows((list) => [wf, ...list])
      hydrate(wf)
      setListState('ready')
      setGalleryOpen(false)
      toast.success(c.presetCreated, wf.name)
    } catch (e) {
      toast.error(c.presetFailed, errMessage(e))
    } finally {
      setBusy('')
    }
  }

  /**
   * 删除工作流。后端是软删（wf_workflow.deleted），运行历史与版本快照都留着——
   * 所以文案只说「从列表消失」，不说「记录被一起删掉」。删掉当前这条后自动打开列表里的下一个。
   */
  const doDelete = async (target: Workflow) => {
    if (!window.confirm(c.deleteConfirm(target.name))) return
    setBusy('delete')
    try {
      await api.workflows.remove(target.id)
      const rest = workflows.filter((w) => w.id !== target.id)
      setWorkflows(rest)
      /* 面板保持打开，方便连着删几条；一条都不剩时再收起来 */
      if (rest.length === 0) setPickerOpen(false)
      if (current?.id === target.id) {
        if (rest.length > 0) {
          hydrate(await api.workflows.get(rest[0].id))
        } else {
          /* 一个都不剩：清空画布，不要让用户对着已删工作流的节点继续编辑 */
          setCurrent(null)
          setNodes([])
          setEdges([])
          setSelected(null)
          setValidation(null)
          setRun(null)
          setNodeRuns([])
        }
      }
      toast.success(c.deleted, target.name)
    } catch (e) {
      toast.error(c.deleteFailed, errMessage(e))
    } finally {
      setBusy('')
    }
  }

  /* ---------------- 渲染 ---------------- */
  if (listState === 'error') {
    return (
      <div className="page">
        <div className="page-head">
          <div>
            <p className="eyebrow" style={{ margin: 0 }}>{c.eyebrow}</p>
            <h1 className="page-title">{c.title}</h1>
          </div>
        </div>
        <StateEmpty
          icon={<AlertTriangle size={20} />}
          title={c.loadListFailed}
          desc={listError}
          action={<button className="btn btn-ghost" type="button" onClick={() => window.location.reload()}><RefreshCw size={14} /> {c.retry}</button>}
        />
      </div>
    )
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
          <button className="btn btn-ghost" type="button" onClick={saveDraft} disabled={!current || busy !== ''}>
            {busy === 'save' ? <LatticeLoader status="working" label="" pattern="sweep" grid={3} cellSize={3.5} gap={1.5} showTimer={false} /> : <Save size={15} />}
            {busy === 'save' ? c.saving : c.save}
          </button>
          <button className="btn btn-ghost" type="button" onClick={doValidate} disabled={!current || busy !== ''}>
            <ShieldCheck size={15} /> {busy === 'validate' ? c.validating : c.validate}
          </button>
          <button className="btn btn-ghost" type="button" onClick={doPublish} disabled={!current || busy !== ''}>
            <Rocket size={15} /> {busy === 'publish' ? c.publishing : c.publish}
          </button>
          <button className="btn btn-ghost" type="button" onClick={() => doRun('debug')} disabled={!current || busy !== ''}>
            {busy === 'debug' ? <LatticeLoader status="working" label="" pattern="sweep" grid={3} cellSize={3.5} gap={1.5} showTimer={false} /> : <Play size={15} />}
            {c.debugRun}
          </button>
          <button
            className="btn btn-primary"
            type="button"
            onClick={() => doRun('run')}
            disabled={!current || busy !== '' || !current.publishedVersionId}
          >
            {busy === 'run' ? <LatticeLoader status="working" label="" pattern="sweep" grid={3} cellSize={3.5} gap={1.5} showTimer={false} /> : <Play size={15} />}
            {busy === 'run' ? c.running : c.run}
          </button>
        </div>
      </div>

      <div className="wf-bar">
        <span className="wf-bar__label" id="wf-pick-label">{c.pickerLabel}</span>
        {/* 切换面板：一个工作流一行，带发布状态与删除入口；原生的 select 只能“选”，不能管理 */}
        <div className="wf-picker" ref={pickerRef}>
          <button
            type="button"
            id="wf-pick"
            className="input wf-picker__btn"
            aria-haspopup="dialog"
            aria-expanded={pickerOpen}
            aria-labelledby="wf-pick-label wf-pick"
            disabled={busy !== '' || workflows.length === 0}
            onClick={() => setPickerOpen((v) => !v)}
          >
            <span className="wf-picker__name">{current ? current.name : c.emptyList}</span>
            <span className="wf-picker__chip" data-tone={current?.status === 'PUBLISHED' ? 'ok' : 'draft'}>
              {current ? (current.status === 'PUBLISHED' ? c.statusPublished : c.statusDraft) : '—'}
            </span>
            <ChevronDown size={14} aria-hidden="true" />
          </button>
          {pickerOpen && (
            <div className="wf-picker__menu" role="dialog" aria-label={c.pickerMenu}>
              {workflows.length === 0 && <p className="wf-picker__empty">{c.pickerEmpty}</p>}
              {workflows.map((w) => (
                <div key={w.id} className={`wf-picker__row${w.id === current?.id ? ' is-active' : ''}`}>
                  <button
                    type="button"
                    className="wf-picker__item"
                    onClick={() => { setPickerOpen(false); if (w.id !== current?.id) void open(w.id) }}
                  >
                    <span className="wf-picker__name">{w.name}</span>
                    <span className="wf-picker__meta">
                      {w.status === 'PUBLISHED' ? c.statusPublished : c.statusDraft}
                      {' · '}{w.nodes?.length ?? 0} {c.nodesCount}
                    </span>
                  </button>
                  <button
                    type="button"
                    className="wf-picker__del"
                    title={c.delete}
                    aria-label={`${c.delete} ${w.name}`}
                    onClick={() => void doDelete(w)}
                    disabled={busy !== ''}
                  >
                    <Trash2 size={13} />
                  </button>
                </div>
              ))}
            </div>
          )}
        </div>
        {/* 模板库入口：卡片按用途分组，选中一个就实例化成草稿 */}
        <button
          className="btn btn-ghost wf-bar__preset"
          type="button"
          onClick={() => setGalleryOpen(true)}
          disabled={busy !== '' || presets.length === 0}
        >
          <LayoutTemplate size={15} /> {c.presetOpen}
        </button>
        {/* 发布状态已经在切换按钮上显示了，这里不再重复一颗 chip */}
        {current && (
          <span className="wf-bar__meta">
            {`${c.publishedAs}: ${current.publishedVersionNo ?? c.noVersion} · ${c.draftAs}: ${current.draftVersionNo ?? c.noVersion}`}
          </span>
        )}
        {current && <span className="wf-bar__meta">{nodes.length} {c.nodesCount} · {edges.length} {c.edgesCount}</span>}
        {current && <span className="wf-chip" data-tone={dirty ? 'warn' : 'muted'}>{dirty ? c.dirty : c.clean}</span>}
        <span className="wf-bar__spacer" />
        <input
          className="input wf-bar__new"
          placeholder={c.createPh}
          value={newName}
          onChange={(e) => setNewName(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') void doCreate() }}
          disabled={busy !== ''}
        />
        <button className="btn btn-ghost" type="button" onClick={doCreate} disabled={busy !== '' || !newName.trim()}>
          <Plus size={15} /> {busy === 'create' ? c.creating : c.create}
        </button>
      </div>

      {!current ? (
        <StateEmpty icon={<GitBranch size={20} />} title={c.emptyList} desc={c.emptyListDesc} />
      ) : (
        <>
          <div className="wf-layout">
            <aside className="wf-palette">
              <h2 className="wf-panel-title">{c.palette}</h2>
              {WF_NODE_TYPES.map((type) => {
                const Icon = ICONS[type]
                const disabled = type === 'START' && hasStart
                return (
                  <button
                    className="wf-palette-item"
                    type="button"
                    key={type}
                    onClick={() => addNode(type)}
                    disabled={disabled}
                    title={disabled ? c.startTaken : undefined}
                  >
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
              {(linkFrom || executing) && (
                <p className="wf-link-hint">
                  {executing ? c.running : c.linking}
                  {linkFrom && (
                    <button className="btn btn-ghost wf-link-hint__btn" type="button" onClick={() => setLinkFrom(null)}>{c.cancelLink}</button>
                  )}
                </p>
              )}
              <div className="wf-canvas" role="application" aria-label={c.title}>
                <div className="wf-canvas__inner" style={{ transform: `scale(${zoom})` }}>
                  <svg className="wf-edges" width={1140} height={460} aria-hidden="true">
                    {edges.map((edge) => {
                      const from = nodes.findIndex((n) => n.id === edge.source)
                      const to = nodes.findIndex((n) => n.id === edge.target)
                      if (from < 0 || to < 0) return null
                      const a = positionOf(nodes[from], from)
                      const b = positionOf(nodes[to], to)
                      const x1 = a.x + 170
                      const y1 = a.y + 34
                      const x2 = b.x
                      const y2 = b.y + 34
                      const branch = edge.sourceHandle === 'true' || edge.sourceHandle === 'false'
                      return (
                        <path
                          key={edge.id}
                          d={`M ${x1} ${y1} C ${x1 + 60} ${y1}, ${x2 - 60} ${y2}, ${x2} ${y2}`}
                          className="wf-edge"
                          data-branch={branch ? edge.sourceHandle : undefined}
                        />
                      )
                    })}
                  </svg>
                  {nodes.map((node, index) => {
                    const Icon = ICONS[node.type] ?? Bot
                    const pos = positionOf(node, index)
                    const state = executing ? 'running' : runState(runByNode.get(node.id)?.status)
                    return (
                      <button
                        className="wf-node"
                        type="button"
                        key={node.id}
                        data-type={node.type}
                        data-state={state}
                        data-on={selected === node.id ? '' : undefined}
                        data-link={linkFrom?.source === node.id ? '' : undefined}
                        style={{ left: pos.x, top: pos.y }}
                        onClick={() => onNodeClick(node.id)}
                      >
                        <span className="wf-node__icon"><Icon size={14} aria-hidden="true" /></span>
                        <span className="wf-node__text"><b>{node.id}</b><small>{summaryOf(node)}</small></span>
                        {state === 'done' && <CheckCircle2 size={13} className="wf-node__done" aria-hidden="true" />}
                        {state === 'failed' && <AlertTriangle size={13} className="wf-node__failed" aria-hidden="true" />}
                      </button>
                    )
                  })}
                </div>
                {nodes.length === 0 && <StateEmpty icon={<GitBranch size={20} />} title={c.palette} desc={c.canvasHint} />}
              </div>
              <p className="hint wf-canvas-note">{c.canvasHint}</p>
            </section>

            <Inspector
              c={c}
              node={selectedNode}
              edges={edges}
              kbs={kbs}
              agents={agents}
              linking={!!linkFrom}
              typeTitle={c.types}
              onPatchConfig={patchConfig}
              onRemoveNode={removeNode}
              onRemoveEdge={removeEdge}
              onStartLink={(source, handle) => setLinkFrom({ source, handle })}
            />
          </div>

          <RunPanels
            c={c}
            validation={validation}
            inputsText={inputsText}
            onInputsChange={setInputsText}
            run={run}
            runError={runError}
            runs={runs}
            runsTotal={runsTotal}
            runsPage={runsPage}
            runsPages={runsPages}
            runsLoading={runsLoading}
            runsError={runsError}
            selectedRunId={selectedRunId}
            onSelectRun={loadTrace}
            onGoRunsPage={goRunsPage}
            onReloadRuns={() => goRunsPage(runsPage)}
            nodeRuns={nodeRuns}
            nodeRunsLoading={nodeRunsLoading}
            traceError={traceError}
            onApprove={doApprove}
            onRetry={doRetry}
            actionBusy={busy === 'approve' || busy === 'retry'}
          />
        </>
      )}

      {galleryOpen && (
        <PresetGallery
          presets={presets}
          copy={c}
          busy={busy === 'create'}
          onUse={(code) => { void createFromPreset(code) }}
          onClose={() => setGalleryOpen(false)}
        />
      )}
    </div>
  )
}
