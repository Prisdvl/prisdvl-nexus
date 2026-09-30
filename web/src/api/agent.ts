import client from './client'

/** 一次工具调用痕迹（"用户可控"的透明性：每一步做了什么都要看得见）。 */
export interface ToolCallRecord {
  tool: string
  input: string
  resultSummary: string
}

export interface AgentAnswer {
  answer: string
  toolCalls: ToolCallRecord[]
  usedTools: boolean
}

/**
 * 问 Agent：模型先决定是否检索知识库（工具循环），再基于结果回答。
 * 工具调用痕迹随回答返回，前端把它们渲染成步骤条。
 */
export function askAgent(question: string) {
  return client.post<never, AgentAnswer>('/agent/ask', { question })
}

/** 评测用例：问题 + 预期出现的要点（笔记中会原文出现的术语）。 */
export interface EvalCase {
  question: string
  expectedPoints: string[]
}

export interface EvalResult {
  testCase: EvalCase
  answer: string
  missingPoints: string[]
  citationsValid: boolean
  citationCount: number
  pointRecall: number
}

export interface EvalReport {
  results: EvalResult[]
  avgPointRecall: number
  passed: number
}

/** 默认评测集（演示用；接口支持传入任意用例）。 */
export const DEFAULT_EVAL_CASES: EvalCase[] = [
  { question: '我的笔记里缓存击穿怎么解决？', expectedPoints: ['SETNX', '逻辑过期'] }
]

/** 跑评测：对每个用例走一遍 Agent，报告要点命中率与引用有效性。 */
export function runEval(kbId: string, cases: EvalCase[]) {
  return client.post<never, EvalReport>(`/eval/kb/${kbId}`, { cases })
}