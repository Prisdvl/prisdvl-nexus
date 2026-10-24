import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  CalendarClock, CheckCircle2, Clock, History, Pencil, Play, Plus, Search,
  Trash2, X, XCircle,
} from 'lucide-react'
import { motion, useReducedMotion } from 'motion/react'
import Modal from '@/components/Modal'
import { SkeletonRows, StateEmpty } from '@/components/Skeleton'
import { toast } from '@/lib/toast'
import { appCopy } from '@/lib/appCopy'
import { pagesCopy } from '@/lib/pagesCopy'
import { useLocale } from '@/lib/locale'
import { api, errMessage, USE_MOCK, type AutomationPayload } from '@/lib/api'
import {
  automationOf, demoAutomationChoices, demoAutomations, formatWhen, runOf, scheduleLabelKey,
  type AutomationChoices, type AutomationItem, type AutomationRun, type AutomationSchedule, type AutomationStatus,
} from '@/lib/automations'
import '@/styles/plugins.css'
import '@/styles/ops.css'

type Tab = 'all' | 'enabled' | 'paused'

const STATUS_ICON: Record<AutomationStatus, typeof Clock> = {
  done: CheckCircle2,
  failed: XCircle,
  queued: Clock,
}

/** 固定频率的真实 cron：与服务端 AutomationSchedule 的预设一致，仅 mock 新建时用来补显示值 */
const PRESET_CRON: Record<AutomationSchedule, string> = {
  hourly: '0 * * * *',
  daily: '0 9 * * *',
  weekly: '0 10 * * 1',
  custom: '',
}

interface FormState {
  name: string
  prompt: string
  schedule: AutomationSchedule
  /** 只有自定义频率才用得上 */
  cron: string
  /** 空串 = 用账户默认 Agent */
  agentId: string
  /** 空串 = 不限定检索范围 */
  kbId: string
}

