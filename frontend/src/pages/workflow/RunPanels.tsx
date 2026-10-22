import { AlertTriangle, CheckCircle2, Circle, RefreshCw } from 'lucide-react'
import type { WorkflowNodeRun, WorkflowRun, WorkflowValidation } from '@/lib/api'
import { runState } from '@/lib/workflowGraph'
import type { WorkflowCopy } from '@/pages/workflow/copy'

interface Props {
  c: WorkflowCopy
  validation: WorkflowValidation | null
  inputsText: string
  onInputsChange: (value: string) => void

  /** 最近一次运行请求的结果：run 有值代表请求成功，runError 有值代表请求失败。 */
  run: WorkflowRun | null
  runError: string

  /** 运行历史（真实接口，分页）。 */
  runs: WorkflowRun[]
  runsTotal: number
  runsPage: number
  runsPages: number
  runsLoading: boolean
  runsError: string
  selectedRunId: string | null
  onSelectRun: (runId: string) => void
  onGoRunsPage: (page: number) => void
  onReloadRuns: () => void

  /** 被选中那次运行的节点轨迹。 */
  nodeRuns: WorkflowNodeRun[]
  nodeRunsLoading: boolean
  traceError: string
  /** 对「当前选中的这次运行」的动作：人工审批 / 从失败节点续跑。 */
  onApprove: (runId: string, decision: 'APPROVE' | 'REJECT') => void
  onRetry: (runId: string) => void
  actionBusy: boolean
}

/** 后端返回的是 LocalDateTime 字符串（无时区），只取时间部分显示，避免 Date 解析引入时区问题。 */
function shortTime(value: string | null | undefined): string {
  if (!value) return '—'
  return value.length >= 19 ? value.slice(11, 19) : value
}

function runTone(status: string): string {
  if (status === 'SUCCEEDED') return 'ok'
  if (status === 'FAILED') return 'bad'
  return 'muted'
}

/**
 * 运行结果常是 {@code {text}}（LLM / TEMPLATE 节点的输出形状）：能直接读的就不甩 JSON。
 * 空串也算「没有可读文本」，交给原始 JSON 展示。
 */
function outputText(output: unknown): string | null {
  if (typeof output === 'string') {
    return output.trim() ? output : null
  }
  if (output && typeof output === 'object' && 'text' in output) {
    const text = (output as { text?: unknown }).text
    if (typeof text === 'string' && text.trim()) {
      return text
    }
  }
  return null
}

