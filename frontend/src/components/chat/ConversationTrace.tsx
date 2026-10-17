import { useMemo, useState } from 'react'
import { Search, X } from 'lucide-react'
import type { ChatMsg, ToolCall } from '@/data'
import { appCopy } from '@/lib/appCopy'
import { pagesCopy } from '@/lib/pagesCopy'
import { useLocale } from '@/lib/locale'
import './ConversationTrace.css'

export interface TraceMeta {
  model?: string
  effort?: string
  startedAt?: number
  totalMs?: number
}

interface Props {
  messages: ChatMsg[]
  meta: Record<string, TraceMeta>
  agent: string
  model?: string
  mode: string
  kbs: string[]
}

type Kind = 'turn' | 'user' | 'assistant' | 'tool'

interface Entry {
  id: string
  kind: Kind
  turn: number
  text?: string
  name?: string
  arg?: string
  status?: ToolCall['status']
}

interface Round {
  turn: number
  input: number
  model: number
  tools: number
}

const fmtMs = (ms: number) => (ms <= 0 ? '—' : ms < 10000 ? `${(ms / 1000).toFixed(1)}s` : `${Math.round(ms / 1000)}s`)

export default function ConversationTrace({ messages, meta, agent, model, mode, kbs }: Props) {
  const { locale } = useLocale()
  const t = pagesCopy(locale).trace
  const common = appCopy(locale).common
  const [query, setQuery] = useState('')

  const { rounds, entries, durationMs, calls } = useMemo(() => {
    const roundsOut: Round[] = []
    const entriesOut: Entry[] = []
    let current: Round | null = null
    let turn = 0
    let duration = 0
    let callCount = 0

    for (const msg of messages) {
      if (msg.role === 'user') {
        turn += 1
        current = { turn, input: 1, model: 0, tools: 0 }
        roundsOut.push(current)
        entriesOut.push({ id: `t-${msg.id}`, kind: 'turn', turn })
        entriesOut.push({ id: `u-${msg.id}`, kind: 'user', turn, text: msg.text })
        continue
      }
      if (!current) {
        turn += 1
        current = { turn, input: 1, model: 0, tools: 0 }
        roundsOut.push(current)
      }
      const info = meta[msg.id]
      const ms = info?.totalMs ?? (info?.startedAt ? Date.now() - info.startedAt : 0)
      duration += ms
      const hasText = Boolean(msg.text?.trim())
      current.model += hasText ? 2.4 : 1
      for (const call of msg.calls ?? []) {
        callCount += 1
        current.tools += 0.8
        entriesOut.push({
          id: `c-${call.id}`,
          kind: 'tool',
          turn: current.turn,
          name: call.name,
          arg: call.argument,
          status: call.status,
        })
      }
      entriesOut.push({
        id: `a-${msg.id}`,
        kind: 'assistant',
        turn: current.turn,
        text: hasText ? msg.text : t.toolOnly,
      })
    }
    return { rounds: roundsOut, entries: entriesOut, durationMs: duration, calls: callCount }
  }, [messages, meta, t])

  const statusText = (s?: ToolCall['status']) =>
    s === 'running' || s === 'idle'
      ? t.statusRunning
      : s === 'awaiting'
        ? t.statusAwaiting
        : s === 'error'
          ? t.statusFailed
          : s === 'timeout'
            ? t.statusTimeout
            : s === 'cancelled'
              ? t.statusCancelled
              : t.statusDone

  const roleLabel = (kind: Kind) =>
    kind === 'user' ? t.roleUser : kind === 'tool' ? t.roleTool : t.roleAssistant

  const q = query.trim().toLowerCase()
  const visible = q
    ? entries.filter((e) => [e.text, e.name, e.arg].filter(Boolean).join(' ').toLowerCase().includes(q))
    : entries

  if (rounds.length === 0) {
    return <div className="trace-empty">{t.empty}</div>
  }

  return (
    <div className="trace-layout">
      <div className="trace-main">
        <div className="trace-head">
          <div className="trace-metrics">
            <span><b>{fmtMs(durationMs)}</b> {t.duration}</span>
            <span><b>{rounds.length}</b> {t.turns}</span>
            <span><b>{calls}</b> {t.calls}</span>
          </div>
          <div className="market-search">
            <Search size={14} aria-hidden="true" />
            <input className="input" value={query} onChange={(e) => setQuery(e.target.value)} placeholder={t.searchPh} aria-label={t.searchAria} />
            {query && (
              <button className="market-search__clear" type="button" aria-label={common.close} onClick={() => setQuery('')}>
                <X size={13} />
              </button>
            )}
          </div>
        </div>

        <div className="trace-strip" aria-hidden="true">
          {([
            { key: 'input', label: t.input },
            { key: 'model', label: t.model },
            { key: 'tools', label: t.tools },
          ] as const).map((row) => (
            <div className="trace-strip__row" key={row.key}>
              <span className="trace-strip__label">{row.label}</span>
              <div className="trace-strip__track">
                {rounds.map((r) => (
                  <span className="trace-strip__seg" data-phase={row.key} style={{ flexGrow: Math.max(0.2, r[row.key]) }} key={r.turn} />
                ))}
              </div>
            </div>
          ))}
        </div>

        <ol className="trace-rail">
          {visible.map((entry) => (
            entry.kind === 'turn' ? (
              <li className="trace-entry trace-entry--turn" key={entry.id}>
                <span className="trace-entry__dot" aria-hidden="true" />
                <span className="trace-entry__turn">{t.turn(entry.turn)}</span>
              </li>
            ) : (
              <li className="trace-entry" data-kind={entry.kind} key={entry.id}>
                <span className="trace-entry__dot" aria-hidden="true" />
                <span className="trace-entry__role" data-kind={entry.kind}>{roleLabel(entry.kind)}</span>
                {entry.kind === 'tool' ? (
                  <span className="trace-entry__body trace-entry__body--mono" title={`${entry.name} ${entry.arg}`}>
                    <b>{entry.name}</b> {entry.arg}
                    <span className="trace-entry__arrow" aria-hidden="true">→</span>
                    <span className="trace-entry__status" data-status={entry.status}>{statusText(entry.status)}</span>
                  </span>
                ) : (
                  <span className="trace-entry__body" title={entry.text}>{entry.text}</span>
                )}
              </li>
            )
          ))}
        </ol>
      </div>

      <aside className="trace-side">
        <h4>{t.overview}</h4>
        <dl className="trace-side__list">
          <div><dt>{t.overviewAgent}</dt><dd>{agent}</dd></div>
          <div><dt>{t.overviewModel}</dt><dd>{model ?? '—'}</dd></div>
          <div><dt>{t.overviewMode}</dt><dd>{mode}</dd></div>
          <div><dt>{t.overviewKb}</dt><dd>{kbs.length > 0 ? kbs.join(' · ') : '—'}</dd></div>
        </dl>
        <h4>{t.legend}</h4>
        <ul className="trace-side__legend">
          <li data-kind="user">{t.roleUser}</li>
          <li data-kind="assistant">{t.roleAssistant}</li>
          <li data-kind="tool">{t.roleTool}</li>
        </ul>
      </aside>
    </div>
  )
}
