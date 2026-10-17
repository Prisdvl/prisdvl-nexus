/**
 * 工作流画布的纯前端辅助：节点类型、默认配置、id 生成、最小合法种子图。
 *
 * 契约来源（逐条对应代码，不凭记忆书写）：
 * - 节点类型集合与必填配置：nexus-app/src/main/java/com/lky/nexus/app/workflow/WorkflowGraphValidator.java:25（TYPES）、:101（validateConfig）
 * - IF_ELSE 操作符：.../executor/IfElseNodeExecutor.java:36
 * - KNOWLEDGE_RETRIEVAL 配置键：.../executor/KnowledgeRetrievalNodeExecutor.java:31
 * - 节点 id 规则 `[A-Za-z0-9_-]{1,64}`：WorkflowGraphValidator.java:41
 * - 条件分支必须同时连 true / false：WorkflowGraphValidator.java:79
 * - 运行取版本：DEBUG 取草稿、MANUAL 取已发布：WorkflowRuntimeService.java:63
 */
import type { WorkflowEdge, WorkflowNode } from '@/lib/api'

export type WfNodeType = WorkflowNode['type']

/** 面板顺序即推荐编排顺序：START 开头、END 收尾。 */
export const WF_NODE_TYPES: WfNodeType[] = [
  'START',
  'KNOWLEDGE_RETRIEVAL',
  'LLM',
  'AGENT',
  'IF_ELSE',
  'TEMPLATE',
  'END',
]

/** 与 IfElseNodeExecutor.evaluate 的 switch 完全一致。 */
export const WF_IF_OPERATORS = [
  'EQ',
  'NE',
  'GT',
  'GTE',
  'LT',
  'LTE',
  'IS_TRUE',
  'IS_FALSE',
  'CONTAINS',
  'NOT_CONTAINS',
] as const

export type WfIfOperator = (typeof WF_IF_OPERATORS)[number]

/** 单目操作符：只比较 left，right 不参与（IfElseNodeExecutor:43）。 */
export const WF_UNARY_OPERATORS: WfIfOperator[] = ['IS_TRUE', 'IS_FALSE']

/** 检索模式：SearchMode.parse（KnowledgeRetrievalNodeExecutor:39）。 */
export const WF_SEARCH_MODES = ['hybrid', 'bm25', 'vector'] as const

/** 新节点的默认 config：刚好满足 WorkflowGraphValidator 的必填项，避免一加就报错。 */
export function defaultConfig(type: WfNodeType): Record<string, unknown> {
  switch (type) {
    case 'LLM':
      return { userPrompt: '{{ input.question }}' }
    case 'AGENT':
      return { agentId: '' }
    case 'KNOWLEDGE_RETRIEVAL':
      return { kbIds: [], topK: 5, query: '{{ input.question }}', mode: 'hybrid' }
    case 'TEMPLATE':
      return { template: '{{ input.question }}' }
    case 'IF_ELSE':
      return { operator: 'EQ', left: '{{ input.question }}', right: '' }
    default:
      // START 无必填配置；END 的 template 可选（EndNodeExecutor:21）
      return {}
  }
}

/** 节点 id：后端要求 `[A-Za-z0-9_-]{1,64}` 且图内唯一。 */
export function nextNodeId(nodes: WorkflowNode[], type: WfNodeType): string {
  const used = new Set(nodes.map((n) => n.id))
  const base = type.toLowerCase().replace(/_/g, '-')
  if (!used.has(base)) return base
  let i = 2
  while (used.has(`${base}-${i}`)) i += 1
  return `${base}-${i}`
}

/** 连线 id：后端只要求非空且唯一。 */
export function nextEdgeId(edges: WorkflowEdge[]): string {
  const used = new Set(edges.map((e) => e.id))
  let i = edges.length + 1
  while (used.has(`e${i}`)) i += 1
  return `e${i}`
}

const SLOT_W = 220
const SLOT_H = 120
const ORIGIN_X = 60
const ORIGIN_Y = 60

/** 画布位置：后端存的是可空坐标，缺失时按序号排一个稳定网格。 */
export function positionOf(node: WorkflowNode, index: number): { x: number; y: number } {
  const x = node.position?.x
  const y = node.position?.y
  if (typeof x === 'number' && typeof y === 'number') return { x, y }
  return { x: ORIGIN_X + (index % 4) * SLOT_W, y: ORIGIN_Y + Math.floor(index / 4) * SLOT_H }
}

/** 新节点的落点：接在当前网格之后的第一个空位。 */
export function nextPosition(nodes: WorkflowNode[]): { x: number; y: number } {
  const i = nodes.length
  return { x: ORIGIN_X + (i % 4) * SLOT_W, y: ORIGIN_Y + Math.floor(i / 4) * SLOT_H }
}

/**
 * 新建工作流后的种子图：START → END。
 * 后端 create 只建档不建图（WorkflowDefinitionService:56），空图无法通过校验
 * （必须有且只有一个 START、且至少一个 END，WorkflowGraphValidator:52-59），
 * 所以这里落一张最小合法图，让「创建 → 校验 → 发布 → 运行」当场可走通。
 */
export function seedGraph(): { nodes: WorkflowNode[]; edges: WorkflowEdge[] } {
  return {
    nodes: [
      { id: 'start', type: 'START', version: 1, position: { x: ORIGIN_X, y: 150 }, config: {} },
      { id: 'end', type: 'END', version: 1, position: { x: ORIGIN_X + 660, y: 150 }, config: {} },
    ],
    edges: [{ id: 'e1', source: 'start', target: 'end', sourceHandle: null }],
  }
}

/** 删除节点时必须连带删掉它的连线，否则后端报「连线引用了不存在的节点」。 */
export function withoutNode(
  nodes: WorkflowNode[],
  edges: WorkflowEdge[],
  nodeId: string,
): { nodes: WorkflowNode[]; edges: WorkflowEdge[] } {
  return {
    nodes: nodes.filter((n) => n.id !== nodeId),
    edges: edges.filter((e) => e.source !== nodeId && e.target !== nodeId),
  }
}

/** 客户端只挡三种明显非法输入，其余交给后端校验器，不重复实现它。 */
export function canLink(
  edges: WorkflowEdge[],
  source: string,
  target: string,
  handle: string | null,
): true | 'self' | 'duplicate' {
  if (source === target) return 'self'
  const dup = edges.some(
    (e) => e.source === source && e.target === target && (e.sourceHandle ?? null) === handle,
  )
  return dup ? 'duplicate' : true
}

/** IF_ELSE 的两个端口是否都已连接（后端要求同时存在）。 */
export function branchPortsFilled(edges: WorkflowEdge[], nodeId: string): { t: boolean; f: boolean } {
  let t = false
  let f = false
  for (const e of edges) {
    if (e.source !== nodeId) continue
    if (e.sourceHandle === 'true') t = true
    if (e.sourceHandle === 'false') f = true
  }
  return { t, f }
}

/** 后端运行/节点状态 → data-state（画布与轨迹共用同一套状态词）。 */
export function runState(status: string | undefined): string {
  if (status === 'SUCCEEDED') return 'done'
  if (status === 'FAILED') return 'failed'
  if (status === 'SKIPPED') return 'skipped'
  if (status === 'RUNNING') return 'running'
  if (status === 'PENDING' || status === 'QUEUED') return 'pending'
  return 'idle'
}

/** 运行终态：用于决定是否需要再拉节点轨迹。 */
export function isTerminal(status: string): boolean {
  return status === 'SUCCEEDED' || status === 'FAILED' || status === 'CANCELLED'
}