/** 画布下方四块：校验结果 / 运行输入与状态 / 运行历史 / 节点轨迹。 */
export default function RunPanels({
  c, validation, inputsText, onInputsChange,
  run, runError,
  runs, runsTotal, runsPage, runsPages, runsLoading, runsError, selectedRunId, onSelectRun, onGoRunsPage, onReloadRuns,
  nodeRuns, nodeRunsLoading, traceError,
  onApprove, onRetry, actionBusy,
}: Props) {
  /* 选中的运行可能不在当前页，因此找不到时不显示「运行失败」提示，只展示轨迹本身 */
  const selectedRun = runs.find((r) => r.id === selectedRunId) ?? null

  return (
    <div className="wf-lower">
      <section className="wf-panel">
        <h2 className="wf-panel-title">{c.validationTitle}</h2>
        {validation === null ? <p className="hint">{c.noValidation}</p> : validation.valid ? (
          <p className="wf-ok"><CheckCircle2 size={14} /> {c.validOk}</p>
        ) : (
          <ul className="wf-errors">
            {validation.errors.map((msg, i) => <li key={i}><AlertTriangle size={13} /> {msg}</li>)}
          </ul>
        )}
      </section>

      <section className="wf-panel">
        <h2 className="wf-panel-title">{c.runTitle}</h2>
        <div className="field">
          <label htmlFor="wf-inputs">{c.inputs}</label>
          <textarea
            id="wf-inputs" className="input" rows={4} spellCheck={false}
            value={inputsText}
            onChange={(e) => onInputsChange(e.target.value)}
          />
          <p className="hint">{c.inputsHint}</p>
        </div>
        {run && (
          <p className="wf-run-meta">
            <span className="wf-chip" data-tone={runTone(run.status)}>{c.statuses[run.status] ?? run.status}</span>
            <span>{c.trigger}: {run.triggerType}</span>
            {run.durationMs != null && <span>{c.duration}: {run.durationMs} ms</span>}
          </p>
        )}
        {/* 运行失败（请求成功返回、运行状态为 FAILED）与请求失败（拿不到 runId）是两回事，分开说 */}
        {run?.status === 'FAILED' && <p className="hint">{c.runFailedIsNotHttp}</p>}
        {runError && (
          <>
            <p className="wf-errors__single"><AlertTriangle size={13} /> {runError}</p>
            <p className="hint">{c.httpFailedNote}</p>
          </>
        )}
        {run?.output != null && (
          <div className="wf-output">
            <h3 className="wf-output__title">{c.outputTitle}</h3>
            {outputText(run.output) !== null ? (
              <>
                <pre className="wf-pre wf-pre--text">{outputText(run.output)}</pre>
                <details className="wf-details">
                  <summary>{c.outputRaw}</summary>
                  <pre className="wf-pre">{JSON.stringify(run.output, null, 2)}</pre>
                </details>
              </>
            ) : (
              <pre className="wf-pre">{JSON.stringify(run.output, null, 2)}</pre>
            )}
          </div>
        )}
      </section>

      <section className="wf-panel">
        <h2 className="wf-panel-title">{c.historyTitle}</h2>
        {runsLoading ? <p className="hint">{c.loading}</p> : runsError ? (
          <>
            <p className="wf-errors__single"><AlertTriangle size={13} /> {c.historyError}</p>
            <p className="hint">{runsError}</p>
            <button className="btn btn-ghost" type="button" onClick={onReloadRuns}><RefreshCw size={14} /> {c.retry}</button>
          </>
        ) : runs.length === 0 ? <p className="hint">{c.historyEmpty}</p> : (
          <>
            <ul className="wf-run-list">
              {runs.map((r) => (
                <li key={r.id}>
                  <button
                    className="wf-run-row"
                    type="button"
                    data-on={selectedRunId === r.id ? '' : undefined}
                    onClick={() => onSelectRun(r.id)}
                  >
                    <span className="wf-chip" data-tone={runTone(r.status)}>{c.statuses[r.status] ?? r.status}</span>
                    <span className="wf-run-row__time">{shortTime(r.startedAt)}</span>
                    <span className="wf-run-row__meta">
                      {r.triggerType}{r.retryOfRunId ? ` ↻ #${r.retryOfRunId}` : ''}{r.durationMs != null ? ` · ${r.durationMs} ms` : ''}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
            <div className="wf-run-page">
              <span className="hint">{runsTotal} {c.historyCount}</span>
              <button className="btn btn-ghost" type="button" onClick={() => onGoRunsPage(runsPage - 1)} disabled={runsPage <= 1}>
                {c.prevPage}
              </button>
              <span className="wf-run-page__now">{runsPage} / {runsPages}</span>
              <button className="btn btn-ghost" type="button" onClick={() => onGoRunsPage(runsPage + 1)} disabled={runsPage >= runsPages}>
                {c.nextPage}
              </button>
            </div>
          </>
        )}
      </section>

      <section className="wf-panel">
        <h2 className="wf-panel-title">{c.traceTitle}</h2>
        {!selectedRunId ? <p className="hint">{c.noRun}</p> : nodeRunsLoading ? <p className="hint">{c.loading}</p> : traceError ? (
          <>
            <p className="wf-errors__single"><AlertTriangle size={13} /> {c.traceError}</p>
            <p className="hint">{traceError}</p>
          </>
        ) : nodeRuns.length === 0 ? <p className="hint">{c.noRun}</p> : (
          <>
            {selectedRun && (
              <p className="wf-run-meta">
                <span className="wf-chip" data-tone={runTone(selectedRun.status)}>{c.statuses[selectedRun.status] ?? selectedRun.status}</span>
                <span>{shortTime(selectedRun.startedAt)}</span>
                {selectedRun.durationMs != null && <span>{c.duration}: {selectedRun.durationMs} ms</span>}
              </p>
            )}
            {/* 动作绑在「当前选中的那次运行」上：挂起中可审批，失败/已取消可续跑 */}
            {selectedRun?.status === 'WAITING_APPROVAL' && (
              <>
                <p className="hint">{c.awaitingApproval}</p>
                <div className="wf-actions">
                  <button className="btn btn-primary" type="button" disabled={actionBusy}
                          onClick={() => onApprove(selectedRun.id, 'APPROVE')}>
                    <CheckCircle2 size={14} /> {c.approve}
                  </button>
                  <button className="btn btn-ghost wf-danger" type="button" disabled={actionBusy}
                          onClick={() => onApprove(selectedRun.id, 'REJECT')}>
                    <AlertTriangle size={14} /> {c.reject}
                  </button>
                </div>
              </>
            )}
            {(selectedRun?.status === 'FAILED' || selectedRun?.status === 'CANCELLED') && (
              <>
                <p className="hint">{c.retryHint}</p>
                <div className="wf-actions">
                  <button className="btn btn-ghost" type="button" disabled={actionBusy}
                          onClick={() => onRetry(selectedRun.id)}>
                    <RefreshCw size={14} /> {c.retryRun}
                  </button>
                </div>
              </>
            )}
            <ol className="wf-log">
              {nodeRuns.map((nr) => (
                <li key={nr.id} data-state={runState(nr.status)}>
                  {nr.status === 'SUCCEEDED'
                    ? <CheckCircle2 size={13} aria-hidden="true" />
                    : nr.status === 'FAILED'
                      ? <AlertTriangle size={13} aria-hidden="true" />
                      : <Circle size={11} aria-hidden="true" />}
                  <span className="wf-log__id">{nr.nodeId}</span>
                  <span className="wf-log__meta">
                    {c.statuses[nr.status] ?? nr.status}{nr.latencyMs != null ? ` · ${nr.latencyMs} ms` : ''}
                  </span>
                  {nr.errorMessage && <em className="wf-log__err">{nr.errorMessage}</em>}
                </li>
              ))}
            </ol>
            {selectedRun?.status === 'FAILED' && <p className="hint">{c.runFailedIsNotHttp}</p>}
          </>
        )}
      </section>
    </div>
  )
}
