import { useMemo, useState } from 'react'
import {
  CalendarClock, CheckCircle2, Clock, History, Pencil, Play, Plus, Search,
  Trash2, X, XCircle,
} from 'lucide-react'
import { motion, useReducedMotion } from 'motion/react'
import Modal from '@/components/Modal'
import { StateEmpty } from '@/components/Skeleton'
import { toast } from '@/lib/toast'
import { appCopy } from '@/lib/appCopy'
import { pagesCopy } from '@/lib/pagesCopy'
import { useLocale } from '@/lib/locale'
import { AUTOMATIONS, scheduleLabelKey, type Automation, type AutomationSchedule, type AutomationStatus } from '@/lib/automations'
import '@/styles/plugins.css'
import '@/styles/ops.css'

type Tab = 'all' | 'enabled' | 'paused'

const STATUS_ICON: Record<AutomationStatus, typeof Clock> = {
  done: CheckCircle2,
  failed: XCircle,
  queued: Clock,
}

interface FormState {
  name: string
  prompt: string
  schedule: AutomationSchedule
  agent: string
  kb: string
  notify: boolean
}

export default function AutomationsPage() {
  const { locale } = useLocale()
  const common = appCopy(locale).common
  const a = pagesCopy(locale).automations
  const reduce = useReducedMotion()
  const [items, setItems] = useState<Automation[]>(AUTOMATIONS)
  const [tab, setTab] = useState<Tab>('all')
  const [query, setQuery] = useState('')
  const [editing, setEditing] = useState<Automation | null>(null)
  const [creating, setCreating] = useState(false)
  const [historyOf, setHistoryOf] = useState<Automation | null>(null)

  const list = useMemo(() => {
    const q = query.trim().toLowerCase()
    return items.filter((item) => {
      if (tab === 'enabled' && !item.enabled) return false
      if (tab === 'paused' && item.enabled) return false
      if (!q) return true
      return [item.name, item.prompt, item.agent, item.kb].join(' ').toLowerCase().includes(q)
    })
  }, [items, query, tab])

  const scheduleLabel = (s: AutomationSchedule) => a[scheduleLabelKey(s) as 'scheduleHourly' | 'scheduleDaily' | 'scheduleWeekly' | 'scheduleCustom']
  const statusLabel = (s: AutomationStatus) => (s === 'done' ? a.statusDone : s === 'failed' ? a.statusFailed : a.statusQueued)

  const toggle = (id: string) => {
    setItems((prev) => prev.map((item) => item.id === id ? { ...item, enabled: !item.enabled, nextRun: item.enabled ? '—' : item.nextRun } : item))
  }

  const runNow = (item: Automation) => {
    toast.success(a.queuedTitle, a.queuedBody(item.name))
    setItems((prev) => prev.map((x) => x.id === item.id ? { ...x, lastRun: item.nextRun === '—' ? a.never : item.nextRun, lastStatus: 'queued' } : x))
  }

  const remove = (item: Automation) => {
    if (!window.confirm(a.deleteConfirm(item.name))) return
    setItems((prev) => prev.filter((x) => x.id !== item.id))
    toast.success(a.deletedTitle, item.name)
  }

  const save = (form: FormState) => {
    const cron = form.schedule === 'hourly' ? '0 * * * *' : form.schedule === 'daily' ? '0 9 * * *' : form.schedule === 'weekly' ? '0 10 * * 1' : '0 9 * * *'
    if (editing) {
      setItems((prev) => prev.map((x) => x.id === editing.id
        ? { ...x, name: form.name, prompt: form.prompt, schedule: form.schedule, cron, agent: form.agent, kb: form.kb }
        : x))
      toast.success(a.updatedTitle, form.name)
    } else {
      const item: Automation = {
        id: `auto-${Date.now()}`,
        name: form.name,
        prompt: form.prompt,
        schedule: form.schedule,
        cron,
        agent: form.agent,
        kb: form.kb,
        enabled: true,
        lastRun: a.never,
        nextRun: form.schedule === 'hourly' ? '1 小时内' : '按计划',
        lastStatus: 'queued',
        history: [],
      }
      setItems((prev) => [item, ...prev])
      toast.success(a.createdTitle, form.name)
    }
    setCreating(false)
    setEditing(null)
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
        {list.length === 0 ? (
          <StateEmpty
            icon={<CalendarClock size={20} />}
            title={query ? a.noResults(query) : a.emptyTitle}
            desc={a.emptyDesc}
            action={<button className="btn btn-primary btn-sm" type="button" onClick={() => setCreating(true)}><Plus size={14} /> {a.newAutomation}</button>}
          />
        ) : (
          <div className="ops-list">
            {list.map((item) => {
              const Icon = STATUS_ICON[item.lastStatus]
              return (
                <div className="ops-row ops-row--static" key={item.id}>
                  <button
                    className="auto-switch"
                    type="button"
                    role="switch"
                    aria-checked={item.enabled}
                    aria-label={`${item.name} ${item.enabled ? a.enabled : a.disabled}`}
                    onClick={() => toggle(item.id)}
                  >
                    <span className="auto-switch__knob" />
                  </button>
                  <span className="ops-row__main">
                    <span className="ops-row__title">{item.name}</span>
                    <span className="ops-row__prompt">{item.prompt}</span>
                    <span className="meta-inline">
                      <span className="meta-inline__item"><CalendarClock size={13} aria-hidden="true" /> {scheduleLabel(item.schedule)}</span>
                      <span className="meta-inline__item">{item.agent}</span>
                      <span className="meta-inline__item">{item.kb}</span>
                    </span>
                  </span>
                  <span className="ops-row__runs">
                    <span className="meta-inline__item"><Icon size={13} aria-hidden="true" /> {statusLabel(item.lastStatus)}</span>
                    <span className="ops-row__time">{a.lastRun} {item.lastRun} · {a.nextRun} {item.enabled ? item.nextRun : a.never}</span>
                  </span>
                  <span className="ops-row__actions">
                    <button className="plugin-icon-btn" type="button" title={a.runNow} aria-label={`${a.runNow}: ${item.name}`} onClick={() => runNow(item)}><Play size={15} /></button>
                    <button className="plugin-icon-btn" type="button" title={a.history} aria-label={`${a.history}: ${item.name}`} onClick={() => setHistoryOf(item)}><History size={15} /></button>
                    <button className="plugin-icon-btn" type="button" title={a.edit} aria-label={`${a.edit}: ${item.name}`} onClick={() => { setEditing(item); setCreating(false) }}><Pencil size={15} /></button>
                    <button className="plugin-icon-btn plugin-icon-btn--danger" type="button" title={a.delete} aria-label={`${a.delete}: ${item.name}`} onClick={() => remove(item)}><Trash2 size={15} /></button>
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
        onClose={() => { setCreating(false); setEditing(null) }}
      >
        <AutomationForm
          initial={editing}
          labels={a}
          onCancel={() => { setCreating(false); setEditing(null) }}
          onSave={save}
        />
      </Modal>

      <Modal
        open={historyOf !== null}
        title={`${a.historyTitle} · ${historyOf?.name ?? ''}`}
        width={520}
        onClose={() => setHistoryOf(null)}
      >
        {historyOf && (
          historyOf.history.length === 0
            ? <p className="hint">{a.historyEmpty}</p>
            : (
              <div className="meta-list">
                {historyOf.history.map((h) => (
                  <span className="meta-list__row" key={h.id}>
                    {h.status === 'done' ? <CheckCircle2 size={14} aria-hidden="true" /> : h.status === 'failed' ? <XCircle size={14} aria-hidden="true" /> : <Clock size={14} aria-hidden="true" />}
                    {h.at}
                    <span className="hint" style={{ marginLeft: 6 }}>{statusLabel(h.status)} · {Math.round(h.durationMs / 1000)}s</span>
                  </span>
                ))}
              </div>
            )
        )}
      </Modal>
    </div>
  )
}

function AutomationForm({ initial, labels, onCancel, onSave }: {
  initial: Automation | null
  labels: ReturnType<typeof pagesCopy>['automations']
  onCancel: () => void
  onSave: (form: FormState) => void
}) {
  const [form, setForm] = useState<FormState>({
    name: initial?.name ?? '',
    prompt: initial?.prompt ?? '',
    schedule: initial?.schedule ?? 'daily',
    agent: initial?.agent ?? '默认助手',
    kb: initial?.kb ?? '我的 Java 笔记',
    notify: true,
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
              className={`chip wizard-chip${form.schedule === s ? '' : ''}`}
              data-on={form.schedule === s ? '' : undefined}
              aria-pressed={form.schedule === s}
              onClick={() => set('schedule', s)}
            >
              {scheduleText(s)}
            </button>
          ))}
        </div>
      </div>
      <div className="field">
        <label htmlFor="auto-agent">{labels.agentLabel}</label>
        <select id="auto-agent" className="input" value={form.agent} onChange={(e) => set('agent', e.target.value)}>
          {['默认助手', '面试助手', 'Java 代码审查员', '论文共读助手', '竞品情报员'].map((x) => <option key={x}>{x}</option>)}
        </select>
      </div>
      <div className="field">
        <label htmlFor="auto-kb">{labels.kbLabel}</label>
        <select id="auto-kb" className="input" value={form.kb} onChange={(e) => set('kb', e.target.value)}>
          {['我的 Java 笔记', '面试八股合集', '软技能与项目', 'Redis 源码阅读'].map((x) => <option key={x}>{x}</option>)}
        </select>
      </div>
      <div className="field">
        <label className="auto-check">
          <input type="checkbox" checked={form.notify} onChange={(e) => set('notify', e.target.checked)} />
          <span>{labels.notifyLabel}</span>
        </label>
        <p className="hint">{labels.notifyHint}</p>
      </div>
      <div className="dialog-actions">
        <button className="btn btn-ghost" type="button" onClick={onCancel}>{labels.cancel}</button>
        <button className="btn btn-primary" type="button" disabled={!form.name.trim() || !form.prompt.trim()} onClick={() => onSave(form)}>{labels.save}</button>
      </div>
    </div>
  )
}
