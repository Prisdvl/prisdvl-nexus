import { Link2, Trash2 } from 'lucide-react'
import type { BackendAgent, KnowledgeBase, WorkflowEdge, WorkflowNode } from '@/lib/api'
import {
  WF_IF_OPERATORS, WF_SEARCH_MODES, WF_UNARY_OPERATORS,
  branchPortsFilled, type WfNodeType,
} from '@/lib/workflowGraph'
import type { WorkflowCopy } from '@/pages/workflow/copy'

interface Props {
  c: WorkflowCopy
  node: WorkflowNode | null
  edges: WorkflowEdge[]
  kbs: KnowledgeBase[]
  agents: BackendAgent[]
  linking: boolean
  typeTitle: Record<WfNodeType, { title: string; desc: string }>
  onPatchConfig: (id: string, patch: Record<string, unknown>) => void
  onRemoveNode: (id: string) => void
  onRemoveEdge: (id: string) => void
  onStartLink: (source: string, handle: string | null) => void
}

/** 右侧检查器：逐类型只暴露后端执行器真正读取的配置键。 */
export default function Inspector({
  c, node, edges, kbs, agents, linking, typeTitle, onPatchConfig, onRemoveNode, onRemoveEdge, onStartLink,
}: Props) {
  if (!node) return <aside className="wf-inspector"><p className="hint">{c.pickHint}</p></aside>

  const cfg = node.config ?? {}
  const outgoing = edges.filter((e) => e.source === node.id)
  const ports = branchPortsFilled(edges, node.id)
  const unary = WF_UNARY_OPERATORS.includes(cfg.operator as never)

  return (
    <aside className="wf-inspector">
      <h2 className="wf-panel-title">{c.inspector}</h2>

      <div className="field">
        <label htmlFor="wf-node-id">{c.fields.nodeId}</label>
        <input id="wf-node-id" className="input" value={node.id} readOnly />
      </div>
      <div className="field">
        <label htmlFor="wf-node-type">{c.fields.type}</label>
        <input id="wf-node-type" className="input" value={typeTitle[node.type].title} readOnly />
      </div>

      {node.type === 'LLM' && (
        <>
          <div className="field">
            <label htmlFor="wf-prompt">{c.fields.userPrompt}</label>
            <textarea
              id="wf-prompt" className="input" rows={3}
              value={String(cfg.userPrompt ?? '')}
              onChange={(e) => onPatchConfig(node.id, { userPrompt: e.target.value })}
            />
          </div>
          <div className="field">
            <label htmlFor="wf-temp">{c.fields.temperature}</label>
            <input
              id="wf-temp" className="input" type="number" step="0.1" min="0" max="2"
              value={String(cfg.temperature ?? '')}
              onChange={(e) => onPatchConfig(node.id, { temperature: e.target.value === '' ? undefined : Number(e.target.value) })}
            />
          </div>
        </>
      )}

      {node.type === 'AGENT' && (
        <div className="field">
          <label htmlFor="wf-agent">{c.fields.agent}</label>
          <select
            id="wf-agent" className="input"
            value={String(cfg.agentId ?? '')}
            onChange={(e) => onPatchConfig(node.id, { agentId: e.target.value })}
          >
            <option value="">{agents.length === 0 ? c.agentEmpty : c.pickNone}</option>
            {agents.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
          </select>
        </div>
      )}

      {node.type === 'KNOWLEDGE_RETRIEVAL' && (
        <>
          <div className="field">
            <label htmlFor="wf-kb">{c.fields.kb}</label>
            {kbs.length === 0 ? <p className="hint">{c.kbEmpty}</p> : (
              <div className="wf-multi">
                {kbs.map((kb) => {
                  const ids = Array.isArray(cfg.kbIds) ? cfg.kbIds.map(String) : []
                  const on = ids.includes(kb.id)
                  return (
                    <label className="wf-multi__row" key={kb.id}>
                      <input
                        type="checkbox"
                        checked={on}
                        onChange={() => onPatchConfig(node.id, { kbIds: on ? ids.filter((x) => x !== kb.id) : [...ids, kb.id] })}
                      />
                      <span>{kb.name}</span>
                    </label>
                  )
                })}
              </div>
            )}
          </div>
          <div className="field">
            <label htmlFor="wf-query">{c.fields.query}</label>
            <input
              id="wf-query" className="input" value={String(cfg.query ?? '')}
              onChange={(e) => onPatchConfig(node.id, { query: e.target.value })}
            />
          </div>
          <div className="field">
            <label htmlFor="wf-topk">{c.fields.topK}</label>
            <input
              id="wf-topk" className="input" type="number" min="1" max="50"
              value={String(cfg.topK ?? 5)}
              onChange={(e) => onPatchConfig(node.id, { topK: Number(e.target.value) })}
            />
          </div>
          <div className="field">
            <label htmlFor="wf-mode">{c.fields.mode}</label>
            <select
              id="wf-mode" className="input" value={String(cfg.mode ?? 'hybrid')}
              onChange={(e) => onPatchConfig(node.id, { mode: e.target.value })}
            >
              {WF_SEARCH_MODES.map((m) => <option key={m} value={m}>{m}</option>)}
            </select>
          </div>
        </>
      )}

      {node.type === 'IF_ELSE' && (
        <>
          <div className="field">
            <label htmlFor="wf-op">{c.fields.operator}</label>
            <select
              id="wf-op" className="input" value={String(cfg.operator ?? 'EQ')}
              onChange={(e) => onPatchConfig(node.id, { operator: e.target.value })}
            >
              {WF_IF_OPERATORS.map((op) => <option key={op} value={op}>{op}</option>)}
            </select>
          </div>
          <div className="field">
            <label htmlFor="wf-left">{c.fields.left}</label>
            <input
              id="wf-left" className="input" value={String(cfg.left ?? '')}
              onChange={(e) => onPatchConfig(node.id, { left: e.target.value })}
            />
          </div>
          {!unary && (
            <div className="field">
              <label htmlFor="wf-right">{c.fields.right}</label>
              <input
                id="wf-right" className="input" value={String(cfg.right ?? '')}
                onChange={(e) => onPatchConfig(node.id, { right: e.target.value })}
              />
            </div>
          )}
        </>
      )}

      {(node.type === 'TEMPLATE' || node.type === 'END') && (
        <div className="field">
          <label htmlFor="wf-tpl">{node.type === 'END' ? c.fields.endTemplate : c.fields.template}</label>
          <textarea
            id="wf-tpl" className="input" rows={3} value={String(cfg.template ?? '')}
            onChange={(e) => onPatchConfig(node.id, { template: e.target.value })}
          />
        </div>
      )}

      {node.type === 'START' && <p className="hint">{c.fields.noConfig}</p>}

      <div className="wf-actions">
        {(node.type === 'IF_ELSE' ? (['true', 'false'] as const) : ([null] as const)).map((handle) => (
          <button
            className="btn btn-ghost"
            type="button"
            key={String(handle)}
            onClick={() => onStartLink(node.id, handle)}
            disabled={linking}
          >
            <Link2 size={14} /> {handle === 'true' ? c.linkTrue : handle === 'false' ? c.linkFalse : c.link}
          </button>
        ))}
        <button className="btn btn-ghost wf-danger" type="button" onClick={() => onRemoveNode(node.id)} title={c.removeNodeConfirm}>
          <Trash2 size={14} /> {c.deleteNode}
        </button>
      </div>

      <h2 className="wf-panel-title" style={{ marginTop: 14 }}>{c.edgesTitle}</h2>
      {outgoing.length === 0 ? <p className="hint">{c.noEdges}</p> : (
        <ul className="wf-edge-list">
          {outgoing.map((e) => (
            <li key={e.id} className="wf-edge-row">
              <span className="wf-edge-row__text">
                {e.sourceHandle ? <em data-branch={e.sourceHandle}>{e.sourceHandle}</em> : null}
                {` → ${e.target}`}
              </span>
              <button className="plugin-icon-btn" type="button" aria-label={c.deleteEdge} onClick={() => onRemoveEdge(e.id)}>
                <Trash2 size={13} />
              </button>
            </li>
          ))}
        </ul>
      )}
      {node.type === 'IF_ELSE' && !(ports.t && ports.f) && <p className="hint wf-warn">{c.branchBothHint}</p>}
    </aside>
  )
}