export default function AutomationsPage() {
  const { locale } = useLocale()
  const common = appCopy(locale).common
  const a = pagesCopy(locale).automations
  const reduce = useReducedMotion()
  const [items, setItems] = useState<AutomationItem[]>([])
  const [choices, setChoices] = useState<AutomationChoices>({ agents: [], kbs: [] })
  const [loading, setLoading] = useState(!USE_MOCK)
  const [saving, setSaving] = useState(false)
  const [runningId, setRunningId] = useState<string | null>(null)
  const [tab, setTab] = useState<Tab>('all')
  const [query, setQuery] = useState('')
  const [editing, setEditing] = useState<AutomationItem | null>(null)
  const [creating, setCreating] = useState(false)
  const [historyOf, setHistoryOf] = useState<AutomationItem | null>(null)
  const [historyRuns, setHistoryRuns] = useState<AutomationRun[]>([])
  const [historyLoading, setHistoryLoading] = useState(false)

  /* 真实模式：任务列表 + 表单候选（Agent / 知识库）一起拉，避免表单打开时还在等下拉数据 */
  const load = useCallback(async () => {
    if (USE_MOCK) {
      setItems(demoAutomations(locale))
      setChoices(demoAutomationChoices(locale))
      setLoading(false)
      return
    }
    setLoading(true)
    try {
      const [page, agents, kbs] = await Promise.all([
        api.automations.list(1, 100),
        api.agents.list(1, 100),
        api.knowledge.list(1, 100),
      ])
      setItems(page.list.map(automationOf))
      setChoices({
        agents: agents.list.map((x) => ({ id: x.id, name: x.name })),
        kbs: kbs.list.map((x) => ({ id: x.id, name: x.name })),
      })
    } catch (e) {
      toast.error(a.loadFailed, errMessage(e))
    } finally {
      setLoading(false)
    }
  }, [a.loadFailed, locale])

  useEffect(() => {
    void load()
  }, [load])

  const list = useMemo(() => {
    const q = query.trim().toLowerCase()
    return items.filter((item) => {
      if (tab === 'enabled' && !item.enabled) return false
      if (tab === 'paused' && item.enabled) return false
      if (!q) return true
      return [item.name, item.prompt, item.agentName, ...item.kbNames].join(' ').toLowerCase().includes(q)
    })
  }, [items, query, tab])

  const scheduleLabel = (s: AutomationSchedule) => a[scheduleLabelKey(s)]
  const statusLabel = (s: AutomationStatus) => (s === 'done' ? a.statusDone : s === 'failed' ? a.statusFailed : a.statusQueued)
  const closeEditors = () => { setCreating(false); setEditing(null) }

  const toggle = async (item: AutomationItem) => {
    const enabled = !item.enabled
    if (USE_MOCK) {
      setItems((prev) => prev.map((x) => (x.id === item.id
        ? { ...x, enabled, nextRunAt: enabled ? x.nextRunAt : null }
        : x)))
      return
    }
    /* 乐观更新：开关反馈必须是即时的；失败再翻回来并说明原因 */
    setItems((prev) => prev.map((x) => (x.id === item.id ? { ...x, enabled } : x)))
    try {
      const saved = automationOf(await api.automations.update(item.id, { enabled }))
      setItems((prev) => prev.map((x) => (x.id === item.id ? { ...saved, history: x.history } : x)))
    } catch (e) {
      setItems((prev) => prev.map((x) => (x.id === item.id ? { ...x, enabled: !enabled } : x)))
      toast.error(a.saveFailed, errMessage(e))
    }
  }

  /* 立即运行是同步的：返回体里就是这次的结果，失败不是异常（HTTP 200 + status=FAILED） */
  const runNow = async (item: AutomationItem) => {
    if (USE_MOCK) {
      toast.success(a.queuedTitle, a.queuedBody(item.name))
      setItems((prev) => prev.map((x) => (x.id === item.id
        ? { ...x, lastStatus: 'queued', lastRunAt: new Date().toISOString() }
        : x)))
      return
    }
    setRunningId(item.id)
    try {
      const run = runOf(await api.automations.run(item.id))
      setItems((prev) => prev.map((x) => (x.id === item.id
        ? { ...x, lastStatus: run.status, lastRunAt: run.at || x.lastRunAt }
        : x)))
      if (run.status === 'failed') {
        toast.error(a.runFailed, run.error ?? a.statusFailed)
      } else {
        toast.success(a.runOk, item.name)
      }
    } catch (e) {
      toast.error(a.runFailed, errMessage(e))
    } finally {
      setRunningId(null)
    }
  }

  const remove = async (item: AutomationItem) => {
    if (!window.confirm(a.deleteConfirm(item.name))) return
    if (USE_MOCK) {
      setItems((prev) => prev.filter((x) => x.id !== item.id))
      toast.success(a.deletedTitle, item.name)
      return
    }
    try {
      await api.automations.remove(item.id)
      setItems((prev) => prev.filter((x) => x.id !== item.id))
      toast.success(a.deletedTitle, item.name)
    } catch (e) {
      toast.error(a.saveFailed, errMessage(e))
    }
  }

  const openHistory = async (item: AutomationItem) => {
    setHistoryOf(item)
    if (USE_MOCK) {
      setHistoryRuns(item.history)
      return
    }
    setHistoryLoading(true)
    setHistoryRuns([])
    try {
      setHistoryRuns((await api.automations.runs(item.id, 20)).map(runOf))
    } catch (e) {
      toast.error(a.loadFailed, errMessage(e))
    } finally {
      setHistoryLoading(false)
    }
  }

  const save = async (form: FormState) => {
    const payload: AutomationPayload = {
      name: form.name.trim(),
      prompt: form.prompt.trim(),
      schedule: form.schedule,
      cron: form.schedule === 'custom' ? form.cron.trim() : undefined,
      kbIds: form.kbId ? [form.kbId] : [],
      enabled: true,
    }
    if (!USE_MOCK) {
      setSaving(true)
      try {
        if (editing) {
          /* agentId 缺省 = 不改；解绑回默认 Agent 必须显式 useDefaultAgent */
          const saved = automationOf(await api.automations.update(editing.id, {
            ...payload,
            agentId: form.agentId || undefined,
            useDefaultAgent: !form.agentId,
          }))
          setItems((prev) => prev.map((x) => (x.id === saved.id ? { ...saved, history: x.history } : x)))
          toast.success(a.updatedTitle, payload.name)
        } else {
          const saved = automationOf(await api.automations.create({ ...payload, agentId: form.agentId || undefined }))
          setItems((prev) => [saved, ...prev])
          toast.success(a.createdTitle, payload.name)
        }
        closeEditors()
      } catch (e) {
        toast.error(a.saveFailed, errMessage(e))
      } finally {
        setSaving(false)
      }
      return
    }
    /* mock：本地落一条，形状与后端返回一致，方便演示 */
    const agent = choices.agents.find((x) => x.id === form.agentId)
    const kb = choices.kbs.find((x) => x.id === form.kbId)
    if (editing) {
      setItems((prev) => prev.map((x) => (x.id === editing.id
        ? {
          ...x,
          name: payload.name,
          prompt: payload.prompt,
          schedule: form.schedule,
          cron: form.schedule === 'custom' ? form.cron : PRESET_CRON[form.schedule],
          agentId: form.agentId || null,
          agentName: agent?.name ?? '',
          kbIds: kb ? [kb.id] : [],
          kbNames: kb ? [kb.name] : [],
        }
        : x)))
      toast.success(a.updatedTitle, payload.name)
    } else {
      const item: AutomationItem = {
        id: `auto-${Date.now()}`,
        name: payload.name,
        prompt: payload.prompt,
        schedule: form.schedule,
        cron: form.schedule === 'custom' ? form.cron : PRESET_CRON[form.schedule],
        agentId: form.agentId || null,
        agentName: agent?.name ?? '',
        kbIds: kb ? [kb.id] : [],
        kbNames: kb ? [kb.name] : [],
        enabled: true,
        lastRunAt: null,
        nextRunAt: new Date(Date.now() + 3600_000).toISOString(),
        lastStatus: null,
        history: [],
      }
      setItems((prev) => [item, ...prev])
      toast.success(a.createdTitle, payload.name)
    }
    closeEditors()
  }

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <p className="eyebrow" style={{ margin: 0 }}>{a.eyebrow}</p>
          <h1 className="page-title">{a.title}</h1>
          <p className="page-sub">{a.sub}</p>
        </div>
        <button className="btn btn-primary" type="button" onClick={() => { setCreating(true); setEditing(null) }}>
          <Plus size={16} /> {a.newAutomation}
        </button>
      </div>

      <div className="market-toolbar">
        <div className="market-tabs" role="tablist" aria-label={a.title}>
          {([
            { key: 'all', label: a.tabAll },
            { key: 'enabled', label: a.tabEnabled },
            { key: 'paused', label: a.tabPaused },
          ] as const).map((t) => (
            <button
              key={t.key}
              type="button"
              role="tab"
              aria-selected={tab === t.key}
              className={`market-tab${tab === t.key ? ' is-on' : ''}`}
              onClick={() => setTab(t.key)}
            >
              {t.label}
            </button>
          ))}
        </div>
        <div className="market-search">
          <Search size={15} aria-hidden="true" />
          <input className="input" value={query} onChange={(e) => setQuery(e.target.value)} placeholder={a.searchPh} aria-label={a.searchAria} />
          {query && (
            <button className="market-search__clear" type="button" aria-label={common.close} onClick={() => setQuery('')}>
              <X size={14} />
            </button>
          )}
        </div>
      </div>

      <motion.div
        key={`${tab}-${query}`}
        initial={reduce ? undefined : { opacity: 0, y: 6 }}
        animate={{ opacity: 1, y: 0 }}
        transition={reduce ? { duration: 0 } : { duration: 0.2, ease: [0.23, 1, 0.32, 1] }}
      >
        {loading && items.length === 0 ? (
          <SkeletonRows rows={4} />
        ) : list.length === 0 ? (
          <StateEmpty
            icon={<CalendarClock size={20} />}
            title={query ? a.noResults(query) : a.emptyTitle}
            desc={a.emptyDesc}
            action={<button className="btn btn-primary btn-sm" type="button" onClick={() => setCreating(true)}><Plus size={14} /> {a.newAutomation}</button>}
          />
        ) : (
          <div className="ops-list">
            {list.map((item) => {
              const Icon = item.lastStatus ? STATUS_ICON[item.lastStatus] : Clock
              return (
                <div className="ops-row ops-row--static" key={item.id}>
                  <button
                    className="auto-switch"
                    type="button"
                    role="switch"
                    aria-checked={item.enabled}
                    aria-label={`${item.name} ${item.enabled ? a.enabled : a.disabled}`}
                    onClick={() => void toggle(item)}
                  >
                    <span className="auto-switch__knob" />
                  </button>
                  <span className="ops-row__main">
                    <span className="ops-row__title">{item.name}</span>
                    <span className="ops-row__prompt">{item.prompt}</span>
                    <span className="meta-inline">
                      <span className="meta-inline__item"><CalendarClock size={13} aria-hidden="true" /> {scheduleLabel(item.schedule)}</span>
                      <span className="meta-inline__item">{item.agentName || a.defaultAgent}</span>
                      <span className="meta-inline__item">{item.kbNames.length > 0 ? item.kbNames.join(' / ') : a.anyKb}</span>
                    </span>
                  </span>
                  <span className="ops-row__runs">
                    <span className="meta-inline__item">
                      <Icon size={13} aria-hidden="true" /> {item.lastStatus ? statusLabel(item.lastStatus) : a.never}
                    </span>
                    <span className="ops-row__time">
                      {a.lastRun} {formatWhen(item.lastRunAt, locale, a.never)} · {a.nextRun} {item.enabled ? formatWhen(item.nextRunAt, locale, a.never) : a.never}
                    </span>
                  </span>
                  <span className="ops-row__actions">
                    <button
                      className="plugin-icon-btn"
                      type="button"
                      title={a.runNow}
                      aria-label={`${a.runNow}: ${item.name}`}
                      disabled={runningId === item.id}
                      onClick={() => void runNow(item)}
                    >
                      {runningId === item.id ? <Clock size={15} /> : <Play size={15} />}
                    </button>
                    <button className="plugin-icon-btn" type="button" title={a.history} aria-label={`${a.history}: ${item.name}`} onClick={() => void openHistory(item)}><History size={15} /></button>
                    <button className="plugin-icon-btn" type="button" title={a.edit} aria-label={`${a.edit}: ${item.name}`} onClick={() => { setEditing(item); setCreating(false) }}><Pencil size={15} /></button>
                    <button className="plugin-icon-btn plugin-icon-btn--danger" type="button" title={a.delete} aria-label={`${a.delete}: ${item.name}`} onClick={() => void remove(item)}><Trash2 size={15} /></button>
                  </span>
                </div>
              )
            })}
          </div>
        )}
      </motion.div>

      <Modal
        open={creating || editing !== null}
        title={editing ? a.editTitle : a.createTitle}
        width={640}
        onClose={closeEditors}
      >
        <AutomationForm
          initial={editing}
          labels={a}
          choices={choices}
          saving={saving}
          onCancel={closeEditors}
          onSave={save}
        />
      </Modal>

      <Modal
        open={historyOf !== null}
        title={`${a.historyTitle} · ${historyOf?.name ?? ''}`}
        width={520}
        onClose={() => setHistoryOf(null)}
      >
        {historyLoading ? (
          <SkeletonRows rows={3} />
        ) : historyRuns.length === 0 ? (
          <p className="hint">{a.historyEmpty}</p>
        ) : (
          <div className="meta-list">
            {historyRuns.map((h) => (
              <span className="meta-list__row" key={h.id}>
                {h.status === 'done' ? <CheckCircle2 size={14} aria-hidden="true" /> : h.status === 'failed' ? <XCircle size={14} aria-hidden="true" /> : <Clock size={14} aria-hidden="true" />}
                {formatWhen(h.at, locale, a.never)}
                <span className="hint" style={{ marginLeft: 6 }}>
                  {statusLabel(h.status)} · {Math.round(h.durationMs / 1000)}s
                  {h.tokens !== null ? ` · ${h.tokens} ${a.tokensLabel}` : ''}
                </span>
                {h.error && <span className="hint" style={{ display: 'block', marginLeft: 20 }}>{h.error}</span>}
              </span>
            ))}
          </div>
        )}
      </Modal>
    </div>
  )
}

