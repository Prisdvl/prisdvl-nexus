import client from './client'

/**
 * Agent 评测接口。
 *
 * 问答本身走 {@code /api/chat/stream}（见 {@code api/chat.ts}）：
 * Agent 对话是"会话 + 消息 + 流式"的通用链路，不该为它单开一套接口。
 * 这里只保留评测——它是 Agent 质量的回归手段，与聊天界面无关。
 */
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