function AutomationForm({ initial, labels, choices, saving, onCancel, onSave }: {
  initial: AutomationItem | null
  labels: ReturnType<typeof pagesCopy>['automations']
  choices: AutomationChoices
  saving: boolean
  onCancel: () => void
  onSave: (form: FormState) => void
}) {
  const [form, setForm] = useState<FormState>({
    name: initial?.name ?? '',
    prompt: initial?.prompt ?? '',
    schedule: initial?.schedule ?? 'daily',
    cron: initial?.cron ?? '',
    agentId: initial?.agentId ?? choices.agents[0]?.id ?? '',
    kbId: initial?.kbIds[0] ?? choices.kbs[0]?.id ?? '',
  })
  const set = <K extends keyof FormState>(key: K, value: FormState[K]) => setForm((f) => ({ ...f, [key]: value }))
  const schedules: AutomationSchedule[] = ['hourly', 'daily', 'weekly', 'custom']
  const scheduleText = (s: AutomationSchedule) =>
    s === 'hourly' ? labels.scheduleHourly : s === 'daily' ? labels.scheduleDaily : s === 'weekly' ? labels.scheduleWeekly : labels.scheduleCustom

  return (
    <div className="form-stack">
      <div className="field">
        <label htmlFor="auto-name">{labels.nameLabel}</label>
        <input id="auto-name" className="input" autoFocus value={form.name} placeholder={labels.namePh} onChange={(e) => set('name', e.target.value)} />
      </div>
      <div className="field">
        <label htmlFor="auto-prompt">{labels.promptLabel}</label>
        <textarea id="auto-prompt" className="input" rows={3} value={form.prompt} placeholder={labels.promptPh} onChange={(e) => set('prompt', e.target.value)} />
      </div>
      <div className="field">
        <label>{labels.scheduleLabel}</label>
        <div className="meta-inline">
          {schedules.map((s) => (
            <button
              key={s}
              type="button"
              className="chip wizard-chip"
              data-on={form.schedule === s ? '' : undefined}
              aria-pressed={form.schedule === s}
              onClick={() => set('schedule', s)}
            >
              {scheduleText(s)}
            </button>
          ))}
        </div>
      </div>
      {form.schedule === 'custom' && (
        <div className="field">
          <label htmlFor="auto-cron">{labels.cronLabel}</label>
          <input id="auto-cron" className="input" value={form.cron} placeholder={labels.cronPh} onChange={(e) => set('cron', e.target.value)} />
        </div>
      )}
      <div className="field">
        <label htmlFor="auto-agent">{labels.agentLabel}</label>
        <select id="auto-agent" className="input" value={form.agentId} onChange={(e) => set('agentId', e.target.value)}>
          <option value="">{labels.defaultAgent}</option>
          {choices.agents.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
        </select>
      </div>
      <div className="field">
        <label htmlFor="auto-kb">{labels.kbLabel}</label>
        <select id="auto-kb" className="input" value={form.kbId} onChange={(e) => set('kbId', e.target.value)}>
          <option value="">{labels.anyKb}</option>
          {choices.kbs.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
        </select>
      </div>
      <div className="dialog-actions">
        <button className="btn btn-ghost" type="button" onClick={onCancel}>{labels.cancel}</button>
        <button
          className="btn btn-primary"
          type="button"
          disabled={saving || !form.name.trim() || !form.prompt.trim() || (form.schedule === 'custom' && !form.cron.trim())}
          onClick={() => onSave(form)}
        >
          {labels.save}
        </button>
      </div>
    </div>
  )
}
